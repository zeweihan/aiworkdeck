---
name: office-addin
description: Microsoft Office 与 WPS 插件领域。任务涉及 Word/Excel/PPT 任务窗格插件（office-addin/）、manifest、Office.js、WPS 加载项（wps/ 壳、wpsExecutor、publish 安装页）、插件与后端的对话/上下文契约、sideload 调试时，先读本文档再动代码。
---

# Office 插件 领域地图

职责边界：`office-addin/` 目录下的 Office Add-in（任务窗格 UI、Office.js 文档访问、与后端的连接/对话链路、office_command 执行器）。总 spec 见 `docs/superpowers/specs/2026-08-06-office-addin-and-memory-sync.md`（Phase B 脚手架、Phase C 工具桥+会话能力过滤、收尾包 Excel/PPT 宿主+SSE 重连+awdk 直连+部署脚本已落地；Phase D 云后端生产化已于 2026-08-07 部署：官方托管实例 addin.aiworkdeck.com，profile=`application-cloud.yml`，部署材料与实录见 `deploy/cloud/README.md`，配置口径见 licensing-billing.md）。SSE/编排契约本体属 ai-chat 领域；office_* 后端桥（OfficeBridgeService/OfficeEditTools/能力过滤）详见 ai-doc-bridge 领域文档「第二条桥」一节。

## 关键文件

- `office-addin/manifest.xml` — XML add-in only 清单（不是 unified JSON）。Hosts=Document+Workbook+Presentation（Word/Excel/PPT 三宿主，VersionOverrides 各有 ribbon 按钮）；开发态 URL 全指 `https://localhost:3000`，部署用 build-manifest.mjs 生成替换版。改后跑 `npx office-addin-manifest validate manifest.xml`（Version 必须 >= 1.0，低了直接判非法）。
- `office-addin/appsource/` — **AppSource（Microsoft Marketplace）提交包**（dev-board#696）：`README.md` 是提交流程一页纸 + 1100/1120 政策对照表，`listing.{en-US,zh-CN}.md` 是逐字商店文案（Name≤50 / Summary≤100 / Description 按 4000 控），`certification-notes.en.md` 是审核员操作说明模板（凭据只用占位符，真值只填 Partner Center），`assets/store-logo-300.png` 300x300 商店 logo，`screenshots/README.md` 是 1366x768 截图规格与镜头清单。**清单的 en-US Override 由 `office-addin/scripts/manifest-locale.test.mjs` 守着**（已并入 `npm test`）：`DefaultLocale` 恒 zh-CN，`DisplayName`/`Description`/每条 `bt:String` 都必须有 en-US Override——AppSource 的商店列表语言必须是清单声明支持的 locale，漏一条就开不了英文列表。同一条测试还钉住 `IconUrl`=32x32、`HighResolutionIconUrl`=64x64（任务窗格加载项的商店硬要求，原先错指 icon-80）。提交包**必须用不带 `--china` 的国际站变体**（政策 1120.1 要求 office.js 走微软全球 CDN），且构建时要 `VITE_ADDIN_SERVER_URL=https://addin.workdeck.ai`（默认后端是构建期焊进 bundle 的，缺省指国内站）。
- `office-addin/scripts/build-manifest.mjs` — 生产部署产物生成器（node 内置模块）：`npm run build:deploy -- --url https://addin.yourfirm.com [--china]` 出 `dist-deploy/`（dist 页面 + 替换 URL 的 manifest）；`--china` 把输出里 taskpane.html 的 office.js CDN 换成世纪互联 `appsforoffice.cdn.partner.office365.cn`（源文件永远指全球版 CDN，dev 流程不变）。
- `office-addin/taskpane.html` — 入口页。**office.js 必须从微软官方 CDN 以 script 标签引入，绝不打包进 bundle**（微软明令禁止自托管/打包）。
- `office-addin/taskpane/` — Vue3 源码：`App.vue`（视图切换+项目下拉；header 不再自绘品牌名——Office 按 manifest DisplayName 已画一条标题，2026-08 去重）、`components/SettingsView.vue`（主路径=账户登录，顶层两个 tab 手机号 / 邮箱，**两个 tab 的主路径都是验证码**（`mode` + 邮箱 tab 内的二级 `emailMode`：`code` 主路径、`password` 留给存量口令账号，靠「改用口令登录 / 改用验证码登录」一行文字链切换），60 秒重发倒计时两个渠道共用；**人机验证控件的 `#login-captcha` 与 `-trigger` 两个节点必须留在 v-if 分支之外**——控件在 onMounted 时按 id 认下节点并一直持有，放进分支里用户切一次 tab 节点就被拆掉，滑块弹不出来也取不到 token，而且一声不响；后端地址、awdk_ Key、awdt_ 设备令牌三项一起收进「高级设置」折叠区。**插件判不了站点**——它面对的是云后端不是官网，所以不学 unlock.vue 按站点自动选手机号/邮箱，直接给两个 tab 让用户挑；官网回 `sms_not_supported_on_site` 时提示文案就在旁边）、`components/ChatView.vue`（纯渲染+交互层；会话态全在 `lib/chatSession.js` 模块级 store——messages/conversationId/SSE 连接/streaming/草稿，切视图不卸载会话）、`lib/`（settings/api/sse/wordDoc/officeExecutor/chatSession/minimalEdit/captcha）。会话持久化：conversationId 按项目落 localStorage（`awd_addin_conv_{projectId}`），窗格重建时经 `GET /api/ai/history` 回灌（tag parser 拆 thinking/final，工具 chip 不回灌），回灌建连的首个 run_state 是权威状态（RUNNING 则锁输入续写，restorePending 标记位区分于 send 建连语义）。**2026-08-25 批次二（PR#597）**：计划审批卡（解析器新增 onArtifact，<artifact> 整块进消息卡）；引用定位（officeExecutor.locateInDocument 客户端直连 search+select，「」/“” 引文 6-80 字符成可点 chip）；批注队列/总结/校对空态快捷入口；附件选择器（contextItems 后端既有字段按 fileId 读内容）；会话删除/重命名（后端新端点 DELETE /api/ai/conversation/{id} 与 POST .../title，RUNNING 拒删 409、标题 1-60）；**中英双语**（lib/i18n.js 平铺 215 键，Office displayLanguage 判定，模板裸中文由 i18n.test.js 静态扫描钉死；模型可见的指令性错误文案保持中文不翻）。**部署注意**：云后端 jar 本地打包必须带 `-Djavacpp.platform=linux-x86_64`（否则 1GB 全平台包）；scp 大 jar 必须 sha256 对账后再换入（2026-08-25 北京实测传输损坏起不来）；**SG 服务已钉 /usr/lib/jvm/jre-21/bin/java**（此前 /usr/bin/java 是 17，plugin-api 刻意 release=21，动态插件类在 SG 一直静默加载失败）。
**2026-08-25 体验大改（PR#594，dev-board#147-150）**：流式渲染前导空白守卫（与桌面端 useAgentStream 同口径，改解析路径必跑 streamRender.test.js）；bubble_end 标 done+durationMs（界面「已完成 · N 秒」）；发送前 `connection.reconnectNow()` 等 emitter 挂上再 POST（退避窗口丢终态的根治）；office_command 执行/回传全链 try/catch、失败详情落 chip.error 用户可见；历史会话面板（GET /api/ai/conversations + switchConversation 复用回灌语义）；技能面板与模型选择器（skillIds/model 随 postChat 上送，均为后端既有字段）；「附带正文」改文档名 pill，不附带时仍上送 activeContext 壳（id/name/fileType，readDocumentMeta）——壳不上送则后端 office 工具指引整段不注入。officeExecutor：insert_text 锚点跨段自动按插入方向取段降级（pickAnchorFallback）；**replace_text 跨段快速失败**（自动降级会把多段替换文本塞进单段造成重复，有意不做）。**云后端 skill 部署**：两台 addin 主机 /opt/aiworkdeck/cloud/skills-builtin + env AI_SKILLS_BUILTIN_DIR（此前云端零内置 skill）；启停状态在 system_setting 的 ai.skills.disabled/seeded（云上启用 listing-pathway 与 shareholder-meeting-verification，依赖本机服务的 5 个禁用）。
**2026-08-26 一揽子体验修复（dev-board#173-177）**：品牌对齐重做视觉——配色从遗留深蓝 #1f3a5f 换成品牌森林绿体系（forest #1A5336 / mint-deep #2D7A52 / 暖白 ivory 中性阶，对齐 frontend/src/uni.scss 的 $awd-* 与「外壳保持浅色」红线），2026-09-20 东方清雅换代后已再换成墨竹青 #2E5A50 / 悬停 #254A41（对齐 office-addin/taskpane/styles.css 的 `--awd-primary`/`--awd-primary-hover`），毛玻璃 `.glass` 工具类（backdrop-filter，@supports 与 prefers-reduced-transparency 双兜底）在 styles.css；动效走 `lib/motion.js`（animejs v4 依赖，riseIn/panelUp/popIn/staggerIn 四个有动机的浮现，prefers-reduced-motion 全退化）。**登录态持久化**：settings.js 双写 localStorage + OfficeRuntime.storage 镜像（Office 清 webview 缓存后由 `hydrateSettings()` 回灌，App.vue onMounted 调），退出用 `clearToken()`。**头部改版**：Logo 图形（dist 根的 icon-32.png，运行时相对路径动态绑定绕开 vite 改写）+ 项目下拉（**≥1 个项目就渲染**，单项目也可见可换）+ 语言切换按钮 + 右上角登录入口/头像菜单（`GET /api/auth/me` 取 displayName/avatarUrl，头像缺省显首字母；菜单含「连接与高级设置」与退出）。**语言手动切换**（i18n.js `setLang`/`getLang`，localStorage `awd_addin_lang` 覆盖 Office displayLanguage，App.vue 用 `:key` 重挂载刷新全部 t()；**chip 名已于 dev-board#713 改成渲染时现查，不再随模块加载定死**）。**composer 多层菜单**：附件/技能/模型/历史收进「+」两级菜单（menu-panel 锚 composer 上方，模型是二级页），底部只留文档 pill/+/新对话；z-index 层级：overlay 面板 20 < 捕获层 24 < 菜单 26 < 头部 30，**composer 不许设 z-index**（会压住 overlay 面板，2026-08-26 真渲染走查踩过）。**听写回显修复**（后端 VoiceDictationService）：input_audio 排前、提示词排后（约束末位），返回文本过 `scrubPromptEcho()`——先逐句剥 PROMPT_SENTENCES 原文再按 PROMPT_MARKERS 标志词丢残句（首句常被弱模型改写，精确匹配够不着）；提示词措辞改动只许动 PROMPT_SENTENCES 数组（与剥离规则同源）。
**2026-08-27 UI 细节批次（dev-board#192-197）**：未登录空态改品牌欢迎卡（ChatView `.welcome`，Logo icon-64.png 同款运行时相对路径）；语言切换改地球 SVG+目标语言缩写；**账户菜单只展示身份与 AI 额度**（`/api/auth/me` 的 phoneMasked/emailMasked + `/api/platform-ai/key/status` 的 remainingUsd/limitUsd——注意 `/api/account/*` 是机器级、云后端普通租户禁入，别换回去），「连接与高级设置」入口已删：登录后不再有任何路径回到登录表单（那个入口让用户以为被登出，dev-board#194），设置页只在未登录时可达。**历史与模型是常驻 pill**（composer 行，dev-board#195），「+」菜单只剩附件与技能；模型 pill 直达二级模型页（openModelMenu）。**项目下拉带 `__new__` 哨兵项新建项目**（弹层输入名 → `POST /api/projects` 固定 `projectType:'BLANK'`，其余类型是桌面端向导的事）。**助手气泡走轻量 Markdown**（`lib/markdown.js`：先整体 HTML 转义再套标签、行内代码用 NUL 哨兵占位、`\n{3,}` 折叠成段距、链接只放行 http(s)；回归 `markdown.test.js`）——样式在 ChatView **非 scoped** 第二个 style 块（v-html 内容拿不到 scoped 属性），流式光标是 `.md-streaming > :last-child::after`（keyframes 也必须放非 scoped 块，scoped 会给动画名加 hash）。前导空白守卫升级：首个非空块的前导空白也裁（"\n\n正文" 混合块曾漏过）。**吸底守卫**：距底 48px 内才跟随流式滚动，用户上滚回看不再被拽回；自己发消息恒滚底。
**充值入口（dev-board#198）**：付费**不进任务窗格**——云后端多租户且刻意不落用户 awdk_（明文不落库），`/api/account/recharge` 是机器级（MachineAccountGuard），收银台只能在官网账户页。插件端 `lib/site.js`：`rechargeUrl(serverUrl)` 白名单映射（addin.aiworkdeck.com→aiworkdeck.com/account、addin.workdeck.ai→workdeck.ai/account；**不做「去 addin. 前缀」通用推导**，私有部署会推出不存在的链接）+ `openExternal()`（优先 Office.context.ui.openBrowserWindow，Mac WKWebView 会吞 window.open）。两个入口：头像菜单「充值」项（额度行剩余 ≤$1 转警示色）；SSE error 含 `AI_QUOTA_EXHAUSTED`（LlmErrorClassifier 标记）时换成引导文案+`errorKind:'quota'`，气泡下挂充值按钮，上游英文原文不外漏。回归：site.test.js + streamRender.test.js 配额用例。connect 403 且本地无消息 → 丢弃存量 conversationId 重新签发重试一次（云后端签发登记簿是内存态、重启即清，存量死 ID 曾把用户永久锁死在「SSE 403」）；`createConversation` 仅端点 404（旧后端）才允许回退客户端自造 conv-*，403/5xx 抛错不落盘。项目非必选：无项目时静默调 `POST /api/projects/ensure-addin-default` 懒建「插件临时项目」，单项目不渲染下拉。默认后端地址构建期注入：`vite.config.js` 的 define `__ADDIN_DEFAULT_SERVER__`（`VITE_ADDIN_SERVER_URL` 环境变量可覆盖，缺省 `https://addin.aiworkdeck.com`），`settings.js` 在 localStorage 无值时回落到它；build-manifest.mjs 只拷 dist 不重新构建，换默认地址要在 `npm run build` 前设环境变量。
- **发版硬步骤（2026-08-19 维护者定）**：每次桌面端发版都必须重建并上架这对 pkg+exe（自 v0.21.0 之后的发版起强制），流程见 `.claude/agents/eng-infra.md` 发版链路第 4.5 步。
- `office-addin/installer/` — 独立安装器（2026-08-19 起，AppSource 之外的分发路）：`npm run build:installers` 产出 macOS DMG（内含用户态安装器 .app，swiftc 编译，把生产 manifest 拷进三个 Office 容器的 `wef/`；Gatekeeper 测试口径红线见 installer/README.md——合成 quarantine 串不许用 0087 标志位，验收以浏览器真实下载为准）与 Windows NSIS exe（`HKCU\...\WEF\Developer` sideload 键，免管理员）。版本号取 desktop/package.json；产物上传服务器 `/opt/aiworkdeck/cloud/web/office-addin/dl/`（2026-08-24 起国际站 addin.workdeck.ai 同样铺 taskpane 资产——用不带 --china 的 dist-deploy 变体；两台 addin vhost 已开 access_log /www/wwwlogs/addin.*.log）（稳定名 `AI-WorkDeck-Office-Addin.dmg/.exe` 是指向带版本文件的软链）。两个安装器都只带 manifest，功能更新全在服务端，manifest 结构变更才需用户重装。坑：makensis 需要 UTF-8 locale（构建脚本已内置 LC_ALL）。**mac 不许用 pkg**：macOS 26 起容器保护直接拒绝 root 安装脚本写 Office 容器（dev-board#68，v0.21 时代的 pkg 从未真正装成过）；写 wef 只能在用户会话内做，且验证必须走真双击链路而不是 Terminal 手跑。.app 自动走 Developer ID Application 签名（证书在维护者钥匙串，备份 fastlane/certs/devid-app-local.*），给齐 NOTARY_* 三个环境变量则 DMG 自动公证+装订（配方见 installer/README.md）；exe 仍未签名。**2026-08-30 安装器 UI 重设计**：mac 侧 `mac/main.swift` 从纯 NSAlert 改成单窗口 AppKit UI（440x580，`NSAppearance(named:.aqua)` 锁 light 外观，配色对齐 uni.scss 的 awd 令牌）——三宿主 Word/Excel/PowerPoint 各一行状态点，安装改由「开始安装」按钮触发而不是启动即写（TCC 授权弹窗因此有上下文），成功/需授权两种终态在窗口内换文案+按钮而不是弹一串 Alert；**安装机制一行未动**（读内容重写避 quarantine、TCC 拒绝时 reveal DMG 根目录 manifest.xml 兜底）。win 侧 `win/installer.nsi` 重写成 MUI2 品牌向导：中英双语 LangString 按系统语言自动选（`MUI_LANGUAGE "SimpChinese"`/`"English"`），侧栏/页眉 BMP + `installer.ico`（PNG 条目 ico，NSIS 3 直接嵌），`VIProductVersion` 文件属性；**刻意不设目录选择页**（装的只是一份清单文件）；构建期新增 `-DARTDIR=` 由 build-installers.mjs 传入。两端美术源在 `installer/art/`（三个 HTML）+ `installer/render-art.mjs`（headless Chrome 截图 → ImageMagick 转 24 位 BMP3 + sips 出 win 图标/DMG 背景 1x2x PNG；依赖 Chrome/ImageMagick/sips，产物入库，维护者改美术时手动跑，CI 不需要）。build-installers.mjs 的 DMG 打包新增背景排版：stage 里放 `.background/background.tiff`（构建时 tiffutil 从入库 1x/2x PNG 合成 hidpi TIFF）→ hdiutil UDRW → Finder osascript 排版（窗口 660x442，图标位 app(330,200)/manifest.xml(572,316) 与背景图光晕联动）→ **轮询 `.DS_Store` 出现 `icvp` 记录，确认 Finder 异步落盘完成才 detach**（不等就封盘会把空壳 .DS_Store 封进只读 DMG，排版白做且无报错，实测踩过）→ UDZO；Finder 自动化被拒或锁屏时降级为朴素 DMG，不阻断构建；`.DS_Store` 用本机 Finder 写而非 dmgbuild，正是避开桌面端同期发现的 pBBk 兼容性问题（见 eng-infra.md）的做法。WPS 网页安装页（`publish/`）本轮刻意未动。**2026-08-31 win 侧再升级为搜狗式一键 UI（dev-board#339）**：`win/installer.nsi` 换装与桌面端共享的 `desktop/build/win/awd-oneclick-ui.nsh` 引擎（大卡片一键安装→右上角进度小卡→完成卡提示重开 Office；不开 AWD_UI_DIR_CHOICE 即无目录展开），位图由 `desktop/scripts/render-oneclick-art.mjs --product addin` 构建现场渲染到 `installer/win/generated/`（不入库），build-installers.mjs 传 `-DAWD_UI_ENGINE/-DGENART/-DLEGALBASE`（法务链接随 --url 站点派生）。老的 MUI LangString 欢迎/完成页删除；`installerHeader.bmp` 仅供卸载器页眉；改卡片布局要同步引擎 AWDUI_* 常量与 oneclick-*.html。
- `office-addin/taskpane/lib/wordDoc.js` — 宿主检测 `detectHost()`（word/excel/powerpoint）+ 宿主感知的 `readActiveDocument()`：Word=正文纯文本、Excel=活动表已用区域 TSV（上限 2000 行）、PPT=各页形状文本（上限 100 页，需 PowerPointApi 1.4）。
- `office-addin/taskpane/lib/officeExecutor.js` — **office_command 执行器**：HANDLERS 表（Word 面 get_text/get_selection/search/replace_text/**replace_batch**/insert_text/add_comment/format_text/set_paragraph_format/get_formatting/set_numbering/format_table/apply_standard_format/insert_table/table_read/table_set_cell/table_add_row/table_delete_row/table_add_col/table_delete_col/insert_break/set_hyperlink/edit_header_footer/get_comments/reply_comment/resolve_comment + 修订/脚注尾注/图片/样式/内容控件/文档属性（批次 9）get_revisions/accept_revision/reject_revision/insert_footnote/insert_endnote/insert_image/apply_style/manage_content_control/set_document_properties + Excel 读写面 excel_get_range/excel_set_values/excel_search + Excel 格式/结构面（批次 6，15 个 command 与桌面端 sheet_* 数量对齐）excel_format_cells/excel_set_borders/excel_edit_rows_cols/excel_merge_cells/excel_sort_range/excel_manage_sheets/excel_freeze_panes/excel_set_formulas/excel_get_overview/excel_select_range/excel_set_autofilter/excel_conditional_format + Excel 批注/校验/图表/命名区域/保护/分组/透视表（批次 9）excel_add_comment/excel_get_comments/excel_reply_comment/excel_resolve_comment/excel_delete_comment/excel_set_data_validation/excel_add_chart/excel_define_name/excel_protect_sheet/excel_group_rows_cols/excel_add_pivot_table + PPT 面 ppt_get_slides/ppt_replace_text/ppt_format_text/ppt_add_slide/ppt_delete_slide/ppt_add_text_box/ppt_move_slide/ppt_add_shape/ppt_get_slide_details/ppt_delete_shape，批次7 + ppt_add_table/ppt_table_read/ppt_table_set_cell/ppt_set_hyperlink，批次9）+ COMMAND_DISPLAY_NAMES 固定中文名 + COMMAND_HOSTS 宿主守卫（宿主不符回 `{ok:false, error:'unsupported host: ...'}`）。未知 command 回 `{ok:false, error:'unsupported command'}`。律所标准格式常量 `HOUSE` 与小标题启发式 `HEADING_RE` 也在本文件（apply_standard_format 用）。PPT 面版本门槛靠通用 `pptApiSupported(version)`：绝大多数命令卡 PowerPointApi 1.4（`requirePptTextApi()`），`ppt_move_slide` 与 `ppt_add_slide` 的挪位置分支单独卡 1.8（`Slide.moveTo`），表格三件套（批次 9）卡 1.8（`requirePptTableApi()`，实测比矩阵调研猜测的 1.9 更宽松），`ppt_set_hyperlink` 单独卡 1.10（`requirePptHyperlinkApi()`）。Excel 批注定位统一传 `Range` 对象给 `getItemByCell`（不用 `"Sheet!A1"` 限定字符串），`get_revisions`/`accept_revision`/`reject_revision` 卡 WordApi 1.6（`trackedChangesSupported()`），脚注/尾注卡 1.5（`footnoteApiSupported()`）。
- `office-addin/taskpane/lib/batchEdits.js` — **批量改写（replace_batch）的入参契约单源**（dev-board#419）：`normalizeBatchItems`（空批/超 50 条/searchText 空或跨段或超 255 字/**条目间 searchText 重复**一律整批拒绝，进宿主 API 之前就拦下）与 `sortByIndex`。Office 面与 WPS 文字面各有自己的落笔实现，但**入参校验必须共用这一份**——后端只有一个 `office_replace_batch` 工具描述，两边对模型说的话不能有出入。上限 50 与后端 `OfficeEditTools.MAX_BATCH_EDITS` 是同一个数，改一处要改三处（还有工具描述里的字面数字）。
- `backend/.../service/ai/OfficePassChunker.java` + `OfficePassStateStore.java` + `OfficeEditTools.office_pass_step` — **Word 整篇过卷（dev-board#422）**。切块器是纯函数（按 `\n` 切段、序号 1 起与内联正文一一对应、目标块 2500 字、超 2 倍目标的单段独立成块、块数上限 60 靠抬块大小达成）；状态是内存态按会话（游标/累计改动/累计失败/建态时的正文哈希，TTL 30 分钟**滑动窗口**）；工具本体只做机械事——切块、维护游标、校验、转调 `replace_batch` 落笔、汇总。**插件端零改动**：过卷不新增任何宿主命令，落笔复用两个家族都已有的 `replace_batch`。
- `office-addin/taskpane/lib/sse.js` — SSE 消费 + **断线自动重连**：指数退避 1s 起上限 30s；心跳（后端 15s 一次）缺失 40s 判死连接主动重建；首连失败不重连（ready reject 即时报错）；onClose 只在主动 close 时触发，重连状态走 onStatus。另含 `createTagStreamParser`（标签流→主文本/思考/反问三路，见下方「标签流解析」契约），回归用例 `taskpane/lib/sse.test.js`（`node --test office-addin/taskpane/lib/sse.test.js`，零依赖，插件仓没有测试框架）。
- `office-addin/assets/` — 16/32/64/80 图标，源自 `desktop/build/icon.png`（sips 缩放），构建时拷入 dist 根（URL 无 /assets 前缀）。
- `office-addin/vite.config.js` — 端口 3000；自动读 `~/.office-addin-dev-certs` 证书启 https；`publicDir: 'assets'`；
  **构建期 `base: './'`（dev 仍是 `/`）**——dist 会被托管在子路径下（官方云是 `/office-addin/`），
  默认的绝对 `/assets/...` 会打到站点根、被 SPA 回退顶成 index.html，任务窗格白屏（2026-08-07 真机踩过）。

