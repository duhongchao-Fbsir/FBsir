import { readFileSync } from 'fs'
import { join } from 'path'

function summarize(round) {
  const p = join(process.cwd(), 'tools', 'out', `evolution-r${round}-import.json`)
  const root = JSON.parse(readFileSync(p, 'utf8'))
  const data = root?.data?.data || {}
  const add = data?.fill?.addNodes || {}
  const logs = Array.isArray(add.perNodeLog) ? add.perNodeLog : []
  const byType = {}
  for (const row of logs) {
    const t = row?.draftType || 'unknown'
    if (!byType[t]) byType[t] = { ok: 0, fail: 0 }
    if (row?.ok) byType[t].ok += 1
    else byType[t].fail += 1
  }
  return {
    round,
    attempted: add.attempted ?? null,
    failedCount: Array.isArray(add.failed) ? add.failed.length : null,
    status: add.status || null,
    verify: data.verifyReport || null,
    byType,
  }
}

const rounds = process.argv.slice(2).length
  ? process.argv.slice(2).map((x) => parseInt(x, 10)).filter((x) => Number.isInteger(x))
  : [1, 2]

for (const r of rounds) {
  console.log(JSON.stringify(summarize(r), null, 2))
}
