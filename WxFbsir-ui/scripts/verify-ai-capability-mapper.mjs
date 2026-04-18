/**
 * 仿真：校验 AI 能力矩阵与 buildCompatibleAiPayload 对各上架 AI 的完整性（无浏览器）。
 * 运行：npm run test:aigc-mapper（在 WxFbsir-ui 目录）
 */
import {
  AI_CAPABILITY_MATRIX,
  buildCompatibleAiPayload,
  normalizeAiCapabilities
} from '../src/utils/aiCapabilityMapper.js'

const SHELVED = ['deepseek', 'gitee', 'doubao', 'qianwen', 'yuanbao', 'wenxin', 'mita']

function assert(cond, msg) {
  if (!cond) throw new Error(msg || 'assertion failed')
}

for (const id of SHELVED) {
  assert(AI_CAPABILITY_MATRIX[id], `missing matrix: ${id}`)
  const m = AI_CAPABILITY_MATRIX[id]
  assert(Array.isArray(m.supportedOptionIds), `${id}: supportedOptionIds`)
  assert(typeof m.fileUpload === 'boolean', `${id}: fileUpload`)
}

const r = normalizeAiCapabilities('doubao', {
  enableDeepThinking: true,
  enableFastMode: false,
  enableExpertMode: false,
  enableWebSearch: true,
  enableFileUpload: false
}, '', {})
assert(r.unsupportedEnabledOptions.includes('enableWebSearch'), 'doubao should flag webSearch as unsupported')

const built = buildCompatibleAiPayload({
  aiId: 'qianwen',
  query: 'test',
  uploadedFileUrl: '',
  chatId: 'c1',
  sessionId: 's1',
  isNewChat: true,
  userPrompt: 'test',
  enabledAIs: ['qianwen'],
  progressLogs: [],
  chatIdField: 'toneChatId',
  aiChatId: 'tid-1',
  aiOptions: { enableFileUpload: true },
  providerOptions: {}
})
assert(built.payload.toneChatId === 'tid-1', 'chatIdField injection')
assert(built.payload.capabilities?.fileUpload?.enabled === true, 'capabilities.fileUpload')

console.log('verify-ai-capability-mapper: OK')
