# 绮梦影库桌面壳 · libmpv 运行库获取脚本（一次性手动运行）
#
# 作用：下载 mpv-dev 包，取 libmpv-2.dll 落到 mpv/lib/（gitignored，绝不入库——ADR-0036 决策 6
# 许可线：mpv 为 GPLv2+，本仓 MIT，DLL 由用户自取不进仓库分发面），并尽力复制到构建产物旁。
# 用法：powershell -ExecutionPolicy Bypass -File desktop\src-tauri\setup-mpv.ps1 [-Force]
# 手工兜底：任一源下载 mpv-dev-x86_64-*.7z，把 libmpv-2.dll 放到本目录 mpv\lib\ 下即可。
param([switch]$Force)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root = Split-Path -Parent $MyInvocation.MyCommand.Path   # .../desktop/src-tauri
$destDir = Join-Path $root 'mpv\lib'
$dllPath = Join-Path $destDir 'libmpv-2.dll'
if ((Test-Path $dllPath) -and -not $Force) {
  Write-Host "已存在：$dllPath（重取加 -Force）"
  exit 0
}

# 双源轮询：zhongfly 为 shinchiro 的活跃续作，任一命中即用
$sources = @('zhongfly/mpv-winbuild', 'shinchiro/mpv-winbuild-cmake')
# 镜像回退（2026-10-06 增）：直连 GitHub 被墙环境下经 gh-proxy.com 透传 API 与下载。
# 镜像只是传输代理，软件包信任仍锚定源仓库与上游 mpv 官方构建；空串=直连优先。
# 注意：ghproxy 系镜像普遍不代理 api.github.com，实测 gh-proxy.com 可透传（ghproxy.net 只放行 release/raw）。
$mirrors = @('', 'https://gh-proxy.com/')
$tmp = Join-Path $env:TEMP ("qimeng-mpv-dev-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
New-Item -ItemType Directory -Path $destDir -Force | Out-Null

$ok = $false
foreach ($repo in $sources) {
  if ($ok) { break }
  foreach ($mirror in $mirrors) {
    try {
      $via = if ($mirror) { "（经镜像 $mirror）" } else { '（直连）' }
      Write-Host "查询 $repo 的最新 mpv-dev $via ..."
      $rel = Invoke-RestMethod -Uri "${mirror}https://api.github.com/repos/$repo/releases/latest"
      $asset = $rel.assets | Where-Object { $_.name -like 'mpv-dev-x86_64-*.7z' } | Select-Object -First 1
      if (-not $asset) { throw '未找到 mpv-dev-x86_64-*.7z 资产' }
      Write-Host "下载 $($asset.name) $via ..."
      $archive = Join-Path $tmp $asset.name
      Invoke-WebRequest -Uri "${mirror}$($asset.browser_download_url)" -OutFile $archive
      # Windows 内置 bsdtar（libarchive）可解 7z；失败提示装 7-Zip 手工解
      & "$env:SystemRoot\System32\tar.exe" -xf $archive -C $tmp
      if ($LASTEXITCODE -ne 0) { throw 'tar 解包失败（本机 tar 不支持 7z？装 7-Zip 后手工解包）' }
      $found = Get-ChildItem $tmp -Recurse -Filter 'libmpv-2.dll' | Select-Object -First 1
      if (-not $found) { throw '解包后未找到 libmpv-2.dll' }
      Copy-Item $found.FullName $dllPath -Force
      Write-Host "已获取：$($asset.name)（源 $repo$via）"
      $ok = $true
      break
    }
    catch { Write-Warning "${repo} 获取失败$via：$_" }
  }
}
if (-not $ok) {
  Write-Error '全部源失败。请手工下载 mpv-dev-x86_64 包（https://github.com/zhongfly/mpv-winbuild/releases，被墙可试 https://gh-proxy.com/ 前缀拼接下载地址），把 libmpv-2.dll 放到 mpv\lib\libmpv-2.dll'
  exit 1
}

# 尽力复制到构建产物旁（Rust 侧加载器另有 mpv/lib 兜底搜索路径）
foreach ($t in @('target\release', 'target\debug')) {
  $dir = Join-Path $root $t
  if (Test-Path $dir) {
    Copy-Item $dllPath (Join-Path $dir 'libmpv-2.dll') -Force
    Write-Host "已复制到 $t\"
  }
}
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
Write-Host '完成。重新构建/启动桌面壳即可使用原生播放内核。'
