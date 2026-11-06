@echo off
setlocal
if not defined QIMENG_RESEARCH_SIDECAR_TOKEN (
  echo [research-sidecar] QIMENG_RESEARCH_SIDECAR_TOKEN is not set. Set it in the service environment; this script never reads, prints or writes the token. 1>&2
  exit /b 1
)
node "%~dp0sidecar.mjs"
