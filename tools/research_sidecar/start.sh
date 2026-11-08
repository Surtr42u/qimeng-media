#!/bin/sh
set -eu
if [ -z "${QIMENG_RESEARCH_SIDECAR_TOKEN:-}" ]; then
  echo "[research-sidecar] QIMENG_RESEARCH_SIDECAR_TOKEN is not set. Set it in the service environment; this script never reads, prints or writes the token." >&2
  exit 1
fi
exec node "$(dirname "$0")/sidecar.mjs"
