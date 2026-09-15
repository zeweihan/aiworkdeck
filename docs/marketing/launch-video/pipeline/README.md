# 发布视频录屏流水线（dev-board #59）

链路验证 + 一段样片。目标不是产出全部八幕正片，是证明「启动产品 → 程序化驱动 UI →
带虚拟光标的流畅录屏 → 输出 mp4」这条链路可行，并把它做成可按幕复用的 harness。

自包含 node 项目，独立 `package.json`，不改产品代码（唯一例外见下面「踩过的坑」第一条，
且那处改动不在这个目录里、也不随这次提交）。

## 结论：走的是哪条路线

**dev Electron + CDP（首选路线，验证通过）。** 没有降级到 dev H5。

起一个真实的、带无边框标题栏的 dev Electron 壳（`AIWORKDECK_DESKTOP_DEV=1`），
`--remote-debugging-port` 开 CDP，`puppeteer-core` 用 `puppeteer.connect()` 连上去驱动
（不是 `puppeteer.launch()`）。渲染层通过 `CHECKBA_BACKEND_PORT` 环境变量指向隔离后端——
这是走 Electron 壳换后端唯一生效的旋钮，`VITE_API_BASE_URL` 对它不起作用（见
`.claude/agents/eng-infra.md` 端口体系一节）。

## 一次跑通的产物（供参照）

- 26 个 CDP 截屏关键帧（真实间隔从 33ms 到 3.6s 不等）→ ffmpeg 按每帧真实时长展开成
  25.9s 恒定 30fps 的 mp4，778 输出帧，H.264，1920x1080，约 600KB。
- 空闲段（`pause()` 静场）只占一个关键帧、靠 CFR 重采样复制填满时长；光标移动段
  实测帧间隔约 33ms（接近我们设定的每步 16ms 目标 × CDP round-trip 开销），movement
  在导出视频里是连续的，不是跳变。

## 全链路

```
startIsolatedBackend()   独立 H2 / user.home / 端口(9895)，起 desktop profile jar，
                         播一份 trial 票据解锁，POST /api/admin/wizard 用 OLLAMA 档
                         走完首启向导（否则卡在向导页，见「踩过的坑」）
        │
seedDemoProject()        REST 建项目「林芳劳动争议」→ 建文件夹「当事人材料」→
                         case-materials/*.md 用 macOS textutil 转 docx → 逐个上传
        │
startDevServer()         frontend/ 下 `npx uni --port 5183`
        │
launchElectron()          desktop/ 下 spawn 'npx electron .'，带 CDP 端口 + 输入焦点
                         仿真三件套（照抄 frontend/tests/_lib/electron-cdp.mjs 的配方）
        │
installCursor()           注入虚拟光标 DOM + 章节标题卡覆盖层
        │
startRecording()          Page.startScreencast 收帧
        │
scene(stage, ctx)         一幕 = 一个 async 函数，见 src/scenes/sample.mjs
        │
recording.stop()          concat demuxer（每帧带 duration）+ ffmpeg -fps_mode cfr
                         合成恒定 30fps mp4
```

`run.mjs sample` 是唯一入口：`node run.mjs <scene 名>`，scene 名对应
`src/scenes/<name>.mjs` 里导出的 `<name>Scene` 函数。

## 目录

```
pipeline/
  run.mjs                 编排入口
  src/
    config.mjs             路径/端口常量
    backend.mjs             起隔离桌面后端 + 首启向导初始化
    demo-project.mjs        REST 建演示项目 + md→docx 转换 + 上传
    electron.mjs             起 dev Electron + CDP 连接（自带一份 electron-cdp 配方）
    cursor.mjs               虚拟光标注入 + 缓动移动/点击/打字 + 章节标题卡
    recorder.mjs             CDP 截屏收帧 + ffmpeg 合成
    stage.mjs                场景脚本用的高层 API（选择器/文本匹配 → 移动/点击）
    scenes/
      sample.mjs             样片场景（本卡交付的那一段）
  out/                     产物目录，gitignore 掉；sample.mp4 落在 out/sample/
```

