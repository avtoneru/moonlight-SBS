package com.limelight;


import com.limelight.binding.PlatformBinding;
import com.limelight.binding.input.InputHandler;
import com.limelight.binding.video.ConfigurableDecoderRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.http.NvApp;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GameGestures;
import com.limelight.ui.MoonlightConnectionListener;
import com.limelight.ui.AbstractUiConnectionListener;
import com.limelight.utils.Dialog;
import com.limelight.utils.SpinnerDialog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.util.Locale;


public class Game extends Activity implements SurfaceHolder.Callback,
    OnSystemUiVisibilityChangeListener, GameGestures, View.OnGenericMotionListener, View.OnTouchListener
{
    private PreferenceConfiguration prefConfig;

    private NvConnection conn;
    private boolean connecting;
    private ConfigurableDecoderRenderer decoderRenderer;
    private InputHandler inputHandler;
    private AbstractUiConnectionListener connectionListener;

    private SurfaceView surfaceView;
    private boolean surfaceIsCreated;

    private WifiManager.WifiLock wifiLock;

    private int drFlags = 0;

    public static final String EXTRA_HOST = "Host";
    public static final String EXTRA_APP_NAME = "AppName";
    public static final String EXTRA_APP_ID = "AppId";
    public static final String EXTRA_UNIQUEID = "UniqueId";
    public static final String EXTRA_STREAMING_REMOTE = "Remote";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String locale = PreferenceConfiguration.readPreferences(this).language;
        if (!locale.equals(PreferenceConfiguration.DEFAULT_LANGUAGE)) {
            Configuration config = new Configuration(getResources().getConfiguration());
            config.locale = new Locale(locale);
            getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        }

        // We don't want a title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Full-screen and don't let the display go off
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // If we're going to use immersive mode, we want to have
        // the entire screen
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

            getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        }

        // Listen for UI visibility events
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(this);

        // Change volume button behavior
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Inflate the content
        setContentView(R.layout.activity_game);

        // Make sure Wi-Fi is fully powered up
        WifiManager wifiMgr = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        wifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Limelight");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();

        // Listen for events on the game surface
        surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceView.setOnGenericMotionListener(this);
        surfaceView.setOnTouchListener(this);
        surfaceView.getHolder().addCallback(this);

        // Perform intent handling
        handleNormalLaunchIntent(getIntent());
    }

    // Required to initialize prefConfig and connectionListener
    private void handleNormalLaunchIntent(Intent intent) {
        // Read the stream preferences
        prefConfig = PreferenceConfiguration.readPreferences(this);

        // Initialize the connection listener
        connectionListener = new MoonlightConnectionListener(this, prefConfig);

        // Warn the user if they're on a metered connection
        checkDataConnection();

        // Get the parameters from the launch intent
        String host = intent.getStringExtra(EXTRA_HOST);
        String appName = intent.getStringExtra(EXTRA_APP_NAME);
        int appId = intent.getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
        String uniqueId = intent.getStringExtra(EXTRA_UNIQUEID);
        boolean remote = intent.getBooleanExtra(EXTRA_STREAMING_REMOTE, false);

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish();
            return;
        }

        // Begin the connection process
        startStreaming(host, new NvApp(appName, appId), uniqueId, remote);
    }

    private void startStreaming(String host, NvApp app, String uniqueId, boolean remote) {
        // Initialize decoder flags
        switch (prefConfig.decoder) {
            case PreferenceConfiguration.FORCE_SOFTWARE_DECODER:
                drFlags |= VideoDecoderRenderer.FLAG_FORCE_SOFTWARE_DECODING;
                break;
            case PreferenceConfiguration.AUTOSELECT_DECODER:
                break;
            case PreferenceConfiguration.FORCE_HARDWARE_DECODER:
                drFlags |= VideoDecoderRenderer.FLAG_FORCE_HARDWARE_DECODING;
                break;
        }

        if (prefConfig.stretchVideo) {
            drFlags |= VideoDecoderRenderer.FLAG_FILL_SCREEN;
        }

        // Create the video decoder
        decoderRenderer = new ConfigurableDecoderRenderer();
        decoderRenderer.initializeWithFlags(drFlags);

        StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(prefConfig.width, prefConfig.height)
                .setRefreshRate(prefConfig.fps)
                .setApp(app)
                .setBitrate(prefConfig.bitrate * 1000)
                .setEnableSops(prefConfig.enableSops)
                .enableAdaptiveResolution((decoderRenderer.getCapabilities() &
                        VideoDecoderRenderer.CAPABILITY_ADAPTIVE_RESOLUTION) != 0)
                .enableLocalAudioPlayback(prefConfig.playHostAudio)
                .setMaxPacketSize(remote ? 1024 : 1292)
                .setRemote(remote)
                .build();

        // Initialize the connection
        conn = new NvConnection(host, uniqueId, connectionListener, config, PlatformBinding.getCryptoProvider(this));

        inputHandler = new InputHandler(conn, this, prefConfig, this);
        inputHandler.start();

        SurfaceHolder sh = surfaceView.getHolder();
        if (prefConfig.stretchVideo || !decoderRenderer.isHardwareAccelerated()) {
            // Set the surface to the size of the video
            sh.setFixedSize(config.getWidth(), config.getHeight());
        }

        if (surfaceIsCreated) {
            // The surface already exists at this point. We'll invoke the callback again
            // synthetically to cause streaming to start
            surfaceCreated(sh);
        }
        else {
            // Otherwise, the surface hasn't been created so the callback will happen on its own
        }
    }

    private void resizeSurfaceWithAspectRatio(SurfaceView sv, double vidWidth, double vidHeight)
    {
        // Get the visible width of the activity
        double visibleWidth = getWindow().getDecorView().getWidth();

        ViewGroup.LayoutParams lp = sv.getLayoutParams();

        // Calculate the new size of the SurfaceView
        lp.width = (int) visibleWidth;
        lp.height = (int) ((vidHeight / vidWidth) * visibleWidth);

        // Apply the size change
        sv.setLayoutParams(lp);
    }

    private void checkDataConnection()
    {
        ConnectivityManager mgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (mgr.isActiveNetworkMetered()) {
            connectionListener.displayTransientMessage(getResources().getString(R.string.conn_metered));
        }
    }

    private final Runnable hideSystemUi = new Runnable() {
            @Override
            public void run() {
                // Use immersive mode on 4.4+ or standard low profile on previous builds
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    Game.this.getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
                else {
                    Game.this.getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LOW_PROFILE);
                }
            }
    };

    // This is called by the connection listener
    public void stopConnection() {
        // Stop the input handler to release inputs
        inputHandler.stop();

        if (connecting || connectionListener.getConnectionComplete()) {
            connecting = false;
            connectionListener.setConnectionComplete(false);
            conn.stop();
        }
    }

    // This is called by the connection listener
    public void hideSystemUi(int delay) {
        Handler h = getWindow().getDecorView().getHandler();
        if (h != null) {
            h.removeCallbacks(hideSystemUi);
            h.postDelayed(hideSystemUi, delay);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        connectionListener.setExpectingTermination(true);
        stopConnection();

        if (!prefConfig.suppressLatencyToast) {
            int averageEndToEndLat = decoderRenderer.getAverageEndToEndLatency();
            int averageDecoderLat = decoderRenderer.getAverageDecoderLatency();
            String message = null;
            if (averageEndToEndLat > 0) {
                message = getResources().getString(R.string.conn_client_latency)+" "+averageEndToEndLat+" ms";
                if (averageDecoderLat > 0) {
                    message += " ("+getResources().getString(R.string.conn_client_latency_hw)+" "+averageDecoderLat+" ms)";
                }
            }
            else if (averageDecoderLat > 0) {
                message = getResources().getString(R.string.conn_hardware_latency)+" "+averageDecoderLat+" ms";
            }

            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        }

        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        wifiLock.release();
    }

    @Override
    public void showKeyboard() {
        LimeLog.info("Showing keyboard overlay");
        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceIsCreated = true;

        if (conn != null && !connectionListener.getConnectionComplete() && !connecting) {
            connecting = true;

            // Resize the surface to match the aspect ratio of the video
            // This must be done after the surface is created.
            if (!prefConfig.stretchVideo && decoderRenderer.isHardwareAccelerated()) {
                resizeSurfaceWithAspectRatio((SurfaceView) findViewById(R.id.surfaceView),
                        prefConfig.width, prefConfig.height);
            }

            conn.start(PlatformBinding.getDeviceName(), holder, drFlags,
                    PlatformBinding.getAudioRenderer(), decoderRenderer);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceIsCreated = false;

        if (connectionListener.getConnectionComplete()) {
            stopConnection();
        }
    }

    @Override
    public void onSystemUiVisibilityChange(int visibility) {
        // Don't do anything if we're not connected
        if (!connectionListener.getConnectionComplete()) {
            return;
        }

        // This flag is set for all devices
        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
            hideSystemUi(2000);
        }
        // This flag is only set on 4.4+
        else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT &&
                 (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
            hideSystemUi(2000);
        }
        // This flag is only set before 4.4+
        else if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.KITKAT &&
                 (visibility & View.SYSTEM_UI_FLAG_LOW_PROFILE) == 0) {
            hideSystemUi(2000);
        }
    }

    @Override
    public boolean onGenericMotion(View v, MotionEvent event) {
        return inputHandler.handleMotionEvent(event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return inputHandler.handleMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return inputHandler.handleMotionEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return inputHandler.handleMotionEvent(event) || super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return inputHandler.handleKeyDown(keyCode, event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return inputHandler.handleKeyUp(keyCode, event) || super.onKeyUp(keyCode, event);
    }
}
