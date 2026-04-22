import { loginToken, previewDraft } from './lib/mvp-evolution-runner.mjs'

const ADMIN = process.env.ADMIN_BASE || 'http://127.0.0.1:8080'
const USER = process.env.ADMIN_USER || 'admin'
const PASS = process.env.ADMIN_PASS || 'admin123'
const ZIP = process.env.EVOLUTION_ZIP_PATH || 'D:/U3WV2/tools/out/fbs-bookwriter-workbuddy-latest.zip'

const token = loginToken(ADMIN, USER, PASS)
const { preview, draft } = previewDraft(ADMIN, token, ZIP)
const steps = preview?.data?.ir?.sceneFlow?.steps || []
const nodes = draft?.nodes || []

console.log('sceneFlow.stats:', preview?.data?.ir?.sceneFlow?.stats)
console.log('workflowDraft:', {
  nodes: nodes.length,
  edges: (draft?.edges || []).length,
  buildDebug: draft?.buildDebug,
})

console.log('first scene steps (max 30):')
for (const s of steps.slice(0, 30)) {
  console.log('-', {
    id: s.id,
    label: String(s.label || '').slice(0, 96),
    stepType: s.stepType,
    sourcePath: s.sourcePath,
  })
}
