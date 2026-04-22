# Skill → 企微工作流：功能导向可评测演化路线图

> 用途：对齐产品/工程优先级，用**可重复指标**判断是否进入下一阶段；与 `tools/run-evolution-rounds.mjs`、`granule` 沉淀、Engine 自动化能力对齐。

---

## 1. 北极星（功能结果，非技术指标）

**在可控成本下，让「上传 Skill ZIP → 得到可在企微侧持续维护的工作流草稿」整条链可预期：**  
解析可解释、画布可录入、等价可验收、问题可回溯、改进可迭代。

---

## 1.5 Wave1 收口（更高维门禁：未通过不得进入 Wave2）

**语义**：Wave2 聚焦执行分层（Engine `openPanel`、真实 `EXPORT`/enterprise 语义线）；在此之前必须先证明「转换器双轨稳定 + MVP 全链验收管线可跑」。这不是替代 R2/R3 晋级表，而是**波次切换**的硬门槛。

| 门禁 ID | 需要什么 | 合格线（量化） | 命令 |
|---------|----------|----------------|------|
| **G-preview** | Admin、`skill-mvp-golden.zip`、`fbs-bookwriter-workbuddy-latest.zip` | 各 **5 轮** `--preview-only`，`previewOkRate=1`；golden：`draftEdgeUniqueValues=[4]`、`sceneEdgeUniqueValues=[2]`、`nodeBuildSource=sceneFlow`；BookWriter：`[10]`、`[0]`、`stageFallback` | `node tools/wave1-exit-gate.mjs --preview-only` |
| **G-import** | 同上 + Engine（8081 + WS） | MVP golden **1 轮**全链，`SOURCE_EXPORT_MODE=draft`，`importOkRate=1`；存在 `verifyReport` 且**非** skipped（脚本将「缺省 `skipped` 字段」视为已执行验收） | `node tools/wave1-exit-gate.mjs` |

**退出码**：`0` 通过；`1` 断言失败；`2` 缺 BookWriter zip；`3` Engine 不可用（仅 G-import）；`5` 需企微扫码登录（叶子 `TASK_ERROR`/未登录）；`6` G-import HTTP 等待超时（可调环境变量 **`WAVE1_IMPORT_TIMEOUT`**；Wave1 脚本默认向 Admin 请求 **`420` 秒**，避免 Playwright 录入被误判为超时）。

**HTTP 语义**：Admin `/ws/engine/request` 可能 **`success=true`** 同时 **`data.success=false`**（任务未登录、重复提交、业务失败等）；跑批 metrics 已在 `tools/lib/mvp-evolution-runner.mjs` 将此种情况记为 **`importHttpOk=false`**（勿只看 HTTP 200）。

**便捷**：`powershell -File tools/wave1-exit-gate.ps1`（可选参数同上转发给 node）。

**无企微登录会话时**（仅验 R0/R1 双轨结构、不跑 G-import / L-rel）：`node tools/wave1-exit-gate.mjs --preview-only` + `powershell -File tools/full-regression.ps1 -Tier smoke -SkipRestart`（或本机先 `QYWEIXIN_SCAN_LOGIN` 后再跑 `full-regression` **standard** 全链）。

---

### 1.6 Wave2 第一波落地（Wave1 完成后执行）

**目标**：在不动 Wave1 门禁语义的前提下，把 **R2 录入质量**（`fill.addNodes`）与 **R3 观测**变成**可归档指标**，便于下一波专攻 `openPanel`/真实 EXPORT。

| 动作 | 命令 | 产出 |
|------|------|------|
| IMPORT 多轮追踪 | `node tools/wave2-import-track.mjs [--rounds 5] [--gate-failed-max N] [--require-verify-pass]` | `tools/out/wave2-import-track-latest.json`（含 `openPanelPhaseFailsTotal`、`verifyPassRounds` 等） |
| 预检 + 追踪一条龙 | `powershell -File tools/wave2-entry.ps1`（`-RequireVerifyPass` 收紧 R3；`-SkipImportTrack` 仅 smoke） | 同上 + 控制台 `analysis` |

**R2 实测基线（快照）**：曾因 HTML 锚点缺失 **`meanAddNodesFailed≈2`、`openPanelPhaseFails≈6/3 轮`**；当前主干在 **`[data-node-id]` 竖向间隙中点**优先命中连线层后，Wave2 追踪可出现 **`meanAddNodesFailed=0`、`openPanelPhaseFailsTotal=0`**（仍以本机 `wave2-import-track` 为准）。待续：**`verifyPass`**、真实 EXPORT/R3。

