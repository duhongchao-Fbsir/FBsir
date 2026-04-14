# 启动 OpenClaw Gateway（默认端口 18789，与种子数据 host_id=test 的健康检查 URL 一致）
# 需已全局安装: npm install -g openclaw@latest
# 首次使用可运行: openclaw setup
$ErrorActionPreference = "Stop"
$port = 18789
$tcp = Test-NetConnection -ComputerName 127.0.0.1 -Port $port -WarningAction SilentlyContinue
if ($tcp.TcpTestSucceeded) {
    Write-Host "端口 $port 已被占用，假定 Gateway 已在运行。"
    exit 0
}
Write-Host "正在启动 OpenClaw Gateway (端口 $port)..."
$cmd = Get-Command openclaw -ErrorAction SilentlyContinue
if (-not $cmd) {
    Write-Error "未找到 openclaw 命令。请执行: npm install -g openclaw@latest"
    exit 1
}
Start-Process -FilePath "openclaw" -ArgumentList "gateway","--port",$port -WindowStyle Normal
Start-Sleep -Seconds 5
$tcp2 = Test-NetConnection -ComputerName 127.0.0.1 -Port $port -WarningAction SilentlyContinue
if (-not $tcp2.TcpTestSucceeded) {
    Write-Warning "端口 $port 尚未监听，请查看新开的 Gateway 窗口日志。"
    exit 1
}
Write-Host "Gateway 已监听 $port。健康检查可访问: http://127.0.0.1:${port}/health"
