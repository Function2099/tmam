@echo off
setlocal
echo 還原 C:\nginx\conf\nginx.conf 後，以系統管理員重啟 Nginx（僅預設歡迎頁，不含 TMAM 路徑規則）
taskkill /F /IM nginx.exe >nul 2>&1
timeout /t 2 /nobreak >nul
cd /d C:\nginx
nginx.exe -t
if errorlevel 1 (
  echo nginx -t 失敗
  pause
  exit /b 1
)
start "" nginx.exe
echo 已啟動。port 80 僅顯示預設歡迎頁，應用請改走 IP:Port 直連 Tomcat。
pause
