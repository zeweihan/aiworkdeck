# Partner Center 开发者账号申请指南（Office 插件上架用）

日期：2026-08-06。依据 Microsoft Learn 官方文档现行版本整理。

目标：注册 Partner Center 并加入 **Microsoft 365 and Copilot program**——这是把 Office 插件（Word/Excel/PPT Add-in）提交到 Microsoft Marketplace（原 AppSource）的唯一通道。**只有公开上架需要这个账号**；开发调试（sideload）和律所集中部署都不需要。

## 〇、开始前必须备齐的材料

| 材料 | 说明 |
|---|---|
| Microsoft Entra ID 工作账户 | 即「工作或学校账户」。不能用个人邮箱（Gmail/QQ/个人 Outlook）。如果公司还没有 Entra 租户，注册流程里可以现场创建；已有 Microsoft 365 企业订阅的话直接用管理员账户 |
| 公司法定名称与注册地址 | 必须与营业执照完全一致，验证环节要核 |
| 主要联系人 | 可以是申请人本人；「验证用邮箱」必须是公司域名邮箱，域名与公司不匹配会被要求补域名归属证明 |
| 签署授权 | 申请人必须有权代表公司接受协议（Microsoft Office Agreement / Publisher Agreement / MAICPP Agreement） |
| 建议：D-U-N-S 编号 | 企业验证首选通道，自动化快；中国公司可向邓白氏中国免费申请（约 1-2 周）。没有 DUNS 则走上传官方文件（营业执照）人工审核 |
| 政府签发证件 + Microsoft Authenticator | 部分账户会被要求身份验证（verified credential）：护照/身份证 + 手机装最新版 Authenticator。**证件姓名必须与 Partner Center 账户姓名同语言且完全一致** |

## 一、注册流程（新账户）

1. 打开 Office 专用注册入口：`https://partner.microsoft.com/dashboard/account/v3/enrollment/introduction/office`
2. 用工作账户登录（或按引导创建新工作账户）。用已有工作账户登录可把公司邮箱域挂到 Partner Center，之后同事都能用各自工作账户登录。
3. 填写 **publisher profile**：公司信息、发布者信息（这个 Publisher 名会显示在商店里，且必须与插件 manifest 的 ProviderName 一致）、联系信息。
4. 阅读并接受 **Microsoft Office Agreement**；如果是首次加入 Microsoft AI Cloud Partner Program（MAICPP），同时接受 **MAICPP Agreement**。点 **Accept and continue** 完成建户。
5. 如果租户下已有 Partner Center 账户，下拉列表会让你选择关联到已有账户。

已有 MAICPP 账户的替代路径：登录 Partner Center → 右上角 Settings（齿轮）→ **Account settings → Programs** → 在 **Microsoft 365 and Copilot** 磁贴点 **Get Started** → 转到 **Identifiers → Publisher** 标签页 → **Add publisher** → 选 Microsoft 365 and Copilot → 选要关联的 PartnerID、填公司名 → 接受 Microsoft Publisher Agreement。

## 二、验证环节（主要耗时点）

提交后进入账户验证，进度在 **Account settings → Legal info → Verification Summary** 页跟踪。会核验四件事：

1. **Email ownership**：验证用邮箱的归属（公司域名邮箱直接过；域不匹配要补文件）。
2. **Employment**：申请人确实受雇于该公司（工作邮箱域是主要依据）。
3. **Business**：公司法定注册真实有效——D-U-N-S 自动核验最快；否则上传营业执照等官方文件走人工。
4. **Mandatory due diligence**：合规尽调，**阻塞步骤**，不过就不能继续。

要点：

- 自动验证几秒到一分钟；进人工审核一般 **2-5 个工作日**（文件路线最长 3-5 天），期间可离开页面等邮件通知。
- 每类验证最多 **3 次申诉**机会，材料务必一次备准。
- 中途修改公司名/地址/邮箱会**重启整个验证**，此前申诉记录清零。
- 被要求身份验证（verified credential）时，须在 **30 天内**完成：Authenticator 领取可验证凭据 → 回 Partner Center 出示。

## 三、完成确认与后续

1. 验证全绿后，在 **Settings → Account settings → Programs** 确认 **Microsoft 365 and Copilot** 显示为已注册。
2. 记下 Publisher 名——插件 manifest 的 `ProviderName` 必须与它一致，否则提交被拒。
3. 之后即可在 Marketplace offers 里创建 Office Add-in offer 提交审核。提交时准备：唯一 GUID、支持 URL、完整测试说明/测试账号、图标（URI 可缓存）、勾选 "additional purchase required"（因为我们收费走自有解锁门体系）。
4. 费用：新流程**无注册费**；我们只上架免费插件，不涉及商店分成与 payout profile。

## 四、常见卡点速查

| 卡点 | 处置 |
|---|---|
| 用了个人邮箱 | 换公司域名工作账户，个人账户不支持 |
| 证件姓名与账户不一致 | 先在 Partner Center 改账户信息再做身份验证 |
| 无 D-U-N-S、营业执照人工审核慢 | 提前 1-2 周申请免费 DUNS；或备好营业执照扫描件（信息与填报完全一致） |
| Publisher 名与 manifest ProviderName 不一致 | 提交前对齐两处 |
| 审批期不可控 | 账号申请与插件开发并行启动，勿等开发完再办 |

## 五、参考

- Open an Office account: https://learn.microsoft.com/partner-center/marketplace-offers/open-a-developer-account
- Verification process: https://learn.microsoft.com/partner-center/enroll/understand-the-verification-process
- Identity verification (verified credentials): https://learn.microsoft.com/partner-center/enroll/complete-identity-verification
- 提交前检查清单: https://learn.microsoft.com/partner-center/marketplace-offers/checklist
