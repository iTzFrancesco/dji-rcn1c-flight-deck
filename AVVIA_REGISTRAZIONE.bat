@echo off
title Registratore volo FPV - video + dati stick
echo ============================================
echo   REGISTRATORE VOLO FPV (Liftoff + RC-N1C)
echo ============================================
echo.
echo Registra lo schermo e i movimenti del gamepad virtuale.
echo Avvia il bridge (USB o WiFi) PRIMA di registrare, altrimenti
echo i dati degli stick risultano a zero.
echo.
echo Per fermare: premi Q + Invio in questa finestra
echo.
"%LOCALAPPDATA%\Programs\Python\Python312\python.exe" "%~dp0registra_volo.py" %*
echo.
pause
