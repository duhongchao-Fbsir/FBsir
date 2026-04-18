/**
 * Engine配置中心
 *
 * 说明：本文件集中管理所有Engine服务的配置信息，包括：
 * - AI平台配置（如DeepSeek、通义千问等）
 * - 非AI服务配置（如需要登录的其他服务）
 * - 消息类型（登录检测、扫码登录、AI咨询）
 * - 图标配置（URL或相对路径）
 * - 选择模式（单选/多选/互斥）
 * - 默认启用状态
 * - 扩展选项配置
 *
 * 扩展新服务：只需在ENGINE_CONFIGS数组中添加新配置
 *
 * @author 15年经验Java开发工程师
 * @version 4.0
 */

import { reactive } from 'vue'

// ============================================================================
// 服务选择模式枚举
// ============================================================================
export const SERVICE_SELECTION_MODE = {
  SINGLE: 'single',           // 单选模式：只能选择一个服务
  MULTIPLE: 'multiple',       // 多选模式：可以同时选择多个服务
  EXCLUSIVE: 'exclusive'      // 互斥模式：某些服务之间互斥
}

// ============================================================================
// 服务类型枚举
// ============================================================================
export const SERVICE_TYPE = {
  AI: 'ai',                   // AI服务
  LOGIN: 'login',             // 纯登录服务（如微信、QQ等）
  OTHER: 'other'              // 其他服务
}