## 核心契约

- **鉴权**：awdt_ 设备令牌放 `X-Session-Id` 请求头（后端 `getUserIdFromSession` 前缀解析）。连接测试 = `GET /api/projects/my`。桌面端生成 awdt_ 的界面在 userprofile「插件访问令牌」分组（走 `POST /api/auth/device-token/issue-local`，仅 local-mode，见 licensing-billing.md）。
- **连接的三条路，换回来的东西完全相同（都是 awdt_ 设备令牌，凭据本身用完即弃不落盘）**：
  1. **账户登录（主路径，v0.18 起；邮箱验证码 dev-board#695 补齐）**：`POST /api/auth/account-login`，body 三选一 `{phone, code}` / `{email, code}` / `{account, password}`（判定顺序就是这个，见 `AuthController.accountLogin`）；配套 `POST /api/auth/account-login/send-code`（body `{phone, captchaToken}` **或** `{email, captchaToken}`，两个都空即参数错误）与
     `GET /api/auth/account-login/captcha-config`（人机验证的公开配置，`{provider:null}` = 未启用）。三条都是**匿名**端点。后端拿凭据调官网 `/api/auth/exchange-key` 换出 awdk_ Key，再走既有桥接，用户从头到尾看不见 Key。
     **发码按渠道转发到官网两条路由**：手机号 → `/api/auth/sms-login/send-code`（`AwdkLoginService.sendLoginCode`），邮箱 → `/api/auth/mail-login/send-code`（`sendLoginCodeByEmail`）；两条的 body 形状逐条对应（只差字段名 phone/email），人机验证同样排在发信之前。
     **`{email, code}` 不是可选项**：国际站的邮箱验证码注册建出来的账号 `passwordHash` 是空串（压根没有口令），口令注册已随邮箱验证码上线一起关掉——少了这条，新国际站用户在官网注册完就再也登不进插件。站点不支持某条渠道时官网给 `sms_not_supported_on_site` / `mail_not_supported_on_site`，两个码在 `AccountLoginExchange.errorMessage` 里各有一条「改用另一种」的双语文案（回落成通用的「登录失败，请稍后重试」等于不告诉用户该怎么办）。
  2. **awdk_ 一键连接**：`POST /api/auth/awdk-login`（body `{key}`，匿名）。
  3. **手工粘 awdt_ 设备令牌**：私有部署与团队服务器。
  后两条在设置页降权进「高级设置」折叠区——私有部署与团队服务器还要用（同官网 PR#67 的理由）。
  三条共用同一个开关 `security.awdk-login-enabled`（默认关，官方云后端开）：账户登录与粘 Key 是同一条桥的两个入口，拆两个开关只会造出「登录能用但桥是关的」这种自相矛盾的配置。信封统一 `{code:0, data:{token, userId, username}}`；开关未开/旧后端 404 → 提示改用高级设置里的 Key 或设备令牌。
- **为什么不能复用桌面端的 `/api/account/login`**：那条开头就 `requireUser(sessionId)`。桌面端 local-mode 会把它自动解析成本机用户所以没事，云后端 `local-mode=false` 下「登录前得先有会话」是死循环，那个端点在云上自锁死。云侧的等价物是上面那两个 `/api/auth/account-login*`。
- **对话**：`POST /api/agent/chat` + `GET /api/agent/connect/{cid}` SSE（fetch + ReadableStream）。conversationId 优先服务端签发（`POST /api/agent/conversations`，body `{projectId}`，契约与后端并行分支约定），404/失败静默回退客户端 `conv-<毫秒>`。
- **界面语言 → 回答语言（dev-board#713）**：插件与桌面端共用**同一条**语言链（`AppLanguageService` → `ContextAssemblerService` 选中/英文 system prompt），差别只在语言值从哪来。桌面端是单机，语言写透全局 system_setting `app.language`；**云后端是多租户，那个全局值恒为默认的 zh-CN，谁也不能替全体租户改掉它**——所以插件走**请求级覆盖**：
  - 客户端：`lib/i18n.js` 的 `getLangTag()`（`zh-CN`/`en-US`，**值域与后端 `AppLanguageService.SUPPORTED` 逐字对齐**，发 `zh`/`en` 会被当成没声明）→ `api.js` 的 `langHeader()` 给**每一条**请求加 `X-App-Language` 头（**每次调用现取**，切语言后下一条请求立刻生效），chat 请求体另带 `appLanguage` 字段。
  - 后端：`config/AppLanguageRequestFilter` 把请求头装进 `service/AppLanguageScope`（ThreadLocal 覆盖层），`AppLanguageService.language()` 覆盖优先、否则回落全局设置——**不带头 = 行为与引入前逐字节一致**，桌面端不受影响。
  - **请求体里的 `appLanguage` 不是冗余**：编排循环跑在 taskExecutor 池线程上，HTTP 线程的 ThreadLocal 不跟着走，`AgentOrchestrator.launchTurn` 必须用它重建作用域；而且请求体会被 AgentInbox 持久化，排队/续跑的那一轮照样说对语言。漏掉这一步是**静默故障**：英文用户拿到中文回答，不报错。
  - 新加的请求头要同步进 `CorsConfig` 的 `Access-Control-Allow-Headers`（窗格与后端同源时不触发预检，分域的私有部署会整条卡在预检上）。回归：`appLanguage.test.js` + `AppLanguageScopeTest` / `AppLanguageRequestFilterTest` / `AddinRequestLanguageTest`。
