package com.limelight.utils;

import android.app.Activity;
import android.content.Intent;

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

    public interface ActionListener {
        void onFailure(String message);
        void onSuccess();
    }

    public static void doPair(final Activity activity,
                              final ComputerManagerService.ComputerManagerBinder managerBinder,
                              final ComputerDetails computer,
                              final ActionListener completionListener) {
        if (computer.reachability == ComputerDetails.Reachability.OFFLINE) {
            completionListener.onFailure(activity.getResources().getString(R.string.pair_pc_offline));
            return;
        }
        if (computer.runningGameId != 0) {
            completionListener.onFailure(activity.getResources().getString(R.string.pair_pc_ingame));
            return;
        }
        if (managerBinder == null) {
            completionListener.onFailure(activity.getResources().getString(R.string.error_manager_not_running));
            return;
        }

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
                    InetAddress addr = ServerHelper.getCurrentAddressFromComputer(computer);

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

                if (success) {
                    completionListener.onSuccess();
                }
                else {
                    completionListener.onFailure(message);
                }

                if (cmsIsPolling) {
                    // Start polling again
                    managerBinder.startPolling();
                }
            }
        }).start();
    }

    public static void doWakeOnLan(final Activity activity,
                                   final ComputerDetails computer,
                                   final ActionListener completionListener) {
        if (computer.reachability != ComputerDetails.Reachability.OFFLINE) {
            completionListener.onFailure(activity.getResources().getString(R.string.wol_pc_online));
            return;
        }

        if (computer.macAddress == null) {
            completionListener.onFailure(activity.getResources().getString(R.string.wol_no_mac));
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    completionListener.onSuccess();
                } catch (IOException e) {
                    completionListener.onFailure(activity.getResources().getString(R.string.wol_fail));
                }
            }
        }).start();
    }

    public static void doUnpair(final Activity activity,
                                final ComputerManagerService.ComputerManagerBinder managerBinder,
                                final ComputerDetails computer,
                                final ActionListener completionListener) {
        if (computer.reachability == ComputerDetails.Reachability.OFFLINE) {
            completionListener.onFailure(activity.getResources().getString(R.string.error_pc_offline));
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    InetAddress addr = ServerHelper.getCurrentAddressFromComputer(computer);

                    httpConn = new NvHTTP(addr,
                            managerBinder.getUniqueId(),
                            PlatformBinding.getDeviceName(),
                            PlatformBinding.getCryptoProvider(activity));
                    if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairingManager.PairState.NOT_PAIRED) {
                            completionListener.onSuccess();
                            return;
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

                completionListener.onFailure(message);
            }
        }).start();
    }

    public static void doAppList(final Activity activity,
                                 final ComputerDetails computer) {
        Intent i = new Intent(activity, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid.toString());
        activity.startActivity(i);
    }

    public static void doStart(final Activity parent,
                               final NvApp app,
                               final ComputerDetails computer,
                               final ComputerManagerService.ComputerManagerBinder managerBinder) {
        Intent intent = new Intent(parent, Game.class);
        intent.putExtra(Game.EXTRA_HOST, ServerHelper.getCurrentAddressFromComputer(computer).getHostAddress());
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
                              final ActionListener completionListener) {
        if (managerBinder == null) {
            completionListener.onFailure(parent.getResources().getString(R.string.error_manager_not_running));
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(address,
                            managerBinder.getUniqueId(), null, PlatformBinding.getCryptoProvider(parent));
                    if (httpConn.quitApp()) {
                        completionListener.onSuccess();
                        return;
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
                }

                completionListener.onFailure(message);
            }
        }).start();
    }
}
