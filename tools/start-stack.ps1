# Orchestrate: MySQL/Redis (Docker compose if needed) -> Admin JAR -> Engine JAR -> UI (if 5173 down)
# Run from repo:  .\tools\start-stack.ps1
# JAR-style UI (build + vite preview):  .\tools\start-stack.ps1 -UiMode prod
#   或:  .\tools\start-jar.ps1
# Requires: JDK 17+, Node/npm for UI. MySQL: Docker (compose.yaml) or existing 3306/wxfbsir.
param(
    [ValidateSet('dev', 'prod')]
    [string]$UiMode = 'dev'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

. (Join-Path $PSScriptRoot 'docker-cli.ps1')

function Test-Port {
    param([int]$Port)
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(600, $false)) { $c.Close(); return $false }
        $c.EndConnect($iar); $c.Close(); return $true
    } catch { return $false }
}

function Wait-Port {
    param([int]$Port, [int]$Seconds = 90)
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Port -Port $Port) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Resolve-Mvn {
    if (Get-Command mvn -ErrorAction SilentlyContinue) { return 'mvn' }
    $b = Join-Path $repoRoot '.tools\maven-extract\apache-maven-3.9.9\bin\mvn.cmd'
    if (Test-Path $b) { return $b }
    return $null
}

function Ensure-Jars {
    $mvn = Resolve-Mvn
    if (-not $mvn) {
        Write-Error 'Maven not found. Add mvn to PATH or extract .tools/maven-bin.zip to .tools/maven-extract/apache-maven-3.9.9/'
        exit 1
    }
    $adminJar = Join-Path $repoRoot 'WxFbsir-admin\target\WxFbsir-admin.jar'
    if (-not (Test-Path $adminJar)) {
        Write-Host '== mvn package WxFbsir-admin ==' -ForegroundColor Cyan
        Set-Location $repoRoot
        & $mvn -pl WxFbsir-admin -am package -DskipTests
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    $engineDir = Join-Path $repoRoot 'WxFbsir-engine'
    $eng = Get-ChildItem -Path (Join-Path $engineDir 'target') -Filter 'wxfbsir-engine-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'original|sources' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $eng) {
        Write-Host '== mvn package WxFbsir-engine ==' -ForegroundColor Cyan
        Set-Location $engineDir
        & $mvn package -DskipTests
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Set-Location $repoRoot
    }
}

# --- MySQL ---
if (-not (Test-Port -Port 3306)) {
    $docker = Get-DockerExecutable
    if ($docker) {
        $deps = Join-Path $PSScriptRoot 'start-local-deps.ps1'
        Write-Host '== MySQL not on 3306: running start-local-deps ==' -ForegroundColor Yellow
        & $deps
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } else {
        Write-Host 'MySQL (127.0.0.1:3306) is down and Docker CLI was not found.' -ForegroundColor Red
        Write-Host 'Install Docker Desktop and run: .\tools\start-local-deps.ps1' -ForegroundColor Yellow
        Write-Host 'Or install MySQL 8, create database wxfbsir, import sql\wxfbsir.sql' -ForegroundColor Yellow
        exit 1
    }
}
if (-not (Test-Port -Port 3306)) {
    Write-Error 'MySQL still not reachable on 3306.'
    exit 1
}

# --- Redis (warn only) ---
if (-not (Test-Port -Port 6379)) {
    Write-Host 'WARN: Redis 6379 is not open. Login/token may fail until Redis is up.' -ForegroundColor Yellow
}

Ensure-Jars

