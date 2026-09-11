---
name: doc-editor
description: 文档编辑器（LOWA/zetaoffice）领域。任务涉及 LibreOffice WASM 引擎、编辑器启动/boot、字体/IME、保活池与 LRU、自动保存、.uno: 命令、zetajs 编组时，先读本文档再动代码。
---

# 文档编辑器（LOWA）领域地图

职责边界：编辑器内核与宿主集成。AI 发编辑指令的链路属 ai-doc-bridge 领域。引擎 = LibreOffice 24.2.8 自建 zh-CN 版（LO core 分支 distro/allotropia/zeta-24-2）。

## 关键文件

**引擎构建与分发**
- `desktop/lowa-build/`：README.md（为什么自建 zh-CN）、RECIPE.md（精确配方+产物 sha256）、mega-build.sh（裸机全自动构建，PHASE_1..7）、autogen.input、patches/（两阶段 zh-CN 焙入 + ZZZ-aiworkdeck-locale-zh-CN.xcd 默认 ooLocale）。
- `desktop/scripts/fetch-lowa-assets.js` — 构建期下载 LOWA 运行时 + OFL CJK 字体到 `frontend/dist/zetaoffice/lowa/`，写 `.encodings.json`（brotli 侧车）；`LOWA_BASE_URL` 指自托管引擎（`https://www.aiworkdeck.com/lowa-engine/24.2.8-zhcn-r4/`，2026-08-07 起；r4 = r3 + Impress/Draw/Math 进包（--with-wasm-module=calc writer impress），wasm.br 42.5MB(+23%)、data.br 18.6MB；r3 保留在架作回退，desktop-build.yml 一行切换）。
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

**宿主能力层与编辑器容器（两种壳，一套 relay）**
- `frontend/src/services/host.js` — **前端访问壳的唯一出口**。业务代码一律 `import { host } from '@/services/host.js'`，禁止再直接读 `window.checkbaDesktop`（那会把 Electron 焊回业务代码）。惰性 Proxy 解析（与原先逐次读 window 的语义一致，注入时机不影响调用点）；桌面态逐字段透传，Web 态只提供浏览器里真能实现的能力、其余字段缺席（调用点原有的 `if (host.x && ...)` 守卫因此原样成立）。`isDesktopHost()` 是「是不是桌面壳」的判据。
- `host.zetaoffice.getEditor()` 返回**带 kind 的描述符**：桌面 `{kind:'webview', url, preload, partition}`、Web `{kind:'iframe', url}`。`host.zetaoffice` 在宿主没有这一项时必须缺席（app-e2e 给浏览器目标注入的最小桩只有 shell.openExternal）——无条件包一层会让调用点的 `typeof getEditor === 'function'` 守卫通过后再抛 TypeError。
- `useZetaOfficeWebview.js` — 两个宿主侧传输适配器：`webviewTransport`（Electron webview IPC）与 `iframeTransport`（同源 postMessage，收发都钉死 `location.origin` 且校验 `e.source`）。差异只在这两个 `{send, subscribe}` 里，relay/executor/worker 一字不改。
- Web 态部署布局见 `deploy/web/nginx.conf.example`（站点 root 下 `zetaoffice/`，全站 COOP/COEP）；`host.zetaoffice.isAvailable()` 在 Web 态 HEAD 探一次编辑器页，没部署就让宿主退回预览路径，而不是挂一个永远起不来的 iframe。

