---
name: "fbs-bookwriter"
version: "2.1.2"
plugin-id: "skill.fbs.bookwriter"
description: "福帮手写书 · 长文生成与交付（迁移轨道脚手架）"
---

# FBS-BookWriter（迁移轨道）

与 OpenClaw 发布的 **fbs-bookwriter** 对齐：长文写作、改写扩写、质检与导出交付。

## 主流程（longform-main）

1. **接收题材与目标**：题材 `genre`、大纲或章节目标 `outlineTarget`。
2. **大纲生成**：产出可执行章节结构。
3. **章节写作**：基于大纲生成正文草稿。
4. **质量门禁**：校对与修订建议。
5. **导出交付**：合并为交付目录（MD/HTML 等）。

## Steps（摘要）

- Input：接收写作参数与用户补充说明。
- Process：大纲 → 正文多轮生成。
- Check：质量门禁（可降级为提示）。
- Output：导出交付物并返回 `traceId`。
