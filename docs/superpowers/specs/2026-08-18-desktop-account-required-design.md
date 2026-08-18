# 官方桌面版必须账户登录

2026-08-18。维护者决定：**官方发布的桌面版不再免账户可用。**
本文是动代码前的设计定稿。前置的登录链路已由官网 PR#66 与桌面 PR#408 完成并合并，
这一期只做「把它变成必经之路」。

## 1. 立项前提的更正（先读这节）

立项书写的是「把 `application-desktop.yml` 的 `security.local-mode: true` 改成 `false`，
保留配置项 + 改默认值即可得到同样的产品结果」。**这条前提不成立，本期不翻这个开关。**

逐条查证 37 处引用后的结论：`local-mode` 不是「要不要登录」的开关，
而是**「这是单机桌面版」的判别位**。把它翻成 `false` 等于告诉整个后端
「这台机器是团队服务器」，当场变的行为：

| 位置 | 现在（true） | 翻成 false 之后 |
|---|---|---|
| `LicenseService.status()` | 解锁门生效，account 模式 30 天离线宽限 | 恒 `unlocked:true` —— **解锁门整体消失**，宽限一起没了 |
| `ExternalProviderResolver.platformAvailable()` | `= localMode` | **平台 AI 通道整条没了**，全部回落 BYOK |
| `ClipboardService.list()` | 免费额度过滤 20 条 | 不再过滤 = 变相全解锁 |
| `StageQuotaService.limited()` | 缓存区额度生效 | `return false` = 同上 |
| `MachineAccountGuard.requireMachineScope()` | 放行 | 要 session + admin —— **PR#408 的 `/api/account/login` 自锁死**（登录端点要求先登录） |
| `LocalModeAccessFilter.doFilter()` | 每请求闸 | 整体短路，2026-08 审计的 F1/F2/F3 三条闸在桌面端失效 |
| `LocalIdentityService.isLocalMode()` | 解析本机工作区 | 不解析 —— **存量工作区成孤儿** |
| `AuthController.issueLocalDeviceToken()` | 本机签发设备令牌（手机端上传） | 直接拒绝 |
| `StorageLocationController.requireLocalDesktop()` | 可改存储位置 | 「该功能仅在本机单机版可用」 |
| `SiteProfileService.pinned` | 站点可切换 | `pinned=true`，切站能力消失 |

还有一条更根本的：翻完之后用户看到的登录框，验的是**本机 H2 库里的 username+password**
（desktop profile 的 `security.admin.initial-password: "123"` + `allow-all-users: true`），
不是官网账户。官网账户与本机 user 表是两套身份，中间没有桥。
要把官网登录接成本机会话，得新写「官网登录成功 → 找/建本机 user 行 → 签发 UserSession」，
而「找哪一行」正是 `LocalIdentityService` 今天在回答的问题。

**所以立项书列的两个难题（离线宽限、存量工作区迁移）不是翻默认值之后需要顺带解决的，
是翻默认值本身造出来的。**

## 2. 真正的免登入口：README 里的公开试用码

今天官方版之所以免账户可用，是 `README.md` 公开了一枚通用试用码
（`AWD-T-AEAW-…`，Ed25519 离线验签，任何人复制即可解锁全功能）。
这是 2026-08-05 商业化改造的既定设计（见 `2026-08-05-commercialization-redesign.md` §1）。

**「必须账户登录」= 关掉这条解锁路。** `local-mode` 是它旁边的无关变量。

## 3. 目标与非目标

**目标**
- 官方发布的桌面版：解锁门只接受账户凭据（手机号/邮箱登录，或手工粘 `awdk_` Key）。
- 商业版 / 私有部署 / 自行 fork：改一行 yml 即完全恢复今天的行为。
- 存量试用用户有明确的、提前告知的过渡期，不出现「升级后打不开」。
- 已登录用户断网可照常开文档编辑；宽限耗尽前有预警，耗尽后有出路。

**非目标（本期不做）**
- 不动 `local-mode`，不动 session/TTL，不动 `LocalModeAccessFilter`。
- 不动平台 AI 通道、免费额度、entitlement 框架。
- 不做存量工作区迁移——不翻 `local-mode` 就不产生这个问题（见 §8）。
- 不做防篡改。**这个闸是默认值不是 DRM**，投入防篡改的收益为零。
- 不动 `awdk_` 手工粘贴路径。它本来就是账户凭据，且团队服务器与私有部署要用。

## 4. 闸的位置与配置项

`backend/src/main/resources/application-desktop.yml` 新增两项，`local-mode: true` 一字不改：

```yaml
security:
  license:
    trial-code:
      # 官方版：试用码不再是解锁路。商业版 / 私有部署 / 自行构建改回 true 即恢复。
      enabled: false
      # 存量 mode=trial 票据的宽限硬期限，与官网手机号补绑同一天（spec 2026-08-17 §5）。
      legacy-grace-until: "2026-09-30"
```

环境变量形式 `SECURITY_LICENSE_TRIAL_CODE_ENABLED` / `SECURITY_LICENSE_TRIAL_CODE_LEGACY_GRACE_UNTIL`
（Spring relaxed binding），发版流水线与 e2e 都靠它覆盖。

