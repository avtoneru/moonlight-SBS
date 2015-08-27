package com.limelight.binding.input;

import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.limelight.LimeLog;
import com.limelight.LimelightBuildProps;
import com.limelight.binding.input.evdev.EvdevListener;
import com.limelight.binding.input.evdev.EvdevWatcher;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GameGestures;

public class InputHandler implements EvdevListener {
    private final NvConnection conn;
    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final GameGestures gestureListener;

    private final ControllerHandler controllerHandler;
    private final KeyboardTranslator keybTranslator;

    private final Point screenSize = new Point(0, 0);

    private EvdevWatcher evdevWatcher;
    private int modifierFlags = 0;
    private boolean grabbedInput = true;
    private boolean grabComboDown = false;

    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;
    private int lastButtonState = 0;

    // Only 2 touches are supported
    private final TouchContext[] touchContextMap = new TouchContext[2];
    private long threeFingerDownTime = 0;

    private static final int THREE_FINGER_TAP_THRESHOLD = 300;

    public InputHandler(NvConnection conn, Activity activity, PreferenceConfiguration prefConfig, GameGestures gestureListener) {
        this.conn = conn;
        this.activity = activity;
        this.prefConfig = prefConfig;
        this.gestureListener = gestureListener;

        keybTranslator = new KeyboardTranslator(conn);
        controllerHandler = new ControllerHandler(conn, gestureListener, prefConfig.multiController, prefConfig.deadzonePercentage);

        Display display = activity.getWindowManager().getDefaultDisplay();
        display.getSize(screenSize);

        // Initialize touch contexts
        for (int i = 0; i < touchContextMap.length; i++) {
            touchContextMap[i] = new TouchContext(conn, i,
                    ((double)prefConfig.width / (double)screenSize.x),
                    ((double)prefConfig.height / (double)screenSize.y));
        }
    }

    public void start() {
        InputManager inputManager = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(controllerHandler, null);

        if (LimelightBuildProps.ROOT_BUILD) {
            // Start watching for raw input
            evdevWatcher = new EvdevWatcher(this);
            evdevWatcher.start();
        }
    }

    public void stop() {
        // Close the Evdev watcher to allow use of captured input devices
        if (evdevWatcher != null) {
            evdevWatcher.shutdown();
            evdevWatcher = null;
        }

        InputManager inputManager = (InputManager) activity.getSystemService(Context.INPUT_SERVICE);
        inputManager.unregisterInputDeviceListener(controllerHandler);
    }

    private final Runnable toggleGrab = new Runnable() {
        @Override
        public void run() {

            if (evdevWatcher != null) {
                if (grabbedInput) {
                    evdevWatcher.ungrabAll();
                }
                else {
                    evdevWatcher.regrabAll();
                }
            }

            grabbedInput = !grabbedInput;
        }
    };

    // Returns true if the key stroke was consumed
    private boolean handleSpecialKeys(short translatedKey, boolean down) {
        int modifierMask = 0;

        // Mask off the high byte
        translatedKey &= 0xff;

        if (translatedKey == KeyboardTranslator.VK_CONTROL) {
            modifierMask = KeyboardPacket.MODIFIER_CTRL;
        }
        else if (translatedKey == KeyboardTranslator.VK_SHIFT) {
            modifierMask = KeyboardPacket.MODIFIER_SHIFT;
        }
        else if (translatedKey == KeyboardTranslator.VK_ALT) {
            modifierMask = KeyboardPacket.MODIFIER_ALT;
        }

        if (down) {
            this.modifierFlags |= modifierMask;
        }
        else {
            this.modifierFlags &= ~modifierMask;
        }

        // Check if Ctrl+Shift+Z is pressed
        if (translatedKey == KeyboardTranslator.VK_Z &&
                (modifierFlags & (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_SHIFT)) ==
                        (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_SHIFT))
        {
            if (down) {
                // Now that we've pressed the magic combo
                // we'll wait for one of the keys to come up
                grabComboDown = true;
            }
            else {
                // Toggle the grab if Z comes up
                Handler h = activity.getWindow().getDecorView().getHandler();
                if (h != null) {
                    h.postDelayed(toggleGrab, 250);
                }

                grabComboDown = false;
            }

            return true;
        }
        // Toggle the grab if control or shift comes up
        else if (grabComboDown) {
            Handler h = activity.getWindow().getDecorView().getHandler();
            if (h != null) {
                h.postDelayed(toggleGrab, 250);
            }

            grabComboDown = false;
            return true;
        }

        // Not a special combo
        return false;
    }

