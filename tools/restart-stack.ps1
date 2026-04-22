# 结束占用 Admin(8080)、Engine(8081)、Vite(5173) 的监听进程后，执行 start-stack.ps1（默认 dev UI）
# 用法（仓库根目录）： .\tools\restart-stack.ps1
$ErrorActionPreference = 'Continue'
$ports = @(8080, 8081, 5173)
foreach ($p in $ports) {
    $conns = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $conns) {
        $procId = $c.OwningProcess
        Write-Host "Stopping PID $procId (listen on $p)"
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
}
Start-Sleep -Seconds 2
& (Join-Path $PSScriptRoot 'start-stack.ps1') -UiMode dev
