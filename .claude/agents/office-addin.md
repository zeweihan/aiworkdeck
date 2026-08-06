---
name: office-addin
description: Microsoft Office 插件领域。任务涉及 Word/Excel/PPT 任务窗格插件（office-addin/）、manifest、Office.js、插件与后端的对话/上下文契约、sideload 调试时，先读本文档再动代码。
---

# Office 插件 领域地图

职责边界：`office-addin/` 目录下的 Office Add-in（任务窗格 UI、Office.js 文档访问、与后端的连接/对话链路、office_command 执行器）。总 spec 见 `docs/superpowers/specs/2026-08-06-office-addin-and-memory-sync.md`（Phase B 脚手架、Phase C 工具桥+会话能力过滤已落地；Phase D 云后端生产化未做）。SSE/编排契约本体属 ai-chat 领域；office_* 后端桥（OfficeBridgeService/OfficeEditTools/能力过滤）详见 ai-doc-bridge 领域文档「第二条桥」一节。

## 关键文件

- `office-addin/manifest.xml` — XML add-in only 清单（不是 unified JSON）。Hosts 目前只有 Document（Word）；开发态 URL 全指 `https://localhost:3000`，部署时整体替换。改后跑 `npx office-addin-manifest validate manifest.xml`（Version 必须 >= 1.0，低了直接判非法）。
- `office-addin/taskpane.html` — 入口页。**office.js 必须从微软官方 CDN 以 script 标签引入，绝不打包进 bundle**（微软明令禁止自托管/打包）。
- `office-addin/taskpane/` — Vue3 源码：`App.vue`（视图切换+项目下拉）、`components/SettingsView.vue`（后端地址+awdt_ 令牌+连接测试）、`components/ChatView.vue`（对话+office_command 消费+工具活动 chip）、`lib/`（settings/api/sse/wordDoc/officeExecutor）。
- `office-addin/taskpane/lib/officeExecutor.js` — **office_command 执行器**：HANDLERS 表（get_text/get_selection/search/replace_text/insert_text/add_comment 的 Office.js 实现）+ COMMAND_DISPLAY_NAMES 固定中文名。未知 command 回 `{ok:false, error:'unsupported command'}`。
- `office-addin/vite.config.js` — 端口 3000；自动读 `~/.office-addin-dev-certs` 证书启 https；`publicDir: 'assets'`（图标构建时拷入 dist 根，URL 无 /assets 前缀）。

## 核心契约

- **鉴权**：awdt_ 设备令牌放 `X-Session-Id` 请求头（后端 `getUserIdFromSession` 前缀解析）。连接测试 = `GET /api/projects/my`。
- **对话**：`POST /api/agent/chat` + `GET /api/agent/connect/{cid}` SSE（fetch + ReadableStream，与主前端 useAgentStream 同一消费方式）。conversationId 客户端生成 `conv-<毫秒>`，插件会话独立。
- **文档上下文**：Word.run 读 body.text，经 `activeContext.inlineContent` 内联上送（客户端 200k 截断）；activeContext.id 用合成值 `office-current-document`（后端不会拿它读库，但 id 非空是注入门槛）。后端侧 inlineContent 优先于 read_document，见 ContextAssemblerService.resolveActiveDocumentContent（服务端同样 200k 上限）。
- **SSE 事件**：消费 text_delta/bubble_end/error/cancelled + `client_action`（仅 tool=office_command，其余 editor_command 等 LOWA 契约一律忽略）；text_delta 内容按 XML 标签轻量分流（final+裸文本=主回复、thinking=折叠）。
- **工具桥（Phase C）**：chat 请求带 `clientCapability: "office"` → 后端本会话只见 office_* 工具（doc_*/sheet_* 隐藏，防 30s 超时死路径）。命令链：SSE client_action `{tool:'office_command', requestId, command, args}` → officeExecutor 执行 → `POST /api/agent/office/result`（body `{requestId, ok, data|error}`，X-Session-Id 令牌鉴权，后端按挂起表校验会话归属）。修改类命令（replace_text/insert_text）执行前置 `changeTrackingMode=TrackAll`、执行后恢复原值（Word 原生修订）；WordApi 1.4 不支持时降级直接修改并标 `tracked:false`，add_comment 则直接报错。

## 已知地雷

- 错误文案不得含「登录/未授权/请先」子串（主前端以此判定未登录清会话；licensing 领域红线）。统一说「连接未就绪/令牌无效」。
- 全局禁 emoji；包管理 npm 不是 pnpm。
- 部署期 CORS：插件正式 Origin 要进 `security.cors.allowed-origins`；local-mode 下 LocalModeAccessFilter 用同一份白名单硬拦非 GET 跨站请求。`security.cors.allow-all` 绝不能开。localhost/127.0.0.1 默认放行，开发态零配置。
- Office 只加载 https 任务窗格：dev 证书 `npx office-addin-dev-certs install`，vite 配置自动拾取。
- **新增 office_* 工具三件套**：后端 OfficeEditTools 加 @Tool + officeExecutor.js 的 HANDLERS 加实现 + COMMAND_DISPLAY_NAMES 加中文名。没有客户端实现的远端工具 = 30s 超时空转（PptxEditTools 教训）。
- Word 的 body.search 查找串上限 255 字符（后端工具已前置校验）；search/replace 的锚点必须与文档文本精确一致（matchCase）。
- 修改类命令必须恢复用户原有的修订开关状态（withTracking 的 finally 恢复），别改成常开。

## 验证

- `cd office-addin && npm install && npm run build`；manifest 校验见上。
- sideload 手测清单与步骤全在 `office-addin/README.md`（含工具桥场景：AI 改文档要出现 Word 原生修订）。
- 后端单测（JDK 21）：`mvn test -Dtest='ContextAssemblerServiceTest,OfficeBridgeServiceTest,OfficeResultControllerTest,ToolRegistryCapabilityFilterTest,OfficeEditToolsTest'`。
