@echo off
title DJI RC-N1C WiFi Bridge - ricevente PC
echo ============================================
echo   DJI RC-N1C WIFI BRIDGE - gamepad Xbox virtuale via WiFi
echo ============================================
echo.
echo PRIMA DI AVVIARE:
echo  1. Telefono: radiocomando collegato con cavo dati e script Termux gia' avviato
echo     (termus-usb -E -e "python rcn1c_phone_tx.py IP_DEL_PC" ^<device^>)
echo  2. PC e telefono sulla STESSA rete WiFi (router o hotspot del telefono)
echo  3. IP del PC corretto nel comando sul telefono (per vederlo: ipconfig)
echo  4. Se non arriva niente: consenti Python nel firewall Windows (reti private)
echo.
echo Per fermare: chiudi questa finestra oppure premi CTRL+C
echo.
"%LOCALAPPDATA%\Programs\Python\Python312\python.exe" "%~dp0wireless\rcn1c_wifi_rx_pc.py"
echo.
pause
