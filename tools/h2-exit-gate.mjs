/**
 * H2：第二套 scene-pack（skill-mvp-golden-h2 / mvp-branch）预览门禁（路线图 H2 / §3.4 黄金集扩展）。
 * 需 Admin；不跑 IMPORT。
 *
 * 用法（仓库根）: node tools/h2-exit-gate.mjs
 * 退出码: 0 通过 | 1 断言失败 | 2 缺 zip
 */
import { existsSync, readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join, basename } from 'path'
import { spawnSync } from 'child_process'

const __dirname = dirname(fileURLToPath(import.meta.url))
const repoRoot = dirname(__dirname)
const outDir = join(repoRoot, 'tools', 'out')
const latestPath = join(outDir, 'evolution-latest.json')
const zipName = 'skill-mvp-golden-h2.zip'

/** 与 mvp-basic 同构线性三业务节点，标定与 golden 一致；H2 作为第二套 pack / 独立 plugin */
const THRESHOLDS = {
  draftEdges: [4],
  sceneEdges: [2],
  nodeBuildSource: 'sceneFlow',
}

function runEvolution(env, extraArgs) {
  const node = process.execPath
  const childEnv = { ...process.env, ...env }
  delete childEnv.SOURCE_EXPORT_MODE
  delete childEnv.SOURCE_EXPORT_ROWS_FILE
  const r = spawnSync(node, [join(repoRoot, 'tools', 'run-evolution-rounds.mjs'), ...extraArgs], {
    cwd: repoRoot,
    encoding: 'utf8',
    env: childEnv,
    maxBuffer: 32 * 1024 * 1024,
  })
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

function assertPreviewTrack(report, th) {
  const meta = report.meta
  const rounds = report.rounds || []
  if (meta.previewOnly !== true) {
    throw new Error('H2 gate 需要 preview-only 跑批')
  }
  if (meta.previewOkRate !== 1) {
    throw new Error(`H2 previewOkRate 期望 1，实际 ${meta.previewOkRate}`)
  }
  const lastZip = basename(String(meta.zipPath || ''))
  if (lastZip !== zipName) {
    throw new Error(`H2 最后一轮 zip 期望 ${zipName}，实际 ${meta.zipPath}`)
  }
  const stab = meta.structureStability
  if (!stab) throw new Error('H2 缺少 structureStability')
  const de = JSON.stringify(stab.draftEdgeUniqueValues || [])
  const se = JSON.stringify(stab.sceneEdgeUniqueValues || [])
  if (de !== JSON.stringify(th.draftEdges)) {
    throw new Error(`H2 draftEdgeUniqueValues 期望 ${JSON.stringify(th.draftEdges)}，实际 ${de}`)
  }
  if (se !== JSON.stringify(th.sceneEdges)) {
    throw new Error(`H2 sceneEdgeUniqueValues 期望 ${JSON.stringify(th.sceneEdges)}，实际 ${se}`)
  }
  for (const row of rounds) {
    if (!row.previewOk) continue
    const src = row.nodeBuildSource
    if (src !== th.nodeBuildSource) {
      throw new Error(`H2 nodeBuildSource 期望 ${th.nodeBuildSource}，某轮为 ${src}`)
    }
  }
}

const zipPath = join(outDir, zipName)
if (!existsSync(zipPath)) {
  console.error(`FAIL: 缺少 ${zipName}，请执行 tools/pack-skill-mvp-golden-h2.ps1`)
  process.exit(2)
}

console.log('=== H2 G-preview: golden-h2 / mvp-branch ===')
runEvolution(
  { EVOLUTION_ZIP: zipName },
  ['--rounds', '5', '--cooldown-ms', '300', '--preview-only'],
)
const report = readLatest()
assertPreviewTrack(report, THRESHOLDS)

console.log('OK: H2 预览门禁通过（第二套 scene-pack）')
process.exit(0)
