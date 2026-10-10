# =====================================================================
#  pair.ps1 — 可靠的 adb 无线调试配对
#
#  为什么不用 `"配对码" | adb pair`：PowerShell 管道传 stdin 时不会正确关闭，
#  adb 收不到 EOF 会报 "protocol fault (couldn't read status message)"。
#  这里用 .NET Process 精确控制 stdin，写入配对码后关闭流。
#
#  用法：
#    .\pair.ps1 -Target <手机IP>:33969 -Code 068829
#    .\pair.ps1 -Target <手机IP>:33969 -Code 068829 -Connect <手机IP>:37117
# =====================================================================
param(
    [Parameter(Mandatory = $true)][string]$Target,   # 配对用 IP:端口（弹窗里的那个）
    [Parameter(Mandatory = $true)][string]$Code,     # 6 位配对码
    [string]$Connect,                                # 连接用 IP:端口（无线调试主页面那个）
    # adb 路径自动探测：优先环境变量，再退回常见安装位置（不要写死本机路径）
[string]$Adb = ''
if (-not $Adb) {
    if ($env:ANDROID_HOME) { $Adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }
    elseif ($env:ANDROID_SDK_ROOT) { $Adb = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe' }
    else {
        foreach ($c in @("$env:LOCALAPPDATA\Android\Sdk", 'C:\Android\Sdk', 'D:\Android\Sdk')) {
            if (Test-Path (Join-Path $c 'platform-tools\adb.exe')) {
                $Adb = Join-Path $c 'platform-tools\adb.exe'; break
            }
        }
    }
}
if (-not $Adb -or -not (Test-Path $Adb)) {
    Write-Host '找不到 adb.exe，请用 -Adb 参数指定，或设置 ANDROID_HOME 环境变量' -ForegroundColor Red
    exit 1
}
)

$ErrorActionPreference = 'Continue'
if (-not (Test-Path $Adb)) { throw "找不到 adb：$Adb" }

function Invoke-AdbWithStdin {
    param([string[]]$Args, [string]$StdinText)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    foreach ($a in $Args) { $psi.ArgumentList.Add($a) }
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $p = [System.Diagnostics.Process]::Start($psi)
    # 写入配对码并立刻关闭 stdin —— 这一步是 adb pair 能成功的关键
    $p.StandardInput.WriteLine($StdinText)
    $p.StandardInput.Close()
    $out = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit(30000) | Out-Null
    [pscustomobject]@{ Exit = $p.ExitCode; Out = $out.Trim(); Err = $err.Trim() }
}

Write-Host "=== 1. 配对 $Target ===" -ForegroundColor Cyan
$r = Invoke-AdbWithStdin -Args @('pair', $Target) -StdinText $Code
if ($r.Out) { Write-Host $r.Out }
if ($r.Err) { Write-Host $r.Err -ForegroundColor Yellow }

$paired = ($r.Out + $r.Err) -match 'Successfully paired'
if ($paired) {
    Write-Host "配对成功" -ForegroundColor Green
} else {
    Write-Host "配对未成功（常见原因：配对窗口已过期，需要重新点开生成新码）" -ForegroundColor Red
}

Write-Host "`n=== 2. 已配对设备 ===" -ForegroundColor Cyan
& $Adb devices -l

if ($Connect) {
    Write-Host "`n=== 3. 连接 $Connect ===" -ForegroundColor Cyan
    & $Adb connect $Connect
    Start-Sleep -Milliseconds 800
    Write-Host "`n=== 4. 最终设备列表 ===" -ForegroundColor Cyan
    & $Adb devices -l
}
