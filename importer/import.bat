@echo off
rem Download YouTube playlists as MP3s into music-player\library.
rem   import.bat                     interactive (paste a URL)
rem   import.bat <playlist-url>      download / update that playlist
rem   import.bat update              re-check every playlist imported before
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
    powershell -NoProfile -ExecutionPolicy Bypass -File setup.ps1 || goto :fail
)
if not exist "tools\ffmpeg\ffmpeg.exe" (
    powershell -NoProfile -ExecutionPolicy Bypass -File setup.ps1 || goto :fail
)
set PYTHONIOENCODING=utf-8
".venv\Scripts\python.exe" ytimport.py %*
set rc=%errorlevel%
if "%~1"=="" pause
exit /b %rc%

:fail
echo Setup failed.
pause
exit /b 1
