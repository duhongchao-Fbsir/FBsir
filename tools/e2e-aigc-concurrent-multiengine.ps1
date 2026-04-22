# 多 Engine ID / 多实例并发压测：两路进程分别连 Admin，但发往不同的 engineId（各实例独立 JVM、独立 TaskExecutionTracker）。
# 前置：Admin:8080；8081 上 engine-dev-001 已在线；8082 上 engine-dev-002 已在线（见 tools\start-second-engine.ps1）；
#       且已插入白名单 engine-dev-002（sql\update_20260421_ws_host_whitelist_engine_dev_002_幂等.sql）。
param(
    [string]$SmokeScript = "",
    [string]$EngineIdA = "engine-dev-001",
    [string]$EngineIdB = "engine-dev-002",
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

function Test-Admin8080 {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:8080/" -Method Get -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
        return $true
    } catch { return $false }
}
if (-not (Test-Admin8080)) {
    throw "Admin not reachable at http://127.0.0.1:8080/"
}

function Test-TcpListen([int]$Port) {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(2500, $false)) { $c.Close(); return $false }
        $c.EndConnect($iar); $c.Close(); return $true
    } catch { return $false }
}
if (-not (Test-TcpListen -Port 8081)) {
    throw "Port 8081 not listening. Start first Engine (engine-dev-001), e.g. tools/start-engine.ps1 or start-stack."
}
if (-not (Test-TcpListen -Port 8082)) {
    throw "Port 8082 not listening. Run tools/start-second-engine.ps1 and ensure ws_host_whitelist has engine-dev-002."
}

$outDir = Join-Path $PSScriptRoot ("out-concurrent-multiengine-{0}" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -Path $outDir -ItemType Directory -Force | Out-Null

$logA = Join-Path $outDir "session-a.log"
$logB = Join-Path $outDir "session-b.log"
$errA = Join-Path $outDir "session-a.err"
$errB = Join-Path $outDir "session-b.err"

Write-Host "Concurrent multi-engine test (distinct engineId per session)" -ForegroundColor Cyan
Write-Host "Engine A: $EngineIdA   Engine B: $EngineIdB"
Write-Host "Smoke:  $SmokeScript"
Write-Host "E2E_REPLY_DEADLINE_SEC: $ReplyDeadlineSec  StaggerSec: $StaggerSec  WaitTimeoutSec: $WaitTimeoutSec"
Write-Host "Output: $outDir"

$smokeLiteral = $SmokeScript.Replace("'", "''")
$slotA = "mengA"
$slotB = "mengB"
$rd = "$ReplyDeadlineSec"
$cmdA = "`$env:E2E_REPLY_DEADLINE_SEC='$rd'; `$env:E2E_WS_SLOT='$slotA'; `$env:E2E_ENGINE_ID='$EngineIdA'; & '$smokeLiteral'; exit `$LASTEXITCODE"
$cmdB = "`$env:E2E_REPLY_DEADLINE_SEC='$rd'; `$env:E2E_WS_SLOT='$slotB'; `$env:E2E_ENGINE_ID='$EngineIdB'; & '$smokeLiteral'; exit `$LASTEXITCODE"

$pA = Start-Process -FilePath "powershell.exe" -ArgumentList @(
    "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $cmdA
) -PassThru -NoNewWindow -RedirectStandardOutput $logA -RedirectStandardError $errA

if ($StaggerSec -gt 0) {
    Write-Host "Stagger: waiting ${StaggerSec}s before session B..."
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
if (-not $pA.HasExited) { try { $pA.Kill() } catch {} ; throw "Session A timed out after ${WaitTimeoutSec}s" }
if (-not $pB.HasExited) { try { $pB.Kill() } catch {} ; throw "Session B timed out after ${WaitTimeoutSec}s" }

function Get-ProcExitCode([System.Diagnostics.Process]$Proc) {
    try {
        $p2 = Get-Process -Id $Proc.Id -ErrorAction Stop
        if (-not $p2.HasExited) { return $null }
    } catch { }
    try { return [int]$Proc.ExitCode } catch { return $null }
}

$exitA = Get-ProcExitCode -Proc $pA
$exitB = Get-ProcExitCode -Proc $pB

Write-Host ""
Write-Host "Session A ($EngineIdA) ExitCode: $exitA"
Write-Host "Session B ($EngineIdB) ExitCode: $exitB"

function Get-SummaryFromLog([string]$Path) {
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
} else { $ok = $false }
if ($sumA -and $sumB) {
    $ok = $ok -and ($sumA.Ok -eq $sumA.Total) -and ($sumB.Ok -eq $sumB.Total)
} else { $ok = $false }

Write-Host ""
if ($ok) {
    Write-Host "--- PASS: both engines 4/4 and exit 0 ---" -ForegroundColor Green
    exit 0
}
Write-Host "--- FAIL: see $outDir ---" -ForegroundColor Red
exit 1
