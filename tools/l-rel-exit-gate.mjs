/**
 * L-rel 发布门槛（路线图 §6.5）：IMPORT 对照行来自冻结 JSON（非 draft 推导），模拟「真实 EXPORT_FULL / 行集」路径。
 * 夹具需与 `docs/workflows/skill-mvp-golden/workflowDraft.expected.json` 三业务节点一致；更新 golden 时请同步刷新 `tools/fixtures/mvp-golden-source-export-rows.json`
 * （或用企微 EXPORT_FULL 结果覆盖同结构行集）。
 *
 * 前置：Wave1 逻辑上已通过；本机需 Admin + Engine(WS)。
 * 用法（仓库根）: node tools/l-rel-exit-gate.mjs
 *
 * 退出码: 0 通过 | 1 断言失败 | 3 ENGINE_OFFLINE
 */
import { existsSync, readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'
import { spawnSync } from 'child_process'

const __dirname = dirname(fileURLToPath(import.meta.url))
const repoRoot = dirname(__dirname)
const outDir = join(repoRoot, 'tools', 'out')
const latestPath = join(outDir, 'evolution-latest.json')
const fixturePath = join(repoRoot, 'tools', 'fixtures', 'mvp-golden-source-export-rows.json')

function runEvolution(env) {
  const node = process.execPath
  const childEnv = { ...process.env }
  delete childEnv.SOURCE_EXPORT_MODE
  delete childEnv.SOURCE_EXPORT_ROWS_FILE
  delete childEnv.EVOLUTION_ZIP
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

function assertRelImportGate(report) {
  const meta = report.meta
  if (meta.previewOnly !== false) {
    throw new Error('L-rel 需要非 preview-only')
  }
  if (meta.importOkRate !== 1) {
    const offline = report.rounds?.some((r) => String(r.errorCode || '') === 'ENGINE_OFFLINE')
    if (offline) {
      const err = new Error('ENGINE_OFFLINE：请启动 Engine(8081) 并注册 WS')
      err.code = 'ENGINE_OFFLINE'
      throw err
    }
    throw new Error(`L-rel 期望 importOkRate=1，实际 ${meta.importOkRate}`)
  }
  if (meta.sourceExportMode) {
    throw new Error(`L-rel 不应使用 SOURCE_EXPORT_MODE（当前为 ${meta.sourceExportMode}）`)
  }
  if (!meta.sourceExportRowsFile || !String(meta.sourceExportRowsFile).includes('mvp-golden-source-export-rows.json')) {
    throw new Error(`L-rel 期望 meta.sourceExportRowsFile 指向 mvp-golden-source-export-rows.json，实际 ${meta.sourceExportRowsFile}`)
  }
  const row = (report.rounds || []).find((r) => r.importHttpOk === true)
  if (!row) throw new Error('L-rel 无成功 import 轮次')
  if (row.sourceExportRowsCount !== 3) {
    throw new Error(`L-rel 期望 sourceExportRowsCount=3，实际 ${row.sourceExportRowsCount}`)
  }
  if (row.verifySkipped !== false) {
    throw new Error(`L-rel 期望 verifySkipped=false，实际 ${row.verifySkipped}`)
  }
  if (row.verifyPass !== true) {
    throw new Error(`L-rel 期望 verifyPass=true（发布对照），实际 ${row.verifyPass}`)
  }
}

if (!existsSync(fixturePath)) {
  console.error(`FAIL: missing fixture ${fixturePath}`)
  process.exit(1)
}

console.log('=== L-rel: MVP golden + SOURCE_EXPORT_ROWS_FILE（冻结行集）===')
runEvolution({
  EVOLUTION_ZIP: '',
  SOURCE_EXPORT_ROWS_FILE: fixturePath,
})

const report = readLatest()
try {
  assertRelImportGate(report)
} catch (e) {
  if (e.code === 'ENGINE_OFFLINE') {
    console.error(e.message)
    process.exit(3)
  }
  throw e
}

console.log('OK: L-rel 门禁通过（payload.sourceExportRows 路径 + verifyPass）')
process.exit(0)
