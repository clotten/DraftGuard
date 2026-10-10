# =====================================================================
#  release.ps1 — 打一个可以直接发布的 APK
#
#  跟 build-apk.ps1 的区别：
#    · 用专门的 release 密钥签名（不是调试密钥），装过一次之后能持续覆盖升级
#    · 自动从 git 取版本号，避免手写版本号写错
#    · 产物命名带版本，便于挂到 GitHub Release
#
#  用法：
#    .\release.ps1                    # 用 git describe 推断版本
#    .\release.ps1 -Version 2.1.0     # 指定版本
#    .\release.ps1 -NewKey            # 首次生成 release 密钥
# =====================================================================
param(
    [string]$Version,
    # SDK 路径留空即自动探测（环境变量 → 常见安装位置）。
    # 不要在这里写死本机路径：仓库是公开的，写死会泄露使用者的磁盘布局，
    # 也让别人 clone 下来跑不起来。
    [string]$Sdk  = '',
    [string]$Bt = '36.1.0',
    [string]$Plat = 'android-36',
    [string]$KeyStore = "$PSScriptRoot\draftguard-release.keystore",
    [string]$KeyAlias = 'draftguard',
    [string]$StorePass = '',   # 不设默认值：口令从环境变量 / keystore.properties / 交互输入取
    [switch]$NewKey
)

$ErrorActionPreference = 'Continue'

# ── 密钥库口令：不要写默认值 ──
# 这个文件是公开仓库的一部分，写死口令等于公开它。
# 取值顺序：环境变量 → keystore.properties（已 gitignore）→ 交互输入。
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $StorePass) {
    if ($env:DRAFTGUARD_STOREPASS) {
        $StorePass = $env:DRAFTGUARD_STOREPASS
    } else {
        $propFile = Join-Path $root 'keystore.properties'
        if (Test-Path $propFile) {
            foreach ($line in Get-Content $propFile) {
                if ($line -match '^\s*storePassword\s*=\s*(.+?)\s*$') { $StorePass = $Matches[1]; break }
            }
        }
    }
}
if (-not $StorePass) {
    try {
        $sec = Read-Host -Prompt '请输入密钥库口令' -AsSecureString
        $StorePass = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
            [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
    } catch {
        $StorePass = Read-Host -Prompt '请输入密钥库口令'
    }
}
if (-not $StorePass) { Write-Host '没有口令，无法签名' -ForegroundColor Red; exit 1 }

$root = $PSScriptRoot

# ---------------------------------------------------------------- 版本号
if (-not $Version) {
    $desc = (git -C $root describe --tags --always 2>$null)
    if ($desc -match '^v?(\d+\.\d+\.\d+)') { $Version = $Matches[1] }
    elseif ($desc) { $Version = "0.0.0-$desc" }
    else { $Version = '2.0.0' }
}
# versionCode 由 build-apk.ps1 按同一公式从版本号推算（major*10000 + minor*100 + patch），
# 保证"版本号变大 = versionCode 变大"，不会出现装了旧版覆盖不了新版的情况。
Write-Host "版本号: $Version" -ForegroundColor Cyan

# ---------------------------------------------------------------- 密钥
if ($NewKey -or -not (Test-Path $KeyStore)) {
    Write-Host "生成 release 签名密钥: $KeyStore" -ForegroundColor Yellow
    Write-Host "  ⚠ 这个文件和口令要自己保管好：弄丢了就无法覆盖升级已装的版本" -ForegroundColor Yellow
    & keytool -genkeypair -keystore $KeyStore -alias $KeyAlias `
        -keyalg RSA -keysize 2048 -validity 10950 `
        -storepass $StorePass -keypass $StorePass `
        -dname "CN=DraftGuard, OU=Release, O=DraftGuard, C=CN" 2>&1 | Out-Null
    if (-not (Test-Path $KeyStore)) { throw '密钥生成失败' }
}

# ---------------------------------------------------------------- 构建
$out = "DraftGuard-$Version.apk"
& "$root\build-apk.ps1" `
    -Sdk $Sdk -Bt $Bt -Plat $Plat `
    -OutName $out `
    -KeyStore $KeyStore -KeyAlias $KeyAlias -StorePass $StorePass `
    -VersionName $Version

if (-not (Test-Path "$root\$out")) { throw "构建失败，没有产物：$out" }

$size = [math]::Round((Get-Item "$root\$out").Length / 1KB, 1)
Write-Host ""
Write-Host "================ 可发布的 APK ================" -ForegroundColor Green
Write-Host "文件：$root\$out"
Write-Host "大小：$size KB"
Write-Host "版本：$Version"
Write-Host ""
Write-Host "发布包已就绪（本仓库只保存代码，APK 不入库）"
Write-Host "  需要分发时把 $out 传到网盘或自己的下载渠道即可"
Write-Host "  仓库只保存源码：CI 仅做测试与编译检查，不产出安装包"
Write-Host "  版本号规则：版本号变了才能覆盖升级，改代码不升版本号会导致装不上"
Write-Host "==============================================" -ForegroundColor Green
