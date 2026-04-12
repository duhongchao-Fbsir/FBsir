<template>
  <div class="app-container home-page">
    <section class="hero-card">
      <div class="hero-decoration hero-decoration--left"></div>
      <div class="hero-decoration hero-decoration--right"></div>

      <div class="hero-layout">
        <div class="hero-main">
          <div class="hero-badge">WxFbsir · AI工具链与智能协同平台</div>
          <div class="hero-title-group">
            <p class="hero-greeting">{{ greetingText }}，{{ userDisplayName }}</p>
            <h1>把内容生产、知识沉淀与自动化执行放到同一工作台</h1>
            <p class="hero-description">
              福帮手不是单点式写作工具，而是围绕 <strong>公众号运营</strong>、<strong>多 AI 协同</strong>、<strong>文档解析</strong>、<strong>知识库同步</strong>
              与 <strong>Engine / OpenClaw 主机纳管</strong> 形成的完整业务闭环。
            </p>
          </div>

          <div class="hero-actions">
            <el-button
              v-if="hasAnyPermission(['business:daily:view'])"
              type="primary"
              size="large"
              class="hero-btn hero-btn--primary"
              @click="navigateTo('/content/daily-assistant')"
            >
              开始内容生产
            </el-button>
            <el-button
              v-if="hasAnyPermission(['business:knowledge:view'])"
              size="large"
              class="hero-btn hero-btn--secondary"
              @click="navigateTo('/content/knowledge')"
            >
              进入知识库
            </el-button>
            <el-button
              size="large"
              text
              class="hero-btn hero-btn--text"
              @click="navigateTo('/user/profile/officeAccount')"
            >
              完成前置配置
            </el-button>
          </div>

          <div class="hero-tags">
            <span class="hero-tag">多模型协同</span>
            <span class="hero-tag">公众号草稿箱投递</span>
            <span class="hero-tag">文档解析</span>
            <span class="hero-tag">知识库同步</span>
            <span class="hero-tag">Playwright / OpenClaw</span>
          </div>
        </div>

        <div class="hero-aside">
          <div class="route-card">
            <div class="route-card__header">
              <span>推荐上手路径</span>
              <el-tag type="success" effect="dark">当前项目主线</el-tag>
            </div>

            <div class="route-list">
              <div v-for="item in visibleQuickStartSteps" :key="item.title" class="route-item">
                <div class="route-item__index">{{ item.step }}</div>
                <div class="route-item__content">
                  <h3>{{ item.title }}</h3>
                  <p>{{ item.description }}</p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="metrics-grid">
      <article v-for="item in metrics" :key="item.title" class="metric-card">
        <div class="metric-card__label">{{ item.label }}</div>
        <div class="metric-card__title">{{ item.title }}</div>
        <p>{{ item.description }}</p>
      </article>
    </section>

    <section class="section-block">
      <div class="section-header">
        <div>
          <span class="section-kicker">Core workbench</span>
          <h2>核心工作台入口</h2>
          <p>首页直接映射项目里的真实模块，而不是抽象概念卡片。</p>
        </div>
        <el-tag type="info" effect="plain">按业务闭环直达</el-tag>
      </div>

      <div class="entry-grid">
        <article v-for="item in visibleWorkbenchCards" :key="item.title" class="entry-card">
          <div class="entry-card__top">
            <div class="entry-icon">
              <el-icon :size="22">
                <component :is="item.icon" />
              </el-icon>
            </div>
            <span class="entry-highlight">{{ item.highlight }}</span>
          </div>

          <div class="entry-card__body">
            <h3>{{ item.title }}</h3>
            <p>{{ item.description }}</p>
          </div>

          <div class="entry-card__footer">
            <span>{{ item.footer }}</span>
            <el-button type="primary" link @click="navigateTo(item.route)">进入模块</el-button>
          </div>
        </article>
      </div>
    </section>

    <section class="dual-grid">
      <div class="section-block section-block--compact">
        <div class="section-header">
          <div>
            <span class="section-kicker">Workflow</span>
            <h2>典型业务流</h2>
            <p>从内容生成到发布追踪，把当前项目最强的链路说清楚。</p>
          </div>
        </div>

        <div class="timeline-list">
          <div v-for="item in workflowSteps" :key="item.title" class="timeline-item">
            <div class="timeline-item__index">{{ item.step }}</div>
            <div class="timeline-item__content">
              <h3>{{ item.title }}</h3>
              <p>{{ item.description }}</p>
            </div>
          </div>
        </div>
      </div>

      <div class="section-block section-block--compact">
        <div class="section-header">
          <div>
            <span class="section-kicker">Platform edge</span>
            <h2>这个项目的差异化能力</h2>
            <p>首页不仅展示“能做什么”，也明确说明“为什么这套系统值得用”。</p>
          </div>
        </div>

        <div class="capability-list">
          <article v-for="item in capabilities" :key="item.title" class="capability-card">
            <div class="capability-icon">
              <el-icon :size="20">
                <component :is="item.icon" />
              </el-icon>
            </div>
            <div class="capability-content">
              <h3>{{ item.title }}</h3>
              <p>{{ item.description }}</p>
            </div>
          </article>
        </div>
      </div>
    </section>

    <section class="section-block">
      <div class="section-header">
        <div>
          <span class="section-kicker">Operations</span>
          <h2>前置配置与运维入口</h2>
          <p>新用户最容易卡住的，不是生成按钮，而是配置、登录和链路状态。</p>
        </div>
      </div>

      <div class="shortcut-grid">
        <article v-for="item in visibleShortcutCards" :key="item.title" class="shortcut-card">
          <div class="shortcut-card__head">
            <div class="shortcut-icon">
              <el-icon :size="18">
                <component :is="item.icon" />
              </el-icon>
            </div>
            <h3>{{ item.title }}</h3>
          </div>
          <p>{{ item.description }}</p>
          <el-button text class="shortcut-link" @click="navigateTo(item.route)">
            {{ item.action }}
          </el-button>
        </article>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  ChatDotRound,
  Collection,
  Connection,
  DataAnalysis,
  Document,
  EditPen,
  Guide,
  Reading,
  Setting,
  Upload
} from '@element-plus/icons-vue'
import useUserStore from '@/store/modules/user'

