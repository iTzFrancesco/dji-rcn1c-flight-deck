# AGENTS.md — Drone Project (DJI Mini 4K + RC-N1C)

Guidance for using the real **DJI RC-N1C** remote controller with drone flight simulators on Windows and Android.

## Hardware and environment

- A Windows PC with Python 3.12.
- A **DJI RC-N1C** remote controller (DJI Mini 4K), configured for Mode 2.
- A powered-on remote controller connected with a USB-C data cable. Use the **lower** USB-C port on the remote controller; the upper port is intended for the phone connection.
- The remote controller exposes separate VCOM ports for protocol data and debugging. Identify the assigned protocol port instead of assuming a fixed COM number.
- Python packages: `pyserial`, `vgamepad`, `websockets`, `python-dotenv`, and `colorama`.
- The **ViGEmBus** driver for the virtual Xbox controller.

## RC-N1C serial protocol (field-verified)

- Baud rate: 921600, 8N1. The VCOM may ignore the baud rate, but the high setting should still be used.
- Request: send the hexadecimal sequence `55 0d 04 33 0a 06 eb 34 40 06 01 74 24` (request/response flow: approximately one packet per request).
- Responses start with `0x55`; the next two bytes are little-endian, and the lower 10 bits contain the packet length.
- Stick-position packet length: **38 bytes**.
  - Offsets 13–14: RX (right-stick X)
  - Offsets 16–17: RY (right-stick Y)
  - Offsets 19–20: LY (left-stick Y)
  - Offsets 22–23: LX (left-stick X)
  - Offsets 25–26: camera wheel (dial)
  - Bit 0 of byte 28, labelled `Fn`, is not confirmed on the RC-N1C Mini 4K; probe v3 showed no change.
- Values are little-endian, range **364..1684**, center **1024**.
- Mode 2 mapping: the left stick controls throttle/yaw (throttle does **not** return to center vertically); the right stick controls pitch/roll.

## Files

| Path | Purpose |
|---|---|
| `dji_rcn1c_bridge.py` | RC → virtual Xbox 360 gamepad bridge through ViGEm. Runs indefinitely or with `--duration N`. |
| `AVVIA_BRIDGE.bat` | Daily launcher for the bridge. |
| `registra_volo.py` | FPV session recorder: FFmpeg screen capture (H.264, CRF 18, embedded `REC mm:ss.mmm` OSD, watchdog for long sessions) plus 100 Hz XInput logging in `registrazioni/` (synchronised MP4, CSV, and JSON). Supports `--duration N`, `--log-only`, `--no-osd`, `--window <title>`, `--crf`, and `--fps`; also exposes the `Recorder` class used by the dashboard. See `registrazioni/AGENTS.md`. |
| `analizza_volo.py` | Session analyser that processes the complete log and video: lap storyboard plus events with frames before, during, and after each event. `python analizza_volo.py [session] [--every 3]` writes `registrazioni/analisi_volo_<timestamp>/report.html`. |
| `AVVIA_REGISTRAZIONE.bat` | Flight-recorder launcher; stop with `Q` followed by Enter. |
| `wireless/rcn1c_phone_tx.py` | Phone-side Termux script: reads the RC USB serial connection and forwards sticks, wheel, and buttons to the PC over UDP. |
| `wireless/button_probe_27.py` | Guided probe for the DUML `0x27` button frame with a mapping report. |
| `wireless/button_live_probe.py` | Live monitor for the DUML `0x27` button frame without creating a virtual gamepad. |
| `rcn1c_protocol.py` | Shared constants and decoders for sticks, buttons, and flight modes. |
| `rcn1c_transport.py` | UDP v1/v2/v3 transport format between Android/Termux and the PC. |
| `wireless/RCN1C_Bridge.apk` | Ready-to-install Android app (v3.0): USB serial input, live buttons/mode, and UDP autodiscovery. |
| `wireless/android-app/` | Android app source (Java) and `build_apk.ps1` build script. |
| `wireless/rcn1c_wifi_rx_pc.py` | PC receiver: UDP port 26789 → virtual Xbox gamepad (sticks, wheel, and buttons). |
| `tests/run_all.py` | Full test suite: `py_compile` plus receiver/dashboard tests (`python tests\\run_all.py`; the suite should pass). |
| `AVVIA_WIFI_BRIDGE.bat` | Wi-Fi receiver launcher. |
| **Desktop: `RC-N1C Dashboard.vbs`** | One-click launcher for the server console and Chrome dashboard; it closes and restarts the components as needed. |
| `sandbox_read_test.py` | Four-stage guided serial diagnostic (left stick, right stick, wheel, and center). |
| `viz_app/controller_viz.py` | Control Center server: serial (`--source serial`, default) or Wi-Fi (`--source udp`, including the virtual gamepad), HTTP on port 8123, WebSocket on port 8124, and recording API (`/api/rec/start|stop|status`, with red REC OSD in the dashboard). |
| `viz_app/AVVIA_VIZ_WIFI.bat` | Wireless dashboard launcher. |
| `viz_app/static/index.html` | Real-time Flight Deck dashboard: stick pads, button matrix, flight mode, bandwidth, and DUML TX/RX console. |
| `viz_app/test_viz.py` | Self-test: HTTP 200, WebSocket `hello`/snapshot handshake, and packet counter. |
| `viz_app/AVVIA_VIZ.bat` | Dashboard launcher. |
| `DJI_RCN1_bridge/` | Original upstream bridge source. |

## Wireless mode (phone as a USB-to-Wi-Fi bridge)

The RC-N1C does not provide a direct Wi-Fi or Bluetooth connection to the PC. For wireless use, the phone acts as the bridge:

