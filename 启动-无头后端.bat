@echo off
rem Backend console: stays visible in the taskbar, no browser or desktop UI.
rem Thin wrapper - ALL logic lives in _server-common.cmd (edit THAT).
call "%~dp0_server-common.cmd" nobrowser
