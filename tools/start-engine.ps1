# 启动 WxFbsir Engine（需先启动 Admin :8080，且 ws_host_whitelist 中存在与 application.yml 相同的 host-id）
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $root) { $root = "D:\u3wv2" }
$jar = Join-Path $root "WxFbsir-engine\target\wxfbsir-engine-1.3.1.jar"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot"
if (-not (Test-Path $env:JAVA_HOME)) {
    Write-Error "未找到 JAVA_HOME: $env:JAVA_HOME"
    exit 1
}
if (-not (Test-Path $jar)) {
    Write-Error "未找到 Engine JAR: $jar 。请在 WxFbsir-engine 目录执行: mvn clean package -DskipTests"
    exit 1
}
$engineDir = Join-Path $root "WxFbsir-engine"
Set-Location $engineDir
Write-Host "工作目录: $engineDir"
Write-Host "启动: $jar"
& "$env:JAVA_HOME\bin\java.exe" -jar $jar
