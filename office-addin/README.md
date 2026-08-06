# AI Workdeck Office 插件（Word 任务窗格）

Microsoft Office Add-in 脚手架，Word 先行（spec Phase B）。任务窗格 = Vue3 + Vite；
office.js 从微软官方 CDN 以 script 标签引入，不打包进 bundle。

插件独立连接后端实例（律所自建服务器 / 官方云 / 同机桌面版 `http://127.0.0.1:5269`），
鉴权用 awdt_ 设备令牌（X-Session-Id 请求头携带）。对话复用主链路契约：
`POST /api/agent/chat` + `GET /api/agent/connect/{conversationId}`（SSE），
当前文档正文经 `activeContext.inlineContent` 内联随消息上送。

## 目录结构

```
office-addin/
  manifest.xml        XML add-in only 清单（开发态，URL 指向 https://localhost:3000）
  assets/             图标占位（构建时拷入 dist 根，dev 下按根路径直出）
  taskpane.html       任务窗格入口页（office.js 以 CDN script 标签引入）
  taskpane/           Vue3 源码（App / SettingsView / ChatView / lib）
  vite.config.js      端口 3000；自动读取 office-addin-dev-certs 的本地 HTTPS 证书
```

## 本地开发

```bash
cd office-addin
npm install

# 首次：安装本地 HTTPS 证书（Office 只加载 https 的任务窗格页面）。
# 会在 ~/.office-addin-dev-certs 下生成 localhost.key/localhost.crt 并把 CA 装入系统信任链，
# vite.config.js 检测到证书即自动启用 https。
npx office-addin-dev-certs install

npm run dev        # https://localhost:3000/taskpane.html
npm run build      # 产物在 dist/
```

无 Office 宿主时可直接在浏览器打开 `https://localhost:3000/taskpane.html` 调试 UI
（office.js 未初始化时「附带文档正文」会读不到内容，属预期）。

## Sideload 调试

### macOS（wef 目录方式）

```bash
cp manifest.xml ~/Library/Containers/com.microsoft.Word/Data/Documents/wef/
```

目录不存在则先 `mkdir -p` 创建。然后完全退出并重开 Word，
菜单「插入 → 加载项（Add-ins）→ 我的加载项 → 开发人员加载项」里选择 AI Workdeck。
更新 manifest 后需删掉重拷并重启 Word。

### Windows（网络共享目录方式）

1. 建一个文件夹（如 `C:\addin-manifests`），右键 → 属性 → 共享，共享给自己，记下网络路径（`\\机器名\addin-manifests`）。
2. 把 `manifest.xml` 拷入该文件夹。
3. Word →「文件 → 选项 → 信任中心 → 信任中心设置 → 受信任的加载项目录」，
   把网络路径添加到目录 URL 并勾选「在菜单中显示」，确定后重启 Word。
4. 「插入 → 获取加载项 → 共享文件夹」里选择 AI Workdeck。

### Windows（注册表方式，仅经典 Office）

`HKEY_CURRENT_USER\Software\Microsoft\Office\16.0\WEF\Developer` 下新建字符串值，
名称任意（如 `AIWorkdeckAddin`），数据为 manifest.xml 的绝对路径。重启 Word 生效。

## manifest 校验

```bash
npx office-addin-manifest validate manifest.xml
```

## 部署注意（CORS 与 local-mode）

后端不需要为插件改任何代码，但部署时要把插件页面的 Origin 配进白名单：

- **CORS 白名单**：插件正式托管后（如 `https://addin.yourfirm.com`），该 Origin 必须加进后端配置
  `security.cors.allowed-origins`（CSV），否则浏览器侧跨域请求会被拦。
  `http(s)://localhost:*` 与 `127.0.0.1` 默认已放行，本地开发态无需配置。
  `security.cors.allow-all` 逃生门绝不能开。
- **local-mode（单机桌面后端 5269）**：`LocalModeAccessFilter` 会对携带 Origin 的非 GET 请求
  做同一份白名单硬校验（跨站请求直接 403，不只是不回 CORS 头）。开发态 `https://localhost:3000`
  在默认放行范围内；若插件页面托管在非 localhost 域而要连本机 5269，同样需要把该 Origin
  加进 `security.cors.allowed-origins`。
- 团队服务器（server 模式）无 local-mode 闸，只需配 CORS 白名单。

## 真机手测清单（发版前过一遍）

1. `npm run dev` 起 dev server（https 生效），sideload 进 Word，任务窗格能打开。
2. 设置页：填后端地址 + awdt_ 令牌 → 「测试连接」显示可访问项目数；
   故意填错令牌 → 提示「令牌无效或后端拒绝了请求」（文案不含「登录/未授权/请先」）。
3. 保存后回到对话视图，顶部项目下拉能列出且能切换项目，选择在重开任务窗格后仍记住。
4. 发一条与文档无关的消息 → 助手回复流式逐字出现，结束后输入框解锁。
5. 打开一份有内容的文档，勾选「随消息附带当前文档正文」，问「总结当前文档」→
   回复内容明确引用了文档正文（验证 inlineContent 注入链路）。
6. 取消勾选再问 → 回复不再引用文档内容。
7. 流式中点「停止」→ 输出停止、输入框解锁；「新对话」→ 消息清空、后续消息开新会话。
8. 断网/停后端再发消息 → 出现「后端不可达」类错误提示，输入框不卡死。
9. 关闭再重开任务窗格 → 设置与项目选择仍在（localStorage 持久化）。

## 已知边界（MVP）

- 只消费 `text_delta`/`bubble_end`/`error`/`cancelled` 四类 SSE 事件，
  `client_action`（工具桥，Phase C）、`plan_update` 等先忽略。
- 流式文本按 XML 标签轻量分流：`<final>` 与标签外文本为主回复、`<thinking>` 折叠展示，
  `<process>`/`<artifact>` 等暂不渲染。
- awdt_ 令牌手工粘贴是 MVP 形态，Phase D 用 awdk_ 桥替换。
