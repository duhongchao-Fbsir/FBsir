# AIGC Full Regression Matrix

This document defines a risk-oriented test matrix for the 7 AI platforms:

- deepseek
- doubao
- qianwen
- yuanbao
- wenxin
- mita
- gitee

## 1) Release Gates

### P0 (must pass before merge/release)

- 7-platform full smoke query success rate
- Login detection correctness
- Payload integrity (`sessionId`, `chatId`, `query`, `userPrompt`)
- Result message integrity (`AI_TASK_RESULT` or `AI_TASK_ERROR`)
- Encoding sanity (`ascii`, `zh-cn`, `emoji`)

### P1 (daily regression)

- Reboot persistence for login state
- UI selector fallback paths
- Timeout boundary behavior
- Anti-noise login judgment (irrelevant hints/modals)
- Error observability (`ai`, `sessionId`, stage, category)

### P2 (weekly stability)

- Multi-run consistency (3+ consecutive full runs)
- Concurrency and pool pressure
- Long-run memory/process stability
- Failure classification trend

## 2) Platform x Risk Matrix

| Risk Dimension | DeepSeek | Doubao | Qianwen | Yuanbao | Wenxin | Mita | Gitee |
|---|---|---|---|---|---|---|---|
| login check accuracy | required | required | required | required | required | required | required |
| login persistence after restart | required | required | required | required | required | required | required |
| anti-noise login judgment | required | required | required | required | required | required | required |
| payload field integrity | required | required | required | required | required | required | required |
| response extraction stability | required | required | required | required | required | required | required |
| timeout/degrade behavior | required | required | required | required | required | required | required |
| mode toggle behavior | optional | optional | optional | optional | optional | optional | required |
| upload passthrough behavior | optional | optional | optional | optional | optional | optional | required |

## 3) Test Case Catalog

### P0 Cases

- `TC-P0-01`: Full 7-platform smoke run, target `RESULT_OK=7/7`.
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
- `TC-P1-05`: Gitee mode switch and repository dialog paths.

### P2 Cases

- `TC-P2-01`: 3x consecutive full runs, compare pass rate variance.
- `TC-P2-02`: Concurrent execution under pool/queue pressure.
- `TC-P2-03`: Long-run (2h+) with process and memory sampling.
- `TC-P2-04`: Noise and network fault injection.

## 4) Execution Script

Use `tools/e2e-aigc-regression.ps1`:

- `-Tier P0` for release gate.
- `-Tier P1` for daily baseline.
- `-Tier P2` for weekly/stability run.
- Outputs:
  - plain logs: `tools/out/*.log`
  - structured json: `tools/out/*.json`
  - per-ai fields: `ai`, `sessionId`, `outcome`, `stage`, `errorCategory`, `aiTypeDetectedFrom`

## 5) Naming Boundary

- `gitee-ai-chat` means the AI chat platform integrated in AIGC flow.
- `gitee-oauth` means account binding/login for system account integration.
- Any regression case or issue must include this boundary label to avoid routing to wrong module.

## 6) Pass Criteria

- P0: all required checks pass; full smoke no critical mismatch.
- P1: no new regression and no repeated false login judgments.
- P2: stable trend, no persistent infra-level failure pattern.

