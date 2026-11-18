@echo off
title StreamEntity Native Streaming Engine
cd /d "c:\Users\Vishal\Downloads\Antigravity\StreamEntity"

echo =======================================================
echo          StreamEntity - Native Streaming App
echo    Zero Hypervisor / Zero Docker / Zero BIOS Setup
echo =======================================================
echo.
echo Launching StreamEntity at http://localhost:3000 ...
echo.

start chrome "http://localhost:3000" || start "" "http://localhost:3000"
node server.js
pause
