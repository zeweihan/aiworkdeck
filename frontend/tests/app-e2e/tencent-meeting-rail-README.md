<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 动态插件侧栏与工作区标签：真实工作台隔离验收

在仓库根目录运行：

```sh
node frontend/tests/app-e2e/tencent-meeting-rail-server.mjs
```

本工作树须已包含 `official-plugins/tencent-meeting/web` 插件源码；服务只读该目录，不接触已安装客户端的状态。资源不存在则启动失败。

可选同时验收尽调报告，显式指定其**静态 Web 目录**：

```sh
TMEET_RAIL_DD_WEB="$HOME/.aiworkdeck/plugins/due-diligence/web" \
  node frontend/tests/app-e2e/tencent-meeting-rail-server.mjs
```

目录必须已存在；fixture 只读取其中 `index.html`、`app.js`、`style.css`、`awd-plugin-sdk.js` 四个静态文件，不读取安装状态、凭据、JAR、数据库或项目文件，也不复制插件源码进仓库。未设置该变量仍只有腾讯会议，与原验收兼容。尽调 fixture 元数据针对 0.6.0 Web 契约，模板为固定合成数据；使用其他版本时应核对其首屏工具契约。

前置：本工作树 `frontend/node_modules` 已安装依赖，或明确复用现有依赖目录的软链接。脚本不安装依赖。默认 fixture 端口 19142、Vite 端口 19143；可分别通过 `TMEET_RAIL_PORT` / `TMEET_RAIL_VITE_PORT` 指定。两者仅绑定 `127.0.0.1`。Ctrl+C 停止 fixture 及其 Vite 子进程。首次动态编译工作台可能超过 uni-app 的页面等待时间；出现“连接服务器超时”时，等终端完成编译后按屏幕重试，不应改动产品超时设置。

## 入口和场景

- 已安装、停用（本次故障的起点）：<http://127.0.0.1:19142/?fixture=disabled#/pages/project-overview/project-overview?id=1140>
- 未安装：<http://127.0.0.1:19142/?fixture=uninstalled#/pages/project-overview/project-overview?id=1140>
- 已启用：<http://127.0.0.1:19142/?fixture=enabled#/pages/project-overview/project-overview?id=1140>
- 已封禁：<http://127.0.0.1:19142/?fixture=revoked#/pages/project-overview/project-overview?id=1140>
- 版本不兼容：<http://127.0.0.1:19142/?fixture=incompatible#/pages/project-overview/project-overview?id=1140>

`fixture=` 只在加载入口 HTML 时设置初始状态；不要在验收中刷新页面，否则它会重置场景。没有该参数的页面加载保留服务器内存状态。各场景共用一个内存服务，勿并行操作相互干扰。

## 真实 UI 验收步骤

在独立浏览器标签通过正常 UI 操作；当前任务只使用 `cua_repl`，不要操作用户真实 AI WorkDeck 窗口，也不要从控制台调用组件方法。

1. 从“已安装、停用”入口打开工作台。确认项目标题为“合成测试 · 腾讯会议侧栏回归”，侧栏 rail 尚无动态“腾讯会议”入口。
2. 点击 rail 的插件中心，点击“已安装”里的“腾讯会议”条目。在中栏真实 `MarketDetailPane` 打开启用开关。
3. **不刷新、不切路由**，确认 rail 立即出现腾讯会议图标。点击该图标，应进入真实 `PluginPane` 承载的插件面板，而不是“未配置入口地址”。插件初始不连接账号。
4. 返回插件中心的腾讯会议详情，关闭开关；确认 rail 立即消失。再开启，入口再次出现。
5. 详情页卸载并在正常确认框确认；确认 rail 消失、已安装列表不再保留该插件。
6. 重新打开“未安装”场景，在左侧 Marketplace 选择“插件”标签，使用条目内的“安装”按钮并在正常权限确认框确认。安装完成仍默认停用、rail 不出现；再通过详情启用，rail 立即出现。此步骤覆盖 `MarketSidebarPanel` 的操作通知链。
7. 单独重置为已封禁/不兼容场景，确认 rail 不出现。

被测事件完全由现有 Vue 组件触发：详情发 `awd:market-changed`，侧栏发 `awd:market-changed-from-sidebar`，工作台收到后重读列表。fixture **不发送这两个事件、不调用 `loadDynamicPlugins`、不修改 Vue 实例或 DOM**。仅看 API 状态改变不能当作 rail 验收通过；必须观察真实 DOM/截图。

## 两插件工作区标签验收

