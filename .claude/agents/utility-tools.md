---
name: utility-tools
description: 辅助小工具领域。任务涉及浏览器面板、截图/OCR、剪贴板、收藏夹、搜索、下载、语音 TTS、文件预览/插入时，先读本文档再动代码。
---

# 辅助小工具 领域地图

职责边界：工作台各辅助面板与桌面能力（浏览器/截图/剪贴板/收藏/搜索/语音/预览插入/OCR）。不含编辑器（doc-editor）、不含左栏布局本身（sidebar-shell）。剪贴板与文件缓存区的**免费额度**在本文档里讲执行方式，权益判定、entitlement 命名与账户连接见 `.claude/agents/licensing-billing.md`。

## 分组清单（前端组件 / desktop 宿主 / 后端 API 三层）

**浏览器**：`frontend/src/components/BrowserPane.vue`；desktop `desktop/main/browser-views.js`（BrowserView 注册表）+ `desktop/main/main.js`（`makeBrowserView` 建 view 并接事件、window.open 拦截转工作区新 tab → 事件 `checkba:browser-open-new-tab`、全屏/黑屏兜底恢复、IPC handlers）；后端 `controller/BrowserProxyController.java`（GET /api/browser/proxy）。

**工具栏收藏与快捷方式抽屉（dev-board#214）**：BrowserPane 工具栏在 ↵ 之后有两个新按钮——
「收藏本页」星形（已收藏该 URL 时实心；判定按去尾斜杠的 URL 匹配收藏列表，再点只提示不重复入库，
删除仍去收藏面板坞位）与「收藏夹/快捷方式」抽屉开关。契约：BrowserPane 新增 prop
`projectId`（收藏落库目标，缺席即星形禁用）与事件 `favorite-added {id,url,title}`；
**落库由 BrowserPane 自己 POST**（`createProjectFavorite`，meta 口径与 onWebMark 同：
kind=webmark + sourceHost，content 存页面标题），父级 project-overview 的
`onBrowserFavoriteAdded` 只做可见反馈（打开收藏面板 + `refresh(true)` + `focusFavorite`——
toast 被 BrowserView 遮挡的老规矩）；失败提示走 `host.app.confirm`。页面标题组件自持
`pageTitle`（桌面端来自 title-updated/adoptViewState，导航时清空；H5 代理不回报标题，
收藏/钉住时退回域名）。抽屉 `.browser-shelf` 是**参与文档流的普通元素**（flex 列里
toolbar 与 browser-body 之间，`flex:0 1 240px`），展开即挤压 `.browser-desktop-mount`
高度、由既有 ResizeObserver 触发 syncDesktopBounds 让 BrowserView 让位——
**绝不能改成 absolute 浮层**（原生层恒盖 DOM）。两个分区：快捷方式存 uni 本地存储
`awd_browser_shortcuts`（数组 {url,title}，全局不分项目，每次展开重读——保活池里每个
标签实例各持一份内存态）；「收藏的页面」拉 `getProjectFavorites`（无 projectId 退
`getMyFavorites`），只展示有 sourceUrl 的并按 URL 去重，点击在当前面板 navigate。
新增 i18n 键 `panels.bpAddFavorite/bpFavorited/bpAlreadyFavorited/bpFavoriteFailed/bpShelf/
bpShortcuts/bpPinCurrent/bpAlreadyPinned/bpUnpin/bpShortcutsEmpty/bpShelfFavorites/bpShelfFavEmpty`
（zh-CN/en-US 两份）；图标 `ICONS.bookmark` 新增于 `config/icons.js`。

**红线：不做自动网核抓取**。维护者 2026-08-21 拍板「网核只留接口，不做自动逐站爬取，不碰验证码与合规风险」。别在浏览器面板/截图链路上做「输入公司名自动跑一遍企业信用公示/裁判文书/失信被执行人」这类批量抓取——尽调的网核走离线适配层（用户手工把外部工具导出的 zip 交进来，`service/evidence/webverify/`，见 `.claude/agents/ai-doc-bridge.md`「P3 网核 zip 接入」节）。浏览器面板仍是给律师自己开网页看、随手收藏、随手截图的地方，这条红线不影响它。

**BrowserView 生命周期（改这块之前必读）**：**面板卸载 = detach（保活），标签关闭 = destroy**。
切标签会把 BrowserPane 整个卸载（project-overview 里是 `v-if` + `:key=tab.id`），如果卸载即销毁，
用户翻到的那一页（页内跳转、滚动位置、填了一半的表单、页面里的登录态）就全没了，
切回来只能按渲染层记的旧地址重新加载——默认标签那个地址是 `https://www.baidu.com`，
现象就是「切走再切回来变成默认地址」。销毁的责任因此落在两处，**都不能漏**：
`fileOpenTabs.js` 的 `closeFile`（另一侧还双开着同一标签时不销毁，见下）与 project-overview
`beforeUnmount` 的 `destroyAllBrowserViews()`。
注册表同时记 `wanted`（哪些面板正端着它，**计数**）与 `attached`（此刻真挂在窗口上）：
- 全局隐藏（弹窗/蒙层、离开工作台、截图框选）走 `setAllVisible(false)`；恢复时**只挂回
  wanted 的那些**。无脑挂回全部，后台保活的标签会一起浮到最上层盖住界面。
