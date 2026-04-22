/**
 * Wave1 收口门禁：在进入 Wave2（Engine 面板专项 / 真实 EXPORT / enterprise 语义线）之前必须通过。
 *
 * G-preview（仅需 Admin + ZIP）：MVP golden 与 BookWriter 预览结构落在标定区间且无漂移。
 * G-import（需 Admin + Engine）：MVP golden 一轮 IMPORT，`SOURCE_EXPORT_MODE=draft` 下验收管线可执行（非 skipped）。
 *
 * 用法（仓库根目录）:
 *   node tools/wave1-exit-gate.mjs                    # 默认：G-preview + G-import
 *   node tools/wave1-exit-gate.mjs --preview-only   # 仅 G-preview（无 Engine 也可用）
 *
 * 退出码: 0 通过 | 1 断言失败 | 2 缺 zip/打包失败 | 3 仅 import 阶段 Engine 不可用（可改 --preview-only）
 */
import { existsSync, readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join, basename } from 'path'
import { spawnSync } from 'child_process'

const __dirname = dirname(fileURLToPath(import.meta.url))
const repoRoot = dirname(__dirname)
const outDir = join(repoRoot, 'tools', 'out')
const latestPath = join(outDir, 'evolution-latest.json')
const bookWriterZipName = 'fbs-bookwriter-workbuddy-latest.zip'

/** 文档与 README 标定值；变更须同步 skill-evolution-roadmap / skill-fbs-bookwriter README */
const THRESHOLDS = {
  golden: {
    draftEdges: [4],
    sceneEdges: [2],
    nodeBuildSource: 'sceneFlow',
  },
  bookWriter: {
    draftEdges: [10],
    sceneEdges: [0],
    nodeBuildSource: 'stageFallback',
  },
}

const args = process.argv.slice(2)
const previewOnlyGate = args.includes('--preview-only')

