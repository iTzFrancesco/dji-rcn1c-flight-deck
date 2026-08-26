# ADR 0001: simulatore FPV mobile locale nella stessa APK

- Stato: accettato per il primo vertical slice
- Data: 2026-08-26

## Contesto

Il progetto dispone già di due percorsi funzionanti per il radiocomando RC-N1C:

```text
USB RC → bridge Android → UDP/Wi-Fi → PC → gamepad virtuale
USB RC → lettore Android → simulatore locale
```

Il secondo percorso serve per allenarsi fuori casa su un telefono Android datato,
senza root, Accessibility Service o iniezione di tocchi. L'Oppo A53s richiede una
scena leggera, offline e con pochi consumer della seriale USB.

Creare una fisica 6DOF, un modello Acro e una prima mappa da zero aumenterebbe
molto il rischio tecnico. È invece disponibile FPV.Sim, un simulatore browser
con codice MIT, modalità Acro e una scena generata proceduralmente.

## Decisione

Per il primo vertical slice:

1. Manteniamo il bridge PC/Wi-Fi e la dashboard esistenti senza modificarne il
   protocollo o il comportamento.
2. Aggiungiamo una `SimulatorActivity` nella stessa APK Android.
3. La Activity usa direttamente `Rcn1cUsbReader`, già responsabile della lettura
   dei frame raw dell'RC-N1C, evitando un secondo servizio USB e qualsiasi
   iniezione di input nel sistema operativo.
4. FPV.Sim viene vendorizzato in `assets/fpv-sim/` e caricato da una WebView
   locale. Three.js è anch'esso locale; il simulatore non dipende dalla rete.
5. `simulator-bridge.js` espone al simulatore un adapter Gamepad interno che
   converte i quattro assi normalizzati del reader in assi HID standard. I
   pulsanti e la modalità RC sono già trasportati dall'API dell'adapter per il
   successivo mapping di arm/reset e profili.
6. Su Android riduciamo pixel ratio, antialiasing e shadow map per contenere
   consumo e carico GPU.

## Alternative considerate

- **Seconda app Android separata**: raddoppia installazione, permessi e gestione
  del lettore USB senza un vantaggio funzionale nel primo prototipo.
- **Touch injection o Accessibility Service**: non risolve bene l'input
  continuo degli stick, aggiunge permessi invasivi e ha già mostrato fragilità
  nel percorso precedente.
- **Fisica e asset completamente nuovi**: scelta possibile in futuro, ma non
  proporzionata per validare prima latenza, WebView e comportamento sul telefono.
- **APK precompilati o crack**: esclusi per motivi legali, di sicurezza e di
  riproducibilità della build.

## Conseguenze

Il vertical slice condivide la stessa APK e conserva il bridge già usato dal PC.
La simulazione è offline e può essere avviata dal pulsante `SIMULATORE FPV`.
La WebView introduce un limite prestazionale rispetto a Liftoff desktop; per
questo la prima verifica reale deve essere fatta sull'Oppo A53s con la scena
ridotta. Il mapping dei pulsanti RC e una modalità di allenamento più ricca
restano il prossimo incremento, dopo aver validato gli assi.

## Licenze

Il codice importato di FPV.Sim e Three.js conserva i rispettivi file `LICENSE`.
Prima di distribuire una release pubblica bisogna mantenere gli avvisi di
copyright delle dipendenze e verificare eventuali nuove risorse esterne.
