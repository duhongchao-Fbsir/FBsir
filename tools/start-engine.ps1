# Engine JAR launcher. Optional second instance: -HttpPort -HostId -PlaywrightDataDir (see start-second-engine.ps1)
param(
    [int]$HttpPort = 0,
    [string]$HostId = "",
    [string]$PlaywrightDataDir = "",
    [switch]$Background,
    [string]$StdOutLog = "",
    [string]$StdErrLog = ""
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$repoRoot = Split-Path -Parent $PSScriptRoot
$engineDir = Join-Path $repoRoot "WxFbsir-engine"

$javaExe = $null
if (Get-Command java -ErrorAction SilentlyContinue) {
    $javaExe = (Get-Command java).Source
}
if (-not $javaExe) {
    Write-Error "JAVA_NOT_FOUND: Install JDK 17+ and add java to PATH."
    exit 1
}

$jarPath = $null
if ($env:WXFBSIR_ENGINE_JAR -and (Test-Path -LiteralPath $env:WXFBSIR_ENGINE_JAR)) {
    $jarPath = $env:WXFBSIR_ENGINE_JAR
} else {
    $candidates = Get-ChildItem -Path (Join-Path $engineDir "target") -Filter "wxfbsir-engine-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "original|sources|javadoc" } |
        Sort-Object LastWriteTime -Descending
    if ($candidates) {
        $jarPath = $candidates[0].FullName
    }
}

if (-not $jarPath) {
    Write-Error "No Engine JAR in WxFbsir-engine/target. Run: mvn -DskipTests package (from WxFbsir-engine), or set WXFBSIR_ENGINE_JAR."
    exit 1
}

$jvmExtra = @()
if ($env:WXFBSIR_ENGINE_JVM_OPTS) {
    $jvmExtra = $env:WXFBSIR_ENGINE_JVM_OPTS -split "\s+" | Where-Object { $_ }
} else {
    $jvmExtra = @("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}

$springArgs = @()
if ($HttpPort -gt 0) {
    $springArgs += "--server.port=$HttpPort"
}
if (-not [string]::IsNullOrWhiteSpace($HostId)) {
    $springArgs += "--wxfbsir.engine.host-id=$($HostId.Trim())"
}
if (-not [string]::IsNullOrWhiteSpace($PlaywrightDataDir)) {
    $springArgs += "--wxfbsir.engine.playwright.data-dir=$($PlaywrightDataDir.Trim())"
}

Write-Host "== java -jar Engine ($jarPath) ==" -ForegroundColor Cyan
Write-Host "Java: $javaExe" -ForegroundColor DarkGray
if ($springArgs.Length -gt 0) {
    Write-Host ("Overrides: " + ($springArgs -join " ")) -ForegroundColor DarkGray
}
Set-Location $engineDir

if ($Background) {
    if ([string]::IsNullOrWhiteSpace($StdOutLog)) {
        $StdOutLog = Join-Path $engineDir "target\engine-background-out.log"
    }
    if ([string]::IsNullOrWhiteSpace($StdErrLog)) {
        $StdErrLog = Join-Path $engineDir "target\engine-background-err.log"
    }
    $procArgs = [System.Collections.ArrayList]::new()
    foreach ($x in $jvmExtra) { [void]$procArgs.Add($x) }
    [void]$procArgs.Add("-jar")
    [void]$procArgs.Add($jarPath)
    foreach ($x in $springArgs) { [void]$procArgs.Add($x) }
    $p = Start-Process -FilePath $javaExe -ArgumentList @($procArgs.ToArray()) -WorkingDirectory $engineDir -WindowStyle Hidden -RedirectStandardOutput $StdOutLog -RedirectStandardError $StdErrLog -PassThru
    Write-Host "Background PID=$($p.Id)  stdout=$StdOutLog stderr=$StdErrLog" -ForegroundColor Green
    exit 0
}

if ($springArgs.Length -gt 0) {
    & $javaExe @jvmExtra -jar $jarPath @springArgs
} else {
    & $javaExe @jvmExtra -jar $jarPath
}
exit $LASTEXITCODE
