package com.limelight.ui;

import com.limelight.nvstream.NvConnectionListener;

public abstract class AbstractUiConnectionListener implements NvConnectionListener {
    private boolean expectingTermination = false;
    private boolean connectionComplete = false;

    public void notifyExpectingTermination() {
        this.expectingTermination = true;
    }

    public boolean getExpectingTermination() {
        return this.expectingTermination;
    }

    public void notifyConnectionComplete() {
        this.connectionComplete = true;
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

    public void notifyStreamStopped() {}
}
