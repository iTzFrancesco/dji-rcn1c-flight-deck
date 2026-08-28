# DJI RC-N1C Flight Deck

Unofficial open-source bridge for using the **DJI RC-N1C** remote controller with flight simulators on PC and Android.

> The repository/project is called **Flight Deck**; the Android app is called **RC-N1C Flight Bridge**.

The project provides:

- a virtual Xbox 360 controller through ViGEmBus on Windows;
- input from the sticks, camera wheel, and physical buttons;
- a real-time dashboard showing connection status, flight mode, and signal quality;
- wireless connectivity through Android or Termux;
- an Android app that sends the remote controller input to the PC over Wi-Fi and displays the dashboard;
- automatic detection of the USB port and the PC on the local network;
- automated Android app builds through GitHub Actions.

## Supported controls

| RC-N1C control | Virtual input |
| --- | --- |
| Left stick | LX / LY |
| Right stick | RX / RY |
| Camera wheel | A / B |
| Shutter / recording | Y |
| Photo/video selector | BACK |
| RTH / STOP | START |
| FN | X |
| Sport / Normal / Cine | Dashboard status |

## Getting started on Windows

Requirements:

- Windows with Python 3.12;
- the Python packages `pyserial`, `vgamepad`, and `websockets`;
- the ViGEmBus driver;
- a powered-on remote controller connected with a USB-C data cable.

Close any DJI applications that may be using the USB connection, then use one of the launchers:

- `AVVIA_BRIDGE.bat` — starts the virtual controller for simulators;
- `viz_app\AVVIA_VIZ.bat` — opens the dashboard and automatically detects the remote controller;
- `AVVIA_BUTTON_LIVE_PROBE.bat` — displays detected buttons without creating a gamepad.

The `RC-N1C Dashboard.vbs` Desktop shortcut is the one-click launcher: it detects USB or Wi-Fi,
opens the dashboard, and automatically creates the virtual Xbox controller. In USB mode it uses a single
process, so the protocol serial port is not opened simultaneously by the dashboard and the bridge.

The dashboard runs locally and does not use external services or resources.

## Demo

[![RC-N1C Flight Deck dashboard preview](docs/media/flight-deck-demo.gif)](docs/media/flight-deck-demo.mp4)

Click the preview to open the full recording.

## Android app: RC-N1C Flight Bridge

The APK provides a Wi-Fi bridge to the PC with a real-time dashboard:

```text
PC BRIDGE
RC-N1C → Android → UDP/Wi-Fi → PC → gamepad virtuale
```

The bridge supports local-network autodiscovery and displays sticks, buttons, flight mode, packet rate, and RTT. Termux is also supported as an alternative; see [docs/uso-wireless.md](docs/uso-wireless.md).

### Mobile FPV simulator

The same APK includes an initial local simulator for practicing with the RC-N1C
away from the PC:

```text
RC-N1C → Rcn1cUsbReader → WebView locale FPV.Sim → scena Acro
```

The FPV simulator option opens an offline 3D scene with Acro physics and
direct remote-controller input. It does not use root, an Accessibility Service, touch injection,
or a second USB bridge; the PC/Wi-Fi path remains available separately.
The open-source foundation and technical decisions are described in
[docs/ricerca-simulatori-fpv-mobile.md](docs/ricerca-simulatori-fpv-mobile.md) and
[docs/adr/0001-simulatore-mobile-webview-locale.md](docs/adr/0001-simulatore-mobile-webview-locale.md).

The first real-world test should be performed with the phone in landscape orientation,
the RC connected to the lower port through an OTG adapter, and USB permission granted. Button mapping
and a richer training scene will follow validation of the axes and performance on the Oppo A53s.

Building the APK requires JDK 17, Android SDK 34, and Gradle 8.7+.

## Updates

At startup, the Android app performs an initial version and remote-controller presence check without starting the bridge automatically. The start action remains manual. From Settings, you can change the IP address and port and check for updates: when a new version is found, the app asks for confirmation before downloading and opening the APK installer. Android always requires user confirmation for an APK installed outside the store.

On Windows, updates can also be checked from PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File tools\Update-DroneApp.ps1
powershell -ExecutionPolicy Bypass -File tools\Update-DroneApp.ps1 -InstallApk
```

The `.github/workflows/release.yml` workflow rebuilds the app, verifies its stable signature, and publishes `RCN1C_Bridge.apk` for stable releases.

## Development and testing

```powershell
$py = "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"
& $py tests\run_all.py
& $py tests\ui_smoke.py
```

The DUML protocol and verified mapping are described in [docs/protocollo-rcn1c.md](docs/protocollo-rcn1c.md). This project is intended for simulators and must not be used to pilot a real drone.

## License

Distributed under the [MIT License](LICENSE).
