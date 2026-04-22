# 一键：必要时启动 Docker 中的 MySQL/Redis，再启动主节点（WxFbsir-admin）
# 默认以可执行 JAR 启动（与生产/常见部署一致）；未找到 JAR 时可用 Maven 运行源码。
param(
    [switch]$UseMavenRun
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$repoRoot = Split-Path -Parent $PSScriptRoot
$adminDir = Join-Path $repoRoot 'WxFbsir-admin'
$defaultJar = Join-Path $adminDir 'target\WxFbsir-admin.jar'

function Test-TcpPortOpen {
    param([string]$HostName, [int]$Port, [int]$TimeoutMs = 800)
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect($HostName, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) {
            $c.Close()
            return $false
        }
        $c.EndConnect($iar)
        $c.Close()
        return $true
    } catch {
        return $false
    }
}

$skipDeps = ($env:WXFBSIR_SKIP_LOCAL_DEPS -eq '1')
if (-not $skipDeps -and ((-not (Test-TcpPortOpen -HostName '127.0.0.1' -Port 3306)) -or (-not (Test-TcpPortOpen -HostName '127.0.0.1' -Port 6379)))) {
    $depsScript = Join-Path $repoRoot 'tools\start-local-deps.ps1'
    if (Test-Path $depsScript) {
        Write-Host "本地 3306/6379 未就绪，尝试启动 Docker 依赖..." -ForegroundColor Yellow
        & $depsScript
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } else {
        Write-Error "MySQL(3306) 或 Redis(6379) 不可用，且未找到 tools\start-local-deps.ps1。"
        exit 1
    }
}

$javaExe = $null
if (Get-Command java -ErrorAction SilentlyContinue) {
    $javaExe = (Get-Command java).Source
}
if (-not $javaExe) {
    Write-Error '未找到 java 命令，请安装 JDK 17+ 并加入 PATH。'
    exit 1
}

. (Join-Path $PSScriptRoot 'Resolve-Maven.ps1')
$mvnExe = Get-MavenExecutable -RepoRoot $repoRoot

$jarPath = $null
if ($env:WXFBSIR_ADMIN_JAR -and (Test-Path -LiteralPath $env:WXFBSIR_ADMIN_JAR)) {
    $jarPath = $env:WXFBSIR_ADMIN_JAR
} elseif (Test-Path -LiteralPath $defaultJar) {
    $jarPath = $defaultJar
}

$useMaven = $UseMavenRun -or ($env:WXFBSIR_USE_MAVEN_RUN -eq '1')
if ($useMaven -or (-not $jarPath)) {
    if (-not $mvnExe) {
        Write-Error '未找到 WxFbsir-admin.jar，且未找到 mvn。请先执行: mvn -pl WxFbsir-admin -am package -DskipTests，或设置 WXFBSIR_ADMIN_JAR 指向已构建的 jar。'
        exit 1
    }
    Set-Location $adminDir
    Write-Host "== spring-boot:run WxFbsir-admin ($mvnExe) ==" -ForegroundColor Cyan
    & $mvnExe spring-boot:run '-DskipTests'
    exit $LASTEXITCODE
}

$jvmExtra = @()
if ($env:WXFBSIR_JVM_OPTS) {
    $jvmExtra = $env:WXFBSIR_JVM_OPTS -split '\s+' | Where-Object { $_ }
} else {
    $jvmExtra = @('-Dfile.encoding=UTF-8', '-Dsun.jnu.encoding=UTF-8')
}

Write-Host "== java -jar WxFbsir-admin ($jarPath) ==" -ForegroundColor Cyan
Write-Host "Java: $javaExe" -ForegroundColor DarkGray
Set-Location $adminDir
& $javaExe @jvmExtra -jar $jarPath
exit $LASTEXITCODE
