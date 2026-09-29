# 音视频播放器：统一控制条、自动字幕、流式播放（设计规格）

日期：2026-09-29。看板：dev-board#1023（控制条）、#1024（自动字幕）、#1025（流式播放）。
产品线 `p/aiworkdeck:文件与项目`。领域文档：`.claude/agents/utility-tools.md`（文件预览）、
`.claude/agents/ai-chat.md` 无关；会议转写管线见 `service/meeting/*`。

## 0. 决策摘要

- **自建对齐一线播放器，不集成第三方播放器**（video.js / plyr / artplayer 一律不引）。理由：uni-h5 编译器会替换 `<audio>`、`<video>` 事件不可靠，第三方接管 DOM 与之冲突；第三方深色皮肤与「外壳保持浅色」红线冲突；安装包刚瘦身；字幕要接自家转写管线，播放器库帮不上。
- **字幕触发成本**：有转写稿就自动显示；没有稿时 CC 按钮呈「生成字幕」态，用户点一次才提交转写、才扣费。绝不在打开文件时自动提交转写。
- **三条一起交付**：控制条 + 字幕（含视频接入转写）+ 流式播放。内部分工可并行，对外一次交终态。
- **UI 是重点**：深浅色两套都要成立；交付前维护者要看到两种主题下的真实截图。

## 1. 现状（改动前）

`frontend/src/components/FilePreview.vue`：

| | 视频 | 音频 |
|---|---|---|
| 实现 | 裸 `<video controls>`（Chromium 原生深灰控件） | 自绘卡片播放器，`new window.Audio()` 驱动 |
| 静音 / 倍速 | 有，藏在原生溢出菜单 | 有（循环点 1→1.25→1.5→2→0.75） |
| 字幕 / 快捷键 | 无 | 无 |
| 取源 | XHR 全量下成 blob 再播；后端 `GET /api/files/{id}/download` 不支持 Range | 同左 |
| EvidenceLink 定位 | `locator` prop → seek 到 startMs 并暂停，带定位时不 autoplay，时间标记条 + 「从这里播放」 | 同左，轨道上另有刻度 |

转写管线（`service/meeting/`）：`MeetingRecording.transcriptJson` 存分段 `{speaker,start,end,text}`（毫秒），`speakerNames` 是 id→名字的 JSON；三档（platform 听悟 / byok 听悟 / local faster-whisper）；`registerExisting(projectId, fileId)` 把已有文件注册成会议记录，只认 `AUDIO_EXTENSIONS`；`MeetingAudioTranscoder.toMp3` 用 javacv FFmpeg 转 16kHz 单声道 mp3，三档共用 `transcodeWithTimeout`。听悟官方支持 mp4/mov/mkv/webm/avi 等视频（单文件 2GB、12 小时）。

## 2. 组件架构（前端）

新增目录 `frontend/src/components/media/` 与 `frontend/src/utils/media/`，FilePreview 的视频/音频两个分支都换成 `<MediaPlayer>`。

```
FilePreview.vue
  └─ MediaPlayer.vue        kind: 'video'|'audio'；拥有媒体元素、播放状态、字幕状态机、快捷键
       ├─ MediaControls.vue  控制条（两种皮：overlay / card），纯展示 + emit
       ├─ MediaTranscript.vue 逐字稿面板（分段列表、跟随、点击跳转）
       └─ (内联) 字幕叠层、中央播放徽标、EvidenceLink 时间标记
utils/media/
  ├─ mediaSource.js     直链 URL 组装（token 追加、日志脱敏）、回退判定
  ├─ transcriptCues.js  segments → cue 查找（二分）、说话人命名、长句切分
  ├─ captionState.js    会议记录 status → CC 按钮五态
  └─ mediaShortcuts.js  keydown → 播放器动作映射
```

**媒体元素一律命令式创建**：`document.createElement('video')` / `new Audio()`，挂进 `<view ref="stage">`。不再在模板里写 `<video>`，彻底绕开 uni 编译产物（事件名不可靠、ref 可能是组件实例）。元素实例不进 `data`（Vue 代理会包住媒体元素）。视频元素属性：`playsinline`、`preload="metadata"`、`disablepictureinpicture=false`、不带 `controls`。

