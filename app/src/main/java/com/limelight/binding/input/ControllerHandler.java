package com.limelight.binding.input;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.binding.input.driver.UsbDriverListener;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.ui.GameGestures;
import com.limelight.utils.Vector2d;

import java.util.Timer;
import java.util.TimerTask;

public class ControllerHandler implements InputManager.InputDeviceListener, UsbDriverListener {

    private static final int MAXIMUM_BUMPER_UP_DELAY_MS = 100;

    private static final int START_DOWN_TIME_MOUSE_MODE_MS = 750;

    private static final int MINIMUM_BUTTON_DOWN_TIME_MS = 25;

    private static final int EMULATING_SPECIAL = 0x1;
    private static final int EMULATING_SELECT = 0x2;

    private static final int EMULATED_SPECIAL_UP_DELAY_MS = 100;
    private static final int EMULATED_SELECT_UP_DELAY_MS = 30;

    private final Vector2d inputVector = new Vector2d();

    private final SparseArray<InputDeviceContext> inputDeviceContexts = new SparseArray<>();
    private final SparseArray<UsbDeviceContext> usbDeviceContexts = new SparseArray<>();

    private final NvConnection conn;
    private final Context activityContext;
    private final double stickDeadzone;
    private final InputDeviceContext defaultContext = new InputDeviceContext();
    private final GameGestures gestures;
    private boolean hasGameController;

    private final boolean multiControllerEnabled;
    private short currentControllers;

