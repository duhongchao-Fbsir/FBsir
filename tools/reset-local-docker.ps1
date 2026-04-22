# 停止并删除 compose 中的卷（清空 MySQL 数据），下次 up 会重新导入 sql/wxfbsir.sql
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot 'compose.yaml'

. (Join-Path $PSScriptRoot 'docker-cli.ps1')
$dockerExe = Get-DockerExecutable
if (-not $dockerExe) {
    Write-Error 'Docker CLI not found.'
    exit 1
}

Set-Location $repoRoot
Write-Host '== docker compose down -v ==' -ForegroundColor Yellow
& $dockerExe compose -f $composeFile down -v
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "reset-local-docker: OK (volumes removed)" -ForegroundColor Green
