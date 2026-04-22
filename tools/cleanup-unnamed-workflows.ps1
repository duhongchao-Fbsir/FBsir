# 删除企微后台「测试遗留」的默认名工作流（需 Engine 在线且已扫码登录同一 userId）
# 安全约束：必须指定 -Creator（创建人列子串），且仅匹配标题完全等于 -WorkflowName 的首条（同名同创建人重复时每次删一行，可循环多次）。
# 用法（仓库根）：
#   powershell -File tools/cleanup-unnamed-workflows.ps1 -Creator "杜红超"
#   $env:QYWX_UNNAMED_CREATOR="杜红超"; powershell -File tools/cleanup-unnamed-workflows.ps1
# 若控制台传中文乱码，用 UTF-8 无 BOM 文件：-CreatorFile tools/out/qywx-creator-cleanup.txt
param(
    [string]$AdminBase = $env:ADMIN_BASE,
    [string]$EngineId = $env:ENGINE_ID,
    [string]$UserId = $env:USER_ID,
    [string]$AdminUser = $env:ADMIN_USER,
    [string]$AdminPass = $env:ADMIN_PASS,
    [string]$Creator = $env:QYWX_UNNAMED_CREATOR,
    [string]$CreatorFile,
    [string]$WorkflowName,
    [int]$MaxDeletes = 30,
    [int]$CooldownSec = 4,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
if (-not $AdminBase) { $AdminBase = 'http://127.0.0.1:8080' }
if (-not $EngineId) { $EngineId = 'engine-dev-001' }
if (-not $UserId) { $UserId = '1' }
if (-not $AdminUser) { $AdminUser = 'admin' }
if (-not $AdminPass) { $AdminPass = 'admin123' }

if ($CreatorFile) {
    $p = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($CreatorFile)
    if (-not (Test-Path -LiteralPath $p)) { Write-Error "CreatorFile not found: $p" }
    $Creator = [System.IO.File]::ReadAllText($p, [System.Text.UTF8Encoding]::new($false)).Trim()
}
if ([string]::IsNullOrWhiteSpace($Creator)) {
    Write-Error '必须指定 -Creator / -CreatorFile / QYWX_UNNAMED_CREATOR（创建人列子串），以免误删他人工作流。'
}
if ([string]::IsNullOrWhiteSpace($WorkflowName)) {
    # PS5 无 BOM 时默认中文参数会乱码，故用码点拼接「未命名工作流」
    $WorkflowName = -join ([char[]](0x672A, 0x547D, 0x540D, 0x5DE5, 0x4F5C, 0x6D41))
}

function Login-Token {
    $loginBody = @{ username = $AdminUser; password = $AdminPass; code = ''; uuid = '' } | ConvertTo-Json -Compress
    $r = Invoke-RestMethod -Uri "$AdminBase/login" -Method POST `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($loginBody)) `
        -ContentType 'application/json; charset=utf-8'
    if (-not $r.token) { throw "login_failed: $($loginBody.Substring(0, [Math]::Min(80, $loginBody.Length)))..." }
    return [string]$r.token
}

function Invoke-DeleteRaw {
    param([string]$Token)
    $reqObj = @{
        engineId = $EngineId
        type     = 'QYWEIXIN_WORKFLOW_DELETE'
        userId   = $UserId
        timeout  = 120
        payload  = @{
            workflowName    = $WorkflowName
            workflowCreator = $Creator
        }
    }
    $json = $reqObj | ConvertTo-Json -Compress -Depth 8
    return Invoke-RestMethod -Uri "$AdminBase/ws/engine/request" -Method POST `
        -Headers @{ Authorization = "Bearer $Token" } `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) `
        -ContentType 'application/json; charset=utf-8'
}

Write-Host "=== cleanup unnamed workflows ===" -ForegroundColor Cyan
Write-Host "Admin=$AdminBase EngineId=$EngineId UserId=$UserId"
Write-Host "Match title='$WorkflowName' creator contains '$Creator'"
if ($DryRun) {
    Write-Host 'DryRun: no HTTP calls.' -ForegroundColor Yellow
    exit 0
}

$token = Login-Token
$okCount = 0
for ($i = 1; $i -le $MaxDeletes; $i++) {
    $resp = Invoke-DeleteRaw -Token $token
    $raw = $resp | ConvertTo-Json -Depth 8 -Compress
    # HTTP: { success, data: enginePayload, executionTime }; enginePayload = { success, data: biz, errorMessage? }
    if ($resp.success -ne $true) {
        Write-Host "[#$i] FAIL admin $($resp.errorCode) $($resp.errorMessage)" -ForegroundColor Red
        Write-Host $raw
        exit 3
    }
    $payload = $resp.data
    $biz = $payload.data
    if ($payload.success -eq $true -and $biz) {
        $dn = $biz.deletedName
        $rem = $biz.remainCount
        Write-Host "[#$i] deleted OK name=$dn remain=$rem" -ForegroundColor Green
        $okCount++
        Start-Sleep -Seconds $CooldownSec
        continue
    }
    $msg = [string]$payload.errorMessage
    $code = [string]$payload.errorCode
    $nf = ([char]0x672a).ToString() + ([char]0x627e).ToString() + ([char]0x5230).ToString() + ([char]0x76ee).ToString() + ([char]0x6807).ToString()
    $emptyList = ([char]0x5217).ToString() + ([char]0x8868).ToString() + ([char]0x4e3a).ToString() + ([char]0x7a7a).ToString()
    if ($msg -like "*${nf}*" -or $msg -like "*${emptyList}*") {
        Write-Host "[#$i] stop (no more rows): $msg" -ForegroundColor DarkYellow
        break
    }
    # Engine: TASK_ERROR often = 未找到「删除」入口（控制台可能对中文显示乱码，用 Unicode 字符检测）
    if ($code -eq 'TASK_ERROR' -and ($msg.IndexOf(([char]0x5220)) -ge 0) -and ($msg.IndexOf(([char]0x9664)) -ge 0)) {
        Write-Host '[#] automation could not click Delete in row menu; use WeCom console: row ... menu -> Delete, only rows that match title+creator safety rule.' -ForegroundColor Yellow
        Write-Host $raw
        exit 4
    }
    Write-Host "[#$i] FAIL code=$code msg=$msg" -ForegroundColor Red
    Write-Host $raw
    exit 3
}

Write-Host "=== done: removed $okCount row(s) ===" -ForegroundColor Green
exit 0
