import request from '@/utils/request'

/** 单次 Engine 能力调用（HTTP 阻塞等待），与连接页健康检查同源 */
export function engineOnceRequest(data, config) {
  return request({
    url: '/ws/engine/request',
    method: 'post',
    data,
    ...config
  })
}

/** Engine 返回静态「企微能力清单」，与 UI 侧 qywxAiHelperRouteMap 对表 */
export function fetchQywxCapabilityCatalog({ engineId, userId, timeoutSec = 15 }) {
  return engineOnceRequest(
    {
      engineId,
      type: 'QYWEIXIN_CAPABILITY_CATALOG',
      userId: userId || '1',
      timeout: timeoutSec,
      payload: {}
    },
    { timeout: (timeoutSec + 5) * 1000 }
  )
}

/** 预览 Skill 包迁移结果（L1骨架 + L2提示） */
export function previewSkillMigration(file, config) {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/ws/admin/skill/preview',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
    ...config
  })
}

/** 查询最近沉淀的 Skill 迁移颗粒 */
export function fetchRecentSkillGranules(limit = 20, config) {
  return request({
    url: '/ws/admin/skill/granules/recent',
    method: 'get',
    params: { limit },
    ...config
  })
}

/** 查询某个 skillId 的颗粒沉淀快照 */
export function fetchSkillGranulesBySkillId(skillId, config) {
  return request({
    url: `/ws/admin/skill/granules/skill/${encodeURIComponent(skillId)}`,
    method: 'get',
    ...config
  })
}

/** 将工作流校验结果追加回写到 skill 颗粒目录 */
export function appendSkillValidationWriteback(skillId, validationResult, config) {
  return request({
    url: `/ws/admin/skill/granules/skill/${encodeURIComponent(skillId)}/append`,
    method: 'post',
    data: { validationResult },
    ...config
  })
}
