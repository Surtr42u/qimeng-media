#requires -Version 7.1
# Windows 启动前监听端口预检（PowerShell 7.1+，无第三方模块）。
#
# 退出码：
#   0 = 端口可用，或已在没有 WSL/Docker/Hyper-V 负载时临时停止 winnat 释放端口
#   2 = 端口位于 Windows 保留段，且检测到可能正在使用 winnat 的负载（不自动停服务）
#   3 = 端口已被其他进程监听（只报告 PID/进程名，绝不结束进程）
#   4 = 无法释放端口、保留段未命中、停止 winnat 失败或其他无法自动处理的情况
#
# 默认检查 8420。未显式传入 -Port 且设置了 QIMENG_LISTEN 时，按其中最后一个
# 冒号后的端口检查；脚本不会修改 QIMENG_LISTEN。显式 -Port 始终优先。
#
# 本文件被测试脚本点源（dot-source）时只注册函数，不执行预检。

[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8420
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Get-PortFromListenValue {
    param([string]$ListenValue)
    if ([string]::IsNullOrWhiteSpace($ListenValue)) { return $null }
    $value = $ListenValue.Trim()
    if ($value -match '^(\d{1,5})$') {
        $candidate = [int]$Matches[1]
    } elseif ($value -match ':(\d{1,5})\s*$') {
        $candidate = [int]$Matches[1]
    } else {
        return $null
    }
    if ($candidate -lt 1 -or $candidate -gt 65535) { return $null }
    return $candidate
}

function Invoke-PortProbe {
    param([int]$Port)

    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
        $listener.Server.ExclusiveAddressUse = $true
        $listener.Start()
        $listener.Stop()
        $listener = $null
        return [pscustomobject]@{
            Status    = 'Listening'
            ErrorCode = 0
            Message   = ''
        }
    } catch {
        $exception = $_.Exception
        $socketException = $null
        while ($null -ne $exception) {
            if ($exception -is [System.Net.Sockets.SocketException]) {
                $socketException = $exception
                break
            }
            $exception = $exception.InnerException
        }
        if ($null -eq $socketException) {
            return [pscustomobject]@{
                Status    = 'Error'
                ErrorCode = 0
                Message   = $_.Exception.Message
            }
        }
        $status = 'Error'
        if ($socketException.SocketErrorCode -eq [System.Net.Sockets.SocketError]::AddressAlreadyInUse) {
            $status = 'AddressAlreadyInUse'
        } elseif ($socketException.SocketErrorCode -eq [System.Net.Sockets.SocketError]::AccessDenied) {
            $status = 'AccessDenied'
        }
        return [pscustomobject]@{
            Status    = $status
            ErrorCode = $socketException.ErrorCode
            Message   = $socketException.Message
        }
    } finally {
        if ($null -ne $listener) {
            try { $listener.Stop() } catch { }
        }
    }
}

function Get-PortListeners {
    param([int]$Port)

    if ($null -eq (Get-Command -Name 'Get-NetTCPConnection' -ErrorAction SilentlyContinue)) {
        Write-Host '[WARN] 当前 PowerShell 没有 Get-NetTCPConnection，无法检查已有 IPv4/IPv6 监听。'
        return @()
    }
    # 没有匹配项时该 cmdlet 会写非终止错误；SilentlyContinue 让它与“查询失败”都返回空。
    $connections = @(Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue)
    # State 在部分 Windows 版本上可能为空/无效；RemotePort 为 0 表示本端监听绑定。
    return @($connections | Where-Object { $null -ne $_ -and $_.RemotePort -eq 0 })
}

function Write-PortOwners {
    param([object[]]$Listeners)

    $list = @($Listeners)
    if ($list.Count -eq 0) {
        Write-Host '  （未找到可识别的监听进程，端口可能刚被释放或由系统组件持有）'
        return
    }
    foreach ($listener in $list) {
        $processId = [int]$listener.OwningProcess
        $processName = '<unknown>'
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($null -ne $process) { $processName = $process.ProcessName }
        Write-Host ('  PID {0,-8} {1}   监听地址 {2}:{3}' -f $processId, $processName, $listener.LocalAddress, $listener.LocalPort)
    }
}

function Get-ExcludedTcpPortRanges {
    param([string[]]$Lines)

    $ranges = New-Object System.Collections.Generic.List[object]
    foreach ($line in @($Lines)) {
        if ($null -eq $line) { continue }
        # 例：'      8380        8479' 或 '      8380        8479     *'
        if ($line -match '^\s*(\d{1,5})\s+(\d{1,5})(?:\s*\*)?\s*$') {
            $start = [int]$Matches[1]
            $end = [int]$Matches[2]
            if ($start -ge 1 -and $end -le 65535 -and $start -le $end) {
                $ranges.Add([pscustomobject]@{ Start = $start; End = $end })
            }
        }
    }
    return $ranges.ToArray()
}

function Get-ReservedRangesForPort {
    param([object[]]$Ranges, [int]$Port)

    return @($Ranges | Where-Object {
        $null -ne $_ -and $Port -ge $_.Start -and $Port -le $_.End
    })
}

