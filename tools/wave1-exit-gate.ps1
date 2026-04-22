# Wave1 收口门禁封装，见 docs/workflows/skill-evolution-roadmap.md §1.5
param(
    [switch]$PreviewOnly
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$nodeExe = "C:\Program Files\nodejs\node.exe"
if (-not (Test-Path -LiteralPath $nodeExe)) {
    $nodeExe = "node.exe"
}

$args = @("tools/wave1-exit-gate.mjs")
if ($PreviewOnly) {
    $args += "--preview-only"
}

& $nodeExe @args
exit $LASTEXITCODE
