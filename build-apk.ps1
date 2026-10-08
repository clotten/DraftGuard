# =====================================================================
#  DraftGuard · 一键构建 APK
#  只用 Android SDK 自带工具，不需要 Gradle、不需要联网下载依赖：
#    aapt2 编译资源 → javac 编译 Java → d8 转 dex → 打包 → 对齐 → 签名
#  改了代码后重新跑一遍这个脚本即可出新包。
# =====================================================================
param(
    [string]$Sdk  = 'D:\android-sdk_r24.4.1-windows\android-sdk-windows',
    [string]$Bt   = '36.1.0',
    [string]$Plat = 'android-36',
    [string]$OutName = 'DraftGuard-2.0.0.apk'
)

# 注意：不能用 $ErrorActionPreference='Stop'。PowerShell 5.1 会把原生命令写到 stderr 的
# 正常输出（keytool/aapt2 都会写）当成异常抛出，导致明明成功却中断。这里统一靠退出码判断。
$ErrorActionPreference = 'Continue'
$root      = Split-Path -Parent $MyInvocation.MyCommand.Path
$appDir    = Join-Path $root 'android'
$build     = Join-Path $root 'build'
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

# ---------------------------------------------------------------- 准备目录
Step '准备构建目录'
Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
foreach ($d in 'compiled-res', 'gen', 'classes', 'dex') {
    New-Item -ItemType Directory -Force -Path (Join-Path $build $d) | Out-Null
}
$keystore = Join-Path $root 'debug.keystore'
$errLog   = Join-Path $build 'tool.err'

# ---------------------------------------------------------------- 1. 资源
Step 'aapt2 compile：编译资源'
$resOut = Join-Path $build 'compiled-res\res.zip'
Invoke-Tool $Aapt2 @('compile', '--dir', (Join-Path $appDir 'res'), '-o', $resOut) $errLog

Step 'aapt2 link：生成资源表与基础 APK'
$baseApk = Join-Path $build 'base.apk'
$genDir  = Join-Path $build 'gen'
Invoke-Tool $Aapt2 @(
    'link',
    '-o', $baseApk,
    '-I', $androidJar,
    '--manifest', (Join-Path $appDir 'AndroidManifest.xml'),
    '--java', $genDir,
    '--min-sdk-version', '26',
    '--target-sdk-version', '33',
    '--version-code', '1',
    '--version-name', '2.0.0',
    '--auto-add-overlay',
    $resOut
) $errLog

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
if (-not (Test-Path $keystore)) {
    Step '生成签名密钥（仅本机使用）'
    Invoke-Tool $KeyTool @(
        '-genkeypair', '-keystore', $keystore,
        '-alias', 'typelog', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10950',
        '-storepass', 'typelog123', '-keypass', 'typelog123',
        '-dname', 'CN=typelog, OU=local, O=local, L=local, ST=local, C=CN'
    ) $errLog
}
Step 'apksigner：签名（v1+v2，兼容老新安卓）'
$final = Join-Path $root $OutName
if (Test-Path $final) { Remove-Item $final -Force }
Invoke-Tool $ApkSigner @(
    'sign',
    '--ks', $keystore,
    '--ks-pass', 'pass:typelog123',
    '--key-pass', 'pass:typelog123',
    '--ks-key-alias', 'typelog',
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
Write-Host "大小：$size KB"
Write-Host "签名密钥：$keystore（口令 typelog123，仅本机调试用）"
Write-Host "==========================================" -ForegroundColor Green
