# Resolve Maven (PATH / MAVEN_HOME / repo .tools bundle) and build WxFbsir-engine.
# Default: mvn package (产出可 java -jar 的 JAR)；快速语法检查可加 -CompileOnly。
param(
    [switch]$CompileOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Find-Mvn {
    $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($env:MAVEN_HOME) {
        $p = Join-Path $env:MAVEN_HOME 'bin/mvn.cmd'
        if (Test-Path $p) { return $p }
    }
    $bundled = Join-Path $repoRoot '.tools/maven-extract/apache-maven-3.9.9/bin/mvn.cmd'
    if (Test-Path $bundled) { return $bundled }
    Get-ChildItem -Path $repoRoot -Filter 'mvn.cmd' -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match 'apache-maven.*\\bin\\mvn\.cmd$' } |
        Select-Object -First 1 -ExpandProperty FullName
}

$mvn = Find-Mvn
if (-not $mvn) {
    Write-Host 'ERROR: mvn not found. Install Maven or set MAVEN_HOME.' -ForegroundColor Red
    exit 1
}

Write-Host "Using: $mvn" -ForegroundColor Cyan
$engineDir = Join-Path $repoRoot 'WxFbsir-engine'
Set-Location $engineDir
if ($CompileOnly) {
    & $mvn -q compile -DskipTests
} else {
    & $mvn -q package -DskipTests
}
exit $LASTEXITCODE
