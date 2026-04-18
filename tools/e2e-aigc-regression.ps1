param(
    [ValidateSet("P0", "P1", "P2")]
    [string]$Tier = "P0",
    [string]$OutputDir = "",
    [string]$SmokeScriptPath = "",
    [int]$RepeatP2 = 3
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $PSScriptRoot "out"
}
if ([string]::IsNullOrWhiteSpace($SmokeScriptPath)) {
    $SmokeScriptPath = Join-Path $PSScriptRoot "e2e-aigc-smoke.ps1"
}
if (-not (Test-Path $SmokeScriptPath)) {
    throw "Smoke script not found: $SmokeScriptPath"
}
if (-not (Test-Path $OutputDir)) {
    New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null
}
if ($RepeatP2 -lt 1) {
    throw "RepeatP2 must be >= 1"
}

$promptAscii = "E2E smoke reply OK only"
$promptZh = [string]::Concat(
    [char]0x56DE, [char]0x5F52, [char]0x6D4B, [char]0x8BD5,
    [char]0xFF1A, [char]0x8BF7, [char]0x4EC5, [char]0x56DE,
    [char]0x590D, [char]0x004F, [char]0x004B
)
$rocket = [string]::Concat([char]0xD83D, [char]0xDE80)
$promptEmoji = "Regression $rocket OK"

function Get-RunPlan {
    param(
        [string]$PlanTier,
        [int]$PlanRepeatP2
    )
    switch ($PlanTier) {
        "P0" {
            return @(
                @{ name = "p0-ascii-full"; prompt = $promptAscii }
            )
        }
        "P1" {
            return @(
                @{ name = "p1-ascii-full"; prompt = $promptAscii },
                @{ name = "p1-zh-full"; prompt = $promptZh },
                @{ name = "p1-emoji-full"; prompt = $promptEmoji }
            )
        }
        "P2" {
            $runs = @()
            for ($i = 1; $i -le $PlanRepeatP2; $i++) {
                $runs += @{ name = "p2-ascii-full-$i"; prompt = $promptAscii }
            }
            $runs += @{ name = "p2-zh-full"; prompt = $promptZh }
            return $runs
        }
    }
    throw "Unsupported tier: $PlanTier"
}