**MediaPlayer 对外契约**（FilePreview 调用）：

- props：`kind`、`file`（需要 `id`/`wpsFileId`/`name`/`fileType`/`projectId`）、`projectId`（file 上没有时的兜底）、`locatorSec`（Number|null，来自 `parseMediaStartSec`）。
- emits：`locator-consumed`（seek 落地后一次）、`error`。
- 既有契约必须原样保留：带 locator 打开 → seek 到该秒并**暂停**、不 autoplay；不带 locator 的视频保持现状（自动播放）、音频不自动播放；时间标记条可关；「从这里播放」从标记处起播；音频轨道上的刻度。

## 3. 视觉与交互规格

尺寸一律 px（桌面固定形制，与现有音频卡片注释一致）。所有颜色走 `--awd-*` 令牌，唯一例外是**压在视频画面上的叠层**（见 3.1 皮肤规则）。不新增任何 emoji、不引图标字体，图标沿用 `config/icons.js` 的 SVG path 体系，新增 path 一律 24 viewBox、1.7~2 描边、圆角端点。

### 3.1 两套皮肤，一个组件

`MediaControls` 通过根 class 切皮，内部只消费组件局部变量：

```
.mp--overlay   /* 视频：压在画面上，固定字面量，不随主题翻转 */
  --mp-fg: rgba(255,255,255,.92)   --mp-fg-2: rgba(255,255,255,.64)
  --mp-hover-bg: rgba(255,255,255,.12)
  --mp-rail: rgba(255,255,255,.28) --mp-buffered: rgba(255,255,255,.45)
  --mp-fill: #89A8A0 (竹月青，深底上是它的主场)  --mp-knob: #FFFFFF
  --mp-mark: #D7C5A1 (浅茶金)
  --mp-pop-bg: rgba(28,26,22,.96)  --mp-pop-fg: rgba(255,255,255,.92)
.mp--card      /* 音频：普通内容面，全部令牌 */
  --mp-fg: var(--awd-text)          --mp-fg-2: var(--awd-text-3)
  --mp-hover-bg: var(--awd-surface-2)
  --mp-rail: var(--awd-surface-3)   --mp-buffered: var(--awd-border-strong)
  --mp-fill: var(--awd-accent)      --mp-knob: var(--awd-accent)
  --mp-mark: var(--awd-gold-line)
  --mp-pop-bg: var(--awd-surface)   --mp-pop-fg: var(--awd-text)
```

视频画面之外的一切（逐字稿面板、音频卡片、弹层阴影）都用令牌，深浅色自动成立。叠层用字面量的理由与 OCR 浮层同：底图不参与主题。

### 3.2 视频（overlay 皮）

