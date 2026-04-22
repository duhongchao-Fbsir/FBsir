---
name: "skill-mvp-golden"
version: "1.0.0"
plugin-id: "skill.mvp.golden"
description: "MVP baseline for skill-to-workflow equivalence"
---

# Skill MVP Golden

## Flow

1. Collect user question as `question`.
2. Run LLM generation to produce `response`.
3. Emit `traceId` for debugging.

## Steps

- Input: receive `question`.
- Process: generate `response` from model.
- Output: return `response` and `traceId`.

