/**
 * 企业微信「智能机器人 → AI 助手」后台与本项目侧栏 / 能力的对齐关系。
 * Engine 能力 QYWEIXIN_EXPLORE_UI_MAP 返回的 projectRouteHints 与此同源维护。
 */
export const QYWX_AI_HELPER_ROUTE_MAP = [
  {
    pageId: 'workflow',
    label: '工作流',
    backendHash: '#/aiHelper/list?tab=workflow',
    projectNavKey: 'workflow',
    engineTypes: [
      'QYWEIXIN_WORKFLOW_LIST',
      'QYWEIXIN_WORKFLOW_OPEN_EDITOR',
      'QYWEIXIN_WORKFLOW_CREATE',
      'QYWEIXIN_WORKFLOW_IMPORT_DRAFT',
      'QYWEIXIN_WORKFLOW_VALIDATE',
      'QYWEIXIN_WORKFLOW_DELETE'
    ]
  },
  {
    pageId: 'manage',
    label: '管理',
    backendHash: '#/aiHelper/manage',
    projectNavKey: 'manage',
    engineTypes: ['QYWEIXIN_LIST_ROBOTS']
  },
  {
    pageId: 'session',
    label: '连接与会话',
    backendHash: '(会话/扫码，无单一 hash)',
    projectNavKey: 'session',
    engineTypes: ['QYWEIXIN_CHECK_LOGIN', 'QYWEIXIN_SCAN_LOGIN']
  },
  {
    pageId: 'lab',
    label: '页面探索 / 细粒度映射',
    backendHash: '(研发)',
    projectNavKey: 'lab',
    engineTypes: [
      'QYWEIXIN_EXPLORE_AIHELPER',
      'QYWEIXIN_EXPLORE_WORKFLOW',
      'QYWEIXIN_EXPLORE_UI_MAP',
      'QYWEIXIN_CAPABILITY_CATALOG'
    ]
  }
]