- 计数而不是布尔，是因为跨窗格拖拽是「在另一侧也打开同一标签」（同 id 双开，见
  `tabDragSplit.moveTabTo`）；关掉一侧不能把另一侧正看的网页摘走。
- `browser-create` 对已存在的 view **不重新 loadURL**，并把 view 此刻的真实状态
  （url/title/canGoBack/canGoForward/mobile）回给渲染层，由 `adoptViewState` 回填工具栏。
**标签地址跟随页内跳转**靠主进程的 `did-navigate`/`did-navigate-in-page` → `checkba:browser-url-updated`；
渲染层不订阅它的话，tab.url 会永远停在打开时那个地址（BrowserPane 此前只消费了
title-updated，把同一条消息里的 url 扔了）。
**前进/后退/刷新走 view 自己的历史**（`checkba:browser-history`）：组件里那份 `history` 数组
只服务 H5 iframe 模式，桌面端从来没驱动过 BrowserView（三个按钮点了没反应），保活之后更没法当历史用。

**Web/H5 的那一半（iframe + 后端代理，改这块之前必读）**：没有 BrowserView 时 BrowserPane
渲染 `<iframe :src=代理地址>`，src 由 `GET /api/browser/proxy?url=…&token=…` 拼出。桌面端那两个病
（切走再切回来丢内容 / 地址不跟随页内跳转）在这条链路上是各自独立的根因，**都要修**：
- **保活**靠组件不卸载：`leftWebTabs`/`rightWebTabs` 是与 `leftLibreFiles` 同形制的池
  （按标签建实例 + `v-show` 藏 + LRU `WEB_KEEPALIVE_MAX=5`）。`v-show` 的 `display:none`
  **不会**丢掉 iframe 文档（滚动位置也在，实测过），换 `src` 才会。
  **这个池只在 Web 开**（`webKeepAliveEnabled = !host.browser`）：桌面端一次挂 5 个
  BrowserPane 就是 5 个 BrowserView 同时挂到窗口上，后台那几个会浮上来盖住界面
  （即下面「无脑挂回全部」那条地雷），而桌面端的保活早由 detach 解决了。
  上限存在的理由：摘下的 BrowserView 会被 Chromium 冻住，藏起来的 iframe **不会**——
  它们跟前台页共用同一个渲染进程，定时器照跑。
- **地址**靠代理注入脚本回报 `postMessage({type:'URL_CHANGED', url})`。iframe 是不透明源
  （`sandbox` 不带 `allow-same-origin`，**绝不能加**：与 `allow-scripts` 同时出现时沙箱失效，
  被访问站点就能读本应用 origin 下的会话凭证），父窗口读不到它的 location，只能等页面自己报。
  postMessage 这条通道在不透明源下照常可用（`event.origin` 是 `"null"`，鉴别靠 token）。
- **`currentUrl` 与 `iframeUrl` 必须分开记**：前者是地址栏/标签显示的地址，跟随页内跳转；
  后者是「iframe 被要求加载的地址」。把跟随来的新地址回写进 src，等于每跳一次就把
  刚打开的那一页重新加载一遍——保活白做。
- **保活之后后台标签也会报事件**（站点自己 302、SPA 换路由），所以
  `onBrowserUrlChange/onBrowserTitleChange` 收 `(pane, tabId, value)`，按 id 找标签；
  按「当前激活的那个」收会把后台标签的地址写到用户正看着的标签上。activityTracker
  只记激活标签。
- 跨窗格双开在 Web 下是**两个独立 iframe**（`tabDragSplit.moveTabTo` 跨窗格复制了 tab 对象，
  左右各有自己的 `tab.url`），与桌面端「一个 BrowserView、`wanted` 计数」不是一回事。

**注入脚本的三条硬规则**（`BrowserProxyController.inject`，`BrowserProxyControllerTest` 钉住）：
1. **整段拼成一行，里面绝不能出现 `//` 行注释**——它会把后面全部代码一起注释掉。
   这个 bug 从写下那天一直活到 2026-08：页面上只留一句
   `SyntaxError: Unexpected end of input`，`_blank`/`window.open` 拦截、同标签跳转、
   `OPEN_NEW_TAB` 回传三件事**全部静默失效**（现象是「点了没反应」，极易误判成 CSP 拦截）。
   要写注释只能用 `/* */`，且写成 Java 侧拼接之间的块注释（不进产物）。
