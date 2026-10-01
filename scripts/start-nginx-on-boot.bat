@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem TMAM: start Nginx at Windows logon / boot with the same config TMAM uses.
rem Register via Task Scheduler (At logon / At startup), Run with highest privileges (port 80).
rem Failure is OK — TMAM still auto-starts Nginx when the app launches (tmam.nginx.auto-start).

set "CONFIG=%USERPROFILE%\.tmam\nginx\nginx.conf"
if not exist "%CONFIG%" (
  echo [TMAM] Nginx config not found: %CONFIG%
  echo [TMAM] Run TMAM once and apply a path-proxy service first.
  exit /b 0
)

set "NGINX_EXE="
if defined NGINX_HOME (
  if exist "%NGINX_HOME%\nginx.exe" set "NGINX_EXE=%NGINX_HOME%\nginx.exe"
)

if not defined NGINX_EXE if exist "C:\nginx\nginx.exe" set "NGINX_EXE=C:\nginx\nginx.exe"
if not defined NGINX_EXE if exist "C:\Program Files\nginx\nginx.exe" set "NGINX_EXE=C:\Program Files\nginx\nginx.exe"
if not defined NGINX_EXE if exist "C:\tools\nginx\nginx.exe" set "NGINX_EXE=C:\tools\nginx\nginx.exe"

if not defined NGINX_EXE (
  for /f "delims=" %%i in ('where nginx.exe 2^>nul') do (
    if not defined NGINX_EXE set "NGINX_EXE=%%i"
  )
)

if not defined NGINX_EXE (
  echo [TMAM] nginx.exe not found. Set NGINX_HOME or install under C:\nginx
  exit /b 0
)

for %%I in ("%NGINX_EXE%") do set "NGINX_DIR=%%~dpI"
cd /d "%NGINX_DIR%"

rem Already listening on 80? skip (best-effort)
powershell -NoProfile -Command "try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', 80); $c.Close(); exit 0 } catch { exit 1 }" >nul 2>&1
if %errorLevel% equ 0 (
  echo [TMAM] Port 80 already in use — skip start
  exit /b 0
)

echo [TMAM] Starting: "%NGINX_EXE%" -c "%CONFIG%"
start "" /b "%NGINX_EXE%" -c "%CONFIG%"
exit /b 0