`LicenseService` 两处分支：

**`activate(code)`**
```
AWD-T- 开头 且 !trialEnabled  ->  failure(「试用码已停用，请用手机号登录账户」)
其余                          ->  一字不动
```

**`status()` 的 `case "trial"`**
```
trialEnabled          -> unlockedStatus("trial","trial",...)          （fork/商业版，行为不变）
!trialEnabled 且未到期 -> unlockedStatus(...) + daysRemaining + graceKind="legacyTrial"
!trialEnabled 且已到期 -> unlocked:false + mode:"trial" + 出路文案
```

`legacy-grace-until` 解析失败或为空时**按已到期处理**（安全侧默认；配错不会变成永久宽限）。

## 5. 状态契约

`GET /api/license/status` 加三个字段，只增不改：

| 字段 | 类型 | 含义 |
|---|---|---|
| `trialCodeEnabled` | boolean | unlock 页据此决定要不要渲染试用码标签 |
| `daysRemaining` | int / 缺省 | 存量 trial 倒计时，或 account 离线宽限剩余天数 |
| `graceKind` | `legacyTrial` \| `offlineReverify` \| 缺省 | 顶栏挂哪句话 |

`daysRemaining` 只在**需要提醒时**出现：`legacyTrial` 全程带，`offlineReverify` 仅剩 ≤7 天时带。
不需要提醒时三个字段里只有 `trialCodeEnabled`，前端逻辑最短。

非 local-mode（团队服务器）分支一字不动：仍恒返回 `{unlocked:true, mode:"account", plan:"paid"}`，
不带这三个字段。

## 6. 六种状态与各自的出路

| 场景 | 判定 | 用户看到 |
|---|---|---|
| 全新安装 | `mode=none` | unlock 页，「账户登录」为主标签（PR#408 已做） |
| 存量试用，2026-09-30 前 | `mode=trial` | 照常用；顶栏 chip「试用版 · 剩 N 天」，点开说明去登录 |
| 存量试用，已到期 | `mode=trial` | unlock 页，文案说明原因 + **数据都在本机、一条没丢** |
| 已登录，断网 30 天内 | `mode=account` 宽限内 | 无感，照常开文档 |
| 已登录，宽限剩 ≤7 天 | `mode=account` | 顶栏 chip「需联网验证 · 剩 N 天」 |
| 已登录，宽限耗尽 | `mode=account` 超期 | unlock 页，两条出路：联网重登 / 内网用户联系 `hi@aiworkdeck.com` 要离线授权 |

离线宽限**不用新做**：`LicenseService.OFFLINE_GRACE` 已是 30 天，锚点 `lastVerifiedAt`
由 `reverifyOnStartup` 在每次联网启动成功复验时重置。笔记本每月联一次网就永不过期，
飞机与短期内网完全不受影响。本期只加「剩 ≤7 天」的预警窗与到期文案。

`hi@aiworkdeck.com` 出现在被锁在门外的用户眼前，与 2026-08-17 spec §10 是同一条风险：
**这个邮箱必须真的有人看。** 上线前确认值守，或接进反馈收件箱由优化者分诊。

## 7. 前端改动面

**`pages/unlock/unlock.vue`**（PR#408 已有 login / code 两个标签）
- `trialCodeEnabled === false` 时：`code` 标签改名为「账户 Key」，输入框 placeholder 与说明只提 `awdk_`。
- 用户仍粘了 `AWD-T-` 时后端会拒，内联展示后端文案（不新造前端校验，避免两处判据漂移）。
- `trialCodeEnabled === true` 时（fork/商业版）：一字不改。

**`pages/project-overview/project-overview.vue`** 顶栏
- 复用已有的 `.trial-chip` / `.account-chip` 与它的点击说明弹窗，加第三个 warning 态。
- `graceKind=legacyTrial` → 「试用版 · 剩 N 天」，弹窗正文换成「过渡期说明 + 去登录」。
- `graceKind=offlineReverify` → 「需联网验证 · 剩 N 天」，弹窗正文给两条出路。
- 不新造组件。

**文案**：`locales/zh-CN` 与 `locales/en-US` 两份，过 `check:locales` 的 parity 校验。
错误文案可以含「登录」——`api.js` 的掉线判定在 PR4-0 已改为只认 `code === 4010`，
中文子串匹配那条老红线已作废。

## 8. 存量工作区：零改动，且这是结论不是省略

`LocalIdentityService` 照常解析本机工作区，项目与文件一行不动。
用户升级后登录，看到的仍是升级前的全部项目。

原立项担心的「登录进来看到空列表」，成因是假定了 `local-mode: false`
（那会让 `getUserIdFromSession` 不再解析本机用户、`/api/local-identity/status` 回 `localMode:false`）。
不翻这个开关，这条路径根本不进入。

账户（`account.json`）是**机器级凭据**，与「哪个 user 行拥有项目」是两个正交的维度，
本来就不该合并——这也是 `LocalIdentityService` 的持久化刻意放在 `SystemSetting`
而不是 `~/.aiworkdeck/*.json` 的原因（指针要和它指向的库同生共死）。