2. **`proxify` 必须拼绝对地址**：页面里有我们注入的 `<base href=真站点>`，
   `location.href = '/api/browser/proxy?…'` 会按文档 base 解析到**被访问站点**头上
   （实测跳成 `http://被访站/api/browser/proxy?url=…`，站点回 404）。
   做法是拿当前文档地址换 query（`new URL(location.href)` + `u.search=…`），
   这样不依赖后端知道前端的 origin/子路径。
3. **塞进 JS 字面量的值要过 `escapeJsString`**：页面地址是被访问站点能控的（302 到任意 URL），
   带 `</script>` 或单引号就能跳出脚本。反斜杠必须先转，`<` 转 `\x3C`。

**代理回的 HTML 必须带 charset**：以前是「按 UTF-8 解码、回 `text/html` 不带 charset」，
浏览器拿默认编码（windows-1252）去解 UTF-8 字节，**中文页面在面板里整页乱码**。
现在按上游 Content-Type 的 charset 解码、统一以 `text/html;charset=utf-8` 回。
没覆盖的一档：只在 `<meta charset>` 里声明、响应头不带的 GBK 页面。

**SSRF 例外名单（`security.browser-proxy.e2e-allowed-hosts`，默认空）**：`SsrfGuard` 按解析后的
IP 拦回环/内网，而 app-e2e 要用本机起的两页小站验这条链路（断言不能挂在外网可达性上）。
名单默认空、**刻意不写进任何 application*.yml**，只有显式传
`SECURITY_BROWSER_PROXY_E2E_ALLOWED_HOSTS` 的进程才放行，发行版拿不到；精确主机名匹配，
不认通配。别为了省事填成通配——放行的是「服务端替你去抓这个地址」，
local-mode 下本机后端把每个请求都当本机用户，等于把本机管理端口暴露给被访问的网页。

**截图**：入口 `checkbaDesktop.ocr.captureScreen`；desktop main.js 透明覆盖框选窗 ~:389-571（BrowserView 模式仅限其区域内框选 ~:426）、capturePage 抓取 ~:577/:585/:709、IPC：ocr-capture-screen/desktop/window/view + ocr-start-selection。**推荐链路是 ocr-capture-view（当前 BrowserView，免 macOS 录屏权限）**；无独立后端端点，产物统一走 OCR。

**剪贴板**：`ClipboardPanel.vue`；desktop main.js 轮询监听 clipboardWatchTimer ~:117-232（指纹去重 ~:110，首 tick 只记指纹）、推送 `checkba:clipboard-copied`；后端 `controller/ClipboardController.java`（/api/clipboard：GET /、POST /text、POST /file、GET /{id}/file、DELETE /{id}）。
  **免费额度（PR-C）**：未拥有 `clipboard.unlimited` 时 GET / 只返回「最近 20 条 且 3 天内」，两条同时生效取更严者。**实现是查询侧过滤，绝不删除记录**——超出的行留在库里，解锁后原样可见。GET / 返回体从裸数组改为 `{items, limited, hiddenCount, maxItems, retentionDays}`（`ClipboardListResult`），hiddenCount 只算「因额度看不见」的（= 总数 − min(3天内条数, 20)），不含被分页 limit 挡住的。常量在 `ClipboardService.FREE_MAX_ITEMS/FREE_RETENTION_DAYS`。**额度只在 local-mode（桌面单机版）执行**：`EntitlementService` 是按本机的（无 userId 维度），团队案件库服务器上权益恒为空集，照执行会把每个接入成员截到 20 条且永远无法解锁。

**收藏夹**：`ProjectFavoritesPanel.vue`；网页选中收藏经 `checkba:webmark`（preload ~:26）→ project-overview 订阅入库（~:2003）；后端 `controller/WebFavoriteController.java`（/api/favorites/my、/api/projects/{id}/favorites、DELETE、image）。
  **收藏成功的反馈必须是「打开收藏面板 + focusFavorite 高亮新卡片」，不能只弹 toast**：
  用户此刻正在浏览器标签里，toast 在 DOM 层、被原生 BrowserView 整个盖住（实测 toast
  中心恒落在 view 区域内），只弹 toast 的现象就是「点了没反应」。右键收藏与 OCR 摘录收藏
  （`ocrDoFavorite`）现在同用这个模式；失败提示同理走 `host.app.confirm`（原生弹窗，不被遮挡）。
  卡片右下角的来源域名读的是 `meta.sourceHost`（`WebFavoriteListItem.from` 从 meta JSON 提取），
  新增收藏入口时 meta 里不写 sourceHost 就永远空白。面板 `refresh(force)`：新增收藏后的刷新
  要传 `force=true` 绕过 1.2s 节流，否则新卡片可能刷不出来、高亮落空。

