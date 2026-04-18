/**
 * 归一化 Engine → 前端的 WebSocket 消息结构，减少 messageType/type、payload 嵌套差异带来的分支重复。
 */

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
  const aiType = payload.aiType || payloadData.aiType || 'unknown'
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
