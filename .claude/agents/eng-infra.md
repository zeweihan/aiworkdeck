---
name: eng-infra
description: 工程基建领域。任务涉及构建、发版、CI workflow、签名公证、测试体系（lowa-e2e/app-e2e/desktop-e2e/mvn test）、本地开发启动、Docker 附属服务时，先读本文档再动代码。
---

# 工程基建 领域地图

职责边界：构建/发版/CI/测试/本地开发。各领域自己的业务测试内容见对应领域文档。

## CI（.github/workflows/）

- **ci.yml**（push master + 所有 PR）：三并行 job——backend（temurin **21**，`mvn -B test`）、frontend（node 20，`check:emits` + `check:locales` + `check:nav:full` + `test:project-home` + `test:commands` + `build:h5`）、desktop（仅 `npm ci`）。不打安装包。
- **desktop-build.yml**（发版主 workflow）：触发 = workflow_dispatch / tag `v*` / PR 改动 desktop|backend|frontend 路径。矩阵：tag 或手动 = mac+win；**普通 PR 只跑 windows**（mac runner 1h+）。每平台步骤顺序（**不可乱**，PR#176）：build:h5 → build:zetaoffice → fetch-lowa-assets（LOWA_BASE_URL=自建 zh-CN 引擎 24.2.8-zhcn-r2）→ desktop npm test → mvn package(-Djavacpp.platform) + prepare-backend(jar+jlink JRE) → 四个 Python 服务 prepare-python-service（pptx/mineru/kokoro/asr） → prepare-graphviz → **(mac)sign-mac-natives.sh → 冒烟（backend /api/admin/wizard 120s、pptx alembic+/health、mineru /docs、kokoro /health+voices 验 zf_001、asr /health 验 modelReady:false）→ pack-pysvc** → electron-builder（mac 签名+公证，先抬 maxfiles/ulimit 524288 防 EMFILE；win 未签名 issue #12）→ 失败时 notarytool history/log 打印 Apple 拒因 → upload-artifact → tag 时 softprops/action-gh-release 附 dmg/exe + 双语 body。
- **star-history.yml**（周一 cron）：重画 star SVG 强推 star-history 分支。

## 发版链路

0. **版本规则 0.X.Y**（docs/INCREMENTAL_UPDATE_DESIGN.md）：X=大版本全量安装包；Y=小版本应用内补丁（overlay 机制，组件=backend-app/frontend-h5/zetaoffice-wrapper/pysvc-src）。小版本 tag 触发 CI `patch-gate` job（desktop/scripts/patch-gate.sh）：改壳（desktop/）、pom、LOWA 引擎、requirements.lock 都会被拒——这些只能随大版本走。补丁产物+签名 manifest 由 build-patch-assets.js 在 windows job 生成（私钥=secret UPDATE_SIGNING_KEY，备份 ~/.ssh/aiworkdeck_update_signing.pem；公钥内置 update-service.js，换钥须发大版本）；镜像同步 deploy/update-mirror-sync.sh 在官网 ECS 跑。
1. 版本号**单一来源 `desktop/package.json` version**（backend 拆为 backend/app.jar + backend/lib/，启动 `java -cp "app.jar:lib/*" com.checkba.CheckbaApplication`，见 backend-service.js javaLaunchArgs；frontend version 不参与）。
2. `git tag v<ver> && git push origin v<ver>` → 触发 desktop-build 双平台。auto 模式下 tag 推送不被分支保护拦；可用 Monitor 等 PR 合并后自动打 tag（v0.8.0 配方）。
3. 产物：mac 仅 dmg（**arm64 only**，已放弃 Intel）；win 仅 nsis exe（x64）。electron-builder 配置在 desktop/package.json "build" 字段（appId com.aiworkdeck.desktop、extraResources 打入 frontend/dist、backend.jar、jre、python、pysvc.tar.gz+meta、**graphviz、skills（随包内置 skill，v0.11.1 以前漏打）、litviz（诉讼可视化引擎）**；notarize teamId X9B97KVA84；entitlements desktop/build/entitlements.mac.plist）。
4. 签名抖动：Apple 时间戳抖动 = rerun 即可（连挂两次也 rerun）；公证轮询抖动排查见 ci-macos 记录。
5. **EN 走查（打 tag 前必过）**：① 以英文语言设置跑 app-e2e 全量（含 J12 英文旅程：切 en-US 断言工作台四列英文锚点 + AI 过程卡工具名无中文，语言键 `awd_app_language`，切语言必须整页 reload）；② 编辑器 boot 用 `?uilang=en-US` 并以 office_thread.js 的 ooLocale 诊断确认 en-US 生效（issue #66 的诊断口径）；③ 人工过一遍英文主界面截图（工作台/设置/AI 面板）。
6. DMG 安装窗口视觉（PR#204）：`build.dmg` 里的 `contents` 坐标是**图标中心、原点在窗口内容区左上角（不含标题栏）**；窗口尺寸由背景图 1x 像素尺寸决定（660x420），所以没写 `window`。背景图 `desktop/build/background.png` + `background@2x.png` 由 electron-builder 自动合成 hidpi TIFF，源文件是 `desktop/build/dmg-background.html`（顶部注释有 headless Chrome 重新生成命令）。改图标落位必须同步改 HTML 里的光晕/箭头位置，否则错位。

