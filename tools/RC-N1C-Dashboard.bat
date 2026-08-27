@echo off
setlocal EnableExtensions EnableDelayedExpansion
title RC-N1C Dashboard + controller - aggiornamento automatico

set "PROJECT=%~dp0.."
for %%I in ("%PROJECT%") do set "PROJECT=%%~fI"
set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
set "URL=http://127.0.0.1:8123"

if not exist "%PROJECT%\viz_app\controller_viz.py" (
    echo [ERRORE] Progetto non trovato: %PROJECT%
    pause
    exit /b 1
)
if not exist "%PY%" (
    echo [ERRORE] Python non trovato: %PY%
    pause
    exit /b 1
)
cd /d "%PROJECT%"

echo [1/4] Chiudo eventuali istanze precedenti...
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'python.exe' -or $_.Name -eq 'pythonw.exe') -and $_.CommandLine -match 'controller_viz\.py|dji_rcn1c_bridge\.py|rcn1c_wifi_rx_pc\.py' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" >nul 2>&1
powershell -NoProfile -Command "Start-Sleep -Milliseconds 800"

echo [2/4] Controllo aggiornamenti GitHub...
set "DIRTY="
for /f "delims=" %%D in ('git status --porcelain 2^>nul') do set "DIRTY=1"
if defined DIRTY (
    echo [INFO] Aggiornamento saltato: ci sono modifiche locali non committate.
) else (
    git pull --ff-only
    if errorlevel 1 echo [WARN] Aggiornamento non disponibile; uso i file locali.
)

echo [3/4] Rilevo il collegamento...
set "SOURCE=udp"
for /f "delims=" %%S in ('%PY% -c "import serial.tools.list_ports as p; print('serial' if any('VCOM For Protocol' in (x.description or '') for x in p.comports()) else 'udp')"') do set "SOURCE=%%S"

if /I "!SOURCE!"=="serial" (
    echo [OK] Radiocomando USB rilevato: avvio dashboard + controller Xbox.
    start "RC-N1C Dashboard" /min "%PY%" "%PROJECT%\viz_app\controller_viz.py" --gamepad --no-browser
) else (
    echo [OK] Nessun VCOM USB rilevato: avvio la sorgente UDP Wi-Fi.
    start "RC-N1C Dashboard" /min "%PY%" "%PROJECT%\viz_app\controller_viz.py" --source udp --gamepad --no-browser
)

echo [4/4] Attendo la dashboard...
for /l %%N in (1,1,30) do (
    powershell -NoProfile -Command "if (Test-NetConnection 127.0.0.1 -Port 8123 -InformationLevel Quiet) { exit 0 } else { exit 1 }" >nul 2>&1
    if not errorlevel 1 goto dashboard_ready
    timeout /t 1 /nobreak >nul
)
echo [ERRORE] La dashboard non ha risposto sulla porta 8123.
pause
exit /b 1

:dashboard_ready
echo [OK] Dashboard pronta: %URL%
start "" "%URL%/?v=%RANDOM%%RANDOM%"
exit /b 0
