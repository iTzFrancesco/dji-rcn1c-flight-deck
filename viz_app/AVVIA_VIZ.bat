@echo off
title DJI RC-N1C Control Center
echo ============================================
echo   RC-N1C CONTROL CENTER - dashboard live
echo ============================================
echo Chiudi questa finestra per fermare la dashboard.
echo.
"%LOCALAPPDATA%\Programs\Python\Python312\python.exe" "%~dp0controller_viz.py"
pause
