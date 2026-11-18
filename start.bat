@echo off
title StreamEntity Native Streaming Engine
cd /d "c:\Users\Vishal\Downloads\Antigravity\StreamEntity"

echo =======================================================
echo          StreamEntity - Native Streaming App
echo    Zero Hypervisor / Zero Docker / Zero BIOS Setup
echo =======================================================
echo.

where ffmpeg >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
  echo [WARNING] ffmpeg was NOT found in your PATH.
  echo MKV streaming requires ffmpeg for HLS transcoding.
  echo Please install ffmpeg or add it to PATH (from https://ffmpeg.org).
  echo.
) else (
  echo [OK] ffmpeg found:
  ffmpeg -version | findstr /C:"ffmpeg version"
  echo.
)

echo Launching StreamEntity at http://localhost:3000 ...
echo.

start chrome "http://localhost:3000" || start "" "http://localhost:3000"
node server.js
pause