function Write-ExcludedRangeTable {
    param([object[]]$Ranges)

    $list = @($Ranges)
    if ($list.Count -eq 0) {
        Write-Host '  （当前没有列出任何 TCP 保留段）'
        return
    }
    Write-Host '  开始端口 - 结束端口'
    foreach ($range in $list) {
        Write-Host ('  {0,8} - {1,8}' -f $range.Start, $range.End)
    }
}

function Get-ExcludedTcpRangeLines {
    try {
        $lines = @(& netsh.exe interface ipv4 show excludedportrange protocol=tcp 2>$null)
        if ($LASTEXITCODE -ne 0) { return @() }
        return $lines
    } catch {
        return @()
    }
}

function Get-RunningWslDistributions {
    $wslCommand = Get-Command -Name 'wsl.exe' -ErrorAction SilentlyContinue
    if ($null -eq $wslCommand) { return @() }

    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(& $wslCommand.Source -l --running 2>$null)
        $exitCode = $LASTEXITCODE
    } catch {
        return @()
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($exitCode -ne 0) { return @() }

    $nonEmpty = @(
        $lines |
            ForEach-Object { ([string]$_).Replace([char]0, '').Trim() } |
            Where-Object { $_.Length -gt 0 }
    )
    # 只输出标题行通常表示没有运行中的发行版；失败/空输出也按不活跃处理。
    if ($nonEmpty.Count -le 1) { return @() }
    return @($nonEmpty | Select-Object -Skip 1)
}

function Get-ActiveNatWorkload {
    $details = New-Object System.Collections.Generic.List[string]
    foreach ($name in @('vmmem', 'vmwp', 'docker', 'wsl')) {
        foreach ($process in @(Get-Process -Name $name -ErrorAction SilentlyContinue)) {
            $details.Add(('{0} (PID {1})' -f $process.ProcessName, $process.Id))
        }
    }
    foreach ($distribution in @(Get-RunningWslDistributions)) {
        $details.Add(('WSL: {0}' -f $distribution))
    }
    return [pscustomobject]@{
        Active  = ($details.Count -gt 0)
        Details = @($details)
    }
}

function Write-PortAlternatives {
    Write-Host ''
    Write-Host '备选方案：改用其他端口启动（不会修改现有环境变量）。'
    Write-Host '  设置 QIMENG_LISTEN 后重新运行启动脚本，本预检会自动检查其中端口。'
    Write-Host '  命令提示符示例: set QIMENG_LISTEN=:18420'
    Write-Host '  PowerShell 示例: $env:QIMENG_LISTEN = '':18420'''
}

function Write-ReservedRangeFailure {
    param([object[]]$Ranges, [int]$Port)

    Write-Host ''
    Write-Host ('[INFO] 当前 IPv4 TCP 保留段（netsh interface ipv4 show excludedportrange protocol=tcp）：')
    Write-ExcludedRangeTable $Ranges
    Write-Host ('[INFO] 未能自动释放端口 {0}。' -f $Port)
    Write-PortAlternatives
}

# 被测试脚本点源时只注册函数。
if ($MyInvocation.InvocationName -eq '.') { return }

$portSource = '默认 8420'
if ($PSBoundParameters.ContainsKey('Port')) {
    $portSource = '-Port 参数'
} else {
    $listenValue = [Environment]::GetEnvironmentVariable('QIMENG_LISTEN')
    if (-not [string]::IsNullOrWhiteSpace($listenValue)) {
        $listenPort = Get-PortFromListenValue $listenValue
        if ($null -ne $listenPort) {
            $Port = $listenPort
            $portSource = 'QIMENG_LISTEN'
        } else {
            Write-Host '[WARN] QIMENG_LISTEN 中无法解析出有效端口，按默认 8420 检查。'
        }
    }
}

Write-Host ('[preflight] 检查监听端口 {0}（来源：{1}）...' -f $Port, $portSource)

# 先查已有监听：Windows 允许 IPv4 绑定与 :: 双栈监听并存，仅靠 0.0.0.0
# 绑定测试会漏掉正在运行的 Go 服务，也会短暂干扰已有服务。
$existingListeners = @(Get-PortListeners -Port $Port)
if ($existingListeners.Count -gt 0) {
    Write-Host ('[ERROR] 端口 {0} 已被占用；后端不会启动。' -f $Port)
    Write-PortOwners $existingListeners
    Write-Host '        本脚本不会结束任何进程。请停止该程序，或改用其他端口：'
    Write-PortAlternatives
    exit 3
}

$probe = Invoke-PortProbe -Port $Port
if ($probe.Status -eq 'Listening') {
    Write-Host ('[OK] 端口 {0} 可用（0.0.0.0 绑定测试通过）。' -f $Port)
    exit 0
}

if ($probe.Status -eq 'AddressAlreadyInUse') {
    $existingListeners = @(Get-PortListeners -Port $Port)
    Write-Host ('[ERROR] 端口 {0} 已被占用；后端不会启动。' -f $Port)
    Write-PortOwners $existingListeners
    Write-Host '        本脚本不会结束任何进程。请停止该程序，或改用其他端口：'
    Write-PortAlternatives
    exit 3
}