    public ControllerHandler(Context activityContext, NvConnection conn, GameGestures gestures, boolean multiControllerEnabled, int deadzonePercentage) {
        this.activityContext = activityContext;
        this.conn = conn;
        this.gestures = gestures;
        this.multiControllerEnabled = multiControllerEnabled;

        // HACK: For now we're hardcoding a 10% deadzone. Some deadzone
        // is required for controller batching support to work.
        deadzonePercentage = 10;

        int[] ids = InputDevice.getDeviceIds();
        for (int id : ids) {
            InputDevice dev = InputDevice.getDevice(id);
            if ((dev.getSources() & InputDevice.SOURCE_JOYSTICK) != 0 ||
                    (dev.getSources() & InputDevice.SOURCE_GAMEPAD) != 0) {
                // This looks like a gamepad, but we'll check X and Y to be sure
                if (getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_X) != null &&
                    getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Y) != null) {
                    // This is a gamepad
                    hasGameController = true;
                }
            }
        }

        // 1% is the lowest possible deadzone we support
        if (deadzonePercentage <= 0) {
            deadzonePercentage = 1;
        }

        this.stickDeadzone = (double)deadzonePercentage / 100.0;

        // Initialize the default context for events with no device
        defaultContext.leftStickXAxis = MotionEvent.AXIS_X;
        defaultContext.leftStickYAxis = MotionEvent.AXIS_Y;
        defaultContext.leftStickDeadzoneRadius = (float) stickDeadzone;
        defaultContext.rightStickXAxis = MotionEvent.AXIS_Z;
        defaultContext.rightStickYAxis = MotionEvent.AXIS_RZ;
        defaultContext.rightStickDeadzoneRadius = (float) stickDeadzone;
        defaultContext.leftTriggerAxis = MotionEvent.AXIS_BRAKE;
        defaultContext.rightTriggerAxis = MotionEvent.AXIS_GAS;
        defaultContext.controllerNumber = (short) 0;
        defaultContext.assignedControllerNumber = true;
    }

    private static InputDevice.MotionRange getMotionRangeForJoystickAxis(InputDevice dev, int axis) {
        InputDevice.MotionRange range;

        // First get the axis for SOURCE_JOYSTICK
        range = dev.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK);
        if (range == null) {
            // Now try the axis for SOURCE_GAMEPAD
            range = dev.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD);
        }

        return range;
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        // Nothing happening here yet
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        InputDeviceContext context = inputDeviceContexts.get(deviceId);
        if (context != null) {
            LimeLog.info("Removed controller: "+context.name+" ("+deviceId+")");
            releaseControllerNumber(context);
            inputDeviceContexts.remove(deviceId);
        }
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        // Remove and re-add 
        onInputDeviceRemoved(deviceId);
        onInputDeviceAdded(deviceId);
    }

    private void releaseControllerNumber(GenericControllerContext context) {
        // If this device sent data as a gamepad, zero the values before removing
        if (context.assignedControllerNumber) {
            conn.sendControllerInput(context.controllerNumber, (short) 0,
                    (byte) 0, (byte) 0,
                    (short) 0, (short) 0,
                    (short) 0, (short) 0);
        }

        // If we reserved a controller number, remove that reservation
        if (context.reservedControllerNumber) {
            LimeLog.info("Controller number "+context.controllerNumber+" is now available");
            currentControllers &= ~(1 << context.controllerNumber);
        }
    }

    // Called before sending input but after we've determined that this
    // is definitely a controller (not a keyboard, mouse, or something else)
    private void assignControllerNumberIfNeeded(GenericControllerContext context) {
        if (context.assignedControllerNumber) {
            return;
        }

        if (context instanceof InputDeviceContext) {
            InputDeviceContext devContext = (InputDeviceContext) context;

            LimeLog.info(devContext.name+" ("+context.id+") needs a controller number assigned");
            if (devContext.name != null &&
                    (devContext.name.contains("gpio-keys") || // This is the back button on Shield portable consoles
                            devContext.name.contains("joy_key"))) { // These are the gamepad buttons on the Archos Gamepad 2
                LimeLog.info("Built-in buttons hardcoded as controller 0");
                context.controllerNumber = 0;
            }
            else if (multiControllerEnabled && devContext.hasJoystickAxes) {
                context.controllerNumber = 0;

                LimeLog.info("Reserving the next available controller number");
                for (short i = 0; i < 4; i++) {
                    if ((currentControllers & (1 << i)) == 0) {
                        // Found an unused controller value
                        currentControllers |= (1 << i);
                        context.controllerNumber = i;
                        context.reservedControllerNumber = true;
                        break;
                    }
                }
            }
            else {
                LimeLog.info("Not reserving a controller number");
                context.controllerNumber = 0;
            }
        }
        else {
            if (multiControllerEnabled) {
                context.controllerNumber = 0;

                LimeLog.info("Reserving the next available controller number");
                for (short i = 0; i < 4; i++) {
                    if ((currentControllers & (1 << i)) == 0) {
                        // Found an unused controller value
                        currentControllers |= (1 << i);
                        context.controllerNumber = i;
                        context.reservedControllerNumber = true;
                        break;
                    }
                }
            }
            else {
                LimeLog.info("Not reserving a controller number");
                context.controllerNumber = 0;
            }
        }

        LimeLog.info("Assigned as controller "+context.controllerNumber);
        context.assignedControllerNumber = true;
    }

    private UsbDeviceContext createUsbDeviceContextForDevice(int deviceId) {
        UsbDeviceContext context = new UsbDeviceContext();

        context.id = deviceId;

        context.leftStickDeadzoneRadius = (float) stickDeadzone;
        context.rightStickDeadzoneRadius = (float) stickDeadzone;
        context.triggerDeadzone = 0.13f;

        return context;
    }

    private InputDeviceContext createInputDeviceContextForDevice(InputDevice dev) {
        InputDeviceContext context = new InputDeviceContext();
        String devName = dev.getName();

        LimeLog.info("Creating controller context for device: "+devName);
        LimeLog.info(dev.toString());

        context.name = devName;
        context.id = dev.getId();

        context.leftStickXAxis = MotionEvent.AXIS_X;
        context.leftStickYAxis = MotionEvent.AXIS_Y;
        if (getMotionRangeForJoystickAxis(dev, context.leftStickXAxis) != null &&
                getMotionRangeForJoystickAxis(dev, context.leftStickYAxis) != null) {
            // This is a gamepad
            hasGameController = true;
            context.hasJoystickAxes = true;
        }

        InputDevice.MotionRange leftTriggerRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_LTRIGGER);
        InputDevice.MotionRange rightTriggerRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RTRIGGER);
        InputDevice.MotionRange brakeRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_BRAKE);
        InputDevice.MotionRange gasRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_GAS);
        InputDevice.MotionRange throttleRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_THROTTLE);
        if (leftTriggerRange != null && rightTriggerRange != null)
        {
            // Some controllers use LTRIGGER and RTRIGGER (like Ouya)
            context.leftTriggerAxis = MotionEvent.AXIS_LTRIGGER;
            context.rightTriggerAxis = MotionEvent.AXIS_RTRIGGER;
        }
        else if (brakeRange != null && gasRange != null)
        {
            // Others use GAS and BRAKE (like Moga)
            context.leftTriggerAxis = MotionEvent.AXIS_BRAKE;
            context.rightTriggerAxis = MotionEvent.AXIS_GAS;
        }
        else if (brakeRange != null && throttleRange != null)
        {
            // Others use THROTTLE and BRAKE (like Xiaomi)
            context.leftTriggerAxis = MotionEvent.AXIS_BRAKE;
            context.rightTriggerAxis = MotionEvent.AXIS_THROTTLE;
        }
        else
        {
            InputDevice.MotionRange rxRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RX);
            InputDevice.MotionRange ryRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RY);
            if (rxRange != null && ryRange != null && devName != null) {
                if (devName.contains("Xbox") || devName.contains("XBox") || devName.contains("X-Box")) {
                    // Xbox controllers use RX and RY for right stick
                    context.rightStickXAxis = MotionEvent.AXIS_RX;
                    context.rightStickYAxis = MotionEvent.AXIS_RY;

                    // Xbox controllers use Z and RZ for triggers
                    context.leftTriggerAxis = MotionEvent.AXIS_Z;
                    context.rightTriggerAxis = MotionEvent.AXIS_RZ;
                    context.triggersIdleNegative = true;
                    context.isXboxController = true;
                }
                else {
                    // DS4 controller uses RX and RY for triggers
                    context.leftTriggerAxis = MotionEvent.AXIS_RX;
                    context.rightTriggerAxis = MotionEvent.AXIS_RY;
                    context.triggersIdleNegative = true;

                    context.isDualShock4 = true;
                }
            }
        }

        if (context.rightStickXAxis == -1 && context.rightStickYAxis == -1) {
            InputDevice.MotionRange zRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Z);
            InputDevice.MotionRange rzRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RZ);

            // Most other controllers use Z and RZ for the right stick
            if (zRange != null && rzRange != null) {
                context.rightStickXAxis = MotionEvent.AXIS_Z;
                context.rightStickYAxis = MotionEvent.AXIS_RZ;
            }
            else {
                InputDevice.MotionRange rxRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RX);
                InputDevice.MotionRange ryRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_RY);

                // Try RX and RY now
                if (rxRange != null && ryRange != null) {
                    context.rightStickXAxis = MotionEvent.AXIS_RX;
                    context.rightStickYAxis = MotionEvent.AXIS_RY;
                }
            }
        }

        // Some devices have "hats" for d-pads
        InputDevice.MotionRange hatXRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_HAT_X);
        InputDevice.MotionRange hatYRange = getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_HAT_Y);
        if (hatXRange != null && hatYRange != null) {
            context.hatXAxis = MotionEvent.AXIS_HAT_X;
            context.hatYAxis = MotionEvent.AXIS_HAT_Y;
        }

        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            context.leftStickDeadzoneRadius = (float) stickDeadzone;
        }

        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            context.rightStickDeadzoneRadius = (float) stickDeadzone;
        }

        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            InputDevice.MotionRange ltRange = getMotionRangeForJoystickAxis(dev, context.leftTriggerAxis);
            InputDevice.MotionRange rtRange = getMotionRangeForJoystickAxis(dev, context.rightTriggerAxis);

            // It's important to have a valid deadzone so controller packet batching works properly
            context.triggerDeadzone = Math.max(Math.abs(ltRange.getFlat()), Math.abs(rtRange.getFlat()));

            // For triggers without (valid) deadzones, we'll use 13% (around XInput's default)
            if (context.triggerDeadzone < 0.13f ||
                context.triggerDeadzone > 0.30f)
            {
                context.triggerDeadzone = 0.13f;
            }
        }

        // Ignore the back buttonn if a controller has both buttons
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            boolean[] hasSelectKey = dev.hasKeys(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK, 0);
            if (hasSelectKey[0] && hasSelectKey[1]) {
                // Xiaomi gamepads claim to have both buttons then only send KEYCODE_BACK events
                if (dev.getVendorId() != 0x2717) {
                    LimeLog.info("Ignoring back button because select is present");
                    context.ignoreBack = true;
                }
            }
        }

        // The ADT-1 controller needs a similar fixup to the ASUS Gamepad
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            // The device name provided is just "Gamepad" which is pretty useless, so we
            // use VID/PID instead
            if (dev.getVendorId() == 0x18d1 && dev.getProductId() == 0x2c40) {
                context.backIsStart = true;
                context.modeIsSelect = true;
                context.triggerDeadzone = 0.30f;
            }
        }

        if (devName != null) {
            // For the Nexus Player (and probably other ATV devices), we should
            // use the back button as start since it doesn't have a start/menu button
            // on the controller
            if (devName.contains("ASUS Gamepad")) {
                // We can only do this check on KitKat or higher, but it doesn't matter since ATV
                // is Android 5.0 anyway
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    boolean[] hasStartKey = dev.hasKeys(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU, 0);
                    if (!hasStartKey[0] && !hasStartKey[1]) {
                        context.backIsStart = true;
                        context.modeIsSelect = true;
                    }
                }

                // The ASUS Gamepad has triggers that sit far forward and are prone to false presses
                // so we increase the deadzone on them to minimize this
                context.triggerDeadzone = 0.30f;
            }
            // Classify this device as a remote by name
            else if (devName.contains("Fire TV Remote") || devName.contains("Nexus Remote")) {
                // It's only a remote if it doesn't any sticks
                if (!context.hasJoystickAxes) {
                    context.ignoreBack = true;
                }
            }
            // SHIELD controllers will use small stick deadzones
            else if (devName.contains("SHIELD")) {
                context.leftStickDeadzoneRadius = 0.07f;
                context.rightStickDeadzoneRadius = 0.07f;
            }
            // Samsung's face buttons appear as a non-virtual button so we'll explicitly ignore
            // back presses on this device
            else if (devName.equals("sec_touchscreen") || devName.equals("sec_touchkey")) {
                context.ignoreBack = true;
            }
            // The Serval has a couple of unknown buttons that are start and select. It also has
            // a back button which we want to ignore since there's already a select button.
            else if (devName.contains("Razer Serval")) {
                context.isServal = true;
                context.ignoreBack = true;
            }
            // The Xbox One S Bluetooth controller has some mappings that need fixing up
            else if (devName.equals("Xbox Wireless Controller")) {
                context.isXboxBtController = true;
            }
        }

        LimeLog.info("Analog stick deadzone: "+context.leftStickDeadzoneRadius+" "+context.rightStickDeadzoneRadius);
        LimeLog.info("Trigger deadzone: "+context.triggerDeadzone);

        return context;
    }

    private InputDeviceContext getContextForDevice(InputDevice dev) {
        // Unknown devices use the default context
        if (dev == null) {
            return defaultContext;
        }

        // Return the existing context if it exists
        InputDeviceContext context = inputDeviceContexts.get(dev.getId());
        if (context != null) {
            return context;
        }

        // Otherwise create a new context
        context = createInputDeviceContextForDevice(dev);
        inputDeviceContexts.put(dev.getId(), context);

        return context;
    }

    private byte maxByMagnitude(byte a, byte b) {
        int absA = Math.abs(a);
        int absB = Math.abs(b);
        if (absA > absB) {
            return a;
        }
        else {
            return b;
        }
    }

    private short maxByMagnitude(short a, short b) {
        int absA = Math.abs(a);
        int absB = Math.abs(b);
        if (absA > absB) {
            return a;
        }
        else {
            return b;
        }
    }

    private void sendControllerInputPacket(GenericControllerContext originalContext) {
        assignControllerNumberIfNeeded(originalContext);

        // Take the context's controller number and fuse all inputs with the same number
        short controllerNumber = originalContext.controllerNumber;
        short inputMap = 0;
        byte leftTrigger = 0;
        byte rightTrigger = 0;
        short leftStickX = 0;
        short leftStickY = 0;
        short rightStickX = 0;
        short rightStickY = 0;

        // In order to properly handle controllers that are split into multiple devices,
        // we must aggregate all controllers with the same controller number into a single
        // device before we send it.
        for (int i = 0; i < inputDeviceContexts.size(); i++) {
            GenericControllerContext context = inputDeviceContexts.valueAt(i);
            if (context.assignedControllerNumber &&
                    context.controllerNumber == controllerNumber &&
                    context.mouseEmulationActive == originalContext.mouseEmulationActive) {
                inputMap |= context.inputMap;
                leftTrigger |= maxByMagnitude(leftTrigger, context.leftTrigger);
                rightTrigger |= maxByMagnitude(rightTrigger, context.rightTrigger);
                leftStickX |= maxByMagnitude(leftStickX, context.leftStickX);
                leftStickY |= maxByMagnitude(leftStickY, context.leftStickY);
                rightStickX |= maxByMagnitude(rightStickX, context.rightStickX);
                rightStickY |= maxByMagnitude(rightStickY, context.rightStickY);
            }
        }
        for (int i = 0; i < usbDeviceContexts.size(); i++) {
            GenericControllerContext context = usbDeviceContexts.valueAt(i);
            if (context.assignedControllerNumber &&
                    context.controllerNumber == controllerNumber &&
                    context.mouseEmulationActive == originalContext.mouseEmulationActive) {
                inputMap |= context.inputMap;
                leftTrigger |= maxByMagnitude(leftTrigger, context.leftTrigger);
                rightTrigger |= maxByMagnitude(rightTrigger, context.rightTrigger);
                leftStickX |= maxByMagnitude(leftStickX, context.leftStickX);
                leftStickY |= maxByMagnitude(leftStickY, context.leftStickY);
                rightStickX |= maxByMagnitude(rightStickX, context.rightStickX);
                rightStickY |= maxByMagnitude(rightStickY, context.rightStickY);
            }
        }
        if (defaultContext.controllerNumber == controllerNumber) {
            inputMap |= defaultContext.inputMap;
            leftTrigger |= maxByMagnitude(leftTrigger, defaultContext.leftTrigger);
            rightTrigger |= maxByMagnitude(rightTrigger, defaultContext.rightTrigger);
            leftStickX |= maxByMagnitude(leftStickX, defaultContext.leftStickX);
            leftStickY |= maxByMagnitude(leftStickY, defaultContext.leftStickY);
            rightStickX |= maxByMagnitude(rightStickX, defaultContext.rightStickX);
            rightStickY |= maxByMagnitude(rightStickY, defaultContext.rightStickY);
        }

        if (originalContext.mouseEmulationActive) {
            int changedMask = inputMap ^  originalContext.mouseEmulationLastInputMap;

            boolean aDown = (inputMap & ControllerPacket.A_FLAG) != 0;
            boolean bDown = (inputMap & ControllerPacket.B_FLAG) != 0;

            originalContext.mouseEmulationLastInputMap = inputMap;

            if ((changedMask & ControllerPacket.A_FLAG) != 0) {
                if (aDown) {
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                }
                else {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                }
            }
            if ((changedMask & ControllerPacket.B_FLAG) != 0) {
                if (bDown) {
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                }
                else {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                }
            }

            conn.sendControllerInput(controllerNumber,
                    (short)0, (byte)0, (byte)0, (short)0, (short)0, (short)0, (short)0);
        }
        else {
            conn.sendControllerInput(controllerNumber, inputMap,
                    leftTrigger, rightTrigger,
                    leftStickX, leftStickY,
                    rightStickX, rightStickY);
        }
    }

    // Return a valid keycode, 0 to consume, or -1 to not consume the event
    // Device MAY BE NULL
    private int handleRemapping(InputDeviceContext context, KeyEvent event) {
        // Don't capture the back button if configured
        if (context.ignoreBack) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                return -1;
            }
        }

        if (context.isDualShock4) {
            switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_Y:
                return KeyEvent.KEYCODE_BUTTON_L1;

            case KeyEvent.KEYCODE_BUTTON_Z:
                return KeyEvent.KEYCODE_BUTTON_R1;

            case KeyEvent.KEYCODE_BUTTON_C:
                return KeyEvent.KEYCODE_BUTTON_B;

            case KeyEvent.KEYCODE_BUTTON_X:
                return KeyEvent.KEYCODE_BUTTON_Y;

            case KeyEvent.KEYCODE_BUTTON_B:
                return KeyEvent.KEYCODE_BUTTON_A;

            case KeyEvent.KEYCODE_BUTTON_A:
                return KeyEvent.KEYCODE_BUTTON_X;

            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return KeyEvent.KEYCODE_BUTTON_THUMBL;

            case KeyEvent.KEYCODE_BUTTON_START:
                return KeyEvent.KEYCODE_BUTTON_THUMBR;

            case KeyEvent.KEYCODE_BUTTON_L2:
                return KeyEvent.KEYCODE_BUTTON_SELECT;

            case KeyEvent.KEYCODE_BUTTON_R2:
                return KeyEvent.KEYCODE_BUTTON_START;

            // These are duplicate trigger events
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_L1:
                return 0;
            }
        }
        // If this is a Serval controller sending an unknown key code, it's probably
        // the start and select buttons
        else if (context.isServal && event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN) {
            switch (event.getScanCode())  {
                case 314:
                    return KeyEvent.KEYCODE_BUTTON_SELECT;
                case 315:
                    return KeyEvent.KEYCODE_BUTTON_START;
            }
        }
        else if (context.isXboxBtController) {
            switch (event.getScanCode()) {
                case 306:
                    return KeyEvent.KEYCODE_BUTTON_X;
                case 307:
                    return KeyEvent.KEYCODE_BUTTON_Y;
                case 308:
                    return KeyEvent.KEYCODE_BUTTON_L1;
                case 309:
                    return KeyEvent.KEYCODE_BUTTON_R1;
                case 310:
                    return KeyEvent.KEYCODE_BUTTON_SELECT;
                case 311:
                    return KeyEvent.KEYCODE_BUTTON_START;
                case 312:
                    return KeyEvent.KEYCODE_BUTTON_THUMBL;
                case 313:
                    return KeyEvent.KEYCODE_BUTTON_THUMBR;
                case 139:
                    return KeyEvent.KEYCODE_BUTTON_MODE;
                default:
                    // Other buttons are mapped correctly
            }
        }

        if (context.hatXAxis != -1 && context.hatYAxis != -1) {
            switch (event.getKeyCode()) {
            // These are duplicate dpad events for hat input
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return 0;
            }
        }
        else if (context.hatXAxis == -1 &&
                 context.hatYAxis == -1 &&
                 context.isXboxController &&
                 event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN) {
            // If there's not a proper Xbox controller mapping, we'll translate the raw d-pad
            // scan codes into proper key codes
            switch (event.getScanCode())
            {
            case 704:
                return KeyEvent.KEYCODE_DPAD_LEFT;
            case 705:
                return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 706:
                return KeyEvent.KEYCODE_DPAD_UP;
            case 707:
                return KeyEvent.KEYCODE_DPAD_DOWN;
            }
        }

        // Past here we can fixup the keycode and potentially trigger
        // another special case so we need to remember what keycode we're using
        int keyCode = event.getKeyCode();

        // This is a hack for (at least) the "Tablet Remote" app
        // which sends BACK with META_ALT_ON instead of KEYCODE_BUTTON_B
        if (keyCode == KeyEvent.KEYCODE_BACK &&
                !event.hasNoModifiers() &&
                (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0)
        {
            keyCode = KeyEvent.KEYCODE_BUTTON_B;
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_START ||
                keyCode == KeyEvent.KEYCODE_MENU) {
            // Ensure that we never use back as start if we have a real start
            context.backIsStart = false;
        }
        else if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            // Don't use mode as select if we have a select
            context.modeIsSelect = false;
        }
        else if (context.backIsStart && keyCode == KeyEvent.KEYCODE_BACK) {
            // Emulate the start button with back
            return KeyEvent.KEYCODE_BUTTON_START;
        }
        else if (context.modeIsSelect && keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            // Emulate the select button with mode
            return KeyEvent.KEYCODE_BUTTON_SELECT;
        }

        return keyCode;
    }

    private Vector2d populateCachedVector(float x, float y) {
        // Reinitialize our cached Vector2d object
        inputVector.initialize(x, y);
        return inputVector;
    }

    private void handleDeadZone(Vector2d stickVector, float deadzoneRadius) {
        if (stickVector.getMagnitude() <= deadzoneRadius) {
            // Deadzone
            stickVector.initialize(0, 0);
        }

        // We're not normalizing here because we let the computer handle the deadzones.
        // Normalizing can make the deadzones larger than they should be after the computer also
        // evaluates the deadzone.
    }

    private void handleAxisSet(InputDeviceContext context, float lsX, float lsY, float rsX,
                               float rsY, float lt, float rt, float hatX, float hatY) {

        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            Vector2d leftStickVector = populateCachedVector(lsX, lsY);

            handleDeadZone(leftStickVector, context.leftStickDeadzoneRadius);

            context.leftStickX = (short) (leftStickVector.getX() * 0x7FFE);
            context.leftStickY = (short) (-leftStickVector.getY() * 0x7FFE);
        }

        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            Vector2d rightStickVector = populateCachedVector(rsX, rsY);

            handleDeadZone(rightStickVector, context.rightStickDeadzoneRadius);

            context.rightStickX = (short) (rightStickVector.getX() * 0x7FFE);
            context.rightStickY = (short) (-rightStickVector.getY() * 0x7FFE);
        }

        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            if (context.triggersIdleNegative) {
                lt = (lt + 1) / 2;
                rt = (rt + 1) / 2;
            }

            if (lt <= context.triggerDeadzone) {
                lt = 0;
            }
            if (rt <= context.triggerDeadzone) {
                rt = 0;
            }

            context.leftTrigger = (byte)(lt * 0xFF);
            context.rightTrigger = (byte)(rt * 0xFF);
        }

        if (context.hatXAxis != -1 && context.hatYAxis != -1) {
            context.inputMap &= ~(ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG);
            if (hatX < -0.5) {
                context.inputMap |= ControllerPacket.LEFT_FLAG;
            }
            else if (hatX > 0.5) {
                context.inputMap |= ControllerPacket.RIGHT_FLAG;
            }

            context.inputMap &= ~(ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG);
            if (hatY < -0.5) {
                context.inputMap |= ControllerPacket.UP_FLAG;
            }
            else if (hatY > 0.5) {
                context.inputMap |= ControllerPacket.DOWN_FLAG;
            }
        }

        sendControllerInputPacket(context);
    }

    public boolean handleMotionEvent(MotionEvent event) {
        InputDeviceContext context = getContextForDevice(event.getDevice());
        float lsX = 0, lsY = 0, rsX = 0, rsY = 0, rt = 0, lt = 0, hatX = 0, hatY = 0;

        // We purposefully ignore the historical values in the motion event as it makes
        // the controller feel sluggish for some users.

        if (context.leftStickXAxis != -1 && context.leftStickYAxis != -1) {
            lsX = event.getAxisValue(context.leftStickXAxis);
            lsY = event.getAxisValue(context.leftStickYAxis);
        }

        if (context.rightStickXAxis != -1 && context.rightStickYAxis != -1) {
            rsX = event.getAxisValue(context.rightStickXAxis);
            rsY = event.getAxisValue(context.rightStickYAxis);
        }

        if (context.leftTriggerAxis != -1 && context.rightTriggerAxis != -1) {
            lt = event.getAxisValue(context.leftTriggerAxis);
            rt = event.getAxisValue(context.rightTriggerAxis);
        }

        if (context.hatXAxis != -1 && context.hatYAxis != -1) {
            hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        }

        handleAxisSet(context, lsX, lsY, rsX, rsY, lt, rt, hatX, hatY);

        return true;
    }

    private short scaleRawStickAxis(float stickValue) {
        return (short)Math.pow(stickValue, 3);
    }

    private void sendEmulatedMouseEvent(short x, short y) {
        Vector2d vector = new Vector2d();
        vector.initialize(x, y);
        vector.scalarMultiply(1 / 32766.0f);
        vector.scalarMultiply(4);
        if (vector.getMagnitude() > 0) {
            // Move faster as the stick is pressed further from center
            vector.scalarMultiply(Math.pow(vector.getMagnitude(), 2));
            if (vector.getMagnitude() >= 1) {
                conn.sendMouseMove((short)vector.getX(), (short)-vector.getY());
            }
        }
    }

    private void toggleMouseEmulation(final GenericControllerContext context) {
        if (context.mouseEmulationTimer != null) {
            context.mouseEmulationTimer.cancel();
            context.mouseEmulationTimer = null;
        }

        context.mouseEmulationActive = !context.mouseEmulationActive;
        Toast.makeText(activityContext, "Mouse emulation is: " + (context.mouseEmulationActive ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();

        if (context.mouseEmulationActive) {
            context.mouseEmulationTimer = new Timer();
            context.mouseEmulationTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    // Send mouse movement events from analog sticks
                    sendEmulatedMouseEvent(context.leftStickX, context.leftStickY);
                    sendEmulatedMouseEvent(context.rightStickX, context.rightStickY);
                }
            }, 50, 50);
        }
    }

    public boolean handleButtonUp(KeyEvent event) {
        InputDeviceContext context = getContextForDevice(event.getDevice());

        int keyCode = handleRemapping(context, event);
        if (keyCode == 0) {
            return true;
        }

        // If the button hasn't been down long enough, sleep for a bit before sending the up event
        // This allows "instant" button presses (like OUYA's virtual menu button) to work. This
        // path should not be triggered during normal usage.
        if (SystemClock.uptimeMillis() - event.getDownTime() < ControllerHandler.MINIMUM_BUTTON_DOWN_TIME_MS)
        {
            // Since our sleep time is so short (10 ms), it shouldn't cause a problem doing this in the
            // UI thread.
            try {
                Thread.sleep(ControllerHandler.MINIMUM_BUTTON_DOWN_TIME_MS);
            } catch (InterruptedException ignored) {}
        }

        switch (keyCode) {
        case KeyEvent.KEYCODE_BUTTON_MODE:
            context.inputMap &= ~ControllerPacket.SPECIAL_BUTTON_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_START:
        case KeyEvent.KEYCODE_MENU:
            if (SystemClock.uptimeMillis() - context.startDownTime > ControllerHandler.START_DOWN_TIME_MOUSE_MODE_MS) {
                toggleMouseEmulation(context);
            }
            context.inputMap &= ~ControllerPacket.PLAY_FLAG;
            break;
        case KeyEvent.KEYCODE_BACK:
        case KeyEvent.KEYCODE_BUTTON_SELECT:
            context.inputMap &= ~ControllerPacket.BACK_FLAG;
            break;
        case KeyEvent.KEYCODE_DPAD_LEFT:
            context.inputMap &= ~ControllerPacket.LEFT_FLAG;
            break;
        case KeyEvent.KEYCODE_DPAD_RIGHT:
            context.inputMap &= ~ControllerPacket.RIGHT_FLAG;
            break;
        case KeyEvent.KEYCODE_DPAD_UP:
            context.inputMap &= ~ControllerPacket.UP_FLAG;
            break;
        case KeyEvent.KEYCODE_DPAD_DOWN:
            context.inputMap &= ~ControllerPacket.DOWN_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_B:
            context.inputMap &= ~ControllerPacket.B_FLAG;
            break;
        case KeyEvent.KEYCODE_DPAD_CENTER:
        case KeyEvent.KEYCODE_BUTTON_A:
            context.inputMap &= ~ControllerPacket.A_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_X:
            context.inputMap &= ~ControllerPacket.X_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_Y:
            context.inputMap &= ~ControllerPacket.Y_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_L1:
            context.inputMap &= ~ControllerPacket.LB_FLAG;
            context.lastLbUpTime = SystemClock.uptimeMillis();
            break;
        case KeyEvent.KEYCODE_BUTTON_R1:
            context.inputMap &= ~ControllerPacket.RB_FLAG;
            context.lastRbUpTime = SystemClock.uptimeMillis();
            break;
        case KeyEvent.KEYCODE_BUTTON_THUMBL:
            context.inputMap &= ~ControllerPacket.LS_CLK_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_THUMBR:
            context.inputMap &= ~ControllerPacket.RS_CLK_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_L2:
            context.leftTrigger = 0;
            break;
        case KeyEvent.KEYCODE_BUTTON_R2:
            context.rightTrigger = 0;
            break;
        default:
            return false;
        }

        // Check if we're emulating the select button
        if ((context.emulatingButtonFlags & ControllerHandler.EMULATING_SELECT) != 0)
        {
            // If either start or LB is up, select comes up too
            if ((context.inputMap & ControllerPacket.PLAY_FLAG) == 0 ||
                (context.inputMap & ControllerPacket.LB_FLAG) == 0)
            {
                context.inputMap &= ~ControllerPacket.BACK_FLAG;

                context.emulatingButtonFlags &= ~ControllerHandler.EMULATING_SELECT;

                try {
                    Thread.sleep(EMULATED_SELECT_UP_DELAY_MS);
                } catch (InterruptedException ignored) {}
            }
        }

        // Check if we're emulating the special button
        if ((context.emulatingButtonFlags & ControllerHandler.EMULATING_SPECIAL) != 0)
        {
            // If either start or select and RB is up, the special button comes up too
            if ((context.inputMap & ControllerPacket.PLAY_FLAG) == 0 ||
                ((context.inputMap & ControllerPacket.BACK_FLAG) == 0 &&
                 (context.inputMap & ControllerPacket.RB_FLAG) == 0))
            {
                context.inputMap &= ~ControllerPacket.SPECIAL_BUTTON_FLAG;

                context.emulatingButtonFlags &= ~ControllerHandler.EMULATING_SPECIAL;

                try {
                    Thread.sleep(EMULATED_SPECIAL_UP_DELAY_MS);
                } catch (InterruptedException ignored) {}
            }
        }

        sendControllerInputPacket(context);
        return true;
    }

    public boolean handleButtonDown(KeyEvent event) {
        InputDeviceContext context = getContextForDevice(event.getDevice());

        int keyCode = handleRemapping(context, event);
        if (keyCode == 0) {
            return true;
        }

        switch (keyCode) {
        case KeyEvent.KEYCODE_BUTTON_MODE:
            context.inputMap |= ControllerPacket.SPECIAL_BUTTON_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_START:
        case KeyEvent.KEYCODE_MENU:
            if (event.getRepeatCount() == 0) {
                context.startDownTime = SystemClock.uptimeMillis();
            }
            context.inputMap |= ControllerPacket.PLAY_FLAG;
            break;
        case KeyEvent.KEYCODE_BACK:
        case KeyEvent.KEYCODE_BUTTON_SELECT:
            context.inputMap |= ControllerPacket.BACK_FLAG;
            break;
        case KeyEvent.KEYCODE_DPAD_LEFT:
            context.inputMap |= ControllerPacket.LEFT_FLAG;
            break;
        case KeyEvent.KEYCODE_DPAD_RIGHT:
            context.inputMap |= ControllerPacket.RIGHT_FLAG;
            break;
        case KeyEvent.KEYCODE_DPAD_UP:
            context.inputMap |= ControllerPacket.UP_FLAG;
            break;
        case KeyEvent.KEYCODE_DPAD_DOWN:
            context.inputMap |= ControllerPacket.DOWN_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_B:
            context.inputMap |= ControllerPacket.B_FLAG;
            break;
        case KeyEvent.KEYCODE_DPAD_CENTER:
        case KeyEvent.KEYCODE_BUTTON_A:
            context.inputMap |= ControllerPacket.A_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_X:
            context.inputMap |= ControllerPacket.X_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_Y:
            context.inputMap |= ControllerPacket.Y_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_L1:
            context.inputMap |= ControllerPacket.LB_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_R1:
            context.inputMap |= ControllerPacket.RB_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_THUMBL:
            context.inputMap |= ControllerPacket.LS_CLK_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_THUMBR:
            context.inputMap |= ControllerPacket.RS_CLK_FLAG;
            break;
        case KeyEvent.KEYCODE_BUTTON_L2:
            context.leftTrigger = (byte)0xFF;
            break;
        case KeyEvent.KEYCODE_BUTTON_R2:
            context.rightTrigger = (byte)0xFF;
            break;
        default:
            return false;
        }

        // Start+LB acts like select for controllers with one button
        if ((context.inputMap & ControllerPacket.PLAY_FLAG) != 0 &&
            ((context.inputMap & ControllerPacket.LB_FLAG) != 0 ||
              SystemClock.uptimeMillis() - context.lastLbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS))
        {
            context.inputMap &= ~(ControllerPacket.PLAY_FLAG | ControllerPacket.LB_FLAG);
            context.inputMap |= ControllerPacket.BACK_FLAG;

            context.emulatingButtonFlags |= ControllerHandler.EMULATING_SELECT;
        }

        // We detect select+start or start+RB as the special button combo
        if (((context.inputMap & ControllerPacket.RB_FLAG) != 0 ||
             (SystemClock.uptimeMillis() - context.lastRbUpTime <= MAXIMUM_BUMPER_UP_DELAY_MS) ||
             (context.inputMap & ControllerPacket.BACK_FLAG) != 0) &&
            (context.inputMap & ControllerPacket.PLAY_FLAG) != 0)
        {
            context.inputMap &= ~(ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG | ControllerPacket.RB_FLAG);
            context.inputMap |= ControllerPacket.SPECIAL_BUTTON_FLAG;

            context.emulatingButtonFlags |= ControllerHandler.EMULATING_SPECIAL;
        }

        // Send a new input packet if this is the first instance of a button down event
        // or anytime if we're emulating a button
        if (event.getRepeatCount() == 0 || context.emulatingButtonFlags != 0) {
            sendControllerInputPacket(context);
        }
        return true;
    }

    @Override
    public void reportControllerState(int controllerId, short buttonFlags,
                                      float leftStickX, float leftStickY,
                                      float rightStickX, float rightStickY,
                                      float leftTrigger, float rightTrigger) {
        UsbDeviceContext context = usbDeviceContexts.get(controllerId);

        Vector2d leftStickVector = populateCachedVector(leftStickX, leftStickY);

        handleDeadZone(leftStickVector, context.leftStickDeadzoneRadius);

        context.leftStickX = (short) (leftStickVector.getX() * 0x7FFE);
        context.leftStickY = (short) (-leftStickVector.getY() * 0x7FFE);

        Vector2d rightStickVector = populateCachedVector(rightStickX, rightStickY);

        handleDeadZone(rightStickVector, context.rightStickDeadzoneRadius);

        context.rightStickX = (short) (rightStickVector.getX() * 0x7FFE);
        context.rightStickY = (short) (-rightStickVector.getY() * 0x7FFE);

        if (leftTrigger <= context.triggerDeadzone) {
            leftTrigger = 0;
        }
        if (rightTrigger <= context.triggerDeadzone) {
            rightTrigger = 0;
        }

        context.leftTrigger = (byte)(leftTrigger * 0xFF);
        context.rightTrigger = (byte)(rightTrigger * 0xFF);

        context.inputMap = buttonFlags;

        sendControllerInputPacket(context);
    }

    @Override
    public void deviceRemoved(int controllerId) {
        UsbDeviceContext context = usbDeviceContexts.get(controllerId);
        if (context != null) {
            LimeLog.info("Removed controller: "+controllerId);
            releaseControllerNumber(context);
            usbDeviceContexts.remove(controllerId);
        }
    }

    @Override
    public void deviceAdded(int controllerId) {
        UsbDeviceContext context = createUsbDeviceContextForDevice(controllerId);
        usbDeviceContexts.put(controllerId, context);
    }

    class GenericControllerContext {
        public int id;

        public float leftStickDeadzoneRadius;
        public float rightStickDeadzoneRadius;
        public float triggerDeadzone;

        public boolean assignedControllerNumber;
        public boolean reservedControllerNumber;
        public short controllerNumber;

        public short inputMap = 0x0000;
        public byte leftTrigger = 0x00;
        public byte rightTrigger = 0x00;
        public short rightStickX = 0x0000;
        public short rightStickY = 0x0000;
        public short leftStickX = 0x0000;
        public short leftStickY = 0x0000;

        public boolean mouseEmulationActive;
        public Timer mouseEmulationTimer;
        public short mouseEmulationLastInputMap;
    }

    class InputDeviceContext extends GenericControllerContext {
        public String name;

        public int leftStickXAxis = -1;
        public int leftStickYAxis = -1;

        public int rightStickXAxis = -1;
        public int rightStickYAxis = -1;

        public int leftTriggerAxis = -1;
        public int rightTriggerAxis = -1;
        public boolean triggersIdleNegative;

        public int hatXAxis = -1;
        public int hatYAxis = -1;

        public boolean isDualShock4;
        public boolean isXboxController;
        public boolean isXboxBtController;
        public boolean isServal;
        public boolean backIsStart;
        public boolean modeIsSelect;
        public boolean ignoreBack;
        public boolean hasJoystickAxes;

        public int emulatingButtonFlags = 0;

        // Used for OUYA bumper state tracking since they force all buttons
        // up when the OUYA button goes down. We watch the last time we get
        // a bumper up and compare that to our maximum delay when we receive
        // a Start button press to see if we should activate one of our
        // emulated button combos.
        public long lastLbUpTime = 0;
        public long lastRbUpTime = 0;

        public long startDownTime = 0;
    }

    class UsbDeviceContext extends GenericControllerContext {}
}
