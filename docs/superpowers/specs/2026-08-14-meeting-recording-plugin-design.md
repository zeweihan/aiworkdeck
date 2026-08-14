# 会议录音插件设计（2026-08-14）

一句话：工作台左栏新增「会议录音」面板——开会时一键录音，停止后自动转写（说话人分离），
一键生成律师级会议纪要 docx 落进项目文件。skill 走「内置 + 广场启停」形态（诉讼可视化模式）。

## 1. 选型结论（调研 2026-08-14）

| 决策 | 结论 | 理由 |
|---|---|---|
| ASR 供应商 | 阿里云通义听悟（Tingwu，OpenAPI 2023-09-30） | 唯一单 API 打包「转写+说话人分离+章节+摘要+待办」；分离免费、不强制预指定人数；0.6 元/小时 + 每项大模型能力 0.064 元/小时；90 天试用每日 2 小时免费；与项目现有阿里云生态（SMS/OCR/ECS）同账号体系 |
| 音频喂给听悟 | OSS 私有 bucket + 签名 URL（有效期 ≥3h） | 听悟只收公网 URL，不支持直传；OSS 是官方推荐路径 |
| 备选 | 腾讯云 ASR（分离 beta、需人数范围、无纪要）；百炼 Paraformer（同账号降级，纯转写） | 听悟开通受阻时的退路，v1 不实现 |
| 本地离线 ASR | 不做 | 体积翻倍（VoiceTranscriptionService 注释里的既有决策）；FunASR+CAM++ 留作远期 |
| 纪要生成 | 不用听悟的摘要当纪要，用本产品 AI 编排生成 | 听悟摘要作为素材注入；律师级纪要格式（议题/决议/待办/风险）由 skill prompt 控制，且可被用户追问修改 |
| 系统声音 loopback | 一期不做，仅麦克风 | Electron 30 无 macOS 原生支持，且引入「屏幕录制」TCC 重权限；线上会议外放场景麦克风可覆盖 |

## 2. 用户凭证清单（交付给维护者去开通）

1. 阿里云账号实名认证（已有则跳过）。
2. 开通通义听悟：https://tingwu.console.aliyun.com → 开通（后付费，90 天试用）→ 创建项目拿 **AppKey**。
3. RAM 子账号：授 `AliyunTingWuFullAccess` + OSS 目标 bucket 读写 → 拿 **AccessKeyId/AccessKeySecret**。
4. OSS：建私有 bucket（建议北京，与听悟同区）→ 记 **bucket 名 + endpoint**。
5. 五项凭证填入 桌面端 设置 → 会议转写：AccessKeyId、AccessKeySecret、听悟 AppKey、OSS bucket、OSS endpoint。

未配置凭证时：录音/存档全可用，转写按钮给引导文案，不阻塞。

## 3. UI 交互（按开会现场设计）

原则：录前零表单、录中零心智负担、录后零手工整理。

- **一键开录**：面板首屏一个主按钮「开始录音」，点击即录（默认标题「会议 MM-DD HH:mm」，事后可改）。
- **录音中**（面板态）：红点脉冲 + 计时 + 实时电平条（让用户确信收音正常）+ 暂停/继续 + 结束录音。
- **跨页面浮动指示器**：录音期间顶部居中浮动胶囊（红点 + 计时 + 停止按钮），feedbackWidget 同款 body 级挂载，切到任何页面都可见可停。录音引擎是模块级单例，页面跳转不断录。
- **边录边传**：MediaRecorder 分段 chunk 顺序追加上传（fileId 分片协议），崩溃/断电不丢已传音频；录音文件落项目文件树「会议录音/」目录。
- **停止后**：卡片进历史列表，凭证已配则自动提交转写，状态流转 已录音→转写中→已转写（面板轮询驱动）；失败可重试。
- **详情视图**：可编辑标题、JS Audio 播放器（uni-h5 禁 audio 标签）、说话人图例（说话人1/2/3 可改名为「张律师/对方代理人」，改名即时反映在全文与后续纪要）、逐段转写（说话人 chip + 时间戳 + 文本）、听悟章节/摘要/待办折叠区、操作：生成会议纪要（主）/导出转写稿/重新转写/删除。
- **生成纪要**：拼含触发词「会议纪要」的 kick-off prompt → `sendExternalPrompt` 走 AI 编排（股东大会同通路），AI 用 meeting_get_transcript 读稿 + write_docx 产出 docx 到项目文件。
- 全局同一时刻只允许一场录音；`reLaunch` 切项目不断录（单例在页面树外）。
- 浅色外壳、mr-* 类名前缀、stroke SVG 图标、全站禁 emoji。

## 4. 架构

### 前端
- `frontend/src/utils/meetingRecorder.js` — 模块级单例录音引擎：getUserMedia + MediaRecorder（pickAudioMime 抄 FeedbackWidget，停止必须 stop 轨道）、WebAudio AnalyserNode 电平、计时、chunk 队列顺序追加上传、状态响应式暴露（Vue ref，overlayState 模式）。
- `frontend/src/utils/recordingIndicator.js` + 内联组件 — body 级浮动指示器（原生 div，无 uni 组件）。
- `frontend/src/components/MeetingRecordingPanel.vue` — 面板本体。
- 注册三件套：`leftSidebarPlugins.js` 条目（key `meeting-recorder`，`requiresSkill: 'meeting-recorder'`）；`project-overview.vue` import/components/v-else-if 分支 + `handleMeetingMinutesStart` handler；api.js 一组 `/api/meetings` 函数。

