# AI 助手关联域：全局洞察与一致性升级

> 定位：在 [AIGC 对话数据流审计](../运行维护/AIGC对话数据流审计与修复建议.md) 与 [全局一致性对齐说明](../全局一致性对齐说明.md) 之上，向 **能力对齐、产出物、日志、接口文档、通用复用** 延伸，形成可执行的治理视图。  
> 更新：2026-04-18

---

## 1. 能力对齐：三层真源与防漂移

| 层级 | 载体 | 职责 | 与其它层关系 |
|------|------|------|----------------|
| **L1 执行真源** | `WxFbsir-engine`：`CapabilityRegistry` + 各 `*Controller` 注解 | 声明 **实际可处理** 的 `type`（含流式/单次、别名） | 若未注册，Admin 再开放也会在 Engine 侧失败 |
| **L2 业务编排真源** | `ClientMessageRouter` / `EngineSession.hasCapability` | 请求到达前校验 **Engine 是否具备能力** | 应与 L1 发布清单同步（版本升级时注意） |
| **L3 体验与降级真源** | `WxFbsir-ui`：`engineConfig.js`（展示/登录/字段）、`aiCapabilityMapper.js`（`AI_CAPABILITY_MATRIX`） | **选项降级、文案、不支持的开关提示** | 允许比 L1「窄」（隐藏未就绪能力），但不得与 L1 **语义冲突** |

**Admin 侧补充**：`AigcServiceImpl#getAvailableAiList` 为 **运营/列表型** 硬编码，用于下拉或展示；与 L3 不一致时，以 **L1+L2 在线** 为准，列表应逐步改为 **读配置或读 Engine 心跳能力集**（中长期）。

**升级动作（建议）**

1. 建立 **「能力对照表」** 单一维护入口：推荐以 **Engine 导出或单元测试** 生成 `AI_*` / `*_CHECK_LOGIN` 清单，CI 对比 `ENGINE_CONFIGS` 与 `AI_CAPABILITY_MATRIX` 键集合。  
2. 文档：`docs/aigc-test-matrix.md` 已描述场景边界；扩展一列 **「能力来源：Registry / UI-only」**。  
3. 新 AI 上架 checklist：**先 L1 注册 → L2 白名单/会话 → L3 卡片与矩阵 → E2E**。

---

## 2. 产出物利用：数据契约与闭环

**前端入口（与截图一致）**：`生成输出物` → `导出Markdown` → `推送Webhook`；编辑弹窗 **保存输出物**。

| 环节 | 关键标识 | 风险 |
|------|-----------|------|
| 生成 | `sessionId`（与对话轮次 `wc_chat_history.id` 一致） | 无会话或会话未落库则失败 |
| 保存 | `sessionId` + `artifactId` | 权限 `business:output:save` |
| 导出 | `exportMarkdown` / `exportJson` 二进制与 JSON 错误混用 | 前端已按 `Content-Type` 区分（见 `output.js` 注释） |
| Webhook | 后端组包推送 | 需配置目标 URL 与安全策略 |

**一致性要求**

- 产出物内容应来自 **已合并的会话数据**（含多 AI `results[]`），与 [数据流审计](../运行维护/AIGC对话数据流审计与修复建议.md) 中 **单行合并模型** 一致；若预保存竞态未治理，产出物可能出现 **单 AI 快照**。  
- **建议**：产出物生成前校验 `data.results` 与本轮 `enabledAIs` 数量（或显式允许部分失败）。

---

## 3. 日志管理：操作日志 vs 业务流水

**已实现（REST）**：`AigcController` 上关键操作已加 `@Log`（AI 请求、草稿、历史、输出物生成/导出/推送/保存等），可在 **系统管理 → 操作日志** 中按模块/人员/状态筛选（与第二张截图能力对应）。

**缺口（架构性）**