function runEvolution(env, extraArgs) {
  const node = process.execPath
  const childEnv = { ...process.env, ...env }
  const r = spawnSync(
    node,
    [join(repoRoot, 'tools', 'run-evolution-rounds.mjs'), ...extraArgs],
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

function assertPreviewTrack(name, report, th) {
  const meta = report.meta
  const rounds = report.rounds || []
  if (meta.previewOkRate !== 1) {
    throw new Error(`[${name}] previewOkRate 期望 1，实际 ${meta.previewOkRate}`)
  }
  const stab = meta.structureStability
  if (!stab) throw new Error(`[${name}] 缺少 structureStability`)
  const de = JSON.stringify(stab.draftEdgeUniqueValues || [])
  const se = JSON.stringify(stab.sceneEdgeUniqueValues || [])
  if (de !== JSON.stringify(th.draftEdges)) {
    throw new Error(`[${name}] draftEdgeUniqueValues 期望 ${JSON.stringify(th.draftEdges)}，实际 ${de}`)
  }
  if (se !== JSON.stringify(th.sceneEdges)) {
    throw new Error(`[${name}] sceneEdgeUniqueValues 期望 ${JSON.stringify(th.sceneEdges)}，实际 ${se}`)
  }
  for (const row of rounds) {
    if (!row.previewOk) continue
    const src = row.nodeBuildSource
    if (src !== th.nodeBuildSource) {
      throw new Error(`[${name}] nodeBuildSource 期望 ${th.nodeBuildSource}，某轮为 ${src}`)
    }
  }
}

function assertImportGate(report) {
  const meta = report.meta
  if (meta.previewOnly !== false) {
    throw new Error('G-import 需要非 preview-only 跑批产物')
  }
  const ok = meta.importOkRate
  if (ok !== 1) {
    const offline = report.rounds?.some((r) => String(r.errorCode || '') === 'ENGINE_OFFLINE')
    if (offline) {
      const err = new Error('ENGINE_OFFLINE：请启动 Engine(8081) 并注册 WS，或改用 --preview-only')
      err.code = 'ENGINE_OFFLINE'
      throw err
    }
    const bad = report.rounds?.find((r) => r.importHttpOk === false)
    const msg = `${bad?.errorMessage || ''}`
    const code = String(bad?.errorCode || '')
    if (code === 'TIMEOUT') {
      const err = new Error(
        `G-import 超时：可提高 WAVE1_IMPORT_TIMEOUT（当前请求 ${process.env.WAVE1_IMPORT_TIMEOUT || '420'}s）或检查 Engine/企微会话是否卡住。`,
      )
      err.code = 'IMPORT_TIMEOUT'
      throw err
    }
    if (
      code === 'TASK_ERROR' &&
      (msg.includes('未登录') || msg.includes('QYWEIXIN_SCAN_LOGIN') || msg.includes('扫码'))
    ) {
      const err = new Error(
        'G-import 需要已登录的企微会话：Engine 侧先完成 QYWEIXIN_SCAN_LOGIN（扫码），再重跑本门禁（或仅用 --preview-only）。',
      )
      err.code = 'NEED_QYWX_LOGIN'
      throw err
    }
    throw new Error(`G-import 期望 importOkRate=1，实际 ${ok}`)
  }
  const rows = report.rounds || []
  const row = rows.find((r) => r.importHttpOk === true)
  if (!row) throw new Error('G-import 无成功 import 轮次')
  /** metrics 仅在 verifyReport 存在且 skipped===true 时填 false；省略 skipped 视为未跳过（与路线图一致） */
  if (row.verifySkipped === true) {
    throw new Error(`G-import 期望验收未跳过（draft 对照），verifySkipped=${row.verifySkipped}`)
  }
}

console.log('=== Wave1 G-preview: MVP golden ===')
runEvolution(
  { EVOLUTION_ZIP: '' },
  ['--rounds', '5', '--cooldown-ms', '300', '--preview-only'],
)
let report = readLatest()
assertPreviewTrack('golden', report, THRESHOLDS.golden)

const bwZip = join(outDir, bookWriterZipName)
if (!existsSync(bwZip)) {
  console.error(`FAIL: 缺少 ${bookWriterZipName}，请执行 tools/sync-fbs-bookwriter-dist.ps1`)
  process.exit(2)
}

console.log('=== Wave1 G-preview: BookWriter workbuddy ===')
runEvolution({ EVOLUTION_ZIP: bookWriterZipName }, ['--rounds', '5', '--cooldown-ms', '300', '--preview-only'])
report = readLatest()
const lastZip = basename(String(report.meta?.zipPath || ''))
if (lastZip !== bookWriterZipName) {
  console.error('最后一轮 zip 非 workbuddy:', report.meta?.zipPath)
  process.exit(1)
}
assertPreviewTrack('bookwriter', report, THRESHOLDS.bookWriter)

if (previewOnlyGate) {
  console.log('OK: Wave1 G-preview 通过（已跳过 G-import）')
  process.exit(0)
}

console.log('=== Wave1 G-import: MVP golden + draft 对照验收可执行 ===')
runEvolution(
  {
    EVOLUTION_ZIP: '',
    SOURCE_EXPORT_MODE: 'draft',
    /** Admin `future.get(timeout)`：企微 IMPORT_DRAFT 常有 Playwright，180s 易误判超时 */
    IMPORT_TIMEOUT: process.env.WAVE1_IMPORT_TIMEOUT || '420',
  },
  ['--rounds', '1', '--cooldown-ms', '300'],
)
report = readLatest()
try {
  assertImportGate(report)
} catch (e) {
  if (e.code === 'ENGINE_OFFLINE') {
    console.error(e.message)
    process.exit(3)
  }
  if (e.code === 'NEED_QYWX_LOGIN') {
    console.error(e.message)
    process.exit(5)
  }
  if (e.code === 'IMPORT_TIMEOUT') {
    console.error(e.message)
    process.exit(6)
  }
  throw e
}

console.log('OK: Wave1 收口通过（G-preview + G-import）')
process.exit(0)
