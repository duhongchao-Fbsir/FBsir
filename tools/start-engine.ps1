# Start WxFbsir Engine (Admin :8080 and ws_host_whitelist host-id must be ready first)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not $root) { $root = "D:\u3wv2" }
$jar = Join-Path $root "WxFbsir-engine\target\wxfbsir-engine-1.3.1.jar"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot"
if (-not (Test-Path $env:JAVA_HOME)) {
    Write-Error "JAVA_HOME not found: $env:JAVA_HOME"
    exit 1
}
if (-not (Test-Path $jar)) {
    Write-Error "Engine JAR not found: $jar (run: mvn package -DskipTests in WxFbsir-engine)"
    exit 1
}

# Mojibake fix: Logback writes UTF-8; Windows CMD uses CP936 by default -> set console to UTF-8 (65001)
if ($IsWindows -or $env:OS -match "Windows") {
    try {
        if (-not ("Fbsir.Win32ConsoleUtf8" -as [type])) {
            Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
namespace Fbsir {
  public static class Win32ConsoleUtf8 {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool SetConsoleOutputCP(uint wCodePageID);
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool SetConsoleCP(uint wCodePageID);
  }
}
"@ -ErrorAction Stop
        }
        [void][Fbsir.Win32ConsoleUtf8]::SetConsoleOutputCP(65001)
        [void][Fbsir.Win32ConsoleUtf8]::SetConsoleCP(65001)
    } catch {}
    try {
        $utf8 = [System.Text.UTF8Encoding]::new($false)
        [Console]::OutputEncoding = $utf8
        [Console]::InputEncoding = $utf8
    } catch {}
}

$engineDir = Join-Path $root "WxFbsir-engine"
Set-Location $engineDir
Write-Host "Work dir: $engineDir"
Write-Host "Starting: $jar"
$java = Join-Path $env:JAVA_HOME "bin\java.exe"
& $java `
    "-Dfile.encoding=UTF-8" `
    "-Dsun.jnu.encoding=UTF-8" `
    -jar $jar
