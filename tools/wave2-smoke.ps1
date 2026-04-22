# Wave2 日常冒烟：预检 → 双包 preview-only（路线图 §6.1 R-PR + R-Week）
param(
    [int]$Rounds = 2
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

& powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'preflight-evolution.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'run-governance-smoke.ps1') -Rounds $Rounds
exit $LASTEXITCODE
