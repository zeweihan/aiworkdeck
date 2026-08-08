---
name: utility-tools
description: 辅助小工具领域。任务涉及浏览器面板、截图/OCR、剪贴板、收藏夹、搜索、下载、语音 TTS、文件预览/插入时，先读本文档再动代码。
---

# 辅助小工具 领域地图

职责边界：工作台各辅助面板与桌面能力（浏览器/截图/剪贴板/收藏/搜索/语音/预览插入/OCR）。不含编辑器（doc-editor）、不含左栏布局本身（sidebar-shell）。剪贴板与文件缓存区的**免费额度**在本文档里讲执行方式，权益判定、entitlement 命名与账户连接见 `.claude/agents/licensing-billing.md`。

## 分组清单（前端组件 / desktop 宿主 / 后端 API 三层）

**浏览器**：`frontend/src/components/BrowserPane.vue`；desktop `desktop/main/main.js`（BrowserView 创建 ~:634、重复 add 先 remove 再 add 置顶 ~:771-810、window.open 拦截转工作区新 tab ~:290/:674 → 事件 `checkba:browser-open-new-tab`、全屏/黑屏兜底恢复 ~:336/:549/:887/:1189、IPC handlers ~:819-988）；后端 `controller/BrowserProxyController.java`（GET /api/browser/proxy）。

**截图**：入口 `checkbaDesktop.ocr.captureScreen`；desktop main.js 透明覆盖框选窗 ~:389-571（BrowserView 模式仅限其区域内框选 ~:426）、capturePage 抓取 ~:577/:585/:709、IPC：ocr-capture-screen/desktop/window/view + ocr-start-selection。**推荐链路是 ocr-capture-view（当前 BrowserView，免 macOS 录屏权限）**；无独立后端端点，产物统一走 OCR。

**剪贴板**：`ClipboardPanel.vue`；desktop main.js 轮询监听 clipboardWatchTimer ~:117-232（指纹去重 ~:110，首 tick 只记指纹）、推送 `checkba:clipboard-copied`；后端 `controller/ClipboardController.java`（/api/clipboard：GET /、POST /text、POST /file、GET /{id}/file、DELETE /{id}）。
  **免费额度（PR-C）**：未拥有 `clipboard.unlimited` 时 GET / 只返回「最近 20 条 且 3 天内」，两条同时生效取更严者。**实现是查询侧过滤，绝不删除记录**——超出的行留在库里，解锁后原样可见。GET / 返回体从裸数组改为 `{items, limited, hiddenCount, maxItems, retentionDays}`（`ClipboardListResult`），hiddenCount 只算「因额度看不见」的（= 总数 − min(3天内条数, 20)），不含被分页 limit 挡住的。常量在 `ClipboardService.FREE_MAX_ITEMS/FREE_RETENTION_DAYS`。**额度只在 local-mode（桌面单机版）执行**：`EntitlementService` 是按本机的（无 userId 维度），团队案件库服务器上权益恒为空集，照执行会把每个接入成员截到 20 条且永远无法解锁。

**收藏夹**：`ProjectFavoritesPanel.vue`；网页选中收藏经 `checkba:webmark`（preload ~:26）→ project-overview 订阅入库（~:2003）；后端 `controller/WebFavoriteController.java`（/api/favorites/my、/api/projects/{id}/favorites、DELETE、image）。

**搜索**：`SearchPanel.vue`；后端 `controller/SearchController.java`（POST /api/projects/{id}/search）。

**下载**：`DownloadList.vue` 是**孤儿组件**（全仓库无引用、未挂载）；文件下载实际走 FileController `GET /api/files/{fileId}/download`。

**语音 TTS**：`EasyVoicePane.vue`（api：getTtsVoices/generateTtsAudio）；desktop 本地 Kokoro 由 `desktop/main/services/kokoro-service.js` 管理；后端 `controller/TtsController.java`（/api/tts/voices、/generate）+ `service/TtsService.java`——`external.tts.provider`：elevenlabs（云端默认）| local（桌面捆绑 Kokoro，OpenAI 兼容 /v1，base-url=`external.tts.local-base-url`）。easyvoice Docker 段已停用。