## 测试命令总表

| 命令 | 目录 | 覆盖 |
|---|---|---|
| `mvn -B test` | backend/ | 51 个 *Test.java：IDOR/鉴权/分片上传/编排/记忆/证据/市场/脱敏 + **回放评测 OrchestratorReplayEvalTest**（resources/ai-eval/cases/ 10 组）+ DesktopContextSmokeTest。**必须 JDK 21**（本机默认 25 SIGBUS） |
| `OPENROUTER_API_KEY=… mvn test -Dtest=RealLlmSmokeTest` | backend/ | 真实 LLM 冒烟（默认跳过） |
| `npm test` | desktop/ | service-manager / model-manager / pysvc-runtime / overlay（补丁覆盖层）/ update-service（验签/下载/激活/回滚，本地 HTTP 伪造更新服务器） |
| `npm run check:emits` | frontend/ | @event 绑定 vs $emit 声明静态护栏（scripts/check-emit-bindings.mjs） |
| `npm run test:commands` | frontend/ | 命令注册表守卫（tests/commands/，17 条）：加速键查重、编辑器保留键黑名单、Esc/Enter/Tab、macOS 系统截图键、客户视图过滤、菜单树可序列化。**已进 CI**，改 config/commands/ 会被它拦 |
| `npm run test:lowa-e2e` | frontend/ | LOWA 真引擎+键盘链路（tests/lowa-e2e/run.mjs，puppeteer-core 无头，基线 19 组 169 断言；不经应用页面，天然无登录前置） |
| `npm run test:app-e2e` | frontend/ | 全应用真人模拟（tests/app-e2e/run.mjs；PR-A 去登录后 J1=首启解锁门（试用码），其余旅程 local-mode 免登直达，不再注册 qa_bot_*；需 dev:h5 **5174** + local-mode 后端（默认 9696，冷启动可用新 jar 9797 顶班 + 隔离 user.home/H2/cwd，APP_E2E_JAR 供 J11）。**发版前必跑**（含 J12 英文旅程） |
  - **冷启动全配方（2026-08-19 实测走通）**：① 隔离后端起法必须 `cd backend/` 再起 jar——`ai.skills.dir: skills` 是相对 cwd 的，cwd 落在仓库根会把内置 skill 全丢掉（症状：`Skill 不存在: desensitize`）；② 全新 H2 未解锁会让套件卡死在解锁门，按 `tests/_lib/license-gate.mjs` 的 SEED_RECIPE 往隔离 `user.home/.aiworkdeck/license.json` 播存量 trial 票据（宽限期内合法，别开 trial-code 开关）；③ 5174 可能被别的 worktree 的旧 dev 服务占着（症状：断言全打在旧代码上），自起专用端口并设 `APP_E2E_BASE`；④ `APP_E2E_JAR` 一律绝对路径（runner 在临时目录 spawn，相对路径必挂 J11）。