**观测（Wave2+）**：`phase=openPanel` 时 `perNodeLog` 可含 **`failureScreenshotUrl`**（失败瞬间整页）与 **`openPanelEnvironment`**（`dataNodeCount` / `roughAddTextHits` 等），与响应根级 **`screenshotUrl`**（终态）对照，用于判断入口在边/层叠/视口哪一类问题。

**R3 草稿验收（IMPORT + draft 对照）**：在录入全绿前提下，`verifyReport.compareMode=draftImportSemantic` + 导出侧正确推断「结束」菜单后，`wave2-import-track` 可出现 **`verifyPassRounds` 等于轮次**（与 `tools/out/evolution-latest.json` 中 `verifyPassCount` 一致）。

---

## 2. 阶段总览（每阶段必须有「门禁」才能晋级）

| 阶段 | 功能导向 | 晋级门禁（全部满足才可进下一阶段） |
|------|-----------|-------------------------------------|
| **R0 基线** | 环境与黄金样本可复现 | 见 §3.1 |
| **R1 结构正确** | IR / `workflowDraft` 图结构可信 | 见 §3.2 |
| **R2 录入可用** | Engine 能在编辑器中落实关键节点类型 | 见 §3.3 |
| **R3 等价可证** | 导入结果与参考导出可比并结论明确 | 见 §3.4 |
| **R4 闭环治理** | 失败可分类、策略可版本化、回滚可执行 | 见 §3.5 |

---

## 3. 分阶段：目标、评测项、验收动作

### 3.1 R0 基线（环境与复现）

| 维度 | 评测项 | 合格线 | 验收动作 |
|------|--------|--------|----------|
| 服务可用 | Admin `8080`、Engine `8081`、WS 注册 | `curl` 根路径 200；`ENGINE_OFFLINE` 为 0 | 运维检查 / 脚本探活 |
| 黄金包 | `docs/workflows/skill-mvp-golden` 可打包预览 | `pack-skill-mvp-golden.ps1` 产出 zip；预览 `code=200` | CI 或本地脚本 |
| 批量探针 | 多轮跑批不落盘爆炸 | `node tools/run-evolution-rounds.mjs --rounds 3` 退出 0、`previewOkRate=1` | 定期或 PR 前 |

**R0 出口**：上述表全绿；`tools/out/evolution-latest.json` 可被提交为基线快照（可选）。

---

### 3.2 R1 结构正确（解析与 Draft 图）

| 维度 | 评测项 | 合格线 | 验收动作 |
|------|--------|--------|----------|
| Scene 边 | `sceneFlow.stats.edgeCount` 与 pack 定义一致 | golden 场景 `edgeCount≥2`（当前约定 2）且与 `mvp-basic.json` 一致 | 断言 `evolution-latest` 中 `sceneEdgeUniqueValues` 单一且等于预期 |
| Draft 边 | `workflowDraft.edges` 覆盖 `n_start→…→n_end` | `draftEdgeCount=4`（golden 三线业务+起止）；无断链 | 同上，检查 `draftEdgeUniqueValues` |
| 节点来源 | 建图来源可追溯 | `buildDebug.nodeBuildSource` 为 `sceneFlow`（golden 不走回退） | 字段断言 |
| 稳定性 | 多轮无结构漂移 | 连续 ≥5 轮 `structureStability` 仅单值 | `run-evolution-rounds --rounds 5 --preview-only` |

> 注（BookWriter 专项）：`draftEdgeCount=4` 仅适用于 MVP golden，不应套用到 `fbs-bookwriter`。  
> 对 BookWriter 采用「单次标定值 + 多轮零漂移」门禁（例如当前阶段标定为 `draftEdge=10`、`sceneEdge=0`，且 `nodeBuildSource=stageFallback`）。

**R1 出口**：结构类指标连续 5 轮 100% 一致；任意回退至 `stageFallback/defaultFallback` 须单独立项排期，不混入 R2。

---

### 3.3 R2 录入可用（Playwright / 映射）

| 维度 | 评测项 | 合格线 | 验收动作 |
|------|--------|--------|----------|
| HTTP 成功 | Admin → Engine 请求成功 | `importOkRate=1`（无 `ENGINE_OFFLINE`/解析错误） | `run-evolution-rounds --rounds 3` |
| 画布操作 | `fill.addNodes` 能添加业务节点 | `partial` 可接受作中间态；**晋级 R3 前**需 `failed` 数在约定类型上下降（见下） | 读 `evolution-r*-import.json` |
| 功能分解 | 按 draft 类型分别统计 | input / process / output 各自「打开面板」成功率分项记录 | 在报告中增加按 `draftType` 的计数（可在后续迭代脚本化） |

