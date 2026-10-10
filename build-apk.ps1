# =====================================================================
#  DraftGuard · 一键构建 APK
#  只用 Android SDK 自带工具，不需要 Gradle、不需要联网下载依赖：
#    aapt2 编译资源 → javac 编译 Java → d8 转 dex → 打包 → 对齐 → 签名
#  改了代码后重新跑一遍这个脚本即可出新包。
# =====================================================================
param(
    # SDK 路径留空即自动探测（环境变量 → 常见安装位置）。
    # 不要在这里写死本机路径：仓库是公开的，写死会泄露使用者的磁盘布局，
    # 也让别人 clone 下来跑不起来。
    [string]$Sdk  = '',
    [string]$Bt   = '36.1.0',
    [string]$Plat = 'android-36',
    [string]$OutName = 'DraftGuard-2.0.0.apk',
    # 签名相关：默认用仓库里的调试密钥；发布时由 release.ps1 传入正式密钥
    [string]$KeyStore = '',
    [string]$KeyAlias = 'typelog',
    [string]$StorePass = '',   # 不设默认值：口令从环境变量 / keystore.properties / 交互输入取
    # -1 表示"从 VersionName 自动推算"：major*10000 + minor*100 + patch
    [string]$VersionName = '2.0.0',
    # -1 = 从 VersionName 自动推算 versionCode（major*10000 + minor*100 + patch），避免两处规则打架
    [int]$VersionCode = -1
)

# 注意：不能用 $ErrorActionPreference='Stop'。PowerShell 5.1 会把原生命令写到 stderr 的
# 正常输出（keytool/aapt2 都会写）当成异常抛出，导致明明成功却中断。这里统一靠退出码判断。
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

$root      = Split-Path -Parent $MyInvocation.MyCommand.Path
$appDir    = Join-Path $root 'android'
$build     = Join-Path $root 'build'

# ── SDK 路径探测（必须在计算工具路径之前）──
# 不要在参数默认值里写死本机路径：仓库是公开的，写死既泄露使用者的磁盘布局，
# 也让别人 clone 下来直接跑不起来。
if ([string]::IsNullOrWhiteSpace($Sdk)) {
    foreach ($cand in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
                        "$env:LOCALAPPDATA\Android\Sdk",
                        'C:\Android\Sdk', 'D:\Android\Sdk')) {
        if ($cand -and (Test-Path (Join-Path $cand 'platforms'))) { $Sdk = $cand; break }
    }
}
if ([string]::IsNullOrWhiteSpace($Sdk) -or -not (Test-Path $Sdk)) {
    Write-Host '找不到 Android SDK：请用 -Sdk 参数指定，或设置 ANDROID_HOME 环境变量' -ForegroundColor Red
    exit 1
}

$btDir     = Join-Path $Sdk "build-tools\$Bt"
$androidJar= Join-Path $Sdk "platforms\$Plat\android.jar"

$Aapt2     = Join-Path $btDir 'aapt2.exe'
$D8        = Join-Path $btDir 'd8.bat'
$ZipAlign  = Join-Path $btDir 'zipalign.exe'
$ApkSigner = Join-Path $btDir 'apksigner.bat'
$Javac     = (Get-Command javac).Source
$KeyTool   = (Get-Command keytool).Source
$Jar       = (Get-Command jar).Source

$d8Jar = Get-ChildItem (Join-Path $btDir 'lib') -Filter 'd8.jar' -ErrorAction SilentlyContinue |
         Select-Object -First 1 -ExpandProperty FullName
if (-not $d8Jar) { $d8Jar = Join-Path $btDir 'lib\d8.jar' }
# lambda 表达式的引导方法不在 android.jar 里，必须靠这个 stub 才能编译（d8 再负责脱糖）
$lambdaStubs = Join-Path $btDir 'core-lambda-stubs.jar'
if (-not (Test-Path $lambdaStubs)) { throw "缺少 core-lambda-stubs.jar：$lambdaStubs" }

foreach ($tool in @($Aapt2, $androidJar, $D8, $ZipAlign, $ApkSigner, $Javac, $KeyTool)) {
    if (-not (Test-Path $tool)) { throw "缺少构建工具：$tool" }
}