const router = useRouter()
const userStore = useUserStore()

const userDisplayName = computed(() => userStore.nickName || userStore.name || '欢迎回来')

const greetingText = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

function hasAnyPermission(permissionList = []) {
  if (!permissionList.length) return true
  const permissions = userStore.permissions || []
  if (!permissions.length) return true
  return permissions.includes('*:*:*') || permissionList.some(item => permissions.includes(item))
}

function navigateTo(path) {
  if (!path) return
  router.push(path)
}

const metrics = [
  {
    label: '内容生产',
    title: '多模型协同生成',
    description: '围绕日更助手与 AI助手，支持多模型初稿、优化整合与输出物复用。'
  },
  {
    label: '运营闭环',
    title: '公众号草稿箱投递',
    description: '从公众号配置、文章投递到发布记录追踪，覆盖完整投递链路。'
  },
  {
    label: '知识沉淀',
    title: '文档解析 × 知识库同步',
    description: '上传文档、提取内容，再把结构化知识同步到元器或企微机器人。'
  },
  {
    label: '自动化执行',
    title: 'Engine / OpenClaw 主机纳管',
    description: '登录管理、调试、白名单与工作流节点管理统一放在主机能力层。'
  }
]

const quickStartSteps = [
  {
    step: '01',
    title: '完成账号与公众号配置',
    description: '先在个人中心补齐公众号信息与主机相关配置，确保后续投递和自动化执行可用。',
    permissions: []
  },
  {
    step: '02',
    title: '从内容工作台发起创作',
    description: '进入日更助手或 AI助手，让多模型协同生成初稿、优化结果和后续输出物。',
    permissions: ['business:daily:view', 'aigc:assistant:list']
  },
  {
    step: '03',
    title: '沉淀文档与知识资产',
    description: '把上传文档、解析结果和业务知识同步到知识库，形成可复用资产。',
    permissions: ['business:document:view', 'business:knowledge:view']
  },
  {
    step: '04',
    title: '保障自动化链路在线',
    description: '通过登录管理器、主机管理和工作流节点编辑，确保 Engine 与 OpenClaw 处于健康状态。',
    permissions: ['engine:login:manager', 'business:host:whitelist:view', 'business:host:apps:view']
  }
]

