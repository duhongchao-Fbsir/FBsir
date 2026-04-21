# AIGC 鐪熸満鐑熸祴锛氶『搴忓悜鍚勪笂鏋?AI 鍙戜竴鏉?QUERY锛岃褰曢鏉＄粓鎬佹秷鎭紙闇€ Admin+Engine+宸茬櫥褰曞悇绔欐椂鎵嶆湁鎴愬姛锛?$ErrorActionPreference = 'Continue'
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

# 榛樿鍙窇鍏ㄩ噺锛涚幆澧冨彉閲?E2E_ONE=1 鏃跺彧璺?DeepSeek锛堟湰鏈哄凡鐧诲綍鏃堕€氬父鑳?RESULT_OK锛夛紝鐢ㄤ簬蹇€熼獙璇佺紪璇?閲嶅惎閾捐矾
if ($env:E2E_ONE -eq '1') {
  $ais = @( @{ id = 'deepseek'; type = 'AI_DEEPSEEK_QUERY' } )
} else {
  $ais = @(
    @{ id = 'deepseek'; type = 'AI_DEEPSEEK_QUERY' },
    @{ id = 'doubao'; type = 'AI_DOUBAO_QUERY' },
    @{ id = 'qianwen'; type = 'AI_QIANWEN_QUERY' },
    @{ id = 'yuanbao'; type = 'AI_YUANBAO_QUERY' },
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
  # 涓㈠純 CONNECTED锛堟暣甯э紝鏀寔鍒嗙墖锛?  $null = Receive-OneWsTextMessage -Socket $ws -Buffer $buf -DeadlineUtc ([DateTime]::UtcNow.AddSeconds(20))
  # 閬垮厤鑴氭湰鏂囦欢缂栫爜宸紓瀵艰嚧涓枃鍦?WebSocket 杞借嵎涓彉褰紱
  # 榛樿浣跨敤 ASCII 娴嬭瘯璇嶏紝鑻ラ渶鑷畾涔夊彲浼?E2E_PROMPT 鐜鍙橀噺銆?  $testPrompt = $env:E2E_PROMPT
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

  # 鏂囧績鍋跺彂鍦?120s 杈圭晫鎵嶅洖缁堟€侊紝鏀惧鍒?150s 闄嶄綆璇垽 TIMEOUT
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
