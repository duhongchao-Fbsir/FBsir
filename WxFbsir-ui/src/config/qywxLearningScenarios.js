/**
 * Admin → Engine_once 串联编排（学习路径）。
 * needsPayload: 执行前在表单中填写 workflowName / workflowIndex 等，否则跳过或失败。
 * approxTotalSec: 各 step.timeoutSec 之和，用于 UI 预估耗时（实际受网络影响）。
 */
export const QYWX_LEARNING_SCENARIOS = [
  {
    id: 'capability_catalog',
    label: '能力清单（纯静态，无浏览器自动化）',
    description: '仅拉取 QYWEIXIN_CAPABILITY_CATALOG，用于项目映射；不依赖企微页面。',
    approxTotalSec: 20,
    steps: [{ type: 'QYWEIXIN_CAPABILITY_CATALOG', timeoutSec: 20, desc: '程序化能力表 + routeHints' }]
  },
  {
    id: 'sandbox_smoke',
    label: '沙箱巡检（登录态 → 机器人列表 → 工作流列表）',
    description: '验证会话有效；不要求预先创建沙箱工作流。',
    approxTotalSec: 45 + 100 + 100,
    steps: [
      { type: 'QYWEIXIN_CHECK_LOGIN', timeoutSec: 45, desc: '检测企微管理端登录态' },
      { type: 'QYWEIXIN_LIST_ROBOTS', timeoutSec: 100, desc: '管理 Tab 智能机器人列表' },
      { type: 'QYWEIXIN_WORKFLOW_LIST', timeoutSec: 100, desc: '工作流结构化列表' }
    ]
  },
  {
    id: 'workflow_ops',
    label: '工作流操作链（需已存在目标工作流）',
    description: '依赖表单中 workflowName（或 index）。用于与 LIST → EDITOR 等能力对齐。',
    needsFormFields: ['workflowName 或 workflowIndex'],
    approxTotalSec: 100 + 130,
    steps: [
      { type: 'QYWEIXIN_WORKFLOW_LIST', timeoutSec: 100, desc: '列表定位' },
      { type: 'QYWEIXIN_WORKFLOW_OPEN_EDITOR', timeoutSec: 130, desc: '打开编辑器' }
    ]
  },
  {
    id: 'workflow_validate',
    label: '工作流校验链（编辑器检查）',
    description: '用于发布前自检：LIST -> OPEN_EDITOR -> WORKFLOW_VALIDATE。',
    needsFormFields: ['workflowName 或 workflowIndex'],
    approxTotalSec: 100 + 130 + 130,
    steps: [
      { type: 'QYWEIXIN_WORKFLOW_LIST', timeoutSec: 100, desc: '列表定位' },
      { type: 'QYWEIXIN_WORKFLOW_OPEN_EDITOR', timeoutSec: 130, desc: '打开编辑器' },
      { type: 'QYWEIXIN_WORKFLOW_VALIDATE', timeoutSec: 130, desc: '运行检查并采集问题' }
    ]
  },
  {
    id: 'explore_map',
    label: '细粒度 UI 映射',
    description: '多路由采集 Tab/按钮/表头（较慢）。',
    approxTotalSec: 120,
    steps: [{ type: 'QYWEIXIN_EXPLORE_UI_MAP', timeoutSec: 120, desc: 'EXPLORE_UI_MAP' }]
  },
  {
    id: 'full_learn',
    label: '完整学习链（清单→登录→机器人→工作流→UI映射）',
    description: '串联常用能力；总耗时约 6～8 分钟量级。需登录管理器已恢复企微会话。',
    approxTotalSec: 25 + 45 + 100 + 100 + 120,
    steps: [
      { type: 'QYWEIXIN_CAPABILITY_CATALOG', timeoutSec: 25, desc: '能力清单' },
      { type: 'QYWEIXIN_CHECK_LOGIN', timeoutSec: 45, desc: '登录态' },
      { type: 'QYWEIXIN_LIST_ROBOTS', timeoutSec: 100, desc: '机器人列表' },
      { type: 'QYWEIXIN_WORKFLOW_LIST', timeoutSec: 100, desc: '工作流列表' },
      { type: 'QYWEIXIN_EXPLORE_UI_MAP', timeoutSec: 120, desc: '多路由 UI 映射' }
    ]
  }
]