## 怎么跑

前置：
1. `backend/` 下用 JDK 21 跑过 `mvn -DskipTests package`（本机 mvn 必须 JDK 21，
   系统默认 JDK 25 会 SIGBUS）；
2. `frontend/`、`desktop/` 下跑过 `npm install`；
3. `frontend/dist/zetaoffice/` 下要有完整引擎（`lowa/` 目录 + `cjk.ttc`，见下面
   「LOWA 引擎从哪来」）；
4. 本机装了 `ffmpeg`（`which ffmpeg` 能找到）；
5. 这个目录本身跑过 `npm install`（装 `puppeteer-core`）。

```bash
cd docs/marketing/launch-video/pipeline
npm install
node run.mjs sample
```

产物在 `out/sample/sample.mp4`；中间帧序列在 `out/sample/frames/`（可用于排障，
不入库）；隔离后端的 home 目录与日志在 `out/backend-home-*`。

## LOWA 引擎从哪来（这个 harness 不负责重新构建引擎）

`frontend/dist/zetaoffice/` 不在仓库里，是构建产物。跑通样片需要里面有完整的
`lowa/`（soffice.wasm/soffice.data/soffice.js/.encodings.json，约 240MB）+ `cjk.ttc`
字体——这条流水线**没有**去跑 `fetch-lowa-assets.js` 联网下载或
`desktop/lowa-build/mega-build.sh` 从源码重建，而是照 `.claude/agents/eng-infra.md`
记录过的既有配方：从一个已经构建过的兄弟 worktree 借 `lowa/` + `cjk.ttc`（这两个是
纯引擎二进制资产，不随源码改动而过期），glue 代码（`office_thread.js`/`zeta.js`/
`editor.html`/`assets/`）本树跑 `npm run build:zetaoffice` 现生成（这两个文件是
`src/zetaoffice/public/` 的构建产物，跟着源码走，借别的树的会跑出「旧引擎行为」）：

```bash
cd frontend
mv dist/zetaoffice/lowa /tmp/borrow-lowa && mv dist/zetaoffice/cjk.ttc /tmp/borrow-cjk.ttc
npm run build:zetaoffice          # emptyOutDir，必须先把上面两样搬走
mv /tmp/borrow-lowa dist/zetaoffice/lowa && mv /tmp/borrow-cjk.ttc dist/zetaoffice/cjk.ttc
```

如果没有现成的兄弟 worktree 可借，退路是跑 `desktop/scripts/fetch-lowa-assets.js`
联网下载官方引擎（会缺 zh-CN 语言包），或用维护者自建的 `LOWA_BASE_URL` 自托管地址。
这个 harness 本身不判断、不下载——它假定这份资产已经在位，跟正常本地开发的前置条件
一致。

## 端口

不用 9696（维护者真实桌面后端）、不用 9797/5173/5174（既有 e2e 套件的约定端口，
并行会话可能正占着）。这条流水线自己的端口，可用环境变量覆盖：

| 用途 | 默认端口 | 覆盖变量 |
|---|---|---|
| 隔离后端 | 9895 | `LAUNCH_VIDEO_BACKEND_PORT` |
| dev H5 | 5183 | `LAUNCH_VIDEO_DEVSERVER_PORT` |
| Electron CDP | 现挑一个从 9333 起的空闲端口 | `LAUNCH_VIDEO_CDP_PORT` |

杀进程一律按 pid（`child.kill()`/`process.kill(-pid)`），不按 jar/进程名 pkill——
遵照 CLAUDE.md 的并行会话红线，绝不会误杀别的会话或维护者的真实后端。

## 场景抽象怎么写新的一幕

