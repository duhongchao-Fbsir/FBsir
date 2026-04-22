# Start MySQL 8.4 with repo data dir (port 3306, matches application-druid.yml).
# Skips if 3306 already listens.
$ErrorActionPreference = "Stop"
$mysqld = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe"
$dataDir = Join-Path (Split-Path $PSScriptRoot -Parent) ".dev\mysql-data"
if (-not (Test-Path $dataDir)) {
    Write-Error "Missing data dir: $dataDir"
    exit 1
}
if (-not (Test-Path $mysqld)) {
    Write-Error "mysqld not found: $mysqld"
    exit 1
}
$tcp = Test-NetConnection -ComputerName 127.0.0.1 -Port 3306 -WarningAction SilentlyContinue
if ($tcp.TcpTestSucceeded) {
    Write-Host "MySQL already listening on 127.0.0.1:3306, skip."
    exit 0
}
Write-Host "Starting MySQL datadir=$dataDir ..."
Start-Process -FilePath $mysqld -ArgumentList "--datadir=$dataDir", "--port=3306" -WindowStyle Hidden
Start-Sleep -Seconds 3
$tcp2 = Test-NetConnection -ComputerName 127.0.0.1 -Port 3306 -WarningAction SilentlyContinue
if (-not $tcp2.TcpTestSucceeded) {
    Write-Error "Port 3306 still closed after start."
    exit 1
}
Write-Host "MySQL started (port 3306)."
