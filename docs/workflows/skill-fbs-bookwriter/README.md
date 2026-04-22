# 福帮手写书 Skill（企微工作流迁移轨道）

## 权威分发包（本机构建产物）

当前用于**完全移植**验证的 Workbuddy ZIP（v2.1.3）：

`D:\210\FBS-BookWriter\dist\fbs-bookwriter-v213-workbuddy.zip`

ZIP 内根目录为 `fbs-bookwriter/`（含 `scene-packs/enterprise.json`、`registry.json`、大量 `references/`）。  
迁移预览服务已支持**带根目录前缀**的 `scene-packs/*.json`，可正确抽取该包内的场景流。

一键同步到固定文件名供脚本使用：

```powershell
.\tools\sync-fbs-bookwriter-dist.ps1
# → tools/out/fbs-bookwriter-workbuddy-latest.zip
```

## 仓库内脚手架（可选对照）

本目录下的 `scene-packs/longform-main.json` 等为**简化长文链路**模板，便于与 MVP golden 对标；与上游完整 spec 并行存在，不替代官方包。

上游 Git/OpenClaw：

- <https://github.com/duhongchao-Fbsir/fbs-bookwriter-openclaw>
- ClawHub：`fbs-bookwriter`

## 本轨道目标（小步快跑）

| 步 | 内容 | 可评测 |
|----|------|--------|
| 1 | 上传官方 `*-workbuddy.zip`，预览 IR / `workflowDraft` | `sceneFlow.stats`、`draftEdgeCount` |
| 2 | Engine `IMPORT_DRAFT` + 映射收敛 | `fill.addNodes`、`verifyReport` |
| 3 | 与路线图 R2/R3 对齐 | `run-evolution-rounds` |

脚手架打包：`.\tools\pack-skill-fbs-bookwriter.ps1` → `tools/out/skill-fbs-bookwriter.zip`。

## v2.1.3 基线（2026-04-22）

本基线使用官方包 `fbs-bookwriter-v213-workbuddy.zip`，通过：

```powershell
.\tools\sync-fbs-bookwriter-dist.ps1
$env:EVOLUTION_ZIP='fbs-bookwriter-workbuddy-latest.zip'
node .\tools\run-evolution-rounds.mjs --rounds 5 --preview-only
```

### 结构基线（S2）

| 指标 | 结果 |
|------|------|
| `previewOkRate` | `1` |
| `sceneFlow.stats.stepCount` | `0`（转而走 `stageFallback`） |
| `sceneFlow.stats.edgeCount` | `0` |
| `workflowDraft.nodes` | `11`（start + 9 business + end） |
| `workflowDraft.edges` | `10` |
| `buildDebug.nodeBuildSource` | `stageFallback` |
| `structureStability.draftEdgeUniqueValues` | `[10]` |
| `structureStability.sceneEdgeUniqueValues` | `[0]` |

#### 线 A / 线 B（enterprise → sceneFlow）

- **线 A（stageFallback）**：当 `sceneFlow` 中可解析业务步骤不足时，用 IR `stages` 生成画布节点；上表为线 A 下的 R1 标定值，**F2 漂移门禁仍以「标定值 + 零漂移」为准**。
- **线 B（enterprise.json 驱动 sceneFlow）**：迁移服务现会解析 `scene-packs` 内 `stages[]` 元素为 `sceneFlow.steps`（此前整段跳过 `stages` 子树）。部署新版本后若 `sceneFlow.stats.stepCount` 大于 0 且 `nodeBuildSource` 为 `sceneFlow`，请重新跑 `--preview-only` 多轮并**单独记录** BookWriter 的边/步基线（勿与 MVP golden 数值混用）。
- **跑批预检**：`tools/preflight-evolution.ps1`；查看 8080/8081 占用进程可加 `-ShowPortOwners`（需 Windows `Get-NetTCPConnection`）。

### 录入/验收现状（S3/S4）

| 指标 | 结果 |
|------|------|
| `importOkRate` | `1` |
| `fill.addNodes.status` | `partial` |
| `addNodesAttempted / failed` | `9 / 8`（较旧噪声版本 `20 / 19` 有明显收敛） |
| `verifyReport`（默认） | `skipped=true`（缺 `sourceExportRows`） |
| `verifyReport`（`SOURCE_EXPORT_MODE=draft`） | `skipped=false`，可输出 `perIndex` 对比结论（当前 `pass=false`） |

> 说明：`SOURCE_EXPORT_MODE=draft` 用于打通 S4「可执行验收」链路；生产验收仍建议替换为真实 `EXPORT_FULL` 行集。

#### L-rel（冻结行集，非 draft）

- 夹具：`tools/fixtures/bookwriter-source-export-rows.json`（结构与 `mvp-golden-source-export-rows.json` 一致；应用**真实**企微导出覆盖时，保持与当前草稿业务节点数一致）。
- 门禁：`node tools/l-rel-bookwriter-exit-gate.mjs`（需已同步 `fbs-bookwriter-workbuddy-latest.zip`）。默认只要求 `verifySkipped=false`；发布强校验可设 `STRICT_BOOKWRITER_VERIFY=1`（且应用真实导出替换夹具）。
- **重型回归**：`tools/full-regression.ps1 -Tier heavy` 在存在上述 zip 时会顺带执行该门禁（缺 zip 则跳过）。
