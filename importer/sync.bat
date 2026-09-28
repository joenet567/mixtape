@echo off
rem Serve music-player\library to the Mixtape app over Wi-Fi (app: Wi-Fi icon > Find PC > Sync now).
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
    powershell -NoProfile -ExecutionPolicy Bypass -File setup.ps1 || (pause & exit /b 1)
)
set PYTHONIOENCODING=utf-8
".venv\Scripts\python.exe" ytimport.py serve %*
pause
