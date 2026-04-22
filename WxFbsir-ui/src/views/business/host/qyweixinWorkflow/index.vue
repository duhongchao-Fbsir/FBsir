<template>
  <div class="app-container qywx-lab">
    <!-- 顶栏：仿管理后台路径条 + 全局参数 -->
    <div class="qywx-topbar">
      <div class="qywx-breadcrumb">
        <span class="qywx-bc-muted">管理工具</span>
        <span class="qywx-bc-sep">/</span>
        <span class="qywx-bc-muted">智能机器人</span>
        <span class="qywx-bc-sep">/</span>
        <span class="qywx-bc-strong">AI 助手</span>
        <el-tag size="small" type="info" class="qywx-bc-tag">映射 Engine 能力</el-tag>
      </div>
      <el-form :inline="true" class="qywx-toolbar-form" @submit.prevent>
        <el-form-item label="Engine">
          <el-select
            v-model="form.engineId"
            placeholder="在线 Engine"
            filterable
            style="width: 220px"
            @visible-change="onEngineSelectVisible"
            @change="onEngineIdChange"
          >
            <el-option
              v-for="e in engineOptions"
              :key="e.engineId"
              :label="e.engineId + ' (' + (e.version || '-') + ')'"
              :value="e.engineId"
            />
          </el-select>
          <el-button class="ml8" @click="refreshEngines(true)" :loading="engineLoading">刷新</el-button>
        </el-form-item>
        <el-form-item label="userId">
          <el-input v-model="form.userId" placeholder="与扫码会话一致" style="width: 120px" />
        </el-form-item>
        <el-form-item label="超时(秒)">
          <el-input-number v-model="form.timeoutSec" :min="10" :max="600" />
        </el-form-item>
      </el-form>
    </div>

    <!-- 侧栏点进来后的主路径：一次点击先跑通（不做其它配置） -->
    <el-card shadow="hover" class="qywx-quickstart-card mb12">
      <div class="qywx-quickstart-inner">
        <div class="qywx-quickstart-copy">
          <h3 class="qywx-quickstart-title">从这里开始</h3>
          <p class="qywx-quickstart-sub">
            点一次即可完成：在线 Engine → 健康检查 → 企微能力清单；下方表格与 JSON 会马上有内容。需要实操企微后台时再去「登录管理」。
          </p>
        </div>
        <el-button
          type="primary"
          size="large"
          class="qywx-quickstart-btn"
          :loading="selfCheckLoading"
          :disabled="quickStartDisabled"
          @click="runSelfCheck"
        >
          一键开始
        </el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="mb12">
      <template #header>
        <span>Skill 转企微工作流（第0步 + L1预览）</span>
      </template>
      <el-space wrap>
        <input ref="skillFileInputRef" type="file" accept=".zip" @change="onSkillZipFileChange" />
        <el-button
          type="primary"
          :loading="skillPreviewLoading"
          :disabled="!skillZipFile"
          @click="runSkillMigrationPreview"
        >
          生成迁移预览
        </el-button>
        <el-button
          type="success"
          plain
          :loading="skillImportDraftLoading"
          :disabled="!form.engineId || !skillWorkflowDraft"
          @click="runWorkflowImportDraft"
        >
          一键录入草稿
        </el-button>
        <el-button
          type="warning"
          plain
          :loading="workflowValidateLoading"
          :disabled="!form.engineId"
          @click="runWorkflowValidateAndWriteback"
        >
          运行工作流校验并回写
        </el-button>
        <el-button
          type="info"
          plain
          :loading="editorProbeLoading"
          :disabled="!form.engineId"
          @click="runWorkflowEditorProbe"
        >
          编辑器诊断快照
        </el-button>
        <el-button
          text
          :loading="skillGranulesLoading"
          @click="loadRecentSkillGranules"
        >
          查看沉淀颗粒
        </el-button>
        <el-button text @click="clearSkillZipFile">清空文件</el-button>
        <el-switch
          v-model="skillImportWithDiagnostics"
          active-text="录入附带诊断"
          class="ml8"
        />
        <el-switch
          v-model="skillImportVerifyEnabled"
          active-text="导入后自动验收"
          class="ml8"
        />
        <el-switch
          v-model="skillImportAutoFeedback"
          active-text="自动写入学习纠偏"
          class="ml8"
        />
        <el-form-item label="验收轮次" class="ml8">
          <el-input-number v-model="skillImportVerifyRounds" :min="1" :max="5" />
        </el-form-item>
      </el-space>
      <el-input
        v-model="sourceExportRowsText"
        type="textarea"
        :rows="4"
        placeholder="可选：粘贴源工作流 EXPORT_FULL.rows JSON 数组；启用后 IMPORT_DRAFT 会返回 verifyReport 与 learningCorrectionsSuggested"
        class="mt8 mono"
      />
      <p class="hint mt8">
        当前已支持 L1 骨架、L2 门禁提示、运行时限制校验与回写。生成预览后可点「一键录入草稿」，再点「运行工作流校验并回写」形成闭环（需已登录、选对 Engine）。
        若录入后卡在画布（如「+ 添加指定操作」点不中），保持 Engine 会话停在编辑器页，点「编辑器诊断快照」查看视口与文本命中策略；勾选「录入附带诊断」可在单次录入结果中附带前后快照。
      </p>
      <el-alert
        v-if="lastImportVerifySummary"
        :title="lastImportVerifySummary"
        :type="lastImportVerifyPass ? 'success' : 'warning'"
        :closable="false"
        show-icon
        class="mt8"
      />
      <el-alert
        v-if="skillPreviewSummaryText"
        :title="skillPreviewSummaryText"
        type="success"
        :closable="false"
        show-icon
        class="mt8"
      />
      <el-alert
        v-if="skillInsightsText"
        :title="skillInsightsText"
        type="info"
        :closable="false"
        show-icon
        class="mt8"
      />
    </el-card>

    <!-- 环境就绪：状态说明 + 辅助入口（与「一键开始」不重复） -->
    <el-alert :type="readinessAlertType" :closable="false" show-icon class="mb12 qywx-readiness">
      <template #title>{{ readinessTitle }}</template>
      <template #default>
        <p class="qywx-readiness-desc">{{ readinessDetail }}</p>
        <el-space wrap class="mt8">
          <el-button size="small" :loading="engineLoading" @click="refreshEngines(true)">刷新 Engine 列表</el-button>
          <el-button size="small" link type="primary" @click="goHostConnection">主机连接 / 在线列表</el-button>
          <el-button size="small" link type="primary" @click="goLoginManager">登录管理</el-button>
        </el-space>
      </template>
    </el-alert>

    <!-- 沙箱 + 串联：会话由登录管理器恢复，Engine 内 Playwright 复用同一登录态 -->
    <el-card shadow="never" class="qywx-sandbox-card mb12">
      <template #header>
        <span>沙箱命名与联动编排</span>
        <el-tag size="small" type="warning" class="ml8">逐条 POST /ws/engine/request</el-tag>
      </template>
      <p class="qywx-sandbox-text">
        在企微后台<strong>手动</strong>创建测试用机器人 / 工作流时，建议使用下列前缀，便于识别与清理。
        <el-button link type="primary" @click="goLoginManager">打开登录管理</el-button>
       （会话与 <code>userId</code> 一致时，下方单次调用与串联均在同一 Engine 浏览器会话中执行。）
      </p>
      <div class="qywx-sandbox-chips mb12">
        <span class="hint mr8">机器人名：</span>
        <el-tag size="small" class="mono-chip" @click="copyText(sandbox.robotNamePrefix)">{{ sandbox.robotNamePrefix }}</el-tag>
        <span class="hint ml12 mr8">工作流标题：</span>
        <el-tag size="small" type="success" class="mono-chip" @click="copyText(sandbox.workflowTitlePrefix)">{{
          sandbox.workflowTitlePrefix
        }}</el-tag>
        <el-button size="small" class="ml8" @click="fillWorkflowSandboxTitle">填入工作流名称</el-button>
      </div>
      <el-form :inline="true" class="qywx-scenario-form" @submit.prevent>
        <el-form-item label="学习路径">
          <el-select
            v-model="scenarioId"
            filterable
            placeholder="筛选或选择场景"
            style="width: min(100%, 420px)"
          >
            <el-option v-for="s in learningScenarios" :key="s.id" :label="s.label" :value="s.id">
              <div class="scenario-option-row">
                <span>{{ s.label }}</span>
                <span class="hint scenario-opt-meta">约 {{ formatApproxDuration(s) }}</span>
              </div>
              <div class="hint scenario-opt-hint">{{ s.description }}</div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading && op === 'scenario'" :disabled="!form.engineId" @click="runLearningScenario">
            执行联动
          </el-button>
          <span v-if="selectedScenarioApprox" class="hint ml8">预估上限约 {{ selectedScenarioApprox }}（受超时配置影响）</span>
        </el-form-item>
      </el-form>
      <div v-if="showScenarioProgressBlock" class="qywx-scenario-live">
        <el-progress :percentage="scenarioProgressPct" :stroke-width="10" :status="scenarioProgressStatus || undefined" />
        <p class="hint scenario-live-txt">{{ scenarioLiveText }}</p>
      </div>
      <p v-if="selectedScenarioDetail" class="hint scenario-detail">{{ selectedScenarioDetail }}</p>
    </el-card>

    <el-alert
      v-if="!engineOptions.length && !engineLoading"
      title="当前无在线 Engine"
      type="error"
      show-icon
      :closable="false"
      class="mb12"
    >
      <template #default>
        请先启动 Engine 副节点，在下方点「主机连接 / 在线列表」确认 WebSocket 已注册，再点「刷新 Engine 列表」。若侧栏没有该菜单，请让管理员执行仓库 <code>sql/update_20260422_host_qyweixin_workflow_menu_幂等.sql</code> 并为您授权。
      </template>
    </el-alert>

    <el-container class="qywx-shell">
      <!-- 侧栏：对应企微 AI 助手内 Tab 心智（工作流 / 管理 / …） -->
      <el-aside width="220px" class="qywx-aside">
        <div class="qywx-aside-title">页面</div>
        <el-menu
          :default-active="navKey"
          class="qywx-side-menu"
          @select="onNavSelect"
        >
          <el-menu-item index="workflow" title="企微后台 hash：#/aiHelper/list?tab=workflow">
            <div class="qywx-menu-cell">
              <span class="qywx-menu-label">工作流</span>
              <span class="qywx-menu-hash">#/aiHelper/list?tab=workflow</span>
            </div>
          </el-menu-item>
          <el-menu-item index="manage" title="企微后台 hash：#/aiHelper/manage">
            <div class="qywx-menu-cell">
              <span class="qywx-menu-label">管理</span>
              <span class="qywx-menu-hash">#/aiHelper/manage</span>
            </div>
          </el-menu-item>
          <el-menu-item index="session">
            <div class="qywx-menu-cell">
              <span class="qywx-menu-label">连接与会话</span>
              <span class="qywx-menu-hash">QYWEIXIN_CHECK_LOGIN / SCAN_LOGIN</span>
            </div>
          </el-menu-item>
            <el-menu-item index="lab">
            <div class="qywx-menu-cell">
              <span class="qywx-menu-label">页面探索</span>
              <span class="qywx-menu-hash">EXPLORE_* · UI_MAP · CATALOG</span>
            </div>
          </el-menu-item>
        </el-menu>
        <div class="qywx-aside-foot">
          <p>以上为本地操作映射，实际页面在企业微信管理后台打开。</p>
        </div>
      </el-aside>

      <el-main class="qywx-main">
        <el-alert title="使用前提" type="success" show-icon :closable="false" class="mb12 qywx-premise-alert">
          <template #default>
            <ol class="qywx-premise-list">
              <li>顶部选择<strong>在线 Engine</strong>，<code>userId</code> 与登录管理器中企微扫码会话一致。</li>
              <li>企微后台操作由 Engine 内 Playwright 执行，依赖「登录管理」中已完成的会话恢复（无需在本页重复扫码）。</li>
              <li><strong>QYWEIXIN_SCAN_LOGIN</strong> 为流式能力，仍须到
                <router-link class="qywx-link" :to="{ path: '/content/login-manager' }">登录管理</router-link>
                发起；单次 HTTP 适合 LIST / EXPLORE / WORKFLOW 等。</li>
            </ol>
          </template>
        </el-alert>

        <!-- —— 工作流（QYWEIXIN_WORKFLOW_*） —— -->
        <div v-show="navKey === 'workflow'" class="qywx-panel">
          <div class="qywx-panel-head">
            <h3>工作流</h3>
            <p class="qywx-panel-desc">对应后台「智能体对话 / 工作流」列表与编辑；动作：拉取列表、打开编辑器、新建、删除。</p>
          </div>
          <el-form :model="form" label-width="118px" class="wf-form">
            <el-divider content-position="left">行定位（列表 / 编辑器 / 删除）</el-divider>
            <el-form-item label="workflowIndex">
              <el-input-number v-model="form.workflowIndex" :min="0" :step="1" />
              <span class="hint ml8">从 0 起；留空则按名称或默认第 1 条</span>
            </el-form-item>
            <el-form-item label="workflowName">
              <el-input
                v-model="form.workflowName"
                placeholder="首列标题第一行"
                clearable
                style="max-width: 420px"
              >
                <template #append>
                  <el-button @click="fillWorkflowSandboxTitle">沙箱前缀</el-button>
                </template>
              </el-input>
            </el-form-item>
            <el-form-item>
              <el-space wrap>
                <el-button type="primary" :loading="loading && op === 'list'" @click="run('list')">
                  拉取工作流列表
                </el-button>
                <el-button type="primary" plain :loading="loading && op === 'editor'" @click="run('editor')">
                  打开编辑器
                </el-button>
                <el-button :loading="loading && op === 'create'" @click="run('create')">进入新建向导</el-button>
                <el-button type="warning" plain :loading="workflowValidateLoading" @click="runWorkflowValidateAndWriteback">
                  校验并回写
                </el-button>
              </el-space>
            </el-form-item>
            <div class="delete-workflow-panel">
              <el-divider content-position="left">
                <span class="delete-workflow-title">删除工作流</span>
              </el-divider>
              <el-alert type="warning" show-icon :closable="false" class="delete-workflow-alert">
                <template #title>匹配规则</template>
                <template #default>
                  <ul class="delete-rules">
                    <li>按<strong>首列标题</strong>；多条前缀相同时删<strong>标题最短</strong>一行。</li>
                    <li>首列完全相同则填创建人消歧。</li>
                  </ul>
                </template>
              </el-alert>
              <el-form-item label="workflowCreator" class="delete-creator-item">
                <el-input
                  v-model="form.workflowCreator"
                  placeholder="可选：第二列创建人"
                  clearable
                  style="max-width: 420px"
                />
              </el-form-item>
              <div class="delete-action-row">
                <el-button type="danger" :loading="loading && op === 'delete'" class="delete-workflow-btn" @click="runDelete">
                  删除工作流
                </el-button>
                <span class="hint delete-action-hint">QYWEIXIN_WORKFLOW_DELETE</span>
              </div>
            </div>
          </el-form>
        </div>

        <!-- —— 管理（QYWEIXIN_LIST_ROBOTS） —— -->
        <div v-show="navKey === 'manage'" class="qywx-panel">
          <div class="qywx-panel-head">
            <h3>管理</h3>
            <p class="qywx-panel-desc">对应后台侧边「管理」Tab：获取智能机器人列表。</p>
          </div>
          <el-button type="primary" :loading="loading && op === 'listRobots'" @click="runOnceType('QYWEIXIN_LIST_ROBOTS', 'listRobots', 95000)">
            拉取智能机器人列表
          </el-button>
          <p class="hint mt8">能力类型：<code>QYWEIXIN_LIST_ROBOTS</code></p>
        </div>

        <!-- —— 连接与会话（QYWEIXIN_CHECK_LOGIN + 引导扫码） —— -->
        <div v-show="navKey === 'session'" class="qywx-panel">
          <div class="qywx-panel-head">
            <h3>连接与会话</h3>
            <p class="qywx-panel-desc">检测当前浏览器会话是否已登录企微管理后台。</p>
          </div>
          <el-space direction="vertical" alignment="stretch" style="width: 100%">
            <div>
              <el-button type="primary" :loading="loading && op === 'checkLogin'" @click="runOnceType('QYWEIXIN_CHECK_LOGIN', 'checkLogin', 35000)">
                检测登录状态
              </el-button>
              <span class="hint ml8"><code>QYWEIXIN_CHECK_LOGIN</code></span>
            </div>
            <el-alert title="扫码登录（流式）" type="info" :closable="false" show-icon>
              <template #default>
                <p>
                  <code>QYWEIXIN_SCAN_LOGIN</code> 为流式能力，请在本系统
                  <router-link class="qywx-link" :to="{ path: '/content/login-manager' }">登录管理</router-link>
                  中选择企业微信并连接 Engine 使用；或使用 AIGC 侧 WebSocket 调试。
                </p>
              </template>
            </el-alert>
          </el-space>
        </div>

        <!-- —— 研发探索（EXPLORE_*） —— -->
        <div v-show="navKey === 'lab'" class="qywx-panel">
          <div class="qywx-panel-head">
            <h3>页面探索</h3>
            <p class="qywx-panel-desc">
              研发用：抓取 DOM/截图；「细粒度 UI 映射」按多路由采集 Tab、按钮、表头、hash 链接等，供与
              <code>qywxAiHelperRouteMap.js</code> 对表。
            </p>
          </div>
          <el-space wrap>
            <el-button type="primary" :loading="loading && op === 'exploreUiMap'" @click="runOnceType('QYWEIXIN_EXPLORE_UI_MAP', 'exploreUiMap', 125000)">
              细粒度 UI 映射（多路由）
            </el-button>
            <el-button :loading="loading && op === 'exploreAi'" @click="runOnceType('QYWEIXIN_EXPLORE_AIHELPER', 'exploreAi', 95000)">
              探索 AI 助手页
            </el-button>
            <el-button :loading="loading && op === 'exploreWf'" @click="runOnceType('QYWEIXIN_EXPLORE_WORKFLOW', 'exploreWf', 95000)">
              探索工作流 Tab
            </el-button>
          </el-space>
          <p class="hint mt8">
            <code>QYWEIXIN_EXPLORE_UI_MAP</code> · <code>QYWEIXIN_EXPLORE_AIHELPER</code> ·
            <code>QYWEIXIN_EXPLORE_WORKFLOW</code>
          </p>
          <el-alert class="mt12" title="测试查看（流式）" type="info" :closable="false" show-icon>
            <template #default>
              <code>QYWEIXIN_TEST_VIEW</code> 为流式能力，HTTP 单次通道不可用；请在 WebSocket/任务流中调试。
            </template>
          </el-alert>

          <div class="qywx-catalog-block mt12">
            <div class="qywx-catalog-head">
              <span class="qywx-catalog-title">程序化能力清单 + 运行时注册对齐</span>
              <el-button size="small" type="primary" plain :loading="catalogLoading" @click="loadCapabilityCatalog">
                拉取 QYWEIXIN_CAPABILITY_CATALOG
              </el-button>
            </div>
            <p class="hint">
              由 Engine 返回 <code>entries</code>（类型 / 模式 / 说明），与下方静态表及 Admin 中已注册的 QYWEIXIN_* 对照。
            </p>
            <div v-if="selectedEngineQywxCaps.length" class="qywx-cap-tags mb8">
              <span class="hint mr8">当前 Engine 已注册：</span>
              <el-tag v-for="t in selectedEngineQywxCaps" :key="t" size="small" class="mr4 mb4">{{ t }}</el-tag>
            </div>
            <el-alert v-else type="info" :closable="false" show-icon class="mb8 catalog-empty-hint">
              <template #default>
                未解析到 QYWEIXIN_* 能力时，请确认 Engine 已升级并重启；仍可在下方表格为空时点击拉取清单。
              </template>
            </el-alert>
            <el-table
              v-if="catalogRows.length"
              :data="catalogRows"
              size="small"
              border
              stripe
              max-height="260"
              class="mt8"
            >
              <el-table-column prop="type" label="消息类型" width="220" show-overflow-tooltip />
              <el-table-column prop="mode" label="模式" width="72" />
              <el-table-column prop="description" label="说明" min-width="120" show-overflow-tooltip />
              <el-table-column prop="payload" label="载荷提示" min-width="160" show-overflow-tooltip />
            </el-table>
          </div>

          <el-collapse class="mt12 qywx-route-collapse" accordion>
            <el-collapse-item title="本项目侧栏与企微 hash / Engine 能力对照（qywxAiHelperRouteMap.js）" name="map">
              <el-table :data="routeMapRows" size="small" border stripe max-height="280">
                <el-table-column prop="label" label="侧栏" width="120" />
                <el-table-column prop="backendHash" label="企微后台" min-width="200" show-overflow-tooltip />
                <el-table-column prop="engineTypes" label="Engine 能力" min-width="260">
                  <template #default="{ row }">
                    <span class="mono-xs">{{ row.engineTypes.join(', ') }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>
        </div>

        <!-- 响应区：单次与联动分开展示 -->
        <el-card shadow="never" class="qywx-result-card">
          <template #header>
            <div class="qywx-result-head">
              <el-radio-group v-model="resultTab" size="small">
                <el-radio-button value="single">单次调用</el-radio-button>
                <el-radio-button value="chain">联动编排</el-radio-button>
              </el-radio-group>
              <el-button text type="primary" @click="copyActiveResult">复制当前页</el-button>
            </div>
          </template>
          <el-input
            :model-value="displayJson"
            type="textarea"
            :rows="18"
            readonly
            placeholder="暂无内容；点击各页能力按钮或执行联动后显示 JSON"
            class="mono"
          />
        </el-card>
      </el-main>
    </el-container>
  </div>
</template>

<script setup name="QyWeixinWorkflowLab">
import {
  engineOnceRequest,
  fetchQywxCapabilityCatalog,
  previewSkillMigration,
  fetchRecentSkillGranules,
  appendSkillValidationWriteback
} from '@/api/business/host/qyweixinWorkflow'
import { listOnlineEngines } from '@/api/business/host/connection'
import { QYWX_AI_HELPER_ROUTE_MAP } from '@/config/qywxAiHelperRouteMap.js'
import { QYWX_SANDBOX } from '@/config/qywxSandboxConstants.js'
import { QYWX_LEARNING_SCENARIOS } from '@/config/qywxLearningScenarios.js'
import useUserStore from '@/store/modules/user'
import { useRouter } from 'vue-router'
import { watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const userStore = useUserStore()
const router = useRouter()

const engineOptions = ref([])
const engineLoading = ref(false)
const loading = ref(false)
const op = ref('')
/** 单次 Engine 调用的 JSON（与各页按钮对应） */
const jsonSingle = ref('')
/** 串联编排最近一次完整 trace */
const jsonChain = ref('')
const resultTab = ref('single')
const navKey = ref('workflow')
/** 默认「能力清单」单步：不要求企微已登录，侧栏点进来最容易一次成功 */
const scenarioId = ref('capability_catalog')
const learningScenarios = QYWX_LEARNING_SCENARIOS
const sandbox = QYWX_SANDBOX

const routeMapRows = QYWX_AI_HELPER_ROUTE_MAP
const catalogRows = ref([])
const catalogLoading = ref(false)
/** 一键：HEALTH_CHECK + 能力清单 */
const selfCheckLoading = ref(false)
/** skill 迁移预览（第0步 + L1） */
const skillFileInputRef = ref(null)
const skillZipFile = ref(null)
const skillPreviewLoading = ref(false)
const skillPreviewSummary = ref(null)
const skillPreviewInsights = ref(null)
/** 最近一次「生成迁移预览」返回的 workflowDraft，供 QYWEIXIN_WORKFLOW_IMPORT_DRAFT 使用 */
const skillWorkflowDraft = ref(null)
const skillPreviewSkillId = ref('')
const skillImportDraftLoading = ref(false)
/** 对应 Engine payload.editorDiagnostics，便于排查分辨率/DOM 命中 */
const skillImportWithDiagnostics = ref(false)
/** IMPORT_DRAFT 验收开关与轮次 */
const skillImportVerifyEnabled = ref(true)
const skillImportVerifyRounds = ref(2)
const skillImportAutoFeedback = ref(false)
const sourceExportRowsText = ref('')
const lastImportVerifyReport = ref(null)
const skillGranulesLoading = ref(false)
const workflowValidateLoading = ref(false)
const editorProbeLoading = ref(false)

/** 当前所选 Engine 在 Admin 侧已声明的 QYWEIXIN_*（运行时） */
const selectedEngineQywxCaps = computed(() => {
  const e = engineOptions.value.find((x) => x.engineId === form.engineId)
  if (!e?.capabilities?.length) {
    return []
  }
  return e.capabilities
    .map((c) => c.type || c)
    .filter((t) => typeof t === 'string' && t.startsWith('QYWEIXIN_'))
})

const selectedScenarioDetail = computed(() => {
  const s = learningScenarios.find((x) => x.id === scenarioId.value)
  if (!s) return ''
  const extra = s.needsFormFields ? ` 依赖：${s.needsFormFields}。` : ''
  return s.description + extra
})

const selectedScenarioApprox = computed(() => formatApproxDuration(learningScenarios.find((x) => x.id === scenarioId.value)))

/** 执行中或最近一次联动结束后仍展示进度与说明，避免「一闪就没」 */
const showScenarioProgressBlock = computed(
  () => (loading.value && op.value === 'scenario') || (scenarioProgressPct.value > 0 && !!scenarioLiveText.value)
)

const displayJson = computed(() => (resultTab.value === 'chain' ? jsonChain.value : jsonSingle.value) || '')
const skillPreviewSummaryText = computed(() => {
  const s = skillPreviewSummary.value
  if (!s) return ''
  return `迁移预览：可映射 ${s.mapped || 0}，降级 ${s.degraded || 0}，暂不支持 ${s.unsupported || 0}（建议继续执行校验并回写）`
})
const skillInsightsText = computed(() => {
  const i = skillPreviewInsights.value
  if (!i) return ''
  return `洞察评分：整体 ${i.overallScore || 0} / 100，触发匹配 ${i.triggerFitnessScore || 0}，输出契约 ${i.outputContractScore || 0}，自动化就绪 ${i.automationReadinessScore || 0}`
})
const lastImportVerifyPass = computed(() => Boolean(lastImportVerifyReport.value?.pass))
const lastImportVerifySummary = computed(() => {
  const v = lastImportVerifyReport.value
  if (!v || typeof v !== 'object') return ''
  const ok = v.pass ? '通过' : '未通过'
  const c1 = v.sourceCount ?? '-'
  const c2 = v.cloneCount ?? '-'
  const lm = v.labelMatchCount ?? '-'
  const mm = v.menuMatchCount ?? '-'
  return `导入验收${ok}：源/导入节点 ${c1}/${c2}，label 匹配 ${lm}，菜单匹配 ${mm}，轮次 ${v.verifyRound || 1}/${v.verifyMaxRounds || 1}`
})

const readinessAlertType = computed(() => {
  if (engineLoading.value) return 'info'
  if (!engineOptions.value.length) return 'error'
  if (!form.engineId) return 'warning'
  if (selectedEngineQywxCaps.value.length === 0) return 'warning'
  return 'success'
})

const readinessTitle = computed(() => {
  if (engineLoading.value) return '正在获取 Engine 列表…'
  if (!engineOptions.value.length) return '暂不可用：没有在线 Engine'
  if (!form.engineId) return '请选择 Engine'
  if (selectedEngineQywxCaps.value.length === 0) return '可尝试调用（未在心跳中看到 QYWEIXIN_*）'
  return '环境就绪：可发起企微 HTTP 单次调用'
})

const quickStartDisabled = computed(() => selfCheckLoading.value)

const readinessDetail = computed(() => {
  if (engineLoading.value) return '拉取 Admin 登记的在线 Engine，用于后续路由与能力白名单校验。'
  if (!engineOptions.value.length) {
    return '副节点未连接或已全部离线。请先启动 Engine JAR，再在「主机连接 / 在线列表」中看到 REGISTERED 后再刷新本页。'
  }
  if (!form.engineId) return '在顶栏下拉框中选择目标 Engine；通常与登录管理里使用的节点一致。'
  if (selectedEngineQywxCaps.value.length === 0) {
    return 'Admin 侧能力快照里暂无 QYWEIXIN_*（常见于旧会话未同步）。请先点「一键自检」：若仍失败请重启 Engine 或等待下一个心跳周期。'
  }
  return `已检测到 ${selectedEngineQywxCaps.value.length} 项 QYWEIXIN_* 声明。正式操作前建议在「登录管理」确认企微会话已恢复。`
})

/** 联动进度条（仅 op===scenario） */
const scenarioProgressPct = ref(0)
const scenarioLiveText = ref('')
const scenarioProgressStatus = ref('')

function formatApproxDuration(s) {
  if (!s?.steps?.length) {
    return ''
  }
  const sec =
    s.approxTotalSec != null
      ? s.approxTotalSec
      : s.steps.reduce((a, x) => a + (x.timeoutSec || 90), 0)
  if (sec < 90) {
    return `${sec} 秒`
  }
  const m = Math.max(1, Math.round(sec / 60))
  return `~${m} 分钟`
}

function goLoginManager() {
  router.push('/content/login-manager')
}

function goHostConnection() {
  router.push('/business/host/connection')
}

async function copyText(t) {
  try {
    await navigator.clipboard.writeText(t)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败')
  }
}

function fillWorkflowSandboxTitle() {
  form.workflowName = sandbox.workflowTitlePrefix
}

function onSkillZipFileChange(event) {
  const files = event?.target?.files
  if (!files || !files.length) {
    skillZipFile.value = null
    return
  }
  const f = files[0]
  if (!/\.zip$/i.test(f.name || '')) {
    skillZipFile.value = null
    ElMessage.warning('仅支持 .zip 文件')
    return
  }
  skillZipFile.value = f
}

function clearSkillZipFile() {
  skillZipFile.value = null
  skillWorkflowDraft.value = null
  skillPreviewInsights.value = null
  skillPreviewSkillId.value = ''
  sourceExportRowsText.value = ''
  lastImportVerifyReport.value = null
  if (skillFileInputRef.value) {
    skillFileInputRef.value.value = ''
  }
}

/** 兼容 Axios 与若依 AjaxResult 嵌套 data */
function unwrapSkillPreviewPayload(raw) {
  if (raw == null) return null
  if (raw.migrationReport != null || raw.workflowDraft != null || raw.ir != null) {
    return raw
  }
  if (raw.data != null && typeof raw.data === 'object') {
    const inner = raw.data
    if (inner.migrationReport != null || inner.workflowDraft != null || inner.ir != null) {
      return inner
    }
  }
  return raw
}

function parseSourceExportRowsText() {
  const raw = String(sourceExportRowsText.value || '').trim()
  if (!raw) {
    return []
  }
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch {
    return null
  }
}

async function runSkillMigrationPreview() {
  if (!skillZipFile.value) {
    ElMessage.warning('请先选择 skill ZIP 文件')
    return
  }
  skillPreviewLoading.value = true
  try {
    const res = await previewSkillMigration(skillZipFile.value, { timeout: 120000 })
    const top = res?.data != null ? res.data : res
    const payload = unwrapSkillPreviewPayload(top)
    skillPreviewSummary.value = payload?.migrationReport?.summary || null
    skillPreviewInsights.value = payload?.insights || payload?.migrationReport?.insights || null
    skillWorkflowDraft.value = payload?.workflowDraft ?? null
    if (Array.isArray(payload?.sourceExportRows) && payload.sourceExportRows.length) {
      sourceExportRowsText.value = JSON.stringify(payload.sourceExportRows, null, 2)
    }
    skillPreviewSkillId.value = String(payload?.skillMeta?.id || payload?.ir?.skillMeta?.id || '').trim()
    jsonSingle.value = JSON.stringify(payload ?? top, null, 2)
    resultTab.value = 'single'
    ElMessage.success(skillWorkflowDraft.value ? '迁移预览已生成，可一键录入草稿' : '迁移预览已生成（未含 workflowDraft）')
  } catch (e) {
    ElMessage.error('迁移预览失败：' + (e?.message || e))
  } finally {
    skillPreviewLoading.value = false
  }
}

async function loadRecentSkillGranules() {
  skillGranulesLoading.value = true
  try {
    const res = await fetchRecentSkillGranules(20, { timeout: 20000 })
    setSingleJsonFromResponse(res)
    if (res?.success === false) {
      ElMessage.error(friendlyHttpError(res))
      return
    }
    ElMessage.success('已加载最近沉淀颗粒')
  } catch (e) {
    ElMessage.error('查询沉淀颗粒失败：' + (e?.message || e))
  } finally {
    skillGranulesLoading.value = false
  }
}

function setSingleJsonFromResponse(res) {
  jsonSingle.value = JSON.stringify(res, null, 2)
  resultTab.value = 'single'
}

const form = reactive({
  engineId: '',
  userId: '',
  timeoutSec: 120,
  workflowIndex: null,
  workflowName: '',
  workflowCreator: ''
})

const KIND_TO_TYPE = {
  list: 'QYWEIXIN_WORKFLOW_LIST',
  editor: 'QYWEIXIN_WORKFLOW_OPEN_EDITOR',
  create: 'QYWEIXIN_WORKFLOW_CREATE'
}

watch(scenarioId, () => {
  if (loading.value || op.value === 'scenario') {
    return
  }
  scenarioProgressPct.value = 0
  scenarioLiveText.value = ''
  scenarioProgressStatus.value = ''
})

onMounted(() => {
  form.userId = userStore.id ? String(userStore.id) : '1'
  refreshEngines(false)
})

function onEngineSelectVisible(v) {
  if (v) refreshEngines(false)
}

function onEngineIdChange() {
  catalogRows.value = []
}

function onNavSelect(key) {
  navKey.value = key
}

function refreshEngines(fromUser) {
  engineLoading.value = true
  listOnlineEngines()
    .then((res) => {
      engineOptions.value = res.engines || []
      if (!form.engineId && engineOptions.value.length > 0) {
        form.engineId = engineOptions.value[0].engineId
      }
      if (fromUser) {
        if (engineOptions.value.length) {
          ElMessage.success(`已加载 ${engineOptions.value.length} 个在线 Engine`)
        } else {
          ElMessage.warning('当前无在线 Engine')
        }
      }
    })
    .catch((e) => {
      ElMessage.error('刷新 Engine 失败：' + (e?.message || e) + '（请检查登录与权限）')
    })
    .finally(() => {
      engineLoading.value = false
    })
}

/** 将 Admin/Engine HTTP 错误转为可读说明（仍保留完整 JSON 在下方文本框） */
function friendlyHttpError(res) {
  if (!res || typeof res !== 'object') {
    return '无有效响应'
  }
  if (res.success === false) {
    const c = res.errorCode
    const m = res.errorMessage || ''
    if (c === 'TIMEOUT') {
      return '超时：Engine 在时限内未通过 Admin 回包。请确认副节点已连上主节点、未卡死，或增大顶栏「超时」后重试。'
    }
    if (c === 'CAPABILITY_NOT_FOUND') {
      return `${m || 'Engine 未声明该能力'} 当前连上的 Engine 进程仍是旧包：请先在运行副节点机器的终端结束占用 WxFbsir-engine/target/*.jar 的 java 进程，再在 WxFbsir-engine 目录执行 mvn package -DskipTests，用新 JAR 启动后回到本页点「刷新」Engine 列表，然后再试。`
    }
    if (c === 'ENGINE_OFFLINE') {
      return 'Engine 离线或未注册：请到「主机连接 / 在线列表」确认 WebSocket 为 REGISTERED。'
    }
    return m || '请求失败'
  }
  const inner = res.data
  if (inner && typeof inner === 'object' && inner.success === false) {
    return inner.errorMessage || 'Engine 返回失败'
  }
  return ''
}

/** 健康检查 + 能力清单，结果写入「单次调用」JSON */
async function runSelfCheck() {
  if (engineLoading.value || selfCheckLoading.value) {
    return
  }
  if (!engineOptions.value.length) {
    engineLoading.value = true
    try {
      const res = await listOnlineEngines()
      engineOptions.value = res.engines || []
      if (engineOptions.value.length) {
        form.engineId = form.engineId || engineOptions.value[0].engineId
        ElMessage.success(`已加载 ${engineOptions.value.length} 个在线 Engine`)
      } else {
        ElMessage.warning('当前无在线 Engine，请先启动副节点')
        return
      }
    } catch (e) {
      ElMessage.error('拉取 Engine 列表失败：' + (e?.message || e))
      return
    } finally {
      engineLoading.value = false
    }
  }
  if (!form.engineId && engineOptions.value.length > 0) {
    form.engineId = engineOptions.value[0].engineId
  }
  if (!form.engineId) {
    ElMessage.warning('无可用 Engine，请先启动副节点并刷新列表')
    return
  }
  selfCheckLoading.value = true
  const uid = (form.userId || '').trim() || '1'
  const healthMs = 28000
  const catMs = 28000
  try {
    const healthRes = await engineOnceRequest(
      {
        engineId: form.engineId,
        type: 'HEALTH_CHECK',
        userId: uid,
        timeout: Math.ceil(healthMs / 1000),
        payload: {}
      },
      { timeout: healthMs + 8000 }
    )
    let catalogRes = null
    let catalogErr = null
    if (healthRes && healthRes.success !== false) {
      const inner = healthRes.data
      if (!(inner && inner.success === false)) {
        try {
          catalogRes = await fetchQywxCapabilityCatalog({
            engineId: form.engineId,
            userId: uid,
            timeoutSec: Math.min(45, Math.ceil(catMs / 1000))
          })
        } catch (e) {
          catalogErr = String(e?.message || e)
        }
      }
    }
    const innerData = catalogRes?.data && catalogRes.data.data != null ? catalogRes.data.data : catalogRes?.data
    if (innerData?.entries && Array.isArray(innerData.entries)) {
      catalogRows.value = innerData.entries
    }
    const bundle = {
      step: 'SELF_CHECK',
      engineId: form.engineId,
      HEALTH_CHECK: healthRes,
      QYWEIXIN_CAPABILITY_CATALOG: catalogRes,
      catalogFetchError: catalogErr || undefined,
      checkedAt: new Date().toISOString()
    }
    jsonSingle.value = JSON.stringify(bundle, null, 2)
    resultTab.value = 'single'

    const hErr = friendlyHttpError(healthRes)
    if (hErr) {
      ElMessage.error('自检：健康检查 ' + hErr)
      return
    }
    if (catalogErr) {
      ElMessage.warning('健康检查通过；能力清单请求异常：' + catalogErr)
      return
    }
    const cErr = friendlyHttpError(catalogRes)
    if (cErr) {
      ElMessage.warning('健康检查通过；' + cErr)
      return
    }
    const entries = catalogRows.value.length
    ElMessage.success(entries ? `自检通过：能力清单 ${entries} 条` : '自检通过：能力清单为空（请确认 Engine 版本）')
  } catch (e) {
    jsonSingle.value = JSON.stringify({ step: 'SELF_CHECK', error: String(e?.message || e) }, null, 2)
    resultTab.value = 'single'
    ElMessage.error('自检异常：' + (e?.message || e))
  } finally {
    selfCheckLoading.value = false
  }
}

/** Engine 静态清单（与 UI 映射表等效、可演进） */
async function loadCapabilityCatalog() {
  if (!form.engineId) {
    ElMessage.warning('请选择 Engine')
    return
  }
  catalogLoading.value = true
  try {
    const res = await fetchQywxCapabilityCatalog({
      engineId: form.engineId,
      userId: form.userId || '1',
      timeoutSec: 20
    })
    setSingleJsonFromResponse(res)
    if (!res || res.success === false) {
      ElMessage.error(friendlyHttpError(res) || res?.errorMessage || '拉取失败')
      return
    }
    const inner = res.data
    if (inner && inner.success === false) {
      ElMessage.error(friendlyHttpError(res) || inner.errorMessage || 'Engine 失败')
      return
    }
    const payload = inner?.data != null ? inner.data : inner
    catalogRows.value = Array.isArray(payload?.entries) ? payload.entries : []
    ElMessage.success(catalogRows.value.length ? '已加载能力清单' : '清单为空（检查 Engine 版本）')
  } catch (e) {
    ElMessage.error(String(e?.message || e))
  } finally {
    catalogLoading.value = false
  }
}

function timeoutMs() {
  return Math.min(600, Math.max(10, form.timeoutSec)) * 1000
}

function handleEngineHttpResponse(res) {
  setSingleJsonFromResponse(res)
  if (!res || typeof res !== 'object') {
    ElMessage.warning('无有效响应')
    return
  }
  if (res.success === false) {
    ElMessage.error(friendlyHttpError(res))
    return
  }
  const inner = res.data
  if (inner && inner.success === false) {
    ElMessage.error(friendlyHttpError(res) || inner.errorMessage || 'Engine 返回失败')
    return
  }
  ElMessage.success('已返回')
}

function payloadBase() {
  const uid = (form.userId || '').trim() || '1'
  const p = {}
  const name = String(form.workflowName || '').trim()
  if (name) {
    p.workflowName = name
  }
  const cr = String(form.workflowCreator || '').trim()
  if (cr) {
    p.workflowCreator = cr
  }
  if (form.workflowIndex !== null && form.workflowIndex !== undefined && form.workflowIndex !== '') {
    p.workflowIndex = String(form.workflowIndex)
  }
  return { userId: uid, payload: p }
}

/** Engine：QYWEIXIN_WORKFLOW_IMPORT_DRAFT（依赖最近一次预览的 workflowDraft） */
async function runWorkflowImportDraft() {
  if (!form.engineId) {
    ElMessage.warning('请先选择 Engine')
    return
  }
  if (!skillWorkflowDraft.value) {
    ElMessage.warning('请先生成迁移预览且包含 workflowDraft')
    return
  }
  const sourceRowsParsed = parseSourceExportRowsText()
  if (sourceRowsParsed === null) {
    ElMessage.warning('sourceExportRows JSON 解析失败，请检查格式（应为数组）')
    return
  }
  const base = payloadBase()
  const ms = Math.max(130000, timeoutMs())
  skillImportDraftLoading.value = true
  jsonSingle.value = '请求中…'
  resultTab.value = 'single'
  lastImportVerifyReport.value = null
  try {
    const res = await engineOnceRequest(
      {
        engineId: form.engineId,
        type: 'QYWEIXIN_WORKFLOW_IMPORT_DRAFT',
        userId: base.userId,
        timeout: Math.ceil(ms / 1000),
        payload: {
          ...base.payload,
          workflowDraft: skillWorkflowDraft.value,
          editorDiagnostics: !!skillImportWithDiagnostics.value,
          verifyImport: !!skillImportVerifyEnabled.value,
          verifyMaxRounds: Number(skillImportVerifyRounds.value || 2),
          autoLearningFeedback: !!skillImportAutoFeedback.value,
          sourceExportRows: sourceRowsParsed.length ? sourceRowsParsed : undefined
        }
      },
      { timeout: ms + 5000 }
    )
    handleEngineHttpResponse(res)
    const top = res?.data
    const inner = top?.data != null ? top.data : top
    const report = inner?.verifyReport
    if (report && typeof report === 'object') {
      lastImportVerifyReport.value = report
      if (report.pass) {
        ElMessage.success('草稿录入完成，导入验收通过')
      } else {
        ElMessage.warning('草稿录入完成，导入验收未通过，请查看 verifyReport')
      }
    }
  } catch (err) {
    setSingleJsonFromResponse({ error: String(err?.message || err) })
    ElMessage.error('请求异常：' + (err?.message || err))
  } finally {
    skillImportDraftLoading.value = false
  }
}

/** Engine：QYWEIXIN_WORKFLOW_EDITOR_PROBE（不导航：诊断当前浏览器 Tab，适合已停在 workflow_editor 时） */
async function runWorkflowEditorProbe() {
  if (!form.engineId) {
    ElMessage.warning('请先选择 Engine')
    return
  }
  const base = payloadBase()
  const ms = Math.max(320000, timeoutMs())
  editorProbeLoading.value = true
  jsonSingle.value = '诊断请求中…'
  resultTab.value = 'single'
  try {
    const res = await engineOnceRequest(
      {
        engineId: form.engineId,
        type: 'QYWEIXIN_WORKFLOW_EDITOR_PROBE',
        userId: base.userId,
        timeout: Math.ceil(ms / 1000),
        payload: {
          ...base.payload,
          skipNavigation: true
        }
      },
      { timeout: ms + 5000 }
    )
    setSingleJsonFromResponse(res)
    if (res?.success === false) {
      ElMessage.error(friendlyHttpError(res))
      return
    }
    ElMessage.success('诊断已完成，查看下方 JSON（含 triggerProbe / samples_add / screenshotUrl）')
  } catch (err) {
    setSingleJsonFromResponse({ error: String(err?.message || err) })
    ElMessage.error('请求异常：' + (err?.message || err))
  } finally {
    editorProbeLoading.value = false
  }
}

/** Engine：QYWEIXIN_WORKFLOW_VALIDATE，并将结果回写到 skill 颗粒目录 */
async function runWorkflowValidateAndWriteback() {
  if (!form.engineId) {
    ElMessage.warning('请先选择 Engine')
    return
  }
  const base = payloadBase()
  const ms = Math.max(130000, timeoutMs())
  workflowValidateLoading.value = true
  jsonSingle.value = '请求中…'
  resultTab.value = 'single'
  try {
    const res = await engineOnceRequest(
      {
        engineId: form.engineId,
        type: 'QYWEIXIN_WORKFLOW_VALIDATE',
        userId: base.userId,
        timeout: Math.ceil(ms / 1000),
        payload: base.payload
      },
      { timeout: ms + 5000 }
    )
    setSingleJsonFromResponse(res)
    if (res?.success === false) {
      ElMessage.error(friendlyHttpError(res))
      return
    }
    const inner = res?.data
    if (inner && inner.success === false) {
      ElMessage.error(inner.errorMessage || '校验失败')
      return
    }
    const validationResult = inner?.data != null ? inner.data : inner
    if (skillPreviewSkillId.value && validationResult && typeof validationResult === 'object') {
      try {
        const wb = await appendSkillValidationWriteback(skillPreviewSkillId.value, validationResult, { timeout: 15000 })
        const current = (() => {
          try {
            return JSON.parse(jsonSingle.value || '{}')
          } catch {
            return {}
          }
        })()
        current.writeback = wb
        jsonSingle.value = JSON.stringify(current, null, 2)
        ElMessage.success('校验完成并已回写颗粒目录')
      } catch (e) {
        ElMessage.warning('校验完成，但回写失败：' + (e?.message || e))
      }
    } else {
      ElMessage.success('校验完成（未找到 skillId，跳过回写）')
    }
  } catch (err) {
    setSingleJsonFromResponse({ error: String(err?.message || err) })
    ElMessage.error('请求异常：' + (err?.message || err))
  } finally {
    workflowValidateLoading.value = false
  }
}

/** 通用 Once：任意消息类型 */
function runOnceType(messageType, opKey, timeoutOverrideMs) {
  if (!form.engineId) {
    ElMessage.warning('请选择 Engine')
    return
  }
  const base = payloadBase()
  const ms = timeoutOverrideMs != null ? timeoutOverrideMs : timeoutMs()
  op.value = opKey
  loading.value = true
  jsonSingle.value = '请求中…'
  resultTab.value = 'single'
  engineOnceRequest(
    {
      engineId: form.engineId,
      type: messageType,
      userId: base.userId,
      timeout: Math.ceil(ms / 1000),
      payload: base.payload
    },
    { timeout: ms + 5000 }
  )
    .then(handleEngineHttpResponse)
    .catch((err) => {
      setSingleJsonFromResponse({ error: String(err?.message || err) })
      ElMessage.error('请求异常：' + (err?.message || err))
    })
    .finally(() => {
      loading.value = false
      op.value = ''
    })
}

function run(kind) {
  if (!form.engineId) {
    ElMessage.warning('请选择 Engine')
    return
  }
  const base = payloadBase()
  let type = KIND_TO_TYPE[kind]
  if (!type) return
  let timeout = timeoutMs()
  if (kind === 'editor') {
    timeout = Math.max(timeout, 130000)
  }
  op.value = kind
  loading.value = true
  jsonSingle.value = '请求中…'
  resultTab.value = 'single'
  engineOnceRequest(
    {
      engineId: form.engineId,
      type,
      userId: base.userId,
      timeout: Math.ceil(timeout / 1000),
      payload: base.payload
    },
    { timeout: timeout + 5000 }
  )
    .then(handleEngineHttpResponse)
    .catch((err) => {
      setSingleJsonFromResponse({ error: String(err?.message || err) })
      ElMessage.error('请求异常：' + (err?.message || err))
    })
    .finally(() => {
      loading.value = false
      op.value = ''
    })
}

function runDelete() {
  if (!form.engineId) {
    ElMessage.warning('请选择 Engine')
    return
  }
  ElMessageBox.confirm(
    '将调用 QYWEIXIN_WORKFLOW_DELETE，按索引/名称/创建人删除企微后台中的工作流，不可恢复。是否继续？',
    '确认删除',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  )
    .then(() => {
      op.value = 'delete'
      loading.value = true
      jsonSingle.value = '请求中…'
      resultTab.value = 'single'
      const base = payloadBase()
      const timeout = timeoutMs()
      engineOnceRequest(
        {
          engineId: form.engineId,
          type: 'QYWEIXIN_WORKFLOW_DELETE',
          userId: base.userId,
          timeout: Math.ceil(timeout / 1000),
          payload: base.payload
        },
        { timeout: timeout + 5000 }
      )
        .then(handleEngineHttpResponse)
        .catch((err) => {
          setSingleJsonFromResponse({ error: String(err?.message || err) })
          ElMessage.error('请求异常：' + (err?.message || err))
        })
        .finally(() => {
          loading.value = false
          op.value = ''
        })
    })
    .catch(() => {})
}

async function copyActiveResult() {
  const t = displayJson.value
  if (!t) {
    return
  }
  try {
    await navigator.clipboard.writeText(t)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败')
  }
}

/** Admin→Engine 串联：任意一步失败即中止；结果写入「联动编排」页签 */
async function runLearningScenario() {
  if (!form.engineId) {
    ElMessage.warning('请选择 Engine')
    return
  }
  const sc = learningScenarios.find((x) => x.id === scenarioId.value)
  if (!sc || !sc.steps?.length) {
    ElMessage.warning('无效场景')
    return
  }
  const base = payloadBase()
  op.value = 'scenario'
  loading.value = true
  scenarioProgressPct.value = 0
  scenarioProgressStatus.value = ''
  scenarioLiveText.value = '准备执行…'
  const total = sc.steps.length
  const trace = []
  let failedAt = null
  try {
    for (let i = 0; i < sc.steps.length; i++) {
      const step = sc.steps[i]
      const ms = Math.min(600000, Math.max(5000, (step.timeoutSec || 90) * 1000))
      scenarioProgressPct.value = Math.round((i / total) * 100)
      scenarioLiveText.value = `步骤 ${i + 1}/${total}：${step.desc}（${step.type}）`
      const res = await engineOnceRequest(
        {
          engineId: form.engineId,
          type: step.type,
          userId: base.userId,
          timeout: Math.ceil(ms / 1000),
          payload: base.payload
        },
        { timeout: ms + 8000 }
      )
      trace.push({
        type: step.type,
        desc: step.desc,
        ok: res && res.success !== false,
        response: res
      })
      const chainPayload = { scenarioId: sc.id, scenarioLabel: sc.label, stoppedAt: null, trace }
      jsonChain.value = JSON.stringify(chainPayload, null, 2)
      resultTab.value = 'chain'
      if (!res || res.success === false) {
        failedAt = step.type
        scenarioProgressStatus.value = 'exception'
        scenarioProgressPct.value = Math.round(((i + 1) / total) * 100)
        const msg = res?.errorMessage || 'Admin 请求失败'
        ElMessage.error(`${step.type} 失败: ${msg}`)
        jsonChain.value = JSON.stringify({ ...chainPayload, stoppedAt: failedAt }, null, 2)
        break
      }
      const inner = res.data
      if (inner && inner.success === false) {
        failedAt = step.type
        scenarioProgressStatus.value = 'exception'
        scenarioProgressPct.value = Math.round(((i + 1) / total) * 100)
        ElMessage.error(`${step.type} Engine 失败: ${inner.errorMessage || 'unknown'}`)
        jsonChain.value = JSON.stringify({ ...chainPayload, stoppedAt: failedAt }, null, 2)
        break
      }
      scenarioProgressPct.value = Math.round(((i + 1) / total) * 100)
    }
    if (!failedAt) {
      scenarioProgressPct.value = 100
      scenarioProgressStatus.value = 'success'
      scenarioLiveText.value = '全部步骤已完成'
      ElMessage.success('联动完成')
    }
  } catch (err) {
    scenarioProgressStatus.value = 'exception'
    jsonChain.value = JSON.stringify(
      {
        scenarioId: sc.id,
        error: String(err?.message || err),
        trace
      },
      null,
      2
    )
    resultTab.value = 'chain'
    ElMessage.error('联动异常：' + (err?.message || err))
  } finally {
    loading.value = false
    op.value = ''
  }
}
</script>

<style scoped>
.qywx-lab {
  padding: 0;
}

.qywx-topbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #e8eaec;
  border-radius: 4px;
  margin-bottom: 12px;
}

.qywx-breadcrumb {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 14px;
}

.qywx-bc-muted {
  color: #909399;
}

.qywx-bc-sep {
  color: #c0c4cc;
}

.qywx-bc-strong {
  color: #303133;
  font-weight: 600;
}

.qywx-bc-tag {
  margin-left: 8px;
}

.qywx-toolbar-form {
  margin: 0;
}

.qywx-toolbar-form :deep(.el-form-item) {
  margin-bottom: 0;
  margin-right: 12px;
}

.qywx-shell {
  min-height: calc(100vh - 220px);
  background: #f5f7fa;
  border: 1px solid #e8eaec;
  border-radius: 4px;
}

.qywx-aside {
  background: #fafbfc;
  border-right: 1px solid #e8eaec;
  padding: 0;
}

.qywx-aside-title {
  padding: 14px 16px 8px;
  font-size: 12px;
  color: #909399;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.qywx-side-menu {
  border-right: none;
  background: transparent;
}

.qywx-side-menu .el-menu-item {
  height: auto;
  line-height: 1.35;
  padding: 10px 12px 10px 16px !important;
  white-space: normal;
  align-items: flex-start;
}

.qywx-menu-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.qywx-menu-label {
  font-size: 14px;
  color: #303133;
}

.qywx-menu-hash {
  font-size: 11px;
  color: #909399;
  font-family: ui-monospace, monospace;
  line-height: 1.3;
  word-break: break-all;
}

.qywx-aside-foot {
  padding: 12px 14px 16px;
  font-size: 12px;
  color: #a0a4a8;
  line-height: 1.5;
}

.qywx-main {
  background: #fff;
  padding: 16px 20px 24px;
}

.qywx-panel-head h3 {
  margin: 0 0 6px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.qywx-panel-desc {
  margin: 0 0 16px;
  font-size: 13px;
  color: #909399;
  line-height: 1.5;
}

.mb12 {
  margin-bottom: 12px;
}

.qywx-sandbox-card {
  border: 1px solid var(--el-color-warning-light-5);
}

.qywx-catalog-block {
  padding: 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.qywx-catalog-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.qywx-catalog-title {
  font-weight: 600;
  font-size: 14px;
}

.qywx-cap-tags {
  line-height: 1.8;
}

.qywx-sandbox-text {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.65;
  color: var(--el-text-color-regular);
}

.qywx-sandbox-text code {
  font-size: 12px;
  padding: 0 4px;
}

.qywx-scenario-form {
  margin-bottom: 0;
}

.scenario-option-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  line-height: 1.4;
}

.scenario-opt-meta {
  flex-shrink: 0;
  font-size: 12px;
}

.mono-chip {
  cursor: pointer;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  user-select: all;
}

.qywx-scenario-live {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
}

.scenario-live-txt {
  margin: 8px 0 0;
  line-height: 1.5;
}

.scenario-opt-hint {
  display: block;
  font-size: 11px;
  margin-top: 2px;
}

.scenario-detail {
  margin: 8px 0 0;
}

.mt8 {
  margin-top: 8px;
}

.mt12 {
  margin-top: 12px;
}

.qywx-link {
  color: var(--el-color-primary);
}

.qywx-result-card {
  margin-top: 20px;
}

.qywx-result-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.qywx-premise-list {
  margin: 8px 0 0;
  padding-left: 20px;
  line-height: 1.65;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.qywx-readiness-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}

.qywx-quickstart-card :deep(.el-card__body) {
  padding: 16px 20px;
}

.qywx-quickstart-inner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.qywx-quickstart-copy {
  flex: 1;
  min-width: 200px;
}

.qywx-quickstart-title {
  margin: 0 0 8px;
  font-size: 17px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.qywx-quickstart-sub {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.qywx-quickstart-btn {
  min-width: 132px;
  flex-shrink: 0;
}

.hint {
  color: #909399;
  font-size: 13px;
}

.ml8 {
  margin-left: 8px;
}

.mono :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.fr {
  float: right;
}

.delete-workflow-panel {
  margin-top: 8px;
  padding: 12px 16px 16px;
  border: 1px solid var(--el-color-danger-light-5);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
}

.delete-workflow-title {
  color: var(--el-color-danger);
  font-weight: 600;
}

.delete-workflow-alert {
  margin-bottom: 16px;
}

.delete-rules {
  margin: 8px 0 0 18px;
  padding: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}

.delete-rules li {
  margin-bottom: 4px;
}

.delete-creator-item {
  margin-bottom: 8px;
}

.delete-action-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 4px;
}

.delete-workflow-btn {
  min-width: 120px;
}

.delete-action-hint {
  flex: 1;
  min-width: 120px;
}

.qywx-route-collapse :deep(.el-collapse-item__header) {
  font-size: 13px;
}

.mono-xs {
  font-family: ui-monospace, monospace;
  font-size: 11px;
  line-height: 1.4;
}
</style>
