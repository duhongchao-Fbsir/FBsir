# H2 L-rel：预检 → 打包 golden-h2 → 冻结行集 IMPORT 验收
param(
    [switch]$SkipPack,
    [switch]$SkipPreflight
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if (-not $SkipPreflight) {
    & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'preflight-evolution.ps1') -StrictImport
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if (-not $SkipPack) {
    & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'pack-skill-mvp-golden-h2.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Remove-Item Env:SOURCE_EXPORT_MODE -ErrorAction SilentlyContinue
Remove-Item Env:SOURCE_EXPORT_ROWS_FILE -ErrorAction SilentlyContinue

$nodeExe = "C:\Program Files\nodejs\node.exe"
if (-not (Test-Path -LiteralPath $nodeExe)) {
    $nodeExe = "node.exe"
}

& $nodeExe (Join-Path $PSScriptRoot 'h2-l-rel-exit-gate.mjs')
exit $LASTEXITCODE
