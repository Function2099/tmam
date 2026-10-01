@echo off
setlocal
echo TMAM: 以系統管理員重啟 Nginx（套用 %USERPROFILE%\.tmam\nginx\ 設定）
taskkill /F /IM nginx.exe >nul 2>&1
timeout /t 2 /nobreak >nul
cd /d C:\nginx
nginx.exe -t -c "%USERPROFILE%\.tmam\nginx\nginx.conf"
if errorlevel 1 (
  echo nginx -t 失敗，請檢查設定。
  pause
  exit /b 1
)
start "" nginx.exe -c "%USERPROFILE%\.tmam\nginx\nginx.conf"
echo 已啟動。請用 http://localhost/Allowance_Mgr/ 測試登入頁。
pause