    private static byte getModifierState(KeyEvent event) {
        byte modifier = 0;
        if (event.isShiftPressed()) {
            modifier |= KeyboardPacket.MODIFIER_SHIFT;
        }
        if (event.isCtrlPressed()) {
            modifier |= KeyboardPacket.MODIFIER_CTRL;
        }
        if (event.isAltPressed()) {
            modifier |= KeyboardPacket.MODIFIER_ALT;
        }
        return modifier;
    }

    private byte getModifierState() {
        return (byte) modifierFlags;
    }

    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        // Try the controller handler first
        boolean handled = controllerHandler.handleButtonDown(event);
        if (!handled) {
            // Try the keyboard handler
            short translated = keybTranslator.translate(event.getKeyCode());
            if (translated == 0) {
                return false;
            }

            // Let this method take duplicate key down events
            if (handleSpecialKeys(translated, true)) {
                return true;
            }

            // Eat repeat down events
            if (event.getRepeatCount() > 0) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            keybTranslator.sendKeyDown(translated,
                    getModifierState(event));
        }

        return true;
    }

    public boolean handleKeyUp(int keyCode, KeyEvent event) {
        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        // Try the controller handler first
        boolean handled = controllerHandler.handleButtonUp(event);
        if (!handled) {
            // Try the keyboard handler
            short translated = keybTranslator.translate(event.getKeyCode());
            if (translated == 0) {
                return false;
            }

            if (handleSpecialKeys(translated, false)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            keybTranslator.sendKeyUp(translated,
                    getModifierState(event));
        }

        return true;
    }

    private TouchContext getTouchContext(int actionIndex)
    {
        if (actionIndex < touchContextMap.length) {
            return touchContextMap[actionIndex];
        }
        else {
            return null;
        }
    }

    // Returns true if the event was consumed
    public boolean handleMotionEvent(MotionEvent event) {
        // Pass through keyboard input if we're not grabbing
        if (!grabbedInput) {
            return false;
        }

        if ((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            if (controllerHandler.handleMotionEvent(event)) {
                return true;
            }
        }
        else if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0)
        {
            // This case is for touch-based input devices
            if (event.getSource() == InputDevice.SOURCE_TOUCHSCREEN ||
                    event.getSource() == InputDevice.SOURCE_STYLUS)
            {
                int actionIndex = event.getActionIndex();

                int eventX = (int)event.getX(actionIndex);
                int eventY = (int)event.getY(actionIndex);

                // Special handling for 3 finger gesture
                if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN &&
                        event.getPointerCount() == 3) {
                    // Three fingers down
                    threeFingerDownTime = SystemClock.uptimeMillis();

                    // Cancel the first and second touches to avoid
                    // erroneous events
                    for (TouchContext aTouchContext : touchContextMap) {
                        aTouchContext.cancelTouch();
                    }

                    return true;
                }

                TouchContext context = getTouchContext(actionIndex);
                if (context == null) {
                    return false;
                }

                switch (event.getActionMasked())
                {
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_DOWN:
                        context.touchDownEvent(eventX, eventY);
                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                    case MotionEvent.ACTION_UP:
                        if (event.getPointerCount() == 1) {
                            // All fingers up
                            if (SystemClock.uptimeMillis() - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                                // This is a 3 finger tap to bring up the keyboard
                                gestureListener.showKeyboard();
                                return true;
                            }
                        }
                        context.touchUpEvent(eventX, eventY);
                        if (actionIndex == 0 && event.getPointerCount() > 1 && !context.isCancelled()) {
                            // The original secondary touch now becomes primary
                            context.touchDownEvent((int)event.getX(1), (int)event.getY(1));
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        // ACTION_MOVE is special because it always has actionIndex == 0
                        // We'll call the move handlers for all indexes manually

                        // First process the historical events
                        for (int i = 0; i < event.getHistorySize(); i++) {
                            for (TouchContext aTouchContextMap : touchContextMap) {
                                if (aTouchContextMap.getActionIndex() < event.getPointerCount())
                                {
                                    aTouchContextMap.touchMoveEvent(
                                            (int)event.getHistoricalX(aTouchContextMap.getActionIndex(), i),
                                            (int)event.getHistoricalY(aTouchContextMap.getActionIndex(), i));
                                }
                            }
                        }

                        // Now process the current values
                        for (TouchContext aTouchContextMap : touchContextMap) {
                            if (aTouchContextMap.getActionIndex() < event.getPointerCount())
                            {
                                aTouchContextMap.touchMoveEvent(
                                        (int)event.getX(aTouchContextMap.getActionIndex()),
                                        (int)event.getY(aTouchContextMap.getActionIndex()));
                            }
                        }
                        break;
                    default:
                        return false;
                }
            }
            // This case is for mice
            else if (event.getSource() == InputDevice.SOURCE_MOUSE)
            {
                int changedButtons = event.getButtonState() ^ lastButtonState;

                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    // Send the vertical scroll packet
                    byte vScrollClicks = (byte) event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    conn.sendMouseScroll(vScrollClicks);
                }

                if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((event.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                    }
                }

                if ((changedButtons & MotionEvent.BUTTON_SECONDARY) != 0) {
                    if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                    }
                }

                if ((changedButtons & MotionEvent.BUTTON_TERTIARY) != 0) {
                    if ((event.getButtonState() & MotionEvent.BUTTON_TERTIARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                }

                // First process the history
                for (int i = 0; i < event.getHistorySize(); i++) {
                    updateAbsoluteMousePosition((int) event.getHistoricalX(i), (int) event.getHistoricalY(i));
                }

                // Now process the current values
                updateAbsoluteMousePosition((int) event.getX(), (int) event.getY());

                lastButtonState = event.getButtonState();
            }
            else
            {
                // Unknown source
                return false;
            }

            // Handled a known source
            return true;
        }

        // Unknown class
        return false;
    }

    private void updateAbsoluteMousePosition(int eventX, int eventY) {
        // Send a mouse move if we already have a mouse location
        // and the mouse coordinates change
        if (lastMouseX != Integer.MIN_VALUE &&
                lastMouseY != Integer.MIN_VALUE &&
                !(lastMouseX == eventX && lastMouseY == eventY))
        {
            int deltaX = eventX - lastMouseX;
            int deltaY = eventY - lastMouseY;

            // Scale the deltas if the device resolution is different
            // than the stream resolution
            deltaX = (int)Math.round((double)deltaX * ((double)prefConfig.width / (double)screenSize.x));
            deltaY = (int)Math.round((double)deltaY * ((double)prefConfig.height / (double)screenSize.y));

            conn.sendMouseMove((short)deltaX, (short)deltaY);
        }

        // Update pointer location for delta calculation next time
        lastMouseX = eventX;
        lastMouseY = eventY;
    }

    @Override
    public void mouseMove(int deltaX, int deltaY) {
        conn.sendMouseMove((short) deltaX, (short) deltaY);
    }

    @Override
    public void mouseButtonEvent(int buttonId, boolean down) {
        byte buttonIndex;

        switch (buttonId)
        {
            case EvdevListener.BUTTON_LEFT:
                buttonIndex = MouseButtonPacket.BUTTON_LEFT;
                break;
            case EvdevListener.BUTTON_MIDDLE:
                buttonIndex = MouseButtonPacket.BUTTON_MIDDLE;
                break;
            case EvdevListener.BUTTON_RIGHT:
                buttonIndex = MouseButtonPacket.BUTTON_RIGHT;
                break;
            default:
                LimeLog.warning("Unhandled button: " + buttonId);
                return;
        }

        if (down) {
            conn.sendMouseButtonDown(buttonIndex);
        }
        else {
            conn.sendMouseButtonUp(buttonIndex);
        }
    }

    @Override
    public void mouseScroll(byte amount) {
        conn.sendMouseScroll(amount);
    }

    @Override
    public void keyboardEvent(boolean buttonDown, short keyCode) {
        short keyMap = keybTranslator.translate(keyCode);
        if (keyMap != 0) {
            if (handleSpecialKeys(keyMap, buttonDown)) {
                return;
            }

            if (buttonDown) {
                keybTranslator.sendKeyDown(keyMap, getModifierState());
            }
            else {
                keybTranslator.sendKeyUp(keyMap, getModifierState());
            }
        }
    }
}