// ============================================================================
// Engine配置列表（响应式，支持登录状态全局同步）
// ============================================================================
export const ENGINE_CONFIGS = reactive([
  // =========================================================================
  // DeepSeek配置（AI服务）
  // =========================================================================
  {
    id: 'deepseek',
    displayName: 'DeepSeek',
    description: 'DeepSeek AI助手，支持深度思考和联网搜索',
    type: SERVICE_TYPE.AI,      // 服务类型

    // 图标配置（支持URL或相对路径）
    icon: {
      type: 'url',              // 'element' | 'url' | 'local'
      value: 'https://u3w.com/chatfile/Deepseek.png',
    },

    // 消息类型配置
    messageTypes: {
      checkLogin: 'DEEPSEEK_CHECK_LOGIN',     // 登录状态检测
      scanLogin: 'DEEPSEEK_SCAN_LOGIN',       // 扫码登录
      query: 'AI_DEEPSEEK_QUERY'              // AI咨询（AI服务特有）
    },

    // 默认状态
    enabled: true,              // 是否默认启用
    loggedIn: false,            // 是否已登录（动态更新）
    requireLogin: true,         // 是否需要登录才能使用

    // 选项配置（按钮选项，支持互斥）
    options: [
      {
        id: 'enableDeepThinking',
        label: '深度思考',
        defaultValue: false,
        exclusive: [],
        disabled: false
      },
      {
        id: 'enableWebSearch',
        label: '联网搜索',
        defaultValue: false,
        exclusive: ['enableFileUpload'],
        disabled: false
      },
      {
        id: 'enableFileUpload',
        label: '上传文件',
        defaultValue: false,
        exclusive: ['enableWebSearch'],
        disabled: false
      },
      {
        id: 'enableFastMode',
        label: '快速模式',
        defaultValue: false,
        exclusive: ['enableExpertMode'],
        disabled: false
      },
      {
        id: 'enableExpertMode',
        label: '专家模式',
        defaultValue: false,
        exclusive: ['enableFastMode'],
        disabled: false
      }
    ],

    // AI会话ID字段名（AI服务特有）
    chatIdField: 'deepseekChatId',

    // 排序权重（数字越小越靠前）
    order: 1
  },

  // =========================================================================
  // 豆包（Doubao）配置（AI服务）
  // =========================================================================
  {
    id: 'doubao',
    displayName: '豆包',
    description: '字节豆包网页版对话（需在登录管理器中完成账号登录）',
    type: SERVICE_TYPE.AI,

    icon: {
      type: 'url',
      value: 'https://lf-flow-web-cdn.doubao.com/obj/flow-doubao/doubao/chat/logo-icon2.png'
    },

    messageTypes: {
      checkLogin: 'DOUBAO_CHECK_LOGIN',
      scanLogin: 'DOUBAO_SCAN_LOGIN',
      query: 'AI_DOUBAO_QUERY'
    },

    enabled: true,
    loggedIn: false,
    requireLogin: true,

    options: [
      {
        id: 'enableDeepThinking',
        label: '思考',
        defaultValue: false,
        exclusive: [],
        disabled: false
      },
      {
        id: 'enableFastMode',
        label: '快速',
        defaultValue: false,
        exclusive: ['enableExpertMode'],
        disabled: false
      },
      {
        id: 'enableExpertMode',
        label: '专家',
        defaultValue: false,
        exclusive: ['enableFastMode'],
        disabled: false
      },
      {
        id: 'enableFileUpload',
        label: '上传文件',
        defaultValue: false,
        exclusive: [],
        disabled: false
      }
    ],

    // 与 index.vue 中 userInfoReq.dbChatId、库表 db_chat_id 一致
    chatIdField: 'dbChatId',

    order: 2
  },

  // =========================================================================
  // 千问（Qianwen）配置（AI服务）
  // =========================================================================
  {
    id: 'qianwen',
    displayName: '千问',
    description: '阿里千问网页版对话（需在登录管理器中完成账号登录）',
    type: SERVICE_TYPE.AI,

    icon: {
      type: 'url',
      value: 'https://img.alicdn.com/imgextra/i4/O1CN01uar8u91DHWktnF2fl_!!6000000000191-2-tps-110-110.png'
    },

    messageTypes: {
      checkLogin: 'QIANWEN_CHECK_LOGIN',
      scanLogin: 'QIANWEN_SCAN_LOGIN',
      query: 'AI_QIANWEN_QUERY'
    },

    enabled: true,
    loggedIn: false,
    requireLogin: true,

    options: [
      {
        id: 'enableFileUpload',
        label: '上传文件',
        defaultValue: false,
        exclusive: [],
        disabled: false
      }
    ],

    // 与 index.vue 中 userInfoReq.toneChatId、库表 tone_chat_id 一致
    chatIdField: 'toneChatId',

    order: 3
  },

  // =========================================================================
  // 腾讯元宝（Yuanbao）配置（AI服务）
  // =========================================================================
  {
    id: 'yuanbao',
    displayName: '腾讯元宝',
    description: '腾讯元宝网页版对话（需在登录管理器中完成账号登录）',
    type: SERVICE_TYPE.AI,

    icon: {
      type: 'element',
      value: 'Connection'
    },

    messageTypes: {
      checkLogin: 'YUANBAO_CHECK_LOGIN',
      scanLogin: 'YUANBAO_SCAN_LOGIN',
      query: 'AI_YUANBAO_QUERY'
    },

    enabled: true,
    loggedIn: false,
    requireLogin: true,

    options: [
      {
        id: 'enableFileUpload',
        label: '上传文件',
        defaultValue: false,
        exclusive: [],
        disabled: false
      }
    ],

    // 与 index.vue 中 userInfoReq.ybChatId、库表 yb_chat_id 一致
    chatIdField: 'ybChatId',

    order: 4
  },

  // =========================================================================
  // 文心一言（百度）配置（AI服务）
  // =========================================================================
  {
    id: 'wenxin',
    displayName: '文心一言',
    description: '百度文心一言网页版对话（会话存 baidu_chat_id）',
    type: SERVICE_TYPE.AI,

    icon: {
      type: 'element',
      value: 'ChatDotRound'
    },

    messageTypes: {
      checkLogin: 'WENXIN_CHECK_LOGIN',
      scanLogin: 'WENXIN_SCAN_LOGIN',
      query: 'AI_WENXIN_QUERY'
    },

    enabled: true,
    loggedIn: false,
    requireLogin: true,

    options: [
      {
        id: 'enableFileUpload',
        label: '上传文件',
        defaultValue: false,
        exclusive: [],
        disabled: false
      }
    ],

    // 与 index.vue userInfoReq.baiduChatId、库表 baidu_chat_id 一致
    chatIdField: 'baiduChatId',

    order: 5
  },

  // =========================================================================
  // 秘塔 AI（Metaso）配置（AI服务）
  // =========================================================================
  {
    id: 'mita',
    displayName: '秘塔',
    description: '秘塔 AI 搜索网页版（会话存 metaso_chat_id）',
    type: SERVICE_TYPE.AI,

    icon: {
      type: 'url',
      value: 'https://metaso.cn/favicon.ico'
    },

    messageTypes: {
      checkLogin: 'MITA_CHECK_LOGIN',
      scanLogin: 'MITA_SCAN_LOGIN',
      query: 'AI_MITA_QUERY'
    },

    enabled: true,
    loggedIn: false,
    requireLogin: true,

    options: [
      {
        id: 'enableFileUpload',
        label: '上传文件',
        defaultValue: false,
        exclusive: [],
        disabled: false
      }
    ],

    chatIdField: 'metasoChatId',

    order: 6
  },

  // =========================================================================
  // Gitee AI Chat配置（AI服务）
  // =========================================================================
  {
    id: 'gitee',
    displayName: 'Gitee AI Chat',
    description: 'Gitee AI Chat 智能助手',
    type: SERVICE_TYPE.AI,      // 服务类型

    // 图标配置（支持URL或相对路径）
    icon: {
      type: 'url',              // 'element' | 'url' | 'local'
      value: 'https://chat.gitee.com/ai-teammates/_next/static/media/Ai@2x.f426668a.gif',
    },

    // 消息类型配置
    messageTypes: {
      checkLogin: 'GITEE_CHECK_LOGIN',     // 登录状态检测
      scanLogin: 'GITEE_SCAN_LOGIN',       // 扫码登录
      query: 'AI_GITEE_QUERY'              // AI咨询（AI服务特有）
    },

    // 默认状态
    enabled: true,              // 是否默认启用
    loggedIn: false,            // 是否已登录（动态更新）
    requireLogin: true,         // 是否需要登录才能使用

    // 选项配置
    options: [
      {
        id: 'openSourceExploration',
        label: '开源探索',
        defaultValue: false,
        exclusive: ['repositoryQA', 'helpCenter'],
        disabled: false
      },
      {
        id: 'repositoryQA',
        label: '仓库问答',
        defaultValue: false,
        exclusive: ['openSourceExploration', 'helpCenter'],
        disabled: false
      },
      {
        id: 'helpCenter',
        label: '帮助中心',
        defaultValue: false,
        exclusive: ['openSourceExploration', 'repositoryQA'],
        disabled: false
      },
      {
        id: 'enableFileUpload',
        label: '上传文件',
        defaultValue: false,
        exclusive: [],
        disabled: false
      }
    ],

    // 仓库问答的仓库列表（由Engine动态探测DOM后回传）
    repositoryChoices: [
      { label: '页面默认仓库', value: '' }
    ],

    // AI会话ID字段名（AI服务特有）
    chatIdField: 'giteeChatId',
    // 排序权重（数字越小越靠前）
    order: 7
  },

  // =========================================================================
  // 元器配置（其他服务）
  // =========================================================================
  {
    id: 'yuanqi',
    displayName: '元器',
    description: '腾讯元器平台，支持工作流编辑、调试和发布',
    type: SERVICE_TYPE.OTHER,      // 服务类型

    // 图标配置（使用Element Plus图标）
    icon: {
      type: 'element',              // 'element' | 'url' | 'local'
      value: 'Connection',          // Element Plus图标名称
    },

    // 消息类型配置
    messageTypes: {
      checkLogin: 'YUANQI_CHECK_LOGIN',     // 登录状态检测
      scanLogin: 'YUANQI_SCAN_LOGIN',       // 扫码登录
    },

    // 默认状态
    enabled: true,              // 是否默认启用
    loggedIn: false,            // 是否已登录（动态更新）
    requireLogin: true,         // 是否需要登录才能使用

    // 选项配置（无额外选项）
    options: [],

    // 排序权重（数字越小越靠前）
    order: 8
  }

  // =========================================================================
  // 扩展示例：新增AI配置模板
  // =========================================================================
  /*
  {
    id: 'yuanbao',
    displayName: '腾讯元宝',
    description: '腾讯元宝AI助手',
    type: SERVICE_TYPE.AI,

    icon: {
      type: 'url',
      value: 'https://example.com/yuanbao.png'
    },

    messageTypes: {
      checkLogin: 'YUANBAO_CHECK_LOGIN',
      scanLogin: 'YUANBAO_SCAN_LOGIN',
      query: 'AI_YUANBAO_QUERY'
    },

    enabled: false,
    loggedIn: false,
    requireLogin: true,

    options: [
      {
        id: 'enableSearch',
        label: '联网搜索',
        defaultValue: false,
        exclusive: [],
        disabled: false
      }
    ],

    chatIdField: 'ybChatId',
    order: 2
  },
  */

  // =========================================================================
  // 扩展示例：纯登录服务配置模板（非AI）
  // =========================================================================
  /*
  {
    id: 'wechat',
    displayName: '微信',
    description: '微信账号登录',
    type: SERVICE_TYPE.LOGIN,

    icon: {
      type: 'url',
      value: 'https://example.com/wechat.png'
    },

    messageTypes: {
      checkLogin: 'WECHAT_CHECK_LOGIN',
      scanLogin: 'WECHAT_SCAN_LOGIN'
      // 注意：非AI服务不需要query字段
    },

    enabled: true,
    loggedIn: false,
    requireLogin: true,

    options: [],      // 非AI服务一般无额外选项

    order: 10
  },
  */
])

