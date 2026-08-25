# Android Gamepad (beta)

Questa modalità fa parte della stessa app **RC-N1C Flight Bridge** usata per il bridge Wi-Fi verso PC.
Non modifica i giochi Android e non richiede un simulatore sviluppato dal progetto.

```text
RC-N1C
  │ USB/OTG
  ▼
RC-N1C Flight Bridge
  │
  ├─ uinput via Shizuku (preferito) ──► vero InputDevice Xbox-style
  │
  └─ IInputManager via Shizuku (fallback) ──► input iniettato
                                            │
                                            ▼
                                 FPV.Skydive / FeelFPV / altri giochi
```

## Perché un foreground service

Quando viene aperto un simulatore, Flight Bridge passa in background. `GamepadBridgeService`
mantiene attivi il reader USB DJI e il gamepad virtuale mentre il gioco è in primo piano.
La notifica permette di tornare alla dashboard o fermare il bridge.

## Componenti

- `Rcn1cUsbReader.java` — lettura VCOM/DUML del RC senza dipendenze da UI, rete o gamepad.
- `GamepadBridgeService.java` — collega i frame RC al backend Android e resta vivo durante il gioco.
- `uinput/UInputService.java` — Shizuku user-service con UID shell.
- `cpp/uinput_jni.cpp` — crea il dispositivo Xbox-style su `/dev/uinput`.
- `uinput/ShizukuInputInjector.java` — fallback se SELinux blocca `/dev/uinput`.
- `GamepadActivity.java` — dashboard minimale, stato backend e launcher dei simulatori.

## Mapping iniziale

| RC-N1C | Android virtual gamepad |
| --- | --- |
| Stick sinistro X/Y | LX / LY |
| Stick destro X/Y | RX / RY |
| Rotella camera destra/sinistra | A / B |
| FN | X |
| Scatto | Y |
| Foto/Video | Select / Back |
| RTH | Start |

I valori raw verificati restano `364..1684`, con centro `1024`, e vengono normalizzati nel range
`-32767..32767`. Non viene applicata una deadzone artificiale nel backend uinput: eventuali deadzone,
inversioni o rate vanno configurati nel simulatore o in una futura configurazione Flight Bridge.

## Primo test hardware

1. Installa/avvia **Shizuku** e autorizza RC-N1C Flight Bridge.
2. Apri `RC-N1C Flight Bridge` → **ANDROID GAMEPAD · BETA**.
3. Collega il RC-N1C al telefono via USB/OTG e accendilo.
4. Premi **AVVIA** e concedi il permesso USB.
5. Controlla il backend mostrato dall'app:
   - `uinput` = percorso ideale; il gioco dovrebbe vedere un vero controller.
   - `Shizuku inject` = fallback; alcuni giochi possono ignorarlo.
6. Muovi tutti e quattro gli assi e verifica la dashboard.
7. Apri **FPV.SKYDIVE** dall'app e usa la calibrazione controller del gioco.
8. Se necessario, prova **FEELFPV** come secondo target.

## Diagnostica

Se il RC non compare, il problema è prima del gamepad virtuale: USB/OTG, permesso Android o endpoint VCOM.

Se il RC viene letto ma compare `uinput bloccato`, la ROM non permette al processo shell di aprire
`/dev/uinput`; Flight Bridge prova automaticamente il fallback InputManager.

Per log mirati durante lo sviluppo:

```bash
adb logcat -s RCN1C-UInputService RCN1C-UInputGamepad RCN1C-InputInject rcn1c_uinput
```

Su alcune versioni ColorOS l'avvio o i permessi di Shizuku possono richiedere impostazioni specifiche
del produttore. Prima di cambiare opzioni di sistema, seguire le istruzioni aggiornate di Shizuku per
la propria versione ColorOS.

## Performance

La modalità non usa motori 3D o framework UI aggiuntivi. Il lavoro continuo è limitato a:

- polling USB del RC;
- parsing di frame da 38/58 byte;
- invio di pochi eventi input per frame;
- refresh dashboard solo quando visibile.

Il simulatore resta quindi il principale carico CPU/GPU; Flight Bridge è progettato per rimanere
leggero anche su dispositivi più vecchi.

## Riferimento tecnico

L'architettura `Shizuku user-service -> /dev/uinput` è stata validata prendendo come riferimento il
progetto MIT `SonicDX12/SteamController-Android`. Flight Bridge mantiene il proprio decoder DJI,
package, UI, servizi e mapping e usa un'implementazione ridotta orientata esclusivamente al RC-N1C.
