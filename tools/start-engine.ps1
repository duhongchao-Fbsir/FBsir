# 启动 Engine 副节点（可执行 JAR）。需主节点 Admin 已启动且 ws-url 可达。
# 默认 jar: WxFbsir-engine\target\wxfbsir-engine-<version>.jar，或通过 WXFBSIR_ENGINE_JAR 指定。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$repoRoot = Split-Path -Parent $PSScriptRoot
$engineDir = Join-Path $repoRoot 'WxFbsir-engine'

$javaExe = $null
if (Get-Command java -ErrorAction SilentlyContinue) {
    $javaExe = (Get-Command java).Source
}
if (-not $javaExe) {
    Write-Error '未找到 java 命令，请安装 JDK 17+ 并加入 PATH。'
    exit 1
}

$jarPath = $null
if ($env:WXFBSIR_ENGINE_JAR -and (Test-Path -LiteralPath $env:WXFBSIR_ENGINE_JAR)) {
    $jarPath = $env:WXFBSIR_ENGINE_JAR
} else {
    $candidates = Get-ChildItem -Path (Join-Path $engineDir 'target') -Filter 'wxfbsir-engine-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'original|sources|javadoc' } |
        Sort-Object LastWriteTime -Descending
    if ($candidates) {
        $jarPath = $candidates[0].FullName
    }
}

if (-not $jarPath) {
    Write-Error @"
未找到 Engine 可执行 JAR。请在 WxFbsir-engine 目录执行:
  mvn -DskipTests package
或设置环境变量 WXFBSIR_ENGINE_JAR 指向已构建的 wxfbsir-engine-*.jar
"@
    exit 1
}

$jvmExtra = @()
if ($env:WXFBSIR_ENGINE_JVM_OPTS) {
    $jvmExtra = $env:WXFBSIR_ENGINE_JVM_OPTS -split '\s+' | Where-Object { $_ }
} else {
    $jvmExtra = @('-Dfile.encoding=UTF-8', '-Dsun.jnu.encoding=UTF-8')
}

Write-Host "== java -jar Engine ($jarPath) ==" -ForegroundColor Cyan
Write-Host "Java: $javaExe" -ForegroundColor DarkGray
Set-Location $engineDir
& $javaExe @jvmExtra -jar $jarPath
exit $LASTEXITCODE