**宿主 UI（保活/实例管理/自动保存）**
- `frontend/src/components/ReviewPanel.vue` — 审阅面板（编辑器右栏）：修订/批注两栏清单，点击定位、逐条接受/拒绝、全部接受/拒绝、批注标记解决/删除。数据全走 worker 原语（list_revisions/goto_revision/resolve_revision/resolve_revisions/resolve_all_revisions、list_comments/goto_comment/set_comment_resolved/delete_comment），executor 由 LibreOfficeEditor 注入；处置后 emit changed → 走自动保存链路。**面板是修订的权威视图**（页边小字读不到作者/时间，且同行多格删除会在页边互叠）。**卡片是「组」不是「条」**：引擎按一次编辑操作记一条 redline，连按 Backspace 删一个词就是一字一条（页边模式下引擎不会自行合并，真机实证 5 次删除 = 5 条）；面板把 `contiguous`（worker 用区间比较给出的首尾相接判据）+ 同类型同作者同分钟的相邻条目并成一张卡，处置时**从高索引往低索引**批量 resolve（index 是枚举序，处置一条后更大的索引会前移）。**批量处置走 `resolve_revisions`，不是循环调 `resolve_revision`**（尽调 P3 稳定性余项 #1，dev-board#100）：`resolveGroup` 把一张卡片覆盖的全部 index 一次性打包成一条命令；worker 侧 `redlineAt(index)` 每次都从头整棵重新枚举 `getRedlines()`，若仍对组内每条各发一次 `resolve_revision`，K 条就是 K 次 O(N) 重扫（O(K·N)），`resolve_revisions` 改成只枚举一次（O(N)）、K 个目标 index 直接按下标取引用（退回 `redlineAt` 兜底见 office_thread.js 内联注释——真机未验证过跨多次 dispatch 复用 redline 引用是否安全，取不到就退回单条重扫，不拖累整批也不会误处置）。`resolve_revision`（单条）仍保留给 AI 工具面 `doc_accept/reject_revision` 使用，未改动。**Word 式审阅窗格的三个维度**（dev-board#377，判定全在纯函数 `utils/reviewGrouping.js`，组件只渲染与发命令）：①**作者**——`authorKind(author, selfAuthor)` 三分 `ai`/`me`/`other`（`AI_AUTHOR = 'AI WorkDeck'` 与 worker `__agent` 署名同源；`selfAuthor` 由 `LibreOfficeEditor.currentAuthorName()` 一处产出，同时喂 `load_document{authorName}` 与面板 prop，**两处必须同串**，否则用户自己的修订被归成「其他人」；**拿不到用户名时任何非 AI 作者一律 `other`**，不把未署名的算到自己头上）。卡片左侧色条 + 作者 chip，顶部四桶筛选（全部/AI/我/其他人），**四个数字恒按未筛选全量算**（`countByAuthorKind(allGroups)`，切筛选不变），tab 上的修订计数也用 `allGroups.length`。②**类型**——worker 如实回传 `RedlineType` 原串，`revisionTypeKey()` 映射成 `insert`/`delete`/`format`/`paraFormat`，**认不出的落 `other` 并原样显示引擎串**（引擎取值不止这四种：Style / TextTable / TableRowInsert…），格式类顺带显示 `RedlineDescription`（「属性已更改」）。改造前只分「Delete 与其余」，格式修订被当成插入——lowa-e2e 组 33 用真引擎的 Format 串锁住映射，引擎改了取值那条会红。③**理由**——AI 的修订理由写成批注（正文常以「【修訂理由】」开头，**前缀是模型自己写的、不固定，所以不按文本识别，只按位置**）：`linkCommentsToRevisions()` 判「同段落 + 闭区间相交或首尾相接」，坐标由 worker 的 `list_revisions`/`list_comments` 同坐标系回传；用闭区间是因为页边模式下删除型在正文流里是零宽（`start === end`），只能靠相接命中。一条批注可同时挂到多条修订（一次替换 = 一删一插），组内去重后并进 `g.reasons`；**理由不进合并判据**（否则一次替换的删+插会被切碎），作者与类型进（不同作者/不同类型绝不合并）。挂不上的批注照旧单独列，批注 tab 上标「已挂到 N 条修订」。④**处置联动**——`resolveGroup` 在 `resolve_revisions` **至少命中一条**后，把该卡的 `g.reasons` 逐条 `set_comment_resolved{id, resolved:true}`（**按 id 不按 index**，修订处置后批注 index 未必仍对得上）：只标记**不删除**（删了「当初为什么这么改」就永久消失，何况 `.uno:DeleteComment` 在宿主上下文里本来就够不着）；一条都没命中时不动批注。`resolveAll`（全部接受/拒绝）**刻意不联动**——它走 `resolve_all_revisions`，拿不到「处置了哪些」，宁可不动也不批量标掉用户没逐条看过的理由。测试：`frontend/tests/project-home/review-grouping.test.mjs`（纯函数）+ `review-panel-author-reason.test.mjs`（组件级，共用底座 `tests/_lib/review-panel-vm.mjs`——它把 `utils/reviewGrouping.js` 的**真实现**喂进被剥壳的 `<script>`，只有 EvidencePanel 是桩）+ lowa-e2e 组 33（真引擎）。**第三个 tab「底稿」**（P2 前叫「证据」，文案已统一成底稿；i18n 键仍是 `editor.review.evidenceTab`）是独立组件 `EvidencePanel.vue` + 同目录外置样式 `evidence-panel.scss`（ReviewPanel 只做壳，v-show 常驻以便 tab 显示计数）：当前文档的 EvidenceLink 清单。**顶部统计条兼状态筛选**（共 N 条 / 正常 / 待核 / 已变 / 失联，计数恒按未筛选的全量算，枚举外的 status 只进 total 不硬塞进桶）；视图两选一——**按章节走两级树**（`groupBySectionTree`：一级 = sectionPath 第一段、二级 = 前两段，三级及以下并进所属二级，只有一段路径的挂一级直属）/ **按主体**（targets 文件的 PARTY 标签，来自 `getProjectFiles(pid,null,true)` 的 tags，**懒加载、只在切到该视图时拉**——整棵树对大项目不便宜，PR#550 才把文件树改成分层懒加载）；第二个筛选跟着当前视图的维度走（章节 / 主体），**章节筛选按路径段前缀匹配**（不是字符串 startsWith，否则「一」会把「一〇」吃进来）。筛选与统计全在前端算（统计要覆盖未筛选的全量，服务端再筛一遍等于多一次往返），后端 REST 未加查询参数。视图/状态/两个筛选键按项目落 `uni.setStorageSync('project_<pid>_evidencePanelView')`（面板是 v-if 挂载的，关一次就重建）；换文档后旧筛选键不在新文档的选项里会落回「全部」，否则列表莫名全空。卡片显示状态色 + 查验方式 + **置信度**（`target.confidence`，只有 P2 勾稽核查跑过才有，null 就整个 chip 不渲染——不显示 0 也不编造）+ **引文摘要**（`locatorQuote(locator)`，只认定位符里的 `quote`，没有就留空不拿文件名顶替）。三种行（一级组头/二级组头/卡片）由 `rows` computed 拍平成一个列表，卡片模板只写一份。纯函数在 `utils/evidenceGrouping.js` 与 `utils/evidenceLocator.js`，测试 `frontend/tests/evidence/panelFilters.test.mjs`（含把 `<script>` 抽出来跑 computed 链的组件级用例）；动作：点卡片 `goto_bookmark`、「保留关联」`check_link_anchors`→`keepEvidenceAnchor`、「重新指定」`bookmark_selection(EVID_new)`+`set_selection_hyperlink`+`rebindEvidenceLink`（进入该态后点卡片不跳转）、点 target emit `locate` → ReviewPanel → LibreOfficeEditor `open-evidence-target` → project-overview `onOpenEvidenceTarget` → `openFileLinkTarget`。面板与编辑器靠 `uni.$emit('awd:evidence-changed', {docFileId, source})` 互相通知重拉（常量在 `utils/evidenceEvents.js`，按 source 跳过自己发的）。**面板计数的刷新链路有三条纪律（dev-board#460，三条都踩过）**：①**`reload()` 读失败不许清零**——`run()` 在超时 / `success:false` / 抛错时返回 null，旧实现无条件写 `|| []`，一次读失败就把 tab 打成「修订 0 / 批注 0」，而文档里躺着 AI 刚做的几十条修订，律师据此以为 AI 什么都没改；现在读不回来就保留上一次的清单（过期但真实），`this.error` 照常置位（同款口径见 `EvidencePanel.load()`）。②**`reload()` 有重入闸**（`_loading` / `_again`，照 `useEvidenceAnchors` 的 `state.recheck`）：在飞时只记一笔 defer、收尾补跑一轮，**不并发再发一轮读命令**——两条读命令堆到单事件循环的 office 线程上排在写命令后面，越忙越读不回来。③**别把刷新只挂在引擎的 `modified` 边沿上**：那条信号服务的是自动保存、`editor-main.js` 里是 500ms **前沿**节流（无尾随，见相邻 `relaySelection` 的对照实现），一批写入的最后一次常被永久丢弃 —— 表现就是「AI 写完不刷、切一次标签才对」。宿主侧另有一条**无损**接缝：AI 的每条编辑命令都是宿主自己发的，命令返回 = 这一笔写完了。`agentClientActions.js` 的 `handleEditorCommand`（写入类 action 且 `success` 时）与 `handleDocStreamEnd` 经 `notifyDocMutated()` 广播 `awd:doc-mutated {fileId}`（300ms 尾随防抖，常量与写入判定在 `utils/docEvents.js`；**判定是「只读白名单之外一律算写入」**，新增写入原语自动被覆盖，漏判一条写入就是本卡的病灶本身），`LibreOfficeEditor.onDocMutatedEvent` 按 fileId 认领后 bump `reviewRefreshKey`。另：`loadDocument()` 换文档后要**同时**补 `uiRefreshKey++` 与 `reviewRefreshKey++`（版本退回 / 检查点恢复 / AI 直改文件都经 `reloadFromBackend` → `loadDocument`），只补工具栏那一个的话面板会端着上一份文档的清单。**修 `relayModified` 加尾随重发是错的路子**：那条尾随发生在编辑器页、在 worker 的 `exportInFlight` 保存闸之外，会把导出前发出的 modified 补发到导出之后，等于保存完立刻重新标脏（save→modified→save 的 3 秒循环正是那个闸要防的）。`EvidencePanel` 也订阅同一条事件（底稿那一半的写入有一半在后端——`doc_link_evidence` 是 worker 打书签 + 后端建 EvidenceLink，后端那半边不发 `awd:evidence-changed`）。测试 `frontend/tests/project-home/review-panel-reload-resilience.test.mjs`、`agent-doc-mutation-refresh.test.mjs` 与 `tests/evidence/panelFilters.test.mjs` 末条。
- `frontend/src/components/EvidenceStaleBar.vue` + `composables/useEvidenceAnchors.js`（dev-board#105）：锚点核对状态机全在 composable——`classifyAnchorResults(cache, items, hashFn)`（`!exists || text===''` → orphan；orphan 不因 exists 复活；hash 变 → stale，**已是 stale 的不重复上报**）、`applyReport(cache, reports, changed)`、`resolveKeepText(exec, key, fallback)`（「保留关联」先问 worker 当前文字，问不到才用缓存）、`createAnchorChecker(deps)`（3s 防抖 `schedule()`、`_cmdBusy>0` 1s 重试、**重入时 defer 而非丢弃**（`state.recheck` → finally 里再排一次）、`flush()` 在有防抖或 defer 时立刻跑、每批 ≤200）；单测 `tests/evidence/anchorCheck.test.mjs`。LibreOfficeEditor 只接线：ready 后 `initEvidence()`（拉 `_evidenceCache` → 仅 writer 跑 `adopt_legacy_links`，有收编即 onDocModified 落盘 → `checker.run()`）；`onDocModified` → `scheduleAnchorCheck`；`flushSave` 前 `checker.flush()`、`reloadFromBackend` 后 `schedule`；`onStale` 回调经 `StaleQueue`（`utils/evidenceStaleQueue.js`：同 key 3s 内只弹一次、ignore 本会话不再弹）flush 进 `staleItems` 渲染提示条。提示条绝对定位钉在 `.libre-canvas-wrap` 顶部（与状态胶囊同一条「钉画布不钉外层」规矩）。**`listEvidenceLinks` 缓存为空时整条链路静默不跑**，没建过链的文档零开销。
- `frontend/src/components/LibreOfficeEditor.vue` — 单文档编辑器组件：webview 创建、prefetch、load/export、autoSave、flushSave、reloadFromBackend。支持**备胎过继**（watch file 仅 null→文档；引擎已就绪走 finishDocLoad，未就绪由 onEndpointReady 接手）与**只读预览接力**（字节预取完成即 docx-preview 本地渲染，previewReady 后 overlay 变成可滚动阅读 + 顶部细进度条，ready 后整体消失）。
- `frontend/src/pages/project-overview/librePool.js` — 保活池方法组（Phase 1 外置）：libreLruKeys/touchLibreLru/evictLibreInstance、syncLibreExecutor 活跃指针、`_libreRefs`/`_libreExecMap` 非响应式注册表、reloadActiveLibreInstances（版本退回/检查点恢复后就地重载）。**按文档体积计权**（尽调模块 P3 稳定性余项 #2，dev-board#100，取代旧的固定 `LIBRE_KEEPALIVE_MAX = 3`）：`LIBRE_SIZE_UNIT_BYTES`=2MB 是 1 个权重单位，`LIBRE_WEIGHT_BUDGET`=6 是总权重上限，`libreInstanceWeight(fileSize)` = `max(1, ceil(fileSize / 2MB))`（体积未知按最小权重 1，退化成旧的按数量语义）。`touchLibreLru(pane, fileId, fileSize)` 新增第三参——刚激活那份的体积由调用方 `onActiveOfficeFileChanged` 直接传入，池里其它 key 的体积经 `libreWeightOf(key)` 从 `_libreRefs` 已挂载实例的 `file.fileSize` 反查（未挂载按权重 1）；从最近使用往回累计权重，一旦超预算，该 key 起（含自身）全部是淘汰候选，交给 `evictLibreInstance` 逐个判活动文件保护再淘汰。150 页/6.6MB 级文档权重=4，两份即超预算，避免旧版"三个大文档同时驻留吃到约 2.4GB"（实测基线）；普通几百 KB 文档权重恒为 1，同时保活数量不降反升（旧固定 3 → 最多可到 6）。活动文件保护不变：`evictLibreInstance` 对 `left:activeFileIdLeft`/`right:activeFileIdRight` 一律跳过，与体积无关。**预热备胎**（PR#220）：libreSpares（{key, file}，file=null 是后台预 boot 的空白隐藏实例），onActiveOfficeFileChanged 里 maybeAdoptLibreSpare（须在 touchLibreLru 之前，靠"不在 lru 记账"识别无实例）过继给池外首开文档，过继后按 'left:fileId' 常规记账；补胎在过继 ready 后（scheduleLibreSpare，4s 延迟）。仅左窗格设备胎（webview 不能跨容器移动）；h5 无 checkbaDesktop 不建胎；常驻多一个空白实例内存（数百 MB）。

## 本地写作辅助（dev-board#538）

- `zetaOfficeCompletion.js` 是客体 DOM 菜单，`writingAssistanceHost.js` 是项目/用户词库与 API 宿主；`editor-main.js` / IME overlay 只提供接线。Writer 就绪且可写时启用，换文档、重载、卸载销毁 session；中文组合输入优先，方向键/Tab 只在候选展开时接管，Enter 保留换段。
- 本地候选覆盖机构、人名、法规、条款、案例/案号、常用词及表述。`completionLexicon.js` 负责确定性提取和前缀匹配；打开文档仅采集有界实体到项目词库，个人词库只从本人输入/采用学习，低频词与表述不立即出候选。用户级开关同步当前各文档，可删除/清空学习项。
- 文档候选按同一 revision 分页读取（最多 1,000 段/200,000 字/500 候选），首次读取与显式刷新才扫描；首次读取被编辑打断时，等待 1.2 秒停笔后重试，成功后不随每次按键重扫；机构、人名、法规、表述按类别保留名额。完整法规名称用于展示，裸法规/条款前缀使用匹配的补入形式；不得补出半个书名号。持股叙述的人名、机构前的“股东名册中”等已覆盖回归。
- 打字只读客体内存，不调用外部库或模型。选中正文右键明确查询才走 `/completion/lookup`；资料由 `completionDetails.js` 转成带来源的预览，点击插入才修改正文。
- 右键「二选一」由 worker 裁决（dev-board#601）：`installContextMenuInterceptor` 在每次换 controller 时注册 `XContextMenuInterceptor`，`hostHandlesContextMenu()` 为真（宿主可写、Writer、选区 1–160 字、行内「全部修订」视图下选区不碰删除修订）时返回 CANCELLED，原生菜单根本不弹；宿主右键走 `get_context_menu_context`（同一谓词，再经 FINAL_TEXT 视图取终稿文字），只在谓词成立时出 HTML 菜单。开关由 `set_host_context_menu` 随 writable 变化下发。**地雷：别再用合成 Escape 去关已弹出的 Qt 菜单**——关掉后 Qt 残留弹窗状态，下一次右键按下会移动光标、丢选区（真机复现：第二次打开即丢）；不带 Escape 时下一次按下又会点中残留菜单的「插入批注」。回归 `tests/lowa-e2e/context-menu.mjs`（真实按住/松开，像素判定两种菜单只出其一）。
- UNO 三动作 `get_completion_context` / `accept_completion` / `insert_completion_content` 以不透明 token 校验模型、光标/选区两端及上下文；补全只追加后缀，表格/纯文本原样插入，整组一次撤销。真实修改使 token 失效，只读导出期间保留 snapshot（含恢复 modified 标志），不能因自动保存误拒插入，也不能放宽位置校验。行内修订视图停用。
- 首期能力止于确定性词库匹配、显式资料查询和原子插入；自动语义诊断、逻辑审校、段落推理与自动改写不在本期范围。
- 回归：`npm run test:completion`、`test:lowa-completion`、`test:writing-ui`、`test:writing-caret`、`test:lowa-link-preview`、`test:writing-desktop`；引擎测试包含移动/输入/重载拒旧 token、跨导出仍可插入、撤销/重做及资料表格。桌面用例从真实项目词库经中文输入/Tab 到自动保存后下载 DOCX 核对，夹具文件隔离在临时目录。

