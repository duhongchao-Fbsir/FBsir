# Quick environment check for local stack (ports, Java, Maven, JARs, Docker)
$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Port-Ok { param([int]$p)
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect('127.0.0.1', $p, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(500, $false)) { $c.Close(); return $false }
        $c.EndConnect($iar); $c.Close(); return $true
    } catch { return $false }
}

. (Join-Path $PSScriptRoot 'docker-cli.ps1')
$docker = Get-DockerExecutable

Write-Host '=== WxFbsir preflight ===' -ForegroundColor Cyan
Write-Host ('MySQL 3306:  ' + (Port-Ok 3306))
Write-Host ('Redis 6379:  ' + (Port-Ok 6379))
Write-Host ('Admin 8080:  ' + (Port-Ok 8080))
Write-Host ('Engine 8081: ' + (Port-Ok 8081))
Write-Host ('Vite 5173:   ' + (Port-Ok 5173))
Write-Host ('Java: ' + $(if (Get-Command java -ErrorAction SilentlyContinue) { (Get-Command java).Source } else { 'MISSING' }))
Write-Host ('Maven: ' + $(if (Get-Command mvn -ErrorAction SilentlyContinue) { 'PATH' } elseif (Test-Path (Join-Path $repoRoot '.tools\maven-extract\apache-maven-3.9.9\bin\mvn.cmd')) { 'bundled' } else { 'MISSING' }))
Write-Host ('Docker: ' + $(if ($docker) { $docker } else { 'MISSING' }))
$aj = Join-Path $repoRoot 'WxFbsir-admin\target\WxFbsir-admin.jar'
Write-Host ('WxFbsir-admin.jar: ' + $(if (Test-Path $aj) { 'OK' } else { 'missing (mvn package)' }))
$ej = Get-ChildItem (Join-Path $repoRoot 'WxFbsir-engine\target') -Filter 'wxfbsir-engine-*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch 'original' } | Select-Object -First 1
Write-Host ('Engine JAR: ' + $(if ($ej) { $ej.Name } else { 'missing (mvn package in WxFbsir-engine)' }))