- **文档上下文**：按宿主读取（见 wordDoc.js），经 `activeContext.inlineContent` 内联上送（客户端 200k 截断）；activeContext.id 用合成值 `office-current-document`。后端侧 inlineContent 优先于 read_document（ContextAssemblerService，服务端同样 200k 上限）。**正文省传**：同一会话内文档没变时客户端只上送 `activeContext.inlineContentHash`（SHA-256 十六进制，`wordDoc.js` 的 `hashContent`）、不带 inlineContent；后端 `InlineContentCache`（按会话，LRU 上限 32 条 ≈13MB）凭哈希取回上一轮正文，**哈希后端自算**（客户端上送值只当省传信号），未命中即按「无内联正文」现状处理不报错。
- **发送路径（批次 5 性能）**：会话签发（`POST /api/agent/conversations`）与 SSE 建连提前到 `preconnect()`——进面板/切项目（activateSession）与「新对话」时就做完，send 只剩「读文档 ‖ 兜底 preconnect → POST /chat」两件并行事。每轮四段耗时经 `console.info('[AddinPerf]', {...})` 输出并存 `lastPerf`（docReadMs/docChars/docReused/connectMs/chatAcceptedMs/firstTokenMs/totalMs），不上报遥测。
- **SSE 事件**：消费 text_delta/bubble_end/error/cancelled/run_state + `client_action`（仅 tool=office_command）。run_state 仅在断线重连后消费：漏掉终态事件时按状态兜底解锁输入框——首连的 run_state 不能当终态看（send 已先置 streaming）。**状态分两档，不许合成一个 stillRunning**：`generating`（RUNNING/PAUSED）锁输入等正文；`awaitingUser`（AWAITING_APPROVAL/**AWAITING_INPUT**）轮次没结束但球在用户这边，**必须解锁输入并给一行 notice**——任务窗格没有桌面端的「继续」按钮，答案/确认就是新一轮普通用户消息，锁着输入等于让用户永远答不上话。run_state 的 status 是枚举名（大写）、bubble_end 的是小写字面量（`awaiting_input`），比对前统一大写。
- **标签流解析（`createTagStreamParser`）**：主文本 = 裸文本 + `<final>` + `<question>` 正文（与桌面端 useAgentStream 同语义）；思考 = `<thinking>`；`<question>` 内的 `<option>` 子标签不进正文，闭合时整块经 `onQuestion({options})` 交给界面做按钮（无选项则回落输入框作答）。点选项 = `chatSession.answerQuestion(文字)` → `send(文字)`，选项原文原样当用户消息（短、像人话，因此不需要契约 D 的 displayText），**不清空输入框草稿**。**未知标签默认不外漏**：形状像协议标签（全小写 ASCII snake_case，≤24 字符）的未知标签只吞标记、内容按外层上下文继续渲染（连内容一起吞会在后端改标签名时给出空气泡）；其余尖括号（`<甲方>`、`<Party A>`）一律当正文——判据是「协议标签的形状」，不是「所有尖括号」。**非标签候选串只放行 `<` 本身、从下一字符重扫**（dev-board#70）：候选串是「`<` 到最近的 `>`」，正文孤立 `<`（如「净利润<0」）时它会把真正的 `</thinking>` 包进去，整段放行=闭合被吞、标签栈错位、后续工具载荷全部漏进思考区；逐字节流式因 PARTIAL_TAG_RE 早失配踩不到，**一次性喂入的历史回放必踩**——所以解析器改动必须同时跑单发与逐字节两组用例（sse.test.js 已钉住）。
- **显示内容 ≠ 发送内容（契约 D）**：历史里 USER 一条的正文取 `displayContent || content`（模型看 content、用户看 displayContent）。插件自己目前**不上送** `displayText`（没有计划审批卡那类要代拟回喂文案的场景），需要时在 `chatSession.send` 的 postChat payload 里加该字段即可，`api.js` 的 postChat 是整体透传。
- **工具桥（Phase C+宿主细分）**：chat 请求带 `clientCapability: "office"` + `officeHost: "word|excel|powerpoint"`（缺省 word）→ ClientCapabilityService 记录 → ToolRegistry 过滤：word 会话只见 Word 面 office_*（读写六个 + 格式六个）、excel 只见 office_excel_*、ppt 只见 office_ppt_*（前缀判定，`hostOfTool` 最长前缀优先）；doc_*/sheet_* 对 office 会话一律隐藏。ContextAssemblerService 的 office 分支文案按宿主点名对应工具集。命令链：SSE client_action `{tool:'office_command', requestId, command, args}` → officeExecutor 执行 → `POST /api/agent/office/result`。Word 修改类命令前置 `changeTrackingMode=TrackAll`、执行后恢复原值（WordApi 1.4 不支持时降级直改标 `tracked:false`）；**Excel/PPT 没有修订机制，写入直接生效**。
- **本地文件附件上传**（dev-board#262）：ChatView「+」菜单「上传本地文件」→ 隐藏 input type=file（多选，accept 覆盖图片/pdf/txt/md/csv/office 文档）→ `chatSession.uploadLocalFiles`：两步上传对齐桌面端 confirmUploadAndAddContext——`api.js createProjectFile`（POST /api/projects/{pid}/files/file，body {parentId,name,fileType,fileSize,wpsFileId}，返回 ProjectFile 本体无信封）+ `uploadFileBytes`（POST /api/files/{fileId}/upload，裸 octet-stream，X-File-Offset:0 + X-File-Total-Size，数字 id 与 wpsFileId 后端双查）。成功并入 attachedFiles（真实 fileId，contextItems 契约不变，图片/PDF 由后端既有 OCR/Tika 抽文本）；中间态在 `uploadingFiles` ref（composer 上方 upload-row pill：上传中转圈、失败标红可重试/移除）。**客户端单文件限 20MB**（UPLOAD_MAX_BYTES，超限不发请求）；createFile 失败无半截状态，字节上传失败不并入附件（空文件比不附更糟）；无选中项目时先 ensureAddinDefaultProject 懒建。fileType 映射与桌面端 getFileTypeFromName 同一张表。回归：`chatSessionUpload.test.js`。
- **模型视觉能力与图片降级明示**（dev-board#266）：后端 `GET /api/ai/models` 每条模型多了 `vision`（boolean）。支持视觉时图片附件由后端作为 image 内容块直送模型，不支持时后端**自动**降级走既有 OCR——**插件端不拦截任何东西**，只负责让用户在选模型那一刻就知道。判定在 `chatSession.activeModelVision`（computed）：`selectedModel || modelCatalog.defaultModel` → 查 `models` 里的 `vision`。三条硬要求：① **必须消费 defaultModel**（`selectedModel===''` 是「跟随后端默认」，也是绝大多数用户的状态，只判显式选中的模型等于对多数人永远静默）；② **三态，undefined 不等于 false**（插件连的是用户自填的服务器地址，很可能是旧后端；当成 false 会对所有模型误报「不支持读图」，未知时什么都不提示）；③ **用 computed 不用一次性赋值的 ref**（`refreshCatalogs` 在模型下线时会把 selectedModel 静默改回空串，赋值式的会僵在已不生效的模型上）。渲染点：模型二级页每行 + **「默认模型」那一行**（改成两行堆叠，230px 面板横排装不下「名目/真名/角标/勾」四件事）、附件面板与 upload-row 的图片条目角标、composer 里的常驻提示 `visionNotice`。**提示必须自成一个 ref，不许复用 `notice`/`banner`**：那两个被 `send()` 与 `finishStreaming()` 无条件清空，发一条消息就没了。两个时机都要覆盖（先选模型后加图 / 先加图后换模型），只做一个会静默一半场景。判图取并集 `isImageAttachment`：`fileType==='image'` **或** `fileTypeFromName(name)==='image'`——项目树里的 fileType 是后端原样透传的，桌面端那张表没有 bmp，只认 fileType 会漏判 .bmp。回归 `visionCapability.test.js`（21 例）。
- **跨设备文件传输面板**（dev-board#251）：`lib/transfer.js`（模块级 store + `/api/mobile/transfer/*` API 封装 + pollUntil 轮询辅助，requestId=crypto.randomUUID）+ `components/TransferPanel.vue`（全局 overlay，z-index 45，已加进 i18n.test.js 的 SCAN_FILES）。两个入口：项目下拉选中 remote:: 项（预选设备/项目）与 ChatView「+」菜单「跨设备文件」。拉取成功后可经 chatSession.toggleAttachedFile 附进对话；投送只支持云项目既有文件（投当前 Word 文档未做）。等待文案要如实说明 B 机约 1 分钟一轮轮询、首个响应通常 1-3 分钟。传输契约与计费红线见 mobile-sync.md「跨设备文件传输」节。
- **项目下拉的远程设备分组 = 归档绑定入口**（dev-board#250 → #297 语义升级，2026-08-30）：`refreshProjects` 顺带拉 `fetchMobileDevices` 渲染 `<optgroup>`，远程项目 option 的 value 是哨兵 `remote::<deviceId>::<key>`。**选中即绑定**（不再是打开传输面板——传输面板入口只剩「+」菜单）：`bindRemoteProject` 调 `POST /api/projects/ensure-addin-link` 拿云端影子容器项目 id，`projectId` 切到影子项目（会话/附件/上下文链路零改动），绑定映射存 `awd_addin_archive_links`（localStorage+OfficeRuntime 镜像，权威源是 `GET /api/projects/addin-links`，refreshProjects 先 merge 它——**否则「记住的项目已不存在」判定会误清绑定的影子项目**，那条判定已加豁免）。select 显示值走 `selectValue` computed（有绑定时落到远程条目；设备离线/目录被顶时 `boundOrphanOption` 补一个兜底选项防显示空白）。绑定项目的对话经云端 outbox 镜像进桌面项目 AI 历史（来源标注靠 chat 请求新字段 `officeFamily: 'office'|'wps'`），文档经 media inbox `document` 类型镜像落「插件文档/」（见 mobile-sync.md「插件归档双镜像」）。
- **文档镜像采集（dev-board#299，`lib/docSnapshot.js`）**：轮次终态（finishStreaming 单点）且本轮有成功**写入类** office_command（`isReadOnlyCommand` 名单外都算写）且项目有绑定 → `captureDocumentBytes()`：Office 面 `getFileAsync(Compressed)` 4MB 分片（**网页版 Word/Excel 拿不到**；取到的是内存态还是上次保存态官方无断言，按「镜像可滞后」接受）；WPS 面 `FullName + Application.FileSystem.readAsBinaryString` 探测链（**真机未验证**，产物必过 ZIP 头校验）。SHA-256 去重（哈希上传成功才提交）、50MB 上限、失败静默下轮重试；**拿不到字节只提示一次不硬凑**（维护者拍板）。上传走 `uploadRelayDocument`（POST /api/mobile/media multipart，clientMediaId=UUID）。新增写入类命令不用动这里；新增**只读**命令要进 `READ_ONLY_COMMANDS` 名单（漏加只是多拍无害，加错会漏归档）。

## 已知地雷

- 错误响应不得带 code=4010（PR4-0 起主前端只认 code=4010 判定未登录清会话，已不做「登录/未授权/请先」子串匹配；licensing 领域红线）。插件**自造**的文案统一说「连接未就绪/令牌无效/账户直连失败」，awdk 失败不透传服务端原文。**唯一的例外是账户登录那两条**（`api.js` 的 `postAnonymous`）：「验证码错误或已过期」「账号或密码不正确」是用户唯一能据此改正的信息，换成自造的通用文案等于把界面做成哑巴，所以原样透传。信封侧的 4010 红线不受影响（后端护栏 `AuthControllerHardeningTest.accountLoginNeverEmits4010`）。
- **账户登录端点的限速维度是「整条桥 + IP」，不是按手机号分桶**（`AuthController.ACCOUNT_LOGIN_RATE_KEY`）。云后端只是转发器，官网看到的来源 IP 恒为这台机器；按手机号分桶等于允许一个 IP 换着号码无限试，失败全部原样打到官网的按 IP 计数（15 分钟 30 次即锁），最后被锁在门外的是本服务器的全体用户。这一档必须比官网那一档紧。
- **人机验证 token 必须一路透传到官网**（dev-board#88）。官网 `/api/auth/sms-login/send-code` 把 `verifyCaptcha` 排在发短信之前，不带 token 恒 403「请先完成安全验证后再试」。这条链有三段，缺任何一段那道闸对插件端就等于把登录堵死：任务窗格渲染控件取 param（`lib/captcha.js`，与桌面端 `frontend/src/utils/captcha.js`、官网 `components/captcha/Captcha.tsx` 同源三份，改一份要看另两份）→ `api.js` 放进请求体 → `AwdkLoginService.sendLoginCode` 原样转发。控件参数只能走**匿名**的 `/api/auth/account-login/captcha-config`；`/api/account/captcha-config` 带 `requireUser`，云后端上是「取参数要会话、要会话先登录、登录先过控件」的死循环，与下一条同一个病根。取不到配置时静默降级成「未启用」，让报错发生在发码那步（有可读文案），而不是把登录整个卡死。
- **发验证码把「尝试」记在出站之前**（与 `/sms/send-code?scene=login` 相反）。那条身后有用户名口令挡着，这条是匿名转发；只记成功的话，拿一串无效手机号刷本服务器就能免费换来等量的对官网出站请求，IP 额度永远耗不尽。
- 全局禁 emoji；包管理 npm 不是 pnpm。
- **不许在模块顶层把 `t()` 的结果存进常量**（dev-board#713）。那等于把文案定死在模块加载那一刻，用户切语言后再也不变，而且**不报错**。`officeExecutor.js` 的命令 chip 名踩过这颗雷（`COMMAND_DISPLAY_NAMES` 的值是 `t('cmdX')`），症状是同一条会话里 Word 的 chip 是英文、Excel 的是中文。现在表里存的是 i18n key（`COMMAND_DISPLAY_KEYS`），`commandDisplayName()` 渲染时才查；**chip 对象上存 `command` 不存 `label`**，界面渲染 `commandDisplayName(tool.command)`，靠 App.vue 的 `:key` 重挂载跟着换语言——存 label 就又把文案冻在创建那一刻了。
- **懒建项目名只做展示层映射，不许改库里的数据**（dev-board#713）。后端按**建项目那一刻**的语言给「插件临时项目」取名并存进 `project.name`，用户之后切语言它不会跟着变。映射在 `lib/projectName.js`（两个固定名 ↔ 当前语言）。反过来「后台按界面语言把库里的名字改掉」是错的：项目名是用户可改字段，会覆盖用户自己起的名字，两台机器语言不同时还会来回翻。改后端 `ProjectController.ADDIN_DEFAULT_PROJECT_NAME_ZH/_EN` 两个字面量时必须同步 `ADDIN_DEFAULT_PROJECT_NAMES`，否则老项目的名字翻不过来（静默）。
- **语言值域两端必须逐字对齐**：插件发 `zh-CN`/`en-US`（`getLangTag()`），后端 `AppLanguageService.SUPPORTED` 只认这两个。发 `zh`/`en` 会被 `AppLanguageScope.normalize` 之外的比对当成没声明→静默回落中文。反过来后端的 `normalize` 刻意只按前两位语言码判定（容忍 `en-GB`/`zh-Hant-TW`）——整串匹配会把 Office/浏览器给的各种 locale 形态全判成不认识。
- 部署期 CORS：插件正式 Origin 要进 `security.cors.allowed-origins`；local-mode 下 LocalModeAccessFilter 用同一份白名单硬拦非 GET 跨站请求。`security.cors.allow-all` 绝不能开。localhost/127.0.0.1 默认放行，开发态零配置。
- **云后端的必配项清单**（官方实例 addin.aiworkdeck.com 已于 2026-08-07 按此清单上线，配置落在
  `application-cloud.yml`；再起新实例照这张单子过一遍）：
  `security.local-mode=false`（默认）、`security.registration-mode=closed`、`security.awdk-login-enabled=true`、
  `security.conversation-issuance-required=true`、`security.cors.allowed-origins` 填插件正式 Origin，
  以及环境变量 **`AWD_PLATFORM_KEY_SECRET`**（per-user 平台 AI 密钥的落库加密密钥，任意高熵串，
  例如 `openssl rand -base64 32`）。**最后这条是启动强不变式**：`awdk-login-enabled=true` 而它缺失时
  服务直接拒绝启动（`PlatformAiKeyCipher` 构造器，licensing 领域地雷 17），刻意不做明文降级。
  它与官网侧的 `AWD_KEY_ENCRYPTION_SECRET` 是两把互不相干的密钥，别复用同一个值。
