# 启动本地 MySQL + Redis（Docker Compose），供 WxFbsir-admin 连接。
# 即使未将 docker 加入 PATH，也会尝试 Docker Desktop 默认安装路径。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot
$composeFile = Join-Path $repoRoot 'compose.yaml'

. (Join-Path $PSScriptRoot 'docker-cli.ps1')
$dockerExe = Get-DockerExecutable

function Test-TcpPortOpen {
    param([string]$HostName, [int]$Port, [int]$TimeoutMs = 800)
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect($HostName, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) {
            $c.Close()
            return $false
        }
        $c.EndConnect($iar)
        $c.Close()
        return $true
    } catch {
        return $false
    }
}

function Test-MysqlPingOnce {
    param([string]$Docker)
    $null = & $Docker exec wxfbsir-mysql mysqladmin ping -h 127.0.0.1 -uroot -proot 2>$null
    return ($LASTEXITCODE -eq 0)
}

if (-not $dockerExe) {
    Write-Host ''
    Write-Host 'Docker CLI not found. Install Docker Desktop or add docker.exe to PATH.' -ForegroundColor Yellow
    Write-Host 'Typical path: C:\Program Files\Docker\Docker\resources\bin\docker.exe' -ForegroundColor Yellow
    Write-Host 'Or start manually: MySQL 8 on 127.0.0.1:3306 database wxfbsir user root password root, Redis 127.0.0.1:6379' -ForegroundColor Yellow
    Write-Host ''
    Write-Error 'Docker CLI not found.'
    exit 1
}

Write-Host "Using Docker: $dockerExe" -ForegroundColor DarkGray
$redisAlready = Test-TcpPortOpen -HostName '127.0.0.1' -Port 6379
if ($redisAlready) {
    Write-Host '6379 is already in use: starting MySQL container only (profile mysql).' -ForegroundColor Yellow
    Write-Host '== docker compose --profile mysql up ==' -ForegroundColor Cyan
    & $dockerExe compose -f $composeFile --profile mysql up -d
} else {
    Write-Host '== docker compose --profile full up (MySQL + Redis) ==' -ForegroundColor Cyan
    & $dockerExe compose -f $composeFile --profile full up -d
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host '== wait for MySQL: port + mysqladmin ping ==' -ForegroundColor Cyan
$deadline = [DateTime]::UtcNow.AddMinutes(4)
while ([DateTime]::UtcNow -lt $deadline) {
    if ((Test-TcpPortOpen -HostName '127.0.0.1' -Port 3306) -and (Test-MysqlPingOnce -Docker $dockerExe)) {
        Write-Host "MySQL is ready." -ForegroundColor Green
        break
    }
    Start-Sleep -Seconds 2
}
if (-not (Test-TcpPortOpen -HostName '127.0.0.1' -Port 3306)) {
    Write-Error ("3306 not listening. Check logs: " + $dockerExe + " logs wxfbsir-mysql")
    exit 1
}
if (-not (Test-MysqlPingOnce -Docker $dockerExe)) {
    Write-Error 'MySQL port open but mysqladmin ping failed. Wait for init or run tools\reset-local-docker.ps1'
    exit 1
}

if (-not $redisAlready) {
    Write-Host '== wait for Redis :6379 ==' -ForegroundColor Cyan
    $deadline = [DateTime]::UtcNow.AddMinutes(1)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-TcpPortOpen -HostName '127.0.0.1' -Port 6379) { break }
        Start-Sleep -Milliseconds 500
    }
    if (-not (Test-TcpPortOpen -HostName '127.0.0.1' -Port 6379)) {
        Write-Error ("6379 not ready. Check logs: " + $dockerExe + " logs wxfbsir-redis")
        exit 1
    }
} else {
    Write-Host 'Skipping Redis container wait (using existing 6379).' -ForegroundColor DarkGray
}

Write-Host 'start-local-deps: OK (MySQL 3306, Redis 6379 reachable)' -ForegroundColor Green
