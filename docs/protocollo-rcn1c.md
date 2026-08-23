# Protocollo verificato RC-N1C

Questa è la parte del progetto ottenuta con catture dal **DJI RC-N1C reale collegato al PC**. Le etichette dei pulsanti non sono state dedotte soltanto da documentazione di altri radiocomandi: sono state controllate premendo i comandi del nostro esemplare.

## Pacchetti DUML

Seriale VCOM Protocol: 921600 baud, 8N1. Ogni richiesta è una transazione request/response.

| Funzione | Richiesta esadecimale | Risposta |
| --- | --- | ---: |
| abilita telemetria simulator | `55 0e 04 66 0a 06 eb 34 40 06 24 01 d9 ec` | — |
| stick e rotella (`0x01`) | `55 0d 04 33 0a 06 eb 34 40 06 01 74 24` | 38 byte |
| pulsanti e modalità (`0x27`) | `55 0d 04 33 0a 06 eb 34 40 06 27 40 60` | 58 byte |

Nel terzo byte dell'header il valore corretto è `0x04`: è il flag di lunghezza DUML. Usare `0x40` lì produceva una richiesta con header non valido e spiega perché il primo tentativo non mostrava i pulsanti.

## Frame stick da 38 byte

I valori sono little-endian, normalmente nell'intervallo 364–1684 con centro 1024:

| Offset | Canale |
| ---: | --- |
| 13–14 | RX, stick destro X |
| 16–17 | RY, stick destro Y |
| 19–20 | LY, stick sinistro Y |
| 22–23 | LX, stick sinistro X |
| 25–26 | rotella camera |

## Frame pulsanti da 58 byte

La mask è big-endian a offset 28–29. Sul nostro RC-N1C:

| Mask | Stato |
| ---: | --- |
| `0x0060` | scatto / avvio-stop registrazione |
| `0x0004` | selettore foto/video |
| `0x0080` | RTH / STOP |
| `0x0002` | FN |
| `0x3000` | bits modalità |

Gli stati osservati durante la prova sono quindi `0x1060` per scatto in Normal, `0x1004` per foto/video, `0x1080` per RTH e `0x1002` per FN. Il valore di riposo Normal è `0x1000`.

## UDP v3

Il ponte Android/Termux invia 18 byte little-endian:

```text
<IHHHHHHBB
seq, RX, RY, LY, LX, camera, button_mask, mode_code, flags
```

Il receiver PC mantiene compatibilità con i frame v1 da 12 byte e v2 da 16 byte. Nei frame vecchi i pulsanti non possono essere ricostruiti, salvo il vecchio bit FN eventualmente presente nel v2.

## Perché questa strada è credibile

Il progetto originale per RC-N1 mostra il percorso VCOM, la richiesta stick e la conversione in gamepad, ma non interroga il comando `0x27`. Progetti indipendenti per RC-N3 descrivono lo stesso tipo di frame DUML da 58 byte e la lettura della mask; questa implementazione usa quei riferimenti come pista tecnica e poi valida offsets e valori sul nostro RC-N1C.

Fonti tecniche consultate:

- [pverhaert/DJI_RCN1_for_drone_simulators](https://github.com/pverhaert/DJI_RCN1_for_drone_simulators) — base del bridge RC-N1.
- [deviverr/DJI-RC-Emulator](https://github.com/deviverr/DJI-RC-Emulator) — emulator e parsing DUML per radiocomandi DJI.
- [gladiusxosmium/DJI-RC-N3-Joystick_MACOS](https://github.com/gladiusxosmium/DJI-RC-N3-Joystick_MACOS) — riferimento per il frame pulsanti `0x27` da 58 byte.
- [o-gs/dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) — dissector DUML e struttura dei comandi.
- [DJI Mini 4K User Manual](https://dl.djicdn.com/downloads/DJI_Mini_4K/DJI_Mini_4K_User_Manual_v1.0_EN.pdf) — disposizione e funzione fisica dei comandi del radiocomando.
- [DJI Mobile SDK: Remote Controller](https://developer.dji.com/mobile-sdk/documentation/introduction/component-guide-remotecontroller.html) — nomenclatura ufficiale dei controlli e modalità di connessione.

## Sicurezza operativa

Il bridge non invia comandi di volo al drone: crea input virtuali per simulatori. La richiesta `0x24` abilita la modalità di lettura simulator già usata dal progetto; l'app non deve essere usata per pilotaggio reale.
