# Skill-Workflow MVP Golden Baseline

这是一套最小等效样本，用于先打通：

- Skill 解析
- workflowDraft 生成
- IMPORT_DRAFT 导入
- 验收 compare

## 语义目标

输入问题 -> 变量接收 -> 大模型回答 -> 结束输出 `response/traceId`

## 文件说明

- `SKILL.md`: 样本 skill 描述
- `_plugin_meta.json`: 样本元信息
- `workbuddy/channel-manifest.json`: workbuddy 渠道声明
- `codebuddy/channel-manifest.json`: codebuddy 渠道声明
- `scene-packs/registry.json`: scene pack 注册
- `scene-packs/mvp-basic.json`: 核心流程（steps + edges）
- `scene-packs/official-schema.json`: 输入列声明
- `workflowDraft.expected.json`: 期望的 workflowDraft 参考（用于人工比对）

## 使用方式

1. 运行 `tools/pack-skill-mvp-golden.ps1` 生成 zip。
2. 在企微工作流实验页上传 zip，点击“生成迁移预览”。
3. 确认返回 `workflowDraft.buildDebug.finalBusinessNodeCount >= 3`。
4. 点击“一键录入草稿”，再看 `verifyReport.pass`。

