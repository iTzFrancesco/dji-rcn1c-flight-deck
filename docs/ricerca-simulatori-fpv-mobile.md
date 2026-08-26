# Ricerca simulatori FPV mobile riutilizzabili

Data della ricerca: 26 agosto 2026

## Obiettivo

Individuare un simulatore FPV/drone già esistente, open source oppure distribuibile legalmente tramite APK ufficiale, che possa diventare un'alternativa mobile leggera a Liftoff e ricevere gli stick del DJI RC-N1C.

La ricerca considera soltanto fonti primarie: repository degli autori, file di licenza, documentazione ufficiale e Google Play. Non sono stati cercati, scaricati o consigliati APK crackati, modificati o piratati.

## Vincoli del progetto attuale

Il repository già legge il controller DJI come dispositivo USB raw/VCOM e conosce il protocollo RC-N1C. L'app Android corrente è invece un bridge PC/Wi-Fi:

- [README del progetto](../README.md) descrive il flusso `RC-N1C -> Android -> UDP/Wi-Fi -> PC -> gamepad virtuale`;
- [Rcn1cUsbReader.java](../wireless/android-app/src/com/drone/rcn1cbridge/Rcn1cUsbReader.java) espone frame con stick, pulsanti e modalità;
- [test_android_app_surface.py](../tests/test_android_app_surface.py) verifica che il bridge/dashboard resti operativo e che l'app non utilizzi Portable Touch o Accessibility;
- [build.gradle.kts](../wireless/android-app/build.gradle.kts) usa `minSdk = 26`, compatibile con Android 10 dell'Oppo A53s.

Questo dettaglio è decisivo: un gioco Android normalmente riceve `MotionEvent`/`KeyEvent` da controller HID riconosciuti dal sistema. L'RC-N1C, nel nostro caso, viene invece decodificato direttamente dall'app. Per usare un simulatore esterno servirebbe quindi un livello di injection globale, mentre un simulatore integrato può consumare direttamente lo stesso `Frame` senza root, Shizuku o Accessibility.

## Classifica open source

### 1. FPV.Sim — miglior base per un prototipo mobile