- Office 只加载 https 任务窗格：dev 证书 `npx office-addin-dev-certs install`，vite 配置自动拾取。
- **新增 office_* 工具四件套**：后端 OfficeEditTools 加 @Tool + officeExecutor.js 的 HANDLERS 加实现 + **wpsWordHandlers/wpsEtHandlers/wpsWppHandlers 对应那一面也加实现** + COMMAND_DISPLAY_NAMES 加中文名，**并在 COMMAND_HOSTS 标宿主**。WPS 文字会话的 officeHost 同样是 `word`，所以只在 Office 面实现的 Word 工具会让 WPS 用户拿到 `unsupported command`。没有客户端实现的远端工具 = 30s 超时空转（PptxEditTools 教训）。**工具名前缀决定宿主可见性**：Excel 面必须 office_excel_*、PPT 面必须 office_ppt_*，其余 office_* 归 Word——起错前缀会漏进错误宿主的会话（死路径）。
- **多处修改必须成批（`office_replace_batch`，dev-board#419）**。这不是效率偏好，是**能不能跑完**的问题：`office_replace_text` 一次只改一处，而 Word 面每处的代价是「一整轮 LLM + 一次 SSE 下发 + 一个 `Word.run` + 七次 `context.sync()`」（其中四次只为把修订开关开了又关）。mock 实测（`officeReplaceBatch.test.js` 会把两个数打进 TAP 输出）：**改 20 处，逐处路径 140 次 sync / 40 次修订开关写入 / 20 轮 LLM；批量路径 7 次 sync / 2 次修订开关写入 / 1 轮 LLM**。叠加 `AgentOrchestrator.MAX_LOOP_DEPTH=30`，整篇校对一份合同（几十上百处）走逐处路径**结构上跑不完**，只会一路「正在操作文档」到撞上步数上限暂停——2026-09-03 用户报的正是这个。批量指引写在 `ContextAssemblerService` 的 Word 分支末位（约束挂末位），并有 `wordSessionIsToldToBatchMultiEdits` 钉住；Excel/PPT 宿主没有这个工具，指引也不许对它们说。
- **整篇任务走分段过卷（`office_pass_step`，dev-board#422），不是「再多批几批」**。#419 让「一批 50 处」成为可能，但模型仍要在一轮里把整篇几十上百处一次列全——长文档下要么漏、要么单次输出超长被截断整轮丢弃。过卷把它变成「一块一步」：每块聚焦、每块落笔、进度可见、可停可查。三条不变式：
  - **块不是上下文窗口**：内联正文（system prompt 里的 inlineContent）一字不动，每轮都在；块只是「本轮请你处理的工作面」。块与块之间的勾稽关系（A 块改了称谓，C 块要跟着改）由主模型自己把握，工具不做任何跨块推断，也不调任何子模型。
  - **edits 是全文查找语义，不限于当前块**：处理 C 块时发现 A 块要连带改，可以写进同一份清单。所以「块」只约束模型的注意力，不约束落笔范围——别在校验里加「searchText 必须落在当前块内」那种检查。
  - **切的必须是模型看到的那一份字节**（`InlineContentCache.contentOf`）：段落序号是模型与工具之间唯一的共同坐标系，改从别处（例如 `office_get_text` 重新读一遍）取正文就会错位。正文超 20 万字符不入缓存 → 过卷直接报错，不静默降级。
- **过卷状态是内存态，重启即丢**（`OfficePassStateStore`，刻意不落库）。进程重启后模型下一步会撞上「没有进行中的过卷」，于是从第 1 块重来——可接受，而为它建表做迁移不值。TTL 是**滑动窗口**（每次推进续命）不是固定窗口：一份长文档过卷本来就要跑十几分钟，固定窗口会让它跑到一半自己过期。**取消会清状态，正常收尾不会**——清理调用刻意只挂在 `handleCancellation`，不能放进 `clearCancelledState`（那个方法每轮正常收尾都调，放进去会让「撞步数上限暂停、用户点继续」的续跑丢掉游标从头再来）。
- **游标只在真的落完笔之后才动**。清单校验失败（返回 `Error:`）与过桥失败（超时/插件断开，返回 `{"error":…}`）两条路都**不推进游标**——推了的话模型重试同一份清单会从下一块开始，被跳过的那一块永远没人看，而且不报错。同理，首次调用要等落笔成功才建态。
- **步数预算不是恒 30**：过卷进行中 `AgentOrchestrator.maxLoopDepthFor()` 抬到 `min(30 + 块数, 120)`（无过卷仍 30）。过卷是刻意的多步推进，一块一步，恒 30 会让长文档跑到一半被迫暂停——正是 #419 要根治的形态换个位置复发。120 是硬上限（模型在过卷里打转时不能变成无限跑），撞上仍走既有 `paused/max_depth`，状态保留、用户可点「继续」。暂停提示里的步数按本轮实际预算报数（`maxDepthNotice(budget)`），别改回写死的 30。
- **进度事件 `pass_progress` 只是展示**（载荷与消费点见 ai-chat.md 契约节）：发不出去不影响工具结果，客户端收到坏载荷静默忽略；但 `done` 之后与轮次终态（`finishStreaming`）都必须归位，挂着「12/12 段」比不显示更误导。插件端状态在 `chatSession.passProgress`，渲染点是 ChatView 的 `pendingStatusText(msg)`。
- **`office_pass_step` 是唯一一个不对应任何插件端 command 的 office_\* 工具**：它不下发 `office_command`，落笔时转调既有的 `replace_batch`。所以「新增 office_* 工具四件套」那条对它不适用——`COMMAND_HOSTS` / `COMMAND_DISPLAY_NAMES` / 三个 WPS handlers 里都没有它，也不该加。要登记的只有 `frontend/src/utils/toolDisplayNames.js`（`ToolDisplayNameCoverageTest` 护栏）与插件端 i18n 的 `cmdPassStep`。
- **批量原语的两条落笔纪律，两个家族各有各的理由，不能互相照抄**：Office 面靠宿主的**活 Range**（编辑别处后 Range 仍指向同一段逻辑文本）免疫坐标推移，所以按条目顺序落笔即可，真正的不变式是「**所有定位排完、sync 完，才进入落笔阶段**」（阶段 A-F，`replace_batch` 的注释逐条标了）；WPS 面用的是**裸字符偏移**，左侧写入会推移右侧坐标，所以必须**整批按文档位置从右到左**排序后落笔（`wpsWordHandlers.test.js` 的「整批从右到左落笔」用例已实证：把 sort 方向改反当场转红）。另外 WPS 面**不许偏移路径与 Find 兜底混跑**（dev-board#264 老地雷），所以整批统一决策：只要有一条偏移校验不通过，整批改走 Find。
- **`OfficeBridgeService` 的超时按 command 分级**（`ACTION_TIMEOUT_SECONDS`，`replace_batch`/`apply_standard_format` 120s，其余 30s）。这与 `EditorBridgeService` 是同一条纪律，理由也一样：**平超时是「后端先放弃、模型重发一次造成双改」的成因**。凡是「一次调用做 N 件事」的原语都必须进这张表——忘了加的症状不是报错，而是内容被写两遍，最难查。
- Word 的 body.search 查找串上限 255 字符（后端工具已前置校验）；search/replace 的锚点必须与文档文本精确一致（matchCase）。
- **replace_text / replace_batch / table_set_cell 走词级最小修订**（`lib/minimalEdit.js`，`planMinimalRedline` 是单处与批量共用的纯 JS 规划阶段）：差异段在命中 Range 内二次 search 定位（歧义时对称扩窗消歧），**全部定位在任何写入之前完成**，任一段不唯一即整段回退（返回值 `via` 标 minimalRedline/fullReplace/mixed）；多命中与段内编辑均从右到左应用。
- **颗粒度是词不是字符，且刻意不再与 LOWA 同口径（dev-board#716）**。原先这里与 LOWA 一样按 UTF-16 码元做 LCS，英文改写因此在词中间切开：AI 把 "kicking off" 改成 "commencing"，Word 行内渲染成 "commenc~~kick~~ing ~~off~~"，一句整句改写落成 13 段 / 23 个删插块（真机截图 `office-addin/appsource/screenshots/01-word-mac-tracked-changes.png`，改后同句 1 段 / 2 块）。现在先切词（英文字母数字连写成词、中文逐字、空白与标点各自成词）再在词序列上做 LCS，然后把「中间未变部分少于 3 个词」的相邻改动并成一块（`MERGE_GAP_WORDS`；每次最多吞掉 2 个未改词，被吞原文原样写回 newText）。**两条链分家的理由**：LOWA 按字符偏移走 UNO 光标，段再碎也落得下；插件没有按偏移切 Range 的 API，每段都要拿 oldText 回文档 search 定位，越碎越容易「定位不唯一」而整段回退。合并另有 255 字上限（`MERGE_MAX_SEGMENT_CHARS` = Word 查找串上限），并成一段搜不到的还不如不并。回归 `lib/minimalEdit.test.js`（把 tokenize 换回逐字符即有 7 条转红；正文恒等那条不会红——它守的是另一件事）。
- **改颗粒度不许动最终文本**：`minimalEdits` 的每段逐段替换后必须恒等于新文（`applyEdits` 断言 + 随机脚本 300 轮），修订只改「怎么留痕」，不改「接受全部修订后是什么」。
- **Word 格式面的单位与门槛**：段落 lineSpacing/spaceBefore/spaceAfter/firstLineIndent/leftIndent/rightIndent 一律是**磅**（Word UI 的行距倍数 = 磅值/12，工具描述里已教模型换算）；字符面与段落面属性都是 WordApi 1.1，唯独 `paragraph.styleBuiltIn`（标题级别）属 **WordApi 1.3**——执行器 `builtInStyleSupported()` 前置守卫，且 get_formatting 在旧宿主上**不能 load 也不能读**该属性（未 load 的属性直接抛），别把它写死进 load 串。套 styleBuiltIn 会重置段落直接格式，必须先落样式 sync 再落其余参数。
- **编号与表格整片压在 WordApi 1.3 上**（`wordApi13Supported()` 前置守卫）：`Word.List`/`startNewList`/`attachToList`/`detachFromList`/`paragraph.isListItem` 与 `body.tables`/`table.getBorder`/`table.alignment`/`autoFitWindow` 全在这一档。`isListItem` 与 styleBuiltIn 同款门槛——旧宿主上连 load 都不能带（set_numbering 按支持与否切两套 load 串）。
- **中文数字编号只能手写**：`Word.ListNumbering` 只有 arabic/lowerLetter/lowerRoman/upperLetter/upperRoman/none，**没有中文数字**。set_numbering 的 `kind=chinese` 因此不走 List API，改为把「一、」「二、」写进各段段首（withTracking 下即插入修订），返回值 `via:'literalText'`；bullet/decimal 走真 List API 标 `via:'listApi'`，旧宿主上同样退化为手写（bullet 用「- 」前缀）。想加中文自动编号只能靠 `setLevelNumbering` 的 formatString——那是数字占位符拼串，变不出中文数字，别再试。
- **Office.js 没有「最小值行距」**：`paragraph.lineSpacing` 只有固定磅值（Word JS API 里 Paragraph 与 ParagraphFormat 都查无 lineSpacingRule）。LOWA 的 HOUSE 是「最小值 16 磅」，插件端只能落成固定 16 磅，apply_standard_format 的返回值用 `lineSpacingMode:'exact'` 向模型交底。
- **中西文分设字体属 WordApiDesktop 1.3**：`font.nameAscii`/`nameFarEast` 不是 WordApi 1.1 那一档，只有较新桌面版 Word 有（`farEastFontSupported()` 守卫）。不支持时 apply_standard_format 只能设单一 `font.name`（中文字体统管全篇），返回值 `fontSplit:false` 说明退化。
- **改律所标准格式规范要改三处**：worker `HOUSE`（frontend/src/zetaoffice/public/office_thread.js）+ 后端 `DocxStyleHelper` + 插件端 `HOUSE`（officeExecutor.js）。三处数值必须逐字一致（正文 12 磅、主标题 16 磅、段后 18 磅、行距 16 磅、首行缩进 24 磅、表格 10 磅）。
- 修改类命令必须恢复用户原有的修订开关状态（withTracking 的 finally 恢复），别改成常开。
- **PowerPoint 文本读写要 PowerPointApi 1.4**（TextFrame/TextRange；Microsoft 365 较新版本才有，2019/2021 永久版没有）——执行器 requirePptTextApi 前置报错，别绕开它直接调 API。Excel 查找是客户端扫已用区域（兼容旧宿主），别改成 ExcelApi 1.9 的 findAll。
- **Excel 格式/结构面（批次 6）需求集分层**：单元格格式/边框/行列插删/选中区域都是 ExcelApi 1.1；合并/取消合并、区域排序、列宽/行高（`format.columnWidth`/`format.rowHeight`）是 ExcelApi 1.2；条件格式（`range.conditionalFormats`）是 **ExcelApi 1.6**；自动筛选（`worksheet.autoFilter`）是 **ExcelApi 1.9**；冻结窗格（`worksheet.freezePanes`）是 **ExcelApi 1.7**——后三者执行器各自 `excelApiSupported('1.6'|'1.9'|'1.7')` 前置守卫，不支持时报明确错误而不是死等 30 秒。
- **条件格式每次 apply 是替换不是叠加**：`excel_conditional_format` 在套新规则前先对该区域 `conditionalFormats.clearAll()`，与桌面端 `sheet_conditional_format` 同口径（每次调用替换区域现有规则）——不要改成累加多条规则，否则重复调用会在同一区域堆规则。`ConditionalCellValueOperator` 归一后是纯小写 token（`greaterthan`/`lessthan`/`between`/`equalto`，`normalizeEnum` 统一小写化丢了原始驼峰，JS 侧 `EXCEL_CF_OPERATORS` 映射表按小写键取值）。
- **自动筛选首版故意功能不全**：`excel_set_autofilter` 只做 apply（套上下拉箭头，不预设筛选条件）/clear（清筛选条件保留箭头）/remove（整体移除），不支持按具体条件筛值——这是产品范围决定写进工具描述的，不是能力缺失，改进时先确认是否真的要做 `FilterCriteria` 那一层。
- **Excel 公式文法与桌面端 LOWA 相反**：`office_excel_set_formulas` 走 Office.js 原生文法——参数逗号分隔、跨表引用 `Sheet1!A1`；桌面端 sheet_* 走的是分号/点号文法（LOWA 内部有 `normalizeFormula` 做归一化，插件端没有也不该加，两条桥各自忠于各自宿主的原生语法）。写入后读回 `range.values`，字符串值以 `#` 开头的判为公式错误收进返回值 `formulaErrors`。
- **删除工作表前必须先查总数**：`Worksheet.delete()` 在工作簿只剩一张表时会抛异常（微软官方 worksheets 教程示例也是先 `sheets.items.length === 1` 判断再删除），`excel_manage_sheets` 的 delete 分支在调用 `delete()` 前先 load `worksheets.items` 判空，给出「无法删除：工作簿至少要保留一张工作表」的可读错误，不依赖捕获 Excel 原生异常文案。
- **PPT 能力对齐批次（issue 批次7）两级版本门槛**：`ppt_format_text`/`ppt_add_text_box`/`ppt_add_shape`/`ppt_get_slide_details`/`ppt_delete_shape`（按文字定位分支）都卡在 PowerPointApi 1.4（与既有 get_slides/replace_text 同档）；`ppt_move_slide` 与 `ppt_add_slide` 的挪位置分支卡在 **PowerPointApi 1.8**（`Slide.moveTo`，比其余 PPT 工具门槛更高）——`ppt_add_slide` 在 1.8 缺失时不报错，退化为"追加到末尾、不挪位置"并在返回值 `note` 字段坦白，别把这条也做成硬拒绝。`slides.add()`（1.3）**没有生产可用的插入位置参数**：`AddSlideOptions.index` 官方文档标注 preview-only 不能用，"插到第 N 页"只能靠"先追加到末尾、再用 moveTo 搬过去"两步近似，改这条逻辑要保留这个顺序。`ppt_format_text` 用 `TextRange.getSubstring(start,len)` 精确切子串设字体（不改变文本长度，不需要 Word 那种从右到左的字符级修订顺序）。
- SSE 重连语义：onClose 只在主动 close 触发；改 ChatView 的 streaming 解锁逻辑时记住三条路径（bubble_end/error/cancelled 正常终态、onClose 主动关、重连后 run_state 兜底）。
- **建连有三种来源，run_state 三种读法**（chatSession.js 的 handleRunState）：回灌（restorePending=true，首个 run_state 是权威状态，generating 则锁输入续写、awaitingUser 则只给 notice 不锁）、**预连**（无 restorePending 无 streaming，run_state 必须零副作用——两个分支都不进）、send 兜底（streaming 已置起，只有 everReconnected 后才用 run_state 解锁）。加预连类的新调用点时先确认它落在哪一种。
- **会话失效自愈必须覆盖 chat，判据是「服务端有没有落库消息」而不是本地消息数**（dev-board#715）。归属校验在 `GET /api/agent/connect` 与 `POST /api/agent/chat` 上是同一个 `canUseConversation`，所以两条路必须同一套自愈：403/404 且会话在服务端**没有落库消息**时，丢弃 conversationId → 重新签发 → **自动重发一次**这条消息（`chatSession.canHealConversation` / `renewConversation`，`postChat` 的 Error 带 `status`）。两个易踩点：① 只给 connect 做自愈是不够的——SSE 的重连循环把 403 吞在退避里只显示「正在重连」，而每条 POST /chat 当场 403，症状是「每条消息都失败，只有点新对话才恢复」；② 旧判据 `!messages.value.length` 在**发送路径上永远不成立**（`send()` 在调 preconnect/postChat 之前就把用户气泡推进了 messages），那段自愈只有进面板那一次能走到，等于一半是死代码。重发前必须 `closeConnection()` 并重建连接（连接绑在旧会话 ID 上，`ensureConnection` 会当成「已连好」直接返回），并**重建 activeContext**（新会话在后端没有 `InlineContentCache` 条目，沿用只带哈希的那份等于把正文抽空）。有落库消息的会话 403 是归属问题，换 ID 修不好也不许悄悄丢历史，走 `conversationDenied` 文案。三条文案（`conversationRenewedNotice` / `conversationExpiredRetryFailed` / `conversationDenied`）受 4010 红线约束，不许含「登录/未授权/请先」。
- **等用户的状态不许锁输入**：AWAITING_APPROVAL/AWAITING_INPUT 下后端没有在生成任何东西，插件又没有「继续」按钮——把它们并进「仍在跑」会让输入框永久锁死、用户答不上话（AWAITING_APPROVAL 曾有这个隐患，随 AWAITING_INPUT 一并改掉）。
- **省传只在上一轮 bubble_end 之后启用**：出过 error 的会话（含旧后端不认 inlineContentHash 的情况）整场退回恒传全文；`crypto.subtle` 取不到（非 secure context）时哈希为空串，同样恒传全文。改 docCache 的提交/失效时机要同时想「旧后端把只带哈希的请求当无正文」这条降级路径。
- 世纪互联 CDN 替换只发生在 build-manifest.mjs 的输出目录——别把源 taskpane.html 的 office.js 地址改掉。
- **表格中间位置插列唯独要 WordApiDesktop 1.3**（Word 网页版没有）：`office_table_add_col` 的 colIndex 传 0（最前）或 -1（最后）在任何 Word 版本都能跑（走 `Table.addColumns(Start|End)`，普通 WordApi 1.3），传中间位置才需要桌面版专属的 `TableColumnCollection.add(beforeColumn)`（`wordApiDesktop13Supported()` 门槛）；行插入没有这道坎（`TableRow.insertRows` 全档 WordApi 1.3 都有）。不对称，改这块代码前先确认动的是行还是列。
- **表格删行删列不产生修订**：`Table.deleteRows/deleteColumns` 走 API 直接删，RecordChanges 开着也没用（与桌面端 doc_table_delete_row/col 同款限制，PR 说明已写进工具描述）；插入行列走 withTracking 是能留痕的，删除不能，别以为对称。
- `office_get_comments` 的定位符是 `index`（数组序号，同一轮请求内稳定）与 `id`（`Word.Comment.id`，跨请求稳定）；`reply_comment`/`resolve_comment` 两者都收，id 优先——先用 index 简单场景够用，id 是给多轮对话跨消息引用同一条批注用的。
- **批次 9（插件侧能力矩阵补齐清单全 15 项，issue 见 `docs/superpowers/specs/2026-08-07-document-capability-matrix.md` 4.1 节）**：`office_insert_image` 给 `OfficeEditTools` 新增了 `ProjectFileRepository`/`StorageServiceFactory` 两个构造器依赖（读项目文件转 base64），改这个工具或再加依赖要同步测试的 `new OfficeEditTools(...)` 调用点。`manage_content_control` 的 insert 分支包裹粒度是**锚点所在整段**（`Paragraph.insertContentControl()`），不是仅锚点文本——`Range.insertContentControl()` 在 Microsoft Learn 检索中未查到确证，改用官方示例明确支持的段落级 API，工具描述已向模型说明这条限制。`get_revisions`/`accept_revision`/`reject_revision` 的 `revisionIndex` 每次都要重新读（Word 修订顺序随接受/拒绝变化，不能缓存旧序号跨轮复用）。Excel 批注定位统一走 `Range` 对象传给 `getItemByCell`（不拼 `"Sheet!A1"` 字符串），三个改批注状态的 command（reply/resolve/delete）保持这一致路径。PPT 表格三件套实测门槛是 PowerPointApi **1.8**（`rowCount`/`columnCount`/`values`/`getCellOrNullObject` 本身在 1.8 已够用），矩阵调研时按 `TableRowCollection` 猜测的 1.9 偏保守——只有真要枚举行列集合对象才需要 1.9，本批次未用到；别把这几个工具的版本门槛错设过高。

