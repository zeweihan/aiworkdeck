---
name: office-addin
description: Microsoft Office 插件领域。任务涉及 Word/Excel/PPT 任务窗格插件（office-addin/）、manifest、Office.js、插件与后端的对话/上下文契约、sideload 调试时，先读本文档再动代码。
---

# Office 插件 领域地图

职责边界：`office-addin/` 目录下的 Office Add-in（任务窗格 UI、Office.js 文档访问、与后端的连接/对话链路、office_command 执行器）。总 spec 见 `docs/superpowers/specs/2026-08-06-office-addin-and-memory-sync.md`（Phase B 脚手架、Phase C 工具桥+会话能力过滤、收尾包 Excel/PPT 宿主+SSE 重连+awdk 直连+部署脚本已落地；Phase D 云后端生产化未做）。SSE/编排契约本体属 ai-chat 领域；office_* 后端桥（OfficeBridgeService/OfficeEditTools/能力过滤）详见 ai-doc-bridge 领域文档「第二条桥」一节。

## 关键文件

- `office-addin/manifest.xml` — XML add-in only 清单（不是 unified JSON）。Hosts=Document+Workbook+Presentation（Word/Excel/PPT 三宿主，VersionOverrides 各有 ribbon 按钮）；开发态 URL 全指 `https://localhost:3000`，部署用 build-manifest.mjs 生成替换版。改后跑 `npx office-addin-manifest validate manifest.xml`（Version 必须 >= 1.0，低了直接判非法）。
- `office-addin/scripts/build-manifest.mjs` — 生产部署产物生成器（node 内置模块）：`npm run build:deploy -- --url https://addin.yourfirm.com [--china]` 出 `dist-deploy/`（dist 页面 + 替换 URL 的 manifest）；`--china` 把输出里 taskpane.html 的 office.js CDN 换成世纪互联 `appsforoffice.cdn.partner.office365.cn`（源文件永远指全球版 CDN，dev 流程不变）。
- `office-addin/taskpane.html` — 入口页。**office.js 必须从微软官方 CDN 以 script 标签引入，绝不打包进 bundle**（微软明令禁止自托管/打包）。
- `office-addin/taskpane/` — Vue3 源码：`App.vue`（视图切换+项目下拉；header 不再自绘品牌名——Office 按 manifest DisplayName 已画一条标题，2026-08 去重）、`components/SettingsView.vue`（主路径=官网 API Key（awdk_）单字段连接；后端地址+awdt_ 设备令牌收进「高级设置」折叠区，服务自建服务器场景）、`components/ChatView.vue`（纯渲染+交互层；会话态全在 `lib/chatSession.js` 模块级 store——messages/conversationId/SSE 连接/streaming/草稿，切视图不卸载会话）、`lib/`（settings/api/sse/wordDoc/officeExecutor/chatSession/minimalEdit）。会话持久化：conversationId 按项目落 localStorage（`awd_addin_conv_{projectId}`），窗格重建时经 `GET /api/ai/history` 回灌（tag parser 拆 thinking/final，工具 chip 不回灌），回灌建连的首个 run_state 是权威状态（RUNNING 则锁输入续写，restorePending 标记位区分于 send 建连语义）。项目非必选：无项目时静默调 `POST /api/projects/ensure-addin-default` 懒建「插件临时项目」，单项目不渲染下拉。默认后端地址构建期注入：`vite.config.js` 的 define `__ADDIN_DEFAULT_SERVER__`（`VITE_ADDIN_SERVER_URL` 环境变量可覆盖，缺省 `https://addin.aiworkdeck.com`），`settings.js` 在 localStorage 无值时回落到它；build-manifest.mjs 只拷 dist 不重新构建，换默认地址要在 `npm run build` 前设环境变量。
- `office-addin/taskpane/lib/wordDoc.js` — 宿主检测 `detectHost()`（word/excel/powerpoint）+ 宿主感知的 `readActiveDocument()`：Word=正文纯文本、Excel=活动表已用区域 TSV（上限 2000 行）、PPT=各页形状文本（上限 100 页，需 PowerPointApi 1.4）。
- `office-addin/taskpane/lib/officeExecutor.js` — **office_command 执行器**：HANDLERS 表（Word 面 get_text/get_selection/search/replace_text/insert_text/add_comment/format_text/set_paragraph_format/get_formatting/set_numbering/format_table/apply_standard_format + Excel 读写面 excel_get_range/excel_set_values/excel_search + Excel 格式/结构面（批次 6，15 个 command 与桌面端 sheet_* 数量对齐）excel_format_cells/excel_set_borders/excel_edit_rows_cols/excel_merge_cells/excel_sort_range/excel_manage_sheets/excel_freeze_panes/excel_set_formulas/excel_get_overview/excel_select_range/excel_set_autofilter/excel_conditional_format + PPT 面 ppt_get_slides/ppt_replace_text）+ COMMAND_DISPLAY_NAMES 固定中文名 + COMMAND_HOSTS 宿主守卫（宿主不符回 `{ok:false, error:'unsupported host: ...'}`）。未知 command 回 `{ok:false, error:'unsupported command'}`。律所标准格式常量 `HOUSE` 与小标题启发式 `HEADING_RE` 也在本文件（apply_standard_format 用）。
- `office-addin/taskpane/lib/sse.js` — SSE 消费 + **断线自动重连**：指数退避 1s 起上限 30s；心跳（后端 15s 一次）缺失 40s 判死连接主动重建；首连失败不重连（ready reject 即时报错）；onClose 只在主动 close 时触发，重连状态走 onStatus。
- `office-addin/assets/` — 16/32/64/80 图标，源自 `desktop/build/icon.png`（sips 缩放），构建时拷入 dist 根（URL 无 /assets 前缀）。
- `office-addin/vite.config.js` — 端口 3000；自动读 `~/.office-addin-dev-certs` 证书启 https；`publicDir: 'assets'`。

