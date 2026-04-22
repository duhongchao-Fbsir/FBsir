/**
 * H2：skill-mvp-golden-h2 + SOURCE_EXPORT_MODE=draft，多轮 IMPORT（与 Wave2 对称，独立 zip）。
 *
 * 用法（仓库根）:
 *   node tools/h2-import-track.mjs [--rounds 3] [--gate-failed-max N] [--cooldown-ms 500] [--require-verify-pass]
 *
 * 需先 `tools/pack-skill-mvp-golden-h2.ps1` 生成 tools/out/skill-mvp-golden-h2.zip。
 */
import { spawnSync } from 'child_process'
import { writeFileSync, mkdirSync, readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const repoRoot = dirname(__dirname)
const outDir = join(repoRoot, 'tools', 'out')
const h2ZipName = 'skill-mvp-golden-h2.zip'
mkdirSync(outDir, { recursive: true })

let rounds = 3
let gateFailedMax = null
/** 首轮 IMPORT 可能接近 Admin 默认 240s；引擎侧未完成时次轮易 DUPLICATE_REQUEST，故默认冷却加长 */
let cooldownMs = 20000
let requireVerifyPass = false
const argv = process.argv.slice(2)
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--rounds' && argv[i + 1]) rounds = Math.max(1, parseInt(argv[++i], 10) || 3)
  else if (argv[i] === '--gate-failed-max' && argv[i + 1])
    gateFailedMax = parseInt(argv[++i], 10)
  else if (argv[i] === '--cooldown-ms' && argv[i + 1])
    cooldownMs = Math.max(0, parseInt(argv[++i], 10) || 0)
  else if (argv[i] === '--require-verify-pass') requireVerifyPass = true
}

const zipPath = join(outDir, h2ZipName)
if (!existsSync(zipPath)) {
  console.error(`FAIL: 缺少 ${h2ZipName}，请执行 tools/pack-skill-mvp-golden-h2.ps1`)
  process.exit(2)
}

const node = process.execPath
const env = {
  ...process.env,
  SOURCE_EXPORT_MODE: 'draft',
  EVOLUTION_ZIP: h2ZipName,
  IMPORT_TIMEOUT: process.env.IMPORT_TIMEOUT || '420',
}
delete env.SOURCE_EXPORT_ROWS_FILE

const r = spawnSync(
  node,
  [
    join(repoRoot, 'tools', 'run-evolution-rounds.mjs'),
    '--rounds',
    String(rounds),
    '--cooldown-ms',
    String(cooldownMs),
  ],
  {
    cwd: repoRoot,
    encoding: 'utf8',
    env,
    maxBuffer: 50 * 1024 * 1024,
  },
)

if (r.status !== 0) {
  console.error(r.stderr || r.stdout || 'run-evolution-rounds failed')
  process.exit(r.status ?? 1)
}

const latestPath = join(outDir, 'evolution-latest.json')
if (!existsSync(latestPath)) {
  console.error('missing evolution-latest.json')
  process.exit(1)
}

function countOpenPanelInImportJson(importPath) {
  if (!importPath || !existsSync(importPath)) return 0
  try {
    const raw = JSON.parse(readFileSync(importPath, 'utf8'))
    const logs = raw?.data?.data?.fill?.addNodes?.perNodeLog ?? []
    if (!Array.isArray(logs)) return 0
    return logs.filter((x) => x && x.phase === 'openPanel').length
  } catch {
    return 0
  }
}

const latest = JSON.parse(readFileSync(latestPath, 'utf8'))
const series = Array.isArray(latest.rounds) ? latest.rounds : []
const failedCounts = []
let partial = 0
let verifyPass = 0
let openPanelFailsTotal = 0
for (const row of series) {
  const f = row.addNodesFailedCount
  if (typeof f === 'number') failedCounts.push(f)
  if (row.fillStatus === 'partial') partial++
  if (row.verifyPass === true) verifyPass++
  const ip = row.importPath
  if (typeof ip === 'string' && existsSync(ip)) {
    openPanelFailsTotal += countOpenPanelInImportJson(ip)
  }
}

const meanFailed =
  failedCounts.length > 0
    ? failedCounts.reduce((s, x) => s + x, 0) / failedCounts.length
    : null
const maxFailed = failedCounts.length ? Math.max(...failedCounts) : null

const analysis = {
  wave: 'H2-import-track',
  zip: h2ZipName,
  rounds: series.length,
  meanAddNodesFailed: meanFailed != null ? Math.round(meanFailed * 100) / 100 : null,
  maxAddNodesFailed: maxFailed,
  partialFillRounds: partial,
  verifyPassRounds: verifyPass,
  openPanelPhaseFailsTotal: openPanelFailsTotal,
  importOkRate: latest.meta?.importOkRate ?? null,
  gateFailedMax,
  requireVerifyPass,
  gateBroken: false,
  verifyGateBroken: false,
}

if (gateFailedMax != null && failedCounts.some((x) => x > gateFailedMax)) {
  analysis.gateBroken = true
}

if (requireVerifyPass && series.length > 0) {
  const bad = series.filter((row) => row.verifyPass !== true)
  if (bad.length > 0) {
    analysis.verifyGateBroken = true
    analysis.gateBroken = true
  }
}

const reportPath = join(outDir, 'h2-import-track-latest.json')
writeFileSync(
  reportPath,
  JSON.stringify(
    {
      generatedAt: new Date().toISOString(),
      analysis,
      evolutionMeta: latest.meta ?? null,
    },
    null,
    2,
  ),
  'utf8',
)

console.log(JSON.stringify(analysis, null, 2))
console.log('Written:', reportPath)

process.exit(analysis.gateBroken ? 1 : 0)