## 保存失败与关闭（实测清单 A6/C10）

- `LibreOfficeEditor.uploadBytes` 的 XHR 必须有 60s 超时与中止终态；导出沿用三层 180s 预算。
- 失败保留 `dirty`，暂停自动重试并在状态胶囊给「重试保存」。引擎超时不代表导出已停止，不能每 15s 再排一笔。
- `flushSave` 返回布尔结果，默认等完成；标签关闭与 LRU 显式给 10s 等待预算。超时仅返回 false，不并发另起保存，也不清掉在途操作。
- `closeFile` 保存失败时让用户选「继续编辑 / 放弃并关闭」；不能用 `isError` 跳过保存（保存失败也算 isError）。只有 `docLoadFailed` 才表示空白 boot 文档，不得覆盖后端真文件。
- 明确放弃走 `discardPendingSave`：关掉上传闸并中止当前 XHR，迟到导出不得再上传。LRU 遇到失败/超时保留实例。
- flush 期间的修改暂缓自动保存；`_flushPromise` 清除后必须补排仍然 dirty 的内容，否则取消关闭后会永久停在 dirty + ready。
- 时序回归：`frontend/tests/project-home/libre-save-recovery.test.mjs`；LRU 失败保留：`libre-pool-weighted-keepalive.test.mjs`。

## 纯文本分流（dev-board#37，不进 LOWA）

- `txt/md/markdown` 走 `frontend/src/components/PlainTextEditor.vue`（CodeMirror 6），**不进 LOWA**。分流表 `fileOpenTabs.js` 的 `PLAIN_TEXT_TYPES`（模块顶部常量）+ `isPlainTextFile()`；判定在 `isEditorOpenableFile` 的 wpsFileId 兜底**之前**——上传文件都被合成了 wpsFileId，不先拦就会被兜底判成"可编辑"送进引擎。当前刻意只收这三种扩展名，别顺手加 json/js。
- 该组件是 **v-if 单实例**（每窗格至多一个，无保活池/LRU/备胎那一套）：切标签即销毁，未保存内容由 `beforeUnmount` 兜底落盘 + `closeFile` 的文本分支显式 `flushSave`。实例登记在 `_plainTextRefs`（`setPlainTextRef`，非响应式，对齐 `_libreRefs` 口径）。
- 存取走与 LOWA 相同的通用字节接口（GET /download、POST /upload multipart）——upload 成功后端 `signalChange` 自动接版本记录，无需额外接线。保存闸：下载失败或"fileSize>0 却空下载"即禁编辑（`_loadOk`，PR#194 同款事故的朴素版预防）。
- **版本退回/AI 直改后必须走 `reloadPlainTextInstances(fileId)`**（fileOpenTabs.js）：`onVersionReloadFiles` 与 `handleTextReloadFile`（SSE `text_reload_file`，AI text_* 工具后端直改后下发）都调用它，让正在显示的实例 `reloadFromBackend()` 就地重载并丢弃本地未保存态——不做的话画面不变、下次 autosave 把旧内容写回去。未激活的文本标签没有实例，下次挂载自然拉新内容。
- 真 `.md` 文件自此可编辑（编辑/预览切换在组件内，markdown-it 渲染）；`isMarkdownTab` 已收窄为只认 `tabType==='markdown'` 的 AI 虚拟产物标签。
- AI 侧对纯文本走后端直读直写（`text_write_file`/`text_find_replace`），不经编辑器桥——契约见 ai-doc-bridge.md。

## 启动链路（打开 docx → 可编辑）

激活文档 → 渲染 LibreOfficeEditor → getEditor() IPC（装隔离+起服务）→ 建 webview（persist:zetaoffice）+ 并行 prefetch 文档字节 → editor.html/editor-main.js 选传输 → bootZetaOffice（校验 crossOriginIsolated、fetch CJK 字体、fontconfig conf、加载 soffice.js、uno_main resolve worker port）→ office_thread.js boot（Desktop.create → 空白 swriter、RecordChanges=true、installModifyListener → ui_ready）→ executor 握手 ready → loadDocument：`load_document {bytes}` 写 MEMFS + loadComponentFromURL 重定位 xModel → ready → onLibreReady 注册 executor + syncLibreExecutor。

## 画布配色（知识存档：深色化已否决回退，勿再启用）

- PR#243 曾做「深绿画布上浮纸页」并已随配色回退撤销（维护者否决深色）。**引擎画布保持默认浅色**。
- 应用配色（dev-board#273 已落地）：`set_app_theme` 命令（office_thread.js，非 AI 白名单，宿主经 lo-relay `set-theme` 消息触发）用 ConfigurationUpdateAccess 写 `/org.openoffice.Office.UI/ColorScheme` 的 AppBackground——深浅主题下纸外工作区分别 #101214/#F1F3F5（与宿主 `--awd-canvas` 同源，改一处必须同步 editor.html 的 CSS 与 office_thread 的常量）；DocColor（纸张）刻意不动，深色下纸仍是纸白。初值走 editor URL `?theme=` 参数（editor.html 头部内联脚本防白闪），ready 后 LibreOfficeEditor.pushTheme() 补发一次盖住保活池过继的旧参数。工具栏 chrome 精确配色 LO 24.2 无注册表口，须 QPalette 补丁重烧（未做）。

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

## 长命令的分批 / 进度 / 取消与段落索引（dev-board#108，PR 见 perf/worker-batching-big-doc）

- **office 线程是单事件循环**，一条 20s 的命令会把自动保存的 export、IME 的 ui_command 全排队。`find_replace`（全部替换走引擎原生 `replaceAll` 一次完成；只替首处 / 纯插入型回退到逐命中路径，命中 > 50 时按 30 一批）与 `apply_house_style`（500 个顶层元素一批）现在是 **async 原语**：批间 `batchBreak()` 发 `progress`、`await` 一个宏任务、查 cancel、重设修订作者（批间别的命令可能把作者切成用户名）。`execCommand` 对返回 Promise 的原语等它结束再 post result，同步原语路径不变。新增分批原语照 `batchBreak(p, done, total)` 的口径写，**批间允许别的命令插进来**（含写命令），别在批间持有跨批失效的 UNO 对象假设。
- **进度链路**：worker `post('progress', {reqId, done, total})` → `libreofficeExecutorClient` `cmd:'progress'`（按 pending reqId 回调 `callOpts.onProgress`，也给全局 `opts.onProgress(reqId, p)`）→ `serveExecutor` 转成 relay `type:'progress'`（reqId 换成宿主那一侧的）→ `createRelayExecutor({onProgress})` → `LibreOfficeEditor.vue` `$emit('command-progress', {reqId, done, total})`。`apply_house_style` 的 `total` = 正文段数（走 paraIndex）+ 表数，`done` 是已处理顶层元素数；取消时批间那帧已报过同一个 done，结束不再补帧。宿主侧 `project-overview.vue` `onEditorCommandProgress` 用 toast 显示「处理中 x/y」（AI 过程卡没有工具内进度位，先降级）。
- **取消**：宿主 `executeCommand('cancel', {reqId})`，reqId 取自 progress；`serveExecutor` 用 inflight 表把宿主 reqId 换成 worker reqId。协作式：只置位 `CANCELLED[reqId]`，命令在下一个批间检查点停下，返回 `{success:true, cancelled:true, done}`（`apply_house_style` 同时 `truncated:true`；正常完成永远带 `truncated:false`）。
- **分级超时三处同表**（改一处同步另两处）：`libreofficeExecutorClient.js` / `zetaOfficeRelay.js` 的 `ACTION_BUDGET_MS`（load/export 180s；find_replace / apply_house_style / apply_style_profile / resolve_all_revisions / resolve_revisions / insert_table 120s）与后端 `EditorBridgeService.ACTION_TIMEOUT_SECONDS`（默认 30s）。
- **段落索引缓存** `paraIndex`（`buildParaIndex` 一次枚举存每段 XTextRange）：`get_document_text` / `get_paragraph` / `modify_paragraph` / `select_paragraph` / `eachParagraph` 全走它，150 页二次读从 2s 降到毫秒级。失效：modified 监听器（主闸）、`verifySnapshot()`、undo/redo、索引绑定 `xModel` 引用（换文档自然重建）。`withParaIndex(fn)` 里 **fn 只许读**——段落对象失效抛异常时会重建再调一次；写原语先 `paraAt(idx)` 拿段落再在外面改。
- **大文档基线组** `npm run test:lowa-big`（`tests/lowa-e2e/big-doc.mjs`，夹具 `fixtures/gen-big-doc.py` 生成到 `$TMPDIR`）：150 页 / 30 表 / 20 图，六项硬阈三次中位数；改 worker 的全文路径后必跑，数字表在 `tests/lowa-e2e/README.md`。
- **写入慢的真根因是 JS 实现的 XModifyListener**（2026-08-22 探针实证）：只要装着它，引擎每条文档写入（一条 `setPropertyValue` / `setString`）都回调进 JS 一次，一次约 35ms，与回调体做什么无关（空函数体同样 35ms）；摘掉后同一条写入 0.1ms，读操作（getString/getPropertyValue）本来就只要 0.06ms。与 RecordChanges 开关、`lockControllers`、undo 都无关。所以 **批量写命令必须包在 `lockModel()/unlockModel()` 里**——它除了锁控制器还会 `suspendModifyListener()`（摘下监听器，结束装回并补发一次 `modified`）。**两者都是深度计数**：`batchBreak` 取消返回前已解过一次锁，调用方的 `finally { unlockModel() }` 再解一次不会让 `removeActionLock/unlockControllers` 下溢（下溢会让 `hasControllersLocked()` 余生恒 true，PR#554 复核抓到）。`cancel` 只对 `INFLIGHT` 里的 reqId 置位，已结束的返回 `stale:true` 不留 `CANCELLED` 痕迹；lowa-e2e 组 28 断言取消后锁平衡、文档可编辑、`modified` 仍触发；`resolve_all_revisions` 只摘监听器不锁控制器（派发走视图）。`applyHouseChar/applyHousePara` 同时改成 `setPropertyValues` 批写，对象不支持或被拒时自动退回逐个写。
- **find_replace 全部替换走原生 `XReplaceable.replaceAll`**（`nativeTrackedReplaceAll`）：150 命中 0.16s（逐命中 15-20s），RecordChanges 开着时同样每命中一条删除+一条插入修订。为保住 PR#188 的字符级颗粒度，先掐掉 findText/replaceText 的公共前后缀，用正则 `(?<=前缀)中段(?=后缀)` 只替差异中段（替换串里 `\ & $` 要转义，`replaceEscape`）。**引擎不接受零宽匹配**：纯插入型（验收后→验收合格后）回退逐命中路径，进度/取消只在该路径出现。

