# AIGC 逐 AI 实测验证方案（严格版）

> **文档性质**：可执行测试方案 + 记录表模板，用于人工/半自动逐 AI 回归。  
> **覆盖范围**：当前 AIGC 对话菜单中已上架的 7 个 AI（见下表）。  
> **非目标**：不替代 Playwright 端到端自动化套件；浏览器/UI 以人工实测与 Engine 真机为主。

---

## 一、目标与通过标准

| 维度 | 说明 | 严格通过条件 |
|------|------|----------------|
| **登录闭环** | 登录管理器检测、扫码/页面登录、状态回写 | 三态一致：Engine 浏览器、Admin、前端 `ENGINE_CONFIGS.loggedIn` |
| **单轮对话** | 仅文本提问 | 返回 `AI_TASK_RESULT`，结果看板有可读 `answer/textContent`，无 `TASK_ERROR` |
| **多轮/续聊** | 同一会话第二次发话 | 请求体带对应 `chatIdField`，Engine 导航到平台会话成功，回复与上下文合理 |
| **能力选项** | 各 AI 配置的 `options` | 开关与 Engine 行为一致；不支持项有降级提示（日志/前端） |
| **文件透传** | 开启「上传文件」+ 小附件 | 日志出现上传成功或软失败后的文本继续；结果不与「未上传」矛盾 |
| **可观测性** | 任务流程 / 截图 / 日志 | 任务流卡片可见；Admin `sys-info` 中 `[AIGC入站]` 含 `errorMessage=`（失败时） |
| **落库完整性** | `wc_chat_history` 等 | `userPrompt` 非空（或从历史回填）；失败时 `results` 中含错误文案 |

**整体验收**：每个 AI 至少完成「登录 + 单轮 + 续聊」三类用例；文件与多选项按矩阵选测。

---

## 二、环境前置（必检）

1. **服务**：Admin（8080）、Engine（8081 或配置端口）、MySQL、前端 `npm run dev` 或构建产物。  
2. **Engine**：Playwright Chromium 已安装；`./data/playwright` 无残留锁（见运维手册）。  
3. **账号**：各平台测试账号可登录；建议**分步测**：先单 AI 再多 AI 并发，避免浏览器池与登录态干扰。  
4. **观测**：  
   - 浏览器开发者工具 → WebSocket 帧（可选）；  
   - `WxFbsir-admin/logs/sys-info.log` 检索 `sessionId`、`errorMessage=`。

---

## 三、消息类型与平台会话字段（速查）

| AI | checkLogin | scanLogin | query | 前端 chatId 字段（`engineConfig`） |
|----|-------------|-----------|-------|-------------------------------------|
| DeepSeek | `DEEPSEEK_CHECK_LOGIN` | `DEEPSEEK_SCAN_LOGIN` | `AI_DEEPSEEK_QUERY` | `deepseekChatId` |
| 豆包 | `DOUBAO_CHECK_LOGIN` | `DOUBAO_SCAN_LOGIN` | `AI_DOUBAO_QUERY` | `dbChatId` |
| 千问 | `QIANWEN_CHECK_LOGIN` | `QIANWEN_SCAN_LOGIN` | `AI_QIANWEN_QUERY` | `toneChatId` |
| 元宝 | `YUANBAO_CHECK_LOGIN` | `YUANBAO_SCAN_LOGIN` | `AI_YUANBAO_QUERY` | `ybChatId` |
| 文心 | `WENXIN_CHECK_LOGIN` | `WENXIN_SCAN_LOGIN` | `AI_WENXIN_QUERY` | `baiduChatId` |
| 秘塔 | `MITA_CHECK_LOGIN` | `MITA_SCAN_LOGIN` | `AI_MITA_QUERY` | `metasoChatId` |
| Gitee | `GITEE_CHECK_LOGIN` | `GITEE_SCAN_LOGIN` | `AI_GITEE_QUERY` | `giteeChatId` |

配置源：`WxFbsir-ui/src/config/engineConfig.js`。

---

## 四、自动化仿真（CI/本地必跑，非 E2E）

用于**协议与映射层**回归，不启动浏览器。

```bash
# Engine：能力规约、秘塔 URL 解析等
mvn -f WxFbsir-engine/pom.xml test

# 前端：能力矩阵与 buildCompatibleAiPayload
cd WxFbsir-ui && npm run test:aigc-mapper
```

**说明**：通过上述命令不代表各站 UI 实测通过。

---

## 五、逐 AI 实测用例矩阵（严格执行顺序）

对**每一个** AI，按 **A → B → C → D** 顺序执行；任一步失败则记录缺陷并暂停该 AI 后续步骤，修复后重测。

### 通用步骤模板

| 步骤 | 操作 | 预期 |
|------|------|------|
| A | 登录管理器 → 检测登录 | 返回已登录或引导扫码；与 Engine 侧一致 |
| B | AIGC 仅勾选**当前 AI**，输入固定短句 `【实测-单轮】` 发送 | `AI_TASK_RESULT`；看板有回复 |
| C | **不新建会话**，再发 `【实测-续聊-请引用上一句】` | 仍带平台 `chatId`；回复合理 |
| D | 按下列「选项/附件」子表选测 | 见各 AI 小节 |

