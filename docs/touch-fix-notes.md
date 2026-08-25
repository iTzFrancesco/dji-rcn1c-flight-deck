# Portable Touch beta2

- Explicit game arming: Unity/immersive window events are no longer required to start touch injection.
- Initial two-finger gesture always has a non-empty path.
- Calibration centers can move almost to the display edges; radius is independent.
- State now reports ARMED / DISPATCHING / CANCELLED for easier hardware diagnosis.
- Shizuku/uinput remains available as Android Gamepad backup.
