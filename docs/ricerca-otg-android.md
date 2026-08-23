# Ricerca: evitare l'attivazione manuale di OTG su Android

**Data della ricerca:** 23 agosto 2026  
**Ambito:** DJI RC-N1C collegato via USB-C a un telefono Android, usato come ponte USB↔Wi-Fi per questo progetto.  
**Vincolo:** questa analisi non modifica codice, manifest, APK o configurazioni del progetto.

## Risposta breve

Non esiste un metodo Android universale e affidabile con cui una normale app possa attivare da sola l'opzione OTG aggiunta dal produttore del telefono. La ragione è che l'opzione OTG non è, in generale, il permesso USB della nostra app: è una politica OEM che abilita o limita la porta USB-C nel ruolo **host** (telefono come host e alimentatore del dispositivo collegato).

Se il telefono espone già la porta in modalità host, l'app può rilevare il DJI RC-N1C, chiedere il permesso USB e aprire il dispositivo. Se invece il firmware mantiene disattivato l'host finché l'utente non abilita OTG, l'app non riceve nemmeno un dispositivo USB utilizzabile e non può superare quel blocco tramite le API pubbliche.

Le possibilità concrete sono quindi:

1. **Nessuna azione manuale**, se si usa un telefono/firmware che abilita automaticamente USB host e non applica un timeout OTG.
2. **Automazione parziale**, per esempio apertura automatica delle impostazioni o rilevamento automatico del dispositivo dopo che OTG è già attivo.
3. **Automazione device-specifica con ADB, Shizuku o root**, se il modello espone un comando funzionante; non è una soluzione portabile né garantita.
4. **Cambio dell'hardware**, cioè un telefono senza interruttore OTG persistente, un hub alimentato se il problema è la potenza, oppure collegamento diretto del radiocomando al PC.

## 1. OTG/USB host e permesso USB dell'app sono livelli diversi