function Step($msg) { Write-Host "`n>>> $msg" -ForegroundColor Cyan }
function Invoke-Tool($file, [string[]]$argv, $errFile) {
    & $file @argv 2>$errFile
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        if (Test-Path $errFile) { Get-Content $errFile | Select-Object -Last 40 | ForEach-Object { Write-Host $_ -ForegroundColor Red } }
        throw "$([IO.Path]::GetFileName($file)) 失败，退出码 $code"
    }
}

# versionCode：未显式指定时按 VersionName 推算，保证"版本号变大 = versionCode 变大"
if ($VersionCode -lt 0) {
    if ($VersionName -match '^(\d+)\.(\d+)\.(\d+)') {
        $VersionCode = [int]$Matches[1] * 10000 + [int]$Matches[2] * 100 + [int]$Matches[3]
    } else {
        $VersionCode = 1
    }
}
Write-Host "版本：$VersionName (versionCode $VersionCode)" -ForegroundColor Cyan
# ---------------------------------------------------------------- 准备目录
Step '准备构建目录'
Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
foreach ($d in 'compiled-res', 'gen', 'classes', 'dex') {
    New-Item -ItemType Directory -Force -Path (Join-Path $build $d) | Out-Null
}
$errLog   = Join-Path $build 'tool.err'

# ---------------------------------------------------------------- 1. 资源
Step 'aapt2 compile：编译资源'
$resOut = Join-Path $build 'compiled-res\res.zip'
Invoke-Tool $Aapt2 @('compile', '--dir', (Join-Path $appDir 'res'), '-o', $resOut) $errLog

Step 'aapt2 link：生成资源表与基础 APK'
$baseApk = Join-Path $build 'base.apk'
$genDir  = Join-Path $build 'gen'