使用设置 `TMEET_RAIL_DD_WEB` 的服务，从已安装停用场景开始，两插件的安装/启用状态相互独立。每个插件都经真实插件详情启用，勿用诊断接口替代 UI 操作。

1. 启用腾讯会议和尽调报告。依次点两个 rail 入口，确认工作区出现两个独立标签，正文占用工作区；左栏保留插件概况和打开入口，不再挤压主要操作界面。
2. 腾讯会议搜索框填 `合成会议-保活`；尽调报告“主体清单”的名称填 `合成主体-保活`。反复切换两个标签，验证搜索/表单输入保留，且插件 SDK 已握手、尽调模板正常显示。
3. 切左侧 rail 到文件、插件中心等面板，确认工作区插件标签及内容仍可见。详情标签与使用标签应有明确区分，不相互覆盖。
4. 重复点同一 rail 和左栏“打开”入口，不能新增重复实例。将插件标签移到分屏后再次点击入口，定位既有实例，不能在另一屏复制 iframe；普通标签切换仍保留输入；跨窗格移动和关闭分屏可能重建 iframe，不要求未持久化输入保留。
5. 关闭腾讯会议使用标签，再从 rail 重开，应正常加载；关闭意味着结束实例，**不要求**关闭前未持久化的搜索值保留。尽调标签不受影响。
6. 从插件详情禁用腾讯会议，确认其 rail 和已开的使用标签移除；尽调仍在且表单不变。再启用腾讯会议、打开，然后卸载；再次确认其入口和使用标签清理。
7. 两插件启用并打开后，用**不带 `fixture=` 参数**的同项目 URL 重新进入，核对工作区快照恢复的标签可正常握手/显示；服务进程保持运行，以保留合成安装状态。重启页面不要求恢复 iframe 内未持久化表单。重复启动服务会重置安装状态，不能将这种重置误判成产品错误。
8. 快照里有插件标签、插件随后卸载的场景，也须重进工作台核对不恢复失效标签。若需模拟离线状态变化，可在关闭验收页后用下面的单插件重置接口；恢复页不得带 `fixture=`。

记录截图与具体 UI 结果；本脚本只提供合成后端，启动成功或 API 自检通过不代表以上工作区行为已通过。

## API 证据

只读诊断：<http://127.0.0.1:19142/__fixture/state>。

该响应的 `plugins` 按插件 ID 保存独立安装/启用状态；顶层字段继续表示腾讯会议以兼容旧检查。还包含按顺序记录的 API 路径及调用时两插件状态，以及尚无专用 mock 的端点。可用于核对：正常 UI 发出 `/api/plugins/tencent-meeting/enable` 之后，是否重新请求了 `/api/plugins/list`；关闭或卸载之后是否再次重读。请求记录不保存认证头或正文。

仅重置内存状态（不会发 UI 事件）：

```sh
curl -X POST 'http://127.0.0.1:19142/__fixture/reset?mode=disabled'
# 仅改变一个插件，另一插件保持原状：
curl -X POST 'http://127.0.0.1:19142/__fixture/reset?mode=uninstalled&plugin=tencent-meeting'
```

## Mock 边界

- **真实**：当前工作树的 uni-app/Vue 工作台、`MarketSidebarPanel`、`MarketDetailPane`、事件总线、API 包装器、动态 rail 计算、`PluginPane`、插件 Web 文件与 SDK。
- **合成**：桌面壳最小 `checkbaDesktop.apiBaseUrl` / `shell.openExternal`，本机用户与项目 1140，插件市场及安装/启停/卸载的 API 状态机，空文件树、空任务和账户状态。`shell.openExternal` 不打开外部链接。
- **未验证**：真实 Marketplace 下载、签名校验、JAR 热加载、腾讯会议 CLI、OAuth、真实账号/会议、Electron 原生窗口及数据库。
- `/api/*` 全部由本服务响应，绝不代理到用户后端；状态仅存在进程内存，停止后丢弃。安装不下载文件，启用不执行 JAR。腾讯会议只读 `status/config/list`、尽调 `dd_templates` 返回固定合成数据；尽调首屏读取 `_尽调/入库.json` 通过空文件树得到未入库状态，其他工具动作返回失败。不会执行入库、起草、创建会议或读写真实业务数据。
- 非关键、尚无专用 mock 的 GET 返回空 `data` 并记入 `unhandled`；未知写操作返回失败。若它影响工作台启动或验收路径，应补准确响应，不能将缺件造成的错误当作产品回归。
- 反向代理只连接本机隔离 Vite，HTML 中注入本地 API 地址。页面 CSP 限定连接为本 origin 和隔离 Vite 端口，阻止意外连接真实后端或公网服务。
