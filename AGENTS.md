# AGENTS.md — Progetto Drone (DJI Mini 4K + RC-N1C)

Setup per pilotare simulatori di drone con il radiocomando reale DJI RC-N1C collegato al PC via USB.

## Hardware / ambiente

- PC: i5-11600K, AMD RX 6500 XT 4GB, 16GB RAM, Windows 11 Pro
- Radiocomando: **DJI RC-N1C** (del DJI Mini 4K), Mode 2
- Collegamento: porta USB-C **INFERIORE** del radiocomando (quella di ricarica) → PC.
  La porta SUPERIORE serve solo per il telefono, non usarla.
- Porte COM quando connesso: `DEVICE USB VCOM For Protocol (COM4)` = dati stick, `DEVICE USB VCOM For Debug (COM5)` = debug
- Python: `%LOCALAPPDATA%\Programs\Python\Python312\python.exe` (pacchetti installati: pyserial, vgamepad, websockets, python-dotenv, colorama)
- Driver **ViGEmBus** installato (gamepad Xbox virtuale)

## Protocollo seriale RC-N1C (verificato sul campo)

- Baud 921600, 8N1 (il VCOM sembra ignorare il baud ma usare quello alto)
- Richiesta: inviare esadecimale `55 0d 04 33 0a 06 eb 34 40 06 01 74 24` (risposta request/response: ~1 pacchetto per richiesta)
- Risposta: pacchetti che iniziano con `0x55`, poi 2 byte little-endian di cui i 10 bit bassi = lunghezza pacchetto
- Pacchetto posizione stick: lunghezza **38 byte**
  - offset 13–14: RX (stick destro X)
  - offset 16–17: RY (stick destro Y)
  - offset 19–20: LY (stick sinistro Y)
  - offset 22–23: LX (stick sinistro X)
  - offset 25–26: rotella camera (dial)
  - byte 28 bit0 "Fn": NON confermato su RC-N1C Mini 4K (sonda v3: nessuna variazione)
- Valori: little-endian, range **364..1684**, centro **1024**
- Mappatura Mode 2: sinistro = throttle/yaw (il throttle NON torna da solo verticalmente), destro = pitch/roll

## File

| Percorso | Scopo |
|---|---|
| `dji_rcn1c_bridge.py` | Bridge RC → gamepad Xbox 360 virtuale (ViGEm). Avvio infinito o `--duration N` |
| `AVVIA_BRIDGE.bat` | Lanciatore del bridge per uso quotidiano (doppio click) |
| `wireless/rcn1c_phone_tx.py` | Lato TELEFONO (Termux): legge la seriale USB dell'RC e inoltra stick+rotella+tasti via UDP al PC |
| `wireless/button_probe_27.py` | Sonda guidata del frame pulsanti DUML 0x27 con report dei mapping |
| `wireless/button_live_probe.py` | Monitor live del frame pulsanti DUML 0x27, senza gamepad virtuale |
| `rcn1c_protocol.py` | Costanti e decoder condivisi per stick, pulsanti e modalità |
| `rcn1c_transport.py` | Formato UDP v1/v2/v3 tra Android/Termux e PC |
| `wireless/RCN1C_Bridge.apk` | **App Android pronta da installare** (v3.0): USB seriale + pulsanti/mode live + autodiscovery UDP |
| `wireless/android-app/` | Sorgenti dell'app (Java) + `build_apk.ps1` per ricompilare l'APK |
| `wireless/rcn1c_wifi_rx_pc.py` | Ricevente PC: UDP :26789 → gamepad Xbox virtuale (stick + rotella + tasti) |
| `tests/run_all.py` | Suite completa: py_compile + test ricevente/dashboard (`python tests\run_all.py`, atteso TUTTI_PASS) |
| `AVVIA_WIFI_BRIDGE.bat` | Lanciatore della ricevente WiFi (doppio click) |
| **Desktop: `RC-N1C Dashboard.vbs`** | Doppio click → console server + Chrome sulla dashboard (chiude/riavvia da solo) |
| `sandbox_read_test.py` | Diagnostica seriale guidata in 4 fasi (stick sx, dx, rotella, centro) |
| `viz_app/controller_viz.py` | Server Control Center: seriale (`--source serial`, default) o WiFi (`--source udp`, include gamepad virtuale) + HTTP :8123 + WebSocket :8124 |
| `viz_app/AVVIA_VIZ_WIFI.bat` | Lanciatore della dashboard in modalità wireless |
| `viz_app/static/index.html` | Dashboard real-time Flight Deck: stick pad, matrice pulsanti, modalità, banda, console DUML TX/RX |
| `viz_app/test_viz.py` | Self-test: HTTP 200 + handshake WS hello/snapshot + contatore pacchetti |
| `viz_app/AVVIA_VIZ.bat` | Lanciatore della dashboard |
| `DJI_RCN1_bridge/` | Repo originale clonato: github.com/pverhaert/DJI_RCN1_for_drone_simulators |

## Modalità wireless (telefono come ponte USB↔WiFi)