if ($probe.Status -ne 'AccessDenied') {
    Write-Host ('[ERROR] 绑定 0.0.0.0:{0} 失败：{1}' -f $Port, $probe.Message)
    $rangeLines = @(Get-ExcludedTcpRangeLines)
    $ranges = @(Get-ExcludedTcpPortRanges -Lines $rangeLines)
    Write-ReservedRangeFailure -Ranges $ranges -Port $Port
    exit 4
}

# WSAEACCES：读取 netsh 保留段，判断是否命中。
Write-Host ('[WARN] 绑定 0.0.0.0:{0} 被拒绝（WSAEACCES，错误码 {1}）。' -f $Port, $probe.ErrorCode)
$rangeLines = @(Get-ExcludedTcpRangeLines)
$ranges = @(Get-ExcludedTcpPortRanges -Lines $rangeLines)
$matchingRanges = @(Get-ReservedRangesForPort -Ranges $ranges -Port $Port)

if ($matchingRanges.Count -eq 0) {
    Write-Host ('[INFO] 端口 {0} 不在当前 netsh 列出的 IPv4 TCP 保留段中。' -f $Port)
    Write-Host '        可能由其他安全软件、ACL 或 Hyper-V 以外的驱动占用。'
    Write-ReservedRangeFailure -Ranges $ranges -Port $Port
    exit 4
}

Write-Host ('[INFO] 端口 {0} 位于 Windows TCP 保留段：' -f $Port)
Write-ExcludedRangeTable $matchingRanges
Write-Host '        这通常由 Hyper-V/WSL2/Docker 的 winnat 驱动动态预留。'

$winnat = Get-Service -Name 'winnat' -ErrorAction SilentlyContinue
if ($null -eq $winnat -or $winnat.Status -ne 'Running') {
    Write-Host '        当前 winnat 服务未运行；脚本不会停止或修改其他服务。'
    Write-ReservedRangeFailure -Ranges $ranges -Port $Port
    exit 4
}

$workload = Get-ActiveNatWorkload
if ($workload.Active) {
    Write-Host '[WARN] winnat 正在运行，且检测到可能的 WSL/Docker/Hyper-V 负载：'
    foreach ($detail in $workload.Details) {
        Write-Host ('       {0}' -f $detail)
    }
    Write-Host '       为避免破坏现有网络，脚本不会自动停止 winnat。'
    Write-Host ''
    Write-Host '处理方式（二选一）：'
    Write-Host '  A. 以管理员身份打开 PowerShell，临时停止 winnat，再启动后端，最后恢复：'
    Write-Host '       Stop-Service winnat -Force'
    Write-Host '       <重新运行启动脚本>'
    Write-Host '       Start-Service winnat'
    Write-Host '     停止 winnat 会短暂中断 WSL2/Docker/Hyper-V 的 NAT 网络。'
    Write-Host '  B. 改用其他端口：'
    Write-PortAlternatives
    exit 2
}

Write-Host '[INFO] 未检测到活跃的 WSL/Docker/Hyper-V 负载，临时停止 winnat 释放端口...'
try {
    Stop-Service -Name 'winnat' -Force -ErrorAction Stop
} catch {
    Write-Host ('[ERROR] 无法停止 winnat 服务：{0}' -f $_.Exception.Message)
    Write-Host '        通常需要管理员权限；请以管理员身份运行 PowerShell 后执行：'
    Write-Host '        Stop-Service winnat -Force'
    Write-ReservedRangeFailure -Ranges $ranges -Port $Port
    exit 4
}

# 服务停止后驱动释放保留段可能有短暂延迟，做有界重试。
$releaseProbe = $null
for ($attempt = 0; $attempt -lt 5; $attempt++) {
    if ($attempt -gt 0) { Start-Sleep -Milliseconds 300 }
    $releaseProbe = Invoke-PortProbe -Port $Port
    if ($releaseProbe.Status -eq 'Listening') { break }
}

if ($releaseProbe.Status -eq 'Listening') {
    $existingListeners = @(Get-PortListeners -Port $Port)
    if ($existingListeners.Count -gt 0) {
        Write-Host ('[ERROR] winnat 已停止，但端口 {0} 仍被其他进程占用。' -f $Port)
        Write-PortOwners $existingListeners
        Write-PortAlternatives
        exit 3
    }
    Write-Host ('[OK] 已临时停止 winnat 并释放端口 {0}。' -f $Port)
    Write-Host '     如使用 WSL/Docker，请稍后执行: Start-Service winnat'
    exit 0
}

if ($releaseProbe.Status -eq 'AddressAlreadyInUse') {
    $existingListeners = @(Get-PortListeners -Port $Port)
    Write-Host ('[ERROR] winnat 已停止，但端口 {0} 被其他进程占用。' -f $Port)
    Write-PortOwners $existingListeners
    Write-PortAlternatives
    exit 3
}

Write-Host ('[ERROR] winnat 已停止，但端口 {0} 仍无法绑定：{1}' -f $Port, $releaseProbe.Message)
Write-ReservedRangeFailure -Ranges $ranges -Port $Port
exit 4
