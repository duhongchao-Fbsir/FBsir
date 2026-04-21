# Try to start Windows MySQL service (may require Administrator PowerShell)
$names = @('MySQL84', 'MySQL80', 'MySQL57', 'MySQL')
foreach ($n in $names) {
    $s = Get-Service -Name $n -ErrorAction SilentlyContinue
    if ($s) {
        Write-Host "Found service: $($s.Name) status=$($s.Status)" -ForegroundColor DarkGray
        if ($s.Status -ne 'Running') {
            try {
                Start-Service -Name $n
                Write-Host "Started: $n" -ForegroundColor Green
            } catch {
                Write-Host "Could not start $n (try Run as Administrator): $_" -ForegroundColor Red
                exit 1
            }
        } else {
            Write-Host "Already running: $n" -ForegroundColor Green
        }
        exit 0
    }
}
$candidates = Get-Service -ErrorAction SilentlyContinue | Where-Object {
    $_.Name -match 'mysql' -or $_.DisplayName -match 'MySQL'
}
if ($candidates) {
    foreach ($s in $candidates) {
        Write-Host "Candidate: $($s.Name) ($($s.DisplayName)) status=$($s.Status)" -ForegroundColor Cyan
        if ($s.Status -ne 'Running') {
            try {
                Start-Service -InputObject $s
                Write-Host "Started: $($s.Name)" -ForegroundColor Green
            } catch {
                Write-Host "Could not start $($s.Name) (try Admin): $_" -ForegroundColor Red
            }
        }
    }
    exit 0
}
Write-Host 'No MySQL Windows service found. If MySQL runs as a user process or WSL, start it manually.' -ForegroundColor Yellow
exit 1
