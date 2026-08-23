# RC-N1C // Flight Deck

Bridge locale per usare il radiocomando **DJI RC-N1C** del DJI Mini 4K come controller Xbox 360 virtuale, con dashboard real-time e ponte wireless opzionale tramite Android/Termux.

Il progetto legge gli stick e il pacchetto DUML dei comandi fisici. Oggi sono verificati sul radiocomando reale:

| Controllo RC-N1C | Mask rilevata | Controller virtuale |
| --- | ---: | --- |
| Stick sinistro/destra | — | assi LX/LY e RX/RY |
| Rotella camera sinistra/destra | — | A/B |
| Scatto / avvio-stop registrazione | `0x1060` | Y |
| Selettore foto/video | `0x1004` | BACK |
| RTH / STOP | `0x1080` | START |
| FN | `0x1002` | X |
| Sport / Normal / Cine | `0x0000` / `0x1000` / `0x2000` | stato dashboard |

## Avvio rapido su Windows

1. Accendi l'RC-N1C e usa la porta USB-C **inferiore**.
2. Chiudi DJI Assistant 2: può tenere occupata la porta seriale.
3. Avvia uno dei launcher:

   - `AVVIA_BRIDGE.bat` — gamepad Xbox virtuale per simulatori.
   - `viz_app\AVVIA_VIZ.bat` — Control Center nel browser, con autodetection della porta.
   - `AVVIA_BUTTON_LIVE_PROBE.bat` — monitor visibile per verificare i pulsanti senza creare gamepad.

La porta cercata automaticamente è `DEVICE USB VCOM For Protocol` (normalmente COM4). Non serve inserire COM4 a mano.

Prerequisiti: Python 3.12 con `pyserial`, `vgamepad`, `websockets` e ViGEmBus installato. Il bridge seriale e la dashboard USB richiedono Windows; la dashboard non carica risorse esterne e funziona offline.

## Modalità wireless

La modalità wireless usa il telefono come ponte USB → UDP:

`RC-N1C → USB-C → Android → UDP :26789 → PC → gamepad/dashboard`

Sul PC avvia `viz_app\AVVIA_VIZ_WIFI.bat` oppure `AVVIA_WIFI_BRIDGE.bat`, mai entrambi insieme. Nell'app Android l'IP può rimanere vuoto: il PC risponde all'autodiscovery UDP sulla porta 26790. Termux resta disponibile come alternativa documentata in [docs/uso-wireless.md](docs/uso-wireless.md).

L'app Android pronta è `wireless\RCN1C_Bridge.apk`. Per ricostruirla:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
powershell -ExecutionPolicy Bypass -File wireless\android-app\build_apk.ps1
```

## Cosa è cambiato rispetto al tentativo precedente

La richiesta `0x01` restituiva soltanto il frame stick da 38 byte. I tasti fisici arrivano invece dalla richiesta DUML `0x27`, con frame da 58 byte e mask a offset 28–29. Il vecchio scanner costruiva l'header con `0x40`; il flag corretto è `0x04`, quindi la richiesta sembrava valida ma non veniva interpretata dal radiocomando. Ora il protocollo comune è in `rcn1c_protocol.py` e il trasporto UDP v3 in `rcn1c_transport.py`.

Dettagli, packet sample e fonti sono in [docs/protocollo-rcn1c.md](docs/protocollo-rcn1c.md).

## Aggiornamenti

Il repository è predisposto per pubblicare release GitHub dell'APK: [`.github/workflows/release.yml`](.github/workflows/release.yml) ricostruisce l'app a ogni tag `v*`. Dopo aver scelto il repository pubblico, inserire `owner/repository` in `config/update.json` e usare:

```powershell
powershell -ExecutionPolicy Bypass -File tools\Update-DroneApp.ps1
powershell -ExecutionPolicy Bypass -File tools\Update-DroneApp.ps1 -InstallApk
```

Il primo comando controlla soltanto; il secondo scarica l'APK della release più recente, verifica l'hash se GitHub lo espone e conserva una copia `.bak`. Non è stato fatto alcun push perché manca ancora il nome/URL del repository pubblico.

## Test

```powershell
$py = "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"
& $py tests\run_all.py
& $py tests\ui_smoke.py
```

Il test UI avvia una dashboard effimera, invia frame v1/v2/v3 sintetici e controlla stick, modalità, pulsanti, failsafe e WebSocket.

## Limiti noti

- Un solo processo può aprire COM4 alla volta.
- Il percorso USB Android/Termux richiede un cavo dati e permesso USB.
- Il bridge wireless blocca il primo mittente UDP per evitare iniezioni casuali nella LAN.
- Il progetto produce input per simulatori: non invia comandi di volo a un drone.
