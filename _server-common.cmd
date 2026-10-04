@echo off
rem ============================================================
rem _server-common.cmd - SINGLE source of truth for starting the
rem backend locally. All launcher bats are thin wrappers that call
rem this file - env vars and the security-sensitive dev-mode line
rem live HERE only (converged 2026-09-22; see REVIEW-20260922 4.1).
rem
rem Usage:   _server-common.cmd [console^|nobrowser^|desktop]
rem   console   = visible window, banner + ready-to-copy LAN URLs,
rem               auto-open browser once port 8420 answers.
rem   nobrowser = HEADLESS backend: minimized, no browser; a start
rem               banner (timestamp + LAN URLs) is appended to
rem               server\console.log, then server output appends
rem               there too (background use).
rem   desktop   = desktop shell: ensure the backend is running
rem               (start headless if port 8420 is dead), wait for
rem               it, then launch the Tauri shell exe.
rem
rem File name and all text below stay ASCII on purpose (legacy
rem codepage safety - Chinese echoes garble under native codepage;
rem ALSO sqlc parses .sql files, not this one - unrelated).
rem ============================================================

setlocal enabledelayedexpansion
cd /d "%~dp0server"
set QIMENG_DATA_DIR=%~dp0qimeng-data
rem Dev-mode passwordless login (user agreement: local daily use
rem stays passwordless; see docs/HANDOVER.md user agreements).
rem MUST be off for production/remote deployment (docs/SECURITY.md "Dev mode").
set QIMENG_AUTH_DEV_MODE=1
set DESKTOP_EXE=%~dp0desktop\src-tauri\target\release\qimeng-media-desktop.exe

if /i "%~1"=="nobrowser" goto nobrowser
if /i "%~1"=="desktop" goto desktop

rem ---------------- console mode (browser launcher) ----------------
title Qimeng Media Server

echo ================================================================
echo   Qimeng Media Server (port 8420)
echo ================================================================
echo.
echo  Starting... (first run needs compiling, please wait
echo  until you see the JSON log line with "HTTP" below)
echo.
echo  The web UI will OPEN AUTOMATICALLY in your browser when
echo  the server is ready - no need to start it separately.
echo.
echo  -------- Ready-to-copy addresses --------
echo    This PC : http://127.0.0.1:8420
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4"') do (
    set ip=%%a
    set ip=!ip: =!
    echo    LAN     : http://!ip!:8420
)
echo  -----------------------------------------
echo.
echo  NOTE: 172.x.x.x and 192.168.56.x are virtual adapters -
echo        usually you want the OTHER 192.168.x.x one.
echo  If Windows Firewall pops up, CHECK "Private networks"
echo  and click [Allow access] - otherwise phone cannot connect!
echo.
echo  To stop the server: close this window (or press Ctrl+C)
echo ================================================================
echo.

rem Watcher: silently wait until port 8420 is reachable (server compiled and
rem listening), then open the web UI in your default browser automatically.
rem Gives up after ~60s without opening anything (e.g. compile error).
start "" /min powershell -NoProfile -WindowStyle Hidden -Command "$ok=$false; for($i=0;$i -lt 120;$i++){try{$c=New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1',8420); $c.Close(); $ok=$true; break}catch{Start-Sleep -m 500}}; if($ok){Start-Process 'http://127.0.0.1:8420'}"

call :build
if errorlevel 1 pause
if errorlevel 1 exit /b 1
qimeng-server.exe
pause
exit /b 0

rem ---------------- nobrowser mode (headless backend) -----------------
:nobrowser
echo [start] qimeng-server.exe (headless, log: server\console.log)
call :build
if errorlevel 1 exit /b 1
call :log_banner
start "qimeng-server" /min cmd /c "qimeng-server.exe >> console.log 2>&1"
exit /b 0

rem ---------------- desktop mode (Tauri shell) ----------------
:desktop
netstat -ano | findstr ":8420" | findstr "LISTENING" >nul 2>&1
if errorlevel 1 (
    echo [desktop] backend not running - starting headless backend...
    call :build
    if errorlevel 1 (
        pause
        exit /b 1
    )
    call :log_banner
    start "qimeng-server" /min cmd /c "qimeng-server.exe >> console.log 2>&1"
    rem Wait (up to ~60s) until the backend answers, so the shell
    rem never opens onto a dead port.
    powershell -NoProfile -Command "$ok=$false; for($i=0;$i -lt 120;$i++){try{$c=New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1',8420); $c.Close(); $ok=$true; break}catch{Start-Sleep -m 500}}; if($ok){exit 0}else{exit 1}"
    if errorlevel 1 (
        echo [ERROR] backend did not come up within 60s - see server\console.log
        pause
        exit /b 1
    )
    echo [desktop] backend is up.
) else (
    echo [desktop] backend already running on port 8420.
)
if not exist "%DESKTOP_EXE%" (
    echo [ERROR] desktop shell not built yet. Build it once with:
    echo     cd desktop
    echo     npm install
    echo     npm run icon   ^(first build only^)
    echo     npm run build
    pause
    exit /b 1
)
start "" "%DESKTOP_EXE%"
exit /b 0

rem ---------------- shared helpers ----------------
rem Build ALWAYS (2026-10-03): skipping the build when the exe exists
rem let a stale binary keep serving after source changes - correctness
rem beats fast-start. Fixed output path (not "go run") keeps Windows
rem Firewall from re-prompting per random temp binary.
:build
"C:\Program Files\Go\bin\go.exe" build -o qimeng-server.exe ./cmd/qimeng
if errorlevel 1 (
  echo.
  echo [ERROR] Build failed - read the Go errors above, fix and retry.
)
exit /b %errorlevel%

rem log_banner appends a start banner (timestamp + ready-to-copy URLs)
rem to server\console.log so the headless backend "outputs" the LAN
rem addresses somewhere a human can find them (2026-10-04 user request).
:log_banner
>> console.log echo ========================================================
>> console.log echo   [%date% %time%] Qimeng backend starting (headless)
>> console.log echo   This PC : http://127.0.0.1:8420
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /i "IPv4"') do (
    set ip=%%a
    set ip=!ip: =!
    >> console.log echo   LAN     : http://!ip!:8420
)
>> console.log echo   (172.x / 192.168.56.x = virtual adapters, usually unusable)
>> console.log echo ========================================================
exit /b 0