| `npm run test:feedback-e2e` | frontend/ | 反馈浮窗全链路（dev Electron + CDP：真走主进程框选截图、Chromium 假麦克风录音、提交后从 API 回读附件字节）。需 dev:h5 + local-mode 后端，同 desktop-e2e 的端口约定 |
| `npm run test:desktop-e2e` | frontend/ | 桌面保存链路（弹 dev Electron 窗口，webview 真 LOWA 插文本→保存→API 下载验内容；PR-A 后免登直达，provision 会自动用试用码解锁+置向导）。`APP_E2E_BACKEND` 的端口会经 `CHECKBA_BACKEND_PORT` 传给 Electron 壳——渲染层的基址是壳注入的，只改 `VITE_API_BASE_URL` 对它无效 |

每日全量 QA：`scripts/qa-nightly.sh`（crontab，跑在 ~/aiworkdeck-qa/repo 专用克隆，报告 ~/aiworkdeck-qa/reports/，失败 gh 开 issue 标签 qa-nightly，引擎取自已安装 app）。

## 端口体系（2026-08 起）

- **打包态桌面后端：5269 → 5369 → 5169 → 随机**（`desktop/main/services/backend-service.js` allocateBackendPort：真实 bind 探测；被占时先探 `/api/admin/wizard` 验明是否自家后端——是则复用，否则降级下一个。service-manager 的 verifyReuse/reallocatePort 契约即为此加）。实际端口经 BrowserWindow additionalArguments → preload → `window.checkbaDesktop.apiBaseUrl` 注入渲染层，`frontend/src/services/api.js` 最优先读它。
- **dev 态后端仍 9696**：restart-backend.sh / e2e / CI 全部不变；`CHECKBA_BACKEND_PORT` 可显式覆盖两种模式。注入优先级高于 `VITE_API_BASE_URL`，所以**凡是走 Electron 壳的测试/脚本，要换后端必须改 `CHECKBA_BACKEND_PORT`**，只指 dev server 的环境变量不管用（desktop-e2e 曾因此整条链失败）。
- pptx/mineru/kokoro/asr 打包态是动态回环端口（由后端内部转发，前端不可见）；编辑器静态服务器 47613 因 COOP/COEP 跨源隔离必须独立源，勿并入。

## 本地开发启动

- 一键：`./restart-all.sh`（Docker 服务 + 后端 + 前端 + 桌面）。
- 后端：`cd backend && ./restart-backend.sh`（mvn package -DskipTests → kill 9696 → nohup java -jar，prod 配置，日志 backend/app.log）。
- 前端：`cd frontend && npm run dev:h5`（5173；e2e 用 `npx uni --port 5174`）。**npm 不是 pnpm**。
- 桌面：`cd desktop && npm run dev`（AIWORKDECK_DESKTOP_DEV=1 electron .；dev Electron 复用已跑的 9696 后端不另起 java）。`npm run clean` 清用户数据目录。predev 钩子会跑 `scripts/brand-dev-electron.js` 把 node_modules 里的 Electron.app 改名（见下条）。
- Docker 附属：`docker compose up`（mineru 8001、pptx 5001；easyvoice 9549 段已停用，语音合成改走桌面捆绑的 Kokoro）。

## 关键构建脚本（desktop/scripts/）

