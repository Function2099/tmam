@echo off
setlocal EnableExtensions
cd /d "%~dp0"

net session >nul 2>&1
if %errorLevel% neq 0 (
  echo TMAM: requesting Administrator to trust the local certificate and allow the app...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -WorkingDirectory '%~dp0' -Verb RunAs"
  exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows-codesign.ps1" -PrepareMachine
if errorlevel 1 (
  echo Failed to prepare this PC for TMAM.
  pause
  exit /b 1
)

echo.
echo Done. If Smart App Control was on, reboot once, then open TMAM.exe.
pause