# 版本号必须写进清单再用 aapt2 link。
#
# 踩过的坑：本机 aapt2（build-tools 36.1.0）**静默忽略** --version-code / --version-name，
# 传了也不报错，产出的 APK 一直是清单里写死的 1 / 2.0.0。
# 结果：所有版本的 versionCode 都是 1，升级时系统看到"版本没变"，
# 也让人误以为装的是最新版（实测排查花了很久才发现）。
# 所以改成：把清单里的版本号替换后链接，并在最后校验产物里的实际版本。
$manifestSrc = Join-Path $appDir 'AndroidManifest.xml'
$manifestTmp = Join-Path $build 'AndroidManifest.build.xml'
$manifestText = [IO.File]::ReadAllText($manifestSrc, [Text.Encoding]::UTF8)
$manifestText = [regex]::Replace($manifestText,
    'android:versionCode="\d+"', "android:versionCode=`"$VersionCode`"")
$manifestText = [regex]::Replace($manifestText,
    'android:versionName="[^"]*"', "android:versionName=`"$VersionName`"")
[IO.File]::WriteAllText($manifestTmp, $manifestText, (New-Object Text.UTF8Encoding($false)))

Invoke-Tool $Aapt2 @(
    'link',
    '-o', $baseApk,
    '-I', $androidJar,
    '--manifest', $manifestTmp,
    '--java', $genDir,
    '--min-sdk-version', '26',
    '--target-sdk-version', '33',
    '--version-code', "$VersionCode",
    '--version-name', "$VersionName",
    '--auto-add-overlay',
    $resOut
) $errLog

# 校验：确认版本真的写进去了（防止又静默失效）
$badging = & $Aapt2 dump badging $baseApk 2>&1 | Select-String -Pattern '^package:' | Select-Object -First 1
if ($badging -and $badging.Line -notmatch "versionCode='$VersionCode'") {
    throw "版本号未写入 APK（期望 $VersionCode）：$($badging.Line)"
}

# ---------------------------------------------------------------- 2. Java → class
Step 'javac：编译 Java 源码'
$srcList = Join-Path $build 'sources.txt'
$sources = @()
$sources += Get-ChildItem -Recurse (Join-Path $appDir 'java') -Filter '*.java' | ForEach-Object { $_.FullName }
$sources += Get-ChildItem -Recurse $genDir -Filter '*.java' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
if (-not $sources) { throw '没有找到任何 .java 源文件' }
# 注意：必须无 BOM，否则 javac 会把 BOM 当成文件名的一部分（"无效文件名"）
[IO.File]::WriteAllLines($srcList, $sources, (New-Object System.Text.UTF8Encoding($false)))

$classesDir = Join-Path $build 'classes'
Invoke-Tool $Javac @(
    '-encoding', 'UTF-8',
    '-source', '8', '-target', '8',
    '-bootclasspath', "$androidJar;$lambdaStubs",
    '-classpath', $androidJar,
    '-nowarn',
    '-d', $classesDir,
    "@$srcList"
) $errLog

# ---------------------------------------------------------------- 3. class → dex
Step 'd8：转换 dex（用 android.jar 做脱糖，避免跑 dx）'
$dexDir = Join-Path $build 'dex'
# d8 要求输出必须是"已存在的目录"或 .jar/.zip，所以这里先建好
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null
$classJar = Join-Path $build 'classes.jar'
Push-Location $classesDir
& $Jar cf $classJar .
Pop-Location
if ($LASTEXITCODE -ne 0) { throw 'jar 打包 class 失败' }
& $D8 --min-api 26 --lib $androidJar --output $dexDir $classJar 2>$errLog
if ($LASTEXITCODE -ne 0) {
    if (Test-Path $errLog) { Get-Content $errLog | Select-Object -Last 40 | ForEach-Object { Write-Host $_ -ForegroundColor Red } }
    throw 'd8 转换 dex 失败'
}
$dexFiles = Get-ChildItem $dexDir -Filter '*.dex'
if (-not $dexFiles) { throw "没有生成 dex 文件" }
Write-Host ("    生成 {0} 个 dex：{1}" -f $dexFiles.Count, (($dexFiles | Select-Object -ExpandProperty Name) -join ', '))

# ---------------------------------------------------------------- 4. 组装 APK
Step '组装 APK（把 dex 塞进基础包）'
$unsig = Join-Path $build 'unsigned.apk'
Copy-Item $baseApk $unsig -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$zip = [System.IO.Compression.ZipFile]::Open($unsig, [System.IO.Compression.ZipArchiveMode]::Update)
foreach ($d in $dexFiles) {
    $entry = $zip.CreateEntry($d.Name, [System.IO.Compression.CompressionLevel]::Optimal)
    $es = $entry.Open()
    $bytes = [IO.File]::ReadAllBytes($d.FullName)
    $es.Write($bytes, 0, $bytes.Length)
    $es.Dispose()
}
$zip.Dispose()

# ---------------------------------------------------------------- 5. 对齐
Step 'zipalign：4 字节对齐'
$aligned = Join-Path $build 'aligned.apk'
Invoke-Tool $ZipAlign @('-f', '-p', '4', $unsig, $aligned) $errLog

# ---------------------------------------------------------------- 6. 签名
if ([string]::IsNullOrWhiteSpace($KeyStore)) {
    $keystore = Join-Path $root 'debug.keystore'
} else {
    $keystore = $KeyStore
}
if (-not (Test-Path $keystore)) {
    Step "生成签名密钥：$keystore"
    Invoke-Tool $KeyTool @(
        '-genkeypair', '-keystore', $keystore,
        '-alias', $KeyAlias, '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10950',
        '-storepass', $StorePass, '-keypass', $StorePass,
        '-dname', "CN=DraftGuard, OU=local, O=local, L=local, ST=local, C=CN"
    ) $errLog
}
Step 'apksigner：签名（v1+v2，兼容老新安卓）'
$final = Join-Path $root $OutName
if (Test-Path $final) { Remove-Item $final -Force }
Invoke-Tool $ApkSigner @(
    'sign',
    '--ks', $keystore,
    '--ks-pass', "pass:$StorePass",
    '--key-pass', "pass:$StorePass",
    '--ks-key-alias', $KeyAlias,
    '--v1-signing-enabled', 'true',
    '--v2-signing-enabled', 'true',
    '--out', $final,
    $aligned
) $errLog

Step '校验签名与包信息'
& $ApkSigner verify --print-certs $final 2>$errLog | Select-Object -First 4
$aapt = Join-Path $btDir 'aapt2.exe'
& $aapt dump badging $final 2>$null | Select-String -Pattern 'package:|application-label:|sdkVersion|targetSdkVersion|uses-permission' | Select-Object -First 8

$size = [math]::Round((Get-Item $final).Length / 1KB, 1)
Write-Host "`n================ 构建成功 ================" -ForegroundColor Green
Write-Host "APK：$final"
Write-Host "大小：$size KB　版本：$VersionName ($VersionCode)"
Write-Host "签名密钥：$keystore"
Write-Host "==========================================" -ForegroundColor Green
