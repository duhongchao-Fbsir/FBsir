# H2：预检 → pack skill-mvp-golden-h2 → preview-only 门禁（第二套 scene-pack）
param(
    [switch]$SkipPack,
    [switch]$SkipPreflight
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if (-not $SkipPreflight) {
    & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'preflight-evolution.ps1')
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

& $nodeExe (Join-Path $PSScriptRoot 'h2-exit-gate.mjs')
exit $LASTEXITCODE