## LO chrome 的取舍（自建工具栏路线，spike 已验证）

维护者定调：**最终形态是自建工具栏**，LO 自己的 menubar/toolbar 退场（菜单栏末端那个 × 会把 webview 里的文档关掉）。分期方案见 `docs/superpowers/specs/2026-08-14-editor-chrome-self-built-toolbar.md`。

- 入口：`ctrl.getFrame().getPropertyValue('LayoutManager')`；元素 URL 形如 `private:resource/menubar/menubar`、`private:resource/toolbar/standardbar`、`private:resource/toolbar/textobjectbar`、`private:resource/statusbar/statusbar`，外加 11 条 `singlemode-*` 上下文工具栏（选中表格/图片时自动冒出，逐项关必须连它们一起关）。全集在 `office_thread.js` 的 `CHROME_URLS`。
- **`hideElement()` 的返回值恒为 false，不代表失败**——必须用 `isElementVisible()` 复核。
- **恢复（逃生开关）必须复核 + 重试**：藏过全套（含 11 条 `singlemode-*`）之后，单纯 `showElement()` 有时恢复不出来（真机实证，e2e 组 26 锁住）。可靠顺序是 `showElement` → 不行就 `createElement` + `showElement` → 再不行就 `setVisible(true)` + `showElement`，每步都用 `isElementVisible` 复核。这是「体验不能退步」的兜底保证，不能靠一次调用碰运气。
- 标尺不归 LayoutManager 管，是 `ViewSettings.ShowHoriRuler/ShowVertRuler`。
- 隐藏 chrome 后编辑、`.uno:` 派发、格式原语、缩放全部照常；引擎自带对话框仍画在 canvas 上，不受影响。
- **P1 命令层原语**（宿主发起，非 AI 管线）：`get_ui_state`（工具栏激活态一次拿全，实测 ~6ms）、`list_styles`（`name` 程序名 + `display` 显示名 + `inUse`）、`set_chrome`、`set_track_changes`（直接写 `RecordChanges`，比派发切换语义的 `.uno:TrackChanges` 可靠）。工具栏按钮统一走 `ui_command` 白名单，**不许改成任意 `.uno:` 透传**。
- **`.uno:Grow` / `.uno:Shrink` 在本引擎是哑弹**（派发不报错，CharHeight 纹丝不动）。字号步进走 `get_ui_state` 读当前值 + `format_selection {fontSize}`。参数名不对称是既有契约：读回叫 `sizePt`，写入叫 `fontSize`，别改。
- 撤销/重做可用性走 `xModel.getUndoManager()`（XUndoManagerSupplier 的**方法**）；`getPropertyValue('UndoManager')` 抛 UnknownPropertyException。
- `XSelectionChangeListener` 装得上、选区变化每次触发，但**纯光标移动基本不触发**——工具栏状态刷新必须是「事件 + 聚焦时轮询」混合。
- **主命令区的横向滚动条必须藏掉**（dev-board#502）：`.etb-scroll` 是 `<scroll-view scroll-x>`，占位式滚动条（macOS「始终显示滚动条」/无头 Chromium）有 **15px** 高，把这一段撑到 41px 塞进 38px 的行里，`align-items:center` 于是把左半边顶得比 `.etb-right` **高 7.5px**（真 Chromium 量出来的数）。藏法与标签栏 `.tabs-scroll` 同一套：`:show-scrollbar="false"`（uni-h5 给真正 overflow 的内层元素挂 `.uni-scroll-view-scrollbar-hidden`，那条规则在 h5 产物的 uni.css 里确实存在）+ scoped 的 `:deep(*::-webkit-scrollbar){display:none}` 兜底（**只写 `.etb-scroll::-webkit-scrollbar` 无效**：scoped 属性只落在 `<uni-scroll-view>` 根元素上，真正滚动的内层 div 不带 scope id）。藏了就必须接 `@wheel.prevent`（`onToolbarWheel`）把纵向滚轮映射成横滚，否则窄窗口下右半截命令够不着。别给 `.etb-scroll` 定高——高度跟着内容走才谈得上左右居中对齐。
- **工具栏弹层（样式/字体/字色/高亮/插入五个下拉）必须 fixed 定位**（dev-board#245）：工具栏行是横向 scroll-view（竖向 overflow hidden），外面又套 pane/workbench 一串 `overflow:hidden`，绝对定位的菜单会被裁得只剩顶边、看起来就是「点了打不开」——从自建工具栏诞生（#356）起就裁死，任何 e2e 都没点过下拉所以一直漏网。现行实现：`toggleMenu`/`openInsert` 打开瞬间 `capturePopPos` 取触发器视口坐标，`popStyle(width)` 内联 `position:fixed` + z 900（弹窗遮罩 1000+ 之下、窗格与编辑器 webview 之上）；`popPos` 取不到时退回原 absolute（会被裁，属降级）。新增弹层照抄这套，别再放回文档流。desktop-e2e 有「还原病灶即转红」的回归步骤（真实鼠标点开 → hit-test 命中菜单 → 选「标题 1」引擎样式真变）。
- `format_selection` 拒绝空选区，工具栏「先设格式再打字」这条路目前是断的（P2 待补）。
- **本引擎不支持尾注**：`insert_endnote` 设 `IsEndnote` 时抛 IllegalArgumentException（脚注正常）。工具栏刻意没有这一项；e2e 组 26 锁住「明确拒绝且给出可读原因」，将来引擎支持了那条会红，提醒把菜单项加回去。
- **`insert_footnote` 之后视图光标停在脚注区里**：紧接着的 `select_all`/`replace_selection` 会打在脚注上而不是正文。
- **`replace_selection` 处理不了跨表格的选区**：静默什么都不改。验证"还能编辑"要用 `insert_at_cursor`，别用 select_all + replace。
- **LO 对话框在本 WASM 构建上不可靠，不许挂进自建菜单**（真机审计）：`.uno:TableDialog`/`.uno:InsertSymbol`/`.uno:SpellDialog` 派发**完全没反应**；`SearchDialog`/`FontDialog`/`ParagraphDialog`/`InsertTable`/`HyperlinkDialog`/`PageStyleName`/`InsertGraphic`/`BulletsAndNumberingDialog`/`WordCountDialog` 弹得出来但**键盘关不掉**（把 IME 覆盖层 blur、焦点还给画布后按 Esc 同样无效——所以不是覆盖层抢焦点造成的）。需要对话框的功能一律自建 DOM 面板。今天 LO 菜单栏还露着，用户点开「查找和替换」就可能卡住；P4 隐藏 chrome 顺带消除这个坑。
- **查找导航不要用 `find_text_locations`**：它每个匹配插一个锚点书签，书签会跟着文档存进 docx（用户只是搜个词）。用 `find_navigate`（findFirst/findNext + `compareRegionStarts` 定位当前序号，只动视图光标）。

## 修订视图三态与对应 .uno: 命令（dev-board#368）

工具栏右侧「修订显示方式」下拉，纯显示切换——**不改一个字节的内容、不动 `RecordChanges`、不处置任何 redline**（e2e 组 32 用 redline 条数守着）。

| 态 | 文案（zh/en） | `ShowChanges`（只读判据） | `ShowChangesInMargin` | 对应 LO 命令 / 真正的写法 |
|---|---|---|---|---|
| `all` | 全部修订 / All markup | true | false | `.uno:ShowTrackedChanges` 开 = `RedlineDisplayType` 写 2 |
| `margin` | 简洁标记 / Simple markup | true | true | `.uno:ShowChangesInMargin` 开（LO 7.1+，tdf#34355）= ViewSettings 直写 |
| `final` | 最终稿 / Final | false | false | `.uno:ShowTrackedChanges` 关 = `RedlineDisplayType` 写 `shortAny(0)` |

