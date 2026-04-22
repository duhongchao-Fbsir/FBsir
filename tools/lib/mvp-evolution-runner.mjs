/**
 * Shared MVP evolution probe: preview + optional IMPORT_DRAFT, structured metrics.
 */
import { existsSync } from 'fs'
import { execFileSync } from 'child_process'
import { join } from 'path'

export function curlExec(extraArgs) {
  return execFileSync('curl.exe', extraArgs, {
    encoding: 'utf8',
    maxBuffer: 50 * 1024 * 1024,
  })
}

export function ensureZip(repoRoot, zipPath, options = {}) {
  const { autoPackGolden = true } = options
  if (existsSync(zipPath)) {
    return
  }
  if (!autoPackGolden) {
    throw new Error(`zip_not_found: ${zipPath}. 请先执行 tools/sync-fbs-bookwriter-dist.ps1 或手动放置文件。`)
  }
  execFileSync(
    'powershell.exe',
    ['-ExecutionPolicy', 'Bypass', '-File', join(repoRoot, 'tools', 'pack-skill-mvp-golden.ps1')],
    { encoding: 'utf8', stdio: 'inherit' },
  )
  if (!existsSync(zipPath)) {
    throw new Error(`zip_not_found_after_pack: ${zipPath}`)
  }
}

export function loginToken(admin, user, pass) {
  const loginBody = JSON.stringify({ username: user, password: pass, code: '', uuid: '' })
  const raw = curlExec([
    '-sS',
    '-X',
    'POST',
    `${admin}/login`,
    '-H',
    'Content-Type: application/json',
    '-d',
    loginBody,
  ])
  const login = JSON.parse(raw)
  if (!login.token) throw new Error(`login_failed: ${raw.slice(0, 400)}`)
  return login.token
}

export function previewDraft(admin, token, zipPath) {
  const raw = curlExec([
    '-sS',
    '-X',
    'POST',
    `${admin}/ws/admin/skill/preview`,
    '-H',
    `Authorization: Bearer ${token}`,
    '-F',
    `file=@${zipPath}`,
  ])
  const preview = JSON.parse(raw)
  const draft = preview?.data?.workflowDraft
  return { raw, preview, draft }
}

function inferMenuFromDraftType(type, fallback) {
  const t = String(type || '').trim().toLowerCase()
  if (t === 'input') return '设置变量'
  if (t === 'output') return '结束'
  if (t === 'check') return '问题分类'
  if (t === 'process' || t === 'llm' || t === '') return '大模型问答'
  return fallback
}

export function buildExpectedRowsFromDraft(draft) {
  const nodes = Array.isArray(draft?.nodes) ? draft.nodes : []
  const out = []
  for (const n of nodes) {
    const type = String(n?.type || '').trim().toLowerCase()
    if (type === 'start' || type === 'end') continue
    const label = String(n?.label || `节点_${out.length}`).trim() || `节点_${out.length}`
    const spec = n?.editorNodeSpec
    const fromSpec = spec && (spec.preferredMenu || spec.preferredmenu)
    const inferredMenu = fromSpec
      ? String(fromSpec).trim() || inferMenuFromDraftType(type, '大模型问答')
      : inferMenuFromDraftType(type, '大模型问答')
    out.push({
      index: out.length,
      labelLine: label,
      inferredMenu,
      panelSnapshot: {
        panelTitle: label,
        panelTextDigest: label,
        fields: [],
      },
    })
  }
  return out
}

