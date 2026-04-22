# 本地第二套 Engine JVM：另一组 host-id + HTTP 端口 + Playwright 数据目录，避免与 8081/engine-dev-001 争用。
# 前置：主节点 Admin 已监听 8080；已执行 sql/update_20260421_ws_host_whitelist_engine_dev_002_幂等.sql；建议先启动第一实例（8081）。
# 用法：.\tools\start-second-engine.ps1
# 默认后台启动并等待 8082 就绪。前台阻塞调：.\tools\start-engine.ps1 -HttpPort 8082 -HostId engine-dev-002 ...
param(
    [int]$HttpPort = 8082,
    [string]$HostId = "engine-dev-002",
    [string]$PlaywrightDataDir = "./data/playwright-engine-dev-002",
    [switch]$Foreground
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

function Test-PortOpen([int]$Port) {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect("127.0.0.1", $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(800, $false)) { $c.Close(); return $false }
        $c.EndConnect($iar); $c.Close(); return $true
    } catch { return $false }
}

if (Test-PortOpen -Port $HttpPort) {
    Write-Host "Port $HttpPort already in use; skip start (second Engine may already be running)." -ForegroundColor Yellow
    exit 0
}

$scriptDir = $PSScriptRoot
$engRoot = Join-Path (Split-Path -Parent $scriptDir) "WxFbsir-engine"
$stdOut = Join-Path $engRoot "target\stack-engine2-out.log"
$stdErr = Join-Path $engRoot "target\stack-engine2-err.log"
$startEngine = Join-Path $scriptDir "start-engine.ps1"

Write-Host "Second Engine: port=$HttpPort host-id=$HostId data-dir=$PlaywrightDataDir" -ForegroundColor Cyan

if ($Foreground) {
    & $startEngine -HttpPort $HttpPort -HostId $HostId -PlaywrightDataDir $PlaywrightDataDir
    exit $LASTEXITCODE
}

& $startEngine -HttpPort $HttpPort -HostId $HostId -PlaywrightDataDir $PlaywrightDataDir `
    -Background -StdOutLog $stdOut -StdErrLog $stdErr
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$deadline = [DateTime]::UtcNow.AddSeconds(90)
while ([DateTime]::UtcNow -lt $deadline) {
    if (Test-PortOpen -Port $HttpPort) {
        Write-Host "Engine2: http://127.0.0.1:$HttpPort/ (Admin ws 仍为第一实例；客户端请求须指定 engineId=$HostId)" -ForegroundColor Green
        Write-Host "Logs: $stdOut / $stdErr" -ForegroundColor DarkGray
        exit 0
    }
    Start-Sleep -Seconds 2
}
Write-Error "Second Engine did not open port $HttpPort in time. See $stdErr"
exit 1