### 2026-08-30 三端双边根因修复（dev-board#285/#286/#287/#288）

孙川真机反馈的三个症状（「PPT 文件不在可编辑列表中」/「未找到锚点文本」/空白气泡标「已完成 · 111 秒」），
经北京云后端**生产日志实证**是同一个根因，不是三个 bug：

- **两个任务窗格共用一个 conversationId 在后端抢同一条 SSE 通道，互相顶掉**。
  实证：2026-08-29 21:24:30–21:33:2x 约 9 分钟，`SSE connection established` 稳定 58 次/分钟；
  nginx access log 每条 `GET /api/agent/connect/... 200 95`（只发出 `connected` 就断）。
  同一会话的 officeHost 在 21:25:25 `WORD -> POWERPOINT`、21:33:11 `POWERPOINT -> WORD`。
  成因：`awd_addin_conv_{projectId}` 不带宿主，而三宿主任务窗格同一个页面地址、同一个源，
  localStorage 共享——与 2026-08-28 已修的 PluginStorage 窗格 id 共用键是**同一类地雷**，当时漏了会话 id。

**新增契约**
- **会话 ID 存储键按宿主分作用域**：`awd_addin_conv_{host}_{projectId}`（`settings.loadConversationId/saveConversationId`
  收第三个参数 hostTag，`chatSession.hostScope()` 提供）。
  **hostScope() 取不到宿主时用 'unknown'，绝不能回落 'word'**——回落就是把三个宿主并回一个会话。
- **再按文档分一层**（2026-09-20，dev-board#717）：`awd_addin_conv_{host}_{projectId}_{docKey}`，
  第四个参数由 `chatSession.docScope()` 给（= `hostBridge.documentIdentity().key`，有路径用路径、否则
  `宿主:文档名#本窗格实例`；缓存一份，换文档时整体换，读写必须是同一个键）。
  **为什么非分不可**：只分到宿主时，同一个项目里同时开着的两份 Word 拿到同一个 conversationId，
  而跨文档读写是按 conversationId 往 SSE 推命令的——两个窗格在通道上分不开，抢到 emitter 的那个
  会替另一个执行 `read_for_reference`，把自己的正文当成对方文档的内容交给模型，沿途无人报错。
  后端 `OpenDocSource.requireAddressable` 现在会拒绝这种目标（既不列也不下发），根子在这个键。
  docKey 取不到（普通浏览器调试）时退回宿主级键。
- **对话按文档绑定**（2026-09-21，dev-board#767）。三条规矩，缺一条就是维护者报的那个病
  （Windows Word 里新建空白 Document1，一开窗格就挂着上一篇新闻摘要的对话）：
  1. **旧键一律不认领**。`settings.loadConversationId` 只认这份文档自己那个键，此前两级
     「首次以文档键打开时继承按项目+宿主／只按项目分的旧键」的升级迁移已**整个删掉**——
     那正是把别的文档的对话按在本文档上的那一步。旧会话一条没丢，在历史面板里可手动翻回去。
  2. **未保存的文档不落盘**。`hostBridge.documentIdentity()` 回 `{key, saved}` 三态，
     `chatSession.docPersist()` 据此决定存不存：`saved:false`（新建、没存过盘）一律不存，
     于是每次打开都是新对话。**key 为空（判不出宿主，普通浏览器调试）时仍然落盘**，
     退回宿主级键与改造前一致——别把这一档并进「不落盘」，那会让调试态与产品态行为分叉。
     注意这与 revisionLog 的绑定是两回事：修订记录用同一个 key，但它只要求本次窗格会话内稳定。
  3. **文档是会话身份的一部分**。`activateSession` 的 sessionKey 里带 docScope
     （`sessionIdentityKey`），所以「换文档」走的就是「换项目」那条久经考验的路
     （关连接、清消息、按新键恢复或新签发、预连、#715 的 403 自愈）。触发器是
     `chatSession.syncActiveDocument()`，App.vue 在**窗格重新拿到焦点**与**每轮心跳**时各调一次
     （WPS 的停靠窗格切文档时未必收得到 focus，只挂 focus 会让状态永远停在上一份文档）；
     文档没变是空操作。**流式进行中不切**（`refreshDocScope` 守住缓存）：SSE 通道绑在当前会话上，
     半途换键等于把正在跑的轮次拆掉。**取不到身份（key 为空）时当成没变**，绝不拿空值顶掉活会话。
  另有一条例外：**未保存的文档首次存盘不算换文档**（`!before.saved && after.saved`），
  会话原地改挂到新键上、`sessionKey` 跟着更新，不重建——否则用户按一次 Ctrl+S，
  正在进行的对话就被清屏重拉。存过盘的 A 另存为 B 不走这条，按维护者定的规矩当新文档处理。
- **任务窗格实例身份**：`chatSession.paneId`（每次窗格载入生成、不持久化）经 `X-Client-Instance`
  请求头上送。后端 `SseEmitterService.createConnection(id, clientId, lastEventId)` 认出「换了窗格」时
  先给旧连接发 `superseded` 事件再关，客户端收到即**停止重连**并提示——把无限互顶变成一次性移交。
- **SSE 断点续传**（SSE 规范内建那一套）：后端每个事件带自增 `id:`，每会话环形缓冲
  （`REPLAY_MAX_EVENTS=512` / `REPLAY_MAX_BYTES=128KB` 双上限，`REPLAY_PURGE_THRESHOLD=200` 按规模清理），
  connect 读 `Last-Event-ID` 补发。**心跳与 connected/superseded 不入缓冲**。
  客户端 `sse.js` 记游标、重连时带上；旧后端不发 id 时游标恒空，行为与改造前一致。
- **`state_recovery` 必须消费**：后端对仍在 RUNNING 的会话推本轮全量快照
  （`AgentOrchestrator.activeStreamContent`，按用户轮次初始化、跨步骤累加）。桌面端一直在用，
  插件端此前整个忽略——这是「空白气泡标已完成」的直接成因。快照是**全量不是增量**，
  必须先清空气泡与解析器状态再整块喂。
- **锚点定位统一走 `taskpane/lib/textMatch.js`**：逐字符归一（NFKC 全角半角、弯直引号、NBSP/表意空格/
  零宽字符/软连字符、各式连字符、连续空白折叠、WPS 的 `\x07`、大小写）+ **显式偏移映射**，
  命中一律换算回**原文坐标**。三条纪律：① 归一只用于「找」，落笔用原文区间；
  ② Office 面取 Range 仍交给宿主 `body.search`（拿命中处的**原文**再搜一次），**绝不自造坐标**；
  ③ WPS 面命中后用 `hit.text`（文档原文）当 needle，保住 `verifiedRange` 的逐笔校验不变式。
  失败报错走 `describeAnchorFailure`：给出文档里最接近的原文片段 + 相似度 + 下一步。

**新增地雷**
- **退避复位不能放在「建连成功」那一刻**：真实故障形态是「每次都连得上、连上就被立刻断开」，
  那样写指数退避永远不生效（实测被打成 1 Hz）。改为连接活满 `STABLE_CONNECTION_MS=5s` 才复位，
  另有 60s/8 次熔断（`onStatus('unstable')`）。**写这条的回归用例观察窗口必须跨过第 4 次重连**
  ——只看 3.4 秒的话，还原病灶后前三次时刻与修好后一模一样，用例会假绿（第一版就是这么错的）。
- **后端每轮结束会主动关流**，客户端把它当意外断线：旧代码每一轮正常收尾都闪一次
  「连接中断，正在自动重连……」。现在有 3 秒宽限（`RECONNECT_NOTICE_GRACE_MS`）。
  **看到这条横幅不等于真的断过**——判断真断线要看服务端建连速率，不是看横幅。
  同理 `everReconnected` 只在**轮次中途**断线时置起，否则 run_state 兜底从第一轮结束起就被永久武装。
- **`bubble_end` 的 status 有五种**：finished / paused(max_depth|max_tokens) / awaiting_approval /
  awaiting_input / 空信封。**只有 finished 与空信封算「写完了」**；`PAUSED` 在 run_state 里
  也**不算「还在生成」**——插件没有「继续」按钮，并进 generating 会把输入框永久锁死。
- **零正文终态必须兜底**：`bubble_end` 时若无可见正文，先去 `/api/ai/history` 补取本轮助手消息，
  补不回来也要给一句人话（`msg.notice`，走 `.msg-notice` 次要色，不是 `msg.error` 的红字）。
- **标签流解析器的 salvage**：模型不输出 `<final>`、把正文写在 `process/step/walkthrough` 里时，
  整轮会被逐字丢弃。`flush()` 里**仅当本气泡一个字都没进正文**才把这些散文捞回来（工具载荷除外）。
- **`sendTextDelta` 的信封必须用 Jackson 序列化**：手写 replace 漏制表符与控制字符，
  模型从表格里带出一个 Tab 就让整条 text_delta 变非法 JSON，客户端 parse 失败后按原文渲染，
  用户看到 `{"content":"…` 这一串信封本身。
