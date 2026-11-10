#requires -Version 7.1
# ensure-listen-port.ps1 的纯函数回归：只用合成 netsh 文本。
# 不调用 netsh、Get-NetTCPConnection、Get-Service 或 Stop-Service，
# 不读取/修改本机保留段，也不启动或结束任何进程。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ensure-listen-port.ps1')

function Assert-Equal {
    param($Expected, $Actual, [string]$Message)
    if ($Expected -ne $Actual) {
        throw ('FAIL: {0}: expected [{1}], got [{2}]' -f $Message, $Expected, $Actual)
    }
}

try {
    # 典型英文 netsh 输出；带 * 的行也是真实格式。
    $english = @(
        'Excluded Port Ranges',
        '----------  ----------',
        'Start Port    End Port',
        '      8380        8479',
        '      9000        9005     *'
    )
    $ranges = @(Get-ExcludedTcpPortRanges -Lines $english)
    Assert-Equal 2 $ranges.Count 'English sample should yield two ranges'
    Assert-Equal 8380 $ranges[0].Start 'First range start'
    Assert-Equal 8479 $ranges[0].End 'First range end'
    Assert-Equal 9000 $ranges[1].Start 'Second range start'
    Assert-Equal 9005 $ranges[1].End 'Second range end'

    $hits = @(Get-ReservedRangesForPort -Ranges $ranges -Port 8420)
    Assert-Equal 1 $hits.Count '8420 should fall inside 8380-8479'
    Assert-Equal 0 @(Get-ReservedRangesForPort -Ranges $ranges -Port 8379).Count '8379 is below the range'
    Assert-Equal 1 @(Get-ReservedRangesForPort -Ranges $ranges -Port 8380).Count 'Start boundary is inclusive'
    Assert-Equal 1 @(Get-ReservedRangesForPort -Ranges $ranges -Port 8479).Count 'End boundary is inclusive'
    Assert-Equal 0 @(Get-ReservedRangesForPort -Ranges $ranges -Port 8480).Count '8480 is above the range'
    Assert-Equal 0 @(Get-ReservedRangesForPort -Ranges $ranges -Port 8950).Count 'Gap between ranges must not match'
    Assert-Equal 1 @(Get-ReservedRangesForPort -Ranges $ranges -Port 9005).Count 'Second range end must match'

    # 当前机器上真实出现过的格式：单端口、末列 *、尾部说明行。
    $realistic = @(
        'Protocol tcp Port Exclusion Ranges',
        '',
        'Start Port    End Port',
        '----------    --------',
        '      1455        1455     *',
        '     50000       50059     *',
        '     54235       54235',
        '* - Administered port exclusions.'
    )
    $ranges = @(Get-ExcludedTcpPortRanges -Lines $realistic)
    Assert-Equal 3 $ranges.Count 'Realistic sample should yield three ranges'
    Assert-Equal 1 @(Get-ReservedRangesForPort -Ranges $ranges -Port 1455).Count 'Single-port range must match'
    Assert-Equal 1 @(Get-ReservedRangesForPort -Ranges $ranges -Port 50059).Count 'Managed range must match'
    Assert-Equal 0 @(Get-ReservedRangesForPort -Ranges $ranges -Port 8420).Count 'Unknown port must not match'

    # 畸形/越界行必须忽略，合法行仍保留。
    $malformed = @(
        '8380',
        '9000 8000',
        '70000 70001',
        '0 100',
        'abc def',
        '  1200   1300  *'
    )
    $ranges = @(Get-ExcludedTcpPortRanges -Lines $malformed)
    Assert-Equal 1 $ranges.Count 'Malformed lines should be ignored'
    Assert-Equal 1200 $ranges[0].Start 'Valid tab/space separated range should survive'

    $tabbed = @("`t8380`t`t8479")
    $ranges = @(Get-ExcludedTcpPortRanges -Lines $tabbed)
    Assert-Equal 1 $ranges.Count 'Tab-separated range should parse'
    Assert-Equal 8479 $ranges[0].End 'Tab-separated range end'

    $empty = @(Get-ExcludedTcpPortRanges -Lines @())
    Assert-Equal 0 $empty.Count 'Empty input should yield no ranges'
    Assert-Equal 0 @(Get-ReservedRangesForPort -Ranges $empty -Port 8420).Count 'Empty ranges should not match'

    # QIMENG_LISTEN 端口提取（不读取真实环境变量）。
    Assert-Equal 8420 (Get-PortFromListenValue -ListenValue ':8420') 'Colon-prefixed listen value'
    Assert-Equal 18430 (Get-PortFromListenValue -ListenValue '127.0.0.1:18430') 'IPv4 listen value'
    Assert-Equal 18430 (Get-PortFromListenValue -ListenValue '[::1]:18430') 'IPv6 loopback listen value'
    Assert-Equal 18430 (Get-PortFromListenValue -ListenValue '18430') 'Bare port value'
    Assert-Equal 65535 (Get-PortFromListenValue -ListenValue '0.0.0.0:65535') 'Upper port boundary'
    Assert-Equal $null (Get-PortFromListenValue -ListenValue ':0') 'Port zero is invalid'
    Assert-Equal $null (Get-PortFromListenValue -ListenValue 'host:70000') 'Out-of-range port is invalid'
    Assert-Equal $null (Get-PortFromListenValue -ListenValue 'not-a-listen-value') 'Unparseable value is invalid'
    Assert-Equal $null (Get-PortFromListenValue -ListenValue '') 'Empty value is invalid'

    Write-Host 'PASS: ensure-listen-port synthetic netsh parsing and QIMENG_LISTEN port extraction.'
    exit 0
} catch {
    Write-Host ('FAIL: ' + $_.Exception.Message)
    exit 1
}
