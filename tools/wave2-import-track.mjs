/**
 * Wave2 第一波落地：MVP golden + SOURCE_EXPORT_MODE=draft，多轮 IMPORT，落盘指标供 R2/R3 迭代对照。
 *
 * 用法（仓库根）:
 *   node tools/wave2-import-track.mjs [--rounds 5] [--gate-failed-max N] [--cooldown-ms 500]
 *                                   [--require-verify-pass]
 *
 * - 不写死退出码与业务阈值；默认 exit 0。若提供 --gate-failed-max，则任意一轮
 *   addNodesFailedCount > N 时 exit 1（可用于 CI 渐进收紧）。
 * - 若提供 --require-verify-pass：要求每一轮 evolution `verifyPass===true`，否则 exit 1（R3 收紧）。
 */
import { spawnSync } from 'child_process'
import { writeFileSync, mkdirSync, readFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const repoRoot = dirname(__dirname)
const outDir = join(repoRoot, 'tools', 'out')
mkdirSync(outDir, { recursive: true })

let rounds = 3
let gateFailedMax = null
let cooldownMs = 500
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

const node = process.execPath
const env = {
  ...process.env,
  SOURCE_EXPORT_MODE: 'draft',
}
delete env.EVOLUTION_ZIP
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
const attempted = []
let partial = 0
let verifyPass = 0
let openPanelFailsTotal = 0
for (const row of series) {
  const f = row.addNodesFailedCount
  if (typeof f === 'number') failedCounts.push(f)
  const a = row.addNodesAttempted
  if (typeof a === 'number') attempted.push(a)
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
  wave: 'Wave2-import-track',
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

const reportPath = join(outDir, 'wave2-import-track-latest.json')
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
