---
name: feedback-optimizer
description: 用户反馈闭环领域。任务涉及右下角反馈浮窗、反馈落库与附件、优化者（Optimizer Agent）分诊/开 PR/发邮件、admin「用户反馈」看板时，先读本文档再动代码。
---

# 用户反馈闭环 领域地图

职责边界：用户报问题 → 落库 → 优化者分诊 → 开 PR / 发邮件 这一条链。
不含 AI 对话编排本身（ai-chat）、不含截图 IPC 的实现（utility-tools）、
不含 admin 页的整体结构（sidebar-shell）。完整设计见 `docs/FEEDBACK_OPTIMIZER_DESIGN.md`。

## 关键文件

**采集（前端）**
- `frontend/src/components/FeedbackWidget.vue` — 浮窗本体（浏览器/桌面通用；截图仅桌面）
- `frontend/src/utils/feedbackWidget.js` — 在 `<body>` 下单独 `createApp` 挂载，全应用一个实例
- `frontend/src/utils/overlayState.js` — 页面树之外的浮层开关（模块级 ref）
- `frontend/src/utils/errorBuffer.js` — 最近 20 条前端报错环形缓冲，`main.js` 的全局错误处理器写入
- `frontend/src/App.vue` `onLaunch` — 挂载点；`frontend/src/services/api.js` — `submitFeedback` 等

**存储与接口（后端 `com.checkba`）**
- `model/entity/UserFeedback` / `FeedbackAttachment`，`repository/UserFeedbackRepository` 等
- `service/feedback/FeedbackService` — 一次 multipart 落库 + 落盘；`FeedbackContextCollector` — 版本/环境/日志尾巴；
  `VoiceTranscriptionService` — 可选 OpenAI 兼容转写
- `controller/FeedbackController` — `/api/feedback`（提交任何用户；查看要管理员）

**云端收件箱（收各安装上传的反馈）**
- `controller/FeedbackIngestController` — `/api/feedback/ingest`（公开+配额）、
  `/pending`、`/{id}/resolution`、`/inbox/status`（`X-Optimizer-Token`）
- `service/feedback/FeedbackIngestGuard` — 配额与体积闸；`FeedbackService.ingest` — 幂等收件
- `service/feedback/FeedbackUploadService` — 桌面端异步补传（本地先落库，永不删）

**优化者（后端 `service/optimizer/`）**
- `OptimizerProperties`（前缀 `optimizer`，默认 enabled=false，source=local）
- `OptimizerFeedbackSource` + `LocalFeedbackSource` / `RemoteFeedbackSource` / `OptimizerSourceConfig`
  —— 优化者读本地库还是云端收件箱，上层无感
- `FeedbackTriageService` — 分诊；`OptimizerCodeFixRunner` — worktree + 编码 Agent + PR；
  `OptimizerMailer` — 只发不收；`OptimizerAgentService` — 调度与分流；`ProcessRunner` — 子进程出口
- **邮件出口走 `service/mail/MailRouter`（`mail.domestic.*` / `mail.global.*`），不是 `spring.mail.*`**
  （2026-08-08 PR#320 改）。多收件人**逐个分别发**：他们可能分属不同通道（维护者的 Gmail 与同事的
  QQ 邮箱走的不是同一条），塞进同一封信只能挑一条，另一半到达率白丢。**发件人由通道决定**，
  `optimizer.mail.from` 已删——两条通道发信域名不同，硬写 from 会让 SPF 当场判失败。
- `controller/OptimizerController` — `/api/optimizer/run|status`（管理员，run 是异步）
- 维护者机器上的常驻配方：`deploy/optimizer/`（run 脚本 + launchd plist + 搬机器步骤）

**看板**：`frontend/src/components/admin/AdminPane.vue` 的 `activeNav === 'feedback'` 分区
（`pages/admin/admin.vue` 只是薄壳）——这是桌面端本地库的入口。

**反馈控制台（云端收件箱的浏览器入口，2026-08-25 dev-board#151）**
- `backend/src/main/resources/static/feedback-console/index.html` — 自包含单页，
  jar 的 classpath:/static/ 直接托管在 `/feedback-console/`；云端 nginx 有一条
  location 反代给后端（`deploy/cloud/nginx-addin.conf.example`）。
- 由来：h5 客户端（含 admin 看板）2026-08-19 从 addin.aiworkdeck.com 退役后，
  云端收件箱在浏览器里没有任何入口，优化者邮件里的裸附件 API 地址点开是 403 死胡同。
- 优化者通知里的直达链接来自 `OptimizerFeedbackSource.consoleRef(fb)`（default null）：
  Remote 来源返回 `<baseUrl>/feedback-console/?fb=<id>`，Local 来源保持 null
  （桌面端自带 admin 看板），正文里就不出现这一节。
