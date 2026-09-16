# AppSource（Microsoft Marketplace）提交包

dev-board#696。这个目录装的是**提交材料**，不是产物：Partner Center 每一页要填什么、从哪个文件
拷、政策哪一条对应我们的哪个事实。产物（可托管的 `dist-deploy/`）仍由 `npm run build:deploy` 出。

| 文件 | 用途 |
|---|---|
| `README.md` | 本文件：提交流程一页纸 + 政策对照表 |
| `listing.en-US.md` | 英文商店列表逐字文案（Name / Summary / Description / Keywords / Categories） |
| `listing.zh-CN.md` | 简体中文商店列表逐字文案 |
| `certification-notes.en.md` | Notes for certification 模板（审核员操作说明，凭据用占位符） |
| `assets/store-logo-300.png` | 商店 logo，300x300 PNG |
| `screenshots/README.md` | 截图规格与镜头清单（截图本身由 dev-board#697 产出） |

---

## 0. 已定事实（不要在提交时重新拍板）

| 项 | 值 |
|---|---|
| 托管地址 | `https://addin.workdeck.ai/office-addin/`（国际站） |
| 提交的清单 | `https://addin.workdeck.ai/office-addin/manifest.xml`（由 build-manifest 生成的那份，不是仓库里的开发态 `manifest.xml`） |
| office.js | 微软全球 CDN `https://appsforoffice.microsoft.com/lib/1/hosted/office.js`（**不要用 `--china` 变体提交**，见政策表 1120.1） |
| 隐私政策 | `https://www.aiworkdeck.com/en/legal/privacy` |
| EULA | `https://www.aiworkdeck.com/en/legal/terms`（**不用**微软标准合同，维护者拍板） |
| 支持 URL | `https://www.workdeck.ai` |
| iPad | **不做**。Product setup 的 iOS 勾选框留空，不填 Apple ID |
| SSO / Entra ID | 无。Product setup 那个勾选框留空 |
| Requires additional purchases | **必须勾**。AI 用量按站内 Credits 计费，充值只在官网账户页 |
| 商店列表语言 | en-US 与 zh-CN 两份（清单 `DefaultLocale=zh-CN`，两边都有 Override） |
| Publisher name | 已建：`AI_WorkDeck`（Partner Center 不收空格，建完改不了，2026-09-16 实测）。清单 `<ProviderName>AI WorkDeck</ProviderName>` **保持不动**，按微软「identical or very similar」口径提交；若审核以此驳回再改清单 |

---

## 1. 出包（提交前必做，顺序不能换）

```bash
cd office-addin
npm install

# 关键：默认后端地址是构建期焊进 bundle 的（vite.config.js 的 __ADDIN_DEFAULT_SERVER__），
# 缺省值是国内的 addin.aiworkdeck.com。国际站包必须显式覆盖，
# 否则托管在 addin.workdeck.ai 的这份包会把全世界的请求打回北京。
VITE_ADDIN_SERVER_URL=https://addin.workdeck.ai npm run build

# 不带 --china：世纪互联变体会把 office.js 换成 partner.office365.cn，那违反政策 1120.1。
npm run build:deploy -- --url https://addin.workdeck.ai/office-addin --out dist-deploy-intl

npx office-addin-manifest validate dist-deploy-intl/manifest.xml
```

`build-manifest.mjs` 会按托管 host 把 `SupportUrl` 与 `GetStarted.LearnMoreUrl` 派生成
`https://www.workdeck.ai`（`BRAND_SITE_BY_ADDIN_HOST`），所以支持链接不用手填进清单。

把 `dist-deploy-intl/` 整份上传到 `addin.workdeck.ai` 的 `/office-addin/` 路径下，然后**先自己
在浏览器里点开**下面四个地址确认都是 200、都不是 SPA 回退页：

- `https://addin.workdeck.ai/office-addin/manifest.xml`
- `https://addin.workdeck.ai/office-addin/taskpane.html`
- `https://addin.workdeck.ai/office-addin/icon-32.png`
- `https://addin.workdeck.ai/office-addin/icon-64.png`

上传给 Partner Center 的是 `dist-deploy-intl/manifest.xml` 这个文件。

---

## 2. Partner Center 逐页对照

