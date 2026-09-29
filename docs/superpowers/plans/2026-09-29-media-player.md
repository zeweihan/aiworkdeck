# 音视频播放器（控制条 / 自动字幕 / 流式播放）实现计划

> **For agentic workers:** 本计划由主会话拆成任务书派给子代理执行（subagent-driven）。每个任务独立可测；子代理只在本 worktree 内改文件，**不提交**，完成后按任务书格式汇报。

**Goal:** 视频与音频预览换成统一自绘播放器（静音 / 倍速菜单 / 全屏 / 画中画 / 快捷键，深浅色两皮），接入自家转写管线做自动字幕与可点击逐字稿，后端下载接口支持 Range 实现流式播放。

**Architecture:** 前端新增 `components/media/{MediaPlayer,MediaControls,MediaTranscript}.vue` 与 `utils/media/*.js` 纯逻辑；FilePreview 的视频/音频分支改为 `<MediaPlayer>`。后端 `FileController.downloadFile` 加 Range；`MeetingRecordingService` 接受视频扩展名并在转写前抽音轨；新增按文件反查会议记录的接口。

**Tech Stack:** Vue3 / uni-app H5（`<view>` 模板，媒体元素命令式创建）、node:test + vue/compiler-sfc SSR 真渲染、Spring MVC `HttpRange`/`ResourceRegion`、javacv FFmpeg（已有）。

**Spec:** `docs/superpowers/specs/2026-09-29-media-player-design.md`（执行者先读它，本计划只讲切法与接口）。

## Global Constraints

- 新建一方源文件带 SPDX 双行头（`SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors` / `SPDX-License-Identifier: AGPL-3.0-or-later`）。
- 不引入任何第三方播放器库；不新增 npm / maven 依赖。
- 颜色只用 `--awd-*` 令牌；唯一例外是压在视频画面上的叠层皮（规格 3.1 列出的字面量）。不用 emoji，图标走 `config/icons.js` 的 SVG path 体系。
- 尺寸用 px。文案进 `locales/zh-CN` 与 `en-US` 两份，键名见规格 3.8。
- 快捷键只挂播放器根元素，不挂 window。
- 后端 `mvn` 用 JDK 21；前端用 npm。
- 子代理**不 commit、不切分支、不 stash**。

---

## 文件结构

```
frontend/src/utils/media/mediaTypes.js        预览用的视频/音频扩展名表 + MIME 映射
frontend/src/utils/media/mediaSource.js       直链 URL、token 脱敏、blob 回退判定
frontend/src/utils/media/transcriptCues.js    segments → cues、二分查找、说话人命名、长句切分
frontend/src/utils/media/captionState.js      会议记录 → CC 五态
frontend/src/utils/media/mediaShortcuts.js    keydown → 动作
frontend/src/utils/media/speakerColors.js     六色循环（从 MeetingRecordingPanel 抽出，面板改为引用）
frontend/src/components/media/MediaPlayer.vue
frontend/src/components/media/MediaControls.vue
frontend/src/components/media/MediaTranscript.vue
frontend/src/components/FilePreview.vue       视频/音频分支换成 <MediaPlayer>
frontend/src/services/api.js                  getMeetingByFile
frontend/src/utils/audioAttachment.js         VIDEO_EXTENSIONS / isTranscribableMedia
frontend/src/locales/{zh-CN,en-US}/files.js   player.* / captions.*
frontend/src/locales/{zh-CN,en-US}/fileTree.js transcribe 文案
frontend/tests/media/*.test.mjs               新增；package.json 加 test:media；ci.yml frontend job 加一行
backend/.../controller/FileController.java    Range
backend/.../controller/MeetingRecordingController.java  by-file
backend/.../service/meeting/MeetingRecordingService.java VIDEO_EXTENSIONS
backend/.../service/meeting/MeetingTranscriptionService.java needsAudioExtraction
backend/src/test/.../FileControllerRangeTest.java 等
```

---

### Task A：后端 Range 流式下载（dev-board#1025）

**Files:** Modify `backend/src/main/java/com/checkba/controller/FileController.java`（`downloadFile`，约 :142-230）；Create `backend/src/test/java/com/checkba/controller/FileControllerRangeTest.java`。

