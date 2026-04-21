/**
 * 归一化 Engine → 前端的 WebSocket 消息结构，减少 messageType/type、payload 嵌套差异带来的分支重复。
 */

/**
 * 从 Engine 消息类型推断 aiType（与 AI_*_QUERY 约定一致）。
 * @param {string} messageType
 * @returns {string} 小写 ai id 或空字符串
 */
export function inferAiTypeFromMessageType(messageType) {
  if (!messageType || typeof messageType !== 'string') {
    return ''
  }
  const u = messageType.toUpperCase()
  if (u.startsWith('AI_DEEPSEEK')) return 'deepseek'
  if (u.startsWith('AI_DOUBAO')) return 'doubao'
  if (u.startsWith('AI_QIANWEN') || u.startsWith('AI_TONGYI')) return 'qianwen'
  if (u.startsWith('AI_YUANBAO')) return 'yuanbao'
  return ''
}

/**
 * @param {object} message - 已解析的 JSON 对象（勿传入原始字符串）
 * @returns {{
 *   messageType: string,
 *   payload: object,
 *   payloadData: object,
 *   aiType: string,
 *   sessionId: string,
 *   success: boolean|undefined,
 *   errorMessage: string|undefined,
 *   errorCode: string|undefined,
 *   errorTitle: string|undefined
 * }}
 */
export function normalizeEngineInboundMessage(message) {
  const payload = message.payload != null ? { ...message.payload } : {}
  // Fastjson/Spring 在少数路径下会把嵌套对象序列成 JSON 字符串；未解析时中文会像「乱码链」一样错位显示
  if (typeof payload.data === 'string') {
    try {
      const parsed = JSON.parse(payload.data)
      payload.data = parsed
    } catch (e) {
      /* 保持原样，由业务侧兜底 */
    }
  }
  const rawData = payload.data
  const payloadData =
    rawData != null && typeof rawData === 'object' && !Array.isArray(rawData) ? rawData : {}
  const messageType = message.messageType || message.type
  let aiType = payload.aiType || payloadData.aiType || ''
  if (!aiType || aiType === 'unknown') {
    const inferred = inferAiTypeFromMessageType(messageType)
    aiType = inferred || 'unknown'
  }
  const sessionId =
    payload.sessionId ||
    message.sessionId ||
    payloadData.sessionId ||
    ''
  const success = payload.success !== undefined ? payload.success : message.success
  const errorMessage = payload.errorMessage || payload.message || message.message
  const errorCode = payload.errorCode
  const errorTitle = payload.errorTitle

  return {
    messageType,
    payload,
    payloadData,
    aiType,
    sessionId,
    success,
    errorMessage,
    errorCode,
    errorTitle
  }
}
