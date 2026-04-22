# 演化门禁一条龙：按 Tier 串联预检与各阶段出口（路线图 §6 落地）。
# 用法（仓库根）:
#   powershell -File tools/full-regression.ps1                    # Tier=standard
#   powershell -File tools/full-regression.ps1 -Tier smoke       # 仅双包 preview
#   powershell -File tools/full-regression.ps1 -Tier heavy       # 含 H2 全链 + L-rel(H2)；耗时长
#   powershell -File tools/full-regression.ps1 -SkipRestart      # 不重启 Admin/Engine
param(
    [ValidateSet('smoke', 'standard', 'heavy')]
    [string]$Tier = 'standard',
    [switch]$SkipRestart,
    [int]$SleepAfterRestart = 55,
    [switch]$IncludeR4Snapshot
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

Remove-Item Env:EVOLUTION_ZIP -ErrorAction SilentlyContinue
Remove-Item Env:SOURCE_EXPORT_MODE -ErrorAction SilentlyContinue
Remove-Item Env:SOURCE_EXPORT_ROWS_FILE -ErrorAction SilentlyContinue

$nodeExe = "C:\Program Files\nodejs\node.exe"
if (-not (Test-Path -LiteralPath $nodeExe)) {
    $nodeExe = "node.exe"
}

function Step-Run {
    param([string]$Title, [scriptblock]$Action)
    Write-Host "=== $Title ===" -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) {
        Write-Host "FAIL at: $Title (exit $LASTEXITCODE)" -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

if (-not $SkipRestart) {
    Step-Run 'restart-backend (SkipBuild)' {
        & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'restart-backend.ps1') -SkipBuild
    }
    Write-Host "Sleep ${SleepAfterRestart}s for Engine WS..." -ForegroundColor DarkYellow
    Start-Sleep -Seconds $SleepAfterRestart
}

Step-Run 'preflight-evolution (-StrictImport)' {
    & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'preflight-evolution.ps1') -StrictImport
}

if ($Tier -eq 'smoke') {
    Step-Run 'wave2-smoke (governance preview-only)' {
        & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'wave2-smoke.ps1') -Rounds 2
    }
    Write-Host 'OK: full-regression smoke done.' -ForegroundColor Green
    exit 0
}

Step-Run 'Wave1 exit gate' {
    & $nodeExe (Join-Path $repoRoot 'tools/wave1-exit-gate.mjs')
}

# Wave2：draft 多轮 IMPORT；不必要求 verifyPass（scene 别名与导出贪心对齐可能部分 false-positive）。
# R3 verifyPass=true 由下一步 L-rel（冻结 sourceExportRows）把关。
Step-Run 'Wave2 import-track (draft, 2 rounds)' {
    & $nodeExe @(
        (Join-Path $repoRoot 'tools/wave2-import-track.mjs'),
        '--rounds', '2',
        '--cooldown-ms', '500'
    )
}

Step-Run 'L-rel (golden + frozen rows)' {
    & $nodeExe (Join-Path $repoRoot 'tools/l-rel-exit-gate.mjs')
}

Step-Run 'pack skill-mvp-golden-h2' {
    & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'pack-skill-mvp-golden-h2.ps1')
}

Step-Run 'H2 preview gate' {
    & $nodeExe (Join-Path $repoRoot 'tools/h2-exit-gate.mjs')
}

if ($Tier -eq 'standard') {
    if ($IncludeR4Snapshot) {
        Step-Run 'R4 snapshot' {
            & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'r4-evolution-snapshot.ps1')
        }
    }
    Write-Host 'OK: full-regression standard done.' -ForegroundColor Green
    exit 0
}

# heavy：第二套 golden-h2 draft IMPORT；R3 verifyPass 由紧随的 H2 L-rel 把关（勿与 draft 语义逐条强绑）。
Step-Run 'H2 import-track (draft, 2 rounds, 20s cooldown)' {
    & $nodeExe @(
        (Join-Path $repoRoot 'tools/h2-import-track.mjs'),
        '--rounds', '2',
        '--cooldown-ms', '20000'
    )
}

Step-Run 'H2 L-rel' {
    & $nodeExe (Join-Path $repoRoot 'tools/h2-l-rel-exit-gate.mjs')
}

$bookWriterZip = Join-Path $repoRoot 'tools\out\fbs-bookwriter-workbuddy-latest.zip'
if (Test-Path -LiteralPath $bookWriterZip) {
    Step-Run 'L-rel-BookWriter (workbuddy zip + frozen rows, optional heavy)' {
        & $nodeExe (Join-Path $repoRoot 'tools/l-rel-bookwriter-exit-gate.mjs')
    }
} else {
    Write-Host 'Skip L-rel-BookWriter: missing tools/out/fbs-bookwriter-workbuddy-latest.zip (sync-fbs-bookwriter-dist.ps1).' -ForegroundColor DarkYellow
}

if ($IncludeR4Snapshot) {
    Step-Run 'R4 snapshot' {
        & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'r4-evolution-snapshot.ps1')
    }
}

Write-Host 'OK: full-regression heavy done.' -ForegroundColor Green
exit 0
