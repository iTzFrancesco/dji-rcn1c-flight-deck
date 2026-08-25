# Portable Touch Bridge

`Portable Touch` is the default Android-only mode of **RC-N1C Flight Bridge**.

```text
RC-N1C -> USB/OTG -> Flight Bridge -> Android Accessibility gestures -> FPV game touch sticks
```

It does **not** require root, Shizuku, ADB, Wi-Fi, Internet or a PC. Android Gamepad/uinput remains available separately as the Shizuku-based backup.

## First setup

1. Install RC-N1C Flight Bridge.
2. Open **Portable Touch**.
3. Tap **ATTIVA ACCESSIBILITÀ** and enable `RC-N1C Portable Touch Bridge` once.
4. Install FPV Freerider Demo or the full FPV Freerider.
5. In Flight Bridge tap **APRI + CALIBRA**.
6. Drag the `L` and `R` circles over the game's two virtual sticks, adjust radius and press **SALVA**.
7. Connect the RC-N1C over USB/OTG, press **AVVIA**, then open FPV Freerider.

The service only injects touches while a supported FPV simulator is foreground and the RC bridge is active. Leaving the simulator releases both virtual fingers.

## Supported packages in the first beta

- `com.Freeride.Freerider_FREE`
- `com.Freeride.Freerider`
- `com.FullFocusStudio.FeelFPV`
- `com.Orqa.FPVSkyDive`

The calibrated positions are stored as screen-relative values, so they survive app restarts and remain portable across sessions at the same orientation.

## Performance

The touch chain runs at roughly 30-40 Hz and contains no 3D rendering. The phone therefore spends most CPU/GPU time in the simulator rather than Flight Bridge.

## Backup mode

If a game ignores Accessibility-injected touch, use **Android Gamepad · Backup**. That path keeps the existing Shizuku + `uinput`/InputManager implementation and exposes an Xbox-style controller when the ROM permits it.
