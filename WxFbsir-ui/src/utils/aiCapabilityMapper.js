/**
 * AIGC 能力映射兼容层：
 * - 向后兼容既有扁平 payload 字段（enableXxx）
 * - 提供统一能力视图（capabilities/conversationProfile/providerOptions）
 */

export const AI_CAPABILITY_MATRIX = {
  deepseek: {
    reasoning: true,
    webSearch: true,
    fileUpload: true,
    multiMode: true,
    supportedOptionIds: ['enableDeepThinking', 'enableWebSearch', 'enableFileUpload', 'enableFastMode', 'enableExpertMode']
  },
  doubao: {
    reasoning: true,
    webSearch: false,
    fileUpload: true,
    multiMode: true,
    supportedOptionIds: ['enableDeepThinking', 'enableFastMode', 'enableExpertMode', 'enableFileUpload']
  },
  qianwen: {
    reasoning: false,
    webSearch: false,
    fileUpload: true,
    multiMode: false,
    supportedOptionIds: ['enableFileUpload']
  },
  yuanbao: {
    reasoning: false,
    webSearch: false,
    fileUpload: true,
    multiMode: false,
    supportedOptionIds: ['enableFileUpload']
  }
}

function resolveConversationProfile(aiId, aiOptions, providerOptions) {
  if (aiId === 'deepseek') {
    if (aiOptions.enableFastMode) return 'fast'
    if (aiOptions.enableExpertMode) return 'expert'
    if (aiOptions.enableDeepThinking && aiOptions.enableWebSearch) return 'deepThinking+webSearch'
    if (aiOptions.enableDeepThinking) return 'deepThinking'
    if (aiOptions.enableWebSearch) return 'webSearch'
    return 'default'
  }
  if (aiId === 'doubao') {
    if (aiOptions.enableFastMode) return 'fast'
    if (aiOptions.enableExpertMode) return 'expert'
    if (aiOptions.enableDeepThinking) return 'deepThinking'
    return providerOptions?.conversationProfile || 'default'
  }
  return providerOptions?.conversationProfile || 'default'
}

export function normalizeAiCapabilities(aiId, aiOptions = {}, uploadedFileUrl = '', providerOptions = {}) {
  const matrix = AI_CAPABILITY_MATRIX[aiId] || {
    reasoning: false,
    webSearch: false,
    fileUpload: true,
    multiMode: false,
    supportedOptionIds: ['enableFileUpload']
  }
  const enabledOptionIds = Object.keys(aiOptions).filter(key => aiOptions[key] === true)
  const unsupportedEnabledOptions = enabledOptionIds.filter(key => !matrix.supportedOptionIds.includes(key))

  return {
    capabilities: {
      reasoning: matrix.reasoning && aiOptions.enableDeepThinking === true,
      webSearch: matrix.webSearch && aiOptions.enableWebSearch === true,
      fileUpload: {
        enabled: matrix.fileUpload && aiOptions.enableFileUpload === true,
        url: uploadedFileUrl || ''
      }
    },
    conversationProfile: resolveConversationProfile(aiId, aiOptions, providerOptions),
    providerOptions: { ...providerOptions },
    unsupportedEnabledOptions
  }
}

export function buildCompatibleAiPayload({
  aiId,
  query,
  uploadedFileUrl,
  chatId,
  sessionId,
  isNewChat,
  userPrompt,
  enabledAIs,
  progressLogs,
  chatIdField,
  aiChatId,
  aiOptions = {},
  providerOptions = {}
}) {
  const normalized = normalizeAiCapabilities(aiId, aiOptions, uploadedFileUrl, providerOptions)
  const payload = {
    query,
    uploadedFileUrl: uploadedFileUrl || '',
    chatId,
    sessionId,
    aiType: aiId,
    isNewChat,
    userPrompt,
    enabledAIs,
    progressLogs
  }
  payload[chatIdField] = aiChatId || ''
  Object.keys(aiOptions).forEach(optionKey => {
    payload[optionKey] = aiOptions[optionKey]
  })
  Object.keys(providerOptions).forEach(optionKey => {
    payload[optionKey] = providerOptions[optionKey]
  })
  payload.capabilities = normalized.capabilities
  payload.conversationProfile = normalized.conversationProfile
  payload.providerOptions = normalized.providerOptions

  return { payload, normalized }
}
