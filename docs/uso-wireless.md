# Uso wireless

## App Android

1. Avvia sul PC `viz_app\AVVIA_VIZ_WIFI.bat`.
2. Installa `wireless\RCN1C_Bridge.apk` sul telefono.
3. Collega l'RC-N1C acceso al telefono con un cavo dati USB-C, usando la porta inferiore del radiocomando.
4. Accetta il permesso USB. L'app tenta automaticamente la connessione e cerca il PC via broadcast UDP.
5. Se l'autodiscovery è bloccato dalla rete, inserisci l'IP LAN del PC e premi `AVVIA`.

Il PC e il telefono devono essere sulla stessa rete. Il receiver usa UDP 26789 e il discovery usa UDP 26790. Windows può chiedere di consentire Python sulle reti private.

## Termux

Installare Termux e Termux:API da una fonte affidabile, poi:

```bash
pkg update && pkg install python termux-api
termux-usb -l
termux-usb -r /dev/bus/usb/002/003
termux-usb -E -e "python rcn1c_phone_tx.py IP_DEL_PC" /dev/bus/usb/002/003
```

Il file `wireless/rcn1c_phone_tx.py` usa solo la libreria standard Python e invia il frame UDP v3 con stick, rotella, mask pulsanti e modalità.

## Diagnostica

- Non avviare insieme `AVVIA_WIFI_BRIDGE.bat` e `viz_app\AVVIA_VIZ_WIFI.bat`: entrambi usano UDP 26789.
- Se il receiver non riceve, controllare `ipconfig`, firewall Windows e il permesso USB Android.
- Se l'RC non viene visto, chiudere DJI Assistant 2 e provare la porta inferiore con un cavo dati.
