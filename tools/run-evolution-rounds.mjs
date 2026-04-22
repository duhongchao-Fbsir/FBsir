/**
 * 多轮演化跑批：每轮 login -> preview -> IMPORT_DRAFT，输出汇总指标与 tools/out 落盘。
 * 用法: node tools/run-evolution-rounds.mjs [--rounds 5] [--cooldown-ms 2000] [--preview-only]
 */
import { writeFileSync, mkdirSync, readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join, isAbsolute, basename } from 'path'
import {
  ensureZip,
  loginToken,
  previewDraft,
  importDraft,
  metricsFromPreview,
  metricsFromImport,
  buildExpectedRowsFromDraft,
} from './lib/mvp-evolution-runner.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const repoRoot = dirname(__dirname)
const outDir = join(repoRoot, 'tools', 'out')
mkdirSync(outDir, { recursive: true })

const args = process.argv.slice(2)
let rounds = 5
let cooldownMs = 2000
let previewOnly = false
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--rounds' && args[i + 1]) {
    rounds = Math.max(1, parseInt(args[++i], 10) || 5)
  } else if (args[i] === '--cooldown-ms' && args[i + 1]) {
    cooldownMs = Math.max(0, parseInt(args[++i], 10) || 0)
  } else if (args[i] === '--preview-only') {
    previewOnly = true
  }
}

const defaultZipName = 'skill-mvp-golden.zip'
const configuredZip = (process.env.EVOLUTION_ZIP || '').trim()
const zipPath = configuredZip
  ? (isAbsolute(configuredZip) ? configuredZip : join(outDir, configuredZip))
  : join(outDir, defaultZipName)
const autoPackGolden = basename(zipPath).toLowerCase() === defaultZipName
const ADMIN = process.env.ADMIN_BASE || 'http://127.0.0.1:8080'
const ENGINE_ID = process.env.ENGINE_ID || 'engine-dev-001'
const USER_ID = process.env.USER_ID || '1'
const USER = process.env.ADMIN_USER || 'admin'
const PASS = process.env.ADMIN_PASS || 'admin123'
const TIMEOUT = parseInt(process.env.IMPORT_TIMEOUT || '180', 10)
const SOURCE_EXPORT_MODE = (process.env.SOURCE_EXPORT_MODE || '').trim().toLowerCase()
const SOURCE_EXPORT_ROWS_FILE = (process.env.SOURCE_EXPORT_ROWS_FILE || '').trim()

function mean(a) {
  if (!a.length) return null
  return a.reduce((s, x) => s + x, 0) / a.length
}
function stdev(a) {
  if (a.length < 2) return 0
  const m = mean(a)
  return Math.sqrt(a.reduce((s, x) => s + (x - m) ** 2, 0) / (a.length - 1))
}
function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

const runAt = new Date().toISOString()
const series = []

for (let r = 1; r <= rounds; r++) {
  const t0 = Date.now()
  const row = { round: r, startedAt: new Date().toISOString() }
  try {
    ensureZip(repoRoot, zipPath, { autoPackGolden })
    const token = loginToken(ADMIN, USER, PASS)
    const { preview, draft } = previewDraft(ADMIN, token, zipPath)
    if (!draft) {
      row.previewOk = false
      row.error = 'no_workflow_draft'
    } else {
      row.previewOk = true
      Object.assign(row, metricsFromPreview(preview, draft))
    }
    if (row.previewOk && !previewOnly) {
      let sourceExportRows = []
      if (SOURCE_EXPORT_MODE === 'draft') {
        sourceExportRows = buildExpectedRowsFromDraft(draft)
      } else if (SOURCE_EXPORT_ROWS_FILE) {
        const rawRows = JSON.parse(readFileSync(SOURCE_EXPORT_ROWS_FILE, 'utf8'))
        sourceExportRows = Array.isArray(rawRows) ? rawRows : []
      }
      const { raw, parsed, parseError } = importDraft(
        ADMIN,
        token,
        draft,
        ENGINE_ID,
        USER_ID,
        TIMEOUT,
        { sourceExportRows },
      )
      const importPath = join(outDir, `evolution-r${r}-import.json`)
      writeFileSync(importPath, raw, 'utf8')
      row.importPath = importPath
      row.sourceExportRowsCount = sourceExportRows.length
      if (parseError) {
        row.importParseError = true
        row.importHttpOk = false
      } else {
        Object.assign(row, metricsFromImport(parsed))
      }
    }
  } catch (e) {
    row.error = String(e?.message || e)
  }
  row.durationMs = Date.now() - t0
  series.push(row)
  if (r < rounds && cooldownMs > 0) {
    await sleep(cooldownMs)
  }
}

const previewOkCount = series.filter((x) => x.previewOk).length
const importAttempts = series.filter((x) => x.importHttpOk !== undefined && x.previewOk && !previewOnly)
const importOkCount = importAttempts.filter((x) => x.importHttpOk).length
const engineOfflineCount = series.filter((x) => x.errorCode === 'ENGINE_OFFLINE').length
const times = importAttempts.filter((x) => x.importHttpOk && x.executionTimeMs != null).map((x) => x.executionTimeMs)
const verifyPassCount = series.filter((x) => x.verifyPass === true).length
const verifySkippedCount = series.filter((x) => x.verifySkipped === true).length
const partialFillCount = series.filter((x) => x.fillStatus === 'partial').length

const draftEdges = series.filter((x) => x.draftEdgeCount != null).map((x) => x.draftEdgeCount)
const sceneEdges = series.filter((x) => x.sceneEdgeCount != null).map((x) => x.sceneEdgeCount)

const aggregate = {
  runAt,
  rounds,
  cooldownMs,
  previewOnly,
  zipPath,
  autoPackGolden,
  sourceExportMode: SOURCE_EXPORT_MODE || null,
  sourceExportRowsFile: SOURCE_EXPORT_ROWS_FILE || null,
  previewOkRate: rounds ? previewOkCount / rounds : 0,
  importAttemptedRounds: importAttempts.length,
  importOkRate: importAttempts.length ? importOkCount / importAttempts.length : null,
  engineOfflineCount,
  verifyPassCount,
  verifySkippedCount,
  partialFillCount,
  executionTimeMs: times.length
    ? { mean: Math.round(mean(times)), stdev: Math.round(stdev(times)), min: Math.min(...times), max: Math.max(...times), n: times.length }
    : null,
  structureStability:
    draftEdges.length && sceneEdges.length
      ? {
          draftEdgeUniqueValues: [...new Set(draftEdges)].sort((a, b) => a - b),
          sceneEdgeUniqueValues: [...new Set(sceneEdges)].sort((a, b) => a - b),
        }
      : null,
}

const report = { meta: aggregate, rounds: series }
const summaryPath = join(outDir, `evolution-summary-${runAt.replace(/[:.]/g, '-')}.json`)
writeFileSync(summaryPath, JSON.stringify(report, null, 2), 'utf8')
writeFileSync(join(outDir, 'evolution-latest.json'), JSON.stringify(report, null, 2), 'utf8')

console.log(JSON.stringify(aggregate, null, 2))
console.log('Written:', summaryPath, join(outDir, 'evolution-latest.json'))

process.exit(0)
