# L-rel：预检 → 清除串味变量 → MVP golden 一轮 IMPORT（冻结 sourceExportRows 文件）
# 路线图 §6.5；与 Wave2 draft 对照分工：发布前跑本脚本而非仅 SOURCE_EXPORT_MODE=draft。
param(
    [switch]$SkipPreflight
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$nodeExe = "C:\Program Files\nodejs\node.exe"
if (-not (Test-Path -LiteralPath $nodeExe)) {
    $nodeExe = "node.exe"
}

if (-not $SkipPreflight) {
    & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'preflight-evolution.ps1') -StrictImport
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Remove-Item Env:EVOLUTION_ZIP -ErrorAction SilentlyContinue
Remove-Item Env:SOURCE_EXPORT_MODE -ErrorAction SilentlyContinue
Remove-Item Env:SOURCE_EXPORT_ROWS_FILE -ErrorAction SilentlyContinue

& $nodeExe (Join-Path $PSScriptRoot 'l-rel-exit-gate.mjs')
exit $LASTEXITCODE
