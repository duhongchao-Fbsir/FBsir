# 端到端：通用上传(/common/upload) -> WebSocket AIGC -> Engine 下载并送入各平台 -> 校验回复是否包含文件内固定标记
# 依赖：Admin 8080、Engine 已注册、各平台已登录（与 e2e-aigc-smoke 相同）
# 默认样本：仓库内 tools/e2e-assets/e2e-file-understanding.txt（可用 .txt / .png 等 DEFAULT_ALLOWED_EXTENSION）
param(
    [string]$Base = "http://127.0.0.1:8080",
    [string]$LocalFile = "",
    [int]$DeadlineSeconds = 300
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($LocalFile)) {
    $LocalFile = Join-Path $PSScriptRoot "e2e-assets\e2e-file-understanding.txt"
}
if (-not (Test-Path -LiteralPath $LocalFile)) {
    Write-Error "Sample file not found: $LocalFile"
    exit 1
}

# 从样本文件首行读取必现标记（用于断言）
$firstLine = (Get-Content -LiteralPath $LocalFile -TotalCount 1 -Encoding UTF8)
if ([string]::IsNullOrWhiteSpace($firstLine)) {
    Write-Error "Sample file first line empty: $LocalFile"
    exit 1
}
$marker = $firstLine.Trim()
Write-Host "MarkerLine: $marker" -ForegroundColor DarkGray

$engineId = "engine-dev-001"
$chatId = "e2e-file-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)

$loginBody = '{"username":"admin","password":"admin123","code":"","uuid":""}'
$login = Invoke-RestMethod -Uri "$Base/login" -Method Post -ContentType "application/json" -Body $loginBody
$token = $login.token
if ([string]::IsNullOrWhiteSpace($token)) {
    throw "Login failed: empty token"
}

# curl 上传（UTF-8 文件名友好）
$uploadRespRaw = & curl.exe -s -X POST "$Base/common/upload" -H "Authorization: Bearer $token" -F "file=@$LocalFile"
if ([string]::IsNullOrWhiteSpace($uploadRespRaw)) {
    throw "Upload failed: empty response"
}
$uploadResp = $uploadRespRaw | ConvertFrom-Json
if ($uploadResp.code -ne 200) {
    throw "Upload failed: $($uploadResp | ConvertTo-Json -Compress)"
}
$uploadedFileUrl = [string]$uploadResp.url
if ([string]::IsNullOrWhiteSpace($uploadedFileUrl)) {
    throw "Upload failed: missing url. Response: $uploadRespRaw"
}
Write-Host "UploadedFileUrl: $uploadedFileUrl" -ForegroundColor Cyan

$encToken = [System.Uri]::EscapeDataString($token)
$bu = [Uri]$Base
$wsScheme = if ($bu.Scheme -eq 'https') { 'wss' } else { 'ws' }
$wsUri = "${wsScheme}://$($bu.Authority)/ws/client?clientType=web&token=$encToken"

# 全量四路（与 e2e-aigc-smoke 对齐）；请求中写明标记便于质量门禁与模型对齐
$ais = @(
    @{ id = "deepseek"; type = "AI_DEEPSEEK_QUERY" },
    @{ id = "doubao";   type = "AI_DOUBAO_QUERY" },
    @{ id = "qianwen";  type = "AI_QIANWEN_QUERY" },
    @{ id = "yuanbao";  type = "AI_YUANBAO_QUERY" }
)

$prompt = "A file has been uploaded. Please read the file content and include the FIRST LINE exactly as-is in your reply (must keep the prefix WXFBSIR_E2E_MARKER=). Then briefly confirm whether you successfully read the uploaded file. Required first-line marker: $marker"

# WebSocket 正文须为 UTF-8；PowerShell Core 7+ 可用 EscapeNonAscii，避免部分环境下非 ASCII 在 JSON 中的编码歧义
function Serialize-AigcWsPayload {
    param([Parameter(Mandatory = $true)] [object]$Obj)
    $depth = 25
    if ($PSVersionTable.PSEdition -eq 'Core') {
        try {
            return ($Obj | ConvertTo-Json -Depth $depth -Compress -EscapeHandling EscapeNonAscii)
        } catch {
            return ($Obj | ConvertTo-Json -Depth $depth -Compress)
        }
    }
    return ($Obj | ConvertTo-Json -Depth $depth -Compress)
}

