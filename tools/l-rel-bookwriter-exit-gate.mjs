/**
 * BookWriter L-rel（S4 发布数据路径）：冻结的 sourceExportRows 夹具（非 SOURCE_EXPORT_MODE=draft），
 * 搭配 `tools/out/fbs-bookwriter-workbuddy-latest.zip`，验证 IMPORT 侧 `verifyReport` 可执行（默认不强制 pass）。
 *
 * 用真实企微 EXPORT_FULL 导出的行集覆盖 `tools/fixtures/bookwriter-source-export-rows.json` 后，可将
 * STRICT_BOOKWRITER_VERIFY=1 打开为发布强门禁（要求 verifyPass=true）。
 *
 * 前置：已 `sync-fbs-bookwriter-dist.ps1`；Admin + Engine(WS)。
 * 用法（仓库根）: node tools/l-rel-bookwriter-exit-gate.mjs
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
const fixturePath = join(repoRoot, 'tools', 'fixtures', 'bookwriter-source-export-rows.json')
const zipName = 'fbs-bookwriter-workbuddy-latest.zip'

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

function assertBookwriterRel(report, fixtureRows) {
  const meta = report.meta
  if (meta.previewOnly !== false) {
    throw new Error('L-rel-BookWriter 需要非 preview-only')
  }
  if (meta.importOkRate !== 1) {
    const offline = report.rounds?.some((r) => String(r.errorCode || '') === 'ENGINE_OFFLINE')
    if (offline) {
      const err = new Error('ENGINE_OFFLINE：请启动 Engine(8081) 并注册 WS')
      err.code = 'ENGINE_OFFLINE'
      throw err
    }
    throw new Error(`L-rel-BookWriter 期望 importOkRate=1，实际 ${meta.importOkRate}`)
  }
  if (meta.sourceExportMode) {
    throw new Error(`L-rel-BookWriter 不应使用 SOURCE_EXPORT_MODE（当前为 ${meta.sourceExportMode}）`)
  }
  const sf = meta.sourceExportRowsFile || ''
  if (!sf.includes('bookwriter-source-export-rows.json')) {
    throw new Error(`L-rel-BookWriter 期望 meta.sourceExportRowsFile 指向 bookwriter-source-export-rows.json，实际 ${sf}`)
  }
  const row = (report.rounds || []).find((r) => r.importHttpOk === true)
  if (!row) throw new Error('L-rel-BookWriter 无成功 import 轮次')
  if (row.sourceExportRowsCount !== fixtureRows.length) {
    throw new Error(`L-rel-BookWriter 期望 sourceExportRowsCount=${fixtureRows.length}，实际 ${row.sourceExportRowsCount}`)
  }
  if (row.verifySkipped !== false) {
    throw new Error(`L-rel-BookWriter 期望 verifySkipped=false（验收可执行），实际 ${row.verifySkipped}`)
  }
  if (row.draftBusinessNodes != null && row.draftBusinessNodes !== fixtureRows.length) {
    console.warn(
      `[WARN] draft 业务节点数 ${row.draftBusinessNodes} 与夹具行数 ${fixtureRows.length} 不一致；请用真实 EXPORT_FULL 刷新夹具或检查 enterprise/sceneFlow 解析。`,
    )
    if (process.env.STRICT_BOOKWRITER_FIXTURE_ALIGN === '1') {
      throw new Error('STRICT_BOOKWRITER_FIXTURE_ALIGN=1：夹具行数必须与 draft 业务节点数一致')
    }
  }
  if (process.env.STRICT_BOOKWRITER_VERIFY === '1' && row.verifyPass !== true) {
    throw new Error(`STRICT_BOOKWRITER_VERIFY=1 要求 verifyPass=true，实际 ${row.verifyPass}`)
  }
}

if (!existsSync(fixturePath)) {
  console.error(`FAIL: missing fixture ${fixturePath}`)
  process.exit(1)
}

const fixtureRows = JSON.parse(readFileSync(fixturePath, 'utf8'))
if (!Array.isArray(fixtureRows)) {
  console.error('FAIL: bookwriter fixture must be a JSON array')
  process.exit(1)
}

const zipPath = join(outDir, zipName)
if (!existsSync(zipPath)) {
  console.error(`FAIL: missing ${zipPath} — 请先运行 tools/sync-fbs-bookwriter-dist.ps1`)
  process.exit(1)
}

console.log('=== L-rel-BookWriter: workbuddy zip + SOURCE_EXPORT_ROWS_FILE（冻结行集）===')
runEvolution({
  EVOLUTION_ZIP: zipName,
  SOURCE_EXPORT_ROWS_FILE: fixturePath,
})

const report = readLatest()
try {
  assertBookwriterRel(report, fixtureRows)
} catch (e) {
  if (e.code === 'ENGINE_OFFLINE') {
    console.error(e.message)
    process.exit(3)
  }
  throw e
}

console.log(
  'OK: L-rel-BookWriter 门禁通过（verify 可执行；若需发布强校验请设 STRICT_BOOKWRITER_VERIFY=1 并换真实 EXPORT_FULL 行集）',
)
process.exit(0)
