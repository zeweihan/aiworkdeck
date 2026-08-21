# hello-web-plugin

纯前端插件的最小示例：没有 Java、没有构建步骤，`web/index.html` 就是全部。

```
hello-web-plugin/
├── manifest.json            frontendEntry 指向 web/index.html，permissions 声明 file_read
└── web/
    ├── index.html           面板本体
    └── awd-plugin-sdk.js    SDK 副本（源头在 sdk/plugin-sdk/，逐字节一致）
```

## 本地试跑

把整个目录拷进插件目录，重启或在插件广场点「重新扫描」，然后在广场里启用它，
左栏就会多出一个入口：

| 形态 | 插件目录 |
|---|---|
| 本地开发 | `backend/plugins/` |
| 打包桌面版 | `~/.aiworkdeck/plugins/` |

```bash
cp -R examples/hello-web-plugin backend/plugins/
```

装完是**禁用**状态（在线安装的插件同理），在插件广场点「启用」后才会出现在左栏。

## 它演示了什么

- `awd.ready()` 拿握手上下文（`pluginId` / `projectId` / `language` / `theme`）
- `awd.files.list()` / `awd.files.read(path)`——需要 manifest 声明 `file_read`，
  把 manifest 里的 `permissions` 改成 `[]` 再重扫，就能看到 `permission_denied` 分支
- `awd.ui.toast(message)`
- `awd.storage.get/set`——插件级 KV，宿主代存，总量上限 64 KB
- `awd.evidence.list({})`——列当前聚焦 Word 文档的底稿关联（需 `file_read`）；
  没有打开 Word 文档时会看到 `no_active_document` 分支。
  建链 `awd.evidence.link` / 定位 `awd.evidence.locate` 需要 `editor` 权限，本示例未声明

## 边界

面板跑在 `sandbox="allow-scripts allow-forms"`（**无** `allow-same-origin`）的
iframe 里，静态资源由后端 `/api/plugin-web/hello-web-plugin/...` 服务并带严格 CSP：
不声明 `network` 权限就 `connect-src 'none'`，插件自己发不出任何请求，
一切能力只能经 postMessage 桥、按 manifest 权限裁剪后取得。

规范见 [docs/PLUGIN_SPEC.md](../../docs/PLUGIN_SPEC.md) §9，SDK 见
[sdk/plugin-sdk/](../../sdk/plugin-sdk/)。
