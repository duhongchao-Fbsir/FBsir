$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$base = "http://127.0.0.1:8080"
$engineId = "engine-dev-001"
$chatId = "e2e-file-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)

# 用户刚上传的图片（场景魔方）
$localImage = "C:\Users\10171\.cursor\projects\d-U3WV2\assets\c__Users_10171_AppData_Roaming_Cursor_User_workspaceStorage_b9379ff0d6702d84906105734576d62f_images_______-d8ec7805-5148-4088-9fb0-da7ef3300c43.png"
if (-not (Test-Path $localImage)) {
    throw "Image file not found: $localImage"
}

$loginBody = '{"username":"admin","password":"admin123","code":"","uuid":""}'
$login = Invoke-RestMethod -Uri "$base/login" -Method Post -ContentType "application/json" -Body $loginBody
$token = $login.token
if ([string]::IsNullOrWhiteSpace($token)) {
    throw "Login failed: empty token"
}

# 上传图片，拿到可供 Engine 下载的 URL
$uploadRespRaw = & curl.exe -s -X POST "$base/common/upload" -H "Authorization: Bearer $token" -F "file=@$localImage;filename=scene-cube.png"
if ([string]::IsNullOrWhiteSpace($uploadRespRaw)) {
    throw "Upload failed: empty response"
}
$uploadResp = $uploadRespRaw | ConvertFrom-Json
$uploadedFileUrl = [string]$uploadResp.url
if ([string]::IsNullOrWhiteSpace($uploadedFileUrl)) {
    throw "Upload failed: missing url. Response: $uploadRespRaw"
}
Write-Host "UploadedFileUrl: $uploadedFileUrl"

$encToken = [System.Uri]::EscapeDataString($token)
$wsUri = "ws://127.0.0.1:8080/ws/client?clientType=web&token=$encToken"

# 按你的要求先跳过豆包；这里包含 deepseek 作为基准，便于对照“正确性”
$ais = @(
    @{ id = "deepseek"; type = "AI_DEEPSEEK_QUERY" },
    @{ id = "qianwen";  type = "AI_QIANWEN_QUERY" },
    @{ id = "yuanbao";  type = "AI_YUANBAO_QUERY" },
    @{ id = "mita";     type = "AI_MITA_QUERY" },
    @{ id = "gitee";    type = "AI_GITEE_QUERY" }
)

$keywords = @(
    "L1", "L2", "L3", "L4",
    "B2B", "SOP",
    "internal", "customer", "delivery", "consulting", "training", "strategy"
)

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
    if ($data.textContent) { $txt = [string]$data.textContent }
    if ([string]::IsNullOrWhiteSpace($txt) -and $data.answer) { $txt = [string]$data.answer }
    return $txt
}

function Score-Answer {
    param([string]$Text, [string[]]$WordList)
    if ([string]::IsNullOrWhiteSpace($Text)) { return 0 }
    $hits = 0
    foreach ($w in $WordList) {
        if ($Text.Contains($w)) { $hits++ }
    }
    return $hits
}

$results = @()
$prompt = "Identify this image strictly by visible text: output 1) title, 2) 4 vertical levels (L1-L4), 3) 3 horizontal scenarios, 4) key role labels."

foreach ($ai in $ais) {
    $sessionId = [Guid]::NewGuid().ToString()
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $ct = New-Object System.Threading.CancellationToken
    $null = $ws.ConnectAsync([Uri]$wsUri, $ct).Wait(20000)
    if ($ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) {
        $results += [pscustomobject]@{
            AI = $ai.id; Outcome = "WS_FAIL"; Score = 0; Detail = "WebSocket not open"; Snippet = ""
        }
        continue
    }

    $buf = New-Object byte[] 524288
    # 丢弃 CONNECTED
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
    $msg = $msgObj | ConvertTo-Json -Depth 8 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($msg)
    $seg = New-Object System.ArraySegment[byte] -ArgumentList @(,$bytes)
    $null = $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait(30000)

    $deadline = [DateTime]::UtcNow.AddSeconds(190)
    $outcome = "TIMEOUT"
    $detail = ""
    $answer = ""

    while ([DateTime]::UtcNow -lt $deadline) {
        $txt = Receive-OneWsTextMessage -Socket $ws -Buffer $buf -DeadlineUtc $deadline
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

    $score = Score-Answer -Text $answer -WordList $keywords
    $snippet = ""
    if (-not [string]::IsNullOrWhiteSpace($answer)) {
        $snippet = $answer.Substring(0, [Math]::Min(160, $answer.Length)).Replace("`r", " ").Replace("`n", " ")
    }

    $results += [pscustomobject]@{
        AI = $ai.id
        Outcome = $outcome
        Score = $score
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
Write-Host "===== FILE ACCURACY SUMMARY ====="
$results | Format-Table -AutoSize
Write-Host ""
Write-Host "===== RAW JSON ====="
$results | ConvertTo-Json -Depth 6
