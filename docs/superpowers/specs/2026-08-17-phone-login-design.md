# 手机号登录（大陆站强制）

2026-08-17。维护者决定：**大陆站强制手机号 + 验证码登录，国际站保留邮箱。**
本文是动代码前的设计定稿，实施拆分见末节。

## 1. 现状（2026-08-17 逐条查证，不是凭印象）

比立项时以为的完整得多，**大部分原语已经存在**：

| 已有 | 位置 |
|---|---|
| 用户名 + 密码登录 | `POST /api/auth/login` |
| 官网账户桥 | `POST /api/auth/awdk-login` |
| **邮箱验证码免密登录** | `POST /api/auth/mail-login/send-code` + `/mail-login/verify` |
| 短信作为登录**第二因素** | `SmsAuthService.requiresCode/sendLoginCode/verifyLoginCode` |
| 绑定手机号 | `POST /api/auth/sms/bind`（`sendBindCode` + `confirmBind`） |
| TOTP 双因素 | `POST /api/auth/totp/*` |
| 验证码存储 | `service/auth/VerificationCodeStore` |
| 发送限流 | `AuthAbuseGuard.checkCodeSendRate(ip)` / `recordCodeSend(ip)` |
| 短信网关抽象 | `SmsGateway`（阿里云 dysmsapi）+ `TwilioSmsGateway`（境外，壳） |
| 用户实体字段 | `email` / `verifiedEmail` / `phone` / `password` / `totp*` |
| 站点分叉 | `service/site/SiteProfile*` |

**缺的只有一件事：手机号不是主登录方式。** 现在它是「已登录用户的第二因素」，
不能拿手机号直接进来。

### 短信通道的真实状态

| | 大陆 | 国际 |
|---|---|---|
| 阿里云签名「京微资易科技」 | **已过审**（`QuerySmsSign` 状态码 1） | — |
| 模板 `SMS_483655011` | **已过审**（状态码 1），内容即验证码模板 | — |
| 服务器密钥 | **两台云后端都没配**，`sms.enabled` 默认 false，网关是暗的 | 没配 |
| 通道 | 注入密钥即可用 | **不存在**（Twilio 账号未开，阿里云国际短信未开通） |

国际站发不出短信，是「大陆强制、国际保留邮箱」这个决定的根据。

## 2. 目标与非目标

**目标**
- 大陆站：手机号 + 验证码成为**唯一**的账号登录入口（新注册与老用户都是）。
- 国际站：邮箱验证码登录不变，不受影响。
- 移动端首次打开走这条登录，登录后列出账号下的桌面端与项目，选一个作归档目标。

**非目标（这次不做）**
- 不做国际短信。等 Twilio 或阿里云国际短信开通了再单开一期。
- 不动 TOTP。它是登录之后的第二因素，与主登录方式正交。
- 不动 `awdk-login` 账户桥与设备令牌（`awdt_`）。手机端上传仍用设备令牌，
  只是令牌的签发前提从「密码登录」变成「手机号登录」。
- 不动 local-mode（桌面端单机免登），那条路径没有登录环节。

## 3. 两站分叉

分叉点复用现成的 `SiteProfile`，**不新造开关**：

| | 大陆站（cn） | 国际站（intl） |
|---|---|---|
| 主登录 | 手机号 + 短信验证码 | 邮箱 + 邮件验证码 |
| 密码登录 | **关闭** | 保留 |
| 手机号 | 必填、唯一、即账号 | 可选，仅作二次验证 |
| 邮箱 | 可选 | 必填、唯一、即账号 |

对应 [[dual-site-architecture]] 的既有口径：两站账户完全独立，分叉只在 `site-config`。

## 4. 新增端点

照 `mail-login` 的形状加一对，**不发明新范式**：

```
POST /api/auth/sms-login/send-code   { phone }        -> { code, message }
POST /api/auth/sms-login/verify      { phone, code }  -> 会话 + { isNewUser }
```

`send-code` 的实现逐条对齐 `mailLoginSendCode`：先 `authAbuseGuard.checkCodeSendRate(ip)`，
再发，再 `recordCodeSend(ip)`。

**注册与登录合一**：手机号没见过就建号（`isNewUser=true`，前端引导补昵称），
见过就登录。不设独立注册入口——多一个入口就多一处枚举面。

## 5. 存量用户迁移

大陆站老用户很少，维护者定的口径是**硬期限，不搞长期并行**：