**当前基线事实**（便于定目标）：golden 上曾出现「仅第一节点录入、大模型/输出面板未找到入口」——R2 的量化目标建议为：

- **P2-1**：`addNodesFailedCount` 从 2 降为 1（至少多一类节点可稳定添加）  
- **P2-2**：`fillStatus` 在 golden 上达到 `complete` 或等价（与产品共定）

**R2 出口**：书面记录「面板失败」根因签名（DOM/选择器/权限/环境），并至少完成 **P2-1**。

---

### 3.4 R3 等价可证（验收与 Golden 扩展）

| 维度 | 评测项 | 合格线 | 验收动作 |
|------|--------|--------|----------|
| 验收启用 | `verifyReport` 不长期 `skipped` | golden 路径上 `verifySkipped=false`（或明确提供 `sourceExportRows` 后通过） | 对照 Engine 契约改 payload |
| 对比可信 | `perIndex` 对齐策略可解释 | 文档化：按节点序 / 导出行序；标签与菜单匹配规则 | 评审 + 样本 |
| 黄金集 | 不只一个 skill | 至少 2 套 scene-pack（简单 + 含分支）均过 R1 | 新增 `skill-mvp-golden-2` 类目录 |

**R3 出口**：在**带 `sourceExportRows`（或 bundle）**的前提下，`verifyPass=true` 在 golden 主路径上可复现；或产品明确签字采用「弱化标签、只比结构/菜单」的替代门禁并写死到配置。

---

### 3.5 R4 闭环治理（迭代与风险）

| 维度 | 评测项 | 合格线 | 验收动作 |
|------|--------|--------|----------|
| 记忆体 | granule / clone learning 与版本关联 | 每次映射或选择器变更有版本号或文件时间线 | 仓库/配置审查 |
| 可归因 | 失败可聚类 | 每周从 `qyweixin-automation.jsonl` / import JSON 导出 Top3 失败签名 | **`node tools/evolution-failure-cluster.mjs`**；留痕 **`tools/archive-evolution-run.ps1`** |
| 部署 | 新 business 必进 Admin 包 | 文档化 `mvn -pl WxFbsir-admin -am package` + 重启；防 404 旧资源 | 发布 checklist |

**R4 出口**：变更可回滚；失败可解释；路线图每季度回顾一次门禁是否仍有效。

---

## 4. 路线图时间建议（可按团队切片）

| 切片 | 重点 | 可交付物 |
|------|------|----------|
| **H1（当前）** | R0+R1 稳定 + R2 根因 | 结构不断链；面板失败 RCA 文档；跑批脚本进常用命令 |
| **H2** | R2 指标达成 + R3 最小等价 | golden 验收可跑通；第二套 scene（已落地 **`docs/workflows/skill-mvp-golden-h2`** / `mvp-branch.json`，门禁 **`node tools/h2-exit-gate.mjs`**） |
| **H3** | R4 治理 + 多租户/多样本 | 聚类报表、策略版本表、可选 UI 展示 `evolution-latest` |

---

## 5. 与仓库内能力映射

| 路线图阶段 | 主要承载 |
|------------|-----------|
| R0–R1 | `SkillMigrationPreviewService`、`tools/pack-skill-mvp-golden.ps1`、`run-evolution-rounds.mjs` |
| R2 | Engine 企微 Playwright、`qyweixin-workflow-editor-node-mapping.json`、`fill.addNodes` |
| R3 | `QYWEIXIN_WORKFLOW_IMPORT_DRAFT` payload、`verifyReport`、`sourceExportRows` |
| R4 | granule catalog、clone learning、自动化日志 |

---

## 6. Wave2 执行分层（不改变 R0–R4 目标，只优化落地方式）

**前置**：必须先通过 **§1.5 Wave1 收口**（`wave1-exit-gate` 绿）。

> 与福帮手写书等 **大型 Workbuddy 包** 对接时，易遇：解析回退组合、进程与 JAR 占用、验收缺参照行、画布 `openPanel` 与菜单映射混谈。本节将**执行节奏**与**门禁分层**写死，避免拖垮日常研发；R0–R4 的语义仍以上文 §3 为准。

### 6.1 双轨回归

| 轨道 | 建议频率 | 使用包 | 覆盖 |
|------|----------|--------|------|
| **R-PR** | 每次 PR / 日更 | `skill-mvp-golden`（MVP 小图） | R1 快检、转换器不回归 |
| **R-Week** | 周 / 合主分支 | `fbs-bookwriter-workbuddy-latest`（真包） | 大体积 + 分支语义 |
| **R-Heavy** | 夜间 / 发布前 | 真包 + 全量 `IMPORT_DRAFT` | R2、R3 |