- **舞台**：`background: #0E0D0B`（暖近黑，与深色模式暖墨调同源，不用纯黑），`object-fit: contain`，占满预览区。
- **底部渐变遮罩** 96px：`linear-gradient(to top, rgba(10,9,7,.78), rgba(10,9,7,0))`。
- **控制条** 44px，左右 padding 12px，排布：`[播放] [时间 0:00 / 12:34] ……弹性…… [CC] [逐字稿] [倍速 1.0x] [音量] [画中画] [全屏]`。按钮 32×32 命中区、20px 图标、6px 圆角，hover `--mp-hover-bg`，焦点态 2px 竹月青描边（`:focus-visible`）。时间 12px `tabular-nums`。
- **进度轨** 在控制条上方、贴遮罩底：视觉 3px，命中区 18px；已缓冲段 `--mp-buffered`（读 `buffered` TimeRanges，多段都画）；已播 `--mp-fill`；hover 时轨道长到 5px、出现 12px 圆形滑块 `--mp-knob`；拖动中同样。EvidenceLink 时间标记：2px 竹茶金竖线 + 顶端 6px 菱形。悬停轨道显示时间气泡（12px，`--mp-pop-bg`）。
- **自动隐藏**：播放中指针 2.5s 无动作 → 遮罩+控制条 200ms 淡出、`cursor: none`；指针移动/键盘/焦点进入即恢复；暂停态常显；弹层（倍速菜单）打开时不隐藏。`prefers-reduced-motion` 下去掉过渡。
- **舞台点击**：单击切换播放；双击切换全屏。暂停时舞台中央显示 56px 圆形徽标（`rgba(10,9,7,.55)` 底 + 白色播放三角），播放中隐藏。
- **字幕叠层**：底部居中，`max-width: 80%`，字号 `clamp(13px, 2.2cqw, 22px)`（容器查询单位，舞台设 `container-type: inline-size`），行高 1.4，白字，底 `rgba(10,9,7,.72)`，padding 4px 10px，圆角 4px；默认离底 16px，控制条可见时抬到 60px（200ms）。多说话人时前缀「名字：」用竹月青。最多两行，超出按 3.5 节切分规则拆 cue。
- **倍速菜单**：点倍速按钮向上弹出，项 0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 1.75 / 2.0，当前项前打勾，宽 120px，项高 32px，`--mp-pop-bg`，阴影 `--awd-shadow-md`。按钮文案显示当前值如 `1.5x`。Esc 或点外部关闭。
- **音量**：按钮单击=静音切换；hover/焦点时右侧展开 72px 横向滑条（与现音频同形制）；图标三态（静音 / 低 / 高）。
- **全屏**：`playerRoot.requestFullscreen()`（不是 video 元素，保证自绘控件仍在）。全屏下布局同。
- **画中画**：`video.requestPictureInPicture()`；`document.pictureInPictureEnabled` 为假时不渲染此按钮。进入 PiP 后舞台显示一行提示「正在画中画播放」。
- **逐字稿面板（视频）**：点「逐字稿」按钮切换。播放器宽度 ≥ 760px 时作为右侧列 300px（舞台随之变窄），否则在舞台下方占 40% 高度。面板是内容面：`--awd-surface` 底、`--awd-border` 分隔线。仅字幕状态为「就绪」时渲染该按钮。

### 3.3 音频（card 皮）

- 保留现卡片骨架（最大宽 460px，头部图标+文件名，轨道，时间，控制行）。控制行改为与视频同一个 `MediaControls`（card 皮）：`[播放圆钮 40px accent] [时间] …… [CC] [倍速菜单] [音量]`；无全屏/画中画。
- 有逐字稿（就绪态、CC 开）时：卡片最大宽放到 640px，卡片下方（同宽列）渲染 `MediaTranscript`，占剩余高度、内部滚动。音频没有叠层字幕，「当前句」就是逐字稿里高亮的那一行；CC 按钮对音频的语义 = 显示/隐藏逐字稿。
- 轨道：视觉 4px、命中 16px（沿用），已缓冲段新增；定位刻度沿用 `--mp-mark`。

### 3.4 逐字稿面板 `MediaTranscript`

- 每段一行：`[时间 mm:ss] [说话人] 文本`。时间 11px `--awd-text-3` tabular；说话人 11px 加粗，颜色沿用会议面板的六色循环规则（同一说话人一致；从 `MeetingRecordingPanel` 抽公共工具，不复制两份）；文本 13px/1.6 `--awd-text`。
- 当前段：底 `--awd-accent-soft`、左侧 2px `--awd-accent` 竖条。hover 行 `--awd-surface-2`。点击行 → seek 到 start，不改变播放/暂停状态。
- **跟随**：播放中当前段滚到面板垂直居中（`scrollIntoView({block:'center'})`，reduced-motion 时不平滑）。用户手动滚动后 4s 内暂停跟随，面板底部浮出「回到当前」小药丸（`--awd-accent` 底反白字，`--awd-shadow-sm`），点击恢复跟随。
- 顶部一行状态：段数与总时长、说话人图例（≥2 人时）。
- 空态（EMPTY）：一行 `--awd-text-3` 文案「未识别到语音」。

### 3.5 字幕数据与 cue 规则（`transcriptCues.js`）