- **即刻起到 2026-09-30**：密码登录仍开，但登录后 `phone` 为空就弹窗强制绑定，
  绑完才能继续用（复用现成的 `/sms/bind`）。弹窗不可跳过，但可以关掉窗口退出登录——
  不做「本次不再提示」，否则到期那天一片哀嚎。
- **2026-09-30 之后**：未绑定的账号一律拒绝登录，文案指向 `hi@aiworkdeck.com`。

期限写成配置项 `auth.phone-binding-deadline`（默认 `2026-09-30`），不硬编码在代码里，
到期前要延期改配置即可。

新注册从一开始就只能用手机号，不进入上面这条迁移路径。

## 6. 降级与失败分支（漏掉任何一条都是「所有人登不进来」）

| 情况 | 行为 |
|---|---|
| `sms.enabled=false` 或网关未配 | cn 站**不得**关闭密码登录。启动时校验：cn + 强制手机号 + 网关不可用 = 拒绝启动，而不是静默降级成谁都进不来 |
| 阿里云返回失败（余额、限流、号码非法） | 明确文案区分「这个号码不对」与「我们这边发不出去」，后者提示稍后再试并留人工通道 |
| 用户收不到码 | 60 秒后可重发；连续 3 次失败提示发邮件到 `hi@aiworkdeck.com`，不要让人无限点 |
| 换号 / 号码已停用 | 发邮件到 `hi@aiworkdeck.com`，管理员在后台代绑。**代绑必须留痕**（谁在什么时候把哪个账号从哪个号改到哪个号），这是账户接管的最短路径，没有留痕就没有追责依据 |
| 验证码错误 | 计次，5 次锁定该手机号 15 分钟（锁号不锁 IP，避免同一办公室互相拖累） |
| 短信轰炸 | 现成的 `AuthAbuseGuard` 按 IP 限流之外，再加按手机号限流（同号 1 分钟 1 条、1 小时 5 条） |
| 号码枚举 | `send-code` 对「号码存在与否」返回**同样**的响应与耗时，是否新用户只在 `verify` 成功后才透露 |

## 7. 三端改动面

**后端**（`backend/`）
- `SmsAuthService` 加 `sendSigninCode(phone)` / `verifySignin(phone, code)`（对照 `MailAuthService`）
- `AuthController` 加两个端点
- 启动期校验（见 §6 第一行）
- `User.phone` 加唯一约束 + 迁移脚本处理历史重复值

**官网**（`aiworkdeck_website`，两站同一份代码、按 `NEXT_PUBLIC_AWD_SITE` 分叉）
- 登录页按站点渲染手机号或邮箱表单
- **改了 `NEXT_PUBLIC_AWD_SITE` 必须重新 build**，`pm2 restart --update-env` 不够

**桌面端**（`frontend/`）
- 登录面板同上分叉
- 补绑期的强制绑定弹窗

**移动端**（`aiworkdeck-mobile`）
- 首屏登录（手机号 + 验证码）
- 登录后拉账号下的桌面端与项目列表，选归档目标
- 拿设备令牌，之后上传用它

## 8. 部署

大陆站要做的（我可以自己办完）：
1. 建 RAM 子用户 `awd-gateway-sms`，只授 `AliyunDysmsFullAccess`（对照现有的
   `awd-gateway-asr` / `awd-gateway-ocr` 的最小权限做法）
2. 密钥写进北京 `/opt/aiworkdeck/cloud/env`（0640），注入 `SMS_AUTH_ENABLED=true`
3. 重启 `aiworkdeck-cloud`，用真实号码实测一条

**新加坡那台不注入**，国际站不走短信。

## 9. 已定（2026-08-17 维护者拍板）

- **一号一账号**：一个手机号只能对应一个账号。`User.phone` 加唯一约束。
- **迁移硬期限 2026-09-30**，见 §5。
- **人工通道走邮件** `hi@aiworkdeck.com`，管理员后台代绑，操作留痕。

## 10. 一条需要维护者确认的风险

`hi@aiworkdeck.com` 这次会出现在**被锁在门外的用户**看到的文案里。
记忆 [[website-legal-docs]] 明写过「要真的有人看 hi@aiworkdeck.com」——
2026-09-30 之后这个邮箱如果没人看，被锁的用户就是真的进不来。
上线前要么确认有人值守，要么把它接进反馈收件箱由优化者分诊。