- 引擎支持实锤（2026-09-02 真机，24.2.8-zhcn-r4，e2e 组 32 用 `debug_revision_view_raw` 绕开原语直读复核）：页边显示读写都通；隐藏修订走 `RedlineDisplayType`。`revisionMarginSupported:false`（读不到 `ShowChangesInMargin`）时工具栏自动摘掉中间项退成两态。
- **`ShowChanges` 属性写不进去**（本引擎实证）：`setPropertyValue('ShowChanges', false)` **不抛异常也不生效**——静默空写。隐藏修订的唯一可用路线是 `RedlineDisplayType`（`com.sun.star.document.RedlineDisplayType`：0=NONE / 1=INSERTED / 2=INSERTED_AND_REMOVED），**且必须 `shortAny()` 带类型 Any**（裸 number 未验证）。e2e 组 32 锁住这条空写，引擎哪天修好了那条会红，提醒把实现简化回属性直写。
- **`RedlineDisplayType` 的读回值不能当判据**：写 NONE(0) 之后读回来是 INSERTED(1)（插入文字本来就得留在版面里，引擎自己归一）。判「现在到底显不显示」一律读 `ShowChanges`——它**读**是诚实的（rdt=2 → true，rdt=1 → false），只是不能写。
- **两个开关分属不同层**：`ShowChanges` 是**模型属性**（跟着文档走），`ShowChangesInMargin` 是**控制器的视图设置**（跟着控制器走）。所以 `bootDoc` 与 `load_document` 的 `retarget` 都必须调 `resetRevisionView()`——保活池里一个 worker 连开好几份文档，不复位就是「上一份设了最终稿、下一份打开修订痕迹默默不见」。lowa-e2e 的 `debug_fresh_document` 探针同样要跟着调它（原 `showDeletionsInMargin()` 已并入）。
- **产品默认 `DEFAULT_REVISION_VIEW = 'margin'`（页边），理由是 AI 文本读取契约，不是观感偏好**：页边模式把删除的文字移出正文流，于是 `get_document_text` / `get_paragraph` 读到的正文就是「改后的样子」，`find_text_locations` / `replace_nth_match` 的 matchIndex **只数可见匹配**（dev-board#369 的用例明写了这条靠页边成立）。换成内联，AI 多轮改稿读到的正文会混进被删的旧字、按可见匹配的计数会错位——那是 AI 编辑契约的回归，不是测试口径问题。曾按「对齐 Word 默认」把它改成 `'all'`，正是撞在这上面被推翻的。**改这个常量 = 改上述契约**，别顺手动。
- **AI 命令另有守卫，让用户可以放心切内联**（`runAgentCommandInMarginView`，execCommand 里对带 `__agent` 的命令生效）：当前视图是 `all` 时，执行前临时切页边 + `refresh()`，执行完切回用户所选的态 + `refresh()`；页边态与最终稿态的正文本来就不含删除文字，直接放行零开销（默认路径因此没有任何额外成本）。豁免 `set_revision_view` / `export_document` / `load_document`（前者会打架，后两者自带包装）。非 Writer 一个属性都不碰。原语可能是 async（分批的 `find_replace` / `apply_house_style`），恢复必须等它 settle，同步抛异常也要还原。**还原病灶即转红**：摘掉守卫后，内联态下 `get_document_text` 读回 `甲方一、甲方二、甲方三。`（混着被删的字）、`find_text_locations` 数出 3 处而不是 2 处（e2e 组 32 的守卫段 3 条转红），装回即绿。
- **不要在非 Writer 模型上问 Writer 专属属性——哪怕包在 try/catch 里**（本轮踩到的最贵一条，七次全量 e2e 才定位）：`withInlineMarkupForExport` 早期版本对**每次**导出都先 `xModel.getPropertyValue('ShowChanges')`，在 Impress 模型上这一问会把引擎搞坏——**紧随其后的 pptx 重新打开要么超过 `load_document` 的 180s 预算超时，要么抛 emscripten 的 `operation does not support unaligned accesses`**（lowa-e2e 组 23，五跑五中；把默认换回页边照样中，说明与三态无关；父提交不问这个属性就全绿）。修法是函数头上 `if (!isWriterDoc()) return fn();`——一个属性都不碰。`get_ui_state` / `set_revision_view` / `retarget` 早就有这个守卫，唯独导出包装漏了。
- `.uno:ShowTrackedChanges` 只当兜底：它是**切换**语义，要设成确定状态就得先读再判；`applyShowChanges` 只在读得回 `ShowChanges` 时才敢退到派发，读不回就如实报失败——不许蒙着切。
- 原语 `set_revision_view {mode}`（已入 `EDITOR_ACTIONS`，宿主发起非 AI 管线）：不带 `mode` = 只读查询；返回的是**读回来的真实状态**（`mode/showChanges/showChangesInMargin/marginSupported`，另带 `requested` 与 `warnings`），不是把入参抄回去。`get_ui_state` 的 `view` 段同样带这三个字段——工具栏高亮读的就是它，不许本地记一份猜。
- 非 Writer 文档不给字段也不给控件（Calc/Impress 没有修订机制）；`set_revision_view` 对它们明确拒绝。
- **页外审阅区宽度 `AwdReviewSidebarWidth` 只有两处可以写**：用户经 `set_revision_view` 从别的模式切进 balloons（`applyRevisionView(mode, reserveGutter)` 预留 280）与宿主的 `set_review_balloons`（仅插入/无批注时释放为 0）。导出、修订处置、AI 读正文这些临时切视图再切回的路径**一律不写宽度**（临时视图本来就不改它），否则每次自动保存都把宿主已释放的空栏重新撑开：整页重排、横向跳两次。单测 `tests/revision-view/review-gutter-restore.test.mjs`。
- **处置修订的光标摆位必须跟着显示模式分支**（`selectRedlineRange`，真机红绿实证）：删除型在**页边**模式下塌陷到区间起点、在**内联**模式下必须跨选整段区间（删除文字就在正文流里）。把默认切成内联试跑时，摆位没跟着改的那一版让 lowa-e2e 组 18 的「resolve_revision 拒绝删除型」立刻转红（`修订未被处置（引擎未命中该条）`、redline 条数不减），加上 `readShowChangesInMargin() === true` 的分支后转绿。默认虽已改回页边，这条分支必须留着——用户在工具栏切到内联后，审阅面板的逐条处置走的就是内联那一支（e2e 组 32 显式切到内联跑一遍删除型/插入型 accept）。插入型两种模式下都是跨选，不分支。
- **定点取样的像素断言会被显示模式挪掉**：显示模式改变纸在画布里的落位（页边模式在纸侧留出那条边，内联不留），lowa-e2e 组 30 的 `set_app_theme` 探针原本定点取画布左缘中部那一小块当「纸外工作区」——把默认切成内联试跑时那一点变成纸白，深浅两态都读 255、断言必红（跟主题毫无关系；缩到 50% 再取也一样红）。探针已改成**整幅前后对比**：同一块画布在深/浅两态各截一张，逐像素比亮度，变暗超过阈值的像素占比 >5% 即判定重绘——纸和文字两态不变、天然不参与计数，不用再猜纸在哪。新的像素断言照这个路子写，别再定点取样。
- **内联态下读不出「结果文本」**：正文里旧字新字同框（把「三十」改成「六十」，`get_document_text` 读回「六三十」；表格单元格同理）。所以默认态才是页边（见上）；**用户手工切到内联之后，凡是「改完读回来核对」的逻辑都要留意这条**——AI 侧由 `runAgentCommandInMarginView` 兜住，宿主侧自己发的读取命令（工具栏、审阅面板）看到的就是用户所选的语义。lowa-e2e 里切模式的断言集中在组 31/32，组 23 之前的用例一律跑在默认的页边语义上。

## 已知地雷

