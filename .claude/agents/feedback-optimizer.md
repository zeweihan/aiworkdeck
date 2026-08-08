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

**优化者（后端 `service/optimizer/`）**
- `OptimizerProperties`（前缀 `optimizer`，默认 enabled=false）
- `FeedbackTriageService` — 分诊；`OptimizerCodeFixRunner` — worktree + 编码 Agent + PR；
  `OptimizerMailer` — 只发不收；`OptimizerAgentService` — 调度与分流；`ProcessRunner` — 子进程出口
- `controller/OptimizerController` — `/api/optimizer/run|status`（管理员，run 是异步）

**看板**：`frontend/src/pages/admin/admin.vue` 的 `activeNav === 'feedback'` 分区

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
