@echo off
rem Browser mode: visible window + auto-open web UI (main launcher).
rem Thin wrapper - ALL logic lives in _server-common.cmd (edit THAT).
call "%~dp0_server-common.cmd" console
