# AIGC 多智能体改造 — PR 提交清单

> 生成时间：2026-04-18 · 分支：`fbsir` · 提交：`826e1bf9`（相对 `origin/fbsir` 超前 1 个提交）

## 一、本次已执行的自动化结果

| 阶段 | 命令 / 说明 | 结果 |
|------|----------------|------|
| Maven 全模块单测 | `mvn -q -DskipTests=false test`（根 `pom.xml`，需本机 Maven） | **通过** |
| Engine 单测 | `WxFbsir-engine` 下 `mvn -q test` | **通过** |
| 前端生产构建 | `WxFbsir-ui`：`npm run build:prod` | **通过** |
| AI 能力映射校验 | `WxFbsir-ui`：`npm run test:aigc-mapper` | **通过** |
| E2E P0 | `tools/e2e-aigc-regression.ps1 -Tier P0`（需 Admin `http://127.0.0.1:8080` + Engine 已登录各站） | **RESULT_OK=7/7** |
| E2E P1 | 同上 `-Tier P1`（ASCII / 中文 / Emoji 各一轮） | **3 轮均 7/7** |

**说明**：本机 Maven 若未加入 `PATH`，可使用仓库内 `build-tools/apache-maven-3.9.15/bin/mvn.cmd`（该目录已在 `.gitignore`，仅本地使用）。

**可选加强（合并前或预发环境）**：`e2e-aigc-regression.ps1 -Tier P2`（多轮 ASCII + 中文），耗时更长。

---

## 二、推送与开 PR

```powershell
cd D:\U3WV2
git status
git push --force-with-lease origin fbsir   # 若曾推送过旧历史；首次推送则用 git push -u origin fbsir
```

在 Gitee/GitHub 上：**base** 选目标主分支（如 `main`/`master`），**compare** 选 `fbsir`，创建 Pull Request。

---

## 三、建议 PR 标题

```
feat(aigc): 多智能体 AIGC 端到端（Engine / Business / UI / 回归工具）
```

---

## 四、建议 PR 正文（可复制）

### 摘要

统一多 AI 的 `aiType` / 会话与草稿契约；Engine 侧补齐多控制器与元宝扫码登录持久化；Business 侧草稿 SQL 与 WebSocket 可观测字段；前端侧消息归一化与多 AI 草稿；附带 E2E 回归脚本与测试矩阵文档。

### 模块范围

- **WxFbsir-engine**：多 AI 控制器与工具类、能力归一、流式与浏览器池等相关改动。
- **WxFbsir-business / WxFbsir-framework**：AIGC 草稿查询、路由日志、`AiResultHandler` 废弃说明、登录与 Gitee 联动。
- **WxFbsir-ui**：`engineMessageNormalizer`、`aigc` / `drafts` / `loginManager`、`engineConfig` 等。
- **tools / docs**：`e2e-aigc-*.ps1`、`aigc-test-matrix.md`、`sql/MIGRATION_ORDER.txt` 等。

### 风险与注意

- 需配合 **数据库迁移**（见 `sql/MIGRATION_ORDER.txt`）与 **Engine 部署**。
- `AiResultHandler` 已标记废弃，写库以 `EngineMessageRouter` 为准。
- E2E 依赖本机/环境 **Admin + Engine** 及各 AI 站登录态。

### 测试

- [x] `mvn test`（根工程）
- [x] `WxFbsir-engine` `mvn test`
- [x] `npm run build:prod` + `npm run test:aigc-mapper`
- [x] E2E P0 / P1（见 `tools/out/` 下本次 JSON/日志，勿提交）

### 相关文档

- `docs/aigc-test-matrix.md`
- `docs/AIGC_REGRESSION_CHECKLIST.txt`

---

## 五、Reviewer 快速核对

1. `sql/MIGRATION_ORDER.txt` 是否在目标环境已执行。
2. 合并后是否在预发跑一轮 **P0** E2E。
3. 前端 `engineConfig` 与后端 Engine 地址是否一致。
