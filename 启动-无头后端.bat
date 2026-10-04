@echo off
rem Headless backend: minimized, no browser; start banner (LAN URLs)
rem and server output go to server\console.log.
rem Thin wrapper - ALL logic lives in _server-common.cmd (edit THAT).
call "%~dp0_server-common.cmd" nobrowser