- Repository: [AristidesAI/FPV.Sim](https://github.com/AristidesAI/FPV.Sim)
- Licenza del codice: [MIT](https://github.com/AristidesAI/FPV.Sim/blob/main/LICENSE)
- Tecnologia: Three.js/WebGL, HTML e JavaScript.
- Stato: progetto piccolo con demo single-player pubblicata su GitHub Pages; il server WebSocket serve solo il multiplayer.
- Fisica: Betaflight Actual Rates, 6DOF, gravità, spinta, resistenza e modalità Acro.
- Contenuti: mondo cittadino low-poly, edifici, archi, ostacoli, gara e volo libero.
- Input: mapping personalizzabile di roll/pitch/yaw/throttle per controller USB; configurazione salvata in `localStorage`.

Il progetto è particolarmente interessante perché il codice principale è concentrato in un singolo `index.html`, con valori di rates, expo e configurazione facilmente individuabili. La scena è già abbastanza semplice da poter essere ridotta per un telefono lento.

Limiti:

- non esiste un APK Android ufficiale;
- il file originale carica Three.js e il font da CDN esterni ([sorgente](https://raw.githubusercontent.com/AristidesAI/FPV.Sim/main/index.html)), quindi per funzionare offline bisogna vendorizzare Three.js e rimuovere il font remoto;
- l'input usa il Gamepad API, non il protocollo raw DJI;
- il codice e gli asset devono essere separati e verificati prima di distribuirli: il repository dichiara MIT per il progetto, ma le dipendenze/risorse esterne possono avere condizioni proprie.

Valutazione: **miglior candidato per un proof of concept**. La strada è impacchettarlo in una WebView locale, esporre un bridge JavaScript `setRcn1cFrame(...)` e alimentare la simulazione da `Rcn1cUsbReader`. Se la WebView non garantisse FPS sufficienti sull'A53s, la fisica e la scena restano comunque un riferimento utile.

### 2. GodotDrone — miglior donatore di fisica, mappe e struttura 3D

- Repository: [Cykyrios/GodotDrone](https://github.com/Cykyrios/GodotDrone)
- Licenza: [GPL-3.0](https://github.com/Cykyrios/GodotDrone/blob/master/LICENSE)
- Tecnologia: Godot/GDScript.
- Stato: repository con molte revisioni, ma progetto hobbistico; il README segnala che la conversione Godot 4 aveva ancora problemi.
- Fisica e gioco: quadcopter FPV, volo libero, gare su tracciati MultiGP, rates/expo, calibrazione dei quattro assi, arm e personalizzazione di peso/batteria.
- Asset: cartelle `Assets`, `sceneries` e `tracks` già presenti nel repository.
- Controller: radio o controller a quattro assi, rimappatura e autocalibrazione.

Il README conferma esattamente alcuni elementi utili al nostro obiettivo: rates/expo, controllo Acro, tracciati e un renderer FPV con modalità fisheye economica. Tuttavia il progetto master è basato su concetti Godot 3 (`Spatial`, `RigidBody`); il ramo Godot 4 citato dall'autore aveva binding, HUD, fisheye e audio non risolti ([README ufficiale](https://raw.githubusercontent.com/Cykyrios/GodotDrone/master/README.md)).

Limiti:

- nessun APK Android pronto e nessuna procedura Android nel README;
- la GPL-3.0 richiede di rispettare gli obblighi copyleft quando si distribuisce un'opera derivata;
- portare tutto il progetto su Android significherebbe introdurre un motore completo e migrare la versione Godot;
- l'unico livello è descritto dallo stesso autore come piuttosto semplice.

Valutazione: **miglior riferimento se accettiamo GPL e un porting Godot**, ma non è il percorso più rapido né il più leggero per l'Oppo A53s.

### 3. KestrelFPV — contenuti completi ma progetto storico

- Repository: [eleurent/KestrelFPV](https://github.com/eleurent/KestrelFPV)
- Licenza: [MIT](https://github.com/eleurent/KestrelFPV/blob/master/LICENSE)
- Tecnologia: Unity3D.
- Fisica: motori, eliche, aerodinamica e modalità Acro/Rate.
- Asset/mappe: Viking Village, Forest e Stadium; viste FPV, follow ed eye-level.
- Controller: supporto a joystick/radio con inversione degli assi.
- Stato: ultimo changelog del repository risalente al 2015; download ufficiali solo Windows, macOS e Linux ([README](https://raw.githubusercontent.com/eleurent/KestrelFPV/master/README.md)).

Valutazione: **utile per studiare asset e concetti di gameplay**, ma Unity e l'età del progetto rendono il porting Android più rischioso del valore recuperabile. Inoltre gli asset vanno comunque verificati singolarmente, anche se il codice del repository è MIT.

### 4. UnityFPVDroneSimulator — buona reference per Acro e input shaping

- Repository: [Venkatesan-M/UnityFPVDroneSimulator](https://github.com/Venkatesan-M/UnityFPVDroneSimulator)
- Licenza del codice: [MIT](https://github.com/Venkatesan-M/UnityFPVDroneSimulator/blob/main/LICENSE)
- Tecnologia: Unity 2021.3 LTS e C#.
- Fisica: `Rigidbody`, forze/torque, Angle, Acro e Horizon.
- Input: Radiomaster Pocket o joystick USB HID, deadzone, expo e damping dello yaw.
- Asset: ambiente low-poly e drone semplice indicati nel README.
- Stato: progetto didattico piccolo; nessun APK Android ufficiale e nessun mapping diretto del protocollo RC-N1C.

La logica di Acro e l'input shaping sono leggibili e potrebbero aiutare a validare il comportamento del nostro motore. Non è però una base pronta: il progetto è preconfigurato per un trasmettitore HID e presuppone l'editor Unity ([README ufficiale](https://raw.githubusercontent.com/Venkatesan-M/UnityFPVDroneSimulator/main/README.md)).

Valutazione: **reference tecnica**, non base consigliata per l'APK finale.

### 5. LOS-Flight-Simulator — ottimo trainer web, ma non vero FPV racing

- Repository: [m72900024/LOS-Flight-Simulator](https://github.com/m72900024/LOS-Flight-Simulator)
- Licenza: [MIT](https://github.com/m72900024/LOS-Flight-Simulator/blob/main/LICENSE)
- Tecnologia: Three.js, singolo HTML e asset locali.
- Fisica: Angle, Horizon, Acro e Alt Hold; gestione esplicita di stick centrati e non centrati.
- Input: Gamepad API, calibrazione automatica, inversione, deadzone, touch e modalità Mode 2.
- Contenuti: otto livelli progressivi, obiettivi, record locali e mappa 3D.

È un buon riferimento per menu, calibrazione, progressione e settings. Il README, però, lo definisce un trainer Line-of-Sight: la scena usa una telecamera in terza persona e il rendering attuale include shadow map molto pesanti ([sorgente](https://raw.githubusercontent.com/m72900024/LOS-Flight-Simulator/main/index.html)).

Valutazione: **reference per UX e calibrazione**, non base principale per un'alternativa FPV a Liftoff.

### 6. PicaSim — vero progetto Android open source, ma per aerei

- Repository: [Rowlhouse/PicaSim](https://github.com/Rowlhouse/PicaSim)
- Build Android documentata: cartella [android](https://github.com/Rowlhouse/PicaSim/tree/main/android), Gradle e APK generato da `assembleDebug`.
- Licenza: [PolyForm Noncommercial 1.0.0](https://github.com/Rowlhouse/PicaSim/blob/main/LICENSE.txt).
- Tecnologia: C++/SDL2/OpenGL, Bullet e asset locali.
- Stato: sorgente completo, infrastruttura multipiattaforma e packaging Android documentato.
- Limite principale: è un simulatore di aeromodelli ad ala fissa, non un simulatore quadcopter FPV.

È utile per studiare una pipeline Android nativa con renderer leggero e input radio, ma non conviene trasformarlo in un simulatore FPV: la dinamica, la camera e i contenuti sono quelli di aerei. La licenza distingue inoltre gli asset: alcuni modelli e immagini possono essere usati solo in derivati diretti di PicaSim o richiedono permessi separati.

Valutazione: **reference per packaging Android**, non candidato per il simulatore FPV.

### 7. FPV-Dronecraft — interessante ma dipendente da Minecraft

- Repository: [SakalioLabs/FPV-Dronecraft](https://github.com/SakalioLabs/FPV-Dronecraft)
- Licenza: MIT.
- Funzioni: Angle, Horizon e Acro, controller mapping, calibrazione, HUD e droni FPV.
- Limiti: mod Fabric per Minecraft 1.21.11, Java 21, non APK Android e dipendenza completa dal mondo Minecraft.

Valutazione: **non adatto al telefono**, ma utile come riferimento per HUD, binding e stati di volo.

## Applicazioni Android ufficiali da provare

Queste app non sono open source e non vanno modificate o ripacchettizzate. Servono solo come test legali e come confronto del comportamento su Oppo A53s.

### FPV Freerider demo

- [Pagina ufficiale Google Play](https://play.google.com/store/apps/details?id=com.Freeride.Freerider_FREE)
- FPV e LOS, self-leveling e Acro.
- Controller fisici tramite USB OTG se riconosciuti da Android.
- Una sola scena desertica nella demo; la versione completa aggiunge sei scenari, generatore procedurale di piste e settings più completi.
- Il produttore raccomanda risoluzione e grafica basse e chiarisce che la demo serve a verificare la compatibilità del dispositivo/controller.

È il primo test da ripetere perché ha già l'esperienza FPV più vicina all'obiettivo. La lista ufficiale cita DJI FPV, ma non certifica il DJI RC-N1C. Il nostro RC potrebbe quindi non apparire come controller HID.

### VelociDrone Mobile

- [Pagina mobile ufficiale](https://www.velocidrone.com/mobile)
- [Manuale ufficiale](https://www.velocidrone.com/downloads/VelocidroneMobileManual.pdf)
- [Pagina Google Play](https://play.google.com/store/apps/details?id=com.velocidrone.velocidrone)
- Droni 5", freestyle, micro, mega e toothpick; piste e ambienti; fisica orientata al racing.
- Il manuale conferma USB OTG e Bluetooth quando Android riconosce il controller.
- Il manuale sconsiglia esplicitamente il touch per la latenza e l'assenza di feedback tattile.
- Gli sviluppatori dichiarano di mantenere piccolo il catalogo mobile per limitare lo spazio occupato.

È probabilmente la migliore app già pronta, ma non è modificabile legalmente e la compatibilità diretta con RC-N1C resta da provare.

### FeelFPV

- [Pagina ufficiale Google Play](https://play.google.com/store/apps/details?id=com.FullFocusStudio.FeelFPV)
- Simulatore FPV per Android e Windows.
- Dichiara supporto a gamepad via cavo/Bluetooth, radio Radiomaster/TBS/iFlight/Jumper via OTG e “all DJI controllers”.
- Touch disponibile ma sconsigliato dall'autore.

È il candidato commerciale più interessante per un test diretto con l'RC-N1C perché cita esplicitamente DJI. Tuttavia “all DJI controllers” non equivale a una conferma del modello RC-N1C; va verificato con l'hardware reale.

### FPV.SkyDive

- [Pagina ufficiale Google Play](https://play.google.com/store/apps/details?id=com.Orqa.FPVSkyDive)
- Racing e freestyle, mappe selezionate, controller esterno e fisica FPV.
- [Manuale/calibration FAQ ufficiale](https://shared.cloudflare.steamstatic.com/store_item_assets/Steam/apps/1278060/manuals/FPV.SkyDive_Calibration_FAQ__4_2022_.pdf?t=1743069505)

È un'alternativa pronta, ma la documentazione mobile segnala limitazioni sui tasti e potenziali problemi con controller di tipo DJI. Priorità inferiore rispetto a Freerider, VelociDrone e FeelFPV.

### SimuDrone 1.3.5

- [Pagina ufficiale Google Play](https://play.google.com/store/apps/details?id=com.makkuzu.simudrone)
- [Pagina dello sviluppatore con le vecchie versioni](https://akkuzu.com.tr/)
- Il sito dello sviluppatore indica la versione 1.3.5 come compatibile con RC-N1C.
- La versione corrente dichiara controller USB/OTG e mappe ad alta fedeltà.

La versione 1.3.5 è interessante come **test hardware di compatibilità**, non come base del nostro simulatore. L'app è proprietaria e l'EULA vieta reverse engineering, modifica e copia ([EULA ufficiale](https://simudrone.akkuzu.com.tr/privacy/)).

### Quadcopter FX e DRS

- [Quadcopter FX](https://play.google.com/store/apps/details?id=com.Creativeworld.QuadcopterFX): app storica con Acro/Acro 3D e requisiti dichiarati molto bassi, ma aggiornata molti anni fa e non open source.
- [DRS - Drone Flight Simulator](https://play.google.com/store/apps/details?id=com.psv.simulator.drone_fpv_drs): dichiara Acro, FPV, mappe e controller, ma è orientata anche a droni camera/missioni e non offre sorgente riutilizzabile.

Sono fallback per verificare se un APK generico gira sull'A53s, non candidati seri per un trainer alternativo a Liftoff.

## Confronto sintetico

| Candidato | Open source | Acro | Mappe/asset | Android pronto | Input RC-N1C raw | Adatto come base |
|---|---:|---:|---:|---:|---:|---:|
| FPV.Sim | MIT | Sì | Sì, low-poly | No, WebView/PWA possibile | Da integrare | **Sì, prototipo** |
| GodotDrone | GPL-3 | Sì | Sì | No | Da integrare | Sì, ma porting oneroso |
| KestrelFPV | MIT | Sì | Sì | No | Da integrare | Solo reference storica |
| UnityFPVDroneSimulator | MIT | Sì | Limitati | No | No, HID | Solo reference fisica |
| LOS-Flight-Simulator | MIT | Sì | Sì, soprattutto LOS | Web | No, Gamepad API | UX/calibrazione |
| PicaSim | PolyForm NC | No, aerei | Sì | Sì, build documentata | Da integrare | Packaging soltanto |
| Freerider demo | Proprietario | Sì | Una scena | Sì, Play Store | Non garantito | Test immediato |
| VelociDrone Mobile | Proprietario | Sì | Sì | Sì, Play Store | Non garantito | Test immediato |
| FeelFPV | Proprietario | FPV | Sì | Sì, Play Store | DJI dichiarato, RC-N1C non confermato | Test immediato |
| SimuDrone 1.3.5 | Proprietario | Parziale/variabile | Sì | APK ufficiale storico | RC-N1C dichiarato | Test compatibilità |

## Scelta consigliata

### Percorso più rapido

1. Provare l'APK ufficiale di FPV Freerider demo su Oppo A53s.
2. Provare FeelFPV, perché dichiara supporto ai controller DJI.
3. Provare VelociDrone Mobile con impostazioni minime.
4. Usare SimuDrone 1.3.5 solo per verificare se il modello RC-N1C viene riconosciuto direttamente.

Il test va fatto con il controller collegato direttamente al telefono via OTG. Il bridge PC/Wi-Fi non aiuta un'app esterna se questa si aspetta un gamepad HID Android.

### Percorso open source raccomandato

Il candidato più conveniente da riutilizzare è **FPV.Sim**:

```text
Rcn1cUsbReader.Frame
        ↓
Java Android bridge
        ↓
JavaScript setRcn1cFrame(...)
        ↓
FPV.Sim: rates/expo + 6DOF + Acro + scena low-poly
```

Per il primo prototipo si mantiene la WebView, Three.js è vendorizzato localmente e il font remoto è stato rimosso. Il multiplayer non viene avviato dalla modalità Android. In seguito si decide con un test FPS se mantenere WebView o portare solo la fisica/scena in un renderer nativo.

GodotDrone è il secondo percorso: offre una struttura più simile a un gioco completo, ma la GPL-3 e la migrazione Godot/Android aumentano il costo. UnityFPVDroneSimulator e KestrelFPV sono utili per confrontare la fisica, non per ottenere rapidamente un APK leggero.

## Architettura tecnica da preservare

Il simulatore mobile non dovrebbe generare click Android e non dovrebbe controllare un'altra app:

```text
RC-N1C USB/VCOM
    → Rcn1cUsbReader
    → Frame normalizzato
       ├── Bridge PC esistente → UDP → ViGEm
       └── Simulatore mobile → input diretto
```

I pulsanti devono diventare stati interni (`pressed`, `down`, `released`) per arm, reset e cambio modalità. Non devono diventare tocchi condivisi con due joystick virtuali: questo evita il problema osservato ieri, in cui un click poteva interrompere o disturbare gli stick.

## Rischi da verificare prima di sviluppare

1. **Riconoscimento RC-N1C:** nessun simulatore commerciale esaminato certifica esplicitamente quel modello, tranne la vecchia indicazione dello sviluppatore SimuDrone 1.3.5.
2. **Prestazioni Oppo A53s:** 4 GB RAM, Snapdragon 460, Adreno 610 e display 1600×720 sono compatibili con un progetto leggero, ma servono misure reali di FPS, temperatura, consumo e latenza.
3. **WebView:** FPV.Sim è il candidato più rapido, ma va verificato offline e senza CDN su Android 10.
4. **Licenze asset:** MIT/GPL del repository non garantiscono automaticamente i diritti su modelli, texture, font e dipendenze esterne.
5. **APK locali del progetto:** gli APK tracciati nella repo sono storici e non devono essere usati per valutare il nuovo simulatore; bisogna distinguere sempre il sorgente corrente dall'artefatto binario.

## Conclusione

Non è emerso un progetto open source che offra contemporaneamente APK Android pronto, vero FPV Acro, mappe, leggerezza e supporto nativo al protocollo RC-N1C.

La soluzione con il miglior rapporto rischio/risultato è:

1. usare le app ufficiali per un test rapido sul telefono;
2. partire da **FPV.Sim** come prototipo open source legalmente riutilizzabile;
3. collegarlo direttamente a `Rcn1cUsbReader` nella nostra app;
4. eliminare multiplayer e risorse remote;
5. misurare le prestazioni sull'Oppo A53s prima di decidere se restare su WebView o migrare a un renderer nativo.

Questa strada riutilizza fisica, scena, rates, expo e struttura di gioco già esistenti senza ricadere nell'injection touch che ha causato i problemi del bridge precedente. Il primo vertical slice è ora in `wireless/android-app/assets/fpv-sim/` e viene aperto da `SimulatorActivity`.
