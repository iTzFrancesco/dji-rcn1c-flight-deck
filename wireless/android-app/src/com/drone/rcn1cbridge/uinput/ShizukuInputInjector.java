package com.drone.rcn1cbridge.uinput;

import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Compatibility fallback used only when shell UID cannot open /dev/uinput.
 * It injects joystick/key events through Android's IInputManager via Shizuku.
 * Some games require a real InputDevice and may ignore this mode, hence uinput stays preferred.
 */
final class ShizukuInputInjector {
    private static final String TAG = "RCN1C-InputInject";
    private static final int SOURCE = InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK;

    private Object inputManager;
    private Method injectMethod;
    private int lastButtons;

    boolean init() {
        try {
            Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            IBinder systemInput = SystemServiceHelper.getSystemService("input");
            inputManager = asInterface.invoke(null, new ShizukuBinderWrapper(systemInput));
            injectMethod = inputManager.getClass().getMethod(
                    "injectInputEvent", android.view.InputEvent.class, int.class);
            lastButtons = 0;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
            inputManager = null;
            injectMethod = null;
            return false;
        }
    }

    boolean isReady() {
        return inputManager != null && injectMethod != null;
    }

    void sendFrame(int buttons,
                   int leftX, int leftY,
                   int rightX, int rightY,
                   int leftTrigger, int rightTrigger,
                   int dpadX, int dpadY) {
        if (!isReady()) return;

        injectButtonEdges(buttons);

        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_UNKNOWN;

        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.setAxisValue(MotionEvent.AXIS_X, normaliseStick(leftX));
        pc.setAxisValue(MotionEvent.AXIS_Y, normaliseStick(leftY));
        pc.setAxisValue(MotionEvent.AXIS_RX, normaliseStick(rightX));
        pc.setAxisValue(MotionEvent.AXIS_RY, normaliseStick(rightY));
        pc.setAxisValue(MotionEvent.AXIS_LTRIGGER, clamp01(leftTrigger / 255f));
        pc.setAxisValue(MotionEvent.AXIS_RTRIGGER, clamp01(rightTrigger / 255f));
        pc.setAxisValue(MotionEvent.AXIS_HAT_X, clamp(dpadX, -1f, 1f));
        pc.setAxisValue(MotionEvent.AXIS_HAT_Y, clamp(dpadY, -1f, 1f));

        MotionEvent event = MotionEvent.obtain(
                now, now,
                MotionEvent.ACTION_MOVE,
                1,
                new MotionEvent.PointerProperties[]{pp},
                new MotionEvent.PointerCoords[]{pc},
                0, 0,
                1f, 1f,
                -1, 0,
                SOURCE, 0);
        inject(event);
        event.recycle();
    }

    private void injectButtonEdges(int buttons) {
        final int[] bits = {
                1 << 0, 1 << 1, 1 << 2, 1 << 3,
                1 << 4, 1 << 5, 1 << 6, 1 << 7,
                1 << 8, 1 << 9, 1 << 10
        };
        final int[] keys = {
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_SELECT,
                KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_BUTTON_MODE,
                KeyEvent.KEYCODE_BUTTON_THUMBL,
                KeyEvent.KEYCODE_BUTTON_THUMBR
        };
        for (int i = 0; i < bits.length; i++) {
            boolean now = (buttons & bits[i]) != 0;
            boolean before = (lastButtons & bits[i]) != 0;
            if (now != before) injectKey(keys[i], now);
        }
        lastButtons = buttons;
    }

    private void injectKey(int keyCode, boolean down) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(
                now, now,
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, 0,
                0, -1, 0,
                KeyEvent.FLAG_FROM_SYSTEM,
                SOURCE);
        inject(event);
    }

    private void inject(android.view.InputEvent event) {
        try {
            injectMethod.invoke(inputManager, event, 0);
        } catch (Throwable t) {
            Log.e(TAG, "inject failed", t);
        }
    }

    private static float normaliseStick(int value) {
        return clamp(value / 32767f, -1f, 1f);
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