- `prepare-backend.js` — fat jar→backend.jar + jlink 裁剪 JRE 到 bundled/<plat>/。
- `prepare-python-service.js` — python-build-standalone 3.11.12 + pip site-packages + 服务源码；mineru 纯 pip 无源码。
- `prepare-graphviz.js` — 烙最小 graphviz（仅布局引擎，不带任何渲染后端；闭包约 4MB）到 bundled/<plat>/graphviz/。**只有诉讼可视化的流程图布局要它**。mac 需 install_name_tool 重定位 + ad-hoc 重签（改过的 Mach-O 不重签会被内核 SIGKILL）；脚本自带正反两条自检（详见 `.claude/agents/litigation-visual.md`）。
- `pack-pysvc.js` — 上万小文件→单 pysvc.tar.gz + meta（首启解压进度条；解压逻辑在 desktop/main/services/pysvc-runtime.js）。**必须在签名与冒烟之后跑**。
- `fetch-lowa-assets.js` — LOWA 运行时+CJK 字体进 frontend/dist/zetaoffice/，保留 brotli + .encodings.json 侧车。
- `sign-mac-natives.sh` — 签 electron-builder 够不到的 Mach-O（JRE + jar 内嵌 dylib），时间戳退避重试，nested jar 用 zip -0 回写。
- `desktop/lowa-build/mega-build.sh` — 从源码重建 zh-CN LOWA（Ubuntu 22.04，无人值守）。

## 部署与其它

- `deploy/web/` — Web 服务器版（瘦客户端 Phase A2/鸿蒙路线）：nginx.conf.example、能力探针 probe/、后端 prod 只听 127.0.0.1。
- `deploy/cloud/` — 官方托管的插件云后端（addin.aiworkdeck.com）：nginx server 块、systemd unit、env 模板、部署实录。
- `deploy/update-mirror-sync.sh` — 官网 ECS 上每小时一跑的镜像同步（补丁产物 + 安装包 + installers/latest.json）。
  **安装包留最新两版，别改回「只留最新一版」**：官网 /start 的下载按钮是服务端渲染的，
  数据源（官网仓 lib/latest-release.ts）对 latest.json 用了 `revalidate: 300`。旧包一删、
  而页面缓存里还是旧文件名，用户点下载就是 404（2026-08-18 发 v0.18.0 实测到）；
  留一版也给「已经点了下载、1.4GB 正在传」的用户留余地。改动这段先跑
  `bash deploy/update-mirror-sync_prune_test.sh`（纯本地，不碰网络与真镜像目录）。
  **服务器上跑的是一份副本**（`/www/wwwroot/update/desktop/update-mirror-sync.sh`，cron 每小时 :17），
  合并 PR 不会让它生效，要 scp 覆盖过去。
- `deploy/publish-pack.sh` — 原生资源包（native pack）上架：check 本地验产物 /
  sign 在官网机上用 env 私钥签 manifest（私钥不落本机）/ publish 双机上架
  `/www/wwwroot/plugin-packs/` 暂存校验后换入、双机验证后才切 manifest 指针 /
  verify 两站对账。产物来自 workflow `pack-release.yml`（workflow_dispatch，
  tag `pack-<id>-v<ver>`，win 侧 graphviz 只能在 win runner 出）。规范
  `docs/NATIVE_PACK_DISTRIBUTION.md`。
- **`deploy/publish-lowa-engine.sh` — 换 LOWA 引擎必须走它，别手工传**（issue #310）。
  `check-build <目录>` 只在本地验产物；`publish <目录> <版本号>` 发到两台机；`verify <版本号>` 切指针前必跑。
  它把三件必须同时做对的事绑在一起：按形态判定该压不该压、**两台机都同步**（新加坡是本地镜像直出、
  不回源北京，而 CI 从境外解析走新加坡——只传北京会让境内 curl 全 200 而 CI 报 404）、
  以及内容级终验。校验不走「自己算的哈希对自己」这种闭环：wasm 断言解压后是 `\0asm`，
  data 断言解压后字节数等于 metadata 里的 `remote_package_size`（emscripten 自带真值，
  下游 fetch-lowa-assets.js 对 data 只查「长度 ≥1024」，兜不住双重压缩）。
  落盘先落 web root 之外的暂存区、校验通过再 rename 换入，旧版本自动备份到 `/root/lowa-engine-backup/`。
