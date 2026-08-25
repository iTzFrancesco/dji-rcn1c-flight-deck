package com.drone.rcn1cbridge.uinput;

final class UInputNative {
    static {
        System.loadLibrary("rcn1c_uinput");
    }

    private UInputNative() {}

    static native boolean canOpen();
    static native boolean createDevice();
    static native void sendFrame(
            int buttons,
            int leftStickX, int leftStickY,
            int rightStickX, int rightStickY,
            int leftTrigger, int rightTrigger,
            int dpadX, int dpadY);
    static native void destroy();
}
