---
name: doc-editor
description: 文档编辑器（LOWA/zetaoffice）领域。任务涉及 LibreOffice WASM 引擎、编辑器启动/boot、字体/IME、保活池与 LRU、自动保存、.uno: 命令、zetajs 编组时，先读本文档再动代码。
---

# 文档编辑器（LOWA）领域地图

职责边界：编辑器内核与宿主集成。AI 发编辑指令的链路属 ai-doc-bridge 领域。引擎 = LibreOffice 24.2.8 自建 zh-CN 版（LO core 分支 distro/allotropia/zeta-24-2）。

## 关键文件

**引擎构建与分发**
- `desktop/lowa-build/`：README.md（为什么自建 zh-CN）、RECIPE.md（精确配方+产物 sha256）、mega-build.sh（裸机全自动构建，PHASE_1..7）、autogen.input、patches/（两阶段 zh-CN 焙入 + ZZZ-aiworkdeck-locale-zh-CN.xcd 默认 ooLocale）。
- `desktop/scripts/fetch-lowa-assets.js` — 构建期下载 LOWA 运行时 + OFL CJK 字体到 `frontend/dist/zetaoffice/lowa/`，写 `.encodings.json`（brotli 侧车）；`LOWA_BASE_URL` 指自托管引擎（`https://www.aiworkdeck.com/lowa-engine/24.2.8-zhcn-r2/`）。
- `desktop/scripts/lowa-selfhost.md` — 自托管流程文档。