## 核心契约

- **鉴权**：awdt_ 设备令牌放 `X-Session-Id` 请求头（后端 `getUserIdFromSession` 前缀解析）。连接测试 = `GET /api/projects/my`。桌面端生成 awdt_ 的界面在 userprofile「插件访问令牌」分组（走 `POST /api/auth/device-token/issue-local`，仅 local-mode，见 licensing-billing.md）。**awdk_ 一键连接**：`POST /api/auth/awdk-login`（body `{key}`，匿名端点，开关 `security.awdk-login-enabled` 默认关）→ `{code:0, data:{token}}` 换回 awdt_ 存本地，Key 本身用完即弃不落盘；开关未开/旧后端 404 → 提示「该服务器未开启账户直连，请改用设备令牌」。
- **对话**：`POST /api/agent/chat` + `GET /api/agent/connect/{cid}` SSE（fetch + ReadableStream）。conversationId 优先服务端签发（`POST /api/agent/conversations`，body `{projectId}`，契约与后端并行分支约定），404/失败静默回退客户端 `conv-<毫秒>`。
- **文档上下文**：按宿主读取（见 wordDoc.js），经 `activeContext.inlineContent` 内联上送（客户端 200k 截断）；activeContext.id 用合成值 `office-current-document`。后端侧 inlineContent 优先于 read_document（ContextAssemblerService，服务端同样 200k 上限）。**正文省传**：同一会话内文档没变时客户端只上送 `activeContext.inlineContentHash`（SHA-256 十六进制，`wordDoc.js` 的 `hashContent`）、不带 inlineContent；后端 `InlineContentCache`（按会话，LRU 上限 32 条 ≈13MB）凭哈希取回上一轮正文，**哈希后端自算**（客户端上送值只当省传信号），未命中即按「无内联正文」现状处理不报错。
- **发送路径（批次 5 性能）**：会话签发（`POST /api/agent/conversations`）与 SSE 建连提前到 `preconnect()`——进面板/切项目（activateSession）与「新对话」时就做完，send 只剩「读文档 ‖ 兜底 preconnect → POST /chat」两件并行事。每轮四段耗时经 `console.info('[AddinPerf]', {...})` 输出并存 `lastPerf`（docReadMs/docChars/docReused/connectMs/chatAcceptedMs/firstTokenMs/totalMs），不上报遥测。
- **SSE 事件**：消费 text_delta/bubble_end/error/cancelled/run_state + `client_action`（仅 tool=office_command）。run_state 仅在断线重连后消费：漏掉终态事件时按状态（非 RUNNING/PAUSED/AWAITING_APPROVAL）兜底解锁输入框——首连的 run_state 不能当终态看（send 已先置 streaming）。
- **工具桥（Phase C+宿主细分）**：chat 请求带 `clientCapability: "office"` + `officeHost: "word|excel|powerpoint"`（缺省 word）→ ClientCapabilityService 记录 → ToolRegistry 过滤：word 会话只见 Word 面 office_*（读写六个 + 格式六个）、excel 只见 office_excel_*、ppt 只见 office_ppt_*（前缀判定，`hostOfTool` 最长前缀优先）；doc_*/sheet_* 对 office 会话一律隐藏。ContextAssemblerService 的 office 分支文案按宿主点名对应工具集。命令链：SSE client_action `{tool:'office_command', requestId, command, args}` → officeExecutor 执行 → `POST /api/agent/office/result`。Word 修改类命令前置 `changeTrackingMode=TrackAll`、执行后恢复原值（WordApi 1.4 不支持时降级直改标 `tracked:false`）；**Excel/PPT 没有修订机制，写入直接生效**。

## 已知地雷

