# 并发双 WebSocket 会话压池：两个进程各跑一遍 e2e-aigc-smoke（不同 chatId 由脚本内 Guid 自动生成）
# 可选错峰启动，降低瞬时多任务叠峰（仍保持会话重叠，压池有效）
# 依赖：Admin+Engine、四路已登录；与 e2e-aigc-smoke 相同
param(
    [string]$SmokeScript = "",
    [int]$ReplyDeadlineSec = 320,
    [int]$StaggerSec = 25,
    [int]$WaitTimeoutSec = 900
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

if ([string]::IsNullOrWhiteSpace($SmokeScript)) {
    $SmokeScript = Join-Path $PSScriptRoot "e2e-aigc-smoke.ps1"
}
if (-not (Test-Path -LiteralPath $SmokeScript)) {
    throw "Smoke script not found: $SmokeScript"
}

$outDir = Join-Path $PSScriptRoot ("out-concurrent-{0}" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -Path $outDir -ItemType Directory -Force | Out-Null

$logA = Join-Path $outDir "session-a.log"
$logB = Join-Path $outDir "session-b.log"
$errA = Join-Path $outDir "session-a.err"
$errB = Join-Path $outDir "session-b.err"

Write-Host "Concurrent dual-session pool test" -ForegroundColor Cyan
Write-Host "Smoke:  $SmokeScript"
Write-Host "E2E_REPLY_DEADLINE_SEC (per AI): $ReplyDeadlineSec  StaggerSec: $StaggerSec  WaitTimeoutSec: $WaitTimeoutSec"
Write-Host "Output: $outDir"

# 子进程内：不同时限 + 不同 wsSlot（与 Server clientId 对齐，避免同 token 互踢）
$smokeLiteral = $SmokeScript.Replace("'", "''")
$slotA = "concA"
$slotB = "concB"
$cmdA = "`$env:E2E_REPLY_DEADLINE_SEC='$ReplyDeadlineSec'; `$env:E2E_WS_SLOT='$slotA'; & '$smokeLiteral'; exit `$LASTEXITCODE"
$cmdB = "`$env:E2E_REPLY_DEADLINE_SEC='$ReplyDeadlineSec'; `$env:E2E_WS_SLOT='$slotB'; & '$smokeLiteral'; exit `$LASTEXITCODE"

$pA = Start-Process -FilePath "powershell.exe" -ArgumentList @(
    "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $cmdA
) -PassThru -NoNewWindow -RedirectStandardOutput $logA -RedirectStandardError $errA

if ($StaggerSec -gt 0) {
    Write-Host "Stagger: waiting ${StaggerSec}s before starting session B..."
    Start-Sleep -Seconds $StaggerSec
}

$pB = Start-Process -FilePath "powershell.exe" -ArgumentList @(
    "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $cmdB
) -PassThru -NoNewWindow -RedirectStandardOutput $logB -RedirectStandardError $errB

$deadline = (Get-Date).AddSeconds($WaitTimeoutSec)
while ((Get-Date) -lt $deadline) {
    if ($pA.HasExited -and $pB.HasExited) { break }
    Start-Sleep -Milliseconds 500
}
if (-not $pA.HasExited) { try { $pA.Kill() } catch {} ; throw "Session A timed out after ${WaitTimeoutSec}s (wait for process)" }
if (-not $pB.HasExited) { try { $pB.Kill() } catch {} ; throw "Session B timed out after ${WaitTimeoutSec}s (wait for process)" }

function Get-ProcExitCode {
    param([System.Diagnostics.Process]$Proc)
    try {
        $p2 = Get-Process -Id $Proc.Id -ErrorAction Stop
        if (-not $p2.HasExited) { return $null }
    } catch { }
    try {
        return [int]$Proc.ExitCode
    } catch {
        return $null
    }
}

$exitA = Get-ProcExitCode -Proc $pA
$exitB = Get-ProcExitCode -Proc $pB

Write-Host ""
Write-Host "Session A ExitCode: $exitA"
Write-Host "Session B ExitCode: $exitB"

function Get-SummaryFromLog {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return $null }
    $t = Get-Content -LiteralPath $Path -Raw -ErrorAction SilentlyContinue
    if ([string]::IsNullOrWhiteSpace($t)) { return $null }
    $m = [regex]::Match($t, "SUMMARY:\s*RESULT_OK=(\d+)\s*/\s*(\d+)")
    if (-not $m.Success) { return $null }
    return @{ Ok = [int]$m.Groups[1].Value; Total = [int]$m.Groups[2].Value }
}

$sumA = Get-SummaryFromLog -Path $logA
$sumB = Get-SummaryFromLog -Path $logB
$sA = if ($sumA) { "$($sumA.Ok)/$($sumA.Total)" } else { "(parse failed)" }
$sB = if ($sumB) { "$($sumB.Ok)/$($sumB.Total)" } else { "(parse failed)" }
Write-Host "Session A SUMMARY: $sA"
Write-Host "Session B SUMMARY: $sB"

$ok = $true
if ($null -ne $exitA -and $null -ne $exitB) {
    $ok = $ok -and ($exitA -eq 0) -and ($exitB -eq 0)
} else {
    $ok = $false
}
if ($sumA -and $sumB) {
    $ok = $ok -and ($sumA.Ok -eq $sumA.Total) -and ($sumB.Ok -eq $sumB.Total)
} else {
    $ok = $false
}

Write-Host ""
if ($ok) {
    Write-Host "--- PASS: both sessions RESULT_OK=4/4 and exit 0 ---" -ForegroundColor Green
    exit 0
}
Write-Host "--- FAIL: see logs under $outDir ---" -ForegroundColor Red
exit 1
