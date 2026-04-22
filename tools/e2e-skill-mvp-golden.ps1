param(
    [string]$AdminBase = "http://127.0.0.1:8080",
    [string]$EngineId = "engine-dev-001",
    [string]$UserId = "1",
    [string]$Username = "admin",
    [string]$Password = "admin123",
    [switch]$RunImport
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$zipPath = Join-Path $repoRoot "tools\out\skill-mvp-golden.zip"

if (-not (Test-Path -LiteralPath $zipPath)) {
    & (Join-Path $repoRoot "tools\pack-skill-mvp-golden.ps1")
}

Write-Host "1) Login admin..."
$loginBody = @{
    username = $Username
    password = $Password
    code = ""
    uuid = ""
} | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Uri "$AdminBase/login" -Method POST -Body $loginBody -ContentType "application/json"
if (-not $login.token) {
    throw "Login failed: token missing."
}
$headers = @{ Authorization = "Bearer $($login.token)" }
$jsonHeaders = @{
    Authorization = "Bearer $($login.token)"
    "Content-Type" = "application/json"
}

Write-Host "2) Upload skill for preview..."
$token = $login.token
$previewRaw = & curl.exe -sS -X POST "$AdminBase/ws/admin/skill/preview" -H "Authorization: Bearer $token" -F "file=@$zipPath"
if (-not $previewRaw) {
    throw "Preview request returned empty response."
}
$previewRaw
Set-Content -LiteralPath (Join-Path $repoRoot "tools\out\skill-mvp-golden-preview.json") -Value $previewRaw -Encoding UTF8

if (-not $RunImport) {
    Write-Host "Preview finished. Saved raw JSON to tools/out/skill-mvp-golden-preview.json."
    exit 0
}

Write-Host "RunImport requires extracting workflowDraft from preview JSON."
Write-Host "Please use UI or parse tools/out/skill-mvp-golden-preview.json then call /ws/engine/request manually."

