@echo off
title Stop StreamEntity
echo =======================================================
echo          Stopping StreamEntity Server...
echo =======================================================
echo.

for /f "tokens=5" %%a in ('netstat -aon ^| findstr :3000 ^| findstr LISTENING') do (
    echo Terminating process PID %%a listening on port 3000...
    taskkill /F /PID %%a >nul 2>&1
)

echo.
echo StreamEntity server has been stopped.
timeout /t 2 >nul