- **office_command 超时不等于没做**：命令已下发、宿主端很可能已经落笔。
  `OfficeBridgeService` 的超时文案现在明确禁止直接重试写入、要求先读取核对——
  旧文案只说「超时」，模型原样重试就把同一段内容写进文档两遍。
- **WPS 宿主判定用 `Application` 的标志性集合**（Presentations/Workbooks/Documents），
  与 `wps/js/ribbon.js` 的 `AwdHostTag()` 同源、也是官方 wpsjs 2.2.3 模板任务窗格的用法。
  `wps.WpsApplication()/EtApplication()/WppApplication()` **不是官方模板里的宿主判据**
  （三套脚手架各服务一个宿主，从不需要判），降为**自校验兜底**：返回的对象必须带该宿主的标志性集合才认。
- **`Range.Width` 在多列区间上是总宽不是单列宽**（WPS 表格面）：拿它解单列内边距会算出负数，
  被夹成 0 —— `ColumnWidth = 0` 等于**把这几列藏起来**，返回值还报成功。回读只许量第一列，且有永不写 0 的合理性闸。
- **演示面写入侧要走递归形状遍历**（`wpsWppHandlers.textBearingShapes`）：表格单元格与组合子形状里的文字，
  读取侧 `wpsDoc.collectShapeText` 早就按三条路收，写入侧此前只看顶层 TextFrame——
  模型在上下文里读得到那些字，一改就报「未找到」。Office/ppt 面同款问题**尚未修**（见 dev-board#288）。
- **PPT 内联正文里的「第N页：」与「 | 」是插件加的装饰**，已在正文开头加一行交底；
  改这两个读取器时装饰与说明要一起改（`wpsDoc.PPT_INLINE_NOTE` 与 `wordDoc.PPT_INLINE_NOTE` 同源两份）。

### 2026-08-30 下午：审计剩余项（dev-board#288，10 条确认项修掉 9 条）

- **Excel 读取路径的截断必须发生在过桥之前**（`wordDoc.readExcelSheet` 与
  `officeExecutor.excel_get_range`）：先只 load 尺寸，再用 `getRangeByIndexes`
  （ExcelApi 1.1，无门槛）取截断后的区间。此前是把整片已用区域的 values 编组过桥再切前
  2000 / 500 行，几万行的台账能让任务窗格无响应几十秒。WPS 面早就是「先 Resize 再取 Value2」。
- **Office/PPT `loadPptTextFrames` 支持组合形状递归**：`Shape.group` / `ShapeGroup.shapes`
  是 **PowerPointApi 1.8**（微软官方文档核实；`ShapeType.group` 本身是 1.4）。
  **1.8 不可用时静默退化成只收顶层、绝不报错**。表格文字不并进来——Office 面有
  `ppt_table_read` / `ppt_table_set_cell` 专门通道（WPS 面没有，所以它把表格并进了遍历）。
- **`ppt_replace_text` 只改命中段**：整框回写 `textRange.text` 会抹平框内所有分段字符格式
  与超链接还报成功。改逐处 `getSubstring` 并**从右到左应用**（偏移按原文算，右边先改
  不推移左边）。`TextRange.text` 可写与 `getSubstring` 都是 PowerPointApi 1.4，无需新守卫。
- **`edit_header_footer` 没给 text 就不许动文字**（两个家族同修）：旧写法无条件整替、
  text 兜底成空串，模型只想改对齐就把用户页眉清空。显式传空串仍是「清空」这个合法意图，
  两者分开；都不给时报错。返回值加 `textUpdated`。
- **`excel_add_pivot_table`**：目标地址支持跨表（`splitSheetQualifiedAddress`，
  含 `'带空格 表'!A1` 的引号包裹）；字段名改成「建表 → 读层级名 → 全对得上才继续，
  对不上就把刚建的表删掉再报错并列出可用字段」，不留空透视表。
- **`excel_protect_sheet` 的密码是 ExcelApi 1.7 那一档**：旧宿主上参数被直接忽略，
  表被保护但**没有密码**还报成功。安全动作不许静默降级——不支持时明确报错，
  并回读 `protection.protected` 报真实状态。
- `office_ppt_add_slide` 的工具描述与实现对齐（实现是「新页成为第 N 页」，描述写的是
  「插到第 N 页之后」，差一位；两个执行器一致，改的是描述）。

**唯一没修的一条，以及为什么**：`standard-format-revision-flood`（Office 面
`apply_standard_format` 逐段落笔，WPS 面已按 run 合并）。不修的理由不是没时间——
**这条在 Office 面缺一个真机判据**：WPS 那次合并是维护者按真机观察到的「每段一条修订」
裁决的，而 Word 是否会把相邻的同款格式修订自动合并，本机无法验证。
而且 Office.js 里 `Range` 只能整段设 `font`，`lineSpacing`/`spaceAfter`/`firstLineIndent`
仍须逐段设——照搬 WPS 的 `doc.Range(首段.Start, 末段.End)` 一次落笔在 Office 面没有对应写法，
`expandTo` 拼出来的区间又缺少 WPS 那条「`merged.Paragraphs.Count === run 段数`」安全阀。
在整篇文档的格式路径上照着未经验证的假设改，风险是「半篇文档格式被刷坏」，
比多几十条格式修订严重得多。要做先补真机观察 + 同款安全阀。

**未验证的中低危 115 条**（从未做过对抗式复核）清单在 dev-board#288 的评论里。

## 验证

- `cd office-addin && npm install && npm run build`；manifest 校验（dev 与 dist-deploy 两份）见上。
- 整篇过卷（dev-board#422）：后端 `mvn -f backend/pom.xml test -Dtest='OfficePass*Test,OfficeEditToolsTest,AgentOrchestratorPassDepthTest,ContextAssemblerServiceTest'`；插件端 `node --test office-addin/taskpane/lib/passProgress.test.js`。
- 批量改写（过桥量与安全不变式）：`node --test office-addin/taskpane/lib/officeReplaceBatch.test.js`（Office 面，带 Word.js mock，会打印逐处 vs 批量的 sync 次数对照）与 `node --test office-addin/taskpane/lib/wpsWordHandlers.test.js`（WPS 文字面）。
- 标签流解析：`node --test office-addin/taskpane/lib/sse.test.js`（Node 自带 test runner，无需 npm install）。
- 对话按文档绑定（dev-board#767）：`node --test office-addin/taskpane/lib/docIdentity.test.js office-addin/taskpane/lib/chatSessionDocScope.test.js office-addin/taskpane/lib/settings.test.js`（**需先 `npm ci`**，chatSessionDocScope import 了 chatSession→vue）。三条病灶各有对应用例，改这条链前先把它们各还原一次：旧键继承加回 `loadConversationId` → 「存过盘但没开过插件的文档」转红；`docPersist()` 恒 true → 「新建未保存的文档」两条转红；`sessionIdentityKey` 去掉 docScope → 「窗格里换了文档」「另存为新名」转红。
- 界面语言链（dev-board#713）：插件端 `node --test office-addin/taskpane/lib/appLanguage.test.js`（**需先 `npm ci`**，它 import 了 chatSession→vue）；后端 `mvn -Dtest='AppLanguageScopeTest,AppLanguageRequestFilterTest,AddinRequestLanguageTest' test`。
- `npm run build:deploy -- --url https://addin.example.com --china` 后检查 dist-deploy/manifest.xml 无 localhost URL、taskpane.html 用 partner.office365.cn CDN。
- **双主站各要一份自己的 Office 插件产物与安装器**（2026-08-29 发版后核对补的，此前两站都发国内那份）：托管地址被焙进包内 manifest，装哪个包就决定了任务窗格从哪个站加载、用哪个 office.js CDN——国际用户装国内包会整条链路绕回北京（实测 office.js TTFB 相差约 38 倍）。品牌站地址（SupportUrl / GetStarted.LearnMoreUrl，出现在 Office 加载项信息面板里）由 `build-manifest.mjs` 的 `BRAND_SITE_BY_ADDIN_HOST` 按托管 host 派生，私有部署用 `--brand-url`。安装器两份文件名相同，**必须用 `--dist` 分开输出目录**否则静默覆盖：`node installer/build-installers.mjs --url https://addin.workdeck.ai/office-addin --dist office-addin/installer/dist-intl`。官网侧对应 `siteConfig.addinOrigin` 与 `offersWpsAddin`（国际站不提供 WPS）。
- sideload 手测清单与步骤全在 `office-addin/README.md`（Word 工具桥场景 + Excel/PPT 场景 + 断线重连/awdk 连接场景）。
- 后端单测（JDK 21）：`mvn test -Dtest='ContextAssemblerServiceTest,InlineContentCacheTest,OfficeBridgeServiceTest,OfficeResultControllerTest,ToolRegistryCapabilityFilterTest,OfficeEditToolsTest'`；连接/登录链路是 `mvn test -Dtest='AwdkLoginServiceTest,AuthControllerHardeningTest,AccountServiceTest'`。
- **设置页的连接链路要走完整条 UI**（原语级测试盖不住 Vue 的接线，见 feedback「验证要走完 UI 链路」）：`npm run build` 后用 `python3 -m http.server` 静态托管 `dist/`（`base:'./'` 决定了它在任何路径下都能开），另起一个桩后端实现 `/api/auth/account-login{,/send-code}`、`/api/auth/awdk-login`、`/api/projects/my` 并带 CORS 头，在浏览器里逐条跑「发验证码（含滑块）→ 错码 → 正确码 → 邮箱口令 → 粘 Key」（桩后端要实现 `/api/auth/account-login/captcha-config`——回真实的 `{provider:'aliyun',sceneId,prefix}` 才能验到滑块真的弹出来，回 `{provider:null}` 只能验到降级分支），判据是 `localStorage.awd_addin_token` 与视图是否切走。`main.js` 在没有 Office 全局时直接挂载，所以不需要真宿主。

## WPS 加载项（2026-08-28，dev-board#244）

同一套任务窗格 Vue 源码构建出的第二个宿主家族：WPS 文字/表格/演示（Windows/Linux 版 WPS；**Mac 版 WPS 不支持加载项**）。后端零改动——officeHost 仍是 word/excel/powerpoint 三值、clientCapability 仍是 'office'、office_command 契约与回传路径完全一致。

### 关键文件