- boot 三地雷勿回退（canvas 必须 id=qtcanvas 且禁 border/padding；COOP/COEP 缺失 SharedArrayBuffer 不可用；locale shim）。
- **久置/失焦回来打开文档是空白页 + 「文档加载失败」——两条冻结源，改一处不够（dev-board#539）**：①`<webview>` 的 `webpreferences` 必须带 **`backgroundThrottling=no`**（`LibreOfficeEditor.createWebview`，与 `contextIsolation=yes,nodeIntegration=no` 同一串逗号分隔）——窗口失焦/被遮挡时 Chromium 把 guest 的定时器降到 1/min、rAF 停摆，LOWA 的 Emscripten/Qt 事件循环跟着冻住；②保活池里非激活的实例**不许用 `v-show`（display:none）**藏——`display:none` 会让 Chromium 直接把 guest 判成不可见，同样冻。现在两个窗格都改成 `:class="{ 'libre-standby': … }"`（`project-overview.vue` 的 `leftLibreFiles`/`rightLibreFiles` 两个 v-for），CSS 与预热备胎的 `.libre-spare-standby` 合成同一条规则（`position:absolute; inset:0; visibility:hidden; pointer-events:none`，`project-overview.scss`）。冻住的表现不是报错而是**沉默**：下一次 `load_document` 撞满 relay 的 180s 墙钟预算才失败，画布上留的是 boot 出来的空白原型。顺带说明为什么这一改不触发 dev-board#503 的黑帧——`.editor-pane` 里除了 `.pane-content` 只有绝对定位的 `EvidenceMethodBar`，标签栏在 `.editor-pane` 之外，所以「flow 里的 flex:1」与「absolute inset:0」是同一个矩形，激活/退居不改 webview 尺寸；仓里也没有任何 display:none→显示的 resize 补偿逻辑会因此失效（grep 过，本来就没有）。
- **备胎过继前必须探活**（`LibreOfficeEditor.adoptAndLoad` / `probeGuestAlive`，dev-board#539）：`_endpointUp` 只是「历史上握过手」的布尔，备胎可能已经在后台空转几小时、guest 早被系统回收。过继路径（`watch: file` 的 null→文档）先发一条 `get_ui_state`（`PROBE_ACTION`）探活，预算 3s（`PROBE_BUDGET_MS`），失败就丢掉备胎走 `remountEditor()` 冷启动。3s 靠 `createRelayExecutor` 新增的 **`callOpts.timeoutMs` 显式覆盖**——`ACTION_BUDGET_MS` 是「只抬高不降低」的下限表，把 3000 写进那张表是个空操作（`Math.max(30000, 3000)`）。
- **`render-process-gone` 与 `unresponsive` 现在有人接**（`createWebview`）：gone（久置被系统回收 / OOM / 崩溃）→ 清脏 + 丢预取字节 + `remountEditor()`，`onEndpointReady` 接着重装当前文件；10s 内再次 gone 不重启（防崩溃风暴循环）。unresponsive **只记日志**——引擎跑大文档排版本来就会长时间不响应，一律重启会把正常的重活当故障杀掉。
- **`remountEditor()` 现在会把 `ready` 与 `_endpointUp` 一起置 false**：承载引擎的元素已经拆了，这两位不复位，加载面板就不会重新亮出来（旧的唯一调用方——boot 失败后的重试——本来这两位就是 false，对它是恒等操作）。
- **relay 超时自愈只做一次**（`finishDocLoad` 的 catch）：`shouldSelfHealLoadFailure(msg, this._loadSelfHealed)`（`utils/editorLoadFailure.js`）为真时 `remountEditor()` 重装一次，**不置 `docLoadFailed`、不落失败态、不发 ready**——所以与 `onLateLoadResult` 的撤回逻辑天然不打架（那个回调第一件事就是判 `docLoadFailed`，且 remount 会 dispose 掉旧 executor 连订阅一起断）。第二次仍超时才落 `loadFailed`。配套修掉一处旧漏：`finishDocLoad` 装载**成功**时现在会 `docLoadFailed = false`——此前重试装载成功后保存闸还关着，autosave 永久拒绝，用户的编辑不落盘。
- **装载失败的状态键是三分的，不是一句「文档加载失败」**（`classifyLoadFailure`，`utils/editorLoadFailure.js`）：后端 404/410 → `fileMissingFailed`（「文件已不在磁盘上」，重试没有意义）；下载超时/网络错/其它 HTTP → `downloadFailed`（「下载失败，请检查网络」）；引擎侧失败与 relay 超时 → 沿用 `loadFailed`。**三个键都刻意以 `Failed` 结尾**，组件里四处 `statusKey.endsWith('Failed')` 判据（`isError` / `finishDocLoad` / `saveDocument` / `reloadFromBackend`）因此一行都不用改——新增状态键时照这个命名走。判据匹配的是本仓自己 reject 的固定串（`HTTP <status>` / `下载超时 …` / `LibreOffice relay timeout: …`），不随界面语言变化。
- **渲染层没有落盘日志通道**：`~/.aiworkdeck/logs` 只有主进程自己在写，`checkba:reveal-logs` 只负责揭示目录，desktop/ 里没有 renderer→main 的 log IPC。所以装载失败的结构化诊断（`logLoadFailure`：fileId/fileType/fileSize/reason/message/elapsedMs/retried/adopted/attempt）目前只进 devtools console。哪天补了日志 IPC，把那一行改成经它落盘即可，字段已经齐了；**不许**为此新增任何出站网络请求。
- **lo-relay 的非命令消息**（客体页 → 宿主：`open-url` / `modified` / `selection` / `evidence-drop` / `boot-log` / `cursor-context`；宿主 → 客体页：`insight-sub`）。`cursor-context` / `insight-sub` 是「依据」窗格的正文联动（dev-board#182，契约在 doc-insight.md 的「前端」一节）：**默认关闭**，宿主 `LibreOfficeEditor` 的 `insightSubscribed` prop 置位才下发订阅，客体页此前一次 `get_cursor_context` 都不打。宿主下行走 `this._transportSend`（relay executor 只发命令，非命令消息要自己送），`ready` 时补发一次——webview 重建/换文档后客体页状态是全新的。
- **宿主事件订阅必须在建元素时就挂，不能推迟到 dom-ready**（`LibreOfficeEditor.subscribeHostEvents`，与命令通道 `wireExecutor` 分开）：`boot-log` 从引擎启动第一刻就在发，`modified` 是自动保存的唯一触发信号——晚挂一步就丢开头的消息，丢 `modified` 等于用户的编辑不落盘。命令通道则相反，webview 必须等 dom-ready（此前 send 无处可去）。
- **宿主一改 `<webview>` 元素的尺寸，编辑区就整块黑一帧**（dev-board#503，真 Electron + 真引擎探针实测）：Electron 客体（guest）有自己的合成面，新尺寸的那一帧到达之前，父合成器把整块客体区域画成**黑色**。10 次开关 36px 的查找栏，132 帧里 8~11 帧带黑带，最黑一帧是「工具栏以下 733 CSS px 全黑」。**给 `.libre-host` 或 `<webview>` 元素铺 `--awd-canvas` 底色拦不住**——客体面盖在它上面，实测带底色那一组照样 11/132（所以别再往那个方向修）。唯一有效的是**不改 webview 尺寸**：会随开关出现的行（查找栏这类）一律做成绝对定位浮层，同款钉法见 EvidenceStaleBar 与状态胶囊；改成浮层后同样 10 次开关 0 帧。浮层压在 webview 上是可靠的（探针里截图确认），域文档里「webview 是独立合成层，浮层压上去不可靠」那句只针对**并排挤宽的审阅面板**那种整块布局，不针对细条浮层。
- **iframe 容器不能加 sandbox**：沙箱会掐掉 SharedArrayBuffer 与 Worker，引擎起不来；跨源隔离靠站点级 COOP/COEP，不是 iframe 属性。
- iframe 传输的订阅挂在 `window` 上，不随元素移除消失——`beforeUnmount` 必须显式退订（`_eventUnsub`），否则关一个文档漏一个监听器，且已销毁实例的 `onDocModified` 还会被触发。
- 引擎仅 Writer+Calc 实锤（PR#165），别承诺 Impress。
- **`fetchArrayBuffer`（`LibreOfficeEditor.vue`）曾经无 `xhr.timeout`**：请求挂起（代理/网络中间层吞掉响应但不触发 `onerror`）时，`loadDocument()` 里的 `await` 永远不返回——加载面板卡在某个百分比（典型是 85%，`bootMilestone(72,85,...)` 那一档），既不报错也不重试。已修：60s `xhr.timeout` + `ontimeout` reject；`loadDocument` 下载失败自动重试一次；加载卡片同一 `bootStageKey` 停留超过 30s（`startBootTrickle` 定时器里判）会亮出「重试」按钮（`retryLoad()`，引擎已就绪则重放 `finishDocLoad`）。改这段之前留意：`bootMilestone`/`file` watcher/`retryLoad` 三处都要同步维护 `_stageChangedAt`（否则 stuck 判据会误报或漏报）。
- CJK 字体走类别映射别名（PR#157/158），**不能用 assign 硬替换**；tofu 排查用 list_fonts 诊断 action。
- 删除键/快捷键必须走 `.uno:` 调度（覆盖层吞键+修订模式手工删卡死教训，PR#164/166）。
- **IME 覆盖层的「吞掉尾随 input」闩不能无条件置位**（`zetaOfficeImeOverlay.js`）：`compositionend` 之后浏览器**不一定**补发 input 事件（中文态标点直接上屏、组合被取消都不发），闩挂着不解就会吃掉用户随后敲的第一个字符——真机现象是「中文标点要按两次才过去」。判据用 `inputType`（只吞 insertCompositionText/insertFromComposition），并且无论如何在下一个宏任务解闩，绝不让它跨事件循环存活。
- **组合中的文字要用独立预览条显示**：覆盖层的 `<input>` 压在画布上，必须全透明（显字会与正文叠印），所以「输入过程」看不见。预览条不依赖光标映射，未点过画布 / 映射失败时照样可见。
- **触控板捏合必须在 canvas 上 `preventDefault`**：Chromium 把捏合报成 `ctrl+wheel`，不拦就是浏览器缩放整个 webview 页面（工具栏跟着放大、画布发糊、IME 像素映射基准作废）。拦下来改派 `set_zoom`（`ViewSettings.ZoomValue`，先切 `ZoomType=BY_VALUE` 否则自动模式会把值算回去；两个属性都是 sal_Int16，必须 `shortAny()`）。
- **保存状态胶囊只在「慢」和「失败」时出声**：成功保存不报「已保存」（维护者反馈：经常闪变、打扰）。浮层要钉在**画布**上而不是编辑器外层——审阅面板是并排挤宽的，钉外层会压住面板标题行。
- `npm run build:zetaoffice` 会清空 dist 并删掉已 fetch 的引擎——本地反复跑 e2e 用 `LOWA_ENGINE_DIR` 规避，或从兄弟 worktree 复制引擎（CDN 挂时的配方）。
- 修订作者：params 带 `__agent:true` → 署名 "AI WorkDeck"（worker `execCommand` 每条命令前按标记切 `/org.openoffice.UserProfile/Data` 的 givenname，`setRedlineAuthor`；引擎 `SwModule` 收到配置变更通知即重取作者，真机实证双向切换即时生效）。**宿主每一条 AI 发起的写命令都要带标记**：`handleEditorCommand` 与 PluginPane 一直带，流式落字 `flushDocStreamBuffer`/`handleDocStreamEnd`（`stream_insert`/`stream_flush`）曾漏掉，AI 起草的整篇内容全记在用户名下（dev-board#367）；单测 `tests/project-home/doc-stream-agent-author.test.mjs` 锁住。用户自己的 IME 输入 / 快捷键 / 工具栏不带标记，署当前登录用户名（`load_document` 的 `authorName`）。
- **`export_document` 必须临时切成内联「全部修订」再导**（`withInlineMarkupForExport`，原 `withMarginOff`，dev-board#367 真机探针 + #368 三态）：ShowChangesInMargin=true 时删除文本被并出版面，docx 导出器却按并合后的正文套修订区间——字符级替换（删「乙」插「丁」）导出成「丁」被删、「乙」消失，多段文档还会把别处的插入标成删除；重新打开 / Word 里看到的修订是错的，且自动保存、版本记录、对比全走这条导出。只关不 `refresh()` 仍错位（探针 R2），导完要恢复用户原来选的显示态再 `refresh()` 一次。**最终稿（`final`）同样要临时恢复显示再导**——隐藏态可能随 `settings.xml` 的 `w:revisionView` 写进 docx，别人在 Word 里打开就看不见修订。**函数头必须 `if (!isWriterDoc()) return fn();`**（见上一节最后一条：漏了这个守卫会把 Impress 导出后的重新打开搞崩）。e2e 组 31 锁住导出→重开的修订与正文一致（该组自己显式切到页边跑），组 32 锁住最终稿导出件里 `w:ins`/`w:del` 与内联导出一条不差、且 `settings.xml` 里没有 `w:revisionView`（真机实测：本引擎两种模式导出的 `w:ins`/`w:del` 计数一致，`settings.xml` 里从来没写过 `w:revisionView`，包装是兜底不是补漏）。
- **`.uno:InsertAnnotation` 必须在 RecordChanges 关闭下派发**（`withRecordChangesOff`，`add_comment` / `add_comment_at_selection` / `reply_comment` 三处）：修订记录开着时引擎把批注字段本身记成一条空文本插入修订（说明字段「已添加批注」），导出成包着批注标记的 `<w:ins>`——Word 里是一条作者 AI WorkDeck、正文为空、时间与真批注相同的幽灵气泡；审阅面板也多一张「插入（空）」卡。关掉派发不影响批注的区间标记 / 作者 / 内容（探针 Q5）。`set_comment_resolved` 走 API 属性，不产生修订。
- **`.uno:InsertAnnotation` 之后视图光标失效**（真机探针 R3/S4）：焦点进了批注窗口，`ctrl.getViewCursor()` 的 `getText()`/`gotoRange()`/插入一律抛 RuntimeException，`ctrl.getSelection()` 为 null——`insert_at_cursor` / `goto` / `replace_selection` / `stream_insert`（都走视图光标）在用户点一下画布之前全部失败；锚点/段落索引类原语（`replace_at_position`、`modify_paragraph`、`add_comment`）不受影响。`set_selection`、`ctrl.select()`、重取 controller、给窗口 setFocus、派发 `.uno:Escape`/`.uno:GoToStartOfDoc` 都救不回来；唯一试出来的恢复手段是派发两次 `.uno:ShowAnnotations`（藏再显）。未上线——只有一次探针实证，先记在这里。
- **批注边栏宽度与锚线在引擎里没有配置项**：宽度 = 缩放比 × 1.8 px 硬编码（`sw/source/uibase/docvw/PostItMgr.cxx` `GetSidebarWidth`，60% 缩放 ≈ 108px），r4 引擎注册表里只有 `ShowNotes`/`ShowChangesInMargin`，没有任何 Sidebar/Notes 宽度键；锚线由 `AnchorOverlayObject` 恒画（`AnnotationWin2.cxx` `SetAnchorState(All)`），只有「隐藏全部批注」时才退成三角。适合页宽会把边栏算进页宽（`viewmdi.cxx` `SetZoom_` 在 `HasNotes && ShowNotes` 时 `AdjustWidth(GetSidebarWidth)`）。要更宽只能改缩放，或在自建引擎里改这个系数重烧。
- **ShowChangesInMargin 依赖自建引擎 ≥24.2.8-zhcn-r3**：原生 LO 把页边删除文本画在锚点所在 frame 左侧，表格内 frame=单元格会叠画左邻格正文；r3 焙入 frmpaint.cxx 表格锚点补丁（`desktop/lowa-build/patches`，锚 FindTabFrame 整表左缘）后才能开。页边模式非纯视图设置：开=删除文本移入 redline 对象（getString 可取、正文不含），关=留正文流且 redline getString 抛异常——debug_revisions 已带 RedlineText/区间双路取回，两种模式都能读。已知残留局限：同一表格行多格删除会在页边同 Y 相互叠（上游按行画、无跨格协调）。批注侧栏与此设置无关。**这个开关现在是「修订视图三态」的一半**，直接调 `ctrl.getViewSettings().setPropertyValue(...)` 的新代码一律改走 `applyRevisionView()`，见上一节。
- **`list_revisions` / `list_comments` 回传的 `paraKey`/`start`/`end` 是同一坐标系**（dev-board#377，批注↔修订关联的地基）：`paraKey` = 正文段落枚举序（`rangeLocator()` 走 `paraIndex` 缓存二分定位，O(log n) 次 `compareRegionStarts`），`start`/`end` = 区间两端在该段落内的字符偏移，取的是**正文流宽度**——页边模式下删除型是零宽 `start === end`，那是正常结果不是取失败。表格单元格 / 页眉页脚里的区间跨 story，`compareRegionStarts` 抛 IllegalArgumentException → 回 `paraKey: -1`，宿主据此**不做关联**（宁可不挂，不猜）。判定本身不放 worker 里，放宿主纯函数 `utils/reviewGrouping.js`（好写单测）。
- **审阅面板原语的光标摆位是硬约束，且跟着显示模式走**（`resolve_revision` / `selectRedlineRange`，真机逐个试出来）：插入型修订必须**跨选**整个 redline 区间才被 `.uno:AcceptTrackedChange` 命中（两种模式都一样）；删除型**只在页边模式下**必须**塌陷**到区间起点（那时删除文本不在正文流，跨选反而打空），**内联模式（现在的默认）下删除文本就在流里，同样必须跨选**——沿用塌陷会「引擎未命中该条」。摆错不报错——dispatch 静默失效甚至凭空多一条空插入修订，所以处置一律用 redline 条数变化复核，别信 dispatch 的返回。
- **批注删除三个前提**（`delete_comment`）：`.uno:DeleteComment` 必须带 `Id`（批注的 `Name` 属性）；文档必须**可见**（Hidden 打开的文档没有批注窗口，按 Id 找不到）；已解决（Resolved）的批注要先取消解决态。API 路线 `dispose()` / `removeTextContent()` 在有些上下文里是「不抛异常也不生效」的假成功，不能据其返回值报成功。
- e2e 探针换文档（`debug_fresh_document`）要跟着生产 retarget 做 `showDeletionsInMargin()`，否则后续断言跑在行内语义下；组 18 还需 `{visible:true}`（批注删除依赖注释窗口）。
- webview/uni 存储格式坑与宿主侧 e2e 配方见 lowa-keepalive 记录（PR#159）。
- **预热备胎是同一个 LibreOfficeEditor 组件（file=null）**：任何在组件树/DOM 里"找编辑器实例"的探针（如 desktop-e2e FIND_EDITOR）必须过滤 `file` 非空，否则命令打在隐藏空白备胎上、样样"成功"但真文档纹丝不动。备胎未激活用 visibility 隐藏（绝对定位占位），不能改 display:none——引擎要在有尺寸画布里 boot。