function Receive-OneWsTextMessage {
    param(
        [System.Net.WebSockets.ClientWebSocket]$Socket,
        [byte[]]$Buffer,
        [DateTime]$DeadlineUtc
    )
    $seg = New-Object System.ArraySegment[byte] -ArgumentList @(,$Buffer)
    $acc = ""
    while ([DateTime]::UtcNow -lt $DeadlineUtc) {
        $remainMs = [int]([Math]::Max(500, ($DeadlineUtc - [DateTime]::UtcNow).TotalMilliseconds))
        if ($remainMs -gt 60000) { $remainMs = 60000 }
        $recvCts = [System.Threading.CancellationTokenSource]::new()
        try {
            $recvCts.CancelAfter($remainMs)
            $recv = $Socket.ReceiveAsync($seg, $recvCts.Token).GetAwaiter().GetResult()
        } catch {
            return $null
        } finally {
            $recvCts.Dispose()
        }
        if ($recv.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { return $null }
        if ($recv.Count -gt 0) {
            $acc += [System.Text.Encoding]::UTF8.GetString($Buffer, 0, $recv.Count)
        }
        if ($recv.EndOfMessage) { return $acc }
    }
    return $null
}

function Get-AnswerText {
    param($Msg)
    if ($null -eq $Msg -or $null -eq $Msg.payload) { return "" }
    $data = $Msg.payload.data
    if ($null -eq $data) { return "" }
    $txt = ""
    if ($data -is [string]) {
        try {
            $dataObj = $data | ConvertFrom-Json
            if ($dataObj.textContent) { $txt = [string]$dataObj.textContent }
            if ([string]::IsNullOrWhiteSpace($txt) -and $dataObj.answer) { $txt = [string]$dataObj.answer }
        } catch {
            $txt = [string]$data
        }
    } else {
        if ($data.textContent) { $txt = [string]$data.textContent }
        if ([string]::IsNullOrWhiteSpace($txt) -and $data.answer) { $txt = [string]$data.answer }
    }
    return $txt
}

function Test-MarkerPresent {
    param([string]$Text, [string]$Required)
    if ([string]::IsNullOrWhiteSpace($Text) -or [string]::IsNullOrWhiteSpace($Required)) { return $false }
    return $Text.Contains($Required)
}

$results = @()

foreach ($ai in $ais) {
    $deadlineUtc = [DateTime]::UtcNow.AddSeconds($DeadlineSeconds)
    $sessionId = [Guid]::NewGuid().ToString()
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $ct = New-Object System.Threading.CancellationToken
    $null = $ws.ConnectAsync([Uri]$wsUri, $ct).Wait(20000)
    if ($ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) {
        $results += [pscustomobject]@{
            AI = $ai.id; Outcome = "WS_FAIL"; MarkerHit = $false; Detail = "WebSocket not open"
        }
        continue
    }

    $buf = New-Object byte[] 786432
    $null = Receive-OneWsTextMessage -Socket $ws -Buffer $buf -DeadlineUtc ([DateTime]::UtcNow.AddSeconds(20))

    $payload = @{
        sessionId = $sessionId
        chatId = $chatId
        aiType = $ai.id
        query = $prompt
        userPrompt = $prompt
        isNewChat = $true
        enableFileUpload = $true
        uploadedFileUrl = $uploadedFileUrl
    }

    $msgObj = @{
        type = $ai.type
        engineId = $engineId
        chatId = $chatId
        payload = $payload
    }
    $msg = Serialize-AigcWsPayload -Obj $msgObj
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($msg)
    $seg = New-Object System.ArraySegment[byte] -ArgumentList @(,$bytes)
    $null = $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait(60000)

    $outcome = "TIMEOUT"
    $detail = ""
    $answer = ""
    while ([DateTime]::UtcNow -lt $deadlineUtc) {
        $txt = Receive-OneWsTextMessage -Socket $ws -Buffer $buf -DeadlineUtc $deadlineUtc
        if ($null -eq $txt) { continue }
        try { $j = $txt | ConvertFrom-Json } catch { continue }
        $t = [string]$j.type
        if ($t -ne "AI_TASK_RESULT" -and $t -ne "AI_TASK_ERROR") { continue }
        $psid = $null
        if ($j.payload) {
            $psid = $j.payload.sessionId
            if (-not $psid -and $j.payload.payload) { $psid = $j.payload.payload.sessionId }
        }
        if ($psid -and $psid -ne $sessionId) { continue }

        if ($t -eq "AI_TASK_RESULT") {
            if ($j.payload.success -eq $true) {
                $outcome = "RESULT_OK"
                $answer = Get-AnswerText -Msg $j
            } else {
                $outcome = "RESULT_FAIL"
                $detail = "success=false"
            }
        } else {
            $outcome = "TASK_ERROR"
            $detail = [string]$j.payload.errorMessage
        }
        break
    }

    $hit = Test-MarkerPresent -Text $answer -Required $marker
    $snippet = ""
    if (-not [string]::IsNullOrWhiteSpace($answer)) {
        $snippet = $answer.Substring(0, [Math]::Min(200, $answer.Length)).Replace("`r", " ").Replace("`n", " ")
    }

    $results += [pscustomobject]@{
        AI = $ai.id
        Outcome = $outcome
        MarkerHit = $hit
        Detail = $detail
        Snippet = $snippet
    }

    try {
        if ($ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
            $null = $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "", $ct).Wait(5000)
        }
    } catch { }
    Start-Sleep -Seconds 1
}

Write-Host ""
Write-Host "===== FILE UPLOAD / UNDERSTANDING SUMMARY =====" -ForegroundColor Green
$results | Format-Table -AutoSize
$ok = @($results | Where-Object { $_.Outcome -eq 'RESULT_OK' -and $_.MarkerHit -eq $true }).Count
$total = $results.Count
Write-Host "--- PASS (RESULT_OK + marker in answer): $ok / $total ---"

if ($ok -lt $total) {
    exit 1
}
exit 0