一幕 = 一个 `async function xxxScene(stage, ctx)`，放进 `src/scenes/xxx.mjs`，`ctx` 是
`seedDemoProject()` 的返回值（`{projectId, folderId, files}`）。`stage`（`src/stage.mjs`）
提供的是语义化的动作，不用碰 CDP/puppeteer 细节：

- `stage.click(selector)` / `stage.rightClick(selector)` —— 选择器命中元素的中心点，
  虚拟光标缓动移过去再点击（真实鼠标事件 + 视觉光标 + 点击涟漪三件同步）；
- `stage.clickText(selector, text)` —— 选择器范围内按文本包含匹配（上下文菜单项这类
  没有稳定 class、只能按文案定位的场景）；
- `stage.clickNth(selector, index)` —— 同类元素里按序号取（颜色色块这类没有文案的）；
- `stage.type(text)` / `stage.key(key)` —— 打字（带自然延迟）/ 按键；
- `stage.wait(ms)` —— 技术性等待（等接口/渲染）；`stage.pause(ms)` —— 语义化静场
  （给旁白留白，不是在等什么完成）；
- `stage.titleCard(text)` —— 章节小标题卡（淡入停留淡出），对应脚本「每幕开头 2 秒
  章节小标题卡」；
- `run.mjs` 负责这一幕的起止（起环境→装光标→开录→跑 scene 函数→停录→合成 mp4→
  收尾），场景脚本本身只管步骤序列。

选择器优先钉在真实 id 上（比如 `.tree-item[data-file-id="${folderId}"]`），这些 id
来自 `seedDemoProject()` 用 REST 建数据时的返回值，不是靠文本/位置去猜——数据是脚本
自己灌的，id 是确定的。

## 踩过的坑

1. **首启向导会挡住项目列表页。** 全新 H2 起来的后端即便已解锁（trial 票据），
   `launch → wizard`（见 `.claude/agents/sidebar-shell.md` 路由术语表）还是会把还没
   选过 AI 供应商的安装拦在向导页——第一轮录制整段视频卡在「欢迎使用 AI WorkDeck」
   的向导屏，没有一帧到过项目列表。解法：`startIsolatedBackend()` 就绪后立即
   `POST /api/admin/wizard {"ai":{"activeProvider":"OLLAMA"}}`——选 OLLAMA 档是因为它
   不需要任何真实凭据、也不触发跨境同意闸门（那道闸只挡 AWD_CLOUD 平台通道），场景
   不需要 AI 真的能聊天，只需要「已初始化」这一件事成立。

2. **ffmpeg 7.x 上 `-vsync vfr` 和 `-r 30` 同时给会直接拒绝开工**（"This is
   contradictory"）。concat demuxer 按每帧的 `duration` 摆好了变间隔的输入时间轴，
   要重采样成恒定 30fps 输出，正确组合是新 flag `-fps_mode cfr` + `-r 30`（`-vsync`
   已弃用，且 `vfr` 语义上是"保留可变帧率"，跟"我要恒定 30fps 输出"矛盾）。

3. **LOWA 引擎不在这次改动范围内、也不该现场重新构建。** 见上面单独一节——这是
   本地开发的常规前置条件（借 `lowa/`+字体、glue 本树构建），harness 本身不重新发明
   这一套。

4. **local-mode 后端对任何请求都解析成本机用户**，`seedDemoProject()` 里的 REST 调用
   不需要登录/会话头——这是桌面单机版的既有设计（`security.local-mode=true`），不是
   这条流水线绕过了什么鉴权。

## 没做的事（明确超出本卡范围）

- 只有样片这一段（项目列表→打开项目→文件树展开→打开材料→右键管理标签），
  不是脚本 v2 的全部八幕；
- 没有做旁白配音合成、没有做字幕、没有做分屏/转场剪辑——录屏产物是给人工剪辑用的
  素材，不是成片；
- 没有验证「幕三 摸底」用到的企查查演示桩（`ai.tools.enterprise-demo-fixtures`，
  这个开关在另一处未提交的改动里，不属于这条流水线）。
