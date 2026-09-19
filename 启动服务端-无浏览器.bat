@echo off
REM ============================================================
REM Qimeng Media Server launcher (no-browser variant)
REM
REM Same as the main launcher bat but: minimized window, no
REM auto-browser, stdout/stderr appended to server\console.log.
REM NOTE: startup logic is DUPLICATED in the main launcher bat
REM ("启动服务端.bat" - env vars, dev-mode line, go build). If you
REM change anything here, check that file too - especially the
REM QIMENG_AUTH_DEV_MODE line below (security-sensitive).
REM Comments are ASCII-only on purpose (legacy codepage safety).
REM Stop the server: close the minimized window, or
REM   taskkill /F /IM qimeng-server.exe
REM ============================================================
setlocal
cd /d "%~dp0server"
set QIMENG_DATA_DIR=%~dp0qimeng-data
set QIMENG_AUTH_DEV_MODE=1
if not exist qimeng-server.exe (
  "C:\Program Files\Go\bin\go.exe" build -o qimeng-server.exe ./cmd/qimeng
  if errorlevel 1 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
  )
)
echo [start] qimeng-server.exe (minimized, log: server\console.log)
start "qimeng-server" /min cmd /c "qimeng-server.exe >> console.log 2>&1"
endlocal
