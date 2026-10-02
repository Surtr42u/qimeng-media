# fetch-ffmpeg-arm64.ps1 - hzw1199 FFmpeg arm64 prebuilt fetch (commit-locked + SHA-256 verified)
# Task U11 batch D (ADR-0015 form B). Supply-lock values live in README.md next to this
# script -- upgrading FFmpeg means updating FOUR places in sync: this hash table, the
# bash twin fetch-ffmpeg-arm64.sh (CI/Git Bash, 2026-10-02), the README hash lines,
# and the release notes.
# NOTE: ASCII-only on purpose (Windows PowerShell 5.1 reads BOM-less UTF-8 as GBK and
# chokes on non-ASCII strings -- project convention for shell-adjacent scripts).
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File deploy/embedded/fetch-ffmpeg-arm64.ps1 [-OutDir <jniLibs root>]

param(
    [string]$OutDir = "$PSScriptRoot\..\..\android\app\src\main\jniLibs"
)

$ErrorActionPreference = 'Stop'

# ---- supply lock (changing these = changing the supply chain; sync README.md + review) ----
$Commit = '90231cc0105aef4f76926b911535f5eb73511b86'
$Files = @{
    'ffmpeg'  = @{
        Path = 'ffmpeg-9.0/bin/ffmpeg'
        Sha  = '9085507B0DC32643B4D6D084A7E7D3469EF17907A7BA15C22D3997ED09C932AA'
    }
    'ffprobe' = @{
        Path = 'ffmpeg-9.0/bin/ffprobe'
        Sha  = 'D8E929FCB2B3B5A1A7C8DC234F6D98834EAF8FF2CE408A85060A470B243E0BB5'
    }
}
# jniLibs packaging names (AGP requires lib*.so; exec goes through an explicit path,
# so the .so extension carries no format semantics)
$LibName = @{ 'ffmpeg' = 'libffmpeg_cli.so'; 'ffprobe' = 'libffprobe_cli.so' }

function Get-Sha256([string]$file) {
    (Get-FileHash -Algorithm SHA256 $file).Hash.Replace('-', '')
}

$target = Join-Path $OutDir 'arm64-v8a'
New-Item -ItemType Directory -Force -Path $target | Out-Null

foreach ($name in $Files.Keys) {
    $meta = $Files[$name]
    $dest = Join-Path $target $LibName[$name]
    if ((Test-Path $dest) -and (Get-Sha256 $dest) -eq $meta.Sha) {
        Write-Output "[skip] $dest exists and hash matches"
        continue
    }
    $url = "https://raw.githubusercontent.com/hzw1199/Android-FFmpeg-Prebuilt/$($Commit)/$($meta.Path)"
    Write-Output "[get ] $url"
    $tmp = Join-Path $env:TEMP "qimeng-$name-arm64"
    Invoke-WebRequest -Uri $url -OutFile $tmp
    $got = Get-Sha256 $tmp
    if ($got -ne $meta.Sha) {
        throw "SHA-256 mismatch ($name): got $got, expected $($meta.Sha) -- upstream changed or download truncated; refusing to use this file"
    }
    Copy-Item $tmp $dest -Force
    Remove-Item $tmp -Force
    Write-Output "[ok  ] $dest ($((Get-Item $dest).Length) bytes)"
}

Write-Output "done: ffmpeg/ffprobe prebuilts staged into $target"
