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
    [string]$Sdk = 'D:\android-sdk_r24.4.1-windows\android-sdk-windows',
    [string]$Bt = '36.1.0',
    [string]$Plat = 'android-36',
    [string]$KeyStore = "$PSScriptRoot\draftguard-release.keystore",
    [string]$KeyAlias = 'draftguard',
    [string]$StorePass = 'draftguard',
    [switch]$NewKey
)

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot

# ---------------------------------------------------------------- 版本号
if (-not $Version) {
    $desc = (git -C $root describe --tags --always 2>$null)
    if ($desc -match '^v?(\d+\.\d+\.\d+)') { $Version = $Matches[1] }
    elseif ($desc) { $Version = "0.0.0-$desc" }
    else { $Version = '2.0.0' }
}
$versionCode = 1
if ($Version -match '^(\d+)\.(\d+)\.(\d+)') {
    $versionCode = [int]$Matches[1] * 10000 + [int]$Matches[2] * 100 + [int]$Matches[3]
}
Write-Host "版本号: $Version  (versionCode $versionCode)" -ForegroundColor Cyan

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
    -VersionCode $versionCode -VersionName $Version

if (-not (Test-Path "$root\$out")) { throw "构建失败，没有产物：$out" }

$size = [math]::Round((Get-Item "$root\$out").Length / 1KB, 1)
Write-Host ""
Write-Host "================ 可发布的 APK ================" -ForegroundColor Green
Write-Host "文件：$root\$out"
Write-Host "大小：$size KB"
Write-Host "版本：$Version (versionCode $versionCode)"
Write-Host ""
Write-Host "挂到 GitHub Release："
Write-Host "  git tag v$Version && git push origin v$Version"
Write-Host "（推 tag 会自动触发 CI 构建并创建 Release；"
Write-Host "  或者手动：gh release create v$Version `"$out`"）"
Write-Host "==============================================" -ForegroundColor Green
