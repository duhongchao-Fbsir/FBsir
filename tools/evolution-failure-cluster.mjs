/**
 * R4：从 tools/out 下 evolution-r*-import.json 抽取失败/异常签名并聚类（路线图 §3.5「可归因」）。
 * 用法（仓库根）: node tools/evolution-failure-cluster.mjs
 * 写出 tools/out/evolution-failure-cluster-latest.json；退出码恒为 0（报表工具）。
 */
import { readFileSync, writeFileSync, readdirSync, existsSync } from 'fs'
import { join, dirname } from 'path'
import { fileURLToPath } from 'url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const repoRoot = dirname(__dirname)
const outDir = join(repoRoot, 'tools', 'out')
const evolutionLatestPath = join(outDir, 'evolution-latest.json')
const wave2TrackLatestPath = join(outDir, 'wave2-import-track-latest.json')
const h2TrackLatestPath = join(outDir, 'h2-import-track-latest.json')

function resolveImportLeaf(obj) {
  let cur = obj?.data
  if (!cur || typeof cur !== 'object') return null
  while (cur.data != null && typeof cur.data === 'object') {
    cur = cur.data
  }
  return cur
}

function norm(s) {
  return String(s || '')
    .trim()
    .slice(0, 200)
}

function collectFromFile(path, relName) {
  const sigs = []
  let raw
  try {
    raw = JSON.parse(readFileSync(path, 'utf8'))
  } catch {
    sigs.push({ sig: 'parse_error', source: relName })
    return sigs
  }

  if (raw.success === false) {
    sigs.push({
      sig: `http:${norm(raw.errorCode || 'unknown')}:${norm(raw.errorMessage).slice(0, 80)}`,
      source: relName,
    })
    return sigs
  }

  const leaf = resolveImportLeaf(raw)
  if (!leaf) {
    sigs.push({ sig: 'empty_leaf', source: relName })
    return sigs
  }

  if (leaf.success === false) {
    sigs.push({
      sig: `engine:${norm(leaf.errorCode)}:${norm(leaf.errorMessage).slice(0, 80)}`,
      source: relName,
    })
    return sigs
  }

  const vr = leaf.verifyReport
  if (vr && vr.pass === false) {
    const per = Array.isArray(vr.perIndex) ? vr.perIndex : []
    const bad = per.filter((x) => x && x.pass === false)
    if (bad.length) {
      for (const b of bad) {
        sigs.push({
          sig: `verify:idx${b.index}:labelOk=${b.labelOk}:menuOk=${b.menuOk}`,
          source: relName,
        })
      }
    } else {
      sigs.push({ sig: 'verify:pass_false:no_perIndex_detail', source: relName })
    }
  }

  const add = leaf.fill?.addNodes
  if (add?.status && add.status !== 'ok' && add.status !== 'complete') {
    sigs.push({ sig: `fill_status:${norm(add.status)}`, source: relName })
  }
  const failed = add?.failed
  if (Array.isArray(failed)) {
    for (const f of failed) {
      const label = norm(f?.label ?? f?.nodeLabel ?? '?')
      const phase = norm(f?.phase ?? '')
      sigs.push({ sig: `addNodes_failed:${label}:${phase}`, source: relName })
    }
  }

  const logs = add?.perNodeLog
  if (Array.isArray(logs)) {
    for (const log of logs) {
      if (!log || log.ok !== false) continue
      const label = norm(log.label)
      const phase = norm(log.phase)
      sigs.push({ sig: `perNode_ok_false:${phase}:${label}`, source: relName })
    }
  }

  return sigs
}

/** S3 wave2：openPanel 维度聚类（phase + reason + draftType），便于与映射 JSON 解耦评审 Playwright。 */
function collectOpenPanelBuckets(path, relName) {
  let raw
  try {
    raw = JSON.parse(readFileSync(path, 'utf8'))
  } catch {
    return []
  }

  const leaf = resolveImportLeaf(raw)
  if (!leaf || typeof leaf !== 'object') {
    return []
  }

  const logs = leaf.fill?.addNodes?.perNodeLog
  const rows = []
  if (!Array.isArray(logs)) {
    return []
  }

  for (const log of logs) {
    if (!log || typeof log !== 'object') continue
    const ph = String(log.phase || '').trim().toLowerCase()
    if (ph !== 'openpanel') continue
    rows.push({
      label: norm(log.label),
      draftType: norm(log.draftType),
      reason: norm(log.reason || log.message),
      preferredMenuHint: norm(log.chosenMenu || log.preferredMenu || ''),
      source: relName,
      failureScreenshotUrl: log.failureScreenshotUrl ? String(log.failureScreenshotUrl).slice(0, 300) : '',
    })
  }
  return rows
}

