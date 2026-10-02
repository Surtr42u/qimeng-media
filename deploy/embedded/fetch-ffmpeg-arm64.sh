#!/usr/bin/env bash
# fetch-ffmpeg-arm64.sh -- hzw1199 FFmpeg arm64 prebuilt fetch (commit-locked +
# SHA-256 verified). Bash port of fetch-ffmpeg-arm64.ps1 for Linux CI / Git Bash
# hosts; the Windows-local make app-embedded-arm64 target keeps calling the .ps1.
# Supply-lock values are byte-identical with fetch-ffmpeg-arm64.ps1 and README.md
# -- upgrading FFmpeg means updating FOUR places in sync: this script, the .ps1
# hash table, and the README hash lines (LGPL-2.1 prebuilt, see README.md).
#
# Usage: bash deploy/embedded/fetch-ffmpeg-arm64.sh [jniLibs-root]
#   (default jniLibs root = repo-relative android/app/src/main/jniLibs, resolved
#    from this script's location -- same layout the .ps1 defaults to)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${1:-"$SCRIPT_DIR/../../android/app/src/main/jniLibs"}"

# ---- supply lock (changing these = changing the supply chain; sync .ps1 + README) ----
COMMIT='90231cc0105aef4f76926b911535f5eb73511b86'
# path under the locked commit + expected SHA-256 (lowercase; compared caseless)
FFMPEG_PATH='ffmpeg-9.0/bin/ffmpeg'
FFMPEG_SHA='9085507b0dc32643b4d6d084a7e7d3469ef17907a7ba15c22d3997ed09c932aa'
FFPROBE_PATH='ffmpeg-9.0/bin/ffprobe'
FFPROBE_SHA='d8e929fcb2b3b5a1a7c8dc234f6d98834eaf8ff2ce408a85060a470b243e0bb5'

# jniLibs packaging names (AGP requires lib*.so; exec goes through an explicit
# path, so the .so extension carries no format semantics)
TARGET="$OUT_DIR/arm64-v8a"
mkdir -p "$TARGET"

sha256_of() {
    # portable digest: sha256sum (Linux/Git Bash) with shasum fallback (macOS)
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1 | tr 'A-F' 'a-f'
    else
        shasum -a 256 "$1" | cut -d' ' -f1 | tr 'A-F' 'a-f'
    fi
}

fetch_one() {
    local name="$1" remote_path="$2" expected_sha="$3" dest="$4"
    if [ -f "$dest" ] && [ "$(sha256_of "$dest")" = "$expected_sha" ]; then
        echo "[skip] $dest exists and hash matches"
        return 0
    fi
    local url="https://raw.githubusercontent.com/hzw1199/Android-FFmpeg-Prebuilt/${COMMIT}/${remote_path}"
    echo "[get ] $url"
    local tmp
    tmp="$(mktemp "${TMPDIR:-/tmp}/qimeng-${name}-arm64.XXXXXX")"
    curl -fsSL --retry 3 -o "$tmp" "$url"
    local got
    got="$(sha256_of "$tmp")"
    if [ "$got" != "$expected_sha" ]; then
        rm -f "$tmp"
        echo "SHA-256 mismatch ($name): got $got, expected $expected_sha -- upstream changed or download truncated; refusing to use this file" >&2
        exit 1
    fi
    mv -f "$tmp" "$dest"
    echo "[ok  ] $dest ($(wc -c <"$dest" | tr -d ' ') bytes)"
}

fetch_one ffmpeg  "$FFMPEG_PATH"  "$FFMPEG_SHA"  "$TARGET/libffmpeg_cli.so"
fetch_one ffprobe "$FFPROBE_PATH" "$FFPROBE_SHA" "$TARGET/libffprobe_cli.so"

echo "done: ffmpeg/ffprobe prebuilts staged into $TARGET"