export function importDraft(admin, token, draft, engineId, userId, timeoutSec, options = {}) {
  const sourceExportRows = Array.isArray(options.sourceExportRows) ? options.sourceExportRows : []
  const lifecycle = (process.env.QYWX_WORKFLOW_LIFECYCLE || 'TEMP_TEST').trim()
  const cleanupCreator = (process.env.QYWX_WORKFLOW_CLEANUP_CREATOR || '').trim()
  const skipCleanup = /^(1|true|yes)$/i.test(String(process.env.QYWX_SKIP_TEMP_WORKFLOW_CLEANUP || ''))

  const engineReq = {
    engineId,
    type: 'QYWEIXIN_WORKFLOW_IMPORT_DRAFT',
    userId: userId,
    timeout: timeoutSec,
    payload: {
      workflowDraft: draft,
      verifyImport: true,
      verifyMaxRounds: 2,
      workflowLifecycle: lifecycle,
    },
  }
  if (sourceExportRows.length > 0) {
    engineReq.payload.sourceExportRows = sourceExportRows
  }
  if (cleanupCreator) {
    engineReq.payload.workflowCleanupCreator = cleanupCreator
  }
  if (skipCleanup) {
    engineReq.payload.skipTemporaryWorkflowCleanup = true
  }
  const raw = curlExec([
    '-sS',
    '-X',
    'POST',
    `${admin}/ws/engine/request`,
    '-H',
    `Authorization: Bearer ${token}`,
    '-H',
    'Content-Type: application/json',
    '-d',
    JSON.stringify(engineReq),
  ])
  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch {
    return { raw, parsed: null, parseError: true }
  }
  return { raw, parsed, parseError: false }
}

export function metricsFromPreview(preview, draft) {
  const sf = preview?.data?.ir?.sceneFlow ?? preview?.data?.sceneFlow
  const stats = sf?.stats || {}
  const edges = draft?.edges || []
  const nodes = draft?.nodes || []
  const biz = nodes.filter((n) => n.type !== 'start' && n.type !== 'end').length
  return {
    sceneEdgeCount: stats.edgeCount ?? null,
    sceneStepCount: stats.stepCount ?? null,
    draftEdgeCount: edges.length,
    draftNodeCount: nodes.length,
    draftBusinessNodes: biz,
    nodeBuildSource: draft?.buildDebug?.nodeBuildSource ?? null,
    previewCode: preview?.code ?? null,
  }
}

/** Admin HTTP：`data` 内可能是 `{ success, data: 业务对象 }`，沿 `.data` 链取到叶子业务字典。 */
function resolveImportResultObject(parsed) {
  let cur = parsed?.data
  if (!cur || typeof cur !== 'object') return null
  while (cur.data != null && typeof cur.data === 'object') {
    cur = cur.data
  }
  return cur
}

export function metricsFromImport(parsed) {
  if (!parsed || parsed.success === false) {
    return {
      importHttpOk: false,
      errorCode: parsed?.errorCode ?? null,
      errorMessage: parsed?.errorMessage ?? null,
      executionTimeMs: null,
      verifyPass: null,
      verifySkipped: null,
      verifyReason: null,
      fillStatus: null,
      addNodesAttempted: null,
      addNodesFailedCount: null,
      editorWorkflowId: null,
    }
  }
  const inner = resolveImportResultObject(parsed) ?? parsed?.data?.data ?? parsed?.data
  /** Admin 常返回外层 HTTP success=true，而 Engine 业务在叶子 success=false（未登录、任务失败等）。 */
  if (inner && typeof inner === 'object' && inner.success === false) {
    return {
      importHttpOk: false,
      errorCode: inner.errorCode ?? null,
      errorMessage: inner.errorMessage ?? null,
      executionTimeMs: parsed.executionTime ?? null,
      verifyPass: null,
      verifySkipped: null,
      verifyReason: null,
      fillStatus: null,
      addNodesAttempted: null,
      addNodesFailedCount: null,
      editorWorkflowId: null,
    }
  }
  const verify = inner?.verifyReport
  const fill = inner?.fill
  const addNodes = fill?.addNodes
  const failed = addNodes?.failed
  /** Engine 有时省略 skipped：只要存在 verifyReport 即视为验收已执行（非 skipped） */
  let verifySkipped = null
  if (verify != null && typeof verify === 'object') {
    verifySkipped = verify.skipped === true
  }
  return {
    importHttpOk: true,
    errorCode: null,
    errorMessage: null,
    executionTimeMs: parsed.executionTime ?? null,
    verifyPass: verify?.pass ?? null,
    verifySkipped,
    verifyReason: verify?.reason ?? verify?.skippedReason ?? null,
    fillStatus: addNodes?.status ?? null,
    addNodesAttempted: addNodes?.attempted ?? null,
    addNodesFailedCount: Array.isArray(failed) ? failed.length : null,
    editorWorkflowId: inner?.editorWorkflowId ?? null,
  }
}