- 直达地址可用 `optimizer.remote.console-url`（env `OPTIMIZER_REMOTE_CONSOLE_URL`，
  `{id}` 占位）改指官网 admin 的「用户反馈」分区（dev-board#152）：
  `https://www.aiworkdeck.com/zh/admin?tab=feedback&fb={id}`——维护者只登录官网后台一处。
- 官网侧（aiworkdeck_website 仓）：`app/[lang]/admin/FeedbackInbox.tsx` 分区 +
  `/api/admin/feedback/*` 三条服务端代理（admin cookie 鉴权，取件密钥只在服务端）；
  官网实例 env 需配 `AWD_FEEDBACK_INBOX_TOKEN`（= 收件箱 FEEDBACK_OPTIMIZER_TOKEN）。
  为此 `GET /api/feedback` 列表/详情也认 X-Optimizer-Token（只读，密钥本就能取
  全部待办与附件，未升格信任级；没配密钥恒拒绝）。
- 页面安全约定：用户可控文本一律 textContent 渲染；附件用 fetch + `X-Session-Id`
  头取 blob 再喂给 `<img>`/`<audio>`，凭据不进 URL（URL 里的 token 会落 nginx access log）。
  登录支持 4005 二次验证（totp/sms/mail）。会话键与 h5 同名 `checkba_session_id`。

## 不变式（改之前先读）

1. **反馈行只改状态，永不删除**，附件同理。用户报过的问题永远留痕。
2. **优化者永不合并**：只出现 `gh pr create`，没有任何 merge 路径；
   工作分支名必须以前缀开头且不等于 baseBranch，否则在发命令之前就失败。
3. **受保护路径（`.github/`、`deploy/`、`desktop/scripts/`、`.git/`）被改就不开 PR**——
   反馈正文是用户可控输入，是 prompt 注入入口。新增受保护路径改
   `OptimizerCodeFixRunner.PROTECTED_PREFIXES` 并同步单测。
4. **没办成不许标成已处理**：邮件出口不可用/分诊失败一律记 `lastError`，
   没到 `maxAttempts` 退回 NEW，到了停在 FAILED。
5. **分诊解析失败降级 UNCLEAR 转人，不得判 NOISE**；只有语音无转写、只有截图无文字
   两种情况在调模型之前就直接转人。
6. **尝试次数在分诊之前就加**：分诊本身挂掉也要计数，否则一条永远失败的反馈每轮都被
   重新捞出来，永远到不了上限（白烧 token）。
7. **优化者与收件箱不同机**：收件箱在公网收用户可控文本，优化者带着推送凭据与编码 Agent。
   合一只在单机自用（`optimizer.source=local`）时成立。
8. **`feedback.optimizer-token` 没配 = 取件与回执整组 403**，不留「未配置即放行」。
   `optimizer.source=remote` 缺 url/token **直接拒绝启动**，不静默退回 local
   （退回的表现是「天天跑、天天零条」，比起不来更难发现）。
9. **上传只置标记不删本地行**；`429` 不算上传成功（标了这条就永远消失了）。
   云端幂等键是 `installId + clientRef`。

## 已知地雷

- **uni H5 编译器会替换标签**：`button`/`input`/`textarea`/`audio` 会变成 uni 组件，
  `<audio>` 直接编译失败（uni-h5 未导出 `Audio`），uni 的 `Input` 不支持 `type="file"`。
  浮窗因此用 `div[role=button]` / `<component :is="'textarea'">` / JS 现建 file input /
  `new window.Audio()`。**别顺手换回原生标签。**
- **浮窗不自己调 `setViewsVisible`**：会和 project-overview 的 `desktopOverlayActive`
  watcher 抢 BrowserView 显隐。只置 `overlayState.js` 的 ref。
- **macOS 麦克风**：硬化运行时下必须有 `com.apple.security.device.audio-input` 权限
  （`desktop/build/entitlements.mac.plist`）+ `NSMicrophoneUsageDescription`
  （`desktop/package.json` `build.mac.extendInfo`）。dev 未签名时是好的，**只有装包后才暴露**。
- **e2e 里 `waitForFunction` 要用定时轮询**：默认 rAF 轮询在窗口失焦时被 Chromium 停掉，
  而框选覆盖窗一定会抢焦点 → 条件等待集体假超时（现象是「截图没出来」，实际早就出来了）。
- 多构造器的 Spring bean（`VoiceTranscriptionService`/`FeedbackTriageService`）
  必须给公开构造器打 `@Autowired`，否则整个上下文起不来。

## 验证

- `cd backend && mvn test`（JDK 21）：五个 *Test 覆盖落库、分诊规则、安全护栏、分流、邮件
- `cd frontend && npm run test:feedback-e2e`：dev Electron + CDP 真截图真录音真提交真回读
- 改浮窗样式/位置后顺带 `npm run check:emits`