Android definisce USB host come il caso in cui il dispositivo Android agisce da host e comunica con periferiche USB tramite UsbDevice e UsbDeviceConnection. La documentazione ufficiale avverte che non tutti i dispositivi Android garantiscono le API host e indica la feature android.hardware.usb.host. [Android Developers: USB host overview](https://developer.android.com/develop/connectivity/usb/host)

Il flusso normale è:

1. la porta USB del telefono entra nel ruolo host e il controller USB enumera il dispositivo;
2. Android rende il dispositivo visibile in UsbManager.getDeviceList();
3. l'app controlla UsbManager.hasPermission() oppure chiama UsbManager.requestPermission();
4. dopo l'autorizzazione l'app apre il dispositivo con openDevice().

requestPermission() concede un permesso temporaneo al package per il dispositivo e la documentazione Android specifica che il permesso vale fino alla disconnessione del dispositivo. Questo permesso autorizza l'app a usare un dispositivo già enumerato; non cambia il ruolo della porta e non abilita OTG. [API UsbManager](https://developer.android.com/reference/android/hardware/usb/UsbManager)

La distinzione spiega il comportamento osservato: se OTG è disattivato dal firmware, la sonda/app non vede il VCOM del radiocomando e non può arrivare alla fase in cui richiedere il permesso.

## 2. Cosa fa AOSP e cosa lascia al dispositivo

Android usa un USB HAL per interrogare la porta USB-C e, sui dispositivi compatibili, cambiare ruolo dati e ruolo di alimentazione. La documentazione AOSP indica che l'HAL USB gestisce proprio le operazioni di data-role e power-role swap e che la sua implementazione deve essere adattata al dispositivo. [AOSP: Implement USB HAL](https://source.android.com/docs/core/permissions/usb-hal)

Nel codice AOSP i ruoli sono espliciti:

- **data role host**: il telefono accede ai dati delle periferiche;
- **data role device**: il telefono offre i propri servizi USB;
- **power role source**: il telefono fornisce alimentazione;
- **power role sink**: il telefono riceve alimentazione.

La gestione della porta passa da UsbPortManager e dal relativo HAL; non è una semplice preferenza applicativa. [AOSP UsbPortManager](https://android.googlesource.com/platform/frameworks/base/+/master/services/usb/java/com/android/server/usb/UsbPortManager.java)

Il Compatibility Definition Document richiede il supporto USB host ai dispositivi che dichiarano quella capacità e richiede il comportamento USB Type-C dual-role quando la porta lo supporta. Non obbliga però tutti i produttori a esporre la stessa interfaccia utente, lo stesso timeout o lo stesso criterio per iniziare la negoziazione host. [Android 15 CDD: USB host e dual-role USB-C](https://source.android.com/docs/compatibility/15/android-15-cdd)

Questa è la parte device-specifica: il firmware, il kernel, il controller USB-C e la personalizzazione OEM possono decidere quando la porta può diventare host e quando interrompere quel ruolo per risparmiare batteria o prevenire alimentazioni indesiderate.

## 3. Un'app normale può accendere OTG?

### API USB pubbliche

Le API pubbliche documentate consentono di enumerare periferiche, controllare il permesso, richiedere il permesso all'utente e aprire il dispositivo. Non espongono un metodo pubblico generale enableOtg() o equivalente. [Android USB API](https://developer.android.com/reference/android/hardware/usb/package-summary)

In AOSP esiste un metodo interno per cambiare i ruoli della porta, ma è annotato come API nascosta e richiede MANAGE_USB. Il servizio USB verifica esplicitamente quel permesso prima di eseguire setPortRoles(). [AOSP UsbManager.setPortRoles()](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-28/+/master/android/hardware/usb/UsbManager.java#829), [AOSP UsbService.setPortRoles()](https://android.googlesource.com/platform/frameworks/base/+/e2279c6/services/usb/java/com/android/server/usb/UsbService.java#323)

MANAGE_USB è una permission di sistema/privilegiata: nei test AOSP viene descritta come signature|privileged. Non è ottenibile da una normale APK distribuita manualmente o dal Play Store. [AOSP permission declaration for MANAGE_USB](https://android.googlesource.com/platform/frameworks/base/+/b2a8c5bfec55/tests/privapp-permissions/system_ext/AndroidManifest.xml)

### WRITE_SETTINGS

WRITE_SETTINGS non è una scorciatoia per controllare OTG. Da Android 6 un'app deve dichiararla e l'utente deve autorizzarla nella schermata speciale di sistema; anche in quel caso riguarda le impostazioni appartenenti alla tabella System. [Android Developers: Settings.System.canWrite()](https://developer.android.com/reference/android/provider/Settings.System#canWrite(android.content.Context))

Le impostazioni Global sono leggibili ma non scrivibili dalle app normali, e le impostazioni Secure sono anch'esse non scrivibili dalle app normali. Inoltre, il nome e il formato dell'eventuale chiave OTG OEM non fanno parte dell'API Android stabile. [Android Developers: Settings.Global](https://developer.android.com/reference/android/provider/Settings.Global), [Android Developers: Settings.Secure](https://developer.android.com/reference/android/provider/Settings.Secure)

### WRITE_SECURE_SETTINGS, API nascoste e app di sistema

WRITE_SECURE_SETTINGS è documentata come non destinata ad applicazioni di terze parti. Anche se si inserisse la permission nel manifest, Android non la concede a una normale APK installata dall'utente. [Android Developers: Manifest.permission.WRITE_SECURE_SETTINGS](https://developer.android.com/reference/android/Manifest.permission#WRITE_SECURE_SETTINGS)

Usare reflection per chiamare API nascoste non risolve il problema in modo affidabile: da Android 9 il sistema limita le interfacce non-SDK, incluse quelle ottenute via reflection o JNI, e le interfacce possono cambiare senza compatibilità garantita. [Android Developers: restrictions on non-SDK interfaces](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces)

**Conclusione per l'app del progetto:** senza essere app di sistema firmata con la chiave della piattaforma, senza privilegi OEM e senza un meccanismo esterno come ADB/root, non possiamo comandare direttamente la policy OTG del telefono.

## 4. ADB, root, Shizuku e automazioni

### ADB

ADB fornisce una shell sul dispositivo e può eseguire comandi di sistema; non è una permission concessa automaticamente a un'app normale. Richiede Debug USB e l'autorizzazione del computer. [Android Developers: Android Debug Bridge](https://developer.android.com/tools/adb)

AOSP documenta anche il comando diagnostico di dumpsys usb per impostare ruoli su una porta Type-C, ad esempio dumpsys usb set-port-roles "default" source host. Il codice AOSP mostra che il comando passa comunque dal port manager e dal supporto reale dell'HAL. [AOSP UsbService shell commands](https://android.googlesource.com/platform/frameworks/base/+/master/services/usb/java/com/android/server/usb/UsbService.java)

Questo apre una possibilità sperimentale:

~~~powershell
adb shell dumpsys usb
adb shell dumpsys usb set-port-roles "default" source host
adb shell dumpsys usb
~~~

Non è però una soluzione affidabile per il prodotto: il nome della porta può non essere default, l'OEM può bloccare o ignorare il cambio, il dispositivo può non supportare quella combinazione e il comando può cambiare il ruolo USB senza aggiornare l'interruttore OTG proprietario. Va usato solo come test non distruttivo su quel telefono.

La stessa shell può, su alcuni firmware, modificare chiavi Settings con settings put; AOSP mostra che il provider tratta system/root/shell in modo diverso dalle app normali. Questo non dimostra l'esistenza di una chiave OTG universale: la chiave può essere OEM-specifica, non esistere o essere riscritta dal servizio USB. [AOSP SettingsProvider: privilegi system/root/shell](https://android.googlesource.com/platform/frameworks/base/+/master/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java)

### Root, Shizuku e app di automazione

Con root si possono, in teoria, raggiungere nodi sysfs o servizi OEM che una normale app non può usare. È una soluzione dipendente da kernel, SELinux, versione Android e firmware; non è adatta a essere assunta come requisito dell'app pubblica.

Tasker documenta l'esecuzione di comandi tramite ADB Wi-Fi, root o Shizuku. Quindi può essere un involucro per un comando già verificato sul modello specifico, ma non crea da sola un nuovo privilegio USB. Senza un backend ADB/root/Shizuku autorizzato, Tasker può al massimo aprire una schermata, avviare la nostra app o reagire a un evento già emesso dal sistema. [Tasker: ADB Wi-Fi e backend shell](https://tasker.joaoapps.com/userguide/en/help/ah_adb_wifi.html), [Tasker: Java API per ADB Wi-Fi/root/Shizuku](https://tasker.joaoapps.com/userguide/en/help/ah_java_code.html)

### USB_DEVICE_ATTACHED e avvio automatico

Android permette a un'app di dichiarare un filtro per android.hardware.usb.action.USB_DEVICE_ATTACHED. Quando il sistema riconosce un dispositivo corrispondente, può presentare la scelta dell'app e, dopo l'accettazione dell'utente, concedere il permesso fino alla disconnessione. [Android Developers: USB device attach intent](https://developer.android.com/develop/connectivity/usb/host#use-intent-filter)

Questo può automatizzare il tratto **dopo** l'abilitazione del ruolo host. È un'inferenza diretta dal flusso Android: se OTG è disattivato e il kernel/HAL non enumera la periferica, non esiste ancora un UsbDevice corrispondente e quindi non c'è un attach intent del radiocomando da cui l'app possa partire. L'intent non è un interruttore OTG.

Inoltre, Android limita l'avvio arbitrario di Activity dal background da Android 10 in poi. Un'app può usare il flusso USB previsto dal sistema o una notifica/servizio foreground, ma non dovrebbe contare su un'attività invisibile che apre impostazioni e simula tocchi. [Android Developers: background activity launch restrictions](https://developer.android.com/guide/components/activities/secure-bal)

## 5. Timeout e firmware OEM: evidenza ufficiale

Il comportamento osservato è compatibile con una personalizzazione del produttore. Per esempio, la documentazione ufficiale Xiaomi per dispositivi con interruttore OTG dice che il toggle può spegnersi automaticamente dopo 10 minuti di inattività. La stessa documentazione distingue le versioni in cui il toggle è stato rimosso e OTG viene usato direttamente, mostrando che il comportamento cambia per versione MIUI/firmware e modello. [Xiaomi: OTG e spegnimento automatico](https://www.mi.com/sg/support/article/KA-07213/), [Xiaomi: caso recente con timeout OTG](https://www.mi.com/uk/support/faq/details/KA-367318/)

La documentazione ufficiale Samsung conferma invece il percorso hardware tramite adattatore USB-OTG e raccomanda di verificare che entrambi i dispositivi supportino la funzione USB scelta; non promette un toggle Android comune o una durata comune a tutti i Galaxy. [Samsung: usare un cavo o adattatore USB-C/OTG](https://www.samsung.com/us/support/answer/ANS10003434/), [Samsung: opzioni di connessione Galaxy](https://www.samsung.com/us/support/answer/ANS10002546/)

Questi esempi non permettono di dedurre il comportamento del telefono in uso: per quello servono modello esatto, versione Android e versione della skin OEM.

## 6. Cavo, porta, negoziazione USB-C e hub alimentato

USB Type-C separa ruolo dati e ruolo di alimentazione. Nella specifica USB-IF, un DFP è tipicamente il lato host, un UFP è tipicamente il lato dispositivo e una porta dual-role può cambiare dinamicamente i ruoli tramite la negoziazione Type-C/USB-PD. [USB-IF: USB Type-C Cable and Connector Specification](https://www.usb.org/sites/default/files/USB%20Type-C%20Spec%20R2.0%20-%20August%202019.pdf), [USB-IF: USB Type-C System Overview](https://www.usb.org/sites/default/files/D1T1-2%20-%20USB%20Type-C%20System%20Overview.pdf)

Da questo segue una distinzione pratica:

- un **cavo solo ricarica**, un cavo difettoso o un cavo con orientamento/connessione non corretti può impedire la negoziazione o la comunicazione;
- un **hub OTG alimentato** può aiutare se il problema è la corrente richiesta dal radiocomando o una negoziazione di alimentazione instabile;
- nessun cavo o hub può garantire di attivare una policy software OEM che mantiene disabilitato il ruolo host. Questa ultima frase è una deduzione tecnica dai ruoli Type-C e dalla separazione AOSP tra HAL/port role e API USB dell'app, non una promessa del produttore dell'hub.

Per il DJI, la documentazione ufficiale richiede il cavo corretto per il tipo di telefono e segnala che, se appare il prompt USB su Android, normalmente va scelta la modalità **solo carica**; DJI segnala anche di provare un altro cavo e la riconnessione quando il collegamento non riesce. [DJI Mini 4K User Manual](https://dl.djicdn.com/downloads/DJI_Mini_4K/20240524/DJI_Mini_4K_User_Manual_v1.0_CHS.pdf), [DJI: connection errors between remote controller and mobile device](https://repair.dji.com/help/content?customId=01700006810&lang=en&paperDocType=ARTICLE&re=US&spaceId=17), [DJI Store: cavo RC-N USB-C compatibile con RC-N1C](https://store.dji.com/es/product/mavic-air-2-rc-cable)

Nel nostro caso va mantenuta la distinzione tra la procedura DJI Fly ufficiale e il percorso custom del progetto: il bridge usa il VCOM del radiocomando, quindi sono essenziali porta corretta e cavo dati, ma un test di hub deve partire dalla verifica che il telefono riconosca davvero un host USB, non solo che eroghi alimentazione.

## 7. Termux conferma il limite del flusso

Termux:API usa le API USB Android per elencare i dispositivi (getDeviceList()), controllare il permesso e chiamare requestPermission(). Il codice ufficiale Termux mostra esplicitamente questo passaggio e usa il normale dialogo di permesso Android. Non contiene un meccanismo generale per attivare OTG o cambiare il ruolo USB del telefono. [Termux:API UsbAPI.java](https://github.com/termux/termux-api/blob/master/app/src/main/java/com/termux/api/apis/UsbAPI.java), [Termux:API repository](https://github.com/termux/termux-api)

Questo è coerente con l'architettura Android: Termux può usare una periferica che Android ha già enumerato e autorizzato, ma non può trasformare una porta disabilitata dal firmware in un host tramite il solo comando termux-usb.

## 8. Alternative realmente utilizzabili

### A. Cambiare telefono o firmware

È la soluzione più affidabile se vogliamo mantenere il ponte Android senza azioni manuali. Occorre scegliere un modello in cui:

- USB host sia dichiarato/supportato;
- non esista un toggle OTG che si spegne dopo inattività, oppure il firmware permetta di disabilitare il timeout;
- la porta possa fornire la corrente necessaria al RC-N1C;
- il sistema lasci all'app il normale permesso USB.

Non è sufficiente confrontare solo Android 13/14/15: il comportamento è legato anche a SoC, controller Type-C, kernel e personalizzazione OEM.

### B. Hub USB-C alimentato

Vale la pena provarlo solo come test di alimentazione/negoziazione:

1. telefono con adattatore USB-C OTG o hub con power input;
2. alimentazione esterna sull'hub;
3. RC-N1C collegato all'hub con cavo dati;
4. osservare se il telefono enumera il VCOM senza cambiare altri parametri.

Se il telefono non enumera nessun host quando il toggle è spento, il test quasi certamente non eliminerà il toggle; se invece il problema era un brownout o un limite di corrente, può migliorare la stabilità.

### C. Collegamento diretto al PC

È l'alternativa più deterministica per questo progetto: il PC usa direttamente il VCOM del radiocomando e non dipende dall'OTG, dal firmware del telefono, dal Wi-Fi del ponte o dal permesso USB Android. Per l'uso mobile resta utile solo se il telefono non richiede l'intervento manuale.

### D. ADB/Tasker come soluzione personale

Può essere sperimentata solo dopo aver identificato il modello e verificato un comando specifico. È adatta a un telefono personale con Debug Wi-Fi, Shizuku o root; non è una funzionalità da incorporare come requisito dell'app pubblica. Anche in questo caso consiglierei di automatizzare un comando di ruolo AOSP solo se dumpsys usb dimostra che il telefono lo supporta, invece di cercare alla cieca una chiave otg nei settings.

## 9. Dati necessari per una verifica sul telefono reale

Per passare dalla conclusione generale a una risposta definitiva sul tuo telefono servono:

- marca e modello commerciale esatto;
- versione Android e numero build;
- skin e versione OEM, per esempio MIUI/HyperOS/One UI;
- posizione e testo esatto del toggle OTG;
- se il toggle si spegne dopo 10 minuti o solo dopo un riavvio/disconnessione;
- cosa compare nella notifica USB quando il RC-N1C è collegato;
- se il telefono riconosce una chiavetta USB o una tastiera con OTG disattivato;
- output di questi comandi di sola lettura, con telefono collegato e OTG acceso:

~~~powershell
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.display.id
adb shell pm list features | Select-String 'usb.host'
adb shell dumpsys usb
~~~

La documentazione ADB ufficiale conferma che adb shell è il canale per eseguire comandi sul dispositivo; questi comandi raccolgono solo identificazione, feature e stato USB e non modificano il telefono. [Android Developers: ADB shell](https://developer.android.com/tools/adb#shellcommands)

## Conclusione operativa

Per **una normale APK senza root/ADB**, la risposta è: **non si può garantire l'attivazione automatica dell'OTG quando il firmware lo tiene spento**. Possiamo automatizzare il rilevamento e il permesso USB solo dopo che Android ha messo il telefono in modalità host.

Per **un telefono specifico**, la risposta può essere migliore: alcuni firmware non hanno un toggle persistente, alcuni applicano timeout documentati e alcuni possono rispondere a un cambio ruolo ADB. Va verificato il modello, non Android in astratto.

Per **ADB/root/app di sistema**, esistono leve tecniche, inclusi i port role AOSP, ma sono privilegiate, dipendenti dall'HAL/OEM e non equivalgono necessariamente al toggle OTG della UI. Non sono una soluzione affidabile da distribuire agli utenti.

La raccomandazione per il progetto è quindi: prima raccogliere i dati del telefono reale e provare il comando AOSP solo come diagnostica; se il firmware conferma un toggle OTG obbligatorio, scegliere un telefono senza quella policy oppure usare il collegamento diretto RC-N1C→PC. Non c'è una modifica della sola app che possa trasformare quel blocco OEM in una funzione Android universale.
