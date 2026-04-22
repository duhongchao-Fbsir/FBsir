# Build Admin + dependencies (includes WxFbsir-business for skill preview). Use after Business changes.
# Engine is NOT in this reactor; use tools/compile-engine.ps1 for Engine.
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Find-Mvn {
    $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($env:MAVEN_HOME) {
        $p = Join-Path $env:MAVEN_HOME "bin/mvn.cmd"
        if (Test-Path $p) { return $p }
    }
    $bundled = Join-Path $repoRoot ".tools/maven-extract/apache-maven-3.9.9/bin/mvn.cmd"
    if (Test-Path $bundled) { return $bundled }
    $found = Get-ChildItem -Path $repoRoot -Filter "mvn.cmd" -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "apache-maven.*\\bin\\mvn\.cmd$" } |
        Select-Object -First 1 -ExpandProperty FullName
    return $found
}

$mvn = Find-Mvn
if (-not $mvn) {
    Write-Host "ERROR: mvn not found" -ForegroundColor Red
    exit 1
}
Write-Host "Using: $mvn" -ForegroundColor Cyan
& $mvn -q -pl WxFbsir-admin -am package -DskipTests
exit $LASTEXITCODE
