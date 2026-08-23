@echo off
title DJI RC-N1C Bridge - Xbox 360 virtuale
echo ============================================
echo   DJI RC-N1C BRIDGE - gamepad Xbox virtuale
echo ============================================
echo.
echo PRIMA DI AVVIARE:
echo  1. Radiocomando ACCESO
echo  2. Collegato alla porta USB-C INFERIORE (quella di ricarica)
echo  3. DJI Assistant 2 CHIUSO (tiene occupata la porta)
echo.
echo Per fermare: chiudi questa finestra oppure premi CTRL+C
echo.
"%LOCALAPPDATA%\Programs\Python\Python312\python.exe" "%~dp0dji_rcn1c_bridge.py"
echo.
pause
