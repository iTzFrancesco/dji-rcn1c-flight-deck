@echo off
setlocal
title DJI RC-N1C - Monitor pulsanti
set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"

if not exist "%PY%" (
    echo Python non trovato: "%PY%"
    pause
    exit /b 1
)

cd /d "%~dp0"
echo.
echo Premi e rilascia i pulsanti del radiocomando.
echo Ogni cambiamento verra' mostrato qui sotto.
echo.
"%PY%" "%~dp0wireless\button_live_probe.py" --duration 300
echo.
echo Monitor terminato.
pause
