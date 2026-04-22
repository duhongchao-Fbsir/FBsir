# R4 一键：失败聚类报表 + 归档当前 tools/out 快照（路线图 §3.5）。
param(
    [switch]$SkipCluster,
    [switch]$SkipArchive
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$nodeExe = "C:\Program Files\nodejs\node.exe"
if (-not (Test-Path -LiteralPath $nodeExe)) {
    $nodeExe = 'node.exe'
}

if (-not $SkipCluster) {
    & $nodeExe 'tools/evolution-failure-cluster.mjs'
}

if (-not $SkipArchive) {
    & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'archive-evolution-run.ps1')
}

exit 0
