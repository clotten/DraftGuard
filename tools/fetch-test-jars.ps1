# fetch-test-jars.ps1 —— 下载存储层测试需要的 jar
#
# 为什么需要它：android.database.sqlite 在 android.jar 里只有空桩
# （调用会抛 RuntimeException: Stub!），所以存储层测试改用 JDBC，
# 引擎还是同一个 SQLite。
#
# jar 不进仓库（build/ 已在 .gitignore 里），这里只记录"下载什么、下到哪"。

$ErrorActionPreference = "Stop"

$Jars = Join-Path $PSScriptRoot "..\.cache\jars"
New-Item -ItemType Directory -Force -Path $Jars | Out-Null

$Base = "https://repo1.maven.org/maven2"
$Items = @(
    @{ Url = "$Base/org/xerial/sqlite-jdbc/3.46.0.0/sqlite-jdbc-3.46.0.0.jar"; File = "sqlite-jdbc.jar" },
    @{ Url = "$Base/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar";            File = "slf4j-api.jar" },
    @{ Url = "$Base/org/slf4j/slf4j-nop/2.0.13/slf4j-nop-2.0.13.jar";            File = "slf4j-nop.jar" }
)

foreach ($it in $Items) {
    $dst = Join-Path $Jars $it.File
    if (Test-Path $dst) {
        Write-Host ("  已存在 " + $it.File)
        continue
    }
    Write-Host ("  下载 " + $it.File + " ...")
    Invoke-WebRequest $it.Url -OutFile $dst -UseBasicParsing -TimeoutSec 180 `
        -Headers @{ "User-Agent" = "Mozilla/5.0" }
    $kb = [math]::Round((Get-Item $dst).Length / 1KB, 0)
    Write-Host ("  完成 " + $it.File + " (" + $kb + " KB)")
}

Write-Host ""
Write-Host "jar 目录：$Jars"
