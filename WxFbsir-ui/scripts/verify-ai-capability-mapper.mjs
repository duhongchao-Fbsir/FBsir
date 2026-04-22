/**
 * 仿真：校验 AI 能力矩阵与 buildCompatibleAiPayload 对各上架 AI 的完整性（无浏览器）。
 * 运行：npm run test:aigc-mapper（在 WxFbsir-ui 目录）
 */
import {
  AI_CAPABILITY_MATRIX,
  buildCompatibleAiPayload,
  normalizeAiCapabilities
} from '../src/utils/aiCapabilityMapper.js'
import { ENGINE_CONFIGS, SERVICE_TYPE } from '../src/config/engineConfig.js'

// Must match ENGINE_CONFIGS entries with type === AI and Object.keys(AI_CAPABILITY_MATRIX) (current product shelf)
const SHELVED = ['deepseek', 'doubao', 'qianwen', 'yuanbao']

function assert(cond, msg) {
  if (!cond) throw new Error(msg || 'assertion failed')
}

function assertSameStringSet(a, b, label) {
  const sa = new Set(a)
  const sb = new Set(b)
  for (const x of sa) assert(sb.has(x), `${label}: extra ${x}`)
  for (const x of sb) assert(sa.has(x), `${label}: missing ${x}`)
}

const engineAiIds = ENGINE_CONFIGS.filter((c) => c.type === SERVICE_TYPE.AI).map((c) => c.id)
assertSameStringSet(Object.keys(AI_CAPABILITY_MATRIX), SHELVED, 'AI_CAPABILITY_MATRIX vs SHELVED')
assertSameStringSet(engineAiIds, SHELVED, 'ENGINE_CONFIGS(AI) vs SHELVED')

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