## 样式画像原语（dev-board#111，office_thread.js）

HOUSE 不再是常量：`buildHouse(profile)` 从画像 JSON 派生写端常量表（字体/字号/段落规格/标题各级规格/表格边框与表头/目录标题），`ACTIVE_PROFILE` 默认 = `self.HOUSE_DEFAULT_JSON`（`house-default.js` 经 `Module.uno_scripts` 在 zeta.js 之后、worker 之前载入，`zetaOfficeBoot.js` 的 `houseProfileUrl` 选项；缺席时 worker 退到硬兜底并在 bootDoc 记日志）。`applyHouseChar/applyHousePara/styleTableStandard/streamParagraph/apply_house_style` 全部只读 HOUSE。单位换算 `profLenPt`：pt 直出、chars 按所在块字号、lines 按字号 x 1.2（与后端 `Units` 同口径）、mm/cm/twips 直换。

| action | 参数 | 说明 |
|---|---|---|
| `set_style_profile` | `{profile}` / `{reset:true}` | 校验 `schemaVersion`（只认 1）、`profileMergeInto`（同 Java `StyleProfile.mergeInto`：对象递归、headings 按 level 合并、其余整体替换）到 house-default 之上，重算 HOUSE。只改 worker 状态不碰文档。返回 `styleProfileSummary()` |
| `apply_style_profile` | `{scope: document\|selection\|styles-only}` | 先 `applyProfileToStyles()` 改 `Standard`/`Heading 1..6`/`Table Contents`/`Table Heading`/`Contents Heading` 定义，再按 scope 走 `applyHouseToDocument(p,{trueLevels:true})`（标题按真实 OutlineLevel 取各级规格）/ 选区内段落 / 不碰正文。分批 500 + progress + 取消，`truncated` 只在取消时 true |
| `insert_toc` | `{levels, title, position: cursor\|start}` | `com.sun.star.text.ContentIndex`（CreateFromOutline、Level=shortAny）insertTextContent 后 `update()`；缺省 levels/title 取画像 `toc`。主标题（流式 `#` 首段）不带 OutlineLevel，不进目录 |
| `set_page_setup` | `{width, height, orientation, margins:{top,bottom,left,right}}`（mm） | 页面样式 Width/Height/IsLandscape/XxxMargin（1/100mm）；给 orientation 时按它校正宽高顺序。返回读回值 |
| `edit_header_footer` 扩展 | `pageNumberPattern`、`fontName/fontNameAsian/fontSize` | `{PAGE}`→`TextField.PageNumber`(SubType CURRENT)、`{NUMPAGES}`→`TextField.PageCount`，NumberingType 用 `shortAny(ARABIC)`；接在 text 之后，text 可省 |
| `format_selection` 扩展 | `fontNameAsian` | 只设 `CharFontNameAsian`（`fontName` 仍三项一起设，先 fontName 后 fontNameAsian） |
| `format_table` 扩展 | `borderColor/borderStyle(single\|double\|dashed)/outsideBorderWidthPt/insideBorderWidthPt/headerFill/repeatHeader/columnWidthsCm` | `applyTableBorders(table, spec)` 内外线分开；`headerFill` 落首行 `BackColor`（none=-1）；`repeatHeader` = `RepeatHeadline` + `HeaderRowCount` |

地雷：
- `apply_house_style`（旧 doc_apply_standard_format）保留改造前口径：非主标题的标题一律按**二级**规格（= 正文款加粗），只有 `apply_style_profile` 按真实级别取画像。house-default 一级是 16 磅粗居中，别把 apply_house_style 改成真级别——会把既有文档里所有 Heading 1 段放大居中。
- 流式续写里的 `#`（非主标题）也按二级规格落（OutlineLevel 仍记 1），理由同上。
- **本引擎（zh-CN r4）按列设宽做不到**：`new css.text.TableColumnSeparator` 与「读回 `TableColumnSeparators` 改 Position 再设回」都抛 `unregistered UNO type`（WASM 桥没注册该结构），`columnWidthsPercent` 从一开始就是死路（e2e 组 29 实锤）。worker 返回「当前编辑器引擎不支持按列设宽」的明确拒绝，e2e 锁住这条；引擎支持了那条会红，提醒把能力加回工具描述。
- `set_style_profile` 是 worker 级全局状态：e2e 组 29 末尾必须 `reset`，否则后续组/复用 worker 的断言全按测试画像跑。
- 改样式定义（`applyProfileToStyles`）要包在 `lockModel/unlockModel` 里，否则每个属性写入都重排版一次。

## EvidenceLink 书签原语（dev-board#103，office_thread.js）

书签名 = linkKey（`EVID_<ULID>`，只含 `[A-Za-z0-9_]`），底稿关联挂在命名书签上跟着文字走。五个 host-initiated action（已入 `EDITOR_ACTIONS` 白名单；失败一律 `bmFail()` 双字段 error+message）：

| action | 参数 | 返回 |
|---|---|---|
| `bookmark_selection` | `{name}` | `{success, name, text}`；空选区 / 非法名 / **重名精确拒绝**（不像 `insert_link_with_bookmark` 那样加 `_n`） |
| `get_bookmark_context` | `{name}` | `{success, exists, text, sectionPath, sectionTitle, paragraphIndex}`；不存在是 `exists:false` 不是失败 |
| `check_link_anchors` | `{names[]}` | `{success, items:[{name, exists, text}], truncated}`；单次 ≤200（超出截断并置 `truncated:true`），宿主分批 |
| `adopt_legacy_links` | `{}` | `{success, adopted:[name], skipped, skippedInvalid}`；先对整条 URL decodeURIComponent（最多两层）再匹配 `filelink?k=<key>`（到 `&`/`#` 止），生产形态 `open?u=encodeURIComponent('checkba://filelink?k=…&projectId=…')` 取到的 key 恰等于原 key；key 含 `[A-Za-z0-9_]` 以外字符（如后端兜底 `lk_<UUID>` 带 `-`）**不改写、计入 `skippedInvalid`（按去重 key 计）**；逐目标 try/catch，单个坏 run 只 `skipped++`；幂等 |
| `goto_bookmark` | `{name}` | `{success, name}`；`anchorRange` + `selectVisibly`，不借 `set_selection`（它只认 `__ai_anchor_*`） |