**Produces:** `GET /api/files/{id}/download` 对 `Range: bytes=a-b` 返回 206 + `Content-Range`/`Accept-Ranges`/`Content-Length`；无 Range 返回 200 + `Accept-Ranges: bytes`；越界 416 + `Content-Range: bytes */total`；MIME 映射覆盖规格 4.1 全部媒体扩展名。资源 `contentLength()` 不可得时退 200 全量。

**Tests（先红后绿）:** MockMvc 用 `@SpringBootTest` 或 standalone setup（照仓内 `FileControllerTest` 既有写法），临时目录放 1000 字节文件、mock `projectFileRepository`/`storageServiceFactory`/`projectMemberService`/session：
1. `range_0_99_returns_206_with_slice`：body 长 100、`Content-Range: bytes 0-99/1000`。
2. `no_range_returns_200_with_accept_ranges`。
3. `range_beyond_length_returns_416`。
4. `range_without_permission_still_403`。
5. `mov_maps_to_video_quicktime`、`m4a_maps_to_audio_mp4`。

**Verify:** `cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B -q test -Dtest='FileController*Test'`，贴输出原文。

---

### Task B：视频接入转写 + 按文件反查接口（dev-board#1024 后端半边）

**Files:** Modify `MeetingRecordingService.java`（`AUDIO_EXTENSIONS` 旁加 `VIDEO_EXTENSIONS`、`isTranscribableMediaName`、`registerExisting` 改判据与文案）；Modify `MeetingTranscriptionService.java`（包内静态 `needsAudioExtraction(String fileName)`，三档提交前对视频强制走 `transcodeWithTimeout`；找到现在「只对 webm 转码」的分叉点改成 `webm || needsAudioExtraction`）；Modify `MeetingRecordingController.java`（`GET /projects/{projectId}/by-file/{fileId}` → `{"meeting": <MeetingRecording|null>}`，成员校验同 `list`）；Modify `frontend/src/utils/audioAttachment.js`（`VIDEO_EXTENSIONS` 与 Java 逐项一致、`isTranscribableMedia(item)`；`audioNeedingTranscription` 改用它）；Modify `frontend/tests/project-home/audio-attachment.test.mjs`（对拍两张表）；Modify `frontend/src/components/FileTree.vue`（右键项判据换 `isTranscribableMedia`）、`frontend/src/pages/project-overview/project-overview.vue` 与 `ChatInterface.vue` 中引用 `isAudioFile` 做「未转写提示」的地方同样换；Modify `frontend/src/locales/{zh-CN,en-US}/fileTree.js`：`transcribe` → 「语音转文字」/ `Transcribe speech`。

**Produces:**
- Java：`MeetingRecordingService.VIDEO_EXTENSIONS`（`Set<String>`，值 `mp4, mov, mkv, avi, m4v, wmv, flv, mpeg, mpg, 3gp`）、`isTranscribableMediaName(String)`。
- Java：`MeetingTranscriptionService.needsAudioExtraction(String fileName)`（视频扩展名为真）。
- HTTP：`GET /api/meetings/projects/{pid}/by-file/{fileId}`。
- JS：`import { VIDEO_EXTENSIONS, isTranscribableMedia } from '@/utils/audioAttachment'`。

**Tests:** `MeetingRecordingServiceTest`（或同包已有测试类）加：mp4 可 `registerExisting`、文件夹拒绝、`.txt` 拒绝且文案含「音视频」；`MeetingTranscriptionServiceTest` 加 `needsAudioExtraction`：mp4/MOV 真、mp3/webm 假；控制器测试加 by-file 有/无记录两例。前端 `node --test tests/project-home/audio-attachment.test.mjs` 对拍。

**Verify:** `mvn -B -q test -Dtest='Meeting*Test'` + 前端该测试，贴输出。

---

### Task C1：前端纯逻辑层（utils/media）

**Files:** Create `frontend/src/utils/media/{mediaTypes,mediaSource,transcriptCues,captionState,mediaShortcuts,speakerColors}.js`；Create `frontend/tests/media/{transcriptCues,captionState,mediaShortcuts,mediaSource}.test.mjs`；Modify `frontend/package.json`（`"test:media": "node --test tests/media/*.test.mjs"`）与 `.github/workflows/ci.yml` frontend job 加 `npm run test:media`；Modify `MeetingRecordingPanel.vue` 改为从 `speakerColors.js` 取六色（行为不变）。

**Produces（后续任务按此签名消费，不得改名）:**

