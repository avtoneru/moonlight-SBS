package com.limelight;

import java.util.Locale;

import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.PcGridAdapter;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

public class PcView extends Activity implements AdapterFragmentCallbacks {
    private RelativeLayout noPcFoundLayout;
    private PcGridAdapter pcGridAdapter;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Register the listener
                    managerBinder.setListener(new ComputerManagerListener() {
                        @Override
                        public void notifyComputerUpdated(final ComputerDetails details) {
                            if (!freezeUpdates) {
                                PcView.this.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        updateComputer(details);
                                    }
                                });
                            }
                        }
                    });

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Reinitialize views just in case orientation changed
        initializeViews();
    }

    private final static int APP_LIST_ID = 1;
    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Setup the list view
        ImageButton settingsButton = (ImageButton) findViewById(R.id.settingsButton);
        ImageButton addComputerButton = (ImageButton) findViewById(R.id.manuallyAddPc);

        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, StreamSettings.class));
            }
        });
        addComputerButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PcView.this, AddComputerManually.class);
                startActivity(i);
            }
        });

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = (RelativeLayout) findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String locale = PreferenceConfiguration.readPreferences(this).language;
        if (!locale.equals(PreferenceConfiguration.DEFAULT_LANGUAGE)) {
            Configuration config = new Configuration(getResources().getConfiguration());
            config.locale = new Locale(locale);
            getResources().updateConfiguration(config, getResources().getDisplayMetrics());
        }

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this,
                PreferenceConfiguration.readPreferences(this).listMode,
                PreferenceConfiguration.readPreferences(this).smallIconMode);

        initializeViews();
    }

    private class ToastyActionListener implements ServerHelper.ActionListener {
        private int successToastResId;
        private boolean hasSuccessToast;

        public ToastyActionListener() {
            this.hasSuccessToast = false;
        }

        public ToastyActionListener(int successToastResId) {
            this.successToastResId = successToastResId;
            this.hasSuccessToast = true;
        }

        @Override
        public void onFailure(String message) {
            UiHelper.displayToast(PcView.this, message, Toast.LENGTH_LONG);
        }

        @Override
        public void onSuccess() {
            if (hasSuccessToast) {
                UiHelper.displayToast(PcView.this, getResources().getString(successToastResId), Toast.LENGTH_SHORT);
            }
        }
    }

    private void startComputerUpdates() {
        if (managerBinder != null) {
            if (runningPolling) {
                return;
            }

            freezeUpdates = false;
            managerBinder.startPolling();
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);
                
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
        if (computer.details.reachability == ComputerDetails.Reachability.UNKNOWN) {
            startComputerUpdates();
            return;
        }
        
        // Inflate the context menu
        if (computer.details.reachability == ComputerDetails.Reachability.OFFLINE) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
            menu.add(Menu.NONE, DELETE_ID, 2, getResources().getString(R.string.pcview_menu_delete_pc));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, getResources().getString(R.string.pcview_menu_pair_pc));
            menu.add(Menu.NONE, DELETE_ID, 2, getResources().getString(R.string.pcview_menu_delete_pc));
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            menu.add(Menu.NONE, APP_LIST_ID, 3, getResources().getString(R.string.pcview_menu_app_list));

            // FIXME: We used to be able to unpair here but it's been broken since GFE 2.1.x, so I've replaced
            // it with delete which actually work
            menu.add(Menu.NONE, DELETE_ID, 4, getResources().getString(R.string.pcview_menu_delete_pc));
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        startComputerUpdates();
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(info.position);
        switch (item.getItemId()) {
            case PAIR_ID:
                Toast.makeText(this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
                ServerHelper.doPair(this, managerBinder, computer.details, new ServerHelper.ActionListener() {
                    @Override
                    public void onFailure(String message) {
                        UiHelper.displayToast(PcView.this, message, Toast.LENGTH_LONG);
                    }

                    @Override
                    public void onSuccess() {
                        // Display the app list after pairing
                        ServerHelper.doAppList(PcView.this, computer.details);
                    }
                });
                return true;

            case UNPAIR_ID:
                Toast.makeText(this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
                ServerHelper.doUnpair(this, managerBinder, computer.details, new ToastyActionListener(R.string.unpair_success));
                return true;

            case WOL_ID:
                Toast.makeText(this, getResources().getString(R.string.wol_waking_pc), Toast.LENGTH_SHORT).show();
                ServerHelper.doWakeOnLan(this, computer.details, new ToastyActionListener(R.string.wol_waking_msg));
                return true;

            case DELETE_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }
                managerBinder.removeComputer(computer.details.name);
                removeComputer(computer.details);
                return true;

            case APP_LIST_ID:
                if (computer.details.reachability == ComputerDetails.Reachability.OFFLINE) {
                    Toast.makeText(this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
                    return true;
                }
                ServerHelper.doAppList(this, computer.details);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }
                ServerHelper.doStart(this, new NvApp("app", computer.details.runningGameId), computer.details, managerBinder);
                return true;

            case QUIT_ID:
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        UiHelper.displayToast(PcView.this, getResources().getString(R.string.applist_quit_app) + " app...", Toast.LENGTH_SHORT);
                        ServerHelper.doQuit(PcView.this,
                                ServerHelper.getCurrentAddressFromComputer(computer.details),
                                new NvApp("app", 0), managerBinder, new ServerHelper.ActionListener() {
                                    @Override
                                    public void onFailure(String message) {
                                        UiHelper.displayToast(PcView.this, message, Toast.LENGTH_LONG);
                                    }

                                    @Override
                                    public void onSuccess() {
                                        UiHelper.displayToast(PcView.this, getResources().getString(R.string.applist_quit_success) + " app", Toast.LENGTH_LONG);
                                    }
                                });
                    }
                }, null);
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }
    
    private void removeComputer(ComputerDetails details) {
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
    }
    
    private void updateComputer(ComputerDetails details) {
        ComputerObject existingEntry = null;

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
        }
        else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return PreferenceConfiguration.readPreferences(this).listMode ?
                R.layout.list_view : (PreferenceConfiguration.readPreferences(this).smallIconMode ?
                R.layout.pc_grid_view_small : R.layout.pc_grid_view);
    }

    @Override
    public void receiveAbsListView(AbsListView listView) {
        listView.setAdapter(pcGridAdapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int pos,
                                    long id) {
                final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
                if (computer.details.reachability == ComputerDetails.Reachability.UNKNOWN) {
                    // Do nothing
                } else if (computer.details.reachability == ComputerDetails.Reachability.OFFLINE) {
                    // Open the context menu if a PC is offline
                    openContextMenu(arg1);
                } else if (computer.details.pairState != PairState.PAIRED) {
                    // Pair an unpaired machine by default
                    Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
                    ServerHelper.doPair(PcView.this, managerBinder, computer.details, new ServerHelper.ActionListener() {
                        @Override
                        public void onFailure(String message) {
                            UiHelper.displayToast(PcView.this, message, Toast.LENGTH_LONG);
                        }

                        @Override
                        public void onSuccess() {
                            // Display the app list after pairing
                            ServerHelper.doAppList(PcView.this, computer.details);
                        }
                    });
                } else {
                    ServerHelper.doAppList(PcView.this, computer.details);
                }
            }
        });
        registerForContextMenu(listView);
    }

    public class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
    }
}