- **WebSocket 主路径**（多 AI 实时对话）**不会**产生与每次消息一一对应的 `sys_oper_log` 行；排障依赖 **应用日志**（`ClientMessageRouter` / `EngineMessageRouter` 中 `stage=preSave`、`platformChatId` 等）。  
- **建议升级**：  
  - **短期**：规范关键字检索（`sessionId`、`requestId`、`aiType`、`platformChatId`），写入运维手册。  
  - **中期**：可选 **业务流水表**（按 `sessionId`+`aiType` 聚合）或对接 ELK；**不作为**替代现有操作日志。  
  - **长期**：对高价值操作（生成输出物、Webhook 推送失败）保留 **强审计**（已有 `@Log` 覆盖 REST）。

---

## 4. 接口完善与 OpenAPI

**现状**

- SpringDoc OpenAPI 聚合在 Admin；配置类 `SwaggerConfig` 曾使用 **若依模板占位描述**（「集团公司人员、XXX 模块」），与 **福帮手 AI 工具链** 产品不符，易造成外部读者误解。  
- **已修复**：见 `WxFbsir-admin` 中 `SwaggerConfig` — 标题/描述与 `wxfbsir.name`、版本及 WebSocket 说明对齐。

**接口分层说明（避免读者误解）**

| 类型 | 路径形态 | 文档位置 |
|------|-----------|-----------|
| REST | `/aigc/**` 等 | OpenAPI `/v3/api-docs`、Knife4j |
| WebSocket | `/ws/client`、`/ws/engine` | `docs/功能说明/engine/WebSocket通信完整指南.md` |

**升级动作**

1. Controller 层对 **输出物、历史、草稿** 补充 **SpringDoc 注解**（`@Tag` / `@Operation`），与 `AigcController` 类头注释同步。  
2. 前端 `src/api/**` 与后端路径 **单一映射**：变更时同步 `verify-ai-capability-mapper` 类脚本（若涉及路径常量）。

---

## 5. 通用能力：复用规范（工程化）

| 能力 | 位置 | 规范 |
|------|------|------|
| 入站消息归一 | `engineMessageNormalizer.js` | 新增 Engine 消息类型时，先补归一字段再写 UI 分支 |
| 出站负载构建 | `aiCapabilityMapper.js` `buildCompatibleAiPayload` | 新 AI 先补 `AI_CAPABILITY_MATRIX` 再放开开关 |
| 会话与分组 ID | `chatId` / `sessionId` / `platformChatId` | 见数据流审计文档；禁止在业务层混用命名 |
| 写库单路径 | `EngineMessageRouter` 为结果主路径 | `AiResultHandler` 仅保留废弃兼容，禁止新逻辑写入 |
| 回归 | `tools/e2e-aigc-regression.ps1` | 新 AI 或新能力必须增加 P0 烟测条 |

---

## 6. 与界面功能的映射（第一张截图）

| UI 按钮 | 关联模块 | 一致性要点 |
|---------|-----------|------------|
| 创建新对话 | `isNewChat` / `currentChatId` | 与 `sessionId` 轮次分离，避免历史恢复污染 |
| 历史记录 | `getChatHistory` + 前端 `loadHistoryItem` | Mapper `aiName` 筛选须全覆盖（见数据流审计 P0） |
| 生成输出物 | `/aigc/output/generate` | 依赖会话数据完整性 |
| 导出 Markdown | `/aigc/output/exportMarkdown/{sessionId}` | Blob 与 JSON 错误区分 |
| 推送 Webhook | `/aigc/output/pushWebhook` | 与操作日志、重试策略一致 |

---

## 7. 建议落地顺序（与数据流审计协同）

1. **OpenAPI 文案与产品一致**（本迭代已改配置类，后续补 `@Tag`）。  
2. **getChatHistory AI 筛选补全**（数据流审计 P0）。  
3. **AiSessionStateManager 接入**（数据流审计 P0）。  
4. **产出物生成前校验** `results` 完整性（本节 §2）。  
5. **能力清单 CI 对照**（本节 §1）。

---

## 8. 相关文档索引

- [全局一致性对齐说明](../全局一致性对齐说明.md)  
- [AIGC 对话数据流审计与修复建议](../运行维护/AIGC对话数据流审计与修复建议.md)  
- [aigc-test-matrix.md](../aigc-test-matrix.md)  
- `docs/功能说明/engine/WebSocket通信完整指南.md`