- 官网部署在独立仓库（website/，gitignore 掉），服务器 ssh -i ~/.ssh/aiworkdeck_ops root@47.92.111.102；ECS 8.137.95.63(~/.ssh/checkba_ecs)。
- 模型不进包：mineru/kokoro/asr 模型首启在"组件管理"下载（下载进度按字节级整体，PR#142）。asr 的 faster-whisper medium 约 1.5GB，依赖闭包 182MB（压缩后约 57MB 进安装包，大头是 onnxruntime 70MB + PyAV 44MB，两者都是 faster-whisper 的硬依赖）。

## 已知地雷

- CI 签名→冒烟→打包顺序不可乱（PR#176）。
- **Windows 上 node:test 的 teardown 删「被本进程 HTTP 服务读流碰过的」临时目录，必须用异步
  `fs.promises.rm(dir, {recursive, force, maxRetries, retryDelay})`，不能用 rmSync**（PR#436，
  drawio-server.test.js 的 rmrf 帮手是范本）：读流 autoClose 的 fs.close 回调可能还排在事件
  循环里，rmSync 的 maxRetries 是同步忙等、会把事件循环连同那个 close 一起卡死，给多大预算
  都是 ENOTEMPTY 跑穿（run 32237671073 实测证伪过 rmSync 重试版）。纯文件读写的测试不受影响。
- 发版有个跨仓的时间差：镜像脚本删旧包 vs 官网页面 ISR 缓存。两边任何一边改保留策略/缓存时长前，先看 `deploy/update-mirror-sync.sh` 里 prune_old_installers 的注释。
- macOS CI 红要当真查（Apple 协议过期事件后恢复）；`--admin` 合并与 worktree 删分支技巧见 ci-macos 记录。
- iCloud 驱逐会掏空本地文件（打包/测试环境两次踩）；EMFILE 用抬 ulimit 解。
- **iCloud 上「读一下」不是免费的**：被驱逐的文件是 dataless 占位，`stat` 秒回真实大小但
  `st_blocks=0`，**一 read 就同步触发下载**（本机实测 23KB 文件首次 read 耗 1.28 秒，延迟
  决定、与大小无关）。所以凡是「扫用户文件夹」的代码只许 stat，绝不许顺手读内容——
  `LocalRootWatchService.WATCH_FILE_HASHER` 就是为此把 DirectoryWatcher 的默认内容哈希
  换成了 mtime 哈希（默认值会在建立监听时把整棵树逐字节读一遍，等于打开项目就把整个
  文件夹从 iCloud 拉回来）。回归用例 `LocalRootWatchServiceTest`，拿命名管道当"未下载文件"。
  已知仍会读穿全树的是版本记录的 `git add .`（JGit 必须读内容才能存 blob），但它被
  `repoService.isInitialized` 挡着，只有开了版本记录的项目才走到。
- **macOS 菜单栏左上角那个应用名只认 .app 包的 `CFBundleName`**，跟 `app.name`、跟菜单模板
  第一项的 label 都无关（实测 `app.setName('AI WorkDeck')` 之后菜单栏照旧写 Electron）。
  打包版由 electron-builder 按 `build.productName` 写好；dev 跑的是 node_modules 里的
  Electron.app，所以要靠 `scripts/brand-dev-electron.js` 就地改名（Electron 的 dist 包是
  linker-signed adhoc，`Info.plist=not bound`，改它不破坏签名）。**做官网/README 截图前
  务必确认这一步跑过**，否则截出来的图左上角是 Electron。