- 错误文案不得含「登录/未授权/请先」子串（主前端以此判定未登录清会话；licensing 领域红线）。统一说「连接未就绪/令牌无效/账户直连失败」。awdk 失败**不透传服务端原文**（服务端文案不受该红线约束）。
- 全局禁 emoji；包管理 npm 不是 pnpm。
- 部署期 CORS：插件正式 Origin 要进 `security.cors.allowed-origins`；local-mode 下 LocalModeAccessFilter 用同一份白名单硬拦非 GET 跨站请求。`security.cors.allow-all` 绝不能开。localhost/127.0.0.1 默认放行，开发态零配置。
- **云后端首次上线的必配项清单**（Phase D 生产化尚未做，站起来之前照这张单子过一遍；截至 2026-08-07 两台 ECS 上都还没有这个后端实例）：
  `security.local-mode=false`（默认）、`security.registration-mode=closed`、`security.awdk-login-enabled=true`、
  `security.conversation-issuance-required=true`、`security.cors.allowed-origins` 填插件正式 Origin，
  以及环境变量 **`AWD_PLATFORM_KEY_SECRET`**（per-user 平台 AI 密钥的落库加密密钥，任意高熵串，
  例如 `openssl rand -base64 32`）。**最后这条是启动强不变式**：`awdk-login-enabled=true` 而它缺失时
  服务直接拒绝启动（`PlatformAiKeyCipher` 构造器，licensing 领域地雷 17），刻意不做明文降级。
  它与官网侧的 `AWD_KEY_ENCRYPTION_SECRET` 是两把互不相干的密钥，别复用同一个值。
- Office 只加载 https 任务窗格：dev 证书 `npx office-addin-dev-certs install`，vite 配置自动拾取。
- **新增 office_* 工具三件套**：后端 OfficeEditTools 加 @Tool + officeExecutor.js 的 HANDLERS 加实现 + COMMAND_DISPLAY_NAMES 加中文名，**并在 COMMAND_HOSTS 标宿主**。没有客户端实现的远端工具 = 30s 超时空转（PptxEditTools 教训）。**工具名前缀决定宿主可见性**：Excel 面必须 office_excel_*、PPT 面必须 office_ppt_*，其余 office_* 归 Word——起错前缀会漏进错误宿主的会话（死路径）。
- Word 的 body.search 查找串上限 255 字符（后端工具已前置校验）；search/replace 的锚点必须与文档文本精确一致（matchCase）。
- **replace_text 走字符级最小修订**（`lib/minimalEdit.js`，口径与 LOWA office_thread.js 的 minimalEdits 一致、PR#188 同源）：差异段在命中 Range 内二次 search 定位（歧义时对称扩窗消歧），**全部定位在任何写入之前完成**，任一段不唯一即整段回退（返回值 `via` 标 minimalRedline/fullReplace/mixed）；多命中与段内编辑均从右到左应用。改差分口径两边要一起想。
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
- SSE 重连语义：onClose 只在主动 close 触发；改 ChatView 的 streaming 解锁逻辑时记住三条路径（bubble_end/error/cancelled 正常终态、onClose 主动关、重连后 run_state 兜底）。
- **建连有三种来源，run_state 三种读法**（chatSession.js 的 handleRunState）：回灌（restorePending=true，首个 run_state 是权威状态，RUNNING 则锁输入续写）、**预连**（无 restorePending 无 streaming，run_state 必须零副作用——两个分支都不进）、send 兜底（streaming 已置起，只有 everReconnected 后才用 run_state 解锁）。加预连类的新调用点时先确认它落在哪一种。
- **省传只在上一轮 bubble_end 之后启用**：出过 error 的会话（含旧后端不认 inlineContentHash 的情况）整场退回恒传全文；`crypto.subtle` 取不到（非 secure context）时哈希为空串，同样恒传全文。改 docCache 的提交/失效时机要同时想「旧后端把只带哈希的请求当无正文」这条降级路径。
- 世纪互联 CDN 替换只发生在 build-manifest.mjs 的输出目录——别把源 taskpane.html 的 office.js 地址改掉。

## 验证

- `cd office-addin && npm install && npm run build`；manifest 校验（dev 与 dist-deploy 两份）见上。
- `npm run build:deploy -- --url https://addin.example.com --china` 后检查 dist-deploy/manifest.xml 无 localhost URL、taskpane.html 用 partner.office365.cn CDN。
- sideload 手测清单与步骤全在 `office-addin/README.md`（Word 工具桥场景 + Excel/PPT 场景 + 断线重连/awdk 连接场景）。
- 后端单测（JDK 21）：`mvn test -Dtest='ContextAssemblerServiceTest,InlineContentCacheTest,OfficeBridgeServiceTest,OfficeResultControllerTest,ToolRegistryCapabilityFilterTest,OfficeEditToolsTest'`。