```js
// mediaTypes.js
export const PREVIEW_VIDEO_EXTENSIONS = ['mp4','webm','ogg','mov','mkv','avi','m4v']
export const PREVIEW_AUDIO_EXTENSIONS = ['mp3','wav','ogg','m4a','flac','aac','opus']
export function previewKindOf(fileType)      // 'video' | 'audio' | null；ogg 归 video 仅当 fileType 为 'ogv'，否则 audio
export function mimeTypeFor(ext)             // 规格 4.1 的映射，未知返回 ''

// mediaSource.js
export function buildStreamUrl(downloadUrl, sessionId)   // 无 sessionId 原样返回；有则追加 ?token=（已有 query 用 &）
export function redactToken(url)                          // token=xxx → token=***
export function shouldFallbackToBlob(mediaErrorCode, alreadyFellBack) // code 2/4 且未回退过 → true

// transcriptCues.js
export function parseSegments(transcriptJson)   // 容错：非法 JSON / 非数组 → []
export function buildSpeakerLabels(segments, speakerNamesJson, locale) // → { labels: {id: label}, count }
export function buildCues(segments, { maxChars = 42, minMs = 800 } = {}) // → [{ start, end, text, speaker, segIndex }]
export function cueIndexAt(cues, ms, { gapMs = 300 } = {})   // 二分；落在 cue 结束后 gapMs 内仍返回该 cue；否则 -1
export function textWidth(str)                   // CJK 计 1，其余 0.5

// captionState.js
export const CAPTIONS_OFF_KEY = 'awd_media_captions_off'
export function captionStateFrom(meeting)        // null → 'none'；RECORDED → 'pending'；TRANSCRIBING → 'transcribing'；TRANSCRIBED 且有段 → 'ready'；TRANSCRIBED 无段或 EMPTY → 'empty'；FAILED → 'failed'
export function isTerminal(state)                // ready/empty/failed/none/pending 为真，transcribing 为假

// mediaShortcuts.js
export const RATE_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]
export function isEditableTarget(el)
export function resolveShortcut(event, { kind, captionsAvailable })
// 返回 null 或 { type: 'toggle-play'|'seek-by'|'seek-to-ratio'|'volume-by'|'toggle-mute'|'toggle-fullscreen'|'toggle-captions'|'rate-step'|'close-popover', value?: number }
export function nextRate(current, step)          // step ±1，钳在 RATE_STEPS 内

// speakerColors.js
export const SPEAKER_COLOR_TOKENS = [...]        // 六个 --awd-* 令牌名（从 MeetingRecordingPanel 现值抽出）
export function speakerColorVar(orderIndex)      // 'var(--awd-...)'
```

**Tests:** 规格 6 节列的四份用例，每个导出至少两条断言（正常 + 边界）。快捷键测试用手工构造的 `{ key, code, shiftKey, target: { tagName, isContentEditable }, preventDefault(){}, stopPropagation(){} }`。

**Verify:** `cd frontend && npm run test:media` 全绿；`npm run test:project-home` 不回归；贴输出。

---

### Task C2：播放器组件与 FilePreview 接线（dev-board#1023 + #1024 前端半边）

**Files:** Create `components/media/MediaPlayer.vue`、`MediaControls.vue`、`MediaTranscript.vue`；Modify `FilePreview.vue`（删自绘音频与 `<video>` 分支、`loadMediaResource`、媒体定位方法，换 `<MediaPlayer>`；`isVideo/isAudio` 改用 `previewKindOf`）；Modify `services/api.js`（`getMeetingByFile(projectId, fileId)`）；Modify `config/icons.js`（新增 path：`play`（已有）、`captions`、`captionsOff`、`transcript`、`pip`、`fullscreen`、`fullscreenExit`、`volumeLow`、`volumeHigh`、`check`）；Modify `locales/{zh-CN,en-US}/files.js`；Create `tests/media/mediaPlayerRender.test.mjs`、迁移 `tests/evidence/previewLocate*.test.mjs` 中的媒体用例到 `tests/media/previewLocate.test.mjs`。

**Consumes:** Task C1 全部导出；Task B 的 `getMeetingByFile` 端点（未合入时前端按契约写，返回 `{meeting}`）。

**Produces（FilePreview 用）:**

```
<MediaPlayer :kind="'video'|'audio'" :file="file" :project-id="projectId" :locator-sec="mediaLocatorSec"
             @locator-consumed="..." @error="..." />
```