**文件缓存区（左下角「文件暂存区」）**：`FileStagingArea.vue`（纯展示，用量条读 `usage` prop）+ `pages/project-overview/stagingArea.js`（方法组）。**物理形态是项目内名为 `__staging_area__` 的文件夹**，「加入缓存区」= `batchMoveFiles` 把已有项目文件移进去，没有独立的缓存区目录。
  **免费额度（PR-C）**：`service/quota/StageQuotaService.java`，未拥有 `stage.unlimited` 时上限 20 个文件 / 500MB。**实现是移入时拦截，已有文件一律不动**——`ProjectFileService.batchMove` 在循环前整体准入检查，超额抛 `StageQuotaExceededException` → GlobalExceptionHandler 转 `code=4003 + feature + usage`，前端 api.js 打 `err.quotaExceeded` 标记。移出方向永不拦截（否则用户无法自救）。跨项目 id 不参与计算（防越权探测文件大小）。**文件夹按它装的全部文件递归计数**（准入与用量同一口径）——文件树允许把整个文件夹拖进缓存区，只算 1 个条目 0 字节的话，套一层目录就能让两条额度同时失效。用量端点 `GET /api/projects/{id}/files/stage/usage?folderId=`，**folderId 是全局 id，必须 `checkFileInProject` 校验归属**（只验路径 projectId 的话能枚举他人项目任意目录的文件数与字节数）。与剪贴板同理，额度只在 local-mode 执行。

**文件存储位置（PR-C）**：`service/storage/StorageLocationService.java` + `GET /api/storage/location`、`POST /api/storage/location`（迁移，需 stage.unlimited）、`POST /api/storage/location/reset`（恢复默认，不需权益）。搬的是**全局存储根**（项目文件与缓存区文件的落盘位置），因为缓存区文件就是项目文件。迁移策略：**复制 → 校验文件数与字节数（含源侧复查）→ 落配置 → 换指针，原目录保留为备份绝不删**；任一步失败清掉本次复制的副本并保持原路径。目标必须是空目录或不存在，且与源不互相嵌套——**嵌套判断在 `toRealPath()` 之后做**，纯词法比较挡不住指向源内部的软链（会把源复制进它自己，在数据根里造出几百个垃圾目录）。**源侧要复查**：只比「复制前的源」与「复制后的目标」，迁移期间自动保存进已复制目录的文件两边数字仍相等，会被静默留在旧根。源目录不可访问时拒绝迁移（否则 copyTree 会把源建出来，「0 个文件迁移成功」）。配置落 `~/.aiworkdeck/storage-location.json`（不落 DB：存储根必须在 JPA 起来之前就确定）。`ProjectStorageResolver.globalRoot` 因此改为 volatile + `relocate()`；能热切是因为 DB 存的是逻辑路径、git 的 gitDir/workTree 每次现算。

**文件预览/插入**：`FilePreview.vue`（docx/pdf/pptx/压缩包分流见 project-overview ~:5110-5157；PDF 走 Chromium 原生引擎渲染，标准 annotation 可见；watch `file.wpsFileId` 在 AI 改完 PDF 后自动重拉字节——reload_file 是 Object.assign 原地更新，file 对象引用不变，别删这个 watch）、`FilePickerDialog.vue`、`FileLinkDropZone.vue`、`FileStagingArea.vue`；图片插入走 `libreofficeExecutorClient.js` insertImage → office_thread.js；desktop `file-service.js` + IPC `checkba:fs-read-file`（含敏感路径拦截+大小上限）；后端 `FileController.java`（download/upload/upload-status/text/compare）、`ProjectFileController.java`（列表/folder/archive/批量/回收站/tags）、`DocFileLinkController.java`（doc-links）。

**反馈浮窗的第二个截图消费者**：`FeedbackWidget.vue` 也走 `host.ocr.startSelection({mode:'window'})`，
自带一份等价的裁剪算法（不复用 project-overview 的实例态方法组）。改截图 IPC 的返回结构
（`{dataUrl, selection, bounds}`）要同时改这两处。见 `.claude/agents/feedback-optimizer.md`。

**OCR**：desktop 框选与抓屏（见截图）；后端 `controller/OcrController.java`（POST /api/ocr/recognize、GET /temp/{fileName}）+ `service/OcrService.java` + `service/ocr/AliyunOcrClient*`——**后端 OCR 实际链路是阿里云**；MinerU 只在 desktop 服务栈（mineru-service.js）与 PPTX 工具链出现，OCR 后端未直接对接 MinerU。

## preload IPC 通道（desktop/preload/preload.js，window.checkbaDesktop）

