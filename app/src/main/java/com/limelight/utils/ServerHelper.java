package com.limelight.utils;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.limelight.AppView;
import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.GfeHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.wol.WakeOnLanSender;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class ServerHelper {
    public static InetAddress getCurrentAddressFromComputer(ComputerDetails computer) {
        return computer.reachability == ComputerDetails.Reachability.REMOTE ?
                computer.remoteIp : computer.localIp;
    }

    public static void doPair(final Activity activity,
                              final ComputerManagerService.ComputerManagerBinder managerBinder,
                              final ComputerDetails computer,
                              final boolean openAppViewOnSuccess) {
        if (computer.reachability == ComputerDetails.Reachability.OFFLINE) {
            Toast.makeText(activity, activity.getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (computer.runningGameId != 0) {
            Toast.makeText(activity, activity.getResources().getString(R.string.pair_pc_ingame), Toast.LENGTH_LONG).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(activity, activity.getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(activity, activity.getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;

                boolean cmsIsPolling = managerBinder.isPolling();
                if (cmsIsPolling) {
                    // Stop updates and wait while pairing
                    managerBinder.stopPolling();
                    managerBinder.waitForPollingStopped();
                }

                try {
                    InetAddress addr = null;
                    if (computer.reachability == ComputerDetails.Reachability.LOCAL) {
                        addr = computer.localIp;
                    }
                    else if (computer.reachability == ComputerDetails.Reachability.REMOTE) {
                        addr = computer.remoteIp;
                    }
                    else {
                        LimeLog.warning("Unknown reachability - using local IP");
                        addr = computer.localIp;
                    }

                    httpConn = new NvHTTP(addr,
                            managerBinder.getUniqueId(),
                            PlatformBinding.getDeviceName(),
                            PlatformBinding.getCryptoProvider(activity));
                    if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        final String pinStr = PairingManager.generatePinString();

                        // Spin the dialog off in a thread because it blocks
                        Dialog.displayDialog(activity, activity.getResources().getString(R.string.pair_pairing_title),
                                activity.getResources().getString(R.string.pair_pairing_msg)+" "+pinStr, false);

                        PairingManager.PairState pairState = httpConn.pair(pinStr);
                        if (pairState == PairingManager.PairState.PIN_WRONG) {
                            message = activity.getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairingManager.PairState.FAILED) {
                            message = activity.getResources().getString(R.string.pair_fail);
                        }
                        else if (pairState == PairingManager.PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = activity.getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = activity.getResources().getString(R.string.error_404);
                } catch (Exception e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (toastMessage != null) {
                            Toast.makeText(activity, toastMessage, Toast.LENGTH_LONG).show();
                        }

                        if (toastSuccess && openAppViewOnSuccess) {
                            // Open the app list after a successful pairing attempt
                            ServerHelper.doAppList(activity, computer);
                        }
                    }
                });

                if (cmsIsPolling) {
                    // Start polling again
                    managerBinder.startPolling();
                }
            }
        }).start();
    }

    public static void doWakeOnLan(final Activity activity, final ComputerDetails computer) {
        if (computer.reachability != ComputerDetails.Reachability.OFFLINE) {
            Toast.makeText(activity, activity.getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(activity, activity.getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(activity, activity.getResources().getString(R.string.wol_waking_pc), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = activity.getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = activity.getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(activity, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    public static void doUnpair(final Activity activity,
                                 final ComputerManagerService.ComputerManagerBinder managerBinder,
                                 final ComputerDetails computer) {
        if (computer.reachability == ComputerDetails.Reachability.OFFLINE) {
            Toast.makeText(activity, activity.getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(activity, activity.getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    InetAddress addr = null;
                    if (computer.reachability == ComputerDetails.Reachability.LOCAL) {
                        addr = computer.localIp;
                    }
                    else if (computer.reachability == ComputerDetails.Reachability.REMOTE) {
                        addr = computer.remoteIp;
                    }
                    else {
                        LimeLog.warning("Unknown reachability - using local IP");
                        addr = computer.localIp;
                    }

                    httpConn = new NvHTTP(addr,
                            managerBinder.getUniqueId(),
                            PlatformBinding.getDeviceName(),
                            PlatformBinding.getCryptoProvider(activity));
                    if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairingManager.PairState.NOT_PAIRED) {
                            message = activity.getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = activity.getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = activity.getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = activity.getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = activity.getResources().getString(R.string.error_404);
                } catch (Exception e) {
                    message = e.getMessage();
                }

                final String toastMessage = message;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(activity, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    public static void doAppList(final Activity activity, final ComputerDetails computer) {
        if (computer.reachability == ComputerDetails.Reachability.OFFLINE) {
            Toast.makeText(activity, activity.getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }

        Intent i = new Intent(activity, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid.toString());
        activity.startActivity(i);
    }

    public static void doStart(Activity parent, NvApp app, ComputerDetails computer,
                               ComputerManagerService.ComputerManagerBinder managerBinder) {
        if (managerBinder == null) {
            Toast.makeText(parent, parent.getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(parent, Game.class);
        intent.putExtra(Game.EXTRA_HOST,
                computer.reachability == ComputerDetails.Reachability.LOCAL ?
                        computer.localIp.getHostAddress() : computer.remoteIp.getHostAddress());
        intent.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        intent.putExtra(Game.EXTRA_APP_ID, app.getAppId());
        intent.putExtra(Game.EXTRA_UNIQUEID, managerBinder.getUniqueId());
        intent.putExtra(Game.EXTRA_STREAMING_REMOTE,
                computer.reachability != ComputerDetails.Reachability.LOCAL);
        parent.startActivity(intent);
    }

    public static void doQuit(final Activity parent,
                              final InetAddress address,
                              final NvApp app,
                              final ComputerManagerService.ComputerManagerBinder managerBinder,
                              final Runnable onComplete) {
        if (managerBinder == null) {
            Toast.makeText(parent, parent.getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(parent, parent.getResources().getString(R.string.applist_quit_app) + " " + app.getAppName() + "...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(address,
                            managerBinder.getUniqueId(), null, PlatformBinding.getCryptoProvider(parent));
                    if (httpConn.quitApp()) {
                        message = parent.getResources().getString(R.string.applist_quit_success) + " " + app.getAppName();
                    } else {
                        message = parent.getResources().getString(R.string.applist_quit_fail) + " " + app.getAppName();
                    }
                } catch (GfeHttpResponseException e) {
                    if (e.getErrorCode() == 599) {
                        message = "This session wasn't started by this device," +
                                " so it cannot be quit. End streaming on the original " +
                                "device or the PC itself. (Error code: "+e.getErrorCode()+")";
                    }
                    else {
                        message = e.getMessage();
                    }
                } catch (UnknownHostException e) {
                    message = parent.getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = parent.getResources().getString(R.string.error_404);
                } catch (Exception e) {
                    message = e.getMessage();
                } finally {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }

                final String toastMessage = message;
                parent.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }
}
