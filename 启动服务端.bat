@echo off
title Qimeng Media Server
cd /d "%~dp0server"
set QIMENG_DATA_DIR=%~dp0qimeng-data
rem Dev-mode passwordless login (user agreement: no password flow until project is done).
rem MUST be removed for production/remote deployment (see docs/SECURITY.md "Dev mode").
set QIMENG_AUTH_DEV_MODE=1

echo ================================================================
echo   Qimeng Media Server (port 8420)
echo ================================================================
echo.
echo  Starting... (first run needs compiling, please wait
echo  until you see the JSON log line with "HTTP" below)
echo.
echo  -------- Your LAN IP addresses (see below) --------
ipconfig | findstr /i "IPv4"
echo  ----------------------------------------------------
echo.
echo  On your PHONE (same WiFi), open browser and visit:
echo      http://YOUR_IP:8420
echo  NOTE: 172.27.x.x and 192.168.56.x are virtual adapters.
echo        Usually you want the OTHER 192.168.x.x one.
echo.
echo  On THIS PC you can also open:  http://127.0.0.1:8420
echo.
echo  If Windows Firewall pops up, CHECK "Private networks"
echo  and click [Allow access] - otherwise phone cannot connect!
echo.
echo  To stop the server: close this window (or press Ctrl+C)
echo ================================================================
echo.
"C:\Program Files\Go\bin\go.exe" run ./cmd/qimeng
pause
