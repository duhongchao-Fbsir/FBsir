/**
 * Golden MVP self-test: preview zip -> IMPORT_DRAFT via Admin HTTP.
 * Requires: admin on :8080, engine online for IMPORT_DRAFT.
 */
import { readFileSync, writeFileSync, existsSync } from 'fs'
import { execFileSync } from 'child_process'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const repoRoot = dirname(__dirname)
const zipPath = join(repoRoot, 'tools', 'out', 'skill-mvp-golden.zip')
const previewPath = join(repoRoot, 'tools', 'out', 'skill-mvp-selftest-preview.json')

const ADMIN = process.env.ADMIN_BASE || 'http://127.0.0.1:8080'
const ENGINE_ID = process.env.ENGINE_ID || 'engine-dev-001'
const USER_ID = process.env.USER_ID || '1'
const USER = process.env.ADMIN_USER || 'admin'
const PASS = process.env.ADMIN_PASS || 'admin123'

function run(cmd, args, opts = {}) {
  return execFileSync(cmd, args, { encoding: 'utf8', maxBuffer: 50 * 1024 * 1024, ...opts })
}

// Pack zip if missing
if (!existsSync(zipPath)) {
  console.log('Pack zip...')
  run('powershell.exe', [
    '-ExecutionPolicy',
    'Bypass',
    '-File',
    join(repoRoot, 'tools', 'pack-skill-mvp-golden.ps1'),
  ])
}

console.log('1) Login...')
const loginBody = JSON.stringify({ username: USER, password: PASS, code: '', uuid: '' })
const loginRaw = run('curl.exe', [
  '-sS',
  '-X',
  'POST',
  `${ADMIN}/login`,
  '-H',
  'Content-Type: application/json',
  '-d',
  loginBody,
])
const login = JSON.parse(loginRaw)
if (!login.token) {
  console.error('Login failed:', loginRaw)
  process.exit(1)
}
const token = login.token

console.log('2) Preview upload...')
const previewRaw = run('curl.exe', [
  '-sS',
  '-X',
  'POST',
  `${ADMIN}/ws/admin/skill/preview`,
  '-H',
  `Authorization: Bearer ${token}`,
  '-F',
  `file=@${zipPath}`,
])
writeFileSync(previewPath, previewRaw, 'utf8')

let preview
try {
  preview = JSON.parse(previewRaw)
} catch (e) {
  console.error('JSON parse failed, saved:', previewPath)
  throw e
}

const draft = preview?.data?.workflowDraft
if (!draft) {
  console.error('No workflowDraft in preview:', JSON.stringify(preview).slice(0, 500))
  process.exit(1)
}

const nodes = draft.nodes || []
const biz = nodes.filter((n) => n.type !== 'start' && n.type !== 'end').length
console.log('   workflowDraft nodes:', nodes.length, 'business:', biz)
console.log('   buildDebug:', draft.buildDebug || '(none)')

console.log('3) IMPORT_DRAFT via /ws/engine/request...')
const engineReq = {
  engineId: ENGINE_ID,
  type: 'QYWEIXIN_WORKFLOW_IMPORT_DRAFT',
  userId: USER_ID,
  timeout: 180,
  payload: {
    workflowDraft: draft,
    verifyImport: true,
    verifyMaxRounds: 2,
  },
}

const importRaw = run('curl.exe', [
  '-sS',
  '-X',
  'POST',
  `${ADMIN}/ws/engine/request`,
  '-H',
  `Authorization: Bearer ${token}`,
  '-H',
  'Content-Type: application/json',
  '-d',
  JSON.stringify(engineReq),
])

const importPath = join(repoRoot, 'tools', 'out', 'skill-mvp-selftest-import.json')
writeFileSync(importPath, importRaw, 'utf8')

let imp
try {
  imp = JSON.parse(importRaw)
} catch (e) {
  console.error('Import response not JSON, saved:', importPath)
  console.error(importRaw.slice(0, 800))
  process.exit(1)
}

const inner = imp?.data?.data ?? imp?.data
const verify = inner?.verifyReport
console.log('4) Result success:', imp?.success !== false)
if (verify) {
  console.log('   verifyReport.pass:', verify.pass)
  console.log('   expectedSource:', verify.expectedSource)
  console.log('   sourceCount:', verify.sourceCount, 'cloneCount:', verify.cloneCount)
  console.log('   skipped:', verify.skipped, 'reason:', verify.reason || '')
} else {
  console.log('   (no verifyReport in response)')
}

console.log('Outputs:', previewPath, importPath)
process.exit(verify && verify.pass === true ? 0 : 2)