- worktree 冷启动跑双 e2e 完整配方见 v0.7.7 发版实录；worktree merge 报 stash failed 用 cherry-pick 绕。
- 坏 pnpm node_modules 遇到过——本项目一律 npm。
- **worktree 的 node_modules 落后于新增依赖**（如 TOTP 带来的 `qrcode`）时，vite 会推一个盖满视口的 `vite-error-overlay`，坐标点击全被它吃掉，e2e 表现成"点了没反应"的超时而非编译错误。冷启动 worktree 跑 e2e 前先 `npm install`；desktop-e2e 的点击已加命中校验，会直接报出遮挡者和它的文案。
- **`dist/zetaoffice` 里的 glue 会随 master 前进而过期，且会伪装成产品回归**（2026-08-18 实测踩到）：
  `office_thread.js` / `zeta.js` 是 `src/zetaoffice/public/` 的构建产物，构建一次就躺在 dist 里不动了。
  worktree 活得久一点、master 又改过那份源码，跑出来的就是**旧引擎行为**。这次的现象是 lowa-e2e
  三条署名断言红：期望 `AI WorkDeck` 实际 `AI Workdeck`——看着像"品牌统一漏改、还会写进用户的 Word
  修订署名"，一查源码一处小写都没有，纯粹是 dist 旧。重建后 391 通过 0 失败。
  **判据**：断言值和源码里的常量对不上、而源码明明是对的 → 先重建 glue，别急着开 bug。
  重建配方（`emptyOutDir: true` 会清空整个目录）：先把 `lowa/` 与 cjk 字体挪走 → `npm run build:zetaoffice`
  → 再挪回来。只借兄弟 worktree 的 `lowa/`+字体，glue 一律本树构建（见记忆「引擎 glue 与载荷之分」）。
- **`dist/zetaoffice` 里的 glue 会随 master 前进而过期，且会伪装成产品回归**（2026-08-18 实测踩到）：
  `office_thread.js` / `zeta.js` 是 `src/zetaoffice/public/` 的构建产物，构建一次就躺在 dist 里不动了。
  worktree 活得久一点、master 又改过那份源码，跑出来的就是**旧引擎行为**。这次的现象是 lowa-e2e
  三条署名断言红：期望 `AI WorkDeck` 实际 `AI Workdeck`——看着像"品牌统一漏改、而且会写进用户的 Word
  修订署名"，一查源码一处小写都没有，纯粹是 dist 旧。重建后 391 通过 0 失败。
  **判据**：断言值和源码里的常量对不上、而源码明明是对的 → 先重建 glue，别急着开 bug。
  重建配方（`emptyOutDir: true` 会清空整个目录）：先把 `lowa/` 与 cjk 字体挪走 → `npm run build:zetaoffice`
  → 再挪回来。只借兄弟 worktree 的 `lowa/`+字体，glue 一律本树构建。
- **e2e 起 Electron 必须整棵进程树一起收，CDP 端口不能写死**（2026-08-17 定位）：
  `spawn('npx', ['electron', ...])` 之后 `elec.kill()` 只打得到 npx，真正的 Electron 是**孙子进程**，
  收不到信号会活下来继续占着 CDP 端口（实测跑完一轮之后它还在 LISTEN，端口不释放）。下一轮撞上它有两种死法：
  残留还应答 CDP → 你连上去驱动的是**上一轮那个窗口**；残留只占端口不应答 → 干等 60 秒报「CDP 端点未就绪」。
  正解三件套（desktop-e2e 已落）：① `detached: true` 起进程组 + `process.kill(-pid)` 整组收（并挂 `process.on('exit'/'SIGINT')`）；
  ② 每轮现挑一个空闲端口（`DESKTOP_E2E_CDP_PORT` 可覆盖），别写死 9333——维护者常年多开，并行会话必撞；
  ③ 连之前用 `lsof` 核一遍持端口的 pid 在不在自己那棵树里，不是就当场报死。
  这三件套 + 输入加固已收进 **`frontend/tests/_lib/electron-cdp.mjs`**，desktop / feedback / meeting
  三套共用（以前各抄一份，同一个坑要踩三次）。**app-e2e / lowa-e2e 不受影响**——它们用
  `puppeteer.launch()` 自己起无头 Chrome，进程由 puppeteer 管，也自带下面那三个开关。
