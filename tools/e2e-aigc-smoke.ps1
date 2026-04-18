# AIGC 真机烟测：顺序向各上架 AI 发一条 QUERY，记录首条终态消息（需 Admin+Engine+已登录各站时才有成功）
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$base = 'http://127.0.0.1:8080'
$engineId = 'engine-dev-001'
$chatId = 'e2e-smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)

$loginBody = '{"username":"admin","password":"admin123","code":"","uuid":""}'
$login = Invoke-RestMethod -Uri "$base/login" -Method Post -ContentType 'application/json' -Body $loginBody
$tok = $login.token
$enc = [System.Uri]::EscapeDataString($tok)
$wsUri = "ws://127.0.0.1:8080/ws/client?clientType=web&token=$enc"

# 默认可跑全量；环境变量 E2E_ONE=1 时只跑 DeepSeek（本机已登录时通常能 RESULT_OK），用于快速验证编译/重启链路
if ($env:E2E_ONE -eq '1') {
  $ais = @( @{ id = 'deepseek'; type = 'AI_DEEPSEEK_QUERY' } )
} else {
  $ais = @(
    @{ id = 'deepseek'; type = 'AI_DEEPSEEK_QUERY' },
    @{ id = 'doubao'; type = 'AI_DOUBAO_QUERY' },
    @{ id = 'qianwen'; type = 'AI_QIANWEN_QUERY' },
    @{ id = 'yuanbao'; type = 'AI_YUANBAO_QUERY' },
    @{ id = 'wenxin'; type = 'AI_WENXIN_QUERY' },
    @{ id = 'mita'; type = 'AI_MITA_QUERY' },
    @{ id = 'gitee'; type = 'AI_GITEE_QUERY' }
  )
}

$results = @()

function Receive-OneWsTextMessage {
  param(
    [System.Net.WebSockets.ClientWebSocket] $Socket,
    [byte[]] $Buffer,
    [DateTime] $DeadlineUtc
  )
  $seg = New-Object System.ArraySegment[byte] -ArgumentList @(,$Buffer)
  $acc = [string]::Empty
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

foreach ($row in $ais) {
  $sessionId = [Guid]::NewGuid().ToString()
  $ws = New-Object System.Net.WebSockets.ClientWebSocket
  $ct = New-Object System.Threading.CancellationToken
  $null = $ws.ConnectAsync([Uri]$wsUri, $ct).Wait(20000)
  if ($ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) {
    $results += [pscustomobject]@{ AI = $row.id; Outcome = 'WS_FAIL'; Detail = $ws.State }
    continue
  }
  $buf = New-Object byte[] 262144
  # 丢弃 CONNECTED（整帧，支持分片）
  $null = Receive-OneWsTextMessage -Socket $ws -Buffer $buf -DeadlineUtc ([DateTime]::UtcNow.AddSeconds(20))
  # 避免脚本文件编码差异导致中文在 WebSocket 载荷中变形；
  # 默认使用 ASCII 测试词，若需自定义可传 E2E_PROMPT 环境变量。
  $testPrompt = $env:E2E_PROMPT
  if ([string]::IsNullOrWhiteSpace($testPrompt)) { $testPrompt = 'E2E smoke reply OK only' }
  $msg = @{
    type     = $row.type
    engineId = $engineId
    chatId   = $chatId
    payload  = @{
      sessionId = $sessionId
      chatId    = $chatId
      aiType    = $row.id
      query     = $testPrompt
      userPrompt = $testPrompt
      isNewChat = $true
    }
  } | ConvertTo-Json -Depth 8 -Compress
  if ($env:E2E_DEBUG -eq '1') {
    Write-Host "[DEBUG-OUTBOUND] $msg"
  }
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($msg)
  $sendSeg = New-Object System.ArraySegment[byte] -ArgumentList @(,$bytes)
  $null = $ws.SendAsync($sendSeg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait(30000)

  # 文心偶发在 120s 边界才回终态，放宽到 150s 降低误判 TIMEOUT
  $deadline = [DateTime]::UtcNow.AddSeconds(150)
  $outcome = 'TIMEOUT'
  $detail = ''
  while ([DateTime]::UtcNow -lt $deadline) {
    $txt = Receive-OneWsTextMessage -Socket $ws -Buffer $buf -DeadlineUtc $deadline
    if ($null -eq $txt) { continue }
    try {
      $j = $txt | ConvertFrom-Json
    } catch { continue }
    $t = [string]$j.type
    if ($t -ne 'AI_TASK_RESULT' -and $t -ne 'AI_TASK_ERROR') { continue }
    $psid = $null
    if ($j.payload) {
      $psid = $j.payload.sessionId
      if (-not $psid -and $j.payload.payload) { $psid = $j.payload.payload.sessionId }
    }
    if ($psid -and $psid -ne $sessionId) { continue }
    if ($t -eq 'AI_TASK_RESULT') {
      $succ = $j.payload.success
      if ($succ -eq $true) { $outcome = 'RESULT_OK' } else { $outcome = 'RESULT_FAIL' }
    } else {
      $outcome = 'TASK_ERROR'
      $detail = [string]$j.payload.errorMessage
    }
    break
  }
  try {
    if ($ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
      $null = $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, '', $ct).Wait(5000)
    }
  } catch { }
  $results += [pscustomobject]@{ AI = $row.id; SessionId = $sessionId; Outcome = $outcome; Detail = $detail }
  Start-Sleep -Seconds 1
}

$results | Format-Table -AutoSize
$ok = @($results | Where-Object { $_.Outcome -eq 'RESULT_OK' }).Count
Write-Host "--- SUMMARY: RESULT_OK=$ok / $($results.Count) ---"
