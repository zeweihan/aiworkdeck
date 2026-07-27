---
name: utility-tools
description: 辅助小工具领域。任务涉及浏览器面板、截图/OCR、剪贴板、收藏夹、搜索、下载、语音 TTS、文件预览/插入时，先读本文档再动代码。
---

# 辅助小工具 领域地图

职责边界：工作台各辅助面板与桌面能力（浏览器/截图/剪贴板/收藏/搜索/语音/预览插入/OCR）。不含编辑器（doc-editor）、不含左栏布局本身（sidebar-shell）。

## 分组清单（前端组件 / desktop 宿主 / 后端 API 三层）

**浏览器**：`frontend/src/components/BrowserPane.vue`；desktop `desktop/main/main.js`（BrowserView 创建 ~:634、重复 add 先 remove 再 add 置顶 ~:771-810、window.open 拦截转工作区新 tab ~:290/:674 → 事件 `checkba:browser-open-new-tab`、全屏/黑屏兜底恢复 ~:336/:549/:887/:1189、IPC handlers ~:819-988）；后端 `controller/BrowserProxyController.java`（GET /api/browser/proxy）。

**截图**：入口 `checkbaDesktop.ocr.captureScreen`；desktop main.js 透明覆盖框选窗 ~:389-571（BrowserView 模式仅限其区域内框选 ~:426）、capturePage 抓取 ~:577/:585/:709、IPC：ocr-capture-screen/desktop/window/view + ocr-start-selection。**推荐链路是 ocr-capture-view（当前 BrowserView，免 macOS 录屏权限）**；无独立后端端点，产物统一走 OCR。

**剪贴板**：`ClipboardPanel.vue`；desktop main.js 轮询监听 clipboardWatchTimer ~:117-232（指纹去重 ~:110，首 tick 只记指纹）、推送 `checkba:clipboard-copied`；后端 `controller/ClipboardController.java`（/api/clipboard：GET /、POST /text、POST /file、GET /{id}/file、DELETE /{id}）。

**收藏夹**：`ProjectFavoritesPanel.vue`；网页选中收藏经 `checkba:webmark`（preload ~:26）→ project-overview 订阅入库（~:2003）；后端 `controller/WebFavoriteController.java`（/api/favorites/my、/api/projects/{id}/favorites、DELETE、image）。

**搜索**：`SearchPanel.vue`；后端 `controller/SearchController.java`（POST /api/projects/{id}/search）。

**下载**：`DownloadList.vue` 是**孤儿组件**（全仓库无引用、未挂载）；文件下载实际走 FileController `GET /api/files/{fileId}/download`。

**语音 TTS**：`EasyVoicePane.vue`（api：getTtsVoices/generateTtsAudio）；desktop 本地 Kokoro 由 `desktop/main/services/kokoro-service.js` 管理；后端 `controller/TtsController.java`（/api/tts/voices、/generate）+ `service/TtsService.java`——`external.tts.provider`：elevenlabs（云端默认）| local（桌面捆绑 Kokoro，OpenAI 兼容 /v1，base-url=`external.tts.local-base-url`）。easyvoice Docker 段已停用。

**文件预览/插入**：`FilePreview.vue`（docx/pdf/pptx/压缩包分流见 project-overview ~:5110-5157）、`FilePickerDialog.vue`、`FileLinkDropZone.vue`、`FileStagingArea.vue`；图片插入走 `libreofficeExecutorClient.js` insertImage → office_thread.js；desktop `file-service.js` + IPC `checkba:fs-read-file`（含敏感路径拦截+大小上限）；后端 `FileController.java`（download/upload/upload-status/text/compare）、`ProjectFileController.java`（列表/folder/archive/批量/回收站/tags）、`DocFileLinkController.java`（doc-links）。

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

## 验证

- desktop 服务栈：`cd desktop && npm test`（service-manager/model-manager/pysvc-runtime）。
- 后端相关单测：TtsServiceTest、FileControllerChunkedUploadTest、ProjectFileService*Test 等；剪贴板/收藏/搜索/OCR/浏览器代理暂无专属测试。
- UI 链路：`cd frontend && npm run test:app-e2e`。
