# =====================================================================
#  connect-phone.ps1 — 一条命令完成无线调试「配对 + 连接」
#
#  用法（手机弹窗开着的时候执行）：
#     cd E:\desktop\酒馆\tools\ziJi
#     .\tools\connect-phone.ps1 -Pair 192.168.137.173:39299 -Code 797139 -Connect 192.168.137.173:37117
#
#  手机 IP 通常不变（你连的是电脑热点，固定 192.168.137.173）。
#  配对端口每次点开弹窗都不同，连接端口也是。
#
#  不想每次敲这么多参数，就把手机当前信息填进下面的默认值，之后直接跑：
#     .\tools\connect-phone.ps1
# =====================================================================
param(
    [string]$Pair,                                  # 弹窗里的「IP 地址和端口」
    [string]$Code,                                  # 弹窗里的 6 位「WLAN 配对码」
    [string]$Connect,                               # 无线调试主页面的「IP 地址和端口」
    [string]$Adb = 'D:\Android\Sdk\platform-tools\adb.exe'
)

# ↓↓↓ 把这几个默认值改成手机当前显示的，以后就能直接跑脚本 ↓↓↓
$DefaultPairPort    = '39299'
$DefaultConnectPort = '37117'
$PhoneIp            = '192.168.137.173'
# ↑↑↑ 改这里 ↑↑↑

$ErrorActionPreference = 'Continue'
if (-not (Test-Path $Adb)) { throw "找不到 adb：$Adb" }

if (-not $PhoneIp) { $PhoneIp = '192.168.137.173' }
if (-not $Connect -and $DefaultConnectPort) { $Connect = "$PhoneIp`:$DefaultConnectPort" }
if (-not $Pair -and $DefaultPairPort)       { $Pair    = "$PhoneIp`:$DefaultPairPort" }

if (-not $Code) {
    $Code = Read-Host "请输入手机弹窗里的 6 位配对码"
}
if (-not $Pair) {
    $Pair = Read-Host "请输入手机弹窗里的「IP 地址和端口」(形如 192.168.137.173:39299)"
}
$Code = $Code.Trim()

Write-Host "`n=== 1. 连通性检查 ===" -ForegroundColor Cyan
$host_, $port_ = $Pair.Split(':')
$ok = $false
try { $t = New-Object System.Net.Sockets.TcpClient; $t.Connect($host_, [int]$port_); $ok = $true; $t.Close() } catch {}
if ($ok) { Write-Host "配对端口 $Pair 可达" -ForegroundColor Green }
else { Write-Host "配对端口 $Pair 不可达 —— 弹窗可能已经关闭或过期，需要重新点开" -ForegroundColor Red }

Write-Host "`n=== 2. 配对 $Pair ===" -ForegroundColor Cyan
# 注意：不能用 `"码" | adb pair`，PowerShell 管道不会正确关闭 stdin，
# adb 会报 protocol fault。这里用 .NET 进程精确控制 stdin。
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $Adb
$psi.Arguments = "pair $Pair"
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$p = [System.Diagnostics.Process]::Start($psi)
$p.StandardInput.WriteLine($Code)
$p.StandardInput.Close()                       # 立刻关掉 stdin，adb 才知道输入结束
$out = $p.StandardOutput.ReadToEnd()
$err = $p.StandardError.ReadToEnd()
$p.WaitForExit(30000) | Out-Null

if ($out.Trim()) { Write-Host $out.Trim() }
if ($err.Trim()) {
    if ($err -match 'Successfully paired') { Write-Host $err.Trim() -ForegroundColor Green }
    else { Write-Host $err.Trim() -ForegroundColor Yellow }
}

$paired = ($out + $err) -match 'Successfully paired'
if ($paired) { Write-Host "配对成功 ✓" -ForegroundColor Green }
else {
    Write-Host "配对失败 ✗" -ForegroundColor Red
    Write-Host "最常见原因：配对弹窗已过期（它的有效期只有几十秒）。" -ForegroundColor Yellow
    Write-Host "处理：手机上重新点「使用配对码配对设备」，拿到新端口和新码后立刻重跑本脚本。" -ForegroundColor Yellow
}

Write-Host "`n=== 3. 连接 $Connect ===" -ForegroundColor Cyan
if ($Connect) {
    & $Adb connect $Connect
    Start-Sleep -Seconds 2
}

Write-Host "`n=== 4. 设备列表 ===" -ForegroundColor Cyan
& $Adb devices -l

$dev = (& $Adb devices) -join "`n"
if ($dev -match '\sdevice$' -or $dev -match '\sdevice\s') {
    Write-Host "`n连接就绪 ✓ 可以开始遥控调试了" -ForegroundColor Green
} elseif ($dev -match 'unauthorized') {
    Write-Host "`n设备显示 unauthorized：去看手机屏幕，点「允许 USB 调试」并勾选始终允许" -ForegroundColor Yellow
} else {
    Write-Host "`n尚未连上。若配对已成功，通常是连接端口变了 —— 回无线调试主页面确认端口后重跑本脚本。" -ForegroundColor Yellow
}
