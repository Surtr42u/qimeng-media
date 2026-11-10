@echo off
rem Console mode: visible window + ready addresses; open the web UI manually.
rem Thin wrapper - ALL logic lives in _server-common.cmd (edit THAT).
call "%~dp0_server-common.cmd" console
