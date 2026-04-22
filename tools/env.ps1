# 在 PowerShell 中执行: . <仓库>\tools\env.ps1
# 为当前会话设置 JDK 17，并用 Resolve-Maven.ps1 解析 Maven（PATH / MAVEN_HOME / 仓库 .tools 等）
# MySQL（本机开发数据目录）可先执行: <仓库>\tools\start-mysql.ps1
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot"
if (Test-Path -LiteralPath $env:JAVA_HOME) {
    $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
}
. (Join-Path $PSScriptRoot 'Resolve-Maven.ps1')
$mvnPath = Get-MavenExecutable -RepoRoot $repoRoot
if ($mvnPath) {
    $mvnBin = Split-Path -Parent $mvnPath
    $env:MAVEN_HOME = Split-Path -Parent $mvnBin
    $env:Path = "$mvnBin;" + $env:Path
}
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "MAVEN_HOME=$env:MAVEN_HOME"
java -version 2>&1
if ($mvnPath) {
    & $mvnPath -version
} else {
    Write-Warning "未解析到 Maven：请安装 Maven、配置 MAVEN_HOME，或将便携包放入 .tools\maven-extract\"
}
