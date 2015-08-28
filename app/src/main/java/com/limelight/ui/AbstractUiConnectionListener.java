package com.limelight.ui;

import com.limelight.nvstream.NvConnectionListener;

public abstract class AbstractUiConnectionListener implements NvConnectionListener {
    private boolean expectingTermination = false;
    private boolean connectionComplete = false;

    public void setExpectingTermination(boolean expectingTermination) {
        this.expectingTermination = expectingTermination;
    }

    public boolean getExpectingTermination() {
        return this.expectingTermination;
    }

    public void setConnectionComplete(boolean connectionComplete) {
        this.connectionComplete = connectionComplete;
    }

    public boolean getConnectionComplete() {
        return this.connectionComplete;
    }

    @Override
    public void stageStarting(Stage stage) {}

    @Override
    public void stageComplete(Stage stage) {}

    @Override
    public void displayMessage(String s) {}

    @Override
    public void displayTransientMessage(String s) {}
}
