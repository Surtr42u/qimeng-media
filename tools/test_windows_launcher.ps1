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
function Invoke-HeadlessLauncher([string]$attempt) {
    $wrapper = Join-Path $fixtureRoot '启动-无头后端.bat'
    $stdout = Join-Path $fixtureRoot ($attempt + '-stdout.log')
    $stderr = Join-Path $fixtureRoot ($attempt + '-stderr.log')
    $process = Start-Process -FilePath $env:ComSpec -ArgumentList ('/d /c call "' + $wrapper + '"') -WorkingDirectory $fixtureRoot -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    if (-not $process.WaitForExit(15000)) { throw 'Launcher did not return' }
    if ($process.ExitCode -ne 0) { throw ('Launcher failed: ' + (Get-Content $stderr -Raw)) }
    return Get-Content -LiteralPath $stdout -Raw
}
try {
    & go build -o $fixtureExe $sourcePath
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic fixture compile failed' }
    $firstOutput = Invoke-HeadlessLauncher 'first'
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8420/readyz' -TimeoutSec 2
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch {}
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw 'Hidden cold start did not become ready' }
    $firstPid = @(Get-NetTCPConnection -LocalPort 8420 -State Listen).OwningProcess | Sort-Object -Unique
    $secondOutput = Invoke-HeadlessLauncher 'second'
    if ($secondOutput -notmatch 'backend already running') { throw 'Repeated launch did not report reuse' }
    $secondPid = @(Get-NetTCPConnection -LocalPort 8420 -State Listen).OwningProcess | Sort-Object -Unique
    $starts = @(Get-Content -LiteralPath (Join-Path $fixtureServer 'synthetic-starts.txt'))
    if ($starts.Count -ne 1 -or ($firstPid -join ',') -ne ($secondPid -join ',')) { throw 'Repeated launch started a duplicate process' }
    Write-Output 'PASS: hidden cold start ready; repeated launcher reuses exactly one synthetic backend.'
} finally {
    # 仅结束路径与本轮合成成品完全相同的进程；临时目录交给 runner 回收。
    Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $fixtureExe } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
}
