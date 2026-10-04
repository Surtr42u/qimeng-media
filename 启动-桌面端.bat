@echo off
rem Desktop shell: starts the backend (headless) if port 8420 is
rem dead, waits for it, then launches the Tauri shell exe.
rem Thin wrapper - ALL logic lives in _server-common.cmd (edit THAT).
call "%~dp0_server-common.cmd" desktop