### 后端
- 实体 `MeetingRecording`：projectId、title、status（RECORDING/RECORDED/TRANSCRIBING/TRANSCRIBED/FAILED）、audioFileId、durationMs、tingwuTaskId、transcriptJson（压缩段落 [{speaker,start,end,text}]）、speakerNames（JSON map）、summaryJson（章节/摘要/待办）、error、时间戳。
- `MeetingRecordingController` `/api/meetings`：POST `/projects/{pid}`（开录：建会议+建音频文件记录）、POST `/{id}/finish`、POST `/{id}/transcribe`、GET `/projects/{pid}`、GET `/{id}`（poll-on-read：TRANSCRIBING 且距上次 ≥10s 才查听悟）、PATCH `/{id}`（标题/说话人改名）、POST `/{id}/export`（转写稿落项目文件）、DELETE `/{id}`。鉴权与项目写权限对齐现有 controller。
- `service/meeting/TingwuClient`（接口）+ `TingwuClientImpl`（官方 SDK `com.aliyun:tingwu20230930`，Tea 核心依赖已随 OCR SDK 在树里）+ 测试桩。
- `service/meeting/MeetingOssUploader` — `aliyun-sdk-oss`：putObject + 4h 签名 URL；转写完成即删 OSS 对象（不留云端副本）。
- `service/meeting/MeetingTranscriptionService` — 编排：取音频 → FFmpeg（JavaCV，已在 pom）转码 16kHz 单声道（优先 mp3，编码器缺失退 wav）→ OSS 上传 → CreateTask（DiarizationEnabled、SpeakerCount=0、AutoChapters、Summarization、MeetingAssistance）→ poll-on-read 查 GetTaskInfo → 下载结果 JSON → 解析落库。
- 凭证：SystemSettingService 两级（`meeting.asr.*` / `meeting.oss.*`），admin 设置页加表单（bocha key 先例）。
- AI 工具 `MeetingTools`（AgentToolComponent，零编排器改动）：`meeting_list_recordings`、`meeting_get_transcript`（输出已含改名后说话人 + 摘要素材）。
- Skill `backend/skills/meeting-recorder/`：`enabled_by_default: false`，触发词（会议纪要/会议录音/生成纪要…），allowed_tools 逐个列全（两只 meeting 工具 + write_docx 等，写前对 ToolRegistry 真名）。

### 桌面壳
- `desktop/package.json` NSMicrophoneUsageDescription 文案改为覆盖「反馈语音留言与会议录音」。
- 确认 backend/skills 打包 glob 覆盖新 skill（AI_SKILLS_BUILTIN_DIR 链路）。

## 5. 数据流（正常路径）

开始录音 → POST /api/meetings/projects/{pid}（建会议+audioFile）→ MediaRecorder 每 5s chunk → 分片追加上传（X-File-Offset）→ 结束 → 最后一块带 X-File-Total-Size + POST finish → 凭证已配则自动 transcribe：转码 → OSS → CreateTask 拿 TaskId → 面板轮询 GET /{id} → COMPLETED 后下载 Transcription/AutoChapters/Summarization/MeetingAssistance 四份 JSON → 解析落库、删 OSS 对象 → 面板展示 → 用户点「生成纪要」→ kick-off prompt（触发词命中 skill）→ AI 读稿写 docx。

## 6. 错误处理

- 无凭证：finish 后停在 RECORDED，前端引导文案；不自动转写。
- 听悟 FAILED / 下载失败：status FAILED + error 落库，面板可重试（重新走 transcribe，幂等：旧 OSS 对象覆盖）。
- 录音中进程被杀：分片已传部分保留；下次打开面板把 stale RECORDING（无心跳）标记为已中断并按已有字节 finalize，音频可播。
- 转写超 2h 音频：听悟上限 6h/6GB，前端不设限，超限错误透传。
- OSS/听悟网络错误：单次失败即 FAILED 可重试，不做自动重试队列（YAGNI）。

## 7. 测试

- 单测：转写结果 JSON 解析（说话人/时间戳/改名映射）、TingwuClient 桩驱动的状态流转、无凭证降级、controller 鉴权。
- BuiltinSkillsTest 自动核对 skill allowed_tools 真名；RealToolBeans 补 MeetingTools；toolDisplayNames.js 补中文名。
- 前端：check:emits；app-e2e 保持绿；dev 真链路人工走查（无 key 降级路径必查）。

## 8. 上架与分发

- 形态：面板与全部代码内置主仓随版本发布；skill `enabled_by_default: false`，用户在插件广场启用即「安装」（诉讼可视化同款，无需动官网 registry / 动态 JAR / Ed25519 流程）。
- `meeting-recorder` 即未来付费 SKU 键（`plugin:meeting-recorder`），v1 免费。
- 面板入口经 `requiresSkill` 过滤：未启用不出现在左栏。
