/**
 * H2 版 L-rel：skill-mvp-golden-h2 + 冻结 sourceExportRows（五业务节点）。
 * 用法（仓库根）: node tools/h2-l-rel-exit-gate.mjs
 */
import { existsSync, readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'
import { spawnSync } from 'child_process'

const __dirname = dirname(fileURLToPath(import.meta.url))
const repoRoot = dirname(__dirname)
const outDir = join(repoRoot, 'tools', 'out')
const latestPath = join(outDir, 'evolution-latest.json')
const fixturePath = join(repoRoot, 'tools', 'fixtures', 'mvp-h2-source-export-rows.json')
const h2ZipName = 'skill-mvp-golden-h2.zip'

function runEvolution(env) {
  const node = process.execPath
  const childEnv = { ...process.env }
  delete childEnv.SOURCE_EXPORT_MODE
  delete childEnv.SOURCE_EXPORT_ROWS_FILE
  delete childEnv.EVOLUTION_ZIP
  if (!childEnv.IMPORT_TIMEOUT) {
    childEnv.IMPORT_TIMEOUT = '420'
  }
  Object.assign(childEnv, env)
  const r = spawnSync(
    node,
    [join(repoRoot, 'tools', 'run-evolution-rounds.mjs'), '--rounds', '1', '--cooldown-ms', '300'],
    {
      cwd: repoRoot,
      encoding: 'utf8',
      env: childEnv,
      maxBuffer: 32 * 1024 * 1024,
    },
  )
  if (r.status !== 0) {
    throw new Error(`run-evolution-rounds exited ${r.status}: ${r.stderr || r.stdout || ''}`)
  }
}

function readLatest() {
  if (!existsSync(latestPath)) {
    throw new Error(`missing ${latestPath}`)
  }
  return JSON.parse(readFileSync(latestPath, 'utf8'))
}

function assertH2RelGate(report) {
  const meta = report.meta
  if (meta.previewOnly !== false) {
    throw new Error('H2 L-rel 需要非 preview-only')
  }
  if (meta.importOkRate !== 1) {
    const offline = report.rounds?.some((r) => String(r.errorCode || '') === 'ENGINE_OFFLINE')
    if (offline) {
      const err = new Error('ENGINE_OFFLINE：请启动 Engine(8081) 并注册 WS')
      err.code = 'ENGINE_OFFLINE'
      throw err
    }
    throw new Error(`H2 L-rel 期望 importOkRate=1，实际 ${meta.importOkRate}`)
  }
  if (meta.sourceExportMode) {
    throw new Error(`H2 L-rel 不应使用 SOURCE_EXPORT_MODE（当前为 ${meta.sourceExportMode}）`)
  }
  if (!meta.sourceExportRowsFile || !String(meta.sourceExportRowsFile).includes('mvp-h2-source-export-rows.json')) {
    throw new Error(`H2 L-rel 期望 sourceExportRowsFile 指向 mvp-h2-source-export-rows.json，实际 ${meta.sourceExportRowsFile}`)
  }
  const row = (report.rounds || []).find((r) => r.importHttpOk === true)
  if (!row) throw new Error('H2 L-rel 无成功 import 轮次')
  if (row.sourceExportRowsCount !== 3) {
    throw new Error(`H2 L-rel 期望 sourceExportRowsCount=3，实际 ${row.sourceExportRowsCount}`)
  }
  if (row.verifySkipped !== false) {
    throw new Error(`H2 L-rel 期望 verifySkipped=false，实际 ${row.verifySkipped}`)
  }
  if (row.verifyPass !== true) {
    throw new Error(`H2 L-rel 期望 verifyPass=true，实际 ${row.verifyPass}`)
  }
}

const zipPath = join(outDir, h2ZipName)
if (!existsSync(zipPath)) {
  console.error(`FAIL: 缺少 ${h2ZipName}，请执行 tools/pack-skill-mvp-golden-h2.ps1`)
  process.exit(2)
}
if (!existsSync(fixturePath)) {
  console.error(`FAIL: missing fixture ${fixturePath}`)
  process.exit(1)
}

console.log('=== H2 L-rel: golden-h2 + SOURCE_EXPORT_ROWS_FILE ===')
runEvolution({
  EVOLUTION_ZIP: h2ZipName,
  SOURCE_EXPORT_ROWS_FILE: fixturePath,
})

const report = readLatest()
try {
  assertH2RelGate(report)
} catch (e) {
  if (e.code === 'ENGINE_OFFLINE') {
    console.error(e.message)
    process.exit(3)
  }
  throw e
}

console.log('OK: H2 L-rel 门禁通过')
process.exit(0)
