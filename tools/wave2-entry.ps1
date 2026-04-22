# Wave2 入口：预检 → 清除串味变量 → MVP golden 多轮 IMPORT+draft 追踪（wave2-import-track）
# Wave1 已通过后方可作为「下一波落地」的日常入口。
# 全链 R2+R3 收紧：-RequireVerifyPass（每轮 verifyReport.pass 必为 true，失败则 exit 1）
# 仅双包 R-PR/R-Week：-SkipImportTrack（走 wave2-smoke = 预检 + governance preview-only）
# 发布对照（L-rel）：另走 tools/l-rel-entry.ps1（冻结 sourceExportRows，非 draft 推导）
# 第二套 scene（H2）：tools/h2-entry.ps1（仅 preview）；全链 tools/h2-import-entry.ps1；H2 L-rel：tools/h2-l-rel-entry.ps1
param(
    [int]$ImportRounds = 3,
    [int]$GateFailedMax = -1,
    [switch]$SkipImportTrack,
    [switch]$RequireVerifyPass
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$nodeExe = "C:\Program Files\nodejs\node.exe"
if (-not (Test-Path -LiteralPath $nodeExe)) {
    $nodeExe = "node.exe"
}

& powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'preflight-evolution.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Remove-Item Env:EVOLUTION_ZIP -ErrorAction SilentlyContinue
Remove-Item Env:SOURCE_EXPORT_MODE -ErrorAction SilentlyContinue
Remove-Item Env:SOURCE_EXPORT_ROWS_FILE -ErrorAction SilentlyContinue

if ($SkipImportTrack) {
    Write-Host 'SkipImportTrack: run wave2-smoke only' -ForegroundColor Cyan
    & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'wave2-smoke.ps1') -Rounds 2
    exit $LASTEXITCODE
}

$args = @(
    "tools/wave2-import-track.mjs",
    "--rounds", "$ImportRounds",
    "--cooldown-ms", "500"
)
if ($GateFailedMax -ge 0) {
    $args += @("--gate-failed-max", "$GateFailedMax")
}
if ($RequireVerifyPass) {
    $args += "--require-verify-pass"
}

& $nodeExe @args
exit $LASTEXITCODE