**搜索**：`SearchPanel.vue`；后端 `controller/SearchController.java`（POST /api/projects/{id}/search）。
`ContentSearchService` 的全文抽取已改为复用 `DocumentTextService`（PDF 走 PDFBox3 原生
API），不再自建 `new Tika()` 解析 PDF——项目 classpath 锁 PDFBox 3.0.1，Tika 2.9.1 的
PDFParser 调 PDFBox2 已删除的 `PDDocument.load` 会抛 `NoSuchMethodError`（Error，能穿透
`catch(Exception)`），一个 PDF 曾经就能把整个搜索请求打挂；`AutoTaggingService` 的自动打标签
抽取同款同因，一并改掉了。逐文件循环的 catch 也收紧为 `catch(Throwable)` 做防御。
标签筛选区默认折叠、按命中频次排序——**那不是排版偏好，是给一个数据 bug 兜底**：
`/api/files/{id}/upload` 同时是编辑器自动保存的落点，挂在 legacy 分支上的
`AutoTaggingService.autoTagFile` 因此每存一次盘就跑一次 LLM，每轮 5 个措辞不同的新词，
而 `getOrCreateSystemTag` 只按精确字符串去重（实测单个文件积到 338 个标签）。
生成侧的闸在 `AutoTaggingService`（已有系统标签就跳过），存量数据由
`service/maintenance/DuplicateAutoTagCleanup` 一次性修（`ApplicationReadyEvent` +
`maintenance.autoTagDedup.done` 标志）：**按文件保留最早 5 条自动标签关联、删掉后续重复批次，
自动标签不超过 5 个的文件一行不动**（健康安装零变化），再清掉零引用的自动标签；
手工标签一律不碰。改这段前先读它的 javadoc——那是一段会在每台存量机器上自动跑一次的删数据代码。
标签筛选区**默认折叠**（`tagsOpen:false`）：自动打标签能给一个项目攒出上百个词，
平铺出来的标签墙会把搜索框和结果一起挤出屏幕。展开后按「已选优先 → 本次结果命中文件数
降序 → 名称」排（计数在 `updateVisibleTags` 里顺带数出来，没搜过就退回按名称排），
超过 24 个截断给「显示全部 N 个」，超过 12 个再加一个过滤框；**折叠态仍常驻显示已选标签**——
否则「搜不到东西」的原因被藏起来了。根因不在这个面板，见下面的自动打标签条目。

**标签类型维度（2026-08-20，dev-board#63）**：`Tag.type` = `NORMAL`/`PARTY`（当事人）/
`ISSUE`（争议焦点），**可空且 null 视同 NORMAL**（存量行零迁移）——前端判断一律走
`utils/tagTypes.js` 的 `normalizeTagType()`，别手写 `tag.type === 'PARTY'`；后端白名单校验在
`TagService.validateType`，`updateTag` 收到 null type 是「不改型」不是清空（前端改型总是显式传值，
含改回 NORMAL）。同名唯一约束不分型：`TagService.getOrCreateTag` 撞同名不同型时**复用不改型**
（改型是用户在标签管理里的决定）。新建默认色按类型（PARTY `#B45309` / ISSUE `#9B1C31` /
NORMAL `#3B82F6`，`tagTypes.js` 与 TagService 两处同值）。分组展示在 TagSelector（可选列表三组）
与 SearchPanel（展开态三个 `.tag-subsec-head` 分组头，**只是 shownTags 的按型切片渲染**，
过滤/截断/排序仍是那一份全局逻辑，别按组各写一套）。AI 工具 `service/ai/tools/TagTools.java`
三件：`tag_list`（描述里强制先查再打、防同义词膨胀）/ `tag_file`（`ToolFileGuard` 校验归属，
幂等）/ `tag_remove_from_file`（只解关联）；接线同步 `RealToolBeans` 与前端 `toolDisplayNames.js`。
`AutoTaggingService` 刻意不产类型标签（当事人/争点判定不适合挂在每次自动保存的便宜档链路上）。
测试：`TagServiceTest` / `TagToolsTest`。

**下载**：`DownloadList.vue` 是**孤儿组件**（全仓库无引用、未挂载）；文件下载实际走 FileController `GET /api/files/{fileId}/download`。

**语音 TTS**：`EasyVoicePane.vue`（api：getTtsVoices/generateTtsAudio）；desktop 本地 Kokoro 由 `desktop/main/services/kokoro-service.js` 管理；后端 `controller/TtsController.java`（/api/tts/voices、/generate）+ `service/TtsService.java`。**只有本机一档**：桌面捆绑 Kokoro，OpenAI 兼容 /v1，地址 `external.tts.local-base-url`（打包态由 Electron 注入动态端口），地址为空即「组件未就绪」。云端 ElevenLabs 那一档与 `external.tts.provider` 开关已整体移除。easyvoice Docker 段已停用。