**桌面壳服务**
- `desktop/main/zetaoffice-server.js` — 同源本地 HTTP 服务（editor.html + /lowa/* 本地优先、CDN 兜底）；memoized `startEditorServer()`。**固定端口 47613**（占用回退随机）+ /lowa/* ETag/304 复验（PR#220）：origin 稳定才能让 Chromium 跨启动复用 HTTP 缓存与 V8 WASM 代码缓存（150MB soffice.wasm 免重编译）；引擎 URL 跨版本不变所以是 no-cache 复验而非 immutable，别改成长缓存。
- `desktop/main/zetaoffice-session.js` — persist:zetaoffice 分区注入 COOP/COEP/CORP（webview 跨源隔离，SharedArrayBuffer 可用，不污染主窗口）。
- `desktop/main/zetaoffice-verify.js` — ⌘⇧L 验证窗口（?verify=1，零副作用验证引擎/中文/AI 命令）。
- IPC：`checkba:zetaoffice-editor`（main.js ~:1373，返回 {url, preload, partition}）；`desktop/preload/zetaoffice-webview-preload.js` 暴露 `window.zetaHostBridge`（通道 lo-relay）。

**前端 boot 与桥**
- `frontend/src/zetaoffice/editor.html` + `editor-main.js` — webview 页面入口：选传输、startEditorEndpoint、IME 覆盖层、modified 节流（1/500ms）、boot-log 里程碑。
- `frontend/src/zetaoffice/public/`：zeta.js（vendored zetajs）、**office_thread.js（worker 内全部 UNO 操作实现，本领域最核心文件）**。
- composables：`zetaOfficeBoot.js`（emscripten Module、注入 CJK 字体+fontconfig 别名 conf、locale shim、resolve office-worker port）、`zetaOfficeEditorEndpoint.js`（boot+executor+serve 三件套）、`zetaOfficeRelay.js`（跨隔离命令中继：serveExecutor/createRelayExecutor/portTransport）、`libreofficeExecutorClient.js`（EDITOR_ACTIONS 白名单+请求应答）、`useLibreOfficeBridge.js`、`useZetaOfficeWebview.js`（webview 包成 executeCommand 契约）、`zetaOfficeImeOverlay.js`（canvas 透明 IME 层，中文输入+控制键转发）。
- `frontend/vite.zetaoffice.config.js` — editor 页专用 Vite 构建（脱离 uni-app），产出 dist/zetaoffice/。

**宿主 UI（保活/实例管理/自动保存）**
- `frontend/src/components/LibreOfficeEditor.vue` — 单文档编辑器组件：webview 创建、prefetch、load/export、autoSave、flushSave、reloadFromBackend。支持**备胎过继**（watch file 仅 null→文档；引擎已就绪走 finishDocLoad，未就绪由 onEndpointReady 接手）与**只读预览接力**（字节预取完成即 docx-preview 本地渲染，previewReady 后 overlay 变成可滚动阅读 + 顶部细进度条，ready 后整体消失）。
- `frontend/src/pages/project-overview/librePool.js` — 保活池方法组（Phase 1 外置）：libreLruKeys/touchLibreLru/evictLibreInstance、syncLibreExecutor 活跃指针、`_libreRefs`/`_libreExecMap` 非响应式注册表、`LIBRE_KEEPALIVE_MAX = 3`、reloadActiveLibreInstances（版本退回/检查点恢复后就地重载）；**预热备胎**（PR#220）：libreSpares（{key, file}，file=null 是后台预 boot 的空白隐藏实例），onActiveOfficeFileChanged 里 maybeAdoptLibreSpare（须在 touchLibreLru 之前，靠"不在 lru 记账"识别无实例）过继给池外首开文档，过继后按 'left:fileId' 常规记账；补胎在过继 ready 后（scheduleLibreSpare，4s 延迟）。仅左窗格设备胎（webview 不能跨容器移动）；h5 无 checkbaDesktop 不建胎；常驻多一个空白实例内存（数百 MB）。

## 启动链路（打开 docx → 可编辑）

激活文档 → 渲染 LibreOfficeEditor → getEditor() IPC（装隔离+起服务）→ 建 webview（persist:zetaoffice）+ 并行 prefetch 文档字节 → editor.html/editor-main.js 选传输 → bootZetaOffice（校验 crossOriginIsolated、fetch CJK 字体、fontconfig conf、加载 soffice.js、uno_main resolve worker port）→ office_thread.js boot（Desktop.create → 空白 swriter、RecordChanges=true、installModifyListener → ui_ready）→ executor 握手 ready → loadDocument：`load_document {bytes}` 写 MEMFS + loadComponentFromURL 重定位 xModel → ready → onLibreReady 注册 executor + syncLibreExecutor。

## zetajs 编组硬规则（office_thread.js，PR#107）

- UNO 服务构造器首参必须是 component context（Desktop.create(context) 等）；结构体用值对象 `new css.beans.PropertyValue({Name,Value})`。
- dispatch 的 args sequence 必须是**纯 Array**（不能 typed array）。
- sequence<byte> 有符号且只收 Array：load_document 字节要 `Array.from(new Int8Array(...))`；省略 `_default` 目标帧会让 zetajs 把字节序列当 context。
- export 走 XOutputStream.writeBytes 取回（Int8Array 视图）；**不能** storeToURL 到 MEMFS 再 FS.readFile（pthread 代理 ENOENT）。
- worker 内 `Module.zetajs` 才是 UNO 桥；主线程 resolve 的是 thread port。
- **typedef 成员的 struct**（BorderLine2.Color=util.Color 等）依赖 vendored zeta.js 里补的 TYPEDEF 解析分支（对齐上游语义）；升级 zeta.js 时确认该分支仍在，否则 TableBorder2 双向编组回退到 "bad type description"。
- **枚举型属性读回可能是裸 short**（ParaAdjust 实锤）：与 css.* 枚举成员比较必须走 `enumEq`/`unoEnumVal`（office_thread.js），恒等比较会"set 成功读回不等"。
- **short 型属性（VertOrient/OutlineLevel）set 必须传 `shortAny()`**（带类型 Any）：裸 number 编组成 long，严格 setter（>>= sal_Int16）拒绝且常被 try 吞掉。
- **表后定位不能用 `table.getAnchor().getEnd()`**——会落进 A1 单元格（后续内容写进表格里）；用 `cursorToParagraphAfterTable()`（按表名在正文枚举定位）。

## 自动保存（LibreOfficeEditor.vue）

触发链：worker modified → 节流 → onDocModified 置 dirty → scheduleAutoSave（延迟 max(200, min(2500, 15000-脏龄))）→ autoSave：**命令在飞（_cmdBusy>0）或距上次命令<1.5s 且脏龄<60s 时让路 2s 重试**（防 export 冻结 Qt 事件循环，PR#182）→ saveDocument → export_document → 整文件 multipart POST /api/files/{id}/upload。失败保持 dirty、15s 慢速重试。flushSave 在 tab 关闭（~:5051）与 LRU 淘汰前 await。

**后端就地改了文件后重载活动实例**：`reloadFromBackend()`（版本退回 / 检查点恢复 / AI 直接改文件都该走它）。组件的 `watch file` **只认 null→文档**（备胎过继，PR#220），文档→文档换内容一概不触发，模板 key 也只含 `file.id`——改 `wpsFileId` 不会让正在显示的实例重新加载，必须显式调这个方法。它按序：取消 `_saveTimer` + 清 `dirty` → 等在途 `saving` 结束 → 清 `_bytesPromise`（预取的是旧字节）→ `loadDocument()` 就地 `load_document` 换文档 → 再清一次脏（retarget 里设 `RecordChanges` 会触发一次 modified）。失败则置 `docLoadFailed`（画布上还是旧内容，保存闸必须落下）。`loadDocument()` 返回 `true`=真换了文档、`false`=后端 0 字节（新建文件，保留空白 boot 文档）。宿主侧入口 `librePool.js` 的 `reloadActiveLibreInstances(fileId)`（只刷 `activeFileId` 命中**且 `inst.file.id` 相符**的实例——空白备胎不注册 `_libreRefs`，这条是硬判据）。**不能用 `closeFile`/淘汰活动实例代替**——那会 `flushSave` 把旧字节写回。

**重载撞上引擎仍在 boot**（含只读预览接力期）：`reloadFromBackend()` 不能只是跳过——预取的旧字节还排在 `finishDocLoad` 后面，装进来再 autosave 就把退回撤销了。此时置 `_reloadPending` 并返回 `true`（延后而非失败），`finishDocLoad` 发完 `ready` 立刻补一次真重载。只读预览体本身无害（无任何保存路径），有害的是它背后那份陈字节。

守卫（防空文档覆盖，PR#194）：`docLoadFailed` 闸——load 失败或"元数据非空却下载 0 字节"（fileSize>0 而 bytes 空）时置位，此后 onDocModified 与 saveDocument 一律拒绝；fileSize==0 才当新建空白。**该编辑器存/取走整文件 XHR，不含分片上传**。

## 已知地雷

- boot 三地雷勿回退（canvas 必须 id=qtcanvas 且禁 border/padding；COOP/COEP 缺失 SharedArrayBuffer 不可用；locale shim）。
- 引擎仅 Writer+Calc 实锤（PR#165），别承诺 Impress。
- CJK 字体走类别映射别名（PR#157/158），**不能用 assign 硬替换**；tofu 排查用 list_fonts 诊断 action。
- 删除键/快捷键必须走 `.uno:` 调度（覆盖层吞键+修订模式手工删卡死教训，PR#164/166）。
- `npm run build:zetaoffice` 会清空 dist 并删掉已 fetch 的引擎——本地反复跑 e2e 用 `LOWA_ENGINE_DIR` 规避，或从兄弟 worktree 复制引擎（CDN 挂时的配方）。
- 修订作者：params 带 `__agent:true` → 署名 "AI Workdeck"。
- webview/uni 存储格式坑与宿主侧 e2e 配方见 lowa-keepalive 记录（PR#159）。
- **预热备胎是同一个 LibreOfficeEditor 组件（file=null）**：任何在组件树/DOM 里"找编辑器实例"的探针（如 desktop-e2e FIND_EDITOR）必须过滤 `file` 非空，否则命令打在隐藏空白备胎上、样样"成功"但真文档纹丝不动。备胎未激活用 visibility 隐藏（绝对定位占位），不能改 display:none——引擎要在有尺寸画布里 boot。

## 验证

- 核心回归：`cd frontend && npm run test:lowa-e2e`（真引擎 puppeteer-core 无头，12 组人机模拟，基线 38 步；前置 `npm run build:zetaoffice` + `node ../desktop/scripts/fetch-lowa-assets.js` 或设 LOWA_ENGINE_DIR）。
- 涉桌面壳/webview：`npm run test:desktop-e2e`（弹 dev Electron 窗口，验证保存落盘链路）。
- 全应用：`npm run test:app-e2e`。改编辑器三件套（原语/白名单/worker）必跑 lowa-e2e。
