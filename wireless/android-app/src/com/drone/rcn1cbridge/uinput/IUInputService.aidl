package com.drone.rcn1cbridge.uinput;

interface IUInputService {
    boolean canCreateDevice();
    boolean createGamepad();
    void sendFrame(
        int buttons,
        int leftStickX, int leftStickY,
        int rightStickX, int rightStickY,
        int leftTrigger, int rightTrigger,
        int dpadX, int dpadY
    );
    void destroy();
}