- 输入：`transcriptJson` 解析出的 `segments[]`（毫秒 start/end）+ `speakerNames`。输出 cue 数组按 start 排序；`cueAt(ms)` 二分查找（O(log n)，两小时会议上千段不能线性扫）。
- 说话人命名：`speakerNames[id]` 有则用，否则「说话人 N」（N 按首次出现顺序，从 1 起；en 为 `Speaker N`），与会议面板口径一致。叠层字幕只在**说话人数 ≥ 2** 时带前缀；逐字稿列表始终显示说话人。
- 长句切分：单段文本超过 42 个字符（CJK 计 1，其余按 0.5 折算）时，按 `。！？；，、` 与英文 `.!?;,` 切成子 cue，时长按字符数比例分配，任一子 cue 不短于 800ms（不足时与相邻合并）。
- 相邻段间隙 ≤ 300ms 视为连续，不闪烁。

### 3.6 CC 按钮五态（`captionState.js`）

由「该文件的会议记录」推导，会议记录按 `audioFileId` 反查（4.2 节接口）：

| 状态 | 判据 | 按钮呈现 | 点击 |
|---|---|---|---|
| `none` | 无会议记录 | CC 轮廓 + 右上小「+」角标；tooltip「生成字幕」 | `POST /api/meetings/projects/{pid}/register-file`；`submitted=true` → 转 `transcribing`；`configured=false` → toast 引导到「设置 - 会议转写」；后端 4xx 消息（含录音告知未确认）原文 toast |
| `pending` | 记录在但 status ∈ RECORDED（注册了没提交） | 同 `none` 呈现 | `POST /api/meetings/{id}/transcribe` |
| `transcribing` | TRANSCRIBING | CC + 环形转圈（1.2s/圈，reduced-motion 下换成三个点）；tooltip「字幕生成中」 | 无操作；每 4s 轮询 `GET /api/meetings/{id}`，离开组件即停 |
| `ready` | TRANSCRIBED 且 segments 非空 | CC 开关：开=实底 `--mp-fill` 白字；关=轮廓 | 切换显示；关掉时写本地存储 `awd_media_captions_off=1`，再开则删键（只存「关」，默认开） |
| `empty` | EMPTY | CC 轮廓 + 斜杠；tooltip「未识别到语音，点击重新生成」 | `POST /{id}/transcribe` |
| `failed` | FAILED | CC 轮廓 + 警示小点 `--awd-danger`；tooltip 显示 error 前 60 字 + 「点击重试」 | `POST /{id}/transcribe` |

轮询在文件切换、组件卸载、进入非 TRANSCRIBING 终态时停止。`transcribing` 期间不阻塞播放。

### 3.7 键盘快捷键（`mediaShortcuts.js`）

播放器根元素 `tabindex="0"`，`keydown` 只挂在根元素上，**不挂 window**（工作台已有捕获阶段的 IDE 键位处理器、AI 输入框与编辑器都不能被抢键）。点击播放器任意位置会把焦点落到根元素（最近可聚焦祖先）；FilePreview 渲染出媒体分支时，若 `document.activeElement` 是 body 或非输入元素，则主动 `focus({preventScroll:true})` 到根元素。事件目标是 input/textarea/contenteditable 时一律放行。

| 键 | 动作 |
|---|---|
| Space / K | 播放 / 暂停 |
| ← / → | 后退 / 前进 5s |
| J / L | 后退 / 前进 10s |
| ↑ / ↓ | 音量 ±10%（解除静音） |
| M | 静音切换 |
| F | 全屏切换（仅视频） |
| C | 字幕切换（仅 `ready`） |
| Shift+, / Shift+. （即 < >） | 倍速降一档 / 升一档 |
| Home / End | 跳到开头 / 结尾 |
| Esc | 关闭弹层；全屏由浏览器自己处理 |

命中的按键 `preventDefault` + `stopPropagation`；未命中的不拦。快捷键触发后控制条出现 1.5s（自动隐藏计时重置）。

### 3.8 可访问性与 i18n

