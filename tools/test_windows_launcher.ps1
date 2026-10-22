# 只在云端 Windows runner 使用合成 HTTP 程序，绝不启动本机真实服务或数据库。
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or -not $env:RUNNER_TEMP) { throw 'Only run in GitHub Actions' }
if (Get-NetTCPConnection -LocalPort 8420 -State Listen -ErrorAction SilentlyContinue) { throw 'Runner test port is occupied' }
$repoRoot = Split-Path -Parent $PSScriptRoot
$fixtureRoot = Join-Path $env:RUNNER_TEMP ('qimeng-launcher-' + [guid]::NewGuid().ToString('N'))
$fixtureServer = Join-Path $fixtureRoot 'server'
$fixtureExe = Join-Path $fixtureServer 'qimeng-server.exe'
New-Item -ItemType Directory -Path $fixtureServer -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repoRoot '_server-common.cmd') -Destination $fixtureRoot
Copy-Item -LiteralPath (Join-Path $repoRoot '启动-无头后端.bat') -Destination $fixtureRoot
$sourcePath = Join-Path $fixtureRoot 'synthetic.go'
@'
package main
import (
    "fmt"
    "log"
    "net/http"
    "os"
)
func main() {
    file, err := os.OpenFile("synthetic-starts.txt", os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
    if err != nil { log.Fatal(err) }
    if _, err := fmt.Fprintln(file, os.Getpid()); err != nil { log.Fatal(err) }
    if err := file.Close(); err != nil { log.Fatal(err) }
    http.HandleFunc("/readyz", func(w http.ResponseWriter, r *http.Request) {
        w.WriteHeader(http.StatusOK)
    })
    log.Fatal(http.ListenAndServe("127.0.0.1:8420", nil))
}
'@ | Set-Content -LiteralPath $sourcePath -Encoding utf8
function Start-HeadlessLauncher([string]$attempt) {
    $wrapper = Join-Path $fixtureRoot '启动-无头后端.bat'
    $stdout = Join-Path $fixtureRoot ($attempt + '-stdout.log')
    $stderr = Join-Path $fixtureRoot ($attempt + '-stderr.log')
    $process = Start-Process -FilePath $env:ComSpec -ArgumentList ('/d /c call "' + $wrapper + '"') -WorkingDirectory $fixtureRoot -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    return [pscustomobject]@{process=$process;stdout=$stdout;stderr=$stderr}
}
$launchers = @()
try {
    & go build -o $fixtureExe $sourcePath
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic fixture compile failed' }
    $first = Start-HeadlessLauncher 'first'
    $launchers += $first
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8420/readyz' -TimeoutSec 2
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch {}
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw 'Console cold start did not become ready' }
    if ($first.process.HasExited) { throw 'Backend console exited while backend was running' }
    $firstPid = @(Get-NetTCPConnection -LocalPort 8420 -State Listen).OwningProcess | Sort-Object -Unique
    $second = Start-HeadlessLauncher 'second'
    $launchers += $second
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline -and (Get-Content $second.stdout -Raw) -notmatch '\[backend\] RUNNING') { Start-Sleep -Milliseconds 250 }
    $secondOutput = Get-Content $second.stdout -Raw
    if ($secondOutput -notmatch 'backend already running' -or $secondOutput -notmatch '\[backend\] RUNNING') { throw 'Repeated launch did not show running console status' }
    if ($second.process.HasExited) { throw 'Existing backend monitor console closed' }
    $secondPid = @(Get-NetTCPConnection -LocalPort 8420 -State Listen).OwningProcess | Sort-Object -Unique
    $starts = @(Get-Content -LiteralPath (Join-Path $fixtureServer 'synthetic-starts.txt'))
    if ($starts.Count -ne 1 -or ($firstPid -join ',') -ne ($secondPid -join ',')) { throw 'Repeated launch started a duplicate process' }
    Write-Output 'PASS: backend console remains open; repeated launcher shows RUNNING and reuses exactly one backend.'
} finally {
    foreach ($launcher in $launchers) {
        if (Get-Process -Id $launcher.process.Id -ErrorAction SilentlyContinue) {
            & taskkill.exe /PID $launcher.process.Id /T /F
            if ($LASTEXITCODE -ne 0) { throw 'Fixture launcher tree cleanup failed' }
        }
    }
    # 仅结束路径与本轮合成成品完全相同的进程；临时目录交给 runner 回收。
    Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $fixtureExe } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
}