**两条改这块必须知道的事（2026-08-17 排查）**：
1. **本机引擎只吃 `rate` 一个参数**（倍率制字符串，`TtsService.parseSpeed` 认
   `"1.2"`/`"1.2x"`）。面板上曾有语速/语调/音量三个滑杆，**三个都是死的**：前端把
   `'+0%'`/`'+0Hz'` 写死在 payload 里、根本没读滑杆的值，而 `'+0%'` 传给 `parseSpeed`
   解析失败恒回落 1.0。#386 把 `rate` 接上了，本次进一步把滑杆本身改成倍率制
   （50..150 = 0.5x..1.5x，界面直接显示 `1.3x`），并删掉语调与音量——本机引擎不支持，
   留着就是骗人。`generateAudio` 的形参也收窄成 `(text, voiceId, rate)`；
   `TtsController.GenerateRequest` 的 `pitch/volume` setter **保留但不再往下传**
   （删字段会让存量客户端的请求体反序列化炸掉）。`TtsServiceTest` 有一条回归钉死
   「百分比串是无效输入」。
2. **`kokoro-service` 在打包态被 `modelManager.isInstalled('kokoro-models')` 卡着**——
   那是个约 300MB 的下载组件，不随包发出（随包的是代码）。端口分配了但服务没起来是常态，
   表现为 `/api/tts/voices` 返回空数组、下拉框空着。`platformServices.js` 里那条
   `LOCAL_TIER_READY.tts` 已随「TTS 去掉档位」一起删干净，不用再管。
   **面板不止把这件事说在合成之前，还给了就地出路**（形制照搬会议录音的 `.mr-tier-gate`）：
   `.ev-gate` 在音色列表为空时出现，按三种情形分说——模型没下（给「下载语音组件」，
   走 `host.model.download('kokoro-models')` + `onProgress` 订阅）/ 模型下好了但引擎没跑
   （只给「重新检测」）/ 浏览器版根本没有本机引擎（只说明，不给下载入口，`host.model` 缺席）。
   **下完必须显式 `host.services.ensure('kokoro-service')`**：那个 descriptor 的 `enabled`
   门在模型上，不主动拉一把就要等到下次启动应用才会起来，用户会以为下载白做了
   （`service-manager._start` 每次重新求值 `enabled`，此刻求值必然为真）。
3. **组件的 `sizeHint` 不带语言**（写 `'300 MB'` 不写 `'约 300MB'`）：它会被塞进 admin 的
   下载/删除确认文案与语音面板的按钮，那些串是双语的，「约」写在 descriptor 里就会
   原样出现在英文界面上。修饰词归各处的 i18n 串。**注意 `MeetingRecordingPanel` 整个面板
   仍是硬编码中文、没做 i18n**，英文版下那一片本来就是中文——这是既有欠账，不是本条引入的。

**录音（ASR 方向）**：会议录音插件的录音单例 `frontend/src/utils/meetingRecorder.js`（getUserMedia+MediaRecorder 配方源自 FeedbackWidget；分片追加上传走 /api/files/{id}/upload 的 X-File-Offset 协议；轨道必须 stop 否则 macOS 录音灯常亮）；转写三档 platform / byok / local，详见 `.claude/agents/licensing-billing.md`「平台服务网关」与 `.claude/agents/plugin-system.md` 会议录音条目。反馈浮窗的 `VoiceTranscriptionService`（OpenAI 兼容接口位）与它无关、各管各的。macOS 麦克风 entitlement 与 NSMicrophoneUsageDescription 已覆盖两个用途（desktop/package.json:103），**权限问题只在签名包暴露，dev 态测不出**。
  **`recorderState.projectId` 必须与 `status='starting'` 同步写入**（`startRecording()`）：不能等 `getUserMedia`+`createMeetingRecording` 两个 await 都过了才赋值——`MeetingRecordingPanel.vue` 的 `recordingHere` 计算属性同时判 `isRecordingActive()` 与 `recState.projectId === this.projectId`，projectId 还是 null 的窗口期会被误判成"别的项目在录音"（新机首次要等系统麦克风授权弹窗，窗口被拉长到必现；e2e 的假麦克风走 `--use-fake-device-for-media-stream` 秒过，覆盖不到这段）。`MeetingRecordingIndicator.vue` 的 `visible()` 也不许手写状态枚举，统一复用 `isRecordingActive()`——之前它漏了 `'starting'`，会出现"面板让你去顶部胶囊停止，胶囊却还没出现"的假象。

**本地 ASR（`asr-service/`，P3 起）**：faster-whisper 的 OpenAI 兼容薄包装，形态与 `kokoro-service/` 同构（`app.py` + `requirements.in/lock`，进 pysvc 单包，定位走 `pysvcPath()`）。端点 `GET /health`（带 `modelReady`，不加载模型）+ `POST /v1/audio/transcriptions`。桌面侧 `desktop/main/services/asr-service.js` 分配端口并把 `EXTERNAL_ASR_LOCAL_BASE_URL` 注入后端；模型 `Systran/faster-whisper-medium`（约 1.5GB）走组件管理 `asr-models` 下载，运行时 `HF_HUB_OFFLINE=1`。后端 `service/meeting/LocalAsrClient.java` 探测 + 转写，`controller/LocalAsrProbeController.java` 出 `GET /api/asr/local/probe`（匿名窗口口径与 Ollama 探测共用 `WizardStateService`）。
**Whisper 说普通话时稳定输出繁体**（本机实测两分钟会见录音整篇繁体），社区常用的 `initial_prompt` 偏置一个字都没纠正过来——所以在 `app.py` 里用 OpenCC 做确定性的繁转简后处理（`ASR_OUTPUT_SCRIPT=original` 可关）。