- **"点了没反应"先分清是输入通道坏了还是界面坏了**：`page.mouse.click()` 之后在 `window` 捕获期录
  pointerdown/mousedown/mouseup/click，**一个都没有 = 输入通道的问题**，同坐标重试没有意义。
  这一档故障的特征很干净也很反直觉：**`mouseMoved` 照常送达，`mousePressed`/`mouseReleased`/`dispatchKeyEvent`
  被静默丢弃**，而渲染器一切正常（evaluate、布局度量、elementFromPoint、`$refs`、页面栈全对），且一旦坏了整轮不恢复。
  已逐条实测排除：窗口被遮挡/隐藏、别的 App 抢焦点、无边框标题栏的 app-region 拖拽带、页面栈堆两个工作台、
  坐标重排点空、OOPIF/webview 盖住、连错了别人的 Electron。
  **`visibilityState: hidden` 不是故障信号**——这台机器上窗口本来常年 hidden（被终端盖着），通过的轮次同样是 hidden，
  别再拿它当线索（上一版这条记错了，害人查了一轮窗口可见性）。
  主因就是上面那条进程泄漏：修掉之后失败率从 **6/36 掉到 1/61**；剩下的极少数还没定位到 Chromium 内部机制。
  desktop-e2e 的处置是**一次诚实的恢复**：整页重载让浏览器重建这一页的输入路径，然后仍旧用真实鼠标重点一次，
  恢复不了就报死并在错误里点明"是输入通道坏了不是界面问题"。
  **不要退回"页面内 `dispatchEvent` 伪造点击"兜底**（#390 曾这么干，已撤）——桌面端就这一条真实输入的覆盖，
  换成假的之后这一步就再也挡不住真的界面回归了。
  **根因已坐实（2026-08-18）**：是"这一页没被当成有焦点/活动窗口"。把加固摘掉重跑 120 轮，
  复现 3 轮、共 8 次判定，**每一次都是**：掉事件时 press=false → 开
  `Emulation.setFocusEmulationEnabled` → press=true。按下与按键跟焦点绑定、`mouseMoved` 不绑定，
  所以现象才是"移动送得到、点击和按键送不到"。
  解药两层，都在 `_lib/electron-cdp.mjs`：起 Electron 带
  `--disable-backgrounding-occluded-windows` / `--disable-renderer-backgrounding` /
  `--disable-background-timer-throttling`（`puppeteer.launch` 自带，手动 spawn 再 connect 就没有），
  连上页面后开 `hardenPageInput`（即焦点仿真），导航多的地方用 `reassertFocusEmulation` 再压一次
  （emulation 挂在 CDP 会话上，会话/导航都可能把它丢掉）。
  **同一批现场还证伪了「整页重载」这个解药**：重载三次按下通道仍然是死的，那一轮照红不误。
  所以恢复路径必须先补焦点仿真，重载只能算兜底的兜底——别再把重载当主力。

- **meeting-e2e 曾在 master 上整轮红（10/10），四个坑叠在一起**（2026-08-17 修复）：
  ① 用的还是"写 `checkba_last_project_id` → reload 直达工作台"的老配方，而 2026-08 起启动一律落
  项目列表页，于是第一步就卡死——改成 desktop-e2e 同款"轮询到真进工作台为止，在列表上就点卡片"；
  ② 拿「资源管理器」当工作台就绪判据，但**左栏面板是记住上次的**，上一轮把它切到「会议录音」之后
  下一轮永远等不到这四个字——改用 `.page-project-overview` 这种与面板无关的根节点；
  ③ rail 按钮是**开关**，面板已经开着时再点一下正好关上——改成"确保打开"而不是"点一下"；
  ④ **没钉死界面语言**，Electron 常带 `--lang=en-GB`，rail 会变成 "Meeting Recording"，
  中文选择器全失配（现象很像"skill 没启用"，极易误判）——补上 `awd_app_language=zh-CN`。
  ②③是同一类：**上一轮留下的状态破坏下一轮**，写这类断言前先问"上一轮跑完留下了什么"。
