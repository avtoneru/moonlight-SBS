package com.limelight.ui;

import android.widget.Toast;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.Dialog;
import com.limelight.utils.SpinnerDialog;

public class MoonlightConnectionListener extends AbstractUiConnectionListener {
    private final Game activity;
    private final PreferenceConfiguration prefConfig;

    private SpinnerDialog spinner;

    public MoonlightConnectionListener(Game activity, PreferenceConfiguration prefConfig) {
        this.activity = activity;
        this.prefConfig = prefConfig;
    }

    @Override
    public void stageStarting(Stage stage) {
        if (spinner == null) {
            // Start the spinner
            spinner = SpinnerDialog.displayDialog(activity, activity.getResources().getString(R.string.conn_establishing_title),
                    activity.getResources().getString(R.string.conn_establishing_msg), true);
        }

        spinner.setMessage(activity.getResources().getString(R.string.conn_starting) + " " + stage.getName());
    }

    @Override
    public void stageFailed(Stage stage) {
        if (spinner != null) {
            spinner.dismiss();
            spinner = null;
        }

        if (!getExpectingTermination()) {
            setExpectingTermination(true);
            activity.stopConnection();
            Dialog.displayDialog(activity, activity.getResources().getString(R.string.conn_error_title),
                    activity.getResources().getString(R.string.conn_error_msg) + " " + stage.getName(), true);
        }
    }

    @Override
    public void connectionTerminated(Exception e) {
        if (!getExpectingTermination()) {
            setExpectingTermination(true);
            e.printStackTrace();

            activity.stopConnection();
            Dialog.displayDialog(activity, activity.getResources().getString(R.string.conn_terminated_title),
                    activity.getResources().getString(R.string.conn_terminated_msg), true);
        }
    }

    @Override
    public void connectionStarted() {
        if (spinner != null) {
            spinner.dismiss();
            spinner = null;
        }

        setConnectionComplete(true);

        activity.hideSystemUi(1000);
    }

    @Override
    public void displayMessage(final String message) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void displayTransientMessage(final String message) {
        if (!prefConfig.disableWarnings) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
