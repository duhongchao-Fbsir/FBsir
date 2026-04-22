# BookWriter IMPORT RCA（2026-04-22）

## 背景

- 目标：`fbs-bookwriter-v213-workbuddy.zip` 达到 S3（可录入）并推进 S4（可验收）。
- 入口：`QYWEIXIN_WORKFLOW_IMPORT_DRAFT`（Admin -> Engine）。
- 观测样本：`tools/out/evolution-r1-import.json`、`evolution-r2-import.json`（同轮跑批）。

## 现象

1. HTTP 层成功：`importOkRate=1`，`ENGINE_OFFLINE=0`。
2. 画布层部分成功：`fill.addNodes.status=partial`，`attempted=9`，`failed=8`。
3. 失败签名高度一致：`phase=openPanel` 且 `reason=未找到「添加指定操作/添加运营操作」等入口`。
4. 验收层默认跳过：`verifyReport.skipped=true`，原因为缺少 `sourceExportRows`。

## 根因拆解

### A. 转换器侧（已处理）

- 旧逻辑会在 `sceneFlow.steps < 3` 时回退 `SKILL.md`，导致大量说明文本被当作步骤（噪声节点）。
- 已收敛：对大文档跳过 markdown fallback，并优先使用 `stageFallback` 生成业务节点。
- 结果：`attempted/failed` 由 `20/19` 收敛到 `9/8`。

### B. 工作流录入侧（当前主要阻塞）

- 失败集中在「打开添加节点面板」步骤，而非节点菜单映射本身。
- 说明核心阻塞是 UI 可交互入口识别/触发链路，而不是单纯 `preferredMenu` 文案不匹配。

### C. 验收侧（已打通最小可执行）

- 默认缺 `sourceExportRows` 会导致 `verifySkipped=true`。
- 通过 `SOURCE_EXPORT_MODE=draft` 可生成最小对照，`verifySkipped=false`，可输出 `perIndex` 结果。
- 当前仍 `pass=false`，但已具备可解释对比证据。

## 结论

1. **P2-1 达成（阶段性）**：失败量已明显下降（`19 -> 8`），但仍未到可发布状态。
2. S3 仍为 `partial`：当前最大瓶颈在企微编辑器「添加操作」入口的稳定定位。
3. S4 已具备执行能力：建议后续用真实 `EXPORT_FULL` 行集替换 draft 伪对照。

## 下一步（按优先级）

1. Engine 侧优先修复 openPanel 入口策略（多选择器 + 兜底交互顺序）。
2. 在跑批报告中按 `draftType` 长期追踪失败分布（当前几乎全是 `process`）。
3. 接入真实 `sourceExportRows`（同 workflow 的导出行），作为发布前验收依据。

### openPanel 聚类（与映射 JSON 解耦）

跑批产物：`node tools/evolution-failure-cluster.mjs` → `tools/out/evolution-failure-cluster-latest.json` 中的 **`openPanelAnalysis`**（按 `reason` / `draftType` / 签名 TopN）。用于评审 Playwright 入口策略迭代，而非优先堆映射表。
