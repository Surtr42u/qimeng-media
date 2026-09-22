@echo off
rem ============================================================
rem _server-common.cmd - SINGLE source of truth for starting the
rem dev server locally. Both launcher bats ("启动服务端.bat" and
rem "启动服务端-无浏览器.bat") are thin wrappers that call this
rem file - env vars and the security-sensitive dev-mode line live
rem HERE only (converged 2026-09-22; the two bats used to duplicate
rem them, see REVIEW-20260922 section 4.1).
rem
rem Usage:   _server-common.cmd [console^|nobrowser]
rem   console   = visible window, banner + LAN IPs, auto-open
rem               browser once port 8420 answers (main launcher).
rem   nobrowser = minimized window, no browser, stdout/stderr
rem               appended to server\console.log (background use).
rem
rem File name and all text below stay ASCII on purpose (legacy
rem codepage safety - Chinese echoes garble under native codepage).
rem ============================================================

cd /d "%~dp0server"
set QIMENG_DATA_DIR=%~dp0qimeng-data
rem Dev-mode passwordless login (user agreement: local daily use
rem stays passwordless; see docs/HANDOVER.md user agreements).
rem MUST be off for production/remote deployment (docs/SECURITY.md "Dev mode").
set QIMENG_AUTH_DEV_MODE=1

if /i "%~1"=="nobrowser" goto nobrowser

rem ---------------- console mode (main launcher) ----------------
title Qimeng Media Server

rem Watcher: silently wait until port 8420 is reachable (server compiled and
rem listening), then open the web UI in your default browser automatically.
rem Gives up after ~60s without opening anything (e.g. compile error).
start "" /min powershell -NoProfile -WindowStyle Hidden -Command "$ok=$false; for($i=0;$i -lt 120;$i++){try{$c=New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1',8420); $c.Close(); $ok=$true; break}catch{Start-Sleep -m 500}}; if($ok){Start-Process 'http://127.0.0.1:8420'}"

echo ================================================================
echo   Qimeng Media Server (port 8420)
echo ================================================================
echo.
echo  Starting... (first run needs compiling, please wait
echo  until you see the JSON log line with "HTTP" below)
echo.
echo  The web UI will OPEN AUTOMATICALLY in your browser when
echo  the server is ready - no need to start it separately.
echo  Manual address (if the browser does not pop up):
echo      http://127.0.0.1:8420
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
echo  If Windows Firewall pops up, CHECK "Private networks"
echo  and click [Allow access] - otherwise phone cannot connect!
echo.
echo  To stop the server: close this window (or press Ctrl+C)
echo ================================================================
echo.
goto build

rem ---------------- nobrowser mode (background) -----------------
:nobrowser
echo [start] qimeng-server.exe (minimized, log: server\console.log)

rem ---------------- shared build (always in console mode, --------
rem ---------------- skip-if-exists in nobrowser mode) ------------
:build
if /i "%~1"=="nobrowser" if exist qimeng-server.exe goto run_bg
rem Build to a FIXED path (not "go run") - go run creates a new random
rem temp binary each start, which makes Windows Firewall treat it as an
rem unknown app and pop the allow-dialog EVERY time. Fixed path + the
rem port-level firewall rule (see docs/HANDOVER.md) = no more prompts.
"C:\Program Files\Go\bin\go.exe" build -o qimeng-server.exe ./cmd/qimeng
if errorlevel 1 (
  echo.
  echo [ERROR] Build failed - read the Go errors above, fix and retry.
  echo Window stays open so you can read them.
  pause
  exit /b 1
)

if /i "%~1"=="nobrowser" goto run_bg
qimeng-server.exe
pause
exit /b 0

:run_bg
start "qimeng-server" /min cmd /c "qimeng-server.exe >> console.log 2>&1"
exit /b 0
