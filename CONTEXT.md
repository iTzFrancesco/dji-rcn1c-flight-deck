# Flight Deck

Linguaggio condiviso per il controllo del DJI RC-N1C e per l'allenamento al volo FPV
su PC e Android.

## Controllo e collegamento

**RC-N1C**:
Il radiocomando DJI fisico usato come sorgente degli stick, della modalità di volo
e dei pulsanti.
_Avoid_: controller DJI, telecomando, gamepad

**Frame RC**:
Un campione coerente dello stato del radiocomando: quattro stick, pulsanti,
modalità e frequenza di aggiornamento.
_Avoid_: click, evento touch, comando singolo

**Bridge PC/Wi-Fi**:
Il percorso che porta i Frame RC dal telefono al simulatore sul PC tramite rete
locale e gamepad virtuale.
_Avoid_: mirroring, controller automatico, touch bridge

## Allenamento

**Simulatore FPV mobile**:
Un ambiente di allenamento locale che mostra un quadricottero in visuale FPV e
consuma direttamente i Frame RC, senza pilotare un drone reale.
_Avoid_: gioco drone, app DJI, volo reale

**Modalità Acro**:
La modalità in cui gli stick comandano la rotazione del quadricottero senza
self-leveling automatico.
_Avoid_: modalità manuale, modalità normale

**Input diretto**:
Il passaggio dello stato degli stick dal Frame RC al simulatore all'interno dello
stesso prodotto, senza trasformarlo in tocchi o comandi destinati a un'altra app.
_Avoid_: injection, click virtuale, joystick digitale
