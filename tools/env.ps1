# 在 PowerShell 中执行: . D:\u3wv2\tools\env.ps1
# 为当前会话设置 JDK 17 与本仓库自带的 Maven，便于命令行编译运行。
# MySQL（本机开发数据目录）可先执行: D:\u3wv2\tools\start-mysql.ps1
$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot"
$env:MAVEN_HOME = "D:\u3wv2\tools\apache-maven\apache-maven-3.9.14"
$env:Path = "$env:MAVEN_HOME\bin;$env:JAVA_HOME\bin;" + $env:Path
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "MAVEN_HOME=$env:MAVEN_HOME"
java -version
mvn -version
