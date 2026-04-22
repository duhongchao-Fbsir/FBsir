# 交付清单：AIGC 多智能体改造与全局一致性治理（会话综合）

> 本文档综合多轮交流与落地结果，便于交接、验收与后续迭代。  
> 整理日期：2026-04-18  

---

## 一、目标与范围（我们达成了什么）

| 主题 | 说明 |
|------|------|
| **多智能体并行** | 以 DeepSeek 为基线，统一 `aiType` / `chatId` / `sessionId` / 平台 `platformChatId` 契约；弱化默认 DeepSeek；历史恢复禁止误用通用 `nestedData.chatId` 回填 DeepSeek。 |
| **登录与持久化** | Engine 侧元宝等扫码成功分支补充登录状态持久化（`saveLoginState`），避免「当次可用但未落库」。 |
| **前端** | `engineMessageNormalizer.js`；`aigc/index.vue`（发送、进度、历史、复制、多 AI 草稿）；`drafts/index.vue`（复制时 AI 标识归一）；登录管理器与引擎配置对齐。 |
| **草稿与 SQL** | `AigcMapper.xml`：草稿 AI 列表归一 `aiType`；`getDraftContent` 多别名匹配。 |
| **可观测与门禁** | `ClientMessageRouter` / `EngineMessageRouter` 日志字段（`platformChatId`、`stage` 等）；`AiResultHandler` 标记废弃，唯一写库路径收口至 `EngineMessageRouter`；E2E 脚本 JSON 字段扩展。 |
| **仓库与文档** | 主仓库对齐 **GitHub** [duhongchao-Fbsir/FBsir](https://github.com/duhongchao-Fbsir/FBsir)，默认分支 **`fbsir`**；全局替换过时 `U3W-AI` 克隆路径；`.gitignore` 忽略 `tools/out/`、`build-tools/`。 |
| **全局洞察（延伸）** | 能力三层真源、产出物闭环、操作日志 vs WebSocket 排障、OpenAPI 与复用规范：见 [AI助手关联域全局洞察与一致性升级.md](架构与规范/AI助手关联域全局洞察与一致性升级.md)。 |

---

## 二、Git / 提交与远程

| 项 | 状态 |
|----|------|
| **合并策略** | 采用 **单分支单 PR / 单提交 squash** 交付（`feat(aigc): multi-agent...`），另含 `chore(tools): pr-submit...`、`docs: align GitHub...` 等后续提交。 |
| **远程** | `origin` → `https://github.com/duhongchao-Fbsir/FBsir.git`，**已推送** `fbsir`。 |
| **待办** | 本地有新提交时执行 `git push origin fbsir`；若重写历史需 `--force-with-lease`。 |

---

## 三、自动化测试与 PR 准备（曾执行）

| 步骤 | 命令 / 说明 |
|------|----------------|
| Maven 全模块测试 | 根目录 `mvn test`（可用 `build-tools/.../mvn.cmd`） |
| Engine 测试 | `WxFbsir-engine` 模块 `mvn test` |

> **主副节点**：上表两条为**两条独立 Maven 工程**；根 `pom` **不**聚合 `WxFbsir-engine`（既定设计）。全量 Java 验证须分别执行，**禁止**在文档或 PR 中写成「根 `mvn test` 已包含 Engine」。审计清单见 [全局一致性对齐说明.md §1.4](全局一致性对齐说明.md)。
| 前端 | `WxFbsir-ui`：`npm run build:prod`、`npm run test:aigc-mapper` |
| E2E | `tools/e2e-aigc-regression.ps1`：P0 / P1（需本机 Admin+Engine+各站登录） |
| PR 辅助 | [tools/pr-submit-aigc.md](../tools/pr-submit-aigc.md)（标题/正文模板、Reviewer 核对项） |

---

## 四、数据流审计：已知风险与修复状态

详见 [运行维护/AIGC对话数据流审计与修复建议.md](运行维护/AIGC对话数据流审计与修复建议.md)。

| 优先级 | 问题 | 状态 |
|--------|------|------|
| **P0** | `AiSessionStateManager` 未接入 | **已修复**：`ClientMessageRouter.ensureSession` / `markAiStarted` |
| **P0** | `getChatHistory` 按 `aiName` 筛选不全 | **已修复**：Mapper 补分支 + `otherwise` |
| **P1** | 多 AI 并行预保存竞态 | **已修复**：`saveInitialRequest` 同步 + 已存在则跳过 |
| **P2** | `engineMessageNormalizer` 默认 `unknown` | **已修复**：`inferAiTypeFromMessageType` |
| **—** | 废弃 `AiResultHandler` 双写库 | **已删除** 占位类（无引用） |
| **—** | 产出物 `OutputArtifactContentResolver`（含 `results` 与回退）、草稿 `user_id`、前端 `test:aigc-mapper` / `run-ci-aigc-checks.ps1` | **已落地**（持续维护） |

---

## 五、文档索引（按主题）

| 文档 | 用途 |
|------|------|
| [全局一致性对齐说明.md](全局一致性对齐说明.md) | 品牌、模块、版本、配置键、源码托管、**§2.3 AI 关联域** |
| [架构与规范/AI助手关联域全局洞察与一致性升级.md](架构与规范/AI助手关联域全局洞察与一致性升级.md) | 能力对齐、产出物、日志、OpenAPI、复用规范 |
| [运行维护/AIGC对话数据流审计与修复建议.md](运行维护/AIGC对话数据流审计与修复建议.md) | 输入→持久化→Engine→回写 全链路问题与修复顺序 |
| [aigc-test-matrix.md](aigc-test-matrix.md) | AIGC 四平台上架矩阵；`gitee-oauth`（OAuth 业务）与已下架的 Gitee AI Chat（浏览器对话）区分 |
| [docs/README.md](README.md) | 文档中心导航 |
| [../tools/pr-submit-aigc.md](../tools/pr-submit-aigc.md) | PR 描述与推送说明 |
| [../sql/MIGRATION_ORDER.txt](../sql/MIGRATION_ORDER.txt) | 数据库迁移顺序（若涉及） |

---

## 六、关键代码路径（速查）

| 层级 | 路径 |
|------|------|
| 前端归一 / AIGC 页 | `WxFbsir-ui/src/utils/engineMessageNormalizer.js`、`views/business/content/aigc/index.vue`、`drafts/index.vue`、`config/engineConfig.js`、`utils/aiCapabilityMapper.js` |
| Admin 路由 | `WxFbsir-business/.../ClientMessageRouter.java`、`EngineMessageRouter.java` |
| AIGC 服务 | `WxFbsir-business/.../AigcServiceImpl.java`、`mapper/aigc/AigcMapper.xml` |
| Engine 元宝等 | `WxFbsir-engine/.../YuanbaoController.java` 等 |
| OpenAPI | `WxFbsir-admin/.../SwaggerConfig.java` |
| E2E | `tools/e2e-aigc-regression.ps1`、`e2e-aigc-smoke.ps1` |

---

## 七、后续建议（可选）

1. `AigcController` 输出物接口在 OpenAPI 中补充分组说明（依赖 Admin 侧 SpringDoc 扫描范围）。  
2. 需要时让 AIGC 页可选拉取 `GET /aigc/ai/list` 与 `ENGINE_CONFIGS` 做差异提示（当前以 `engineConfig.js` 为展示真源）。  

---

## 八、约束与约定（沟通过程中确认）

- **不随意修改** 用户指定的「计划类」文件（若单独约定）。  
- 大范围「代码整理」以 **与契约/审计直接相关** 为界，避免无测试覆盖的大规模格式化。  
- 回归产物目录 **`tools/out/`** 不提交版本库。  

---

*本清单随仓库迭代更新；技术细节以代码与专题文档为准。*
