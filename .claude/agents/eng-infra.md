---
name: eng-infra
description: 工程基建领域。任务涉及构建、发版、CI workflow、签名公证、测试体系（lowa-e2e/app-e2e/desktop-e2e/mvn test）、本地开发启动、Docker 附属服务时，先读本文档再动代码。
---

# 工程基建 领域地图

职责边界：构建/发版/CI/测试/本地开发。各领域自己的业务测试内容见对应领域文档。

## CI（.github/workflows/）

- **ci.yml**（push master + 所有 PR）：三并行 job——backend（temurin **21**，`mvn -B test`）、frontend（node 20，`npm run check:emits` + `build:h5`）、desktop（仅 `npm ci`）。不打安装包。
- **desktop-build.yml**（发版主 workflow）：触发 = workflow_dispatch / tag `v*` / PR 改动 desktop|backend|frontend 路径。矩阵：tag 或手动 = mac+win；**普通 PR 只跑 windows**（mac runner 1h+）。每平台步骤顺序（**不可乱**，PR#176）：build:h5 → build:zetaoffice → fetch-lowa-assets（LOWA_BASE_URL=自建 zh-CN 引擎 24.2.8-zhcn-r2）→ desktop npm test → mvn package(-Djavacpp.platform) + prepare-backend(jar+jlink JRE) → 三 Python 服务 prepare-python-service → **(mac)sign-mac-natives.sh → 冒烟（backend /api/admin/wizard 120s、pptx alembic+/health、mineru /docs、kokoro /health+voices 验 zf_001）→ pack-pysvc** → electron-builder（mac 签名+公证，先抬 maxfiles/ulimit 524288 防 EMFILE；win 未签名 issue #12）→ 失败时 notarytool history/log 打印 Apple 拒因 → upload-artifact → tag 时 softprops/action-gh-release 附 dmg/exe + 双语 body。
- **star-history.yml**（周一 cron）：重画 star SVG 强推 star-history 分支。

## 发版链路

0. **版本规则 0.X.Y**（docs/INCREMENTAL_UPDATE_DESIGN.md）：X=大版本全量安装包；Y=小版本应用内补丁（overlay 机制，组件=backend-app/frontend-h5/zetaoffice-wrapper/pysvc-src）。小版本 tag 触发 CI `patch-gate` job（desktop/scripts/patch-gate.sh）：改壳（desktop/）、pom、LOWA 引擎、requirements.lock 都会被拒——这些只能随大版本走。补丁产物+签名 manifest 由 build-patch-assets.js 在 windows job 生成（私钥=secret UPDATE_SIGNING_KEY，备份 ~/.ssh/aiworkdeck_update_signing.pem；公钥内置 update-service.js，换钥须发大版本）；镜像同步 deploy/update-mirror-sync.sh 在官网 ECS 跑。
1. 版本号**单一来源 `desktop/package.json` version**（backend 拆为 backend/app.jar + backend/lib/，启动 `java -cp "app.jar:lib/*" com.checkba.CheckbaApplication`，见 backend-service.js javaLaunchArgs；frontend version 不参与）。
2. `git tag v<ver> && git push origin v<ver>` → 触发 desktop-build 双平台。auto 模式下 tag 推送不被分支保护拦；可用 Monitor 等 PR 合并后自动打 tag（v0.8.0 配方）。
3. 产物：mac 仅 dmg（**arm64 only**，已放弃 Intel）；win 仅 nsis exe（x64）。electron-builder 配置在 desktop/package.json "build" 字段（appId com.aiworkdeck.desktop、extraResources 打入 frontend/dist、backend.jar、jre、python、pysvc.tar.gz+meta；notarize teamId X9B97KVA84；entitlements desktop/build/entitlements.mac.plist）。
4. 签名抖动：Apple 时间戳抖动 = rerun 即可（连挂两次也 rerun）；公证轮询抖动排查见 ci-macos 记录。
5. DMG 安装窗口视觉（PR#204）：`build.dmg` 里的 `contents` 坐标是**图标中心、原点在窗口内容区左上角（不含标题栏）**；窗口尺寸由背景图 1x 像素尺寸决定（660x420），所以没写 `window`。背景图 `desktop/build/background.png` + `background@2x.png` 由 electron-builder 自动合成 hidpi TIFF，源文件是 `desktop/build/dmg-background.html`（顶部注释有 headless Chrome 重新生成命令）。改图标落位必须同步改 HTML 里的光晕/箭头位置，否则错位。

