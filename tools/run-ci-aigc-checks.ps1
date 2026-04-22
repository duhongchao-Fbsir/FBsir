# 全自动：业务模块单测 + 前端能力矩阵校验 + 生产构建（仓库根执行）
# 依赖：JDK 17+、Maven（mvn 在 PATH 中）
# 说明：本脚本仅覆盖根聚合内的 WxFbsir-business（及 -am 依赖链）与 WxFbsir-ui。
#       WxFbsir-engine 不在根 pom 的 reactor 中，属主节点/副节点分轨的既定设计；
#       Engine 的 test/package 请在 WxFbsir-engine 目录单独执行，勿将「本脚本未跑 Engine」视为疏漏。
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

. (Join-Path $PSScriptRoot 'Resolve-Maven.ps1')
$mvnExe = Get-MavenExecutable -RepoRoot $repo
if (-not $mvnExe) {
    Write-Error '未找到 mvn：可将 Apache Maven 加入 PATH，设置 MAVEN_HOME，设置 WXFBSIR_MVN，或放置便携包于 .tools\maven-extract\ 或 build-tools\'
    exit 1
}

Write-Host "== Maven WxFbsir-business tests ($mvnExe) ==" -ForegroundColor Cyan
& $mvnExe -pl WxFbsir-business -am -DskipTests=false test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host '== forbid AiResultHandler regression (business main) ==' -ForegroundColor Cyan
$bizJava = Join-Path $repo 'WxFbsir-business\src\main\java'
$bad = Get-ChildItem -Path $bizJava -Filter '*.java' -Recurse -ErrorAction SilentlyContinue |
    Where-Object { Select-String -Path $_.FullName -Pattern 'AiResultHandler' -Quiet }
if ($bad) {
    Write-Error 'Forbidden: AiResultHandler referenced under WxFbsir-business/src/main/java'
    exit 1
}

$ui = Join-Path $repo 'WxFbsir-ui'
Set-Location $ui

Write-Host '== npm test:aigc-mapper ==' -ForegroundColor Cyan
& npm run test:aigc-mapper
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host '== npm build:prod ==' -ForegroundColor Cyan
& npm run build:prod
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host 'run-ci-aigc-checks: OK' -ForegroundColor Green