组件间契约：
- `MediaControls` props：`variant('overlay'|'card')`、`kind`、`playing`、`currentSec`、`durationSec`、`buffered`（`[[startSec,endSec],...]`）、`volume`、`muted`、`rate`、`captionState`、`captionsOn`、`transcriptOpen`、`pipAvailable`、`fullscreen`、`markSec`（null 或秒）。emits：`toggle-play`、`seek(sec)`、`volume(v)`、`toggle-mute`、`rate(r)`、`caption-click`（由父按五态决定动作）、`toggle-transcript`、`toggle-pip`、`toggle-fullscreen`、`popover(open)`。
- `MediaTranscript` props：`cues`、`labels`、`speakerCount`、`currentIndex`、`following`。emits：`seek(sec)`、`follow`（点「回到当前」）。

**实现要点（对照规格 2 / 3 / 3.6 / 3.7 / 4.2）:**
- 媒体元素 `document.createElement('video')` / `new Audio()`，实例挂在 `this._media`（非响应式）。视频元素 append 进 `$refs.stage`。
- 取源：`buildStreamUrl(getFileDownloadUrl(fileId), getSessionId())`；`error` 事件走 `shouldFallbackToBlob` → 复用原 XHR blob 逻辑（连 `_mediaReqId` 竞态防护一起搬）。日志一律 `redactToken`。
- `timeupdate` 节流到 rAF 更新 `currentSec`；`progress` 事件读 `buffered` 转成秒数组。
- 字幕：mounted 时 `getMeetingByFile`；状态机按 `captionStateFrom`；`transcribing` 每 4s `getMeetingRecording(id)`（api.js 已有 GET）；文件切换/卸载清定时器。CC 点击按五态分派（规格 3.6 表）。
- 快捷键：根元素 `tabindex="0"` + `@keydown` → `resolveShortcut` → 执行；FilePreview 渲染媒体分支时若 `document.activeElement` 为 body 则 `focus({ preventScroll: true })`。
- 自动隐藏、全屏（根元素）、PiP、倍速弹层、音量滑条、逐字稿面板布局按规格 3.2/3.3/3.4。
- 定位契约：`locatorSec` 非空 → 元数据就绪后 seek 并 pause，不 autoplay；emit `locator-consumed`；时间标记条 + 「从这里播放」。
- 深浅色：card 皮全令牌；overlay 皮用规格 3.1 字面量；`prefers-reduced-motion` 去过渡。

**Tests:** `mediaPlayerRender.test.mjs`（SSR 真渲染，harness 抄 `tests/evidence/previewLocateRender.test.mjs` 的 `buildSsrRender`，分别编译 MediaControls 与 MediaPlayer 模板）断言：overlay/card 根 class；CC 五态各自 class 与 i18n 键；`transcriptOpen` 按钮只在 `captionState==='ready'`；`pipAvailable=false` 不渲染 PiP；`kind='audio'` 无全屏；`markSec` 渲染刻度；模板里没有 `controls` 属性与 `<video>` 标签。`previewLocate.test.mjs` 保留原三条契约。`npm run check:emits`、`npm run check:locales` 必须过。

**Verify:** `npm run test:media && npm run test:evidence && npm run check:emits && npm run check:locales && npm run build:h5`，贴输出。

---

### Task D：真机走查与截图（主会话自己做，不外包）

隔离 local-mode 后端 + `dev:h5`（`VITE_API_BASE_URL` 指向隔离后端），ffmpeg 生成 20s mp4（`testsrc2` + 正弦音）与 mp3 上传到 QA 项目；两主题下按规格 6 节清单逐张截图；`curl -sI -H 'Range: bytes=0-99'` 验 206；浏览器网络面板确认媒体请求 206。字幕数据本机无转写凭证时向隔离 H2 直插一条 TRANSCRIBED 记录。全部截图亲眼过一遍再提交。

---

## Self-Review

- 规格覆盖：2（C2）、3.1–3.4（C2）、3.5（C1 transcriptCues）、3.6（C1 captionState + C2 分派）、3.7（C1 mediaShortcuts + C2 挂载）、3.8（C2 i18n/aria）、4.1（A）、4.2（B + C2 api.js）、4.3（B）、5（C2）、6（C1/C2 单测 + D 真机）、7 不做项无任务。无缺口。
- 类型一致：`captionState` 五态字符串在 C1 定义、C2 消费同名；`buffered` 秒数组两处同形；`getMeetingByFile` 返回 `{meeting}` 与 B 一致。