入口示例：`tools/run-governance-smoke.ps1`（R-PR + R-Week 的 preview-only 最小集）；可先 `preflight-evolution.ps1`，或一条 `tools/wave2-smoke.ps1`（预检 + governance smoke）。**R-Heavy（MVP golden 全链 R2+R3）**：`powershell -File tools/wave2-entry.ps1 -RequireVerifyPass`（或同参调用 `wave2-import-track.mjs --require-verify-pass`）。全量大包录入另走发布清单并注意企微「工作流」条数配额。

### 6.2 跑批前预检（R0 子项）

- **Admin 8080** 可访问；若需重打 `WxFbsir-admin.jar`，先结束占用该 JAR 的进程。  
- **Engine 8081** 独占；**HTTP 健康 ≠ WebSocket 已注册**；`IMPORT` 前以无 `ENGINE_OFFLINE` 为准。  
- 清理易串味环境变量：`EVOLUTION_ZIP`、`SOURCE_EXPORT_MODE`、`SOURCE_EXPORT_ROWS_FILE`（全链与 smoke 不要混用上一轮的实验态）。  
- **一键脚本**：`powershell -File tools/preflight-evolution.ps1`（可选 `-ClearEvolutionEnv` 清空会话内上述变量；`-StrictImport` 要求端口 + Engine `/api/monitor/health`=200）。  
- **跑批后清数（工作流条数/额度）**：`QYWEIXIN_WORKFLOW_IMPORT_DRAFT` 默认 **`workflowLifecycle=TEMP_TEST`**（跑批脚本通过 `QYWX_WORKFLOW_LIFECYCLE` 传入），录入结束后 Engine 会在列表尝试**删除该行**（失败不否定录入结果，见响应 `temporaryWorkflowCleanup`）；正式编排请显式 **`workflowLifecycle=PRODUCTION`**。仍可辅以 `cleanup-unnamed-workflows.ps1` / 手工删除遗留行。

### 6.3 R1 的两条基线（福帮手写书专项，不取消「零漂移」）

- **线 A（稳态）**：`buildDebug.nodeBuildSource=stageFallback` 时，以「标定边/步数 + 多轮 `structureStability` 单值」为 R1 合格线（与 §3.2 脚注一致）。  
- **线 B（语义）**：在**不降低**线 A 门禁的前提下，单独排期让 `scene-packs/enterprise.json` 等驱动 `sceneFlow` 与 `workflowDraft` 更一致（见 `docs/workflows/skill-fbs-bookwriter/README.md` 基线表）。两线可并行记录，避免“为追语义放掉结构”。

### 6.4 R2 失败先分类，再动代码

| 类 | 信号 | 优先改什么 |
|----|------|-------------|
| **P0 壳层** | `perNodeLog` 中 `phase=openPanel` | Engine / Playwright：「添加指定操作」入口定位 |
| **P1 映射** | 已尝试菜单候选仍不匹配 | `qyweixin-workflow-editor-node-mapping.json` |
| **P2 环境** | 登录态、权限、跳转异常 | 账号与运行环境 |

### 6.5 R3 验收双层

| 层 | 数据 | 用途 |
|----|------|------|
| **L-dev** | `SOURCE_EXPORT_MODE=draft` 或最小手工行集 | 保证 `verifyReport` **可执行**、便于开发定位 |
| **L-rel** | 真实 `EXPORT_FULL` / `payload.sourceExportRows` | **发布门槛**；不得长期以 draft 伪对照代替 |

**L-rel 门禁（仓库夹具路径）**：`node tools/l-rel-exit-gate.mjs`（一轮 IMPORT，`SOURCE_EXPORT_ROWS_FILE=tools/fixtures/mvp-golden-source-export-rows.json`，要求 `verifyPass=true`）。夹具与 MVP golden 三业务节点对齐；更新 golden 解析结果或企微导出格式时需同步刷新该 JSON，或以真实 `EXPORT_FULL` 导出行覆盖同结构。**便捷**：`powershell -File tools/l-rel-entry.ps1`（预检含 `-StrictImport`）。

### 6.6 R4 快照（观测与归档，不改变 R0–R4 语义）

