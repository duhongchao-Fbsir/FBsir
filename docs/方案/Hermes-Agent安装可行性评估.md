# Hermes Agent（NousResearch）安装可行性评估

> **评估日期**：2026-04-15（§2.4 WSL2 Gateway + `/health` 已实测）  
> **上游同步**：2026-04-14，`main` @ **`4610551`**（`git pull` + `venv` 内 `pip install -e ".[dev]"` 或 `pip install -e "."`）  
> **对象**：GitHub [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent)（PyPI/源码安装，包版本仍为 **v0.9.0**，以 `pyproject.toml` 为准）  
> **目的**：判断在 **Engine 同机** 部署及后续 **Admin HTTP 健康检查** 集成的可行性与注意事项。

---

## 1. 官方立场

| 项目 | 说明 |
|------|------|
| 支持平台（官方） | **Linux / macOS / WSL2**；一键脚本 `install.sh` |
| Windows 原生 | 文档写明 **Native Windows is not supported**，要求 **WSL2** |
| 运行时 | **Python 3.11+**（文档示例用 `uv` + 3.11）；本评估使用 **Python 3.12.10** |
| 形态 | **Python 包 + CLI `hermes`**；Gateway 为 **独立子系统**（仓库内 `gateway/` 目录），与 OpenClaw 的单一 Node Gateway 不同 |

---

## 2. 本机实际执行结果（Windows 原生）

> 说明：以下为**验证技术可行性**，**不代表**官方推荐路径；生产环境仍建议 **Linux 或 WSL2** 与文档一致。

### 2.1 安装步骤

1. 安装 **Python 3.12**（winget：`Python.Python.3.12`）。  
2. 浅克隆：`git clone --depth 1 https://github.com/NousResearch/hermes-agent.git D:\u3wv2\.dev\hermes-agent`  
3. 虚拟环境：`py -3.12 -m venv venv`  
4. 可编辑安装：`pip install -e "."`（**未安装** `.[all]`，以减小体积与失败面）

### 2.2 结果

| 项 | 结果 |
|----|------|
| `pip install -e "."` | **成功**（`hermes-agent==0.9.0`） |
| `hermes --version` | **成功**：`Hermes Agent v0.9.0 (2026.4.13)` |
| `hermes doctor` | **在默认 GBK 控制台失败**（UnicodeEncodeError，emoji 输出）；设置 **`PYTHONUTF8=1`** 并 **UTF-8 控制台** 后 **成功** |
| 诊断摘要 | 缺少 `~/.hermes/.env`、未配置 OpenRouter 等 API；**未配置为阻塞** CLI 本身运行；可选组件（croniter、telegram、discord 等）未装 |

### 2.3 结论（Windows 原生）

- **核心依赖安装与 CLI 可用**：**可行**（本机已验证）。  
- **`hermes gateway run`（Gateway 前台）**：**不可用**——`gateway/status.py` 使用 Unix 式 `os.kill(pid, 0)` 做存活检测，在 Windows 上会 **`OSError: [WinError 87]`**；**请使用 WSL2/Linux** 跑 Gateway。  
- **运维与脚本**：官方脚本/服务安装（systemd、launchd）**面向 Unix**，Windows 需自行封装（计划任务、NSSM 等）。  
- **终端编码**：自动化脚本中应设置 **`PYTHONUTF8=1`** 或使用 **`chcp 65001`**，避免 `doctor`、日志中的 emoji 触发编码错误。

### 2.4 WSL2（本机已验证，推荐跑 Gateway）

| 项 | 结果 |
|----|------|
| 发行版 | **Ubuntu 24.04**，WSL **2** |
| Python | **3.12**（系统包 + **`/root/hermes-venv`**） |
| 安装方式 | `pip install -e "/mnt/d/u3wv2/.dev/hermes-agent[dev]"`（与 Windows 侧同一克隆目录，经 `/mnt/d` 挂载） |
| 启动命令 | **`API_SERVER_ENABLED=true` `hermes gateway run`**（CLI 子命令为 **`run`**，非裸 `gateway`） |
| HTTP 健康检查 | **`http://127.0.0.1:8642/health`** → **HTTP 200**，JSON **`{"status":"ok","platform":"hermes-agent"}`**（从 **Windows** 侧 `curl` 验证） |

**一键脚本（仓库内）**：`tools/start-hermes-wsl.ps1`（前台）；`-Background` 后台写 `/tmp/hermes-gw.out`。Shell 模板：`tools/hermes-wsl/gateway-run.sh`。

