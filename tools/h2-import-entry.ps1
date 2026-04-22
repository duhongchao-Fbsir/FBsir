# H2 全链 IMPORT 追踪：预检 → 打 skill-mvp-golden-h2.zip → h2-import-track（draft 对照 + 可选 R3）
param(
    [int]$ImportRounds = 2,
    [int]$GateFailedMax = -1,
    [switch]$RequireVerifyPass,
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

$args = @(
    "tools/h2-import-track.mjs",
    "--rounds", "$ImportRounds",
    "--cooldown-ms", "20000"
)
if ($GateFailedMax -ge 0) {
    $args += @("--gate-failed-max", "$GateFailedMax")
}
if ($RequireVerifyPass) {
    $args += "--require-verify-pass"
}

& $nodeExe @args
exit $LASTEXITCODE