真机实测结论（lowa-e2e 组 27，2026-08-21）：
- **书签覆盖的文字被整段删除后，LO 连书签一起删掉**：`check_link_anchors` 回 `exists:false`，不会留空点书签。宿主判 orphan 仍用「`!exists || text===''`」双口径（text 为空是防御）。
- 书签内部插字会扩张书签；**正好在书签末端插入不扩张**（e2e 要先 `collapse_selection end` + `move_cursor left` 再插）。
- 书签经 docx export/load 往返存活（文字含后插的字）。
- `adopt_legacy_links` 不能把 TextPortion 直接喂 `insertTextContent`（抛 IllegalArgumentException），要 `createTextCursorByRange(start)` + `gotoRange(end, true)` 在正文上造区间游标；且先收集目标再插书签，枚举中改段落会让 portion 枚举失效。
- `headingChainOf` 用 `XTextRangeCompare` 定位段落，表格单元格内的书签跨 story 比较会抛 → `sectionPath` 空、`paragraphIndex -1`（P0 接受）。

## 文档 Generator 元数据（可溯源性设计规范附录 B4）

保存出去的 docx/xlsx/pptx 在 `docProps/app.xml` 的 `<Application>` 里写 `AI WorkDeck <version>`（Word / WPS / LibreOffice 都写这个标准字段，我们此前是唯一不写的）。**只写产品名与版本，不写作者/单位/机器名**；开关 `document.generator.enabled` 缺省开，设置页「文档属性」一栏可关（交付前要 scrub 元数据的律所用）。

- **引擎 API 改不到这个字段**（2026-09-09 真机探针，24.2.8-zhcn-r4，三种试法全否）：`getDocumentProperties()` 上**没有** `setGenerator/getGenerator` 方法（zetajs 把 `Generator` 暴露成属性）；属性写法 `dp.Generator = '...'` **写得进也读得回**，但导出件的 `<Application>` 纹丝不动，仍是 `ZetaOffice/24.2.8.0.beta1$Emscripten_x86 LibreOffice_project/<buildid>`；`UserDefinedProperties.addProperty('Application', …)` 只多出一个 `docProps/custom.xml`，标准字段照旧。结论：oox 导出器硬写 `utl::DocInfoHelper::GetGeneratorString()`。**别再试 API 路线**，引擎哪天改了 lowa-e2e 组 32 第 5 项的「基线」那条会红。
- 实现在**宿主侧**：`frontend/src/utils/docxAppProps.js` 的 `stampApplication(bytes, application)`（async——引擎导出的条目全是 DEFLATE、带 data descriptor，读 app.xml 要 `DecompressionStream('deflate-raw')`），只换 `docProps/app.xml` 这一个条目（新条目 STORED、自算 CRC32），其余条目的本地头与数据**逐字节原样拷贝**、只重写中央目录偏移；ZIP64 / 非 zip / 没有 EOCD / app.xml 里没有 `<Application>` 元素（**只替换不插入**）/ 自检不过——**一律原样返回入参**。不引 jszip：那会把整包重压一遍，大文档在保存路径上不可接受。
- 接线：`LibreOfficeEditor.saveDocument` 在 `uploadBytes` 之前调 `stampGeneratorMetadata(u8)`，开关与串从 `GET /api/document/generator/settings` 取一次、`utils/documentGeneratorSetting.js` 按会话缓存（**读失败不入缓存**，后端刚起来那几秒读不到是常态）。打标链路任何异常都退回导出原字节，绝不影响保存。
- 覆盖边界：lowa-e2e 跑的是 dist 的 editor-main.js，**打标那一步经不过来**——纯函数由 `tests/lowa-unit/docxAppProps.test.mjs` 守，宿主接线由 `tests/project-home/libre-save-generator-stamp.test.mjs` 守（删掉 saveDocument 里那一行即转红），真产物+真引擎回读由 lowa-e2e 组 32 第 5 项守。
- 后端 POI 落盘路径同口径：`DocumentGeneratorStamp.apply(doc, documentGeneratorSettings)` 一行，已接 SensitiveService / MeetingRecordingService / LitigationTimelineTools / DdExportService(xlsx) / DocumentEditTools(xlsx)。**docx4j 的两条路径（`doc_start_stream` 建空白 docx、DdExportService 的 markdown→docx）没接**——docx4j 不走 POI 的属性 API，且前者随即由编辑器接管保存、自然会被宿主打上。

## 验证

- 装载失败分类 / relay 超时自愈判据 / 探活预算：`cd frontend && npm run test:lowa-unit`（node --test，不需要引擎；`tests/lowa-unit/editorLoadFailure.test.mjs`）。
- 核心回归：`cd frontend && npm run test:lowa-e2e`（真引擎 puppeteer-core 无头，33 组人机模拟，2026-09-09 基线 547 步；前置 `npm run build:zetaoffice` + `node ../desktop/scripts/fetch-lowa-assets.js` 或设 LOWA_ENGINE_DIR）。
- 修订视图三态的接线契约（白名单 / 三态命令序列 / 换文档复位）：`npm run test:revision-view`（node --test，不需要引擎）。
- 大文档性能：`npm run test:lowa-big`（同一套启动件 `tests/lowa-e2e/_boot.mjs`；端口被别的 worktree 占着时设 `LOWA_E2E_PORT`）。
- 涉桌面壳/webview：`npm run test:desktop-e2e`（弹 dev Electron 窗口，验证保存落盘链路）。
- 全应用：`npm run test:app-e2e`。改编辑器三件套（原语/白名单/worker）必跑 lowa-e2e。

- 离开工作台/退出登录前，`flushDirtyEditors` 逐个保存后必须同步重扫当前 Office/文本注册表；保存 B 期间重新编辑 A、原本干净实例变脏、或新注册实例变脏都应阻止导航（`flush-dirty-editors.test.mjs` 时序用例），不能只相信每个实例刚保存时的状态。
- 文本标签关闭同样必须检查 `flushSave` 返回值及最终 dirty/saving；失败仅提示原有重试入口并保留标签。`PlainTextEditor.flushSave` 不得吞掉 `save()` 的 false，也不能在保存期间新增输入后报全已保存。

## 即时审校（dev-board#547）

`inlineReviewHost.js` 读当前 Writer 正文快照，修改后 1.2 秒防抖调 `/insight/review`（`deep:false`），只有点击「深入审校」才传 `deep:true`。会话与 worker revision 双围栏拦住迟到结果；修改、换文档、销毁立即失效。同用户开关同步，独立于写作补全开关。默认计时器用箭头包装调用，不能以 `{set:setTimeout}.set()` 调浏览器原生函数（Illegal invocation，Node 单测捕获不了）；真实浏览器测试覆盖启动与销毁。

`zetaOfficeInlineReview.js` 是不落盘的 guest DOM：当前段落光标旁提示、全量问题清单、原文定位、明确点击采用。不给文档塞书签或批注。IME/编辑/滚动立即隐藏旧定位；**LOWA boot 每秒发同尺寸 synthetic resize，不能因此清掉提示；只有视口或 canvas 几何变化才失效**。不抢 Tab；候选菜单打开时让位。

审校入口与面板是同一个可拖动悬浮控件，收起后保留用户位置；位置只按稳定用户 key 存 sessionStorage，不带文档内容。分类固定为全部/待补充/一致性/格式与号码/AI 审校，tab 钉在列表滚动区上方，计数始终来自全量 finding。正文变化后保留用户的展开/收起选择，但旧 finding 禁止定位或采用并显示等待重查；补全菜单出现时只隐藏行旁 chip，不能顺带收起用户打开的审校面板。

worker `get_document_text` 与 `get_review_context` 回 revision；`goto_review_range` / `apply_review_edit` 校验 revision、0 基段落、UTF-16 起止、完整段落和引文后操作临时 range。采用建议保留细粒度修订、一次撤销，旧结果拒绝。导出不增 revision，重载即使同文也增。`get_cursor_rect.viewData` 只返回可序列化原始值。

IME 光标定位优先用 `XController.getViewData()` 的实时分号数据与 VCL 编辑子窗坐标；该格式不是 UNO 公共字段契约，当前只对锁定的 LOWA 24.2.8-zhcn-r4 验证，解析或窗口匹配失败必须退回点击校准。VCL 窗口尺寸使用物理像素，必须用 component 的 `convertPointToPixel` 读取实际 DPI；固定 96 会使 Retina 匹配失败。worker 坐标含 component/child 偏移，宿主再补 canvas 与 container 的实时菜单高度差（隐藏菜单时为 0）；CSS 宽度比负责 DPR/缩放换算。100%/194% 长文档验证已覆盖。异步 `get_cursor_rect` 必须只允许最新请求落位，避免旧响应覆盖新光标后把补全菜单带回旧位置；缩放、滚动后的下一次输入或移动重新读取实时几何。

正文链接点击先由 worker 区分外链与内部 `#bookmark`/REF，再交宿主预览。内部目标只展示实际解析到的书签/引用文字，源文件和项目文件按当前项目、当前文档身份绑定；文档或项目切换后的迟到结果必须丢弃。分屏目标若已在来源侧后台，须先保存并移到对侧，避免文件去重逻辑把原文顶掉；不从 guest 直接 `window.open`，默认外链也统一走宿主 `open-url`。

检查范围为正文段落（不含表格、页眉页脚），单段 >15,000 字跳过并披露截断，总计 200,000 字/10,000 段/60 页。行内修订模式需切页边或最终视图。完整在线核验保留在依据窗格，打开窗格不自动调用模型或外库，点击后先保存对应文档。

验证：`npm run test:inline-review`、`test:lowa-inline-review`，真实桌面 `tests/desktop-e2e/writing.mjs` 同时检查中文补全与刚输入正文的规则提示、无自动深入审校/外查。