- 所有按钮 `role="button"`、`tabindex="0"`、`aria-label` 与 `title`（tooltip 即 title），Enter/Space 触发；进度与音量轨 `role="slider"` + `aria-valuemin/max/now`、`aria-valuetext` 为 mm:ss。
- 新文案全部落 `frontend/src/locales/{zh-CN,en-US}/files.js` 的 `player.*` 与 `captions.*` 命名空间，两份同步（CI `check:locales`）。既有 `files.audioPlay/audioPause/audioRate/audioMute` 继续用；`fileTree.transcribe` 文案改为「语音转文字」/ `Transcribe speech`（右键项对视频也显示，见 4.3）。

## 4. 后端与数据

### 4.1 流式播放：`FileController.downloadFile` 支持 Range（#1025）

- 解析 `Range` 头（`HttpRange.parseRanges`）。有且资源长度可知（`resource.contentLength()` 成功）→ 取第一段，返回 **206** + `ResourceRegion`，头：`Accept-Ranges: bytes`、`Content-Range: bytes a-b/total`、`Content-Length`。范围越界 → **416** + `Content-Range: bytes */total`。多段请求只服务第一段（媒体元素不发多段）。
- 无 Range → 沿用 200，但**必须**带 `Accept-Ranges: bytes`（Chromium 据此决定能否按需拖动）。
- OSS 存储（`OssStorageService`）的 Resource 若不支持 `contentLength`，退回 200 全量，不报错。
- MIME 映射补齐：webm→video/webm、ogg→（按扩展）video/ogg 或 audio/ogg、mov→video/quicktime、mkv→video/x-matroska、avi→video/x-msvideo、m4a→audio/mp4、wav→audio/wav、flac→audio/flac、aac→audio/aac、opus→audio/ogg。
- `Content-Disposition` 仍为 attachment（媒体元素不受影响，浏览器直接下载行为不变）。
- 鉴权口径不变：`?token=` 或 `X-Session-Id`，二者其一。
- 测试 `FileControllerRangeTest`（MockMvc，本地存储临时文件）：Range 正常 206 + 正确字节切片；无 Range 200 + Accept-Ranges；越界 416；未授权 403 不因 Range 而变。

前端 `mediaSource.js`：`buildStreamUrl(fileId)` = `getFileDownloadUrl(fileId)` + `?token=<sessionId>`（无 sessionId 时不追加，local-mode 桌面后端本就忽略）。**任何日志里打印 URL 前必须 `redactToken()`**。媒体元素 `error` 事件 `code ∈ {MEDIA_ERR_NETWORK, MEDIA_ERR_SRC_NOT_SUPPORTED}` 且尚未回退过 → 走旧的 XHR blob 路径（保留 `loadMediaResource` 逻辑，搬进 MediaPlayer，带 `_mediaReqId` 竞态防护）。blob 回退成功后功能不变，只是无缓冲段显示。

### 4.2 字幕数据接口（#1024）

- 新增 `GET /api/meetings/projects/{projectId}/by-file/{fileId}` → `200 {meeting}` 或 `200 {meeting:null}`（不是 404，避免前端把「没稿」当错误），成员校验同 `list`。实现用既有 `MeetingRecordingService.findByAudioFile`。
- 前端 `services/api.js` 新增 `getMeetingByFile(projectId, fileId)`。
- 返回体沿用 `MeetingRecording` 实体序列化（含 `transcriptJson`、`speakerNames`、`status`、`error`、`progress`）。

### 4.3 视频文件接入转写（#1024）

- `MeetingRecordingService` 新增 `VIDEO_EXTENSIONS = {mp4, mov, mkv, avi, m4v, webm(已在音频表), wmv, flv, mpeg, mpg, 3gp}`，`isTranscribableMediaName(name)` = 音频 ∪ 视频；`registerExisting` 改用它，错误文案改「该文件不是音视频文件」/ `Not an audio or video file`。`isAudioFileName` 保留给既有调用方。
- `MeetingTranscriptionService`：三档提交前，若源文件扩展名 ∈ VIDEO，**必须**经 `transcodeWithTimeout` 抽音轨成 mp3（现在只有 webm/opus 路径在转；视频容器 `FFmpegFrameGrabber.grabSamples()` 同样可抽）。判定函数 `needsAudioExtraction(fileName)` 独立成包内静态方法便于单测。听悟直传前的大小校验按 mp3 产物算。
- 前端 `utils/audioAttachment.js` 新增 `VIDEO_EXTENSIONS` 与 `isTranscribableMedia(item)`；`tests/project-home/audio-attachment.test.mjs` 对拍两张表与 Java 常量（现有对拍机制扩一列）。`FileTree` 右键项对视频也显示（`isTranscribableMedia`），`ChatInterface` 的「未转写提示」范围扩到视频（视频进 AI 上下文同样只能靠转写稿）。
- 后端测试：`MeetingRecordingServiceTest` 补 mp4 可注册、文件夹拒绝；`MeetingTranscriptionServiceTest` 补 `needsAudioExtraction` 对 mp4/mov 为真、mp3 为假、webm 沿用原逻辑。