**文件缓存区（左下角「文件暂存区」）**：`FileStagingArea.vue`（纯展示，用量条读 `usage` prop）+ `pages/project-overview/stagingArea.js`（方法组）。**物理形态是项目内名为 `__staging_area__` 的文件夹**，「加入缓存区」= `batchMoveFiles` 把已有项目文件移进去，没有独立的缓存区目录。
  **免费额度（PR-C）**：`service/quota/StageQuotaService.java`，未拥有 `stage.unlimited` 时上限 20 个文件 / 500MB。**实现是移入时拦截，已有文件一律不动**——`ProjectFileService.batchMove` 在循环前整体准入检查，超额抛 `StageQuotaExceededException` → GlobalExceptionHandler 转 `code=4003 + feature + usage`，前端 api.js 打 `err.quotaExceeded` 标记。移出方向永不拦截（否则用户无法自救）。跨项目 id 不参与计算（防越权探测文件大小）。**文件夹按它装的全部文件递归计数**（准入与用量同一口径）——文件树允许把整个文件夹拖进缓存区，只算 1 个条目 0 字节的话，套一层目录就能让两条额度同时失效。用量端点 `GET /api/projects/{id}/files/stage/usage?folderId=`，**folderId 是全局 id，必须 `checkFileInProject` 校验归属**（只验路径 projectId 的话能枚举他人项目任意目录的文件数与字节数）。与剪贴板同理，额度只在 local-mode 执行。

**文件存储位置（PR-C）**：`service/storage/StorageLocationService.java` + `GET /api/storage/location`、`POST /api/storage/location`（迁移，需 stage.unlimited）、`POST /api/storage/location/reset`（恢复默认，不需权益）。搬的是**全局存储根**（项目文件与缓存区文件的落盘位置），因为缓存区文件就是项目文件。迁移策略：**复制 → 校验文件数与字节数（含源侧复查）→ 落配置 → 换指针，原目录保留为备份绝不删**；任一步失败清掉本次复制的副本并保持原路径。目标必须是空目录或不存在，且与源不互相嵌套——**嵌套判断在 `toRealPath()` 之后做**，纯词法比较挡不住指向源内部的软链（会把源复制进它自己，在数据根里造出几百个垃圾目录）。**源侧要复查**：只比「复制前的源」与「复制后的目标」，迁移期间自动保存进已复制目录的文件两边数字仍相等，会被静默留在旧根。源目录不可访问时拒绝迁移（否则 copyTree 会把源建出来，「0 个文件迁移成功」）。配置落 `~/.aiworkdeck/storage-location.json`（不落 DB：存储根必须在 JPA 起来之前就确定）。`ProjectStorageResolver.globalRoot` 因此改为 volatile + `relocate()`；能热切是因为 DB 存的是逻辑路径、git 的 gitDir/workTree 每次现算。

**文件预览/插入**：`FilePreview.vue`（docx/pdf/pptx/压缩包分流见 project-overview ~:5110-5157；PDF 走 Chromium 原生引擎渲染，标准 annotation 可见；watch `file.wpsFileId` 在 AI 改完 PDF 后自动重拉字节——reload_file 是 Object.assign 原地更新，file 对象引用不变，别删这个 watch）、`FilePickerDialog.vue`、`FileStagingArea.vue`（原 `FileLinkDropZone.vue` 已删：文件关联的落点改成编辑器画布本身，见 ai-doc-bridge.md「EvidenceLink 契约 → 前端」）；`FilePreview.vue` 还吃 `locator` prop（EvidenceLink 定位，详见下一条）；图片插入走 `libreofficeExecutorClient.js` insertImage → office_thread.js；desktop `file-service.js` + IPC `checkba:fs-read-file`（含敏感路径拦截+大小上限）；后端 `FileController.java`（download/upload/upload-status/text/compare）、`ProjectFileController.java`（列表/folder/archive/批量/回收站/tags）、`DocFileLinkController.java`（doc-links）。

**底稿定位（EvidenceLink `locator` prop，尽调 P3，改这块之前必读）**：宿主
`openFile(file, {locator})` → `tab.pendingLocator` → `FilePreview` 的 `locator` prop →
`applyLocator()` 拷成 `appliedLocator` 并 `$emit('locator-consumed')`（不清 prop 的话切回
标签会重复跳转）。**locator 的分型 schema 见 `docs/superpowers/specs/2026-08-21-evidence-link-p0-design.md` §1.4，
不许在前端改口径**；解析、坐标换算全在 `utils/evidenceLocator.js` 的纯函数里
（`parsePdfLocator` / `parseImageRect` / `parseMediaStartSec` / `imageTransform` / `imageRectBox`），
组件里只做渲染与播放器控制。三条硬规矩：