页面名与步骤号对齐官方
[step-by-step submission guide](https://learn.microsoft.com/partner-center/marketplace-offers/add-in-submission-guide)。

| Partner Center 页 | 填什么 | 从哪儿拷 |
|---|---|---|
| **新建 offer**（Step 1-2） | 类型选 Office Add-in；名称 `AI WorkDeck`；选 publisher | `listing.en-US.md` → Name。publisher 名必须与 `<ProviderName>` 一致 |
| **Product setup**（Step 3） | Apple Store：**不勾**。Entra ID / SSO：**不勾**。Requires additional purchases：**勾**。Lead management：不接 | 本文件 §0 |
| **Packages**（Step 4） | 上传 `dist-deploy-intl/manifest.xml` | §1 出包 |
| **Properties**（Step 5） | Categories（1-3 个）、Industries（0-2 个）、EULA 链接、隐私政策链接、支持链接 | `listing.en-US.md` → Categories / Industries；链接见 §0 |
| **Marketplace listings**（Step 6） | Manage additional languages → 勾 English (United States) 与 Chinese (Simplified) | 清单里两个 locale 都有 Override（`npm test` 守着） |
| **Marketplace listings → en-US**（Step 7） | Name / Summary / Description / Search keywords / Store logo / Screenshots | `listing.en-US.md` + `assets/store-logo-300.png` + `screenshots/` |
| **Marketplace listings → zh-CN**（Step 7） | 同上，中文那份 | `listing.zh-CN.md` |
| **Availability**（Step 8） | 首发日期。**首次发布后不能改** | 维护者定 |
| **Review and publish → Notes for certification**（Step 9） | 审核员操作说明 + 测试账号 | `certification-notes.en.md`，把 `{{REVIEW_EMAIL}}` / `{{REVIEW_CODE}}` 换成真值 |

### 字段上限（官方原文，2026-09-16 核）

来源：[Create effective listings](https://learn.microsoft.com/partner-center/marketplace-offers/create-effective-office-store-listings)
的 "Apply guidelines for name and description length"。

| 字段 | 上限 | 建议 | 关键信息放在 |
|---|---|---|---|
| Name | 50 字符 | 30 字符 | 前 30 字符 |
| Summary | 100 字符 | 70 字符 | 前 30 字符 |
| Description | 10,000 字符（同页正文另写 "maximum length for descriptions is 4,000 characters"，冲突——我们按 4,000 控） | 300-500 词 | 前 300 词 |

图片：

| 图 | 规格 | 来源 |
|---|---|---|
| Store logo（Large） | PNG，正方形，216x216 ~ 350x350；Partner Center 自动派生 48x48 与 90x90 | 我们出 300x300，见 `assets/` |
| Screenshot | PNG，**1366x768**，单张 ≤1024 KB，至少 1 张最多 5 张 | [Craft effective store images](https://learn.microsoft.com/partner-center/marketplace-offers/craft-effective-appsource-store-images)：`Provide at least one image that is 1366w x 768h pixels and no greater than 1024 KB` |
| 清单内图标 | `IconUrl` 必须 **32x32**，`HighResolutionIconUrl` 必须 **64x64**（任务窗格加载项那一档） | 同上 create-effective-listings 的图标表 |

> `HighResolutionIconUrl` 原先指 `icon-80.png`（80x80），本卡改成 `icon-64.png`，
> 并由 `scripts/manifest-locale.test.mjs` 钉住，防止改回去。

---

## 3. 政策对照表

政策原文：[Microsoft Marketplace certification policies](https://learn.microsoft.com/legal/marketplace/certification-policies)，
1100（Microsoft 365）与 1120（Office Add-ins）两节。

### 1120.1 Offer requirements

| 条 | 要求 | 我们的状态 | 证据 |
|---|---|---|---|
| Support URL | add-in only 清单要有有效 `SupportUrl` | 合规 | `build-manifest.mjs` 的 `BRAND_SITE_BY_ADDIN_HOST` 把 `addin.workdeck.ai` 映射为 `https://www.workdeck.ai`，落进产物清单的 `<SupportUrl>` |
| 高分辨率图标必备 | `HighResolutionIconUrl` 必须有 | 合规 | `manifest.xml`；尺寸已按 64x64 修正 |
| SourceLocation 指向有效网址 | — | 合规 | `https://addin.workdeck.ai/office-addin/taskpane.html`，§1 里有逐条 200 自检 |
| office.js 必须用微软托管的最新版 | `https://appsforoffice.microsoft.com/lib/1/hosted/office.js` | 合规**当且仅当不用 `--china` 出包** | `taskpane.html` 源文件永远指全球版；`--china` 只在世纪互联私有分发里换 CDN。**提交包不许带 `--china`** |
| 用最新正式版（非 preview）清单 schema | — | 合规 | add-in only 清单，`appforoffice/1.1` + `VersionOverridesV1_0` |
| 更新要递增版本号 | — | 待办 | 当前 `<Version>1.0.0`。每次重新提交必须 +1 |

### 1120.2 Mobile requirements

不适用：不提交 iPad。这不只是省事——政策明文禁止移动端出现 in-app purchase、升级引导或指向线上
商店的链接，而我们的任务窗格账户菜单里恰恰有「充值」入口与余额显示（`taskpane/lib/site.js` 的
`rechargeUrl` / `openExternal`）。要上 iPad 就得把那条入口在移动端整条藏掉，另开卡做。

### 1120.3 Functionality

| 条 | 要求 | 我们的状态 | 证据 / 风险 |
|---|---|---|---|
| 浏览器兼容 | Edge / Chrome / Firefox / Safari(macOS) 最新版 | 合规（预期） | 任务窗格是 Vue3 + Vite，`build.target` 未特化；**未逐浏览器实测**，见 §5 |
| Hosts 里写的宿主都要能用 | `Document` / `Workbook` / `Presentation` | 合规 | 三宿主各有 ribbon 按钮与各自的 `office_*` 工具集（Word/Excel/PPT 面），见 `taskpane/lib/officeExecutor.js` |
| 平台覆盖：Office on web 与 Mac 都要能用 | — | 合规（预期） | 同一份 Office.js 代码；**Mac 与网页版要出截图，走查卡 dev-board#697** |
| 触屏设备无键鼠也要能用 | — | 合规（预期） | 全部交互是点击/轻触；文字输入靠系统软键盘。**未在触屏实测** |
| HTTPS 有效证书 | — | 合规 | `addin.workdeck.ai` 走 nginx + 正式证书 |
| SSO 必须有 fallback | — | 不适用 | 不用 SSO，账户登录是自有账号体系 |

### 1100.x（Microsoft 365 通则）

| 条 | 要求 | 我们的状态 | 证据 |
|---|---|---|---|
| 1100.1 额外收费必须在描述里披露 | `Your offer description must disclose any app or add-in features or content that require an extra charge` | 合规 | 两份 listing 的 Description 都有「安装前须知 / Before you install」整段，写明需要账户、AI 用量扣 Credits、充值链接 |
| 1100.1 必须勾 in-app purchase 复选框 | Product setup 的 `requires purchase of a service or offers additional in-app purchases` | 待勾 | §0 与 §2 的 Product setup 行 |
| 1100.1 首次使用体验 | 要求用户登录之前，价值主张必须已经说清 | 合规 | 未登录空态是欢迎卡，先讲能做什么再请登录（`i18n.js` 的 `signInWelcomeTitle` / `signInWelcomeHint`） |
| 1100.5 HTTPS | — | 合规 | 同 1120.3 |
| 1100.5 不得申请过高权限 | 不得 full-control | 合规 | `<Permissions>ReadWriteDocument</Permissions>`，是编辑文档所需的最低档；不是 `ReadWriteMailbox` 那类 |
| 1100.5 图标尺寸正确 | — | 合规 | 32x32 / 64x64，见 §2 图片表 |
| 1100.7 清单里必须声明语言支持，且 offer 主语言在其中 | `The primary language selected when you submit your offer must be one of the specified supported languages` | 合规 | **本卡做的就是这件事**：清单 `DefaultLocale=zh-CN` + 每个可本地化元素挂 `en-US` Override，由 `scripts/manifest-locale.test.mjs` 守住 |
| 1100.7 各语言体验要大体一致 | — | 合规 | 任务窗格 i18n 双语平铺（`taskpane/lib/i18n.js`），`i18n.test.js` 静态扫描钉住模板里不许有裸中文 |
| 1100.7 不得与已提交的加载项重复 | — | 合规 | 首次提交，无同名 offer |
| 1100.8 改价不得影响存量用户 | — | 不适用 | 首次提交 |
| 清单：隐私政策要点名本应用、不能是 Terms of Use、不能 404 | — | 合规（已实测） | `https://www.aiworkdeck.com/en/legal/privacy` 2026-09-16 实测可达，标题 `Privacy Policy · AI WorkDeck`，正文有一句 `This policy covers the AI WorkDeck website, desktop application, Office add-ins and related services.`；与服务条款是两个独立页面 |
| 清单：EULA 必须是 https URL | — | 合规（已实测） | `https://www.aiworkdeck.com/en/legal/terms` 实测可达，标题 `Terms of Service · AI WorkDeck`，含 Credits 计费条款 |
| 清单：支持链接必须是 URL 不能是邮箱 | — | 合规 | `https://www.workdeck.ai` |

---

## 4. 维护约定

- **清单的 en-US Override 由 `office-addin/scripts/manifest-locale.test.mjs` 守着**，`npm test` 跑。
  往 `<Resources>` 里加任何 `bt:String`，都必须同时加 en-US Override，否则测试红。
- 改商店文案时**两份 listing 一起改**，并回来核字数（脚本见每份文件头部的上限表）。
- 重新提交前把 `<Version>` 递增（1120.1）。

## 5. 未验证 / 待办

- **Publisher name 未定**，必须与 `<ProviderName>AI WorkDeck</ProviderName>` 逐字一致，且建完不能改。
- **Partner Center 实际上传校验没跑过**：字段上限、分类名、关键词条数都是按文档准备的，以
  Partner Center 现场为准；分类与行业的可选项列表以页面上的实际下拉为准。
- **截图没拍**，见 `screenshots/README.md` 与 dev-board#697。
- **浏览器矩阵与触屏没实测**（1120.3 的两条）。
- **审核员测试账号没建**，`certification-notes.en.md` 里的第 2 步要按实际登录 UI 重新核一遍
  （见该文件开头的红字提示）。
- 清单里**没有 `<Requirements>` 元素**，所以校验器判定支持面一直到 Office 2013 与 iPad。
  代码里各 API 都有运行时降级守卫，但「全平台都要能用」这条（1120.3）在老版本上没有实测覆盖。
  真被审核员在 Office 2016 上打回时，正确做法是补 `<Requirements>` 收窄声明面，而不是改代码。