$java = (Get-Command java).Source
$adminJar = Join-Path $repoRoot 'WxFbsir-admin\target\WxFbsir-admin.jar'
$engineDir = Join-Path $repoRoot 'WxFbsir-engine'
$engineJar = (Get-ChildItem -Path (Join-Path $engineDir 'target') -Filter 'wxfbsir-engine-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'original|sources' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName

$jvm = @('-Dfile.encoding=UTF-8', '-Dsun.jnu.encoding=UTF-8')
$logAo = Join-Path $repoRoot 'WxFbsir-admin\target\stack-admin-out.log'
$logAe = Join-Path $repoRoot 'WxFbsir-admin\target\stack-admin-err.log'
$logEo = Join-Path $repoRoot 'WxFbsir-engine\target\stack-engine-out.log'
$logEe = Join-Path $repoRoot 'WxFbsir-engine\target\stack-engine-err.log'

if (-not (Test-Port -Port 8080)) {
    Write-Host '== start Admin (background) ==' -ForegroundColor Cyan
    Start-Process -FilePath $java -ArgumentList ($jvm + @('-jar', $adminJar)) -WorkingDirectory (Join-Path $repoRoot 'WxFbsir-admin') `
        -WindowStyle Hidden -RedirectStandardOutput $logAo -RedirectStandardError $logAe -PassThru | Out-Null
    if (-not (Wait-Port -Port 8080 -Seconds 120)) {
        Write-Host "Admin did not open 8080 in time. See $logAe" -ForegroundColor Red
        exit 1
    }
    Write-Host 'Admin: http://127.0.0.1:8080/' -ForegroundColor Green
} else {
    Write-Host '8080 already in use (skip Admin).' -ForegroundColor DarkGray
}

if (-not (Test-Port -Port 8081)) {
    Write-Host '== start Engine (background) ==' -ForegroundColor Cyan
    Start-Process -FilePath $java -ArgumentList ($jvm + @('-jar', $engineJar)) -WorkingDirectory $engineDir `
        -WindowStyle Hidden -RedirectStandardOutput $logEo -RedirectStandardError $logEe -PassThru | Out-Null
    if (-not (Wait-Port -Port 8081 -Seconds 90)) {
        Write-Host "Engine did not open 8081 in time. See $logEe" -ForegroundColor Yellow
    } else {
        Write-Host 'Engine: http://127.0.0.1:8081/' -ForegroundColor Green
    }
} else {
    Write-Host '8081 already in use (skip Engine).' -ForegroundColor DarkGray
}

if (-not (Test-Port -Port 5173)) {
    $ui = Join-Path $repoRoot 'WxFbsir-ui'
    $npm = (Get-Command npm -ErrorAction SilentlyContinue).Source
    if (-not $npm) { $npm = 'npm' }
    if ($UiMode -eq 'prod') {
        $envProd = Join-Path $ui '.env.production'
        $envEx = Join-Path $ui '.env.production.example'
        if (-not (Test-Path -LiteralPath $envProd) -and (Test-Path -LiteralPath $envEx)) {
            Copy-Item -LiteralPath $envEx -Destination $envProd -Force
            Write-Host 'Copied WxFbsir-ui\.env.production.example -> .env.production' -ForegroundColor DarkGray
        }
        Write-Host '== WxFbsir-ui: npm run build:prod (no JAR; static dist + preview) ==' -ForegroundColor Cyan
        Push-Location $ui
        & $npm run build:prod
        if ($LASTEXITCODE -ne 0) { Pop-Location; exit $LASTEXITCODE }
        Pop-Location
        Write-Host '== WxFbsir-ui: vite preview on 5173 ==' -ForegroundColor Cyan
        Start-Process -FilePath $npm -ArgumentList @('run', 'preview', '--', '--host', '0.0.0.0', '--port', '5173') -WorkingDirectory $ui -WindowStyle Normal
        Start-Sleep -Seconds 3
    } else {
        Write-Host '== Vite dev (new window, WxFbsir-ui) ==' -ForegroundColor Cyan
        Start-Process -FilePath $npm -ArgumentList @('run', 'dev') -WorkingDirectory $ui -WindowStyle Normal
        Start-Sleep -Seconds 4
    }
}

Write-Host ''
$uiNote = if ($UiMode -eq 'prod') { 'UI=dist+preview (see .env.production)' } else { 'UI=vite dev' }
Write-Host "stack: Admin 8080, Engine 8081, http://127.0.0.1:5173/  ($uiNote)" -ForegroundColor Green
Write-Host "Logs: $logAo / $logAe , $logEo / $logEe" -ForegroundColor DarkGray