---

### 5.1 DeepSeek

| 用例 ID | 内容 | 通过判据 |
|---------|------|----------|
| DS-1 | 深度思考 ON | 日志/行为符合深度思考（或产品定义映射） |
| DS-2 | 联网搜索 ON（与上传互斥时勿同时开） | 无互斥冲突；回复体现检索或日志说明 |
| DS-3 | 上传文件 ON + 小 txt/docx | 软失败时仍返回文本且日志说明 |
| DS-4 | 快速/专家模式（二选一） | 模式切换日志顺序正确；续聊后 `deepseekChatId` 更新 |

---

### 5.2 豆包（Doubao）

| 用例 ID | 内容 | 通过判据 |
|---------|------|----------|
| DB-1 | 思考 ON | 发送前切换思考开关 |
| DB-2 | 快速 ON（与专家互斥） | 仅一种生效 |
| DB-3 | 专家 ON | 同上 |
| DB-4 | 上传文件 ON | 上传成功或软失败+文本继续；`dbChatId` 持久化 |

---

### 5.3 千问（Qianwen）

| 用例 ID | 内容 | 通过判据 |
|---------|------|----------|
| QW-1 | 仅文本单轮/续聊 | `toneChatId` 写入与历史加载一致 |
| QW-2 | 上传文件 ON | 附件进入输入区或软失败可接受 |

---

### 5.4 腾讯元宝（Yuanbao）

| 用例 ID | 内容 | 通过判据 |
|---------|------|----------|
| YB-1 | 单轮/续聊 | `ybChatId` 一致 |
| YB-2 | 上传文件 ON | 同上千问 |

---

### 5.5 文心一言（Wenxin）

| 用例 ID | 内容 | 通过判据 |
|---------|------|----------|
| WX-1 | 单轮/续聊 | `baiduChatId` 一致；结果看板含截图字段（若实现） |
| WX-2 | 上传文件 ON | 同上千问 |

---

### 5.6 秘塔（Mita）

| 用例 ID | 内容 | 通过判据 |
|---------|------|----------|
| MT-1 | 单轮/续聊 | `metasoChatId` 为 URL 路径片段；**无错误 hash 片段** |
| MT-2 | 上传文件 ON | 同上千问 |

---

### 5.7 Gitee AI Chat

| 用例 ID | 内容 | 通过判据 |
|---------|------|----------|
| GT-1 | 开源探索 / 仓库问答 / 帮助中心 **三选一** | 模式与页面一致 |
| GT-2 | 上传文件 ON | 与模式无冲突或日志说明 |
| GT-3 | 续聊 | `giteeChatId` 仅用 Gitee 自身 id，不串其他 AI |

---

## 六、并发与压力（严格实测补充）

在**每个 AI 单测全绿**后，增加一轮：

1. **双 AI 并发**（任选 2 个）：同一 `sessionId` 下两条 query，无串会话、无统一 `TASK_ERROR`。  
2. **七 AI 全开**（可选）：仅用于压测浏览器池；若出现 `浏览器池繁忙` 或大量 `TASK_ERROR`，记录为**环境/容量**类缺陷，与单 AI 逻辑缺陷区分。

---

## 七、缺陷记录表（复制使用）

| 日期 | AI | 用例 ID | 现象 | sys-info 关键词 / sessionId | 根因初判 | 状态 |
|------|-----|---------|------|-----------------------------|----------|------|
| | | | | | | |

---

## 八、方案维护

- **配置变更**：仅改 `engineConfig.js` 时，同步更新本文「第三节」与 `npm run test:aigc-mapper` 矩阵。  
- **新增上架 AI**：补一节「5.x」+ 第三节表格一行。  
- **版本**：与 Engine `application.yml` 中 `engine.version` 可在页脚备注以便追溯。

---

## 九、智能体分工建议（执行层面）

| 角色 | 职责 |
|------|------|
| **测试负责人** | 排期、按第五节顺序执行、填写第七节表格 |
| **环境/运维智能体** | MySQL、Engine Chromium、端口、日志路径 |
| **开发智能体** | 对失败用例做代码级追踪（Controller / Util / Router） |
| **自动化智能体** | 维护第四节命令与 CI 门禁 |

（以上为组织方式说明，非代码模块。）

---

## 十、多智能体实施与门禁（工程侧）

| 环节 | 内容 |
|------|------|
| **自动化门禁** | `mvn -f WxFbsir-engine/pom.xml test`；`cd WxFbsir-ui && npm run test:aigc-mapper` |
| **并发实测注意** | Engine `TaskExecutionTracker` 全局槽位与 `BrowserPoolManager` 信号量需 ≥ 并行路数；七路全开失败时优先判**容量/池配置**而非单 AI 逻辑 |
| **落库错误可追溯** | Admin `EngineMessageRouter` 已对 `AI_TASK_ERROR` 合并 `errorMessage` 至 `results`，`payload.data` 非 Map 时安全忽略；`sys-info` 中 `[AIGC入站]` 含 `errorMessage=` |
