# Hermes Agent（NousResearch）安装可行性评估

> **评估日期**：2026-04-14  
> **对象**：GitHub [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent)（PyPI/源码安装，当前浅克隆版本 **v0.9.0**）  
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
- **运维与脚本**：官方脚本/服务安装（systemd、launchd）**面向 Unix**，Windows 需自行封装（计划任务、NSSM 等）。  
- **终端编码**：自动化脚本中应设置 **`PYTHONUTF8=1`** 或使用 **`chcp 65001`**，避免 `doctor`、日志中的 emoji 触发编码错误。

---

## 3. 与 WxFbsir「Engine 同机 + Admin 纳管」的匹配度

### 3.1 部署形态

- Hermes 为 **Python 进程**，与 **Java Engine** 同机共存：**无冲突**（不同端口/不同进程）。  
- 官方 **一键安装** 在 Windows 上不可用，**建议**：  
  - **生产/长期**：Engine 所在机使用 **Linux** 或 **WSL2** 跑 Hermes，与官方文档一致；或  
  - **开发环境**：接受 **Windows 原生 venv + pip**（本评估路径）。

### 3.2 健康检查（HTTP）

- **OpenClaw**：独立 Gateway，常配 **固定 HTTP 端口** 做 GET 探测。  
- **Hermes**：Gateway 逻辑在仓库 **`gateway/`** 包内，**具体监听端口、是否提供 `/health` 类 HTTP**，需以 **实际运行 `hermes gateway` 的配置** 为准（并阅读 `gateway` 与 `web/` 下实现）。  
- **本评估未启动 `hermes gateway`**（通常需先 `hermes setup`、配置 API Key/渠道，且可能依赖 Node/npm 等），**未实测 HTTP 端点**。  
- **集成建议**：在方案落地阶段 **增加一步**：启动 Gateway 后，用 `curl`/浏览器确认 **Admin 可访问的 URL**（内网 IP + 路径），再写入 `ws_host_whitelist.health_check_url`。

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
