@echo off
rem Thin wrapper: ALL startup logic (env vars, security-sensitive
rem QIMENG_AUTH_DEV_MODE line, go build) lives in _server-common.cmd
rem - edit THAT file, not this one.
rem Mode "nobrowser": minimized window, no browser, output appended
rem to server\console.log. Stop: close the minimized window, or
rem   taskkill /F /IM qimeng-server.exe
call "%~dp0_server-common.cmd" nobrowser
