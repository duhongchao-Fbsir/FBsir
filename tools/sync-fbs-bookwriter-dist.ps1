# 将本机构建的 Workbuddy 分发包复制到仓库 tools/out，便于预览/脚本固定路径。
# 默认源路径可按需修改或通过参数传入。
param(
    [string]$SourceZip = "D:\210\FBS-BookWriter\dist\fbs-bookwriter-v213-workbuddy.zip",
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) {
    $OutDir = Join-Path $repoRoot "tools\out"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
if (-not (Test-Path -LiteralPath $SourceZip)) {
    throw "找不到技能包: $SourceZip"
}
$dest = Join-Path $OutDir "fbs-bookwriter-workbuddy-latest.zip"
Copy-Item -LiteralPath $SourceZip -Destination $dest -Force
Write-Host "已同步: $dest"