// ============================================================================
// 默认配置
// ============================================================================
export const DEFAULT_CONFIG = {
  selectionMode: SERVICE_SELECTION_MODE.SINGLE,  // 默认单选模式
  maxConcurrent: 3,                               // 多选模式下最多同时选择的服务数量
  autoExpand: true,                               // 是否自动展开任务流程
  defaultEngineId: ''                             // 默认Engine ID
}

// ============================================================================
// 工具函数
// ============================================================================

/**
 * 根据ID获取服务配置
 */
export function getEngineConfig(serviceId) {
  return ENGINE_CONFIGS.find(s => s.id === serviceId)
}

/**
 * 根据消息类型获取服务配置
 */
export function getEngineConfigByMessageType(messageType) {
  return ENGINE_CONFIGS.find(s =>
    Object.values(s.messageTypes).includes(messageType)
  )
}

/**
 * 获取所有AI服务
 */
export function getAiServices() {
  return ENGINE_CONFIGS.filter(s => s.type === SERVICE_TYPE.AI)
}

/**
 * 获取所有已启用且已登录的AI
 */
export function getEnabledAis() {
  return ENGINE_CONFIGS.filter(s => s.type === SERVICE_TYPE.AI && s.enabled && s.loggedIn)
}

/**
 * 获取所有需要登录的服务
 */