function aggregateOpenPanel(rows) {
  const byReason = new Map()
  const byDraftType = new Map()
  const bySig = new Map()
  for (const r of rows) {
    const rk = r.reason || '(empty_reason)'
    const dk = r.draftType || '(empty_draftType)'
    const sig = `openPanel|${rk}|${dk}`
    byReason.set(rk, (byReason.get(rk) || 0) + 1)
    byDraftType.set(dk, (byDraftType.get(dk) || 0) + 1)
    bySig.set(sig, (bySig.get(sig) || 0) + 1)
  }
  const sortMap = (m) =>
    [...m.entries()]
      .map(([k, count]) => ({ key: k, count }))
      .sort((a, b) => b.count - a.count)

  const clusters = [...bySig.entries()]
    .map(([signature, count]) => ({ signature, count }))
    .sort((a, b) => b.count - a.count)

  return {
    rowCount: rows.length,
    byReason: sortMap(byReason),
    byDraftType: sortMap(byDraftType),
    signatureClusters: clusters,
    topOpenPanelSignatures: clusters.slice(0, 15),
  }
}

function main() {
  if (!existsSync(outDir)) {
    console.error('missing tools/out')
    process.exit(0)
  }

  let files = readdirSync(outDir).filter((f) => /^evolution-r\d+-import\.json$/i.test(f))
  /** L-rel 等跑批会覆盖 evolution-latest；取 wave2 追踪与 latest 中较大 round 数以匹配近期 IMPORT 文件 */
  let roundCap = 0
  try {
    if (existsSync(evolutionLatestPath)) {
      const latest = JSON.parse(readFileSync(evolutionLatestPath, 'utf8'))
      roundCap = Math.max(roundCap, latest?.meta?.rounds ?? 0)
    }
    if (existsSync(wave2TrackLatestPath)) {
      const w2 = JSON.parse(readFileSync(wave2TrackLatestPath, 'utf8'))
      roundCap = Math.max(roundCap, w2?.analysis?.rounds ?? w2?.evolutionMeta?.rounds ?? 0)
    }
    if (existsSync(h2TrackLatestPath)) {
      const h2 = JSON.parse(readFileSync(h2TrackLatestPath, 'utf8'))
      roundCap = Math.max(roundCap, h2?.analysis?.rounds ?? h2?.evolutionMeta?.rounds ?? 0)
    }
  } catch {
    roundCap = 0
  }
  if (roundCap > 0) {
    const want = new Set()
    for (let i = 1; i <= roundCap; i++) want.add(`evolution-r${i}-import.json`)
    const filtered = files.filter((f) => want.has(f))
    if (filtered.length > 0) {
      files = filtered
    }
  }
  const bucket = new Map()
  const openPanelRows = []

  for (const f of files.sort()) {
    const full = join(outDir, f)
    openPanelRows.push(...collectOpenPanelBuckets(full, f))
    const sigs = collectFromFile(full, f)
    for (const { sig, source } of sigs) {
      const key = sig
      if (!bucket.has(key)) {
        bucket.set(key, { signature: key, count: 0, sources: [] })
      }
      const e = bucket.get(key)
      e.count++
      if (!e.sources.includes(source)) e.sources.push(source)
    }
  }

  const clusters = [...bucket.values()].sort((a, b) => b.count - a.count)
  const report = {
    generatedAt: new Date().toISOString(),
    filesScanned: files.length,
    signaturesTotal: clusters.reduce((s, x) => s + x.count, 0),
    clusters,
    top10: clusters.slice(0, 10),
    openPanelAnalysis: aggregateOpenPanel(openPanelRows),
  }

  const outPath = join(outDir, 'evolution-failure-cluster-latest.json')
  writeFileSync(outPath, JSON.stringify(report, null, 2), 'utf8')
  console.log('Written:', outPath)
  console.log(JSON.stringify({ top10: report.top10, filesScanned: report.filesScanned }, null, 2))
  process.exit(0)
}

main()
