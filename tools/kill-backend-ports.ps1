# Kill processes listening on Admin/Engine ports (typically java -jar).
param([int[]]$Ports = @(8080, 8081))

$ErrorActionPreference = 'Continue'
foreach ($port in $Ports) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($conn in $conns) {
        try {
            Write-Host "Stopping PID $($conn.OwningProcess) port $port"
            Stop-Process -Id $conn.OwningProcess -Force -ErrorAction Stop
        } catch {
            Write-Host "Skip PID $($conn.OwningProcess): $($_.Exception.Message)"
        }
    }
}
Start-Sleep -Seconds 2