## 测试命令总表

| 命令 | 目录 | 覆盖 |
|---|---|---|
| `mvn -B test` | backend/ | 51 个 *Test.java：IDOR/鉴权/分片上传/编排/记忆/证据/市场/脱敏 + **回放评测 OrchestratorReplayEvalTest**（resources/ai-eval/cases/ 10 组）+ DesktopContextSmokeTest。**必须 JDK 21**（本机默认 25 SIGBUS） |
| `OPENROUTER_API_KEY=… mvn test -Dtest=RealLlmSmokeTest` | backend/ | 真实 LLM 冒烟（默认跳过） |
| `npm test` | desktop/ | service-manager / model-manager / pysvc-runtime / overlay（补丁覆盖层）/ update-service（验签/下载/激活/回滚，本地 HTTP 伪造更新服务器） |
| `npm run check:emits` | frontend/ | @event 绑定 vs $emit 声明静态护栏（scripts/check-emit-bindings.mjs） |
| `npm run test:lowa-e2e` | frontend/ | LOWA 真引擎+键盘链路（tests/lowa-e2e/run.mjs，puppeteer-core 无头，基线 19 组 169 断言；不经应用页面，天然无登录前置） |
| `npm run test:app-e2e` | frontend/ | 全应用真人模拟（tests/app-e2e/run.mjs；PR-A 去登录后 J1=首启解锁门（试用码），其余旅程 local-mode 免登直达，不再注册 qa_bot_*；需 dev:h5 **5174** + local-mode 后端（默认 9696，冷启动可用新 jar 9797 顶班 + 隔离 user.home/H2/cwd，APP_E2E_JAR 供 J11）。**发版前必跑** |
| `npm run test:desktop-e2e` | frontend/ | 桌面保存链路（弹 dev Electron 窗口，webview 真 LOWA 插文本→保存→API 下载验内容；PR-A 后免登直达，provision 会自动用试用码解锁+置向导）。`APP_E2E_BACKEND` 的端口会经 `CHECKBA_BACKEND_PORT` 传给 Electron 壳——渲染层的基址是壳注入的，只改 `VITE_API_BASE_URL` 对它无效 |

每日全量 QA：`scripts/qa-nightly.sh`（crontab，跑在 ~/aiworkdeck-qa/repo 专用克隆，报告 ~/aiworkdeck-qa/reports/，失败 gh 开 issue 标签 qa-nightly，引擎取自已安装 app）。

## 端口体系（2026-08 起）

- **打包态桌面后端：5269 → 5369 → 5169 → 随机**（`desktop/main/services/backend-service.js` allocateBackendPort：真实 bind 探测；被占时先探 `/api/admin/wizard` 验明是否自家后端——是则复用，否则降级下一个。service-manager 的 verifyReuse/reallocatePort 契约即为此加）。实际端口经 BrowserWindow additionalArguments → preload → `window.checkbaDesktop.apiBaseUrl` 注入渲染层，`frontend/src/services/api.js` 最优先读它。
- **dev 态后端仍 9696**：restart-backend.sh / e2e / CI 全部不变；`CHECKBA_BACKEND_PORT` 可显式覆盖两种模式。注入优先级高于 `VITE_API_BASE_URL`，所以**凡是走 Electron 壳的测试/脚本，要换后端必须改 `CHECKBA_BACKEND_PORT`**，只指 dev server 的环境变量不管用（desktop-e2e 曾因此整条链失败）。
- pptx/mineru/kokoro 打包态是动态回环端口（由后端内部转发，前端不可见）；编辑器静态服务器 47613 因 COOP/COEP 跨源隔离必须独立源，勿并入。

