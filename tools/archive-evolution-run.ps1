# 将当前 tools/out 下跑批产物归档到 tools/out/archive/<时间戳>/，便于 CI 或发布前留痕（R4）。
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$out = Join-Path $RepoRoot 'tools\out'
if (-not (Test-Path -LiteralPath $out)) {
    Write-Error "Missing $out"
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dest = Join-Path $out "archive\$stamp"
New-Item -ItemType Directory -Path $dest -Force | Out-Null

$patterns = @(
    'evolution-latest.json',
    'evolution-failure-cluster-latest.json',
    'wave2-import-track-latest.json',
    'l-rel-*.json'
)
foreach ($p in $patterns) {
    Get-ChildItem -Path $out -Filter $p -ErrorAction SilentlyContinue | Copy-Item -Destination $dest -Force -ErrorAction SilentlyContinue
}
Get-ChildItem -Path $out -Filter 'evolution-r*-import.json' -ErrorAction SilentlyContinue | Copy-Item -Destination $dest -Force
Get-ChildItem -Path $out -Filter 'evolution-summary-*.json' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 5 |
    Copy-Item -Destination $dest -Force

Write-Host "Archived to $dest" -ForegroundColor Green
exit 0
