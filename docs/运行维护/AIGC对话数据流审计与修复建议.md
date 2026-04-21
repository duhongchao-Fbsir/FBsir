# AIGC 对话数据流：完整性 / 正确性 / 一致性审计与修复建议

> 审计视角：以 **AI 对话为主轴**，覆盖 **输入 → Admin 路由与持久化 → Engine → 回写 → 前端展示与历史/草稿**。  
> 审计日期：2026-04-18 · 对齐代码：`WxFbsir-ui`、`WxFbsir-business`（`ClientMessageRouter`、`EngineMessageRouter`、`AigcServiceImpl`、`AigcMapper.xml`）、`WxFbsir-engine`（能力注册与回包字段）。

---

## 1. 端到端数据流（事实描述）

| 阶段 | 关键载体 | 关键标识字段 |
|------|-----------|----------------|
| 前端发送 | WebSocket JSON | `type`（如 `AI_*_QUERY`）、顶层 `chatId`（分组）、`payload.sessionId`（一轮追踪）、`payload.aiType`、`engineId` |
| Admin 入站 | `ClientMessageRouter` | 强制生成 `requestId`；写入 `userId`、`sourceType=WEBSOCKET`；`sessionId`↔`chatId` 缓存 |
| 预持久化 | `AigcServiceImpl.saveInitialRequest` | 行主键 `wc_chat_history.id` = **`sessionId`**；`data` 存整段 `AiRequest` JSON |
| Engine 执行 | 各 AI Controller / Util | 平台侧会话 ID 多在 `payload.data.chatId`（与前端分组 `chatId` 不同概念） |
| 结果回写 | `EngineMessageRouter` | `AI_TASK_LOG` / `SCREENSHOT` / `RESULT` / `ERROR`；合并 `results[]`；`setAiChatIdField` 写入各 `*_chat_id` |
| 前端归一 | `engineMessageNormalizer.js` | `messageType`/`type`、`payload`/`payload.data`、`aiType` 多路径兜底 |

**设计意图**：同一「用户轮次」共享一个 `sessionId`、一条 `wc_chat_history` 行，`data` 内 **`results` 数组按 `aiType` 合并**（见 `mergeAiResults`）。

---

## 2. 问题清单（漂移 / 污染 / 筛检 / 遗漏 / 错误）

### P0｜会话状态管理未接入（遗漏）

**现象**：`AiSessionStateManager.createSession(...)` 在 **Business 全仓库无调用点**；仅 `markAiCompleted` / `markAiFailed` 被调用。  
**后果**：`sessions.get(sessionId)` 恒为 `null`，**整轮完成判定、进度统计、失败归因**在该组件上**全部失效**；相关日志（如「整轮对话完成」）实际**不会按设计触发**。

**修复建议**：

1. 在 **`ClientMessageRouter`** 于同一 `sessionId` 的 **首轮** `AI_*` 请求（或聚合完本轮 `enabledAIs` 后）调用 `createSession(sessionId, userId, Set.of(aiTypes...))`。  
2. 若多请求并发到达，使用 **幂等**：`ConcurrentHashMap.computeIfAbsent` 或 `createSessionIfAbsent`，避免覆盖已注册 AI 集合。  
3. 可选：`markAiStarted` 在转发 Engine 前调用，与 Engine 侧任务启动对齐。

---

### P0｜历史列表按 `aiName` 筛选时「筛检」失效（SQL 行为风险）

**位置**：`AigcMapper.xml` → `getChatHistory` 中 `<choose>` 仅覆盖 `deepseek` / `yuanbao` / `gitee` / `wenxin` / `mita` 等，**未覆盖 `doubao`、`qianwen`（千问）等**。

**现象**：当 `aiName` 为未列出的值时，`<choose>` **无匹配分支** → 该 `<if>` 块内输出为空，**等价于不按 AI 过滤**，可能返回 **全量历史**（与产品预期「只看某 AI」不符）。

**修复建议**：

1. 为 **所有已上架 AI** 补全 `<when>`，映射到对应 `*_chat_id IS NOT NULL`（与 `setAiChatIdField` 一致）。  
2. 增加 `<otherwise>`：`AND 1=0` 或记录告警日志，**禁止**静默退回「不过滤」。

---

### P1｜预保存并发写入同一 `sessionId`（污染 / 竞态）

**位置**：`saveInitialRequest` + `saveChatData` 的 `ON DUPLICATE KEY UPDATE`。

**现象**：多 AI **并行**发送时，多次预保存同一 `sessionId`：**`userPrompt`、`data`（整段初始请求 JSON）** 可能被后到达的请求 **覆盖**，短暂出现「只像单 AI 请求」的中间态；虽后续 `saveAiResult` 会再合并，但 **审计与排错** 时易误判；极端情况下若某路失败，中间态可能残留。

