@echo off
rem Thin wrapper: ALL startup logic (env vars, security-sensitive
rem QIMENG_AUTH_DEV_MODE line, go build, watcher, banner) lives in
rem _server-common.cmd - edit THAT file, not this one.
rem Mode "console": visible window + auto-open browser on ready.
call "%~dp0_server-common.cmd" console