## 9. README 与自行构建

- 删 `README.md` 的公开试用码段（当前 :73-79），换成「注册账号（手机号）即可使用」+ 指向官网 `/start`。
- 加一节告诉自行构建者：改 `security.license.trial-code.enabled` 一行即恢复离线试用。
  AGPL 项目不能只把门关上不给钥匙。
- `README.zh-CN.md` 同步（英文是 `README.md`，中文是 `README.zh-CN.md`）。

## 10. 上线顺序（硬约束）

关试用码**必须**在官网手机号登录与桌面账户登录都真正可用之后。反了就是
「关掉旧路、新路没通」，所有新用户进不来——`PhoneLoginGuard` 那条
「宁可拒启也不要静默锁人」的同款风险。

2026-08-18 10:26 核实的前置状态：

| 前置 | 状态 |
|---|---|
| 官网 PR#66（手机号登录 + `exchange-key`） | 已合并 02:16Z，自动部署完成 |
| 北京官网服务器 `AWD_SMS_*` 四个变量 | 10:19 注入，进程 10:20 重启已带上 |
| 官网 `/account` 手机号登录表单 | 线上可见 |
| 桌面 PR#408（`AccountService.loginWithPhone` + unlock 登录标签） | 已进 master |

桌面端无法在启动期验证官网可达（离线是正常态），所以这条只能靠发版顺序 + §11 的 e2e 保证。

**发版前仍需一次人工确认**：用真实手机号在官网走一遍收码登录。
密钥已注入 ≠ 短信真能送达。

## 11. 测试

**`mvn test` — `LicenseServiceTest` 新增**
- `trialEnabled=false` 时 `activate("AWD-T-…")` 被拒，且**未写 license.json**。
- `trialEnabled=true` 时试用码照常解锁（fork 路径不回归）。
- 存量 `mode=trial` 在 `legacy-grace-until` 前一天 → `unlocked:true` + `daysRemaining`。
- 存量 `mode=trial` 在到期当天与之后 → `unlocked:false`。
- `legacy-grace-until` 为空 / 格式非法 → 按已到期处理。
- `mode=account` 宽限剩 8 天 → 不带 `graceKind`；剩 7 天 → 带 `offlineReverify` + `daysRemaining`。
- `mode=account` 超 30 天 → `unlocked:false`，文案含出路。

**`app-e2e` — J1 重构（两处既有地雷一并处理）**

地雷一：J1 用 README 那枚真试用码解锁（`run.mjs:142` 硬编码），关掉之后 120 条全过不了第一道门。
地雷二：J1 会 `deactivate` 长驻的真实桌面后端（默认 9696），原状态是账户模式时还原不回去
——`run.mjs` 头部自己记了这条已知伤。

做法：J1 改为给自己 `spawnBackend` 一个隔离后端（J11 已有这套基建，带 `extraArgs`
与隔离 `user.home`/H2/cwd），用**发版默认值**跑门的断言：
- 试用码被拒 + 内联报错文案
- 「账户登录」标签在位且为默认标签
- 存量 trial 宽限边界（用 `-Dsecurity.license.trial-code.legacy-grace-until=` 注入过去/未来两个日期各跑一次）

J2-J12 继续跑在原后端上，**不再被 deactivate**。

实际落地时把「自起隔离后端」这一步省掉了：unlock 页是一个普通页面，直接导航进去
就能验门的形态，不需要先把机器锁上。破坏性链路（deactivate → 重新解锁）改为只在
**两个条件同时成立**时才跑——试用码这条路还开着（否则解锁不回来，长驻后端会被打成砖），
且原状态不是账户模式（那种 deactivate 之后还原不回去）。两条都不成立时走非破坏性
分支并 `note('skip')`，不静默假绿。

**不用 `trial-code.enabled=true` 糊绿 e2e。** 那等于被测对象唯一覆盖的那个默认值反而没人测
（见记忆 `feedback-no-bypass-for-flaky-gates`）。

**实现时发现的新约束**：发版默认值下，全新 `user.home` 起来的后端是 `mode=none`，
而本套件没有任何办法解锁它（唯一的路是账户凭据，要真实手机号与官网）。
冷启动跑法必须先往隔离 `user.home` 里播一份**存量 trial 票据**——那是真实存在的
过渡期状态，不是绕过闸，且顺带让顶栏 chip 断言覆盖到「试用版 · 剩 N 天」。
配方已写进 `frontend/tests/app-e2e/run.mjs` 头部。

**其余**：`check:emits`、`check:locales`（新增文案两语言）、`build:h5`。

## 12. 不做的事（复述，防止实施时漂移）

不动 `local-mode`；不动 session 与 TTL；不动 `LocalModeAccessFilter` 与 `LocalModeLoopbackGuard`；
不动 `PhoneLoginGuard`；不动 `DataInitializer`；不动平台 AI 通道、免费额度与 entitlement；
不做工作区迁移；不做防篡改；不动 `awdk_` 手工粘贴路径。