export function getLoginRequiredServices() {
  return ENGINE_CONFIGS.filter(s => s.requireLogin && !s.loggedIn)
}

/**
 * 获取服务显示名称
 */
export function getServiceDisplayName(serviceId) {
  const config = getEngineConfig(serviceId)
  return config ? config.displayName : serviceId
}

/**
 * 获取AI的查询消息类型
 */
export function getAiQueryMessageType(aiId) {
  const config = getEngineConfig(aiId)
  return config?.messageTypes?.query || `AI_${aiId.toUpperCase()}_QUERY`
}

/**
 * 获取服务的扫码登录消息类型
 */
export function getServiceScanLoginMessageType(serviceId) {
  const config = getEngineConfig(serviceId)
  return config?.messageTypes?.scanLogin || `${serviceId.toUpperCase()}_SCAN_LOGIN`
}

/**
 * 获取服务的登录检测消息类型
 */
export function getServiceCheckLoginMessageType(serviceId) {
  const config = getEngineConfig(serviceId)
  return config?.messageTypes?.checkLogin || `${serviceId.toUpperCase()}_CHECK_LOGIN`
}

/**
 * 获取AI的会话ID字段名
 */
export function getAiChatIdField(aiId) {
  const config = getEngineConfig(aiId)
  return config?.chatIdField || `${aiId}ChatId`
}

/**
 * 检查两个选项是否互斥
 */
export function areOptionsExclusive(serviceId, optionId1, optionId2) {
  const config = getEngineConfig(serviceId)
  if (!config) return false

  const option1 = config.options?.find(opt => opt.id === optionId1)
  if (!option1 || !option1.exclusive) return false

  return option1.exclusive.includes(optionId2)
}

/**
 * 获取排序后的服务配置列表
 */
export function getSortedEngineConfigs() {
  return [...ENGINE_CONFIGS].sort((a, b) => (a.order || 999) - (b.order || 999))
}

/**
 * 初始化服务选项状态
 */
export function initServiceOptionsState(serviceId) {
  const config = getEngineConfig(serviceId)
  if (!config) return {}

  const state = {}
  config.options?.forEach(option => {
    state[option.id] = option.defaultValue
  })
  return state
}

