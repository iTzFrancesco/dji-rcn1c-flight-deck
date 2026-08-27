# Ricerca: live video DJI Mini 4K verso PC

Stato: verifica aggiornata il 27 agosto 2026.

## Verdetto

Il video che hai visto è plausibile, ma quasi certamente riguarda un radiocomando con
schermo diverso dall'RC-N1C.

Per il nostro DJI Mini 4K la soluzione più affidabile è:

```text
Mini 4K --OcuSync 2.0--> RC-N1C --cavo dati--> telefono con DJI Fly
                                      \-- Wi-Fi/LAN --> PC con ricevitore RTMP
```

La USB-C inferiore dell'RC-N1C non è documentata come uscita video. Il Mini 4K usa
OcuSync 2.0 e la live view dichiarata dal radiocomando è 720p/30 fps; il 4K/30 a 100 Mbps
è il video registrato dalla camera, non la live view trasmessa al telefono.

## La differenza tra i radiocomandi

| Radiocomando | Uscita video | Compatibilità ufficiale con Mini 4K | Valutazione |
| --- | --- | --- | --- |
| **RC-N1C** (il nostro) | Nessuna uscita HDMI/UVC documentata; USB-C per computer/ricarica e VCOM sul nostro esemplare | **Sì** | Non è una sorgente video USB supportata |
| **DJI RC** (schermo integrato, prima generazione) | DJI indica `Video Output Port: N/A`; USB-C per ricarica/accessori | **No** | Non è la soluzione vista nei video per Mini 4K |
| **DJI RC 2** (schermo integrato) | FAQ DJI: USB-C per ricarica e collegamento al computer; nessun HDMI dichiarato | **No** | Esistono dimostrazioni USB-C -> HDMI/DisplayPort, ma è comportamento non documentato e dipendente da firmware/adattatore |
| **DJI RC Pro** (schermo integrato) | Mini-HDMI con uscita 4K; DJI dichiara anche video output via USB-C | Non risulta il radiocomando ufficiale del Mini 4K | È il caso reale e ufficiale: `RC Pro HDMI -> capture card -> PC` |

Quindi sì: qualcuno lo ha fatto. Se il video mostra un **RC Pro**, il funzionamento è
ufficiale. Se mostra un **RC 2**, può essere una funzione USB-C/DisplayPort Alt Mode
non garantita da DJI. In nessuno dei due casi questo rende l'RC-N1C una webcam video.

## Cosa è ufficialmente confermato

- DJI indica per Mini 4K: DJI O2/OcuSync 2.0, live view 720p/30 fps e video registrato
  fino a 4K/30, H.264, 100 Mbps.
- DJI indica per il Mini 4K come radiocomandi compatibili RC-N1C e RC-N1.
- DJI Fly supporta il livestreaming RTMP con Mini 4K. Questo è il percorso da provare
  senza cambiare radiocomando: il telefono riceve la live view e la inoltra al PC.
- La matrice SDK ufficiale DJI esclude Mini 4K dal Mobile/Windows/Onboard SDK. Le API
  generiche `VideoFeeder` non cambiano questa limitazione.
- DJI RC Pro è esplicitamente diverso: la sua FAQ documenta mini-HDMI 4K e USB-C con
  supporto video output.

## Percorsi pratici

### 1. RC-N1C USB-C -> PC

Da considerare **no**, salvo reverse engineering non supportato.

Nel repository la porta viene usata per VCOM/DUML: stick, rotella, pulsanti e modalità.
Non vengono esposti frame video e non c'è una periferica UVC o HDMI documentata.

### 2. DJI Fly -> RTMP -> PC

È il candidato principale per il Mini 4K:

1. avviare sul PC un ricevitore RTMP sull'indirizzo LAN del PC;
2. collegare normalmente Mini 4K, RC-N1C e telefono;
3. impostare in DJI Fly l'indirizzo RTMP locale;
4. ricevere il flusso sul PC con FFmpeg/GStreamer e poi elaborarlo con OpenCV o altro;
5. misurare codec, risoluzione, fps, bitrate, keyframe e latenza.

