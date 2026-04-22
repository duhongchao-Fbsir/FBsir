# 仅重启 Admin(8080) + Engine(8081)，不碰 Vite(5173)。可选先打全量包。
# 用法（仓库根）: .\tools\restart-backend.ps1   或   .\tools\restart-backend.ps1 -SkipBuild
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if (-not $SkipBuild) {
    Write-Host '== compile-admin ==' -ForegroundColor Cyan
    & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'compile-admin.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host '== compile-engine (package) ==' -ForegroundColor Cyan
    & powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'compile-engine.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$javaExe = $null
if (Get-Command java -ErrorAction SilentlyContinue) {
    $javaExe = (Get-Command java).Source
}
if (-not $javaExe) {
    Write-Error 'java not in PATH'
    exit 1
}

$adminJar = Join-Path $repoRoot 'WxFbsir-admin\target\WxFbsir-admin.jar'
if (-not (Test-Path -LiteralPath $adminJar)) {
    Write-Error "Missing $adminJar — run compile-admin.ps1 first."
    exit 1
}

$engineJar = Get-ChildItem -Path (Join-Path $repoRoot 'WxFbsir-engine\target') -Filter 'wxfbsir-engine-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'original|sources|javadoc' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $engineJar) {
    Write-Error 'Missing Engine JAR — run compile-engine.ps1 first.'
    exit 1
}

& powershell.exe -ExecutionPolicy Bypass -NoProfile -File (Join-Path $PSScriptRoot 'kill-backend-ports.ps1')

$adminDir = Join-Path $repoRoot 'WxFbsir-admin'
$adminOut = Join-Path $adminDir 'target\admin-restart-out.log'
$adminErr = Join-Path $adminDir 'target\admin-restart-err.log'
$p1 = Start-Process -FilePath $javaExe -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', $adminJar) `
    -WorkingDirectory $adminDir -WindowStyle Hidden `
    -RedirectStandardOutput $adminOut -RedirectStandardError $adminErr -PassThru
Write-Host "Admin PID=$($p1.Id) log=$adminOut" -ForegroundColor Green

$engineDir = Join-Path $repoRoot 'WxFbsir-engine'
$engOut = Join-Path $engineDir 'target\engine-restart-out.log'
$engErr = Join-Path $engineDir 'target\engine-restart-err.log'
$p2 = Start-Process -FilePath $javaExe -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', $engineJar.FullName) `
    -WorkingDirectory $engineDir -WindowStyle Hidden `
    -RedirectStandardOutput $engOut -RedirectStandardError $engErr -PassThru
Write-Host "Engine PID=$($p2.Id) log=$engOut" -ForegroundColor Green

Write-Host 'Wait for 8080/8081 (up to ~90s)...' -ForegroundColor Cyan
$deadline = [DateTime]::UtcNow.AddSeconds(90)
while ([DateTime]::UtcNow -lt $deadline) {
    $a = $false
    $e = $false
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect('127.0.0.1', 8080, $null, $null)
        if ($iar.AsyncWaitHandle.WaitOne(400, $false)) { $c.EndConnect($iar); $a = $true }
        $c.Close()
    } catch {}
    try {
        $c2 = New-Object System.Net.Sockets.TcpClient
        $iar2 = $c2.BeginConnect('127.0.0.1', 8081, $null, $null)
        if ($iar2.AsyncWaitHandle.WaitOne(400, $false)) { $c2.EndConnect($iar2); $e = $true }
        $c2.Close()
    } catch {}
    if ($a -and $e) {
        Write-Host '8080 and 8081 are up.' -ForegroundColor Green
        exit 0
    }
    Start-Sleep -Seconds 2
}
Write-Host 'WARN: ports not both listening yet; check admin/engine logs above.' -ForegroundColor Yellow
exit 0
