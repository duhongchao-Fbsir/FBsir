# AIGC Full Regression Matrix

This document defines a risk-oriented test matrix for the **four browser-based AIGC platforms** currently on the shelf (aligned with `WxFbsir-ui/src/config/engineConfig.js`):

- deepseek
- doubao
- qianwen
- yuanbao

> **Removed from AIGC shelf (Engine handlers deleted; Admin still rejects legacy message types):** Gitee AI Chat (`GITEE_*` / `AI_GITEE_*`), Mita / 秘塔 (`MITA_*` / `AI_MITA_*`).  
> **Gitee OAuth / profile / analysis** remains a separate business module (`business/gitee/*`), not the same as `gitee-ai-chat`.

## 1) Release Gates

### P0 (must pass before merge/release)

- 4-platform full smoke query success rate (`RESULT_OK=4/4` for default `e2e-aigc-smoke` subset)
- Login detection correctness
- Payload integrity (`sessionId`, `chatId`, `query`, `userPrompt`)
- Result message integrity (`AI_TASK_RESULT` or `AI_TASK_ERROR`)
- Encoding sanity (`ascii`, `zh-cn`, `emoji`)

### P1 (daily regression)

- Reboot persistence for login state
- UI selector fallback paths
- Timeout boundary behavior
- Anti-noise login judgment (irrelevant hints/modals)
- Error observability (`ai`, `sessionId`, `stage`, `category`)

### P2 (weekly stability)

- Multi-run consistency (3+ consecutive full runs)
- Concurrency and pool pressure
- Long-run memory/process stability
- Failure classification trend

## 2) Platform x Risk Matrix

| Risk Dimension | DeepSeek | Doubao | Qianwen | Yuanbao |
|---|---|---|---|---|
| login check accuracy | required | required | required | required |
| login persistence after restart | required | required | required | required |
| anti-noise login judgment | required | required | required | required |
| payload field integrity | required | required | required | required |
| response extraction stability | required | required | required | required |
| timeout/degrade behavior | required | required | required | required |
| mode toggle behavior | optional | optional | — | — |
| upload passthrough behavior | optional | optional | optional | optional |

## 3) Test Case Catalog

### P0 Cases

- `TC-P0-01`: Full 4-platform smoke run, target `RESULT_OK=4/4` (see `tools/e2e-aigc-smoke.ps1`).
- `TC-P0-02`: Verify top-level and nested payload fields are consistent.
- `TC-P0-03`: Verify login manager all-green is reflected by `CHECK_LOGIN`.
- `TC-P0-04`: Verify noisy UI hints do not force false login failure.
- `TC-P0-05`: Verify Chinese and emoji prompts are stored/read without mojibake.
- `TC-P0-06`: Verify timeout returns correct error category and message.

### P1 Cases

- `TC-P1-01`: Rebuild/restart and re-check login persistence.
- `TC-P1-02`: Input/send selector fallback works under UI variants.
- `TC-P1-03`: WebSocket fragmented frame parsing remains stable.
- `TC-P1-04`: Failure logs include `ai + sessionId + stage + reason`.

### P2 Cases

- `TC-P2-01`: 3x consecutive full runs, compare pass rate variance.
- `TC-P2-02`: Concurrent execution under pool/queue pressure.
- `TC-P2-03`: Long-run (2h+) with process and memory sampling.
- `TC-P2-04`: Noise and network fault injection.

## 4) Execution Script

Use `tools/e2e-aigc-regression.ps1` if present in your branch, or `tools/e2e-aigc-smoke.ps1` for a smaller matrix:

- `-Tier P0` for release gate (when regression script supports tiers).
- Outputs:
  - plain logs: `tools/out/*.log`
  - structured json: `tools/out/*.json`
  - per-ai fields: `ai`, `sessionId`, `outcome`, `stage`, `errorCategory`, `aiTypeDetectedFrom`

## 5) Naming Boundary

- **`gitee-oauth` / `business/gitee`** — account binding, profile, usage reports, analysis; **not** AIGC browser chat.
- **Legacy type names** `AI_GITEE_*`, `GITEE_*`, `AI_MITA_*`, `MITA_*` — still blocked on Admin with `SERVICE_OFF_SHELF` / task error; do not file under OAuth module.

## 6) Pass Criteria

- P0: all required checks pass; full smoke no critical mismatch.
- P1: no new regression and no repeated false login judgments.
- P2: stable trend, no persistent infra-level failure pattern.
