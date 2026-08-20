# Office 插件独立安装器

给不经 AppSource 的用户一条「下载 → 双击 → 重启 Office」的安装路。原理：任务窗格本体
托管在 https://addin.aiworkdeck.com/office-addin ，安装器只把一份指向该地址的 manifest
种进微软官方的 sideload 位置，因此**功能更新全在服务端完成，安装器一次安装长期有效**，
只有 manifest 结构变更（改 Id/Hosts/按钮等）才需要用户重装。

| 平台 | 产物 | 机制 |
|---|---|---|
| macOS | `AI-WorkDeck-Office-Addin-<版本>.dmg`（内含用户态安装器 .app，swiftc 编译） | 双击 app，把 manifest 拷进 Word/Excel/PowerPoint 容器的 `~/Library/Containers/com.microsoft.*/Data/Documents/wef/`；首次写入系统弹「访问其他 App 的数据」授权，拒绝时 app 给手动拖拽指引（DMG 根目录带一份 manifest.xml 兜底） |
| Windows | `AI-WorkDeck-Office-Addin-<版本>.exe`（NSIS，免管理员） | manifest 拷到 `%LOCALAPPDATA%\AIWorkDeck\OfficeAddin\`，写 `HKCU\Software\Microsoft\Office\16.0\WEF\Developer` sideload 键；带 uninstall.exe 并注册到「应用和功能」 |

> 历史教训（dev-board#68）：v0.21 前 macOS 走 payload-free pkg + root postinstall 写容器，
> macOS 26 起被应用容器保护直接拒绝（EPERM、无授权弹窗），Installer 必然失败；
> 且当时的「本机实测」是 Terminal 手跑脚本（带全盘访问），绕过了真正失败的 installd 链路。
> 结论：**写他人应用容器只能在用户会话内做**，验证必须走真 Installer/双击链路。

## 构建

```bash
cd office-addin && npm run build:installers
```

版本号取 `desktop/package.json`（单一来源）。产物在 `installer/dist/`。
Windows 侧需要 `brew install makensis`。`--url` 可换托管地址（律所私有部署）。

## 发布

上传到云后端静态目录，与任务窗格同域：

```bash
scp installer/dist/AI-WorkDeck-Office-Addin-*.{dmg,exe} root@8.152.169.44:/opt/aiworkdeck/cloud/web/office-addin/dl/
```

下载地址：`https://addin.aiworkdeck.com/office-addin/dl/<文件名>`，稳定名
`AI-WorkDeck-Office-Addin.dmg/.exe` 是服务器上指向带版本文件的软链，发新版要顺手更新。

## 签名与公证（macOS）

.app 走 Developer ID Application 签名 + Apple 公证（DMG 整体公证并装订），Gatekeeper
直接放行。构建脚本自动处理：钥匙串里有 `Developer ID Application: Zhen Shan Mei Grace
Legacy Limited` 身份即签名（证书与私钥备份在 `5-BQT_Global/fastlane/certs/
devid-app-local.{cer,key.pem}`，2026-08-20 办理；注意 Developer ID 证书只有 Account
Holder 能在开发者网站创建，ASC API 会 403）；再给齐三个环境变量即公证+装订：

```bash
NOTARY_KEY_PATH=/Users/zewei/Documents/2024-2044/5-Tech/5-BQT_Global/fastlane/ASCKey.p8 \
NOTARY_KEY_ID=<ASC_KEY_ID> NOTARY_ISSUER_ID=<ASC_ISSUER_ID> npm run build:installers
```

（两个 ID 的值在 `5-BQT_Global/fastlane/.env`。）验证：`spctl --assess --type open -v <dmg>`
或挂载后 `spctl --assess -v <app>` 应输出 `source=Notarized Developer ID`。

## 测试口径红线（dev-board#68 血泪）

1. Gatekeeper 验证必须在**带 quarantine 的副本**上做（干净本地构建会直接放行，量错对象）。
2. 手工合成 quarantine 串时标志位只许用浏览器真实值 `0081`/`0083`。**不许用 `0087`**——
   它含「文件由沙箱应用创建」标志位，系统会因此拒绝执行并报
   `File created by an AppSandbox, exec/open not allowed`（表现为对话框
   「The application can't be opened」），与产物本身毫无关系。#68 曾因此连冤三轮形态。
3. 最终验收以**浏览器真实下载**的包为准，不要信合成串。

## 已知限制

- **exe 未签名**，SmartScreen 会出「未知发布者」提示（与桌面端 Windows 包同等待遇）。
- Windows 的 `WEF\Developer` 键在 Office 「我的加载项」对话框里会归到开发者分组，
  功能区按钮不受影响。
- 卸载：Windows 走「应用和功能」；macOS 删除三个 wef 目录下的 `aiworkdeck-manifest.xml` 即可。
- macOS 首次运行安装器会弹一次「想访问其他 App 的数据」，用户点「不允许」则自动安装失败，
  app 会转手动指引；重试需在终端 `tccutil reset All com.aiworkdeck.office-addin.installer`
  或直接手动拷贝。