- `taskpane/lib/hostBridge.js` — **Vue 层唯一宿主入口**：按运行环境分发到 Office 面（wordDoc/officeExecutor）或 WPS 面（wpsDoc/wpsExecutor）。chatSession/ChatView 只许 import 这里，不许直连两个家族的实现文件。detectHost/readDocumentMeta 走「officeDetectHost 优先 + 全局对象兜底」的判定顺序（office.js 半初始化窗口期 + 既有测试钉着，别改成 hostFamily 一刀切）。
- `taskpane/lib/wpsDoc.js` — WPS 三宿主文档读取（契约同 wordDoc.js）。表格读取必须 Value2 批量（跨进程桥约 0.2ms/调用，逐格会拖死任务窗格）。
- `taskpane/lib/wpsExecutor.js` + `wpsWordHandlers.js` / `wpsEtHandlers.js` / `wpsWppHandlers.js` — office_command 的 WPS 执行器（分发器 + 三张宿主 HANDLERS 表）。**命令名与参数/返回值契约以 officeExecutor.js 为准绳**；宿主守卫按前缀（excel_*/ppt_*/其余归 word）。
- `taskpane-wps.html` — WPS 构建入口（不含 office.js；window.wps 由宿主注入）。与 taskpane.html 同出一个 dist（vite 双入口），build.target 压 es2018 给参差的 CEF 内核留余量。
- `wps/` — ribbon 薄壳（vanilla JS，不进 Vite）：manifest.xml + ribbon.xml（都三宿主共用）+ index.html/main.js/js/*（在线模式 WPS 启动拉 url/index.html）+ `vendor/publish-template.html`（官方 wpsjs@2.2.3 publish.html 原样 vendor，构建脚本只做 PUBLISH_REPLACE_STRING/SERVERID_REPLEASE_STRING 两处替换 + 标题品牌化 + 追加 BRAND_STYLE 覆盖样式（对齐官网 DESIGN.md，选择器钉在模板既有类名上，dev-board#246），**机制代码不许手改**——「验证中/正常/无效」状态色是机制 JS 写 inline 的，样式层刻意不碰）。
- `scripts/build-wps.mjs` — `npm run build:wps` 出 dist-wps/（wps/ 壳含 manifest.xml + wps/ui/=dist 拷贝 + install.html + jsplugins.xml）。默认部署地址 https://addin.aiworkdeck.com/wps-addin。

### 分发契约

- **在线模式**：加载项 url = `<baseUrl>/wps/`（必须以 / 结尾）。WPS **每次启动宿主都成对拉 `url/manifest.xml` 与 `url/ribbon.xml`**，两者都得裸可达（别过 SPA 回退/鉴权）；`index.html` 要等用户点按钮才拉。`manifest.xml` 照 wpsjs 脚手架模板（`<JsPlugin><ApiVersion>/<Name>/<Description>`，`<Name>` 与 `ADDON_NAME` 同源），三宿主共用一份——2026-08-29 之前构建脚本从没产出过它，线上一直 404。发版 = 覆盖静态目录，用户无需重装。壳文件（manifest.xml/ribbon.xml/index.html/main.js/js/*）必须 no-cache，ui/assets/* 带 hash 长缓存。
- **个人版安装唯一通路**：install.html 经本机 WPS 常驻服务 127.0.0.1:58890 `/deployaddons/runParams` 写用户 `%APPDATA%\kingsoft\wps\jsaddons\publish.xml`（个人版 12.1.0.16910 起 oem.ini/jsplugins.xml 被禁）。企业版私有部署仍走 jsplugins.xml + oem.ini `JSPluginsServer`。
- 三宿主 = publish.xml 里三条记录（type=wps/et/wpp，同一 url、同名 aiworkdeck）。
- **每个宿主首次加载各弹一次「是否信任」，而且点完还得重启那个宿主**（2026-08-29 三宿主实测）：安装页一次写三条记录，但授信是**按宿主**发生的；不点「允许」那个宿主不出「AI WorkDeck」选项卡，**点了「允许」当次会话也照样不出**——加载项要到该宿主下次启动才真正加载。用户会以为「表格/演示的插件没装上」或者「点了允许也没用」。给用户的话术必须带上重启这一步。
- **任务窗格 id 的 PluginStorage 键按宿主分**（`wps/js/ribbon.js` 的 `AwdPaneKey()` 与 `wpsDoc.taskPaneKey()` 后缀同源 wps/et/wpp，改一边就得改另一边）。理由：三宿主共用一份 PluginStorage，而窗格 id 是各宿主进程内从 1 开始自增的——共用一个键的话，文字里存下的 `id=1` 会被表格拿去 `GetTaskPane(1)`，那是表格自己的 1 号窗格（很可能是别家加载项的），于是点我们的按钮开/关了别人的窗格。ribbon 侧判宿主用 `Application.Presentations/Workbooks/Documents`（三者恰好各有一个非空），**不能用 `Application.Name`**——见下面「Name 判不了是不是 WPS」那条。
- **「选项卡不见了 / 图标空白 / 点了没反应」= 该宿主的授信没落地，不是代码问题**（dev-board#270，2026-08-29 三宿主真机定案）。
  - **机器可读的判据在 `%APPDATA%\kingsoft\wps\jsaddons\authaddin.json`**：每个宿主一条 `{enable, isload}`。**`isload:false` 就是这个病**——实测演示宿主 `enable:true, isload:false` 时，每次启动都重弹授信框、选项卡始终不出现；三个宿主都 `isload:true` 时一切正常。`publish.xml` 只说注册了什么，说明不了加载与否，别拿它当判据。
  - 服务器访问日志的对应形态：坏的时候 WPS 每次启动宿主仍会成对拉到 `manifest.xml` + `ribbon.xml`（UA 为空、HTTP/1.1、200，**所以网络是通的**），但**不拉 `index.html`/`main.js`/`js/*`**——JS 入口没加载，三个回调一个都没执行。
  - **恢复办法（就是给用户的话术）**：到安装页把该宿主那条**先「卸载」再「安装」，然后重启这个宿主**。两步缺一不可——单独重启不管用，装完不重启也不管用（**授信框弹出的那一次会话不会加载加载项**，实测三个宿主都这样：点完允许当次仍无选项卡，重启才有）。
  - 已逐个排除的假根因，别再走一遍：`publish.xml` 的 `enable="enable_dev"`（表格那条至今仍是 `enable_dev` 且工作正常，WPS 自己写的就是这个值；反倒是被手工改成 `enable` 的演示那条一直 `isload:false`）、`jsaddinblockhost.ini`、PluginStorage 共用键、COM 启动方式、整机重启、虚拟机网络不通。

### 已知地雷（WPS 面）

- **不许依赖 wps.Enum**：枚举表在旧宿主上不存在（官方模板都是手工 WPS_Enum 兜底）。三张 HANDLERS 表一律用本地数值常量（VBA 同值）。
- JSAPI 三折算规则（表格面最易踩）：集合 `.Item()` 函数调用；带参属性按函数调（`Address(false,false)`/`Cells.Item(r,c)`）；**赋值走 Value2**（Value 在 JSAPI 是只读方法）。
- 颜色是 BGR 打包数值（低字节红），与 #RRGGBB 互转必须过转换函数；表格列宽单位是字符宽不是磅（1 字符≈5.69 磅）。
- WPS 批注/修订只有 1-based 序号无 GUID；表格批注是老式单条（reply 降级文本追加、resolve 不支持）；文字 `insert_image` 本版不支持（无 base64 直插路）。
- **`Selection.Text` 赋值后选区扩展覆盖新文本**（VBA/WPS 语义，与 Word 面 `getSelection().insertText` 光标落在插入之后相反）：无锚点 insert_text 连续两次时第二次会把第一次整段替换（修订态下第一段变红色删除线，2026-08-29 真机实锤）。修法是赋值后立刻 `sel.Collapse(0)`（wdCollapseEnd）折叠到插入内容末尾，让连续插入成为追加（wpsWordHandlers.js，单测钉住）。
- 文字面比 Office.js 强的三处（别照抄 Office 版降级）：行距最小值原生支持（wdLineSpaceAtLeast）、NameFarEast/NameAscii 无门槛、中文编号 wdListNumberStyleSimpChinNum* 原生支持；PPT AddSlide(Index, CustomLayout) 原生带插入位置。
- `sse.js` 双通道：流式 fetch 探测不过（或 resp.body 缺失）整条降级 XHR onprogress。**XHR abort 是同步收尾**——close() 里必须先记 wasReading 再 abort，否则 onClose 双触发（已修，sse.test.js 钉住）。
- **Find 兜底路的「匹配宽松度」必须逐项显式钉死**（`pinFindStrictness`，dev-board#264）：`ClearFormatting()` 只清格式，而 `IgnorePunct` / `IgnoreSpace` / `MatchFuzzy` / `MatchByte` 是 Find 对象上的持久属性（对应查找对话框里的「忽略标点符号」「忽略空格」等复选框），**既不在 `Execute` 的 15 参签名里，也会从用户上一次手动查找继承下来**。不钉死的话同一份文档在两台机器上命中范围可能不同——而兜底路的命中会被直接拿去替换，是最难复现的一类错。注意 `MatchByte` 极性与其余三个相反：**true 才是「区分全角/半角」**（Word VBA 第一方文档）。各属性各自 try/catch（WPS 未必全部暴露），另外先试 `ClearAllFuzzyOptions()`。
- **进 Find 兜底前有四条硬拦截**（`assertFindFallbackUsable`，dev-board#264）——Find 与 indexOf 偏移路径的能力边界不同，落差全在这里，越界一律报可执行的错而不是让 Find 去猜：① `searchText` > 255 字（查找引擎硬上限，而 indexOf 路径无上限）；② `searchText` 含 `^`（查找语法的转义前导符，关掉通配符也仍生效）；③ `replaceText` 跨段（替换框只认 `^p`，塞裸 `\r` 要么抛异常要么写进字面控制字符）；④ **多处替换且 `replaceText` 里含 `searchText`**——兜底是「循环替换最靠前一处」，产物里再含 needle 就会反复替换自己刚写出来的文本（「甲方」→「甲方（以下简称甲方）」这类律师常做的改写正是），结果是第一处堆出嵌套垃圾、其余各处一个没动，返回值还报全部成功。
- **写入类命令一律「全部命中先校验完再落笔」**：边校验边写的话，第 k 处校验失败时前 k-1 处已经改了，用户拿到的是「半篇改了格式 + 一条报错」，还留着一片修订。`replace_text` 的全有全无门、`set_paragraph_format` / `apply_style` 的预取、`format_text`（dev-board#264 补齐）现在是同一口径；新增写入类命令照此办理。
- **长循环先关宿主重绘**（`withoutScreenUpdating`）：同步桥下每次 Font/Format 写入都会触发 WPS 重绘。`apply_standard_format` 已包在里面；finally 里恢复原值，读不出原值时恢复成 true——**绝不能把宿主留在停止重绘的状态**。同理，逐段循环里不许重复取 `p.Range` / `doc.Paragraphs`（每个属性访问都是一次跨进程往返）。
- **`apply_standard_format` 按「连续同类段落 run」合并落笔**（dev-board#265）：分类仍逐段做（纯 JS 状态机，判定粒度不变），只把写入合并成 `doc.Range(首段.Start, 末段.End)` 一次。**这条不只是性能**——维护者按「法律场景用户方便性」裁决：格式修订从「每段一条」变「每个 run 一条」，一份数百段的文书从几百条降到几十条，律师真正要看的实质性红线不再被格式噪音埋掉（格式修订本来就没人逐条接受/拒绝；某一段不该被标准化，直接改那一段比在几百条修订里翻出它容易）。**四条断 run 规则一条都不能少**：空段断（合并会把段后 18 磅与最小值行距刷到空行上，是肉眼可见的版面变化）、kind 变断、段落文本含 `\x07`（表格单元格）断且单独落笔、`limit` 处断。**安全阀**：合并区间落笔前校验 `merged.Paragraphs.Count === run 段数`，对不上就退回逐段落笔并在返回值报 `degradedRuns`——偏移口径真机未验，这一次校验把「静默把格式刷到别的段落上」变成「诚实降级、结果不变」。run 长度为 1 时走单段路径，**最坏情况不劣于合并前**。回归口径：`wpsWordHandlers.test.js` 的 `effectiveFormat()` 把区间写入折算成「每段最终拿到的格式」，同一组断言在合并前后都必须成立（已实测：特征化用例在新旧两版实现下都绿）。
- **锚点定位现在是「坐标换算 → Find 定位」两级，两级都逐笔校验**（dev-board#264，`makeDocCoords` / `rangeAtJsOffset` / `findRange` / `verifiedRange`）：先按上面那条换算式把 JS 下标折成文档位置直切（表格文档因此恢复正常，且零额外跨桥调用），取到的文本与锚点对不上再退 Find 定位。**两条路的通过条件都是「取到的文本与锚点逐字相同」，所以兜底不是猜**——对不上就当没找到、报可恢复的错让模型换锚点，绝不落笔。这条也修正了上一轮基于「兜底＝猜」做出的裁决：有了逐笔校验，`insert_text`/`set_hyperlink` 这些「猜错就静默改坏文档」的命令也可以安全地用兜底。`applyToAll` 逐处取第 N 个命中（`findRange` 的 skip 参数，每次命中后 `Collapse(0)` 再找下一处）——**绝不能照 `findReplaceOnce` 那样每次新建 Range 重搜**，那样三处会全命中最靠前那一处，而格式写入是幂等的、不报错、返回值照样是 `formatted:3`，属于静默撒谎。
- **表格面实测定案（2026-08-29，WPS 12.1.0.28043，报告在 `office-addin/scripts/measurements/`）**：
  - **公式错误值经 `Value2` 回来的是 CVErr 数值码**（`#DIV/0!` 是 -2146826281 即 0x800A07D7、低位 2007；`#NAME?` 是 2029），**不是** Office.js 那种 `'#DIV/0!'` 字符串——照 Office 口径扫「`#` 开头的字符串」在 WPS 上永远判不出错误，公式写崩了也报成功。改为问宿主要错误单元格：`SpecialCells(xlCellTypeFormulas=-4123, xlErrors=16)`（与绑定形态无关），显示文本取 `Range.Text`（实测给的正是 `#DIV/0!`）。**单格区间不许用 SpecialCells**——VBA 语义下它会改为搜索整张表（实测确认），会把别处的旧错误算到本次写入头上。无错误时 SpecialCells 抛异常是正常路径。
  - **列宽与磅是仿射关系不是正比**：字符宽 5→31.5 磅、10→59.6、20→115.85、40→228.35，解得 `磅 ≈ 5.625 × 字符宽 + 3.35`（截距是单元格内边距）。斜率随标准字体会变，`setColumnWidthPoints` 落笔后读回 `Width` 就地解出截距再修正一次。
  - 已实测可用、不必再怀疑：`Value2` 收二维数组写入、`Formula` 收二维数组写入、空表 `UsedRange` 返回 A1 单格且 `Value2` 为 null、`Range.Sort(Key1=Range)`、`Names.Add`、`FormatConditions.Add/AddColorScale/Delete`、`Validation.Add/Delete`、老式单条批注（`AddComment`/`Comment.Text()`）、`Shapes.AddChart2`、冻结窗格/分组/保护、**透视表全链路**（`PivotCaches().Create` → `CreatePivotTable`，此前列为最高风险的类推链）、`AutoFilterMode=false` 后 `Range.AutoFilter()`。
- **`Range.Sort` 的 Header/Order/Orientation/SortMethod 是会被保存复用的持久设置**（MS 官方明文），必须显式传满到第 12 位。留空就继承用户上一次手动排序的选择——律师手动做过一次「按行排序（从左到右）」之后，AI 的每次排序都会把整张表横着重排。**与 Word 面 Find 宽松度是同一类地雷**。注意 `XlSortOrientation` 命名反直觉：`xlSortColumns=1` 才是「数据行上下重排」。
- **破坏性写入必须「全部校验完再动手」**（`excel_conditional_format` / `excel_set_data_validation`）：这两条都是先 `Delete()` 清空用户既有规则再校验入参，模型把 operator/type 拼错一次，律师配好的条件格式或下拉列表就没了还只收到一句报错。Office.js 那边写入排队到 `context.sync()` 才落地、抛异常天然回滚，**WPS 是同步桥、当场生效**——同一份契约在两个宿主上原子性不同，凡是「先删后建」的命令都要把校验提前。
- **大区域必须先 `Resize` 再取 `Value2`**：`excel_get_range` 只回 500 行、`readEtSheet` 只附 2000 行，却都把整片已用区域编组过同步桥，几万行的台账「一问就把 WPS 卡死几十秒」。截断要发生在过桥之前；`excel_search` 另加 `MAX_SEARCH_SCAN_ROWS=5000` 扫描上限并在返回值里如实交代扫到哪儿（不假装搜完了）。
- **演示面实测定案**：`TextRange.Text` 的 JS 下标与 `Characters(start, len)` 的 1 基位置**是对齐的**（段落符 `\r` 计入长度，实测 21=21，跨段命中也对得上）——文字面那种两套坐标系的问题在 WPP **不存在**，别照搬。但 **`Replace` 的续查会串坐标系**：`TextRange.Start` 是**形状内绝对**位置，而 `Characters(start, len)` 的 start 是**相对被调用区间**的，第一轮恰好相等所以看不出来，第二轮起就错，**真机第三处直接抛 COM E_FAIL**。续查必须用形状内绝对游标（每轮从完整 TextRange 重新切），游标跳到刚写进去的内容之后，顺便也解决了「replaceText 里含 searchText 时自噬」。
  - 实测可用：`Slides.Add(Index, Layout)` 带插入位置、`Characters(s,l).ActionSettings.Item(1).Hyperlink.Address` **子串挂链**（此前列为未验证的类推 API）、`Shapes.AddTable` 与 `Table.Cell(r,c).Shape.TextFrame`、`Slide.MoveTo`、`Font.Underline`/`Font.Color.RGB`。
- **演示稿的正文常常不在 TextFrame 里**：表格形状（对比表、时间表、条款对照）的文字在 `Table.Cell(r,c).Shape` 里、组合形状（图示+标注、SmartArt 转出来的）的文字在子形状里。只看 `TextFrame` 会把整页读成「（无文本）」——用户看着满屏字，AI 说这页没内容。`wpsDoc.collectShapeText` 已按「表格 → 组合递归 → 普通文本框」三条路收，单个形状读失败只跳过它、不许拖垮整篇。
- **同步桥的循环纪律（ET/WPP 同样适用）**：`Count` 不许写在 for 的条件位（每轮都要跨桥重取）、`TextFrame`/`Range` 取一次存局部变量。`shapeHasText` 原先连取三次 `TextFrame`，上百页的演示稿光这一处就是几千次白跑。
- 官方模板 index.html 的角色：本地模式下 WPS 自动生成 index.html、开发者不许自建；**在线模式相反**，WPS 从服务器拉 url/index.html，我们必须提供。两句话都对，别拿一句去改另一边。
- **停靠任务窗格冻住 ribbon 是真机实锤**（2026-08-28 复测，bbs 93291 平台 bug，官方未修、社区全部规避手段无效）：窗格打开期间整条 ribbon 拒收鼠标，而关窗格按钮在 ribbon 上=死锁。解法：App.vue 头部有 WPS 家族专属「收起面板」按钮（hostBridge.hidePanel → wpsDoc.hideWpsTaskPane，经 PluginStorage 的**按宿主分键** `awd_taskpane_id_{wps,et,wpp}` 取窗格句柄自藏），收起即解锁、重开走 ribbon「AI 助手」。**别把这颗按钮当冗余删掉**。
- office 会话的产出去向规则在 ContextAssemblerService（中英两处）：起草/生成类请求默认写进当前打开的文档，不许新建项目文件保存（2026-08-28 真机复测教训：模型曾把「写简报」落成建空项目文件）。宿主措辞已中性化（「Microsoft Word 或 WPS 文字」等六处），别改回单提微软。
- 真机已验：install.html 一键安装、任务窗格 window.wps、登录、SSE 流式全通（2026-08-28，WPS 365/Win）。仍欠：Replies.Add/透视表/子串挂链三个类推 API。
- **字符偏移口径已实测定案（2026-08-29，WPS 12.1.0.28043，原始报告在 `office-addin/scripts/measurements/`）**——这是 dev-board#264「含表格文档成片死路」的根因：
  - `doc.Range().Text` 与 `doc.Range(start, end)` 是**两套坐标系**。表格的单元格结束符与行结束符在文本里是 `\r\x07` 两个 UTF-16 单元，在 Range 坐标系里只占 **1** 个位置。换算式：**文档位置 = JS 下标 − 该下标之前的 `\x07` 个数**。26 个锚点实测对照：直接拿 JS 下标去切只有 **2** 个对得上，按本式换算 **26 个全对**（含跨两张表格、含一个装了两段文字的单元格）。表格之前的锚点碰巧对齐、之后的全错——错的量正好等于中间的 `\x07` 个数，这就是「成片死路」的形状。
  - **推算不出来的两类**：批注引用标记（占文档位置但不进文本）、域（超链接的域代码占一大段位置，实测把整篇的偏差从 −6 翻成 +31）；**修订开着改过一轮之后也会漂**。这三档只能靠逐笔校验拦下、再退 Find。
  - **Find 可以只定位不替换**：`Execute` 不带 ReplaceWith 时会把调用它的 Range 重定义为命中区间（VBA 语义，WPS 实测确认），`.Start` 给出真实文档位置；命中的 Range 可以直接 InsertAfter / 设 Font / 取 Paragraphs.Item(1) / 传给 `Comments.Add`（**含表格单元格内**）与 `Hyperlinks.Add`，全部实测通过。
  - **WPS 的 Find 没有 Word 那条 255 字上限，也能跨段匹配**：300 字查找串命中且回读文本逐字相同、长度也是 300（无截断）；带 `\r` 的跨段查找串同样命中且逐字相同。所以别照搬 Word 的 255/不跨段口径给 WPS 加守卫。
- **量偏移口径的环境坑**：`Application.Name` **判不了宿主**——WPS 的 COM 层为了让针对 Word 写的 VBA 宏原样能跑，Name 属性直接返回 `"Microsoft Word"`（实测：机器上 Word 已卸载，`kwps.Application` 建出来的对象 Name 仍是 Microsoft Word）。**要按 `Application.Path` 判**（WPS 是 `...\Kingsoft\WPS Office\<版本>\office6`）。另：给 guest 跑的 .ps1 若含中文注释**必须存成带 BOM 的 UTF-8**，否则 Windows PowerShell 5.1 读乱后会报语法错。Parallels 共享的 `\\Mac\Home` 对 guest **可写**（`Remove-Item` 会静默失败）。
- `npm test`（含 wps*Handlers 单测与 sse XHR 通道用例）+ `npm run build` + `npm run build:wps`。
- 真机清单见 office-addin/README.md「WPS 加载项」章（Windows 虚拟机装个人版 WPS）。

## 跨文件读写：窗格心跳、跨文档写入与修订记录（2026-09-18，dev-board#717-720）

spec `docs/superpowers/specs/2026-09-18-addin-cross-file-design.md`。一句话：A 文档窗格里的 AI 能读别的文件（另一个打开的文档 / 桌面端项目 / 云端项目 / 官方案件库 / 关联的 git 仓库），也能改**另一个打开着的**文档——但痕迹必须落在**被改的那份文档自己的窗格**里。后端那一半（`ref_*` 四个工具、五个来源、`PaneRegistry`、`executeOnPane`）见 ai-chat.md「参考来源工具 ref_*」节；桌面端那一半见 mobile-sync.md「桌面端常连与参考读取」节。

### 关键文件（新增）

- `taskpane/lib/paneHeartbeat.js` — 30 秒一轮的心跳循环（`startHeartbeat({getState, post})`，起步即发一次，`beatNow()` 供补发）。**任何失败一律吞掉**：它跑在定时器里，抛出去就是没人接的 unhandled rejection；漏一轮无害，云端 90 秒过期本来就留了两轮余量。
- `taskpane/lib/referenceRead.js` — `read_for_reference` 的纯函数部分：`parseLocator`（`page:N` / `slide:N` / `sheet:名[!A1:D20]` / `heading:文字` / 空）、`sliceByHeading`、`capReferenceText`、`blankPageText`、`unsupportedLocatorError`。Office 面与 WPS 面共用同一份口径。
- `taskpane/lib/crossDocWrite.js` — 收到带 `origin` 的命令后怎么执行、怎么留痕、怎么撤销（`runCrossDocWrite` / `undoRevisionEntry` / `locateEntry` / `mergeCrossDocBanner` / `summarize`）。宿主相关的取值写回全部经 `hostBridge` 注入，可在 node 里测。
- `taskpane/lib/revisionLog.js` — 修订记录 store（模块级 `entries` reactive + `unread` ref + `revisionLogOpen`），按文档持久化。
- `taskpane/lib/gitLink.js` + `components/GitLinkPanel.vue` — 关联 GitHub/Gitee 仓库的入口（账户菜单一处）。
- `components/RevisionLogPanel.vue` — 修订记录面板（overlay，同 TransferPanel 形态）。
- 改：`chatSession.js`（paneId/身份广播/跨文档分流）、`api.js`（三组新端点）、`hostBridge.js`（跨文档五个接缝 + `documentKey()`）、`officeExecutor.js` / `wpsExecutor.js` / `wps{Word,Et,Wpp}Handlers.js`（`read_for_reference` + 快照读写）、`docSnapshot.js`、`i18n.js` / `i18n.test.js`、`App.vue`。

### 核心契约

- **paneId 就是 SSE 的 `clientId`，不另造第二个**：`chatSession.paneId = makePaneId()` 既当建 SSE 时（`/api/agent/connect/{cid}`）上送的 `clientId`，也当心跳与 `open:<paneId>` 里的那一个。两个同宿主窗格（两份 Word）各自独立。
- **心跳 `POST /api/addin/panes/heartbeat`**，body `{paneId, host, family, docName, projectId, conversationId}`，30 秒一轮；云端 90 秒没收到即当窗格已关。未登录（`configured` 为假）不发。`docName` **每轮现取**（未保存的新文档另存后名字会变）。云端对 `conversationId` 过一次 `canUseConversation`：**不是本账号的会话 → 整条心跳回 `code:1` 且不登记**（ai-chat.md 地雷 2b）。心跳失败本来就吞掉、下一轮再发，所以插件侧不需要为这一档加分支；窗格表现为暂时不出现在别人的 `ref_list` 里。
- **会话身份一变立刻补发**：`chatSession` 里 conversationId 的**每一处赋值都必须走 `setConversationId`**，它经 `onIdentityChange` 通知 App.vue 调 `hb.beatNow()`。绕过它 = 云端在下一轮心跳前（最多 30 秒）都拿着旧 conversationId，别的窗格发来的命令全投进一条没人听的会话里白等超时。
- **告别 `POST /api/addin/panes/bye`**：`pagehide` 与 `logout` 各发一次。**优先 `navigator.sendBeacon`**——卸载途中普通 fetch 会被掐掉；beacon 带不了自定义头，所以**令牌放在 body.token 里**，载荷用 `text/plain`（CORS 安全类型，跨域不触发预检），后端 `AddinPaneController.bye` 两种都认。beacon 不可用时落 `fetch(..., {keepalive:true})`。**登出时先告别再注销**：令牌一失效就认不出这是谁的窗格了。送不到也只是退回 90 秒过期兜底。
- **`read_for_reference {locator}` 是读取的唯一出口**，三宿主 × 两家族共六个实现，返回 `{text, truncated, totalChars}`（后端只取 `text`）。**宿主不支持的定位组合一律明确报错，绝不静默退回全文**——模型以为拿到的是第 3 页、其实是全文，比报错更糟。Word 按页要 `WordApiDesktop 1.2`（`wordPagesSupported()`），WPS 文字走 `ComputeStatistics` / `Information`。上限 200,000 字符、截断标注 `\n...(截断)`，**与后端 `ReferenceSourceService.MAX_CHARS` / `cap()` 逐字一致**。空页回「（第 N 页没有可读取的文字）」而不是空串。
- **它必须留在 `docSnapshot.READ_ONLY_COMMANDS` 里**：漏掉的话别人来读一次就被当成本文档有写入，白触发一次文档镜像上传。
- **跨文档命令走另一条路**：`handleClientAction` 见到 `action.origin` 就转 `handleCrossDocAction`，**串行排队**（`crossDocQueue`）——「取改前值 → 执行 → 读回改后值」是一个整体，两条命令交错会让后一条记下的改前值是前一条改到一半的状态，撤销就写回错东西。这是**别的会话**的动作，不往本窗格当前会话的气泡里挂工具 chip；无论成败都照常 `postOfficeResult`（发起方的工具调用在等它），永不 throw。
- **Word 面：无痕迹的跨文档写入不允许发生。** `crossDocTrackingOk()`（Office = `WordApi 1.4`，WPS = `TrackRevisions` 可读写）为假时**拒绝执行**并回「本机 Word 版本无法标记修订，已拒绝跨文档修改」；`WORD_UNTRACKABLE_COMMANDS`（`table_delete_row` / `table_delete_col` / `accept_revision` / `reject_revision` / `set_document_properties` / **`add_comment` / `reply_comment` / `resolve_comment`**）本身就标不出修订，同样拒绝——批注不是修订，两个家族的处理函数都不经 `withTracking`，`__forceTracking` 传进去会被原样丢掉。**漏一条的后果不只是少一道痕迹**：`runCrossDocWrite` 会照样记下一条 `undoable:false` 且没有 `noBefore` 的条目，面板据此显示「已标为修订，撤销请在修订中拒绝」，而文档里根本没有那处修订可拒绝。名单与执行器的对拍护栏在 `crossDocWrite.test.js`（Word 宿主的每条写入命令：要么经 withTracking 执行，要么在名单里）。**注意这与本窗格自己的写入不同**：自己的写入在 1.4 不支持时是降级直改标 `tracked:false`（见上文「工具桥」），跨文档不给这条降级。
- **Excel / PPT 面：没有修订机制，修订记录就是唯一痕迹**，也是撤销的依据。执行前经 `captureCrossDocState` 取受影响区域原值、执行后读回改后值，一并记进条目；`UNDO_LIMITS`（2000 格 / 20000 字符）之外不记快照，条目如实标「无法记录改前值」并照常执行。撤销前 `sameState(当前值, 改后值)` 比对，用户已再改过就报冲突**不覆盖**。`excel_select_range` 在 `NO_TRACE_COMMANDS` 里：不改内容，不留条目也不弹横幅。
- **修订记录按文档分开存**：`hostBridge.documentKey()`（Office 文档 URL / WPS `FullName`；**没有路径时是「宿主:文档名#本窗格实例」**，见地雷 5；判不出宿主回空串 = 只在内存里）→ `revisionLog.bindDocument(docKey)`，落 `localStorage` 键 `awd_addin_revlog_{docKey}`，上限 200 条。**写满配额的降级**：丢掉最新 20 条之外条目的 `before`/`after` 再存一次，那些条目改标 `undoable:false` + `snapshotDropped`，内存里同步改——宁可较早的撤不了，也不能整本记录从此存不进去、刷新后全没了。
- **跨文档写入之后要就地触发一次文档镜像**（`handleCrossDocAction` 置 `turnHadWrite` 后**立刻** `maybeArchiveSnapshot()`）。`turnHadWrite` 平时由 `finishStreaming` 消费，而收到跨文档命令的窗格并没有在跑自己的轮次——只置位不汇合的话，镜像要等本窗格的用户下次自己发消息才跑；被改的那份文档常常根本没人在它的窗格里聊天，于是**永远不跑**，桌面端项目里那份副本停在改动之前，界面上还没有任何迹象说它是旧的。护栏 `crossDocChat.test.js` 的镜像用例。
- **条目存 `summaryKey` + `summaryParams`，不存翻好的文字**（dev-board#713 同一条纪律）：切语言后已经记下的条目跟着换语言。
- **横幅与角标是两件事**：横幅（`chatSession.crossDocBanner`，同一来源 10 秒内合并计数）不自隐，用户点「查看」进面板或点 x 收起；**点 x 不清未读角标**（只是把提示划走，并没有看过改了什么），只有打开面板才清。头部入口的角标是「有人改过你的文档」唯一一直在的提示。
- **git 关联**：`GET/POST/DELETE /api/addin/git-links`，信封 `{code, message}`，**message 原样上浮**（「服务器未配置 git 令牌密钥」「仓库拒绝了这次访问」是用户唯一能据以改正的信息）。仓库访问令牌只在 POST 请求体里出现一次，**不写日志、不回显、不进本机存储**；面板只显示 `tokenLast4` 与 `lastError`。只读关联——AI 从不提交、不推送。

### 已知地雷（跨文件面）

1. **绕过 `setConversationId` = 跨窗格下发静默投空**。新会话/切会话/换项目/登出，四条路径都要经它。
2. **新增写入类命令要想清楚跨文档那一面**：Word 面标不出修订的要进 `WORD_UNTRACKABLE_COMMANDS`（否则会发生无痕迹的跨文档写入）；Excel/PPT 面要在 `captureOfficeState` / `captureWpsState` 里给出快照口径，给不出就是「不可撤销」——这是如实标注，不是 bug，但别让它默默变成「记了个错的改前值」。
3. **新增只读命令要进 `READ_ONLY_COMMANDS`**（漏加会被当成写入：多拍一次镜像、跨文档时还会白弹一次横幅）。
5. **未保存的新文档不能只靠「宿主:文档名」当键**（2026-09-20 修，`hostBridge.documentKey`）。Office 面的文档名就是从 `Office.context.document.url` 推出来的，url 为空（未保存的新文档）时它是宿主通称「当前 Word 文档」——两份新建的 Word 于是算出同一个 docKey、同一条 conversationId，跨窗格下发按会话走，命令落到另一份文档上，沿途无人报错（后端 `OpenDocSource` 现在会拒绝这种寻址不到的目标，但那是兜底，根子在这个键）。没有路径时键后面再缀一维「本窗格实例」（模块加载时生成、不持久化）：未保存的新文档本来就没有稳定身份，窗格重载即换键、修订记录只在本次会话内存活，但两份新文档绝不会撞在一起；存过盘之后有了路径，键自然回到按路径分。护栏 `revisionLocate.test.js` 的两条 documentKey 用例。
4. **两个新组件必须在 `i18n.test.js` 的 `SCAN_FILES` 里**（已加）：那条扫描是「模板里不得出现裸中文」的唯一护栏，不加等于这两个面板不受管。
5. **sendBeacon 在 Mac WKWebView 上是否送达未验证**：送不到就退回 90 秒过期，表现是关掉的文档在别人的 `ref_list` 里多留一会儿——**不要为此把过期时间调短**，那会误杀网络抖动的活窗格。
6. **`documentKey()` 必须在 `Office.onReady` 之后取**（App.vue 的 `onMounted` 已满足）：早了拿不到文档路径，条目会落到空 key 上、只在内存里。
7. **横幅文案不许说「修订已标记」**：那只对文字宿主成立，表格/演示没有修订机制。现文案统一说「已记入修订记录」。

### 验证（跨文件面）

- `cd office-addin && npm test` —— 新增 `paneHeartbeat.test.js`、`chatSessionIdentity.test.js`（身份广播与补发）、`referenceRead.test.js` / `referenceReadOffice.test.js` / `referenceReadWps.test.js`（locator 解析、按页/标题切段、截断、不支持即报错）、`crossDocWrite.test.js`（强制修订、拒绝分支、快照与冲突撤销）、`crossDocChat.test.js`（origin 分流、串行、必定回传）、`officeCrossDoc.test.js` / `wpsCrossDoc.test.js`（两个家族的快照读写接缝）、`revisionLog.test.js` / `revisionLogUi.test.js` / `revisionLogPanel.test.js` / `revisionLocate.test.js`、`gitLink.test.js`、`api.test.js`（心跳/告别/git-links 的请求形状）。
- UI/vite 动过就再 `npm run build`（与 `npm run build:wps`）。
- **真机走查是这批的判据，不是可选项**（spec §10）：Mac 上 Word A + Word B 互读互改、Word A 改 Excel/PPT B 并撤销、WPS（Parallels）同样两轮、桌面端常连的冷热耗时与断网回落、案件库与 GitHub 各读一份 docx。未做的项在卡上与汇报里如实标「未验证」。
