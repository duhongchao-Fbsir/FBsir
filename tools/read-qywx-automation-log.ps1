# 查看 Engine 企微工作流自动化落盘日志（JSON Lines），默认跟新行（Ctrl+C 结束）
param(
    [string]$Path = "",
    [int]$Last = 20,
    [switch]$Follow
)
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$defaultPath = Join-Path $repoRoot "WxFbsir-engine\logs\qyweixin-automation.jsonl"
$p = if ($Path) { $Path } else { $defaultPath }
if (-not (Test-Path -LiteralPath $p)) {
    Write-Host "文件不存在: $p" -ForegroundColor Yellow
    Write-Host "请先在实验页执行录入/诊断，或检查 application.yml 中 wxfbsir.engine.qyweixin.automation-log-file" -ForegroundColor Gray
    exit 1
}
if ($Follow) {
    Get-Content -LiteralPath $p -Tail $Last -Wait -Encoding UTF8
} else {
    Get-Content -LiteralPath $p -Tail $Last -Encoding UTF8
}
