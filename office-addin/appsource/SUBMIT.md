# Partner Center 提交操作手册（AI WorkDeck Office 插件，首次提交）

按页面顺序走，每一步写「点哪里、填什么、从哪个文件拷」。本文件对应的材料都在本目录：
`listing.en-US.md`、`listing.zh-CN.md`、`assets/store-logo-300.png`、`screenshots/`、`certification-notes.en.md`。
入口：https://partner.microsoft.com/dashboard/marketplace-offers/overview → 找到 offer「AI WorkDeck」。
左侧导航就是下面的章节顺序：Product setup → Packages → Properties → Marketplace listings → Availability → Review and publish。
每页填完都点右上角 **Save draft**。

---

## 1. Product setup

| 项 | 怎么填 |
|---|---|
| Publisher | 已建的 `AI_WorkDeck`，不动 |
| Apple Store / iOS（iPad 支持） | **不勾**，Apple ID 留空 |
| Microsoft Entra ID / SSO | **不勾** |
| Requires additional purchases（in-app purchase） | **勾**。AI 用量按站内 Credits 计费 |
| Lead management / CRM 连接 | 不接（None） |

## 2. Packages

已上传并通过校验（AppDomain 报错已修）。若页面仍显示旧的报错条目，点 Remove 后重新上传
`~/Downloads/manifest.xml`（与线上 https://addin.workdeck.ai/office-addin/manifest.xml 同一份）。
状态变绿即可，不要上传别的文件。

## 3. Properties

| 项 | 填 |
|---|---|
| Categories（1–3 个） | 按 `listing.en-US.md` 的 Categories 段：Productivity；Compliance & Legal；Content Management（下拉里名字略有出入就选最接近的） |
| Industries（0–2 个，可选） | Professional services / Legal（同上按下拉选） |
| Legal → License agreement | 选 **Use my own EULA**，链接填 `https://www.aiworkdeck.com/en/legal/terms` |
| Privacy policy link | `https://www.aiworkdeck.com/en/legal/privacy` |
| Support link / Help link | `https://www.workdeck.ai` |

## 4. Marketplace listings

### 4.1 先加语言
点 **Manage additional languages** → 勾 **English (United States)** 和 **Chinese (Simplified)** → Update。
列表里会出现两行，分别点进去填。

### 4.2 English (United States)

| 字段 | 从哪拷 | 注意 |
|---|---|---|
| Name | `listing.en-US.md` → Name | 上限 50 |
| Summary（Short description） | → Summary | 上限 100 |
| Description | → Description 整段 | 上限 4,000；支持简单 HTML，直接粘 |
| Search keywords | → Search keywords，逐个填 | 最多 3 个（表单给几个槽填几个） |
| Store logo（Large 300x300） | `assets/store-logo-300.png` | 48/90 会自动派生 |
| Screenshots（最多 5 张，1366x768） | `screenshots/` 里选 5 张，顺序即展示顺序，见 4.4 | 每张要填一行 caption |
| Video | 留空 |

### 4.3 Chinese (Simplified)
同样五个字段，从 `listing.zh-CN.md` 拷。logo 同一张；截图可复用英文那 5 张（Partner Center 每种语言各传一遍）。

### 4.4 截图顺序与 caption（英文列表）

| 序 | 文件 | Caption |
|---|---|---|
| 1 | `01-word-mac-tracked-changes.png` | Ask in plain language, get edits back as tracked changes you can accept or reject |
| 2 | `02-word-mac-comment.png` | Add comments and questions anchored to the exact sentence |
| 3 | `03-excel-mac-total-row.png` | Works in Excel: add totals, format headers, read ranges |
| 4 | `04-powerpoint-mac-new-slide.png` | Works in PowerPoint: summarise a deck into a new slide |
| 5 | `06-word-web.png` | Same add-in in Word for the web |

中文列表 caption 对应：「用自然语言下指令，改动以修订形式回到文档，可逐条接受或拒绝」「批注精确锚定到句子」
「Excel 里加合计行、设表头格式、读取区域」「PowerPoint 里把整份演示总结成新的一页」「Word 网页版同样可用」。
`05-word-windows-tracked-changes.png` 是第六张备用，Partner Center 只收 5 张；要换的话用它替换第 5 张。

## 5. Availability

- Markets：全部（Select all）。
- Release date：选 **As soon as certified**（首次发布后这一项不能改）。

## 6. Review and publish

1. 页面顶部会列出每页是否 Complete；有黄色 Incomplete 的回去补。
2. **Notes for certification**：把 `certification-notes.en.md` 里 ```text 围起来的那一整段粘进去，
   把 `{{REVIEW_EMAIL}}` 换成审核邮箱、`{{REVIEW_CODE}}` 换成固定验证码（两个值只在你手上，不进仓库）。
   我另外给了你一份已替换好的纯文本，直接粘即可。
3. 点 **Publish**。状态会依次走 Automated validation → Certification → Publish，一般 3–7 个工作日。

## 7. 提交后

- 审核结果发到 Partner Center 注册邮箱。被打回时页面会给出具体政策条号，把原文贴给我，我按条修。
- 审核通过并上架后：把国际站官网 env 里的 `AUTH_REVIEW_ACCOUNT_IDENTITY` 清空（关闭审核账号旁路），这一步我来做。
- 之后每次重新提交前，清单 `<Version>` 必须递增（1.0.0 → 1.0.1）。

## 8. 容易卡住的地方

- 发布者名 `AI_WorkDeck` 与清单 `ProviderName` 「AI WorkDeck」差一个下划线：微软要求 identical or very similar，已按此提交；若以此被打回再改清单。
- 截图上传报尺寸错误：必须精确 1366x768、PNG、1MB 以内，`screenshots/` 里的都已处理成这个规格。
- Description 粘进去后字符超限：英文 3,317 字符、中文 1,562 字符，都在 4,000 以内；若表单按别的口径报超，删掉「Before you install」之后的段落最后一句即可。
