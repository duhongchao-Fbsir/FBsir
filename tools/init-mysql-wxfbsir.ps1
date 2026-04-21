# Initialize local MySQL for WxFbsir: CREATE DATABASE + import sql/wxfbsir.sql
# Requires mysql.exe (PATH or -MysqlBin). Default user/pass matches application-druid.yml (root/root).
param(
    [string]$HostName = '127.0.0.1',
    [int]$Port = 3306,
    [string]$User = 'root',
    [string]$Password = 'root',
    [string]$MysqlBin = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$sqlFile = Join-Path $repoRoot 'sql\wxfbsir.sql'
if (-not (Test-Path -LiteralPath $sqlFile)) {
    Write-Error "Missing: $sqlFile"
    exit 1
}

function Resolve-MysqlExe {
    if ($MysqlBin -and (Test-Path -LiteralPath $MysqlBin)) { return $MysqlBin }
    $cmd = Get-Command mysql -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe',
        'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    )
    $pf86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    if ($pf86) { $candidates += (Join-Path $pf86 'MySQL\MySQL Server 8.0\bin\mysql.exe') }
    foreach ($p in $candidates) {
        if ($p -and (Test-Path -LiteralPath $p)) { return $p }
    }
    return $null
}

$mysqlExe = Resolve-MysqlExe
if (-not $mysqlExe) {
    Write-Host 'mysql.exe not found. Add MySQL Server ...\bin to PATH or use -MysqlBin "full\path\mysql.exe"' -ForegroundColor Yellow
    exit 1
}

Write-Host "Using: $mysqlExe" -ForegroundColor DarkGray

$createSql = "CREATE DATABASE IF NOT EXISTS wxfbsir CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
Write-Host '== CREATE DATABASE wxfbsir ==' -ForegroundColor Cyan
# Use --host=... so PowerShell does not split -h127.0.0.1 into -h127 + .0.0.1
if ($Password) {
    & $mysqlExe "--host=$HostName", "--port=$Port", "-u$User", "--password=$Password", '-e', $createSql
} else {
    & $mysqlExe "--host=$HostName", "--port=$Port", "-u$User", '-e', $createSql
}
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Connection failed (is mysqld running on this port?). Try: .\tools\start-mysql-service.ps1 (Admin) or services.msc' -ForegroundColor Yellow
    exit $LASTEXITCODE
}

Write-Host '== IMPORT sql/wxfbsir.sql ==' -ForegroundColor Cyan
if ($Password) {
    cmd /c "`"$mysqlExe`" --host=$HostName --port=$Port -u$User --password=$Password wxfbsir < `"$sqlFile`""
} else {
    cmd /c "`"$mysqlExe`" --host=$HostName --port=$Port -u$User wxfbsir < `"$sqlFile`""
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host 'init-mysql-wxfbsir: OK' -ForegroundColor Green