const workbenchCards = [
  {
    title: '日更助手',
    description: '多模型并行生成公众号文章，支持优化整合、智能排版和草稿箱投递。',
    highlight: '内容生产主闭环',
    footer: '最适合直接开始发文任务',
    route: '/content/daily-assistant',
    permissions: ['business:daily:view'],
    icon: EditPen
  },
  {
    title: 'AI助手',
    description: '统一接入多 AI 服务、会话历史和输出物能力，适合通用生成与问答协作。',
    highlight: '多 AI 协作',
    footer: '适合灵活咨询与生成类场景',
    route: '/content/aigc',
    permissions: ['aigc:assistant:list'],
    icon: ChatDotRound
  },
  {
    title: '文档解析助手',
    description: '上传 PDF / Word / TXT 等文件，基于元器工作流异步提取关键信息。',
    highlight: '文档智能解析',
    footer: '适合把资料快速转成可用内容',
    route: '/content/document-parse',
    permissions: ['business:document:view'],
    icon: Document
  },
  {
    title: '知识库',
    description: '管理个人与公共知识库，并同步到腾讯元器或企业微信机器人。',
    highlight: '长期资产沉淀',
    footer: '适合建立可复用知识中台',
    route: '/content/knowledge',
    permissions: ['business:knowledge:view'],
    icon: Collection
  },
  {
    title: '发布记录',
    description: '查看投递结果、素材 ID 与失败原因，让公众号发布链路可追踪、可排查。',
    highlight: '投递结果追踪',
    footer: '适合回看和复盘投递效果',
    route: '/content/publish-record',
    permissions: ['business:publish:list'],
    icon: Upload
  },
  {
    title: '登录管理器',
    description: '统一管理 Engine 服务的扫码登录与状态检测，保障 AI 和自动化模块在线。',
    highlight: '执行链路可用性',
    footer: '适合处理服务未登录和失效问题',
    route: '/content/login-manager',
    permissions: ['engine:login:manager'],
    icon: Connection
  },
  {
    title: '工作流节点编辑',
    description: '编辑元器工作流节点与策略映射，把业务能力延伸到可编排的自动化流程。',
    highlight: '工作流编排',
    footer: '适合深入定制元器能力',
    route: '/host/apps',
    permissions: ['business:host:apps:view'],
    icon: Setting
  },
  {
    title: 'OpenClaw / 主机管理',
    description: '集中管理 Engine 与 OpenClaw 主机白名单、在线连接和访问控制。',
    highlight: '自动化中台',
    footer: '适合排查和纳管执行节点',
    route: '/host/whitelist',
    permissions: ['business:host:whitelist:view'],
    icon: Guide
  }
]

const workflowSteps = [
  {
    step: '01',
    title: '配置基础能力',
    description: '先完成公众号配置、主机绑定与必要登录，后面的创作、同步与发布才真正可执行。'
  },
  {
    step: '02',
    title: '发起内容或解析任务',
    description: '可以从日更助手生成文章，也可以从文档解析助手把外部资料转成可消费内容。'
  },
  {
    step: '03',
    title: '沉淀到知识库与输出物',
    description: '把高价值结果转成知识库或结构化输出物，供后续复用和团队协作。'
  },
  {
    step: '04',
    title: '依赖 Engine / 工作流执行',
    description: '登录管理器、主机管理和工作流节点编辑共同保证自动化链路能持续跑通。'
  },
  {
    step: '05',
    title: '投递并追踪结果',
    description: '最终投递到公众号草稿箱，再通过发布记录回看状态、素材ID 与错误原因。'
  }
]