## 5. FilePreview 改造与既有测试

- 删除 FilePreview 内的自绘音频代码、`<video>` 模板、`loadMediaResource`（搬进 MediaPlayer）、`attachVideoLocator/teardownVideoLocator/seekToLocator/playFromMark` 的媒体部分；图片与 PDF 定位逻辑不动。`isVideo/isAudio` 判定保留，扩展名表改为从 `utils/audioAttachment.js` 与新增的 `utils/media/mediaTypes.js`（视频表）读取，别再散落三处。
- `tests/evidence/previewLocate.test.mjs` 与 `previewLocateRender.test.mjs` 中媒体相关用例迁到 `tests/media/`，断言的**契约不变**：带 locator → seek 到秒数且暂停、无 autoplay；时间标记渲染；「从这里播放」起播。
- `npm run check:emits` 必须过（MediaPlayer/MediaControls/MediaTranscript 之间的 emit 契约）。

## 6. 测试与验证矩阵

单元（`node --test`，新增脚本 `test:media` 并挂进 CI frontend job）：

- `tests/media/transcriptCues.test.mjs`：二分查找边界、说话人命名、42 字切分与 800ms 下限、300ms 间隙连续。
- `tests/media/captionState.test.mjs`：六种 status → 五态；`configured=false` 与 `submitted` 分支。
- `tests/media/mediaShortcuts.test.mjs`：每个键的动作；输入框内放行；音频下 F 无效。
- `tests/media/mediaSource.test.mjs`：token 追加/不追加；`redactToken`；回退判定只触发一次。
- `tests/media/mediaPlayerRender.test.mjs`（SSR 真渲染，harness 抄 `previewLocateRender`）：overlay/card 皮 class；CC 五态各自 class 与 i18n 键；逐字稿按钮只在 ready 渲染；PiP 按钮受能力开关控制；音频无全屏按钮。
- `tests/media/previewLocate.test.mjs`：迁移过来的定位契约。

后端：`mvn -B test`（JDK 21）含 4.1 / 4.3 新用例。

**真机走查（维护者亲自看截图，缺一不可）**：隔离 local-mode 后端（新 jar、独立 user.home/H2）+ `dev:h5` 带 `VITE_API_BASE_URL`；用 ffmpeg 生成 20s 测试 mp4（testsrc + 正弦音）与 mp3，上传到 QA 项目；在 `data-theme=light` 与 `dark` 两种主题下各截：视频暂停态（控制条可见）、播放中控制条已隐藏、倍速菜单展开、字幕就绪且叠层显示、逐字稿右栏展开、CC「生成字幕」态、音频卡片 + 逐字稿。字幕数据若本机无转写凭证则向隔离 H2 直插一条 TRANSCRIBED 记录（只为 UI 走查），并在汇报里标明视频真实转写是否实跑过。Range 用 `curl -H 'Range: bytes=0-99'` 验 206 与 `Content-Range`，并在浏览器网络面板确认媒体请求是 206 而非全量 200。

## 7. 明确不做

- 不做 HLS/DASH、不做多音轨/多字幕轨选择、不做字幕样式自定义、不做字幕导出（逐字稿导出已在会议面板）。
- 不改手机端与 Office/WPS 插件（它们没有媒体预览）。
- 不在打开文件时自动提交转写，不做「打开即扣费」。
- 不给 AI 新增工具（`meeting_get_transcript` 已能读视频转写稿，因为它按 meetingId 读）。