/** Gitee：开源探索 / 仓库问答 / 帮助中心 三选一（与 options.exclusive 配置一致） */
export const GITEE_MODE_OPTION_IDS = ['openSourceExploration', 'repositoryQA', 'helpCenter']

/**
 * Gitee 模式互斥切换：模式项三选一；非模式项（如 enableFileUpload）走独立开关
 * @returns {{ newState: Record<string, boolean>, needRequestRepositoryChoices: boolean }}
 */
export function applyGiteeOptionsToggle(optionId, newValue, currentState) {
  const newState = { ...currentState }
  if (GITEE_MODE_OPTION_IDS.includes(optionId)) {
    if (newValue) {
      GITEE_MODE_OPTION_IDS.forEach(key => {
        newState[key] = key === optionId
      })
      return {
        newState,
        needRequestRepositoryChoices: optionId === 'repositoryQA'
      }
    }
    newState[optionId] = false
    return { newState, needRequestRepositoryChoices: false }
  }
  newState[optionId] = newValue
  return { newState, needRequestRepositoryChoices: false }
}

/**
 * 处理互斥选项切换
 */
export function handleExclusiveOptionToggle(serviceId, optionId, newValue, currentState) {
  const config = getEngineConfig(serviceId)
  if (!config) return currentState

  const newState = { ...currentState }
  newState[optionId] = newValue

  if (newValue) {
    const option = config.options?.find(opt => opt.id === optionId)
    if (option && option.exclusive) {
      option.exclusive.forEach(exclusiveId => {
        newState[exclusiveId] = false
      })
    }
  }

  return newState
}

/**
 * 更新服务登录状态（全局同步）
 */
export function updateServiceLoginStatus(serviceId, isLoggedIn) {
  const config = getEngineConfig(serviceId)
  if (config) {
    config.loggedIn = isLoggedIn
  }
}

/**
 * 动态更新服务可选项（如 Gitee 仓库问答二级菜单）
 */
export function updateServiceDynamicChoices(serviceId, fieldName, choices) {
  const config = getEngineConfig(serviceId)
  if (!config) return
  if (!Array.isArray(choices)) return
  config[fieldName] = choices
}

/**
 * 批量更新服务登录状态
 */
export function batchUpdateLoginStatus(statusMap) {
  Object.entries(statusMap).forEach(([serviceId, isLoggedIn]) => {
    updateServiceLoginStatus(serviceId, isLoggedIn)
  })
}

/**
 * 获取所有服务的登录状态
 */
export function getAllLoginStatus() {
  const statusMap = {}
  ENGINE_CONFIGS.forEach(config => {
    statusMap[config.id] = config.loggedIn
  })
  return statusMap
}

/**
 * 🔥 保存登录状态到localStorage（持久化）
 */
export function saveLoginStatusToStorage() {
  const statusMap = getAllLoginStatus()
  try {
    localStorage.setItem('engine_login_status', JSON.stringify(statusMap))
    console.log('💾 [持久化] 登录状态已保存到localStorage:', statusMap)
  } catch (error) {
    console.error('❌ [持久化] 保存登录状态失败:', error)
  }
}

/**
 * 🔥 从localStorage恢复登录状态
 */
export function restoreLoginStatusFromStorage() {
  try {
    const stored = localStorage.getItem('engine_login_status')
    if (stored) {
      const statusMap = JSON.parse(stored)
      batchUpdateLoginStatus(statusMap)
      console.log('📂 [持久化] 登录状态已从localStorage恢复:', statusMap)
      return statusMap
    }
  } catch (error) {
    console.error('❌ [持久化] 恢复登录状态失败:', error)
  }
  return null
}

/**
 * 🔥 清除localStorage中的登录状态
 */
export function clearLoginStatusFromStorage() {
  try {
    localStorage.removeItem('engine_login_status')
    console.log('🗑️ [持久化] 登录状态已清除')
  } catch (error) {
    console.error('❌ [持久化] 清除登录状态失败:', error)
  }
}

// 兼容旧版本的函数别名
export const getAiConfig = getEngineConfig
export const getAiConfigByMessageType = getEngineConfigByMessageType
export const getAiDisplayName = getServiceDisplayName
export const getAiScanLoginMessageType = getServiceScanLoginMessageType
export const getAiCheckLoginMessageType = getServiceCheckLoginMessageType
export const getSortedAiConfigs = getSortedEngineConfigs
export const initAiOptionsState = initServiceOptionsState
