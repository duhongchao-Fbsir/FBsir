# 双包快速回归：MVP golden + BookWriter workbuddy（preview-only）。
# 用途：发布前/周检最小门禁，验证转换器未退化。
param(
    [int]$Rounds = 2
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$nodeExe = "C:\Program Files\nodejs\node.exe"
if (-not (Test-Path -LiteralPath $nodeExe)) {
    throw "未找到 Node: $nodeExe"
}

Write-Host "== smoke: MVP golden ==" -ForegroundColor Cyan
Remove-Item Env:EVOLUTION_ZIP -ErrorAction SilentlyContinue
Remove-Item Env:SOURCE_EXPORT_MODE -ErrorAction SilentlyContinue
Remove-Item Env:SOURCE_EXPORT_ROWS_FILE -ErrorAction SilentlyContinue
& $nodeExe "tools/run-evolution-rounds.mjs" --rounds $Rounds --cooldown-ms 300 --preview-only
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "== smoke: BookWriter workbuddy ==" -ForegroundColor Cyan
$env:EVOLUTION_ZIP = "fbs-bookwriter-workbuddy-latest.zip"
& $nodeExe "tools/run-evolution-rounds.mjs" --rounds $Rounds --cooldown-ms 300 --preview-only
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Smoke done. Latest summary: tools/out/evolution-latest.json" -ForegroundColor Green
