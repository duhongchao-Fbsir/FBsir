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
  if (u.startsWith('AI_GITEE')) return 'gitee'
  if (u.startsWith('AI_DOUBAO')) return 'doubao'
  if (u.startsWith('AI_QIANWEN') || u.startsWith('AI_TONGYI')) return 'qianwen'
  if (u.startsWith('AI_YUANBAO')) return 'yuanbao'
  if (u.startsWith('AI_WENXIN')) return 'wenxin'
  if (u.startsWith('AI_MITA')) return 'mita'
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
  const payload = message.payload || {}
  const payloadData = payload.data || {}
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
