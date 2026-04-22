param(
    [string]$SourceDir = "",
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

if (-not $SourceDir) {
    $SourceDir = Join-Path $repoRoot "docs\workflows\skill-mvp-golden-h2"
}
if (-not $OutDir) {
    $OutDir = Join-Path $repoRoot "tools\out"
}

if (-not (Test-Path -LiteralPath $SourceDir)) {
    throw "SourceDir 不存在: $SourceDir"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$zipPath = Join-Path $OutDir "skill-mvp-golden-h2.zip"
if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}

Compress-Archive -Path (Join-Path $SourceDir "*") -DestinationPath $zipPath -Force
Write-Host "已生成: $zipPath"
