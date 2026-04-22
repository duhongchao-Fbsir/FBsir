# Wave2 evolution preflight (skill-evolution-roadmap section 6.2)
# TCP + HTTP; warns that Engine HTTP alive != WebSocket registered to Admin.
# Optional -ClearEvolutionEnv clears EVOLUTION_* / SOURCE_EXPORT_* in current session.
param(
    [string]$AdminBase = $env:ADMIN_BASE,
    [string]$EngineBase = $env:ENGINE_BASE,
    [switch]$ClearEvolutionEnv,
    [switch]$StrictImport,
    [switch]$ShowPortOwners
)

$ErrorActionPreference = 'Stop'
if (-not $AdminBase) { $AdminBase = 'http://127.0.0.1:8080' }
if (-not $EngineBase) { $EngineBase = 'http://127.0.0.1:8081' }

function Port-Ok {
    param([int]$p)
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect('127.0.0.1', $p, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(800, $false)) { $c.Close(); return $false }
        $c.EndConnect($iar); $c.Close(); return $true
    }
    catch { return $false }
}

function Http-Code {
    param([string]$Url)
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5 -MaximumRedirection 0 -ErrorAction Stop
        return [int]$r.StatusCode
    }
    catch {
        $resp = $_.Exception.Response
        if ($resp -and $resp.StatusCode) {
            return [int]$resp.StatusCode.value__
        }
        return -1
    }
}

function Curl-Exe {
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) { return $curl.Source }
    return $null
}

Write-Host '=== evolution preflight (Wave2) ===' -ForegroundColor Cyan

$tcp8080 = Port-Ok 8080
$tcp8081 = Port-Ok 8081
Write-Host ('TCP Admin 8080:  ' + $tcp8080)
Write-Host ('TCP Engine 8081: ' + $tcp8081)

if ($ShowPortOwners) {
    Write-Host ''
    Write-Host 'Listening process (Get-NetTCPConnection); needs Windows + appropriate rights:' -ForegroundColor DarkYellow
    foreach ($port in 8080, 8081) {
        try {
            $row = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($row -and $row.OwningProcess) {
                $pn = '(unknown)'
                $p = Get-Process -Id $row.OwningProcess -ErrorAction SilentlyContinue
                if ($p) { $pn = $p.ProcessName }
                Write-Host ("  :{0} -> PID {1} ({2})" -f $port, $row.OwningProcess, $pn)
            } else {
                Write-Host ("  :{0} -> (no listener)" -f $port)
            }
        } catch {
            Write-Host ("  :{0} -> (could not query)" -f $port)
        }
    }
}

$curlBin = Curl-Exe
if ($curlBin) {
    $adminCode = & $curlBin -sS -o NUL -w '%{http_code}' "$AdminBase/" 2>$null
    if (-not $adminCode) { $adminCode = 'ERR' }
    Write-Host ("HTTP GET $AdminBase/ -> $adminCode")
}
else {
    $code = Http-Code "$AdminBase/"
    Write-Host ("HTTP GET $AdminBase/ -> $code (Invoke-WebRequest)")
}

if ($curlBin) {
    $engUrl = "$EngineBase/api/monitor/health"
    $engCode = & $curlBin -sS -o NUL -w '%{http_code}' $engUrl 2>$null
    if (-not $engCode) { $engCode = 'ERR' }
    Write-Host ("HTTP GET $engUrl -> $engCode")
}
else {
    $code = Http-Code "$EngineBase/api/monitor/health"
    Write-Host ("HTTP GET $EngineBase/api/monitor/health -> $code")
}

Write-Host ''
Write-Host 'Evolution-related env (avoid stale EVOLUTION_* / SOURCE_EXPORT_* between runs):' -ForegroundColor Yellow
$keys = @('ADMIN_BASE','ENGINE_BASE','ADMIN_USER','ADMIN_PASS','ENGINE_ID','USER_ID','IMPORT_TIMEOUT','EVOLUTION_ZIP','SOURCE_EXPORT_MODE','SOURCE_EXPORT_ROWS_FILE','QYWX_WORKFLOW_LIFECYCLE','QYWX_WORKFLOW_CLEANUP_CREATOR','QYWX_SKIP_TEMP_WORKFLOW_CLEANUP')
foreach ($k in $keys) {
    $v = [Environment]::GetEnvironmentVariable($k, 'Process')
    if (-not $v) { $v = '(unset)' }
    Write-Host ("  ${k}: $v")
}

if ($ClearEvolutionEnv) {
    Remove-Item Env:EVOLUTION_ZIP -ErrorAction SilentlyContinue
    Remove-Item Env:SOURCE_EXPORT_MODE -ErrorAction SilentlyContinue
    Remove-Item Env:SOURCE_EXPORT_ROWS_FILE -ErrorAction SilentlyContinue
    Write-Host 'Cleared session: EVOLUTION_ZIP, SOURCE_EXPORT_MODE, SOURCE_EXPORT_ROWS_FILE' -ForegroundColor Green
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$adminJar = Join-Path $repoRoot 'WxFbsir-admin\target\WxFbsir-admin.jar'
if (Test-Path $adminJar) {
    Write-Host ''
    Write-Host 'Tip: if mvn package fails on locked JAR, stop the process listening on 8080 first.' -ForegroundColor DarkYellow
}

Write-Host ''
Write-Host 'Note: Engine HTTP 200 does not imply WS registered; IMPORT may still return ENGINE_OFFLINE.' -ForegroundColor DarkCyan

if ($StrictImport) {
    $ok = $tcp8080 -and $tcp8081
    if (-not $ok) {
        Write-Host 'FAIL: StrictImport requires TCP 8080 and 8081.' -ForegroundColor Red
        exit 1
    }
    $hc = if ($curlBin) { & $curlBin -sS -o NUL -w '%{http_code}' "$EngineBase/api/monitor/health" 2>$null } else { '' }
    if ($hc -ne '200') {
        Write-Host "FAIL: StrictImport needs Engine health HTTP 200, got $hc" -ForegroundColor Red
        exit 1
    }
}

Write-Host 'preflight done.' -ForegroundColor Green
exit 0