- **缺字段一律退化成「只打开文件」**。坐标常来自 OCR 或外部核查服务，少给一个数是常态，
  纯函数这时返回 null，组件就什么都不画。**绝不补默认值凑一个框出来**——补出来的是假高亮。
- **pdf 不做「叠在原生视图上的高亮」**。PDF 由 Chromium 内置插件在 iframe 里渲染，它是
  不透明的：页面的实际像素位置、滚动量、工具栏高度都读不到，照着 `#page=` + `view=FitH`
  猜出来的坐标会把高亮画到别的行上。所以做法是：跳页仍走 `pdfSrc` 的 `#page=`，右上角浮
  `.evidence-locate-card`——有 `rects` 就在 A4 比例的**页位图**上按归一化坐标画（那是能算准的），
  没有 `rects` 只有 `quote` 就**如实显示「未能在本页定位到引文」**并给「复制引文」让用户自己在
  阅读器里查找（内置引擎没有可编程的查找接口）。要做真正的页内高亮，只有两条路：引 pdf.js
  自己渲染页面，或让后端用已有的 PDFBox（`PdfEditService` 里已经有找文字算 quad 的机器）
  出「页面 PNG + 引文 rects」——两条都不是「在现有预览之上加一层」能糊出来的。
- **图片画框要跟着缩放/平移/旋转走**。`imageTx/imageTy` 的口径是**旋转后外接框的左上角**，
  `imageTransform` 按角度补一段平移把外接框推回该处，`imageRectBox` 用同一套口径算框——
  三处（居中摆放 `applyImageView`、缩放锚点 `zoomImageTo`、画框）共用一份 tx/ty，别在组件里
  另写一份换算。旋转只走 90° 步进（`imageRotate`），转完重新适应窗口。
  框**常驻**（缩放旋转后还要能核对），3s 后撤掉的只是压暗周边的那圈 `box-shadow`；
  工具栏「定位框」按钮可收起/重新亮出。
- **音视频 seek 完必须 `pause()`**，并把 `autoplay` 绑成 `mediaLocatorSec == null`——
  带定位打开的目的是看那一帧，自动播下去等于当场把定位冲掉。**别只靠模板上的
  `@loadeddata`/`@loadedmetadata`**：uni 在各端把 `<video>` 编译成自家组件，事件名与
  `e.target` 都不保证是原生那一套（P0 的 `@loadeddata` 很可能从没触发过）；`attachVideoLocator()`
  在 `blobUrl` 落地后 `$nextTick` 去真的 `<video>` 上挂原生监听，换文件与卸载时 `teardownVideoLocator()`
  摘干净。

测试：`frontend/tests/evidence/locatorGeometry.test.mjs`（坐标换算，含四个旋转角与 CSS
transform 的自洽互校）、`previewLocate.test.mjs`（三种定位各一个可复现实例，抠组件方法体真跑）、
`previewLocateRender.test.mjs`（用 vue 自带的 compiler-sfc + server-renderer 把模板真渲染成
HTML 再断言——模板里 class 名写错、v-if 挂错分支、i18n 键打错，只跑方法体的那份测试一个都发现不了）。
三份都在 `npm run test:evidence` 里，CI 跑。**模板里的中文注释会原样进 HTML**，
断言标签属性要先剥注释（`<video src>` 这几个字就写在既有注释里，直接 match 会假绿）。

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

FilePickerDialog :298 / EasyVoicePane :537 / DesensitizePane :543 / SearchPanel :550 / PluginPane(左栏) :555 / FileStagingArea :578 / BrowserPane 左:719 右:786 / FilePreview 左:749 右:816 / ProjectFavoritesPanel :892 / ClipboardPanel :900。import 区 :1217-1233，components 注册 :1293-1312。

## 已知地雷

- **commons-compress 1.26+ 依赖 commons-lang3 ≥3.14（ArrayFill），而 Spring Boot 3.2 BOM 钉 3.13**：
  pom 已属性覆盖 `<commons-lang3.version>3.14.0</commons-lang3.version>`（PR#435），别删。缺了它，
  压缩包预览/解压遇到老 PKZIP imploded 条目（`BinaryTree` 解码）与 tar 写路径会
  `NoClassDefFoundError`——且是 Error，`walkArchive` 外层 `catch (Exception)` 兜不住，直接 500。
  回归用例 `extractZipWithImplodedEntryDecodesContent`（fixture `imploding-8Kdict-3trees.zip`）。
  升级 Boot 或 commons-compress 时重新核对这对版本。
