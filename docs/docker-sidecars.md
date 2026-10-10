<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# 根目录 docker-compose：只是侧车 / Root docker-compose: sidecars only

> **⚠️ 不是全栈 / Not a full stack.** 根目录 `docker-compose.yml` 只起 MinerU 与 PPTX 两个侧车，
> **不**含 Java 后端、前端、PostgreSQL，`docker compose up` 之后不会得到可用的 AI WorkDeck。
> The root `docker-compose.yml` starts only the MinerU and PPTX sidecars. It does **not** start the
> Java backend, the frontend, or PostgreSQL; `docker compose up` alone does not give you a working app.

## 它起什么 / What it starts

| 服务 Service | 宿主地址 Host address | 容器内 In container | 健康检查 Health check |
|---|---|---|---|
| `mineru-service`（文档解析 / PDF→Markdown） | `127.0.0.1:8001` | `:8000` | `curl -f http://127.0.0.1:8001/docs` |
| `pptx-service`（banana-slides，PPTX 生成/编辑、PDF OCR 入口） | `127.0.0.1:5001` | `:5000` | `curl -f http://127.0.0.1:5001/health` |

两者都只发布到宿主回环（服务本身不鉴权）。MinerU 首次启动要下载模型，`start_period` 为 300 秒，
内存预留 8 GB、上限 16 GB。
Both publish to host loopback only (the services have no auth). MinerU downloads models on first
start (300 s start period, 8–16 GB RAM).

## 它不起什么 / What it does not start

- Java 后端（Spring Boot，`:9696`）/ Java backend
- 前端 H5 开发服务器（`:5173`）与 Electron 桌面壳 / H5 dev server and Electron shell
- PostgreSQL / pgvector

这些由 [`restart-all.sh`](getting-started.md#run-the-stack) 在宿主上起（侧车也由它顺带拉起）。
Those run on the host via `restart-all.sh` (which also brings the sidecars up).

## 主栈如何连到侧车 / How the main stack reaches the sidecars

- **Java 后端 → PPTX**：`backend/src/main/resources/application.yml` 中 `external.pptx-service.base-url`（默认 `http://localhost:5001`，见 `PptxServiceClient.java:44`）。
  The backend calls the PPTX sidecar at `http://localhost:5001`.
- **PPTX → MinerU**：在 compose 网络内经 `pptx-service/.env` 的 `MINERU_LOCAL_URL=http://mineru-service:8000`；
  宿主上的 `127.0.0.1:8001` 主要用于排障。若配置了 `MINERU_TOKEN`，本地失败时会回退到云端 MinerU（会外发，见评估包关闭清单）。
  The PPTX sidecar reaches MinerU over the compose network (`MINERU_LOCAL_URL`). Host port 8001 is
  mainly for debugging. If `MINERU_TOKEN` is set, it can fall back to cloud MinerU (outbound traffic).
- **桌面 release 用户不需要这份 compose。** Desktop-release users do not need this compose file.

## 律所自托管 / Firm self-host

律师默认评估路径（拓扑 A，桌面端）见 [firm-eval-topology-a.md](firm-eval-topology-a.md)。
所内全自托管主栈（拓扑 B：Postgres + 后端 + 前端 compose）在 [#3](https://github.com/AI-WorkDeck/aiworkdeck/issues/3)
跟踪，暂缓到有实际律所需求时再做。
The default lawyer evaluation path (topology A) is in `firm-eval-topology-a.md`. A fully self-hosted
main stack (topology B) is tracked in #3 and deferred until a real firm needs it.