const capabilities = [
  {
    title: '内容生产闭环',
    description: '从文章标题输入、多模型初稿、优化整合到排版投递，闭环非常完整。',
    icon: EditPen
  },
  {
    title: '知识与文档双轮驱动',
    description: '文档解析把资料结构化，知识库负责长期积累与同步下发。',
    icon: Reading
  },
  {
    title: '自动化执行中台',
    description: '项目不只是前端页面，更有 Engine、OpenClaw、WebSocket 调试与登录纳管能力。',
    icon: Connection
  },
  {
    title: '平台化扩展能力',
    description: '工作流节点编辑、策略管理和权限体系，让它更像一个可扩展平台。',
    icon: DataAnalysis
  }
]

const shortcutCards = [
  {
    title: '公众号配置',
    description: '发文前的第一步，配置 AppID、密钥、作者与封面素材。',
    route: '/user/profile/officeAccount',
    action: '前往配置',
    permissions: [],
    icon: Setting
  },
  {
    title: '登录状态检查',
    description: '当 AI 服务或自动化链路不可用时，优先到登录管理器补登录。',
    route: '/content/login-manager',
    action: '查看状态',
    permissions: ['engine:login:manager'],
    icon: Connection
  },
  {
    title: '主机白名单 / OpenClaw',
    description: '统一管理执行节点访问权限和健康状态，是自动化能力的底座。',
    route: '/host/whitelist',
    action: '进入主机管理',
    permissions: ['business:host:whitelist:view'],
    icon: Guide
  },
  {
    title: '工作流节点编辑',
    description: '需要深入调整元器工作流时，可直接从首页跳转进入。',
    route: '/host/apps',
    action: '进入工作流',
    permissions: ['business:host:apps:view'],
    icon: Document
  }
]

const visibleWorkbenchCards = computed(() => workbenchCards.filter(item => hasAnyPermission(item.permissions)))
const visibleShortcutCards = computed(() => shortcutCards.filter(item => hasAnyPermission(item.permissions)))
const visibleQuickStartSteps = computed(() => quickStartSteps.filter(item => hasAnyPermission(item.permissions)))
</script>

