# 使用本仓库初始化过的数据目录启动 MySQL（监听 3306，root 密码 root，与 application-druid.yml 默认一致）。
# 若 3306 已有实例在运行则直接退出。
$ErrorActionPreference = "Stop"
$mysqld = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe"
$dataDir = Join-Path (Split-Path $PSScriptRoot -Parent) ".dev\mysql-data"
if (-not (Test-Path $dataDir)) {
    Write-Error "数据目录不存在: $dataDir 。请先在本机执行过一次 MySQL 初始化与导入，或联系维护者。"
    exit 1
}
if (-not (Test-Path $mysqld)) {
    Write-Error "未找到 mysqld: $mysqld 。请确认已安装 MySQL Server 8.4。"
    exit 1
}
$tcp = Test-NetConnection -ComputerName 127.0.0.1 -Port 3306 -WarningAction SilentlyContinue
if ($tcp.TcpTestSucceeded) {
    Write-Host "MySQL 已在 127.0.0.1:3306 监听，跳过启动。"
    exit 0
}
Write-Host "正在启动 MySQL（datadir=$dataDir）..."
Start-Process -FilePath $mysqld -ArgumentList "--datadir=$dataDir", "--port=3306" -WindowStyle Hidden
Start-Sleep -Seconds 3
$tcp2 = Test-NetConnection -ComputerName 127.0.0.1 -Port 3306 -WarningAction SilentlyContinue
if (-not $tcp2.TcpTestSucceeded) {
    Write-Error "启动后仍无法连接 3306，请检查是否被防火墙或其它 mysqld 占用。"
    exit 1
}
Write-Host "MySQL 已启动。"