- BrowserView 弹窗守卫模式与 window.open 消费者、截图假死根因见 v0.6.1 修复（desktop-interaction-bugfix 记录）；改 BrowserView 生命周期务必测全屏/黑屏恢复路径。
- **摘下窗口的 BrowserView 会被 Chromium 冻住渲染进程**（后台标签的正常待遇，页面状态照留）。
  自动化里往冻着的 target 里 evaluate 会一直挂到 CDP 的 protocolTimeout，最后只落一句
  「Runtime.callFunctionOn timed out」——desktop-e2e 那一段为此自带超时与「等它醒过来」轮询。
- **浏览器面板的注入脚本是一行**：加代码时别顺手写 `//` 注释，整段会当场死掉且只在页面控制台
  留一句 SyntaxError（详见上面「注入脚本的三条硬规则」）。app-e2e 的 J6.7 与
  `BrowserProxyControllerTest` 都能拦住。
- **给网页标签加保活池要分宿主**：Web 走组件池，桌面走 BrowserView detach；两边都开等于
  桌面端把多个 BrowserView 同时挂上窗口。
- 剪贴板去重靠指纹+window 级状态，改动监听逻辑先读 PR#148/#151 教训。
- `checkba:fs-read-file` 有敏感路径拦截，别为新功能开任意路径读写。
- Kokoro 大陆网络 401 = hf_xet 绕镜像问题，禁 xet 修（PR#142）。asr-models 的下载走同一条路，同样禁 xet。
- **asr-service 不像 kokoro 那样把服务门在模型上**：kokoro 的 descriptor 有 `enabled`（没模型不起进程），asr-service **没有**。就绪探测必须能分开「服务没起」（重启应用）与「模型没下」（下 1.5GB），不起进程就只剩前一种结论，用户照提示重启一万次也不会有模型。
- **`POST /api/files/{id}/upload` 同时是编辑器自动保存的落点**（`LibreOfficeEditor.uploadBytes`
  不带 `X-File-Total-Size` 头，命中 `FileController` 的 legacy 分支）。挂在那条分支上的
  「上传完成」副作用因此在一份正在编辑的文档上**每存一次盘就跑一次**。
  已踩过一次：`AutoTaggingService` 每次都调一遍 LLM 拿 5 个新词，而
  `getOrCreateSystemTag` 只按精确字符串去重，标签无上限累积（实测单文件 338 个），
  每次自动保存还白烧一次辅助模型的钱。修法是**在 AutoTaggingService 里加幂等闸**
  （文件已有系统标签就跳过，`AutoTaggingServiceTest` 钉住），不是在 FileController
  里按调用方分类——那要靠猜谁是自动保存。
  **同一条分支上的 `refreshProjectKnowledgeIncremental` 不要跟着砍**：它只做
  `retrieverCache.remove(projectId)`，几乎零成本，而且内容变了让缓存失效正是对的，
  砍掉会让 AI 读到旧内容。往这条分支上加新副作用前，先问「一份文档存 60 次盘，
  这件事跑 60 次可以吗」。
- **免费额度的红线**：剪贴板是「隐藏超出部分」、缓存区是「拒绝新增」，两者都**绝不删除或清理用户已有数据**。任何"顺手清理超额记录"的改动都是回归——用户付费后必须能看到之前被隐藏的全部内容。
- **权益失效不等于把人锁在外面**：`applyOnStartup` 无条件应用自选存储根（权益失效后数据照常读写），因此 `GET /api/storage/location` 与「恢复默认位置」都**不设权益闸**——否则 Key 被吊销 + 外置盘拔掉的用户既看不到自己数据在哪，也换不回默认位置。付费闸只加在「改到新的自选位置」这个动作上。
- `StorageException` 默认落到通用异常处理器会被替换成「服务器内部错误」（防路径回显）。存储位置那几条用户语言文案是在 `StorageLocationController` 里转成 `IllegalArgumentException` 才送出去的，新增可回显文案要走同一条路且确保不含路径。

## 验证

- desktop 服务栈：`cd desktop && npm test`（service-manager/model-manager/pysvc-runtime/**browser-views**）。
  `browser-views.test.js` 钉住的就是上面那套记账：复用不重载、detach 不销毁、隐藏恢复只挂回前台、双开计数。
- 后端相关单测：TtsServiceTest、FileControllerChunkedUploadTest、ProjectFileService*Test、
  **BrowserProxyControllerTest**（SSRF 例外名单默认关、注入脚本能解析、proxify 绝对地址 +
  URL_CHANGED、JS 字面量转义、HTML 带 charset）等；剪贴板/收藏/搜索/OCR 暂无专属测试。
- UI 链路：`cd frontend && npm run test:app-e2e`。其中 **J6.7 浏览器面板**覆盖 Web/H5 这条链路
  （harness 自起两页小站 → 开两个网页标签 → 页内点链接跳第二页 → 切走再切回来断言仍是同一个
  文档实例 + 地址正确）。跑它要给后端加
  `SECURITY_BROWSER_PROXY_E2E_ALLOWED_HOSTS=127.0.0.1`，否则这一段会显式 skip（不假绿）。
  桌面端（BrowserView）那一半在 `npm run test:desktop-e2e`。