<style scoped lang="scss">
.home-page {
  padding: 24px;
  background:
    radial-gradient(circle at top left, rgba(59, 130, 246, 0.12), transparent 32%),
    linear-gradient(180deg, #f3f7ff 0%, #f8fbff 28%, #ffffff 100%);
  min-height: calc(100vh - 84px);

  .hero-card {
    position: relative;
    overflow: hidden;
    padding: 32px;
    border-radius: 28px;
    background: linear-gradient(135deg, #0f172a 0%, #1d4ed8 58%, #38bdf8 100%);
    box-shadow: 0 24px 60px rgba(15, 23, 42, 0.16);
    margin-bottom: 24px;
  }

  .hero-decoration {
    position: absolute;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.08);
    filter: blur(1px);

    &--left {
      width: 220px;
      height: 220px;
      left: -70px;
      top: -90px;
    }

    &--right {
      width: 280px;
      height: 280px;
      right: -110px;
      bottom: -120px;
    }
  }

  .hero-layout {
    position: relative;
    z-index: 1;
    display: grid;
    grid-template-columns: minmax(0, 1.7fr) minmax(320px, 0.95fr);
    gap: 24px;
    align-items: stretch;
  }

  .hero-main {
    display: flex;
    flex-direction: column;
    gap: 22px;
    color: #f8fbff;
  }

  .hero-badge {
    display: inline-flex;
    align-items: center;
    width: fit-content;
    padding: 8px 14px;
    border: 1px solid rgba(255, 255, 255, 0.18);
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.08);
    color: rgba(255, 255, 255, 0.92);
    font-size: 13px;
    letter-spacing: 0.04em;
  }

  .hero-title-group {
    .hero-greeting {
      margin: 0 0 12px;
      font-size: 16px;
      color: rgba(255, 255, 255, 0.8);
    }

    h1 {
      margin: 0;
      font-size: 38px;
      line-height: 1.2;
      font-weight: 700;
      letter-spacing: -0.02em;
    }

    .hero-description {
      margin: 18px 0 0;
      max-width: 860px;
      font-size: 16px;
      line-height: 1.85;
      color: rgba(255, 255, 255, 0.88);

      strong {
        color: #ffffff;
        font-weight: 700;
      }
    }
  }

  .hero-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
  }

  .hero-btn {
    min-width: 132px;
    border-radius: 999px;
    font-weight: 600;

    &--primary {
      border: none;
      box-shadow: 0 10px 24px rgba(15, 23, 42, 0.18);
    }

    &--secondary {
      background: rgba(255, 255, 255, 0.12);
      color: #fff;
      border: 1px solid rgba(255, 255, 255, 0.28);
    }

    &--text {
      color: rgba(255, 255, 255, 0.82);
    }
  }

  .hero-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }

  .hero-tag {
    padding: 8px 12px;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.1);
    color: rgba(255, 255, 255, 0.9);
    font-size: 13px;
    border: 1px solid rgba(255, 255, 255, 0.14);
  }

  .hero-aside {
    display: flex;
  }

  .route-card {
    width: 100%;
    padding: 22px;
    border-radius: 24px;
    background: rgba(15, 23, 42, 0.18);
    border: 1px solid rgba(255, 255, 255, 0.15);
    backdrop-filter: blur(10px);
    color: #fff;

    &__header,
    .route-card__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 18px;
      font-size: 15px;
      font-weight: 600;
    }
  }

  .route-list {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .route-item {
    display: grid;
    grid-template-columns: 42px 1fr;
    gap: 12px;
    padding: 14px;
    border-radius: 18px;
    background: rgba(255, 255, 255, 0.08);

    &__index {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 42px;
      height: 42px;
      border-radius: 14px;
      background: rgba(255, 255, 255, 0.14);
      font-weight: 700;
      color: #fff;
    }

    &__content {
      h3 {
        margin: 0 0 6px;
        font-size: 15px;
        color: #ffffff;
      }

      p {
        margin: 0;
        font-size: 13px;
        line-height: 1.75;
        color: rgba(255, 255, 255, 0.78);
      }
    }
  }

  .metrics-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 16px;
    margin-bottom: 24px;
  }

  .metric-card {
    padding: 22px;
    border-radius: 22px;
    background: rgba(255, 255, 255, 0.92);
    border: 1px solid rgba(15, 23, 42, 0.06);
    box-shadow: 0 12px 30px rgba(15, 23, 42, 0.05);

    &__label {
      font-size: 12px;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: #3b82f6;
      margin-bottom: 10px;
      font-weight: 700;
    }

    &__title {
      font-size: 20px;
      font-weight: 700;
      color: #0f172a;
      margin-bottom: 10px;
      line-height: 1.35;
    }

    p {
      margin: 0;
      font-size: 14px;
      line-height: 1.75;
      color: #64748b;
    }
  }

  .section-block {
    padding: 28px;
    border-radius: 28px;
    background: rgba(255, 255, 255, 0.94);
    border: 1px solid rgba(15, 23, 42, 0.06);
    box-shadow: 0 14px 36px rgba(15, 23, 42, 0.05);
    margin-bottom: 24px;

    &--compact {
      margin-bottom: 0;
      height: 100%;
    }
  }

  .section-header {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 18px;
    margin-bottom: 22px;

    .section-kicker {
      display: inline-block;
      margin-bottom: 8px;
      font-size: 12px;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: #3b82f6;
      font-weight: 700;
    }

    h2 {
      margin: 0;
      font-size: 28px;
      line-height: 1.25;
      color: #0f172a;
    }

    p {
      margin: 10px 0 0;
      font-size: 14px;
      line-height: 1.75;
      color: #64748b;
    }
  }

  .entry-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 16px;
  }

  .entry-card {
    display: flex;
    flex-direction: column;
    gap: 18px;
    padding: 22px;
    min-height: 230px;
    border-radius: 22px;
    background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
    border: 1px solid rgba(59, 130, 246, 0.12);
    transition: transform 0.25s ease, box-shadow 0.25s ease, border-color 0.25s ease;

    &:hover {
      transform: translateY(-6px);
      box-shadow: 0 16px 30px rgba(59, 130, 246, 0.12);
      border-color: rgba(59, 130, 246, 0.22);
    }

    &__top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
    }

    &__body {
      flex: 1;

      h3 {
        margin: 0 0 10px;
        font-size: 20px;
        color: #0f172a;
      }

      p {
        margin: 0;
        font-size: 14px;
        line-height: 1.8;
        color: #64748b;
      }
    }

    &__footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding-top: 14px;
      border-top: 1px solid rgba(148, 163, 184, 0.16);

      span {
        font-size: 13px;
        color: #94a3b8;
      }
    }
  }

  .entry-icon,
  .shortcut-icon,
  .capability-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 46px;
    height: 46px;
    border-radius: 16px;
    background: linear-gradient(135deg, rgba(59, 130, 246, 0.12), rgba(14, 165, 233, 0.22));
    color: #2563eb;
  }

  .entry-highlight {
    padding: 6px 10px;
    border-radius: 999px;
    background: rgba(59, 130, 246, 0.08);
    color: #2563eb;
    font-size: 12px;
    font-weight: 600;
  }

  .dual-grid {
    display: grid;
    grid-template-columns: minmax(0, 1.15fr) minmax(0, 0.95fr);
    gap: 24px;
    margin-bottom: 24px;
  }

  .timeline-list {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .timeline-item {
    display: grid;
    grid-template-columns: 48px 1fr;
    gap: 16px;
    padding: 16px;
    border-radius: 20px;
    background: #f8fbff;
    border: 1px solid rgba(59, 130, 246, 0.08);

    &__index {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 48px;
      height: 48px;
      border-radius: 16px;
      background: linear-gradient(135deg, #2563eb, #38bdf8);
      color: #fff;
      font-weight: 700;
    }

    &__content {
      h3 {
        margin: 0 0 8px;
        font-size: 17px;
        color: #0f172a;
      }

      p {
        margin: 0;
        font-size: 14px;
        line-height: 1.85;
        color: #64748b;
      }
    }
  }

  .capability-list {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .capability-card {
    display: grid;
    grid-template-columns: 52px 1fr;
    gap: 14px;
    padding: 16px;
    border-radius: 20px;
    background: #f8fafc;
    border: 1px solid rgba(148, 163, 184, 0.14);
  }

  .capability-content {
    h3 {
      margin: 0 0 8px;
      font-size: 17px;
      color: #0f172a;
    }

    p {
      margin: 0;
      font-size: 14px;
      line-height: 1.8;
      color: #64748b;
    }
  }

  .shortcut-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 16px;
  }

  .shortcut-card {
    padding: 20px;
    border-radius: 20px;
    background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
    border: 1px solid rgba(148, 163, 184, 0.14);

    &__head {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 14px;

      h3 {
        margin: 0;
        font-size: 18px;
        color: #0f172a;
      }
    }

    p {
      margin: 0 0 12px;
      font-size: 14px;
      line-height: 1.8;
      color: #64748b;
      min-height: 76px;
    }
  }

  .shortcut-link {
    padding: 0;
    font-weight: 600;
  }
}

@media (max-width: 1440px) {
  .home-page {
    .entry-grid,
    .shortcut-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }

    .metrics-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
}

@media (max-width: 1200px) {
  .home-page {
    .hero-layout,
    .dual-grid {
      grid-template-columns: 1fr;
    }
  }
}

@media (max-width: 768px) {
  .home-page {
    padding: 16px;

    .hero-card,
    .section-block {
      padding: 20px;
      border-radius: 22px;
    }

    .hero-title-group {
      h1 {
        font-size: 30px;
      }
    }

    .metrics-grid,
    .entry-grid,
    .shortcut-grid {
      grid-template-columns: 1fr;
    }

    .section-header {
      flex-direction: column;
      align-items: flex-start;
    }

    .entry-card__footer,
    .hero-actions {
      flex-direction: column;
      align-items: stretch;
    }

    .route-card__header {
      flex-direction: column;
      align-items: flex-start;
    }
  }
}
</style>
