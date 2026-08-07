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
- `office-addin/taskpane/` — Vue3 源码：`App.vue`（视图切换+项目下拉）、`components/SettingsView.vue`（后端地址+awdt_ 令牌+连接测试+awdk_ 账户 Key 一键连接）、`components/ChatView.vue`（对话+office_command 消费+工具活动 chip+重连提示+run_state 兜底解锁）、`lib/`（settings/api/sse/wordDoc/officeExecutor）。
- `office-addin/taskpane/lib/wordDoc.js` — 宿主检测 `detectHost()`（word/excel/powerpoint）+ 宿主感知的 `readActiveDocument()`：Word=正文纯文本、Excel=活动表已用区域 TSV（上限 2000 行）、PPT=各页形状文本（上限 100 页，需 PowerPointApi 1.4）。
- `office-addin/taskpane/lib/officeExecutor.js` — **office_command 执行器**：HANDLERS 表（Word 面 get_text/get_selection/search/replace_text/insert_text/add_comment + Excel 面 excel_get_range/excel_set_values/excel_search + PPT 面 ppt_get_slides/ppt_replace_text）+ COMMAND_DISPLAY_NAMES 固定中文名 + COMMAND_HOSTS 宿主守卫（宿主不符回 `{ok:false, error:'unsupported host: ...'}`）。未知 command 回 `{ok:false, error:'unsupported command'}`。
- `office-addin/taskpane/lib/sse.js` — SSE 消费 + **断线自动重连**：指数退避 1s 起上限 30s；心跳（后端 15s 一次）缺失 40s 判死连接主动重建；首连失败不重连（ready reject 即时报错）；onClose 只在主动 close 时触发，重连状态走 onStatus。
- `office-addin/assets/` — 16/32/64/80 图标，源自 `desktop/build/icon.png`（sips 缩放），构建时拷入 dist 根（URL 无 /assets 前缀）。
- `office-addin/vite.config.js` — 端口 3000；自动读 `~/.office-addin-dev-certs` 证书启 https；`publicDir: 'assets'`。

## 核心契约

- **鉴权**：awdt_ 设备令牌放 `X-Session-Id` 请求头（后端 `getUserIdFromSession` 前缀解析）。连接测试 = `GET /api/projects/my`。**awdk_ 一键连接**：`POST /api/auth/awdk-login`（body `{key}`，匿名端点，开关 `security.awdk-login-enabled` 默认关）→ `{code:0, data:{token}}` 换回 awdt_ 存本地，Key 本身用完即弃不落盘；开关未开/旧后端 404 → 提示「该服务器未开启账户直连，请改用设备令牌」。
- **对话**：`POST /api/agent/chat` + `GET /api/agent/connect/{cid}` SSE（fetch + ReadableStream）。conversationId 优先服务端签发（`POST /api/agent/conversations`，body `{projectId}`，契约与后端并行分支约定），404/失败静默回退客户端 `conv-<毫秒>`。
- **文档上下文**：按宿主读取（见 wordDoc.js），经 `activeContext.inlineContent` 内联上送（客户端 200k 截断）；activeContext.id 用合成值 `office-current-document`。后端侧 inlineContent 优先于 read_document（ContextAssemblerService，服务端同样 200k 上限）。
- **SSE 事件**：消费 text_delta/bubble_end/error/cancelled/run_state + `client_action`（仅 tool=office_command）。run_state 仅在断线重连后消费：漏掉终态事件时按状态（非 RUNNING/PAUSED/AWAITING_APPROVAL）兜底解锁输入框——首连的 run_state 不能当终态看（send 已先置 streaming）。
- **工具桥（Phase C+宿主细分）**：chat 请求带 `clientCapability: "office"` + `officeHost: "word|excel|powerpoint"`（缺省 word）→ ClientCapabilityService 记录 → ToolRegistry 过滤：word 会话只见 Word 面六个 office_*、excel 只见 office_excel_*、ppt 只见 office_ppt_*（前缀判定，`hostOfTool` 最长前缀优先）；doc_*/sheet_* 对 office 会话一律隐藏。ContextAssemblerService 的 office 分支文案按宿主点名对应工具集。命令链：SSE client_action `{tool:'office_command', requestId, command, args}` → officeExecutor 执行 → `POST /api/agent/office/result`。Word 修改类命令前置 `changeTrackingMode=TrackAll`、执行后恢复原值（WordApi 1.4 不支持时降级直改标 `tracked:false`）；**Excel/PPT 没有修订机制，写入直接生效**。

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
- 修改类命令必须恢复用户原有的修订开关状态（withTracking 的 finally 恢复），别改成常开。
- **PowerPoint 文本读写要 PowerPointApi 1.4**（TextFrame/TextRange；Microsoft 365 较新版本才有，2019/2021 永久版没有）——执行器 requirePptTextApi 前置报错，别绕开它直接调 API。Excel 查找是客户端扫已用区域（兼容旧宿主），别改成 ExcelApi 1.9 的 findAll。
- SSE 重连语义：onClose 只在主动 close 触发；改 ChatView 的 streaming 解锁逻辑时记住三条路径（bubble_end/error/cancelled 正常终态、onClose 主动关、重连后 run_state 兜底）。
- 世纪互联 CDN 替换只发生在 build-manifest.mjs 的输出目录——别把源 taskpane.html 的 office.js 地址改掉。

## 验证

- `cd office-addin && npm install && npm run build`；manifest 校验（dev 与 dist-deploy 两份）见上。
- `npm run build:deploy -- --url https://addin.example.com --china` 后检查 dist-deploy/manifest.xml 无 localhost URL、taskpane.html 用 partner.office365.cn CDN。
- sideload 手测清单与步骤全在 `office-addin/README.md`（Word 工具桥场景 + Excel/PPT 场景 + 断线重连/awdk 连接场景）。
- 后端单测（JDK 21）：`mvn test -Dtest='ContextAssemblerServiceTest,OfficeBridgeServiceTest,OfficeResultControllerTest,ToolRegistryCapabilityFilterTest,OfficeEditToolsTest'`。
