---
name: office-addin
description: Microsoft Office 插件领域。任务涉及 Word/Excel/PPT 任务窗格插件（office-addin/）、manifest、Office.js、插件与后端的对话/上下文契约、sideload 调试时，先读本文档再动代码。
---

# Office 插件 领域地图

职责边界：`office-addin/` 目录下的 Office Add-in（任务窗格 UI、Office.js 文档访问、与后端的连接/对话链路）。总 spec 见 `docs/superpowers/specs/2026-08-06-office-addin-and-memory-sync.md`（Phase B 已落地脚手架；Phase C 工具桥、Phase D 云后端生产化未做）。SSE/编排契约本体属 ai-chat 领域。

## 关键文件

- `office-addin/manifest.xml` — XML add-in only 清单（不是 unified JSON）。Hosts 目前只有 Document（Word）；开发态 URL 全指 `https://localhost:3000`，部署时整体替换。改后跑 `npx office-addin-manifest validate manifest.xml`（Version 必须 >= 1.0，低了直接判非法）。
- `office-addin/taskpane.html` — 入口页。**office.js 必须从微软官方 CDN 以 script 标签引入，绝不打包进 bundle**（微软明令禁止自托管/打包）。
- `office-addin/taskpane/` — Vue3 源码：`App.vue`（视图切换+项目下拉）、`components/SettingsView.vue`（后端地址+awdt_ 令牌+连接测试）、`components/ChatView.vue`（对话）、`lib/`（settings/api/sse/wordDoc）。
- `office-addin/vite.config.js` — 端口 3000；自动读 `~/.office-addin-dev-certs` 证书启 https；`publicDir: 'assets'`（图标构建时拷入 dist 根，URL 无 /assets 前缀）。

## 核心契约

- **鉴权**：awdt_ 设备令牌放 `X-Session-Id` 请求头（后端 `getUserIdFromSession` 前缀解析）。连接测试 = `GET /api/projects/my`。
- **对话**：`POST /api/agent/chat` + `GET /api/agent/connect/{cid}` SSE（fetch + ReadableStream，与主前端 useAgentStream 同一消费方式）。conversationId 客户端生成 `conv-<毫秒>`，插件会话独立。
- **文档上下文**：Word.run 读 body.text，经 `activeContext.inlineContent` 内联上送（客户端 200k 截断）；activeContext.id 用合成值 `office-current-document`（后端不会拿它读库，但 id 非空是注入门槛）。后端侧 inlineContent 优先于 read_document，见 ContextAssemblerService.resolveActiveDocumentContent（服务端同样 200k 上限）。
- **SSE 事件**：MVP 只消费 text_delta/bubble_end/error/cancelled；text_delta 内容按 XML 标签轻量分流（final+裸文本=主回复、thinking=折叠），`client_action` 等留给 Phase C。

## 已知地雷

- 错误文案不得含「登录/未授权/请先」子串（主前端以此判定未登录清会话；licensing 领域红线）。统一说「连接未就绪/令牌无效」。
- 全局禁 emoji；包管理 npm 不是 pnpm。
- 部署期 CORS：插件正式 Origin 要进 `security.cors.allowed-origins`；local-mode 下 LocalModeAccessFilter 用同一份白名单硬拦非 GET 跨站请求。`security.cors.allow-all` 绝不能开。localhost/127.0.0.1 默认放行，开发态零配置。
- Office 只加载 https 任务窗格：dev 证书 `npx office-addin-dev-certs install`，vite 配置自动拾取。
- Phase C 之前不要注册任何 office_* 远端执行工具（客户端还没有执行器，会是死路径——PptxEditTools 教训）。

## 验证

- `cd office-addin && npm install && npm run build`；manifest 校验见上。
- sideload 手测清单与步骤全在 `office-addin/README.md`。
- 后端 inlineContent 路径单测：`mvn test -Dtest=ContextAssemblerServiceTest`（JDK 21）。
