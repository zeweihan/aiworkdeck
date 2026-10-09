<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# 律所评估包 · 拓扑 A：桌面端（律师默认评估路径）/ Firm eval pack · Topology A

> 目标：干净机器 ≤30 分钟到「能打开项目并跑一次 AI」。
> Goal: from a clean machine to "open a project and run one AI turn" in ≤30 minutes.
>
> 拓扑 B（所内全自托管主栈）**暂缓**，等有实际律所需求再做，跟踪见 [#3](https://github.com/zeweihan/aiworkdeck/issues/3)。
> Topology B (fully self-hosted main stack) is deferred until a real firm needs it; tracked in #3.

## 推荐路径（多数律所试用）/ Recommended path

1. 下载[最新桌面 release](https://github.com/zeweihan/aiworkdeck/releases/latest)（macOS / Windows）。
2. 安装并启动。社区版安装包与商业授权是同一二进制。启动即进工作台，无需登录。
3. 项目文件默认存在本机（以官网隐私说明为准）。
4. 首次使用 AI 或市场付费能力时，按提示就地登录对应区域站点（aiworkdeck.com 或 workdeck.ai）。
5. 按提示开通/充值后，对一份合同跑一次 AI 小改。计费与试用规则以协议为准。

**不需要** JDK / Maven / PostgreSQL / Docker：桌面 release 自带后端、JRE 与本地数据库（H2）。
根目录 `docker-compose.yml` **不是**全栈，只起 MinerU(8001) 与 PPTX(5001) 侧车，见 [docker-sidecars.md](docker-sidecars.md)。

English: download the desktop release, install, open the workbench (no sign-in at startup), sign in
in place the first time you use AI, then run one small AI edit on a contract. No JDK, Maven,
PostgreSQL, or Docker is needed; the release bundles the backend, a JRE, and a local database.

## 从源码评估（贡献者 / IT）/ From source

见 [getting-started.md](getting-started.md)：`./restart-all.sh` 起侧车 + 后端 :9696 + H5 :5173 + Electron。

| 服务 Service | 地址 Address |
|---|---|
| Backend | http://localhost:9696 |
| Frontend (H5 dev) | http://localhost:5173 |
| PPTX sidecar | http://localhost:5001 |
| MinerU sidecar | http://localhost:8001 |

## 会外发功能关闭清单（评估勾选）/ Outbound-feature checklist

对齐官网隐私说明，所内演示前按需关闭 / 不启用：

- [ ] 云端大模型（改用本地模型，或不用 AI）/ Cloud LLMs
- [ ] 云端 OCR / 通义听悟 / 联网搜索 / 企查查等插件 / Cloud OCR, Tingwu, web search, Qichacha plugins
- [ ] 云端 MinerU 回退（源码栈：不配 `MINERU_TOKEN`）/ Cloud MinerU fallback
- [ ] 手机云端中转 / Mobile cloud relay
- [ ] 团队 / 律所共享记忆（服务器侧）/ Team or firm shared memory (server side)
- [ ] 远程 Git 同步（若不想出域）/ Remote Git sync

## 健康检查 / Health checks

- 桌面：能新建项目并打开一份空白 docx。/ Desktop: create a project and open a blank docx.
- 源码栈后端：`curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9696/api/app/language` 应返回 `200`
  （后端未引入 Spring Actuator，没有 `/actuator/health`）。
  Source backend: the call above should print `200` (no Actuator endpoint exists).
- 侧车：`curl -f http://127.0.0.1:5001/health`、`curl -f http://127.0.0.1:8001/docs`。

失败时先看：后端日志 `backend/app.log`；侧车 `docker compose logs mineru-service pptx-service`。
On failure check `backend/app.log` and `docker compose logs`.

## 计时验收（待做）/ Timed acceptance (pending)

#3 验收要求新人按本文计时 ≤30 分钟。本文尚未经过干净机器计时，计时结果补在 #3。
#3 requires a newcomer to complete this in ≤30 minutes; that timed run has not been done yet.
