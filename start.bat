@echo off
setlocal enabledelayedexpansion
title StreamEntity Native Streaming Engine
cd /d "%~dp0"

echo =======================================================
echo          StreamEntity - Native Streaming App
echo    Zero Hypervisor / Zero Docker / Zero BIOS Setup
echo =======================================================
echo.

:: Auto-detect Winget / Gyan FFmpeg if not already in system PATH
where ffmpeg >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
  if exist "%LOCALAPPDATA%\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0.2-full_build\bin" (
    set "PATH=%LOCALAPPDATA%\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0.2-full_build\bin;%PATH%"
  )
)

:: Verify Node.js
where node >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
  echo [ERROR] Node.js is not found in PATH!
  echo Please install Node.js from https://nodejs.org and restart.
  echo.
  pause
  exit /b 1
)

:: Verify FFmpeg
where ffmpeg >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
  echo [WARNING] ffmpeg was NOT found in your PATH.
  echo MKV streaming requires ffmpeg for HLS transcoding.
  echo Please install ffmpeg or add it to PATH from https://ffmpeg.org
  echo.
) else (
  echo [OK] ffmpeg found:
  ffmpeg -version | findstr /C:"ffmpeg version"
  echo.
)

echo Launching StreamEntity at http://localhost:3000 ...
echo.

:: Open default browser cleanly without error popup
start "" "http://localhost:3000"

node server.js
if %ERRORLEVEL% NEQ 0 (
  echo.
  echo StreamEntity exited with error code %ERRORLEVEL%.
)
pause
