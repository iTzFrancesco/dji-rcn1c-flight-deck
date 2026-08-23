@echo off
title DJI RC-N1C Control Center - modalita' WiFi
echo ============================================
echo   CONTROL CENTER - modalita' WIRELESS (telefono)
echo ============================================
echo.
echo PRIMA DI AVVIARE:
echo  1. Telefono: app RC-N1C WiFi Bridge avviata con radiocomando collegato
echo  2. IP del PC corretto nell'app (per vederlo: ipconfig)
echo  3. NON avviare anche AVVIA_WIFI_BRIDGE.bat: questa finestra
echo     riceve gia' gli stick e crea il gamepad virtuale (porta UDP unica)
echo.
echo Per fermare: chiudi questa finestra oppure premi CTRL+C
echo.
"%LOCALAPPDATA%\Programs\Python\Python312\python.exe" "%~dp0controller_viz.py" --source udp
echo.
pause