function Invoke-OneSmokeRun {
    param(
        [hashtable]$RunSpec,
        [string]$ScriptPath,
        [string]$OutDir
    )

    $name = [string]$RunSpec.name
    $prompt = [string]$RunSpec.prompt
    $start = Get-Date
    Write-Host ""
    Write-Host "=== START RUN: $name ==="
    Write-Host "Prompt: $prompt"
    Write-Host "Start:  $($start.ToString('yyyy-MM-dd HH:mm:ss'))"

    $oldPrompt = $env:E2E_PROMPT
    $env:E2E_PROMPT = $prompt

    $lines = @()
    $exitCode = 0
    try {
        $cmdOutput = & powershell -NoProfile -ExecutionPolicy Bypass -File "$ScriptPath" 2>&1
        foreach ($obj in $cmdOutput) {
            $line = [string]$obj
            $lines += $line
            Write-Host $line
        }
        $exitCode = $LASTEXITCODE
    } finally {
        if ($null -eq $oldPrompt) {
            Remove-Item Env:E2E_PROMPT -ErrorAction SilentlyContinue
        } else {
            $env:E2E_PROMPT = $oldPrompt
        }
    }

    $end = Get-Date
    $durationSec = [Math]::Round(($end - $start).TotalSeconds, 2)
    $summaryMatch = [regex]::Match(($lines -join "`n"), "SUMMARY:\s*RESULT_OK=(\d+)\s*/\s*(\d+)")
    $okCount = $null
    $totalCount = $null
    if ($summaryMatch.Success) {
        $okCount = [int]$summaryMatch.Groups[1].Value
        $totalCount = [int]$summaryMatch.Groups[2].Value
    }

    $aiResults = @()
    $aiLinePattern = "^(deepseek|doubao|qianwen|yuanbao|wenxin|mita|gitee)\s+([0-9a-fA-F-]+)\s+(RESULT_OK|RESULT_FAIL|TASK_ERROR|TIMEOUT|WS_FAIL)\s*(.*)$"
    foreach ($line in $lines) {
        $m = [regex]::Match($line.Trim(), $aiLinePattern)
        if (-not $m.Success) {
            continue
        }
        $outcome = $m.Groups[3].Value
        $stage = "finish"
        $errorCategory = $null
        if ($outcome -eq "WS_FAIL") {
            $stage = "connect"
            $errorCategory = "WS_CONNECT_FAIL"
        } elseif ($outcome -eq "TIMEOUT") {
            $stage = "execute"
            $errorCategory = "TASK_TIMEOUT"
        } elseif ($outcome -eq "TASK_ERROR") {
            $stage = "execute"
            $errorCategory = "TASK_ERROR"
        } elseif ($outcome -eq "RESULT_FAIL") {
            $stage = "store"
            $errorCategory = "RESULT_NOT_SUCCESS"
        }
        $aiResults += [ordered]@{
            ai = $m.Groups[1].Value
            sessionId = $m.Groups[2].Value
            outcome = $outcome
            detail = $m.Groups[4].Value.Trim()
            stage = $stage
            errorCategory = $errorCategory
            aiTypeDetectedFrom = "smoke-table-row"
        }
    }

    $ts = $start.ToString("yyyyMMdd-HHmmss")
    $logPath = Join-Path $OutDir "$($name)-$ts.log"
    $lines | Out-File -FilePath $logPath -Encoding UTF8

    $runPassed = $false
    if ($null -ne $okCount -and $null -ne $totalCount -and $totalCount -gt 0 -and $okCount -eq $totalCount -and $exitCode -eq 0) {
        $runPassed = $true
    }

    Write-Host "End:    $($end.ToString('yyyy-MM-dd HH:mm:ss'))"
    Write-Host "RunSec: $durationSec"
    Write-Host "Pass:   $runPassed"
    Write-Host "Log:    $logPath"
    Write-Host "=== END RUN: $name ==="

    return [ordered]@{
        name = $name
        prompt = $prompt
        startTime = $start.ToString("o")
        endTime = $end.ToString("o")
        durationSec = $durationSec
        exitCode = $exitCode
        summaryFound = $summaryMatch.Success
        resultOk = $okCount
        resultTotal = $totalCount
        passed = $runPassed
        aiResults = $aiResults
        logPath = $logPath
    }
}

$plan = Get-RunPlan -PlanTier $Tier -PlanRepeatP2 $RepeatP2
$jobStart = Get-Date
Write-Host "Tier:   $Tier"
Write-Host "Runs:   $($plan.Count)"
Write-Host "Output: $OutputDir"
Write-Host "Smoke:  $SmokeScriptPath"

$runResults = @()
foreach ($run in $plan) {
    $runResults += Invoke-OneSmokeRun -RunSpec $run -ScriptPath $SmokeScriptPath -OutDir $OutputDir
}

$jobEnd = Get-Date
$jobDurationSec = [Math]::Round(($jobEnd - $jobStart).TotalSeconds, 2)
$passCount = @($runResults | Where-Object { $_.passed }).Count
$overallPassed = ($passCount -eq $runResults.Count)

$summary = [ordered]@{
    tier = $Tier
    startTime = $jobStart.ToString("o")
    endTime = $jobEnd.ToString("o")
    durationSec = $jobDurationSec
    runCount = $runResults.Count
    passCount = $passCount
    failedCount = $runResults.Count - $passCount
    passed = $overallPassed
    runs = $runResults
}

$summaryPath = Join-Path $OutputDir ("regression-{0}-{1}.json" -f $Tier.ToLower(), $jobStart.ToString("yyyyMMdd-HHmmss"))
$summary | ConvertTo-Json -Depth 10 | Out-File -FilePath $summaryPath -Encoding UTF8

Write-Host ""
Write-Host "=== REGRESSION SUMMARY ==="
Write-Host "Tier:       $Tier"
Write-Host "Duration:   $jobDurationSec sec"
Write-Host "RunCount:   $($runResults.Count)"
Write-Host "PassCount:  $passCount"
Write-Host "FailedCount:$($runResults.Count - $passCount)"
Write-Host "Summary:    $summaryPath"
Write-Host "Passed:     $overallPassed"

if (-not $overallPassed) {
    exit 1
}
exit 0