- app：`checkba:app-open-internal`（订阅）、`checkba:ui-confirm`（应用内确认弹窗，不被 BrowserView 遮挡）
- browser：invoke create/navigate/set-active/set-bounds/set-views-visible/destroy/get-bounds/wait-ready/get-snapshot/set-ua；订阅 open-new-tab/webmark/title-updated
- ocr：invoke start-selection/capture-window/desktop/view/screen；订阅 selection-result/selection-error
- clipboard：订阅 `checkba:clipboard-copied`
- backend：invoke restart；订阅 status
- model：invoke status/download/cancel/remove；订阅 progress
- services：invoke `checkba:service-ensure`
- utils：invoke `checkba:fs-read-file`（唯一保留的文件读取；旧任意路径 readFile/writeFile 已移除缩小攻击面）
- fs：invoke `fs:showOpenDialog`
- zetaoffice：invoke `checkba:zetaoffice-editor`

## IPC 订阅的正确模式（PR#148/#151 权威范例）

navigateTo 页面栈会存活多个 project-overview 实例，各自绑定全局监听 → 一次事件 N 份副作用。这是**全页面级地雷**。正确模式（都在 project-overview.vue）：
- 活跃实例指针 `window.__checkbaActiveOverviewVm`（mounted ~:1992 置 this，beforeDestroy ~:1783 置空）；守卫 `isActiveOverviewInstance()`（~:2960），全局事件处理先判 `if (!this.isActiveOverviewInstance()) return`（范例 ~:2006/:2046/:2084/:2228）。
- 剪贴板订阅（~:3274-3290）：`_desktopClipboardUnsub` 保存反订阅函数防重复订阅；去重状态挂 window 而非组件实例（`window.__checkbaClipLastText` ~:3258）。
- 编辑器同款模式：非响应式注册表 `_libreExecMap`/`_libreRefs` + `syncLibreExecutor()` 活跃指针（~:5855-5904，注释标明"同 PR#151 模式"）。
**新增任何全局 IPC/事件订阅必须套用此模式。**

## 面板在 project-overview.vue 的挂载点

FilePickerDialog :298 / EasyVoicePane :537 / DesensitizePane :543 / SearchPanel :550 / PluginPane(左栏) :555 / FileLinkDropZone :570 / FileStagingArea :578 / BrowserPane 左:719 右:786 / FilePreview 左:749 右:816 / ProjectFavoritesPanel :892 / ClipboardPanel :900。import 区 :1217-1233，components 注册 :1293-1312。

## 已知地雷

- BrowserView 弹窗守卫模式与 window.open 消费者、截图假死根因见 v0.6.1 修复（desktop-interaction-bugfix 记录）；改 BrowserView 生命周期务必测全屏/黑屏恢复路径。
- 剪贴板去重靠指纹+window 级状态，改动监听逻辑先读 PR#148/#151 教训。
- `checkba:fs-read-file` 有敏感路径拦截，别为新功能开任意路径读写。
- Kokoro 大陆网络 401 = hf_xet 绕镜像问题，禁 xet 修（PR#142）。
- **免费额度的红线**：剪贴板是「隐藏超出部分」、缓存区是「拒绝新增」，两者都**绝不删除或清理用户已有数据**。任何"顺手清理超额记录"的改动都是回归——用户付费后必须能看到之前被隐藏的全部内容。
- **权益失效不等于把人锁在外面**：`applyOnStartup` 无条件应用自选存储根（权益失效后数据照常读写），因此 `GET /api/storage/location` 与「恢复默认位置」都**不设权益闸**——否则 Key 被吊销 + 外置盘拔掉的用户既看不到自己数据在哪，也换不回默认位置。付费闸只加在「改到新的自选位置」这个动作上。
- `StorageException` 默认落到通用异常处理器会被替换成「服务器内部错误」（防路径回显）。存储位置那几条用户语言文案是在 `StorageLocationController` 里转成 `IllegalArgumentException` 才送出去的，新增可回显文案要走同一条路且确保不含路径。

## 验证

- desktop 服务栈：`cd desktop && npm test`（service-manager/model-manager/pysvc-runtime）。
- 后端相关单测：TtsServiceTest、FileControllerChunkedUploadTest、ProjectFileService*Test 等；剪贴板/收藏/搜索/OCR/浏览器代理暂无专属测试。
- UI 链路：`cd frontend && npm run test:app-e2e`。