`RC → USB-C data cable → phone → UDP port 26789 → PC → virtual gamepad`

The v3 frame carries sticks, wheel, buttons, and flight mode.

**Method 1 — Android app (recommended):** install `wireless/RCN1C_Bridge.apk` (v3.0, landscape orientation) on the phone and connect the powered-on RC through the **lower** USB-C port. Grant USB permission. Leave the IP field empty for autodiscovery or enter it manually. The app shows sticks, buttons, flight mode, packet rate, and RTT. On the PC, launch `AVVIA_WIFI_BRIDGE.bat`, or use `viz_app\\AVVIA_VIZ_WIFI.bat` to run the dashboard and virtual gamepad in one process. Do **not** run both receivers at the same time. Rebuild the app with `wireless/android-app/build_apk.ps1` after configuring the Android SDK.

**Method 2 — Termux:** install **Termux** and **Termux:API** from F-Droid, then run `pkg install python termux-api`.

1. Connect the powered-on remote controller to the phone with a data-capable USB-C cable and grant USB permission.
2. In Termux:
   ```bash
   termux-usb -l                                  # note the path, for example /dev/bus/usb/002/003
   termux-usb -r /dev/bus/usb/002/003
   termux-usb -E -e "python rcn1c_phone_tx.py PC_IP_ADDRESS" /dev/bus/usb/002/003
   ```
3. On the PC, launch `AVVIA_WIFI_BRIDGE.bat` or run `wireless/rcn1c_wifi_rx_pc.py`.

The PC and phone must be on the same network. If no packets arrive, allow Python through the Windows firewall on private networks. Wireless adds approximately 5–15 ms over USB. The Wi-Fi adapter only connects the PC to the network; it does not communicate with the remote controller directly.

UDP v3 is 18 bytes: `<IHHHHHHBB` (sequence + RX/RY/LY/LX + wheel + button mask + mode + flags). Receivers also accept v1/v2.

On the RC-N1C, the 58-byte DUML `0x27` frame exposes `0x0060` shutter/recording, `0x0004` photo/video, `0x0080` RTH/STOP, and `0x0002` FN; `0x3000` indicates Sport/Normal/Cine. The wheel maps to A/B beyond a ±99 threshold. CSC stick input maps to LB/RB.

Autodiscovery uses the broadcast `RCN1C_DISC` on port 26790 and the `RCN1C_HERE` response. When the app's IP field is empty, it finds the PC automatically. All receivers lock to the first UDP sender to reduce LAN injection risk.

## Commands

```powershell
py -3.12 dji_rcn1c_bridge.py              # bridge for simulators
py -3.12 registra_volo.py                 # record video and stick data (Q + Enter to stop)
py -3.12 registra_volo.py --duration 1800 --crf 20  # 30 minutes with a smaller file
py -3.12 analizza_volo.py                 # analyse the latest session completely
py -3.12 analizza_volo.py session_name --every 2    # denser coverage → report.html
py -3.12 sandbox_read_test.py             # guided remote-controller test
py -3.12 viz_app/controller_viz.py        # dashboard (USB serial, opens the browser)
py -3.12 viz_app/controller_viz.py --source udp   # dashboard + gamepad through Wi-Fi bridge
py -3.12 viz_app/test_viz.py              # self-test (expected to pass)
```

## Important caveats

- Only one process at a time can open the protocol VCOM port. Close the bridge before starting the dashboard, and vice versa. **DJI Assistant 2 must also be closed** if it is holding the port.
- The same rule applies to UDP port 26789 in Wi-Fi mode: run either `AVVIA_WIFI_BRIDGE.bat` or `AVVIA_VIZ_WIFI.bat`, never both.
- The dashboard reconnects automatically every two seconds when the remote controller is powered off or disconnected and shows an “Idle” overlay.
- The camera wheel maps to virtual A/B buttons beyond approximately ±15% from center; this can change Windows focus or screens and is expected.
- The bridge publishes an `XBOX 360 For Windows` controller. Select that controller in the simulator's input settings.
- If input is limited to approximately 30 packets per second, check for other consumers on the port and verify that the high baud rate is configured.
- `registra_volo.py` reads the gamepad through XInput and does not lock the serial or UDP input. If the video is black, switch the simulator from exclusive fullscreen to borderless windowed mode. Start the bridge before recording, otherwise the stick log will contain zeros. The embedded REC OSD matches `t_s` in the CSV. Analyses cover every CSV row and every video second.

## Recommended simulator

**Liftoff FPV Drone Racing** is a recommended simulator for this project. It supports Angle mode (similar to the normal mode of camera drones) and Acro mode (FPV), with additional maps available through the Steam Workshop. Configure it under Options → Controls by selecting `Controller (XBOX 360 For Windows)` and using a small deadzone, such as approximately 2%.

## Project conventions

- Keep new and updated project documentation in English. Preserve current UI labels exactly when referring to buttons shown by the app.
- By default, no data leaves the PC: services bind to `127.0.0.1`, except the Wi-Fi receiver, which binds to `0.0.0.0` to receive packets from the phone.
- The dashboard must work offline without CDN or external resources.
- Avoid unnecessary code comments; add docstrings only when they improve usage or maintenance.

## Android releases and auto-update

- For every Android release, increment `versionCode` and `versionName`, rebuild the APK with the stable signing configuration, and verify the published asset.
- The app checks `releases/latest`. The release used for auto-update must not remain a prerelease. After the GitHub workflow completes, verify that the new release is marked **Latest** (`prerelease=false`, `make_latest=true`).
- Verify the latest release through the repository's GitHub API endpoint, for example `gh api repos/OWNER/REPOSITORY/releases/latest`, and confirm that it returns the new tag and `RCN1C_Bridge.apk`.