- **app-e2e 的两类陈旧断言**（2026-08-18 扫出来的，各红过一轮）：
  ① **左栏 rail 的标题直接抄了 i18n 文案**，而文案会改——「EasyVoice」在 #389 改成「语音合成」，
  J6 那条 `[title="EasyVoice"]` 就一直等不到。改文案时记得回看 J6 那一行；标题的真源是
  `src/config/leftSidebarPlugins.js` 的 `label`（i18n 键 `config.sidebar.*`）。
  ② **界面上出现 ≠ 已经落库**：概览档案那步 `waitText` 一过就立刻读 `/profile` 接口，
  可 @blur 触发的 PUT 还在飞、ProfileHeader 又是乐观更新的，于是间歇性读到一条全 null 的记录。
  凡是"改了界面再回接口对账"的断言，都要轮询到后端认账为止，别赌那一拍。
  （这一类和 #403「不再赌时间」是同一个毛病，写新断言前先问一句：我等的是渲染还是持久化？）
- 分支保护拦 gh pr merge 时权宜 = 用户网页点 Bypass rules and merge（白名单未配成）。
- `docs/` 在 .gitignore，入库要 `git add -f`。
- **改跨类 `public static final String` 常量的值必须 `mvn clean test`**：这类常量在编译期被内联进
  调用方的字节码，maven 增量编译不重编未改动的测试类，于是出现「源码一致、字节码不一致」的假失败
  （2026-08 改 Ollama 设置键时踩过：测试报 expected qwen3:14b but was qwen3-vl:8b，`javap` 确认
  测试类里内联的还是老键名）。CI 是 clean build 所以只坑本地。
- **删「零引用」的 Spring bean 前先想有没有 `@ConditionalOnMissingBean` 角色**：
  `service/ai/DynamicContentRetriever` 全仓 grep 不到调用方，但它靠存在本身压住了 langchain4j
  `RagAutoConfig`——删掉后自动配置自己造 `contentRetriever`，而本项目有两个
  `EmbeddingStore<TextSegment>`，全部 `@SpringBootTest` 一起 NoUniqueBeanDefinition 起不来
  （2026-08 清理孤儿类时踩过，16 个 context 加载失败）。`DesktopContextSmokeTest` 是这条的护栏。
- **`@Table` 的 `indexes` / `uniqueConstraints` 里必须写 snake_case 物理列名**：这两处不参与
  PhysicalNamingStrategy 的驼峰转换（本仓没配自定义策略，用 Spring Boot 默认的
  CamelCaseToUnderscoresNamingStrategy，字段 `projectId` 落成列 `project_id`）。
  Hibernate 6.4.4 会先把它当**逻辑名**查一次，所以写驼峰往往侥幸能解析、索引其实建对了；
  但一旦查不到就把字符串原样塞进 DDL，而 `@UniqueConstraint` 是内联在 `create table` 里的，
  整条建表语句失败、Hibernate 只打一行 WARN（`GenerationTarget encountered exception`）
  就继续启动——**表根本没建出来，启动看着正常，第一次查询才炸**。
  `EntityIndexColumnNamingTest` 是这条的护栏：反射扫全部 `@Entity`，对着 H2 的
  INFORMATION_SCHEMA 逐条对账列名与索引，新实体写错驼峰会自动红。

## 验证（改本领域自身时）

- 改 workflow：推 PR 触发 windows 矩阵先验；mac 变更合并后用手动 workflow_dispatch 验证。
- 改构建脚本：本地跑对应脚本 + desktop `npm test`（pysvc-runtime 有覆盖）。
