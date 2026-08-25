package com.drone.rcn1cbridge.uinput;

import android.content.Context;
import android.util.Log;

/**
 * Shizuku user-service. This class is instantiated in a separate shell-UID process,
 * which is the only place where the native /dev/uinput calls are attempted.
 */
public final class UInputService extends IUInputService.Stub {
    private static final String TAG = "RCN1C-UInputService";

    @SuppressWarnings("unused")
    public UInputService() {
        Log.i(TAG, "created (no-arg)");
    }

    @SuppressWarnings("unused")
    public UInputService(Context context) {
        Log.i(TAG, "created with context");
    }

    @Override
    public boolean canCreateDevice() {
        try {
            return UInputNative.canOpen();
        } catch (Throwable t) {
            Log.e(TAG, "canOpen failed", t);
            return false;
        }
    }

    @Override
    public boolean createGamepad() {
        try {
            return UInputNative.createDevice();
        } catch (Throwable t) {
            Log.e(TAG, "createGamepad failed", t);
            return false;
        }
    }

    @Override
    public void sendFrame(int buttons,
                          int leftStickX, int leftStickY,
                          int rightStickX, int rightStickY,
                          int leftTrigger, int rightTrigger,
                          int dpadX, int dpadY) {
        try {
            UInputNative.sendFrame(buttons,
                    leftStickX, leftStickY,
                    rightStickX, rightStickY,
                    leftTrigger, rightTrigger,
                    dpadX, dpadY);
        } catch (Throwable t) {
            Log.e(TAG, "sendFrame failed", t);
        }
    }

    @Override
    public void destroy() {
        try {
            UInputNative.destroy();
        } catch (Throwable t) {
            Log.e(TAG, "destroy failed", t);
        }
        // Shizuku user-service contract: terminate the dedicated process.
        System.exit(0);
    }
}