| 动作 | 命令 | 产出 |
|------|------|------|
| 失败签名聚类 | `node tools/evolution-failure-cluster.mjs` | `tools/out/evolution-failure-cluster-latest.json`（含 `top10`） |
| 跑批归档 | `powershell -File tools/archive-evolution-run.ps1` | `tools/out/archive/<时间戳>/` |
| 一键（聚类 + 归档） | `powershell -File tools/r4-evolution-snapshot.ps1` | 同上 |

> 聚类默认只统计 `evolution-latest.json` 与 `wave2-import-track-latest.json` 中的**较大**轮次序号对应的 `evolution-r*-import.json`，避免目录里陈旧跑批残留干扰 Top10；跑完 L-rel 单独覆盖 latest 后仍可依 wave2 追踪档对齐轮次。

### 6.7.1 一键全量回归（发布前 / CI 候选）

| Tier | 命令 | 包含 |
|------|------|------|
| **smoke** | `powershell -File tools/full-regression.ps1 -Tier smoke` | 预检 + `wave2-smoke`（双包 preview-only） |
| **standard**（默认） | `powershell -File tools/full-regression.ps1` | 默认 **重启** + 等待 55s + 预检 → **Wave1 + Wave2（draft IMPORT 追踪）+ L-rel（冻结行集 R3）+ H2 预览**（不打 H2 IMPORT）。Wave2 不要求 `verifyPass`（draft 语义对照可能与 scene 别名不完全一致）；**`verifyPass=true` 以 L-rel 为准**。 |
| **heavy** | `powershell -File tools/full-regression.ps1 -Tier heavy` | standard 基础上 + **H2 IMPORT（draft 2 轮）+ H2 L-rel（R3）** +（若存在 zip）**L-rel-BookWriter**；耗时长，需企微额度与 Engine 稳定 |

可选 `-SkipRestart`、`-IncludeR4Snapshot`（heavy/standard 末行聚类+归档）。

**CI**：`.github/workflows/evolution-gates.yml`（`workflow_dispatch`，**`runs-on: self-hosted`**）。托管 Runner 无本地 Admin/Engine/企微会话，不适用；夜间 heavy 建议在专用 Windows 自托管机上执行。

### 6.7 H2 第二套 scene-pack（预览门禁）

与主力 **MVP golden（`mvp-basic`）** 并行：仓库内 **`docs/workflows/skill-mvp-golden-h2`**，`scene-packs/mvp-branch.json` 为**与同构 MVP 三线性流**（`draftEdge=4`、`sceneEdge=2`），独立 **plugin-id** 与 zip，便于第二套 Golden 门禁；与 `mvp-basic.json` **内容区分**（第二套样本）且不打进默认 `skill-mvp-golden.zip`。含分支的大图可待对比器对齐后再单独加场景。

| 动作 | 命令 | 合格线（当前标定） |
|------|------|-------------------|
| 打包 | `powershell -File tools/pack-skill-mvp-golden-h2.ps1` | `tools/out/skill-mvp-golden-h2.zip` |
| H2 预览门禁 | `node tools/h2-exit-gate.mjs` | `previewOkRate=1`；`draftEdgeUniqueValues=[4]`、`sceneEdgeUniqueValues=[2]`、`nodeBuildSource=sceneFlow` |
| H2 IMPORT 追踪（L-dev draft） | `node tools/h2-import-track.mjs [--require-verify-pass]` | `tools/out/h2-import-track-latest.json` |
| H2 **L-rel**（冻结三行） | `node tools/h2-l-rel-exit-gate.mjs` | `verifyPass=true`，夹具 `tools/fixtures/mvp-h2-source-export-rows.json` |
| 预览一条龙 | `powershell -File tools/h2-entry.ps1` | 预检 → 打包 → 预览门禁 |
| IMPORT 一条龙 | `powershell -File tools/h2-import-entry.ps1 -RequireVerifyPass` | 预检 → 打包 → IMPORT 追踪（默认 `IMPORT_TIMEOUT=420`、轮间冷却 20s，减轻 DUPLICATE_REQUEST） |
| H2 L-rel 一条龙 | `powershell -File tools/h2-l-rel-entry.ps1` | 预检 → 打包 → H2 L-rel |

---

- **晋级评审**：阶段出口由负责人对照 §3 表格逐条打勾；Wave2 执行分层（§6）作为**落地约束**，不改变 §3 晋级语义。  
- **指标变更**：调整合格线须更新本文档版本并注明日期。  
- **单一事实源**：评测以 `tools/out/evolution-latest.json` + 对应轮次 `evolution-r*-import.json` 为准（可 CI 归档）。

---

*文档版本：2.2 | §6.7.1：增补 `.github/workflows/evolution-gates.yml`（自托管 CI）。*