**修复建议**：

1. **合并式预保存**：预保存只执行 **一次**（例如仅第一个到达的 `AI_*` 插入，后续 duplicate 时 **不覆盖** `userPrompt`/`data`，或只合并 `extraParams`）。  
2. 或将预保存 `data` 改为 **仅记录元数据骨架**（`sessionId`、`chatId`、AI 列表），完整 `AiRequest` 不重复覆盖。  
3. 短期：**同步块**（按 `sessionId`）串行化预保存，降低竞态（注意线程与性能）。

---

### P1｜`chatId` / `sessionId` 兜底与「漂移」

**位置**：`EngineMessageRouter.saveAiResult`：`actualChatId` 空时依次用缓存、`sessionId`。

**风险**：分组 ID 与追踪 ID 混用会导致 **历史分组错乱** 或 **与前端 `currentChatId` 不一致**。

**修复建议**：

1. 契约文档化：`chatId` = 用户会话分组；`sessionId` = 单次多 AI 轮次；**禁止**长期用 `sessionId` 代替 `chatId` 展示给用户。  
2. 若缓存未命中，**打 ERROR 级日志**并打点监控，而非静默兜底。

---

### P2｜前端 `normalizeEngineInboundMessage` 默认 `aiType = 'unknown'`

**位置**：`engineMessageNormalizer.js`。

**风险**：Engine 漏传 `aiType` 时，UI 与草稿可能归入 **unknown**，与矩阵、草稿筛选不一致。

**修复建议**：与发送侧一致，**从 `messageType` 反推** `aiType`（可与 `inferAiTypeFromMessageType` 共用逻辑）；仅在无法推断时用 `unknown`。

---

### P2｜草稿表 `user_name` 存用户 ID 字符串（命名污染）

**位置**：`wc_playwright_draft.user_name` 实际绑定 `userId`（见 `saveDraft` / `getDrafts` 条件）。

**建议**：迁移期双写、新列 `user_id`；文档与代码注释统一写清，避免运维按「用户名」查询出错。

---

### P2｜双路径写库（已收口但仍需门禁）

**现象**：`AiResultHandler` 曾作为废弃占位。  
**状态**：类已 **删除**；写库以 `EngineMessageRouter` 为唯一路径。建议在 CI 中对 `AiResultHandler` 字符串做 `grep` 防回流。

---

## 3. 一致性检查清单（上线 / 回归）

| 检查项 | 方法 |
|--------|------|
| 多 AI 同轮 `sessionId` 单行合并 | DB `wc_chat_history.data` 中 `results` 条数 = 启用 AI 数（成功路径） |
| 平台 chatId 落库 | 各 `*_chat_id` 与 Engine 回包 `data.chatId` 一致 |
| 历史筛选 | 对每个 `aiName` 传参执行 `getChatHistory`，结果仅含该 AI 有会话 ID 的记录 |
| E2E | 已有 `tools/e2e-aigc-regression.ps1`；合并 P0 修复后补 **多 AI + 筛选** 用例 |

---

## 4. 建议实施顺序与落地情况

| 项 | 状态 |
|----|------|
| P0 `getChatHistory` 补全 + `otherwise` | **已落地**（`AigcMapper.xml`） |
| P0 `AiSessionStateManager` 路由接入 | **已落地**（`ClientMessageRouter.ensureSession`、`mergeExpectedAiTypes`） |
| P1 预保存并发 | **已落地**（`saveInitialRequest` 同步块 + 已存在则跳过） |
| P2 前端 `aiType` 推断 | **已落地**（`engineMessageNormalizer.js`） |
| 删除 `AiResultHandler` | **已落地** |
| 草稿 `user_id` 双写、产出物 `results` 与嵌套 answer 回退、`getDraft` GET、草稿 AI 列表按用户过滤、能力脚本 | **已落地**（见当前代码与 `tools/run-ci-aigc-checks.ps1`） |

---

## 5. 说明

- **Gitee OAuth / chat.gitee.com** 等为 **产品能力 URL**，与 **源码托管 GitHub** 无关；勿在业务 URL 上改为 GitHub。  
- 本审计不替代渗透与性能测试；批量 `progressLogs`/截图队列与最终 `saveAiResult` 的时序若需强一致，可另开「批处理与最终态」专题。

---

## 6. 关联延伸（更高维治理）

- [AI助手关联域全局洞察与一致性升级](../架构与规范/AI助手关联域全局洞察与一致性升级.md)：能力三层真源、产出物闭环、操作日志与 WebSocket 排障、OpenAPI 与复用规范。