## 本地开发启动

- 一键：`./restart-all.sh`（Docker 服务 + 后端 + 前端 + 桌面）。
- 后端：`cd backend && ./restart-backend.sh`（mvn package -DskipTests → kill 9696 → nohup java -jar，prod 配置，日志 backend/app.log）。
- 前端：`cd frontend && npm run dev:h5`（5173；e2e 用 `npx uni --port 5174`）。**npm 不是 pnpm**。
- 桌面：`cd desktop && npm run dev`（AIWORKDECK_DESKTOP_DEV=1 electron .；dev Electron 复用已跑的 9696 后端不另起 java）。`npm run clean` 清用户数据目录。
- Docker 附属：`docker compose up`（mineru 8001、pptx 5001、easyvoice 9549 段已停用改 ElevenLabs）。

## 关键构建脚本（desktop/scripts/）

- `prepare-backend.js` — fat jar→backend.jar + jlink 裁剪 JRE 到 bundled/<plat>/。
- `prepare-python-service.js` — python-build-standalone 3.11.12 + pip site-packages + 服务源码；mineru 纯 pip 无源码。
- `pack-pysvc.js` — 上万小文件→单 pysvc.tar.gz + meta（首启解压进度条；解压逻辑在 desktop/main/services/pysvc-runtime.js）。**必须在签名与冒烟之后跑**。
- `fetch-lowa-assets.js` — LOWA 运行时+CJK 字体进 frontend/dist/zetaoffice/，保留 brotli + .encodings.json 侧车。
- `sign-mac-natives.sh` — 签 electron-builder 够不到的 Mach-O（JRE + jar 内嵌 dylib），时间戳退避重试，nested jar 用 zip -0 回写。
- `desktop/lowa-build/mega-build.sh` — 从源码重建 zh-CN LOWA（Ubuntu 22.04，无人值守）。

## 部署与其它

- `deploy/web/` — Web 服务器版（瘦客户端 Phase A2/鸿蒙路线）：nginx.conf.example、能力探针 probe/、后端 prod 只听 127.0.0.1。
- 官网部署在独立仓库（website/，gitignore 掉），服务器 ssh -i ~/.ssh/aiworkdeck_ops root@47.92.111.102；ECS 8.137.95.63(~/.ssh/checkba_ecs)。
- 模型不进包：mineru/kokoro 模型首启在"组件管理"下载（下载进度按字节级整体，PR#142）。

## 已知地雷

- CI 签名→冒烟→打包顺序不可乱（PR#176）。
- macOS CI 红要当真查（Apple 协议过期事件后恢复）；`--admin` 合并与 worktree 删分支技巧见 ci-macos 记录。
- iCloud 驱逐会掏空本地文件（打包/测试环境两次踩）；EMFILE 用抬 ulimit 解。
- worktree 冷启动跑双 e2e 完整配方见 v0.7.7 发版实录；worktree merge 报 stash failed 用 cherry-pick 绕。
- 坏 pnpm node_modules 遇到过——本项目一律 npm。
- **worktree 的 node_modules 落后于新增依赖**（如 TOTP 带来的 `qrcode`）时，vite 会推一个盖满视口的 `vite-error-overlay`，坐标点击全被它吃掉，e2e 表现成"点了没反应"的超时而非编译错误。冷启动 worktree 跑 e2e 前先 `npm install`；desktop-e2e 的点击已加命中校验，会直接报出遮挡者和它的文案。
- 分支保护拦 gh pr merge 时权宜 = 用户网页点 Bypass rules and merge（白名单未配成）。
- `docs/` 在 .gitignore，入库要 `git add -f`。

## 验证（改本领域自身时）

- 改 workflow：推 PR 触发 windows 矩阵先验；mac 变更合并后用手动 workflow_dispatch 验证。
- 改构建脚本：本地跑对应脚本 + desktop `npm test`（pysvc-runtime 有覆盖）。
