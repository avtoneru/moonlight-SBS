package com.limelight;


import com.limelight.binding.PlatformBinding;
import com.limelight.binding.input.InputHandler;
import com.limelight.binding.video.ConfigurableDecoderRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.http.NvApp;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GameGestures;
import com.limelight.utils.Dialog;
import com.limelight.utils.SpinnerDialog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
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


public class Game extends Activity implements SurfaceHolder.Callback, NvConnectionListener,
    OnSystemUiVisibilityChangeListener, GameGestures, View.OnGenericMotionListener, View.OnTouchListener
{
    private PreferenceConfiguration prefConfig;

    private NvConnection conn;
    private SpinnerDialog spinner;
    private boolean displayedFailureDialog = false;
    private boolean connecting = false;
    private boolean connected = false;

    private ConfigurableDecoderRenderer decoderRenderer;
    private InputHandler inputHandler;

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

        // Start the spinner
        spinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.conn_establishing_title),
                getResources().getString(R.string.conn_establishing_msg), true);

        // Read the stream preferences
        prefConfig = PreferenceConfiguration.readPreferences(this);
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

        // Listen for events on the game surface
        SurfaceView sv = (SurfaceView) findViewById(R.id.surfaceView);
        sv.setOnGenericMotionListener(this);
        sv.setOnTouchListener(this);

        // Warn the user if they're on a metered connection
        checkDataConnection();

        // Make sure Wi-Fi is fully powered up
        WifiManager wifiMgr = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        wifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Limelight");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();

        String host = Game.this.getIntent().getStringExtra(EXTRA_HOST);
        String appName = Game.this.getIntent().getStringExtra(EXTRA_APP_NAME);
        int appId = Game.this.getIntent().getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
        String uniqueId = Game.this.getIntent().getStringExtra(EXTRA_UNIQUEID);
        boolean remote = Game.this.getIntent().getBooleanExtra(EXTRA_STREAMING_REMOTE, false);

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish();
            return;
        }

        decoderRenderer = new ConfigurableDecoderRenderer();
        decoderRenderer.initializeWithFlags(drFlags);
        
        StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(prefConfig.width, prefConfig.height)
                .setRefreshRate(prefConfig.fps)
                .setApp(new NvApp(appName, appId))
                .setBitrate(prefConfig.bitrate * 1000)
                .setEnableSops(prefConfig.enableSops)
                .enableAdaptiveResolution((decoderRenderer.getCapabilities() &
                        VideoDecoderRenderer.CAPABILITY_ADAPTIVE_RESOLUTION) != 0)
                .enableLocalAudioPlayback(prefConfig.playHostAudio)
                .setMaxPacketSize(remote ? 1024 : 1292)
                .setRemote(remote)
                .build();

        // Initialize the connection
        conn = new NvConnection(host, uniqueId, Game.this, config, PlatformBinding.getCryptoProvider(this));

        inputHandler = new InputHandler(conn, this, prefConfig, this);
        inputHandler.start();

        SurfaceHolder sh = sv.getHolder();
        if (prefConfig.stretchVideo || !decoderRenderer.isHardwareAccelerated()) {
            // Set the surface to the size of the video
            sh.setFixedSize(prefConfig.width, prefConfig.height);
        }

        // The connection will be started when the surface gets created
        sh.addCallback(this);
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
            displayTransientMessage(getResources().getString(R.string.conn_metered));
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

    private void hideSystemUi(int delay) {
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

        displayedFailureDialog = true;
        stopConnection();

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
    public void stageStarting(Stage stage) {
        if (spinner != null) {
            spinner.setMessage(getResources().getString(R.string.conn_starting) + " " + stage.getName());
        }
    }

    @Override
    public void stageComplete(Stage stage) {
    }

    private void stopConnection() {
        // Stop the input handler to release inputs
        inputHandler.stop();

        if (connecting || connected) {
            connecting = connected = false;
            conn.stop();
        }
    }

    @Override
    public void stageFailed(Stage stage) {
        if (spinner != null) {
            spinner.dismiss();
            spinner = null;
        }

        if (!displayedFailureDialog) {
            displayedFailureDialog = true;
            stopConnection();
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
                    getResources().getString(R.string.conn_error_msg) + " " + stage.getName(), true);
        }
    }

    @Override
    public void connectionTerminated(Exception e) {
        if (!displayedFailureDialog) {
            displayedFailureDialog = true;
            e.printStackTrace();

            stopConnection();
            Dialog.displayDialog(this, getResources().getString(R.string.conn_terminated_title),
                    getResources().getString(R.string.conn_terminated_msg), true);
        }
    }

    @Override
    public void connectionStarted() {
        if (spinner != null) {
            spinner.dismiss();
            spinner = null;
        }

        connecting = false;
        connected = true;

        hideSystemUi(1000);
    }

    @Override
    public void displayMessage(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void displayTransientMessage(final String message) {
        if (!prefConfig.disableWarnings) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!connected && !connecting) {
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
        if (connected) {
            stopConnection();
        }
    }

    @Override
    public void onSystemUiVisibilityChange(int visibility) {
        // Don't do anything if we're not connected
        if (!connected) {
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