L'RC-N1C non ha WiFi/BT verso il PC: l'unico modo senza fili è usare il telefono come ponte.
Catena: RC → cavo dati USB-C → telefono → UDP :26789 → PC → gamepad virtuale. Il frame v3 trasporta stick, rotella, pulsanti e modalità.

**Metodo 1 — App Android (consigliato)**: installa `wireless/RCN1C_Bridge.apk` (v3.0, orizzontale)
sul telefono e collega l'RC acceso via USB-C alla porta **INFERIORE** (verificato: è la porta
dati seriale, stessa scelta di tutti i progetti bridge; la porta SUPERIORE è dedicata a DJI Fly
e non espone il VCOM). Consenti USB; lascia l'IP vuoto per l'autodiscovery oppure inseriscilo a mano.
L'app mostra stick, pulsanti, modalità, pkt/s e RTT. Sul PC avvia `AVVIA_WIFI_BRIDGE.bat`, oppure
`viz_app\AVVIA_VIZ_WIFI.bat` per avere dashboard + gamepad in un solo processo (NON insieme ad
AVVIA_WIFI_BRIDGE.bat). Ricompilare l'app: `wireless/android-app/build_apk.ps1`
(SDK in `%LOCALAPPDATA%\Android\Sdk`).

**Metodo 2 — Termux**: installa **Termux** e **Termux:API** da F-Droid, poi `pkg install python termux-api`.
1. Collega il radiocomando acceso al telefono con cavo dati USB-C e consenti USB.
2. In Termux:
   ```bash
   termus-usb -l                                  # annota il percorso es. /dev/bus/usb/002/003
   termus-usb -r /dev/bus/usb/002/003
   termus-usb -E -e "python rcn1c_phone_tx.py IP_DEL_PC" /dev/bus/usb/002/003
   ```
3. Sul PC: doppio click `AVVIA_WIFI_BRIDGE.bat` (o avvia `wireless/rcn1c_wifi_rx_pc.py`).

Note: IP del PC con `ipconfig`; PC e telefono sulla stessa rete; se non arriva nulla consentire
Python nel firewall Windows (reti private). Latenza ~5-15ms in più vs USB. La chiavetta WiFi
serve solo a collegare il PC alla rete, non parla col radiocomando. Frame UDP v3 (18 byte): `<IHHHHHHBB`
(seq + RX/RY/LY/LX + rotella + mask pulsanti + mode + flags); i receiver accettano anche v1/v2.
Sul nostro RC-N1C il frame DUML `0x27` da 58 byte espone `0x0060` scatto/registrazione,
`0x0004` foto/video, `0x0080` RTH/STOP e `0x0002` FN; `0x3000` indica Sport/Normal/Cine.
Rotella → A/B (soglia ±99). CSC stick → LB/RB.
Auto-discovery: broadcast `RCN1C_DISC` su :26790 → risposta `RCN1C_HERE`
(l'app con campo IP vuoto trova il PC da sola). Tutti i receiver bloccano il primo mittente UDP
(anti-iniezione LAN).

## Comandi

```powershell
$py = "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"
& $py dji_rcn1c_bridge.py              # bridge verso i simulatori
& $py sandbox_read_test.py             # test guidato del radiocomando
& $py viz_app/controller_viz.py        # dashboard (seriale USB, apre il browser da sola)
& $py viz_app/controller_viz.py --source udp   # dashboard + gamepad via ponte WiFi
& $py viz_app/test_viz.py              # self-test (atteso: SELFTEST_PASS)
```

## Gotcha importanti

- **Un solo processo alla volta può aprire COM4**: chiudi il bridge prima di avviare la dashboard e viceversa. **DJI Assistant 2 va chiuso** (tiene occupata la porta).
- In modalità WiFi vale lo stesso principio sulla porta UDP 26789: un solo receiver (AVVIA_WIFI_BRIDGE.bat OPPURE AVVIA_VIZ_WIFI.bat, mai entrambi).
- La dashboard si riconnette da sola ogni 2s se il radiocomando è spento/scollegato (overlay "In attesa").
- Rotella camera → pulsanti A/B virtuali oltre soglia ±15% dal centro (può cambiare schermate/focus in Windows: è normale).
- Il bridge pubblica un controller "XBOX 360 For Windows": nei giochi selezionare quello nelle impostazioni comandi.
- Se gli input sembrano limitati (~30 pkt/s) verificare di non avere altri consumer sulla porta e baud alto impostato.

## Simulatore consigliato

**Liftoff FPV Drone Racing** (Steam ~25€): gira bene su RX 6500 XT, ha Angle mode (= modalità normale Mini 4K) e Acro (= FPV), centinaia di mappe gratuite su Steam Workshop. Configurazione: Options → Controls → selezionare "Controller (XBOX 360 For Windows)", deadzone ~2%.

## Convenzioni progetto

- Lingua UI e documentazione: italiano
- Nessun dato lascia il PC: tutto bind su 127.0.0.1 (eccezione: receiver UDP in modalità wireless su 0.0.0.0 per ricevere dal telefono)
- Dashboard senza CDN/risorse esterne (funziona offline)
- Codice senza commenti superflui; docstring solo dove aiutano l'uso