> WSL 启动时若提示与 **localhost 代理 / NAT** 相关警告，一般**不影响**本机 `127.0.0.1:8642` 访问；若需消除，可在新版 Windows 11 的 **`.wslconfig`** 中尝试 **`networkingMode=mirrored`**（按微软文档操作）。

---

## 3. 与 WxFbsir「Engine 同机 + Admin 纳管」的匹配度

### 3.1 部署形态

- Hermes 为 **Python 进程**，与 **Java Engine** 同机共存：**无冲突**（不同端口/不同进程）。  
- 官方 **一键安装** 在 Windows 上不可用，**建议**：  
  - **生产/长期**：Engine 所在机使用 **Linux** 或 **WSL2** 跑 Hermes，与官方文档一致；或  
  - **开发环境**：接受 **Windows 原生 venv + pip**（本评估路径）。

### 3.2 健康检查（HTTP）

- **OpenClaw**：独立 Gateway，常配 **固定 HTTP 端口** 做 GET 探测。  
- **Hermes**：在 **WSL2** 启用 **`API_SERVER_ENABLED=true`** 并执行 **`hermes gateway run`** 时，默认 **API Server** 监听 **8642**，路径 **`/health`**（本机已用 `curl` 验证 **200**）。生产需配置 **`API_SERVER_KEY`** 等（日志会提示无密钥时匿名可访问）。  
- **集成建议**：白名单 **`health_check_url`** 填 **`http://<主机>:8642/health`**（本机开发可用 **`http://127.0.0.1:8642/health`**）；若 Admin 在 Windows、Hermes 仅在 WSL，通常仍可用 **127.0.0.1**（WSL2 与 Windows 回环互通）。

### 3.3 资源与依赖

- **最小安装**（仅 `pip install -e .`）已拉取较多 Python 依赖；**完整 `.[all]`** 更大，且文档提到 **Node.js**、**npm install** 用于浏览器/WhatsApp 等能力。  
- **Engine 机器** 需预留 **磁盘与内存**；与 Engine 同机时建议 **监控** CPU/内存，避免与 Playwright 池争抢。

---

## 4. 总体结论

| 维度 | 评估 |
|------|------|
| **技术可行性** | **高**：Python 包可安装，CLI 可运行；与 Java Engine 无直接冲突。 |
| **官方支持路径** | **Linux / macOS / WSL2**；Windows 仅作开发/验证可行，**生产对齐 WSL2/Linux**。 |
| **与 Admin 纳管对齐** | **已实现**：白名单 **`hermes`** + **`health_check_url`**，Admin **定时/手动** GET 探测；具体 URL 仍须在 **Hermes Gateway 跑通后** 按实际端口与路径填写。 |
| **建议下一步** | 在目标环境启动 **`hermes gateway`**，将可访问的 **HTTP 健康 URL** 写入白名单；生产优先 **Linux/WSL2** 与官方安装路径对齐。 |

---

## 5. Admin 集成状态（2026-04）

- 已在 **主机白名单** 支持 **`host_type=hermes`**，与 OpenClaw 共用 **定时 + 手动** HTTP 健康检查；详见 **`docs/方案/Hermes-Agent与主机纳管集成方案.md`** §12。

---

## 6. 本机路径（便于复现）

- 源码克隆：`D:\u3wv2\.dev\hermes-agent`  
- 虚拟环境：`D:\u3wv2\.dev\hermes-agent\venv`  
- CLI：`D:\u3wv2\.dev\hermes-agent\venv\Scripts\hermes.exe`

（`.dev/` 已在仓库 `.gitignore` 中忽略，避免误提交。）

### 6.1 重新同步上游（维护命令）

在 **`D:\u3wv2\.dev\hermes-agent`** 下执行：

```bash
git fetch origin && git pull origin main
.\venv\Scripts\python.exe -m pip install -e "."
.\venv\Scripts\hermes.exe --version
```

启用 API Server 做 Admin 健康检查时（默认 **8642**，路径 **`/health`**）：

- **WSL2**（推荐，见 §2.4）：`tools/start-hermes-wsl.ps1` 或 `hermes gateway run`（需 `API_SERVER_ENABLED=true`）。  
- **Windows 原生**：仅 **`hermes --version` / doctor** 等可用；**不要**依赖 `hermes gateway run`（见 §2.3）。