DJI segnala requisiti di rete/account per il livestreaming: bisogna quindi verificare
fisicamente se la versione corrente di DJI Fly accetta un endpoint LAN privato.

### 3. RC Pro -> capture card -> PC

Questo è il percorso hardware dei video che mostrano un RC Pro:

```text
drone -> RC Pro -> mini-HDMI -> capture card HDMI-USB -> PC
```

Il PC riceve un'immagine video già renderizzata dal radiocomando. A seconda delle
impostazioni può contenere telemetria e controlli a schermo oppure una vista più pulita.
Non è il 4K originale memorizzato sulla scheda del drone.

### 4. RC/RC 2 -> USB-C -> display/capture

Per DJI RC e RC 2 va trattato come percorso sperimentale. Le dimostrazioni online mostrano
che alcuni esemplari/adattatori riescono a duplicare lo schermo, ma le FAQ DJI non lo
garantiscono e la compatibilità con Mini 4K resta assente. Non è una base per il progetto
attuale senza prima possedere e testare quel preciso radiocomando.

### 5. Mirroring Android

Se RTMP non fosse utilizzabile, si può catturare la schermata del telefono con una soluzione
ADB/screen mirroring o MediaProjection. È però una registrazione della schermata DJI Fly,
con overlay e ulteriore latenza, non un accesso al bitstream camera originale.

## Prossimo test consigliato

Prima di scrivere un ricevitore permanente nel Flight Deck:

1. P0: collegare solo l'RC-N1C via USB-C inferiore al PC e registrare descriptor,
   interfacce, endpoint e porte create; confermare VCOM senza USB Video Class.
2. P1: provare un ricevitore RTMP locale con Mini 4K, RC-N1C e DJI Fly.
3. Acquisire almeno 30 secondi e analizzare il file con `ffprobe`.
4. Solo se P1 fallisce, valutare il mirroring del telefono.

Non conviene acquistare un DJI RC/RC 2 per questo scopo: non sono compatibili ufficialmente
con Mini 4K. Un RC Pro risolverebbe l'uscita video, ma richiederebbe comunque verificare la
compatibilità operativa con il Mini 4K prima di considerarlo un investimento sensato.

## Fonti primarie

- [DJI Mini 4K specs](https://www.dji.com/mini-2-se/specs) — O2, live view 720p/30, video registrato e bitrate.
- [DJI Mini 4K FAQ](https://www.dji.com/mini-2-se/faq) — RC-N1C e RC-N1 come radiocomandi compatibili.
- [DJI Mini 2 SE/Mini 4K beginner guide](https://repair.dji.com/help/content?customId=01700007462&lang=en&paperDocType=ARTICLE&re=US&spaceId=17) — livestreaming RTMP del Mini 4K.
- [DJI RC-N1/RC-N1C port guide](https://repair.dji.com/help/content?customId=01700006567&lang=en&paperDocType=ARTICLE&re=US&spaceId=17) — collegamento al mobile device e USB-C verso computer.
- [DJI RC support/specs](https://www.dji.com/support/product/rc) — DJI RC con `Video Output Port: N/A`.
- [DJI RC FAQ](https://www.dji.com/rc/faq) — funzioni ufficiali dei due USB-C del DJI RC.
- [DJI RC 2 FAQ](https://www.dji.com/rc-2/faq) — USB-C per ricarica/computer e compatibilità del RC 2.
- [DJI RC Pro FAQ](https://www.dji.com/rc-pro/faq) — mini-HDMI 4K e USB-C con video output.
- [DJI official SDK support table](https://repair.dji.com/help/content?customId=01700000763&documentType=&lang=zh-CN&paperDocType=ARTICLE&re=CN&spaceId=17) — Mini 4K non supportato dagli SDK DJI.
- [DJI RC 2 video USB-C dimostrativo](https://www.youtube.com/watch?v=wSGKezd-4XA) — evidenza pratica non ufficiale, da considerare dipendente da firmware e adattatore.
