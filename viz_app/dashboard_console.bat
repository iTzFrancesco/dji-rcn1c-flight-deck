@echo off
title RC-N1C Dashboard
cd /d "%~dp0"
"%LOCALAPPDATA%\Programs\Python\Python312\python.exe" controller_viz.py --source udp --no-browser
echo.
echo Server terminato.
pause
