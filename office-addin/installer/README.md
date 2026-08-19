# Office 插件独立安装器

给不经 AppSource 的用户一条「下载 → 双击 → 重启 Office」的安装路。原理：任务窗格本体
托管在 https://addin.aiworkdeck.com/office-addin ，安装器只把一份指向该地址的 manifest
种进微软官方的 sideload 位置，因此**功能更新全在服务端完成，安装器一次安装长期有效**，
只有 manifest 结构变更（改 Id/Hosts/按钮等）才需要用户重装。

| 平台 | 产物 | 机制 |
|---|---|---|
| macOS | `AI-WorkDeck-Office-Addin-<版本>.pkg`（payload-free） | postinstall 把 manifest 拷进 Word/Excel/PowerPoint 容器的 `~/Library/Containers/com.microsoft.*/Data/Documents/wef/` |
| Windows | `AI-WorkDeck-Office-Addin-<版本>.exe`（NSIS，免管理员） | manifest 拷到 `%LOCALAPPDATA%\AIWorkDeck\OfficeAddin\`，写 `HKCU\Software\Microsoft\Office\16.0\WEF\Developer` sideload 键；带 uninstall.exe 并注册到「应用和功能」 |

## 构建

```bash
cd office-addin && npm run build:installers
```

版本号取 `desktop/package.json`（单一来源）。产物在 `installer/dist/`。
Windows 侧需要 `brew install makensis`。`--url` 可换托管地址（律所私有部署）。

## 发布

上传到云后端静态目录，与任务窗格同域：

```bash
scp installer/dist/AI-WorkDeck-Office-Addin-*.{pkg,exe} root@8.152.169.44:/opt/aiworkdeck/cloud/web/office-addin/dl/
```

下载地址：`https://addin.aiworkdeck.com/office-addin/dl/<文件名>`。

## 已知限制

- **pkg 未签名**（本机与 CI 均无 Developer ID Installer 证书；桌面端签名用的是
  Developer ID Application，签不了 pkg）。用户首次打开需右键 → 打开，或在
  系统设置 → 隐私与安全性里放行。要消除此摩擦需在 Apple Developer 后台补一张
  Developer ID Installer 证书，之后 `productsign` + 公证接入即可。
- **exe 未签名**，SmartScreen 会出「未知发布者」提示（与桌面端 Windows 包同等待遇）。
- Windows 的 `WEF\Developer` 键在 Office 「我的加载项」对话框里会归到开发者分组，
  功能区按钮不受影响。
- 卸载：Windows 走「应用和功能」；macOS 删除三个 wef 目录下的 `aiworkdeck-manifest.xml` 即可。
