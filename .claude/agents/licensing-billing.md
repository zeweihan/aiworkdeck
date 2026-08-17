---
name: licensing-billing
description: 授权与计费领域。任务涉及解锁门（试用码/账户 Key）、与官网账户的连接、entitlement 权益判定、免费额度与付费解锁、平台 AI 通道计费、广场付费项闸门、本机免登身份解析时，先读本文档再动代码。
---

# 授权与计费 领域地图

职责边界：**谁被允许用、用多少、钱怎么记**。包括桌面解锁门、账户连接、entitlement 框架、
本地 SKU 的免费额度执行、平台 AI 通道的取 key 与对账、广场付费项闸门、单机免登的身份解析与准入闸。
不含：广场的页面形态与安装链路本身（plugin-marketplace）、AI 编排与供应商路由的其余部分（ai-chat）、
剪贴板/缓存区/存储位置这些功能自身的实现（utility-tools）、协作与团队案件库（version-control）。
这四个领域各自的文档里都有本领域的落点，跨领域任务两份都读。

商业化改造（2026-08-05/06）由六个已合并 PR 落地：PR-A #247（去登录 + 解锁门）、PR-B #248
（entitlement + 账户连接 + 平台 AI 通道）、PR-C #249（剪贴板/缓存区免费额度 + 自选存储位置）、
#250（身份解析修正）、PR-D #251（广场付费项）、PR-E #252（协作 UX，本领域只涉术语）。
总 Spec 见 `docs/superpowers/specs/2026-08-05-commercialization-redesign.md`，
**Spec 与实现有偏差时以代码为准**，偏差逐条记在该文件末尾「实现与 Spec 的偏差记录」一节。

## 关键文件地图

**解锁门（PR-A）**
- `backend/src/main/java/com/checkba/service/LicenseService.java` — 授权状态机与落盘。两条解锁路
  （试用码离线验签 / 账户 Key 在线校验）、`~/.aiworkdeck/license.json`（0600）、30 天离线宽限、
  启动期机会性复验（`reverifyOnStartup`，后台线程，失败静默）。
- `backend/src/main/java/com/checkba/service/TrialCodeVerifier.java` — 试用码纯函数验签（无 Spring 依赖）。
- `backend/src/main/resources/license/trial-public-key.pem` — 内置 Ed25519 公钥
  （**与插件 registry 签名是两套独立密钥对**，别混用）。
- `backend/src/main/java/com/checkba/controller/LicenseController.java` — `/api/license/{status,activate,deactivate}`，
  **匿名端点**（解锁前没有身份可言）。activate 里粘 `awdk_` 会顺带 `AccountService.connect()` 一步到位；
  连接失败不回滚解锁但**必须可见**：响应附 `accountConnected` / `accountNotice`，unlock 页弹提示（2026-08）。
  `status` 除 LicenseService 的 `{unlocked,mode,plan}` 外另附**展示口径** `accountConnected` + `edition`（paid|trial|none，
  `resolveEdition`：账户已连或 mode=account → paid）——授权票据与账户连接是两条独立状态，先试用码解锁、后连账户的用户
  mode 永远停在 trial，界面判「试用版/正式版」**一律读 edition 不读 mode**（userprofile 授权行、顶栏 chip 已改）。
  组合是只读的，**绝不回写 license.json**（改写会抹掉试用码票据，断开账户即掉回未解锁）；
  非 local-mode 不查 AccountService（机器级状态 + 本端点匿名，照查等于泄露给匿名请求），edition 恒 paid。
- 前端：`frontend/src/pages/launch/launch.vue`（启动分流页）、`pages/unlock/unlock.vue`（解锁页）、
  `pages/identity/identity.vue`（本机工作区选择页）。

**本机免登身份（PR-A + #250）**
- `backend/src/main/java/com/checkba/service/LocalIdentityService.java` — local-mode 下「本机用户」的解析：
  已持久化的选择 → `username=local` → 按数据量判定（唯一有数据的直接选中；多个有数据则待选定；全空库复用空 admin）。
  选择结果落 `SystemSetting` 的 `local.identity.selectedUserId`。
- `backend/src/main/java/com/checkba/controller/LocalIdentityController.java` — `/api/local-identity/{status,candidates,select}`，同为匿名端点。
- `backend/src/main/java/com/checkba/config/LocalModeAccessFilter.java` — 免登模式的每请求准入闸（回环校验 + 反代痕迹拒绝 + 跨站 Origin 硬拦截）。
- `backend/src/main/java/com/checkba/config/LocalModeLoopbackGuard.java` — 启动强不变式：local-mode 必须绑回环地址，否则拒绝启动。
- `AuthController.getUserIdFromSession()` 在 local-mode 下把任何请求解析为本机用户（90 余处调用方一行未改）。

**账户连接（PR-B）**
- `backend/src/main/java/com/checkba/service/account/AccountService.java` — 与官网账户的唯一连接方式是 `awdk_` Key。
  `~/.aiworkdeck/account.json`（0600）；`connect/disconnect/status/fetchProfile/fetchEntitlements/fetchLedger/fetchAiUsage/fetchAiKey/currentKeyOrNull`。
- `service/account/AccountTransport.java` + `HttpAccountTransport.java` — 出站 HTTP 缝（单测打桩不依赖网络）；固定 HTTP/1.1。
- `service/account/AccountEndpoint.java` — 授权服务器地址的协议校验（https，回环 http 例外），`LicenseService` 与 `AccountService` 共用。
- `service/account/AccountException.java` — `Kind`：NETWORK / UNAUTHORIZED / CONFLICT / NOT_CONNECTED / MALFORMED。
- `backend/src/main/java/com/checkba/controller/AccountController.java` — `/api/account/{status,connect,disconnect,usage}`。
- `service/account/AccountSwitchCleanup.java` — **换账户后作废动作的唯一出口**（`afterConnect` / `afterDisconnect`）：
  权益缓存 + 平台 AI 密钥缓存 + 余额判定 + 用量基线四样一起清，disconnect 还要 `demotePlatformProvider()`。
  连接账户有**两个**入口（设置页 `AccountController.connect`、解锁页 `LicenseController.activate` 粘 `awdk_`），
  动作抄两份必然漏（见地雷 22）。新增第三条连接路径时接这里。
- `AccountService.accountFingerprintOrNull()` — 账户指纹（Key 的 SHA-256 前 12 位）的**唯一定义**。
  机器级缓存都是账户级内容，换账号必须作废；指纹单向、可比对可进日志，不受「别把 Key 拿出去传」的限制。
- 前端：`frontend/src/pages/admin/admin.vue` 的「账户与用量」分区（连接/断开、余额、AI 额度、最近用量、
  本机工作区切换、文件缓存区存储位置）；顶栏 chip 在 `project-overview.vue`（已连接账户优先于试用版标识）。

**Entitlement 框架（PR-B）**
- `backend/src/main/java/com/checkba/service/entitlement/EntitlementService.java` — **`isEnabled(feature)` 是唯一出口**。
  来源并集 = 本地票据（LicenseService 解锁 → `app.unlocked`）∪ 账户同步（缓存 `~/.aiworkdeck/entitlements.json`）。
- `service/entitlement/FeatureCatalog.java` — 中央功能目录，桌面端唯一的 feature 名字来源。
- `backend/src/main/java/com/checkba/controller/EntitlementController.java` — `GET /api/entitlements`（`?refresh=true` 显式同步刷新），**要求登录**。
- 前端：`frontend/src/composables/useEntitlement.js`（模块级单例 + 在途去重）、`components/UnlockHint.vue`（统一解锁引导）。

**本地 SKU 的额度执行（PR-C，实现细节见 utility-tools.md）**
- `backend/src/main/java/com/checkba/service/ClipboardService.java` — 剪贴板查询侧过滤（`FREE_MAX_ITEMS=20` / `FREE_RETENTION_DAYS=3`）。
- `backend/src/main/java/com/checkba/service/quota/StageQuotaService.java` — 缓存区移入时拦截（`FREE_MAX_FILES=20` / `FREE_MAX_BYTES=500MB`）。
- `backend/src/main/java/com/checkba/exception/StageQuotaExceededException.java` + `GlobalExceptionHandler`（code=4003 + feature + usage）。
- `backend/src/main/java/com/checkba/service/storage/StorageLocationService.java` + `controller/StorageLocationController.java` — 自选存储位置（需 `stage.unlimited`；GET 与 reset 不设权益闸）。

**平台 AI 通道与计费（PR-B，编排侧见 ai-chat.md）**
- `backend/src/main/java/com/checkba/service/ai/PlatformAiChannel.java` — **取 key 的唯一路由出口**。
  机器级路径（缓存 `~/.aiworkdeck/platform-ai-key.json`，0600）与 per-user 路径在这里分叉，
  `keyFingerprint()` 供模型实例缓存失效。缓存文件带 `owner`（签发它的账户指纹），
  归属对不上一律丢弃重取（见地雷 22）。`usesMachineKey(userId)` 是余额闸的适用判据。
- `service/ai/PlatformCreditsGate.java` — **余额闸**：确知 Credits 为 0 时不让这一轮跑起来，
  由 `ChatModelFactory.platformApiKey()` 在取 key 之前调用（那是平台通道每条消息的必经点）。
  三条判据见地雷 23。
- `service/ai/PlatformUsageAccountant.java` — 平台通道真实扣费对账（`GET https://openrouter.ai/api/v1/key` 累计消费差分），
  基线按**密钥指纹**分桶、worker 按指纹分片；兼做吊销探测（401/403 即作废本地密钥）。
- `service/ai/ChatModelFactory.java` — `Provider.AWD_CLOUD` 路由；`demotePlatformProvider()` 在断开账户时把供应商降级回落。
- `model/entity/TokenUsage.java` 的 `costSource`：`platform`（真实扣费）/ `estimate`（BYOK 单价表估算）。
- 前端选平台通道有**两个**入口，前置条件相同（已连接账户 + 已分配额度）但**闸门形态不同**，别照抄：
  - `pages/admin/admin.vue` 的 `aiProviderOptions`——缺条件时展示但 `unavailable`，`hint` 指出下一步
    （「需先连接账户」/「需先在官网分配额度」）；
  - `pages/wizard/wizard.vue` 的 `providerOptions`——`AWD_CLOUD` **恒可选**，选中就地展开连接块把条件补齐，
    闸门挪到 `handleSubmit`（见下方地雷 15：向导里的每一条「下一步」都必须能在向导里做完）。
    向导刻意不预选任何供应商，见下方地雷 14。
- 供应商自 2026-08 收敛为**三档**：`AWD_CLOUD`（平台通道）/ `OPENROUTER`（自备 Key）/ `OLLAMA`
  （本地，离线实验档，只支持 ASK）。`GEMINI` 已下线（Google Key 三个字段与 `external.google.*` 键一并删除，
  Gemini 系列模型经 OpenRouter 的 `google/*` 仍可用）。两个入口的取值合法性由
  `AdminConfigController.toSettingsUpdates` 统一校验（非三档枚举直接 400），
  存量 DB 里的 `ai.activeProvider=GEMINI` 由 `ChatModelFactory` 的启动期迁移改写成 `OLLAMA`。
- AI 相关设置的唯一写入口仍是这两处，写入的键：`ai.activeProvider`、`ai.defaultModel`、`ai.auxModel`、
  `ai.subagentModel`（留空 = 继承 `ai.auxModel`）、`ai.networkRegion`（auto/domestic/international）、
  `ai.ollama.baseUrl` / `ai.ollama.modelName`。三个模型键**空串是合法值**（= 跟随内置默认），
  非空必须在 `AllowedModels` 白名单内，否则报 400 而不是让工厂静默回落。两个入口保存后都调
  `chatModelFactory.clearCache()`。
- `toSettingsUpdates` **跳过 null 字段**（不再把同组其余字段写成空串）：`SystemSettingService` 只在
  行不存在时回退默认值，空串会被当成真实配置，历史上会把 env 提供的 baseUrl / 走
  `wizard/reset` 重跑向导的正确 baseUrl 清掉（受害者 QichachaService / TushareService / TtsService）。
  代价是「清空某字段」需要显式传空串——admin 页整表回传不受影响。

**广场付费项（PR-D，链路见 plugin-marketplace.md）**
- `backend/src/main/java/com/checkba/service/market/MarketPurchaseGate.java` — Skill 与插件两条安装链路共用的付费判定单一出口。
- `service/market/RegistryReply.java` — `httpGet(url, bearer)` 缝的返回值（状态码交调用方判，402 不在缝里抛）。
- 前端 `frontend/src/utils/marketPricing.js` — 价格展示与状态判定的唯一出口。

**server 模式加固（插件云后端，2026-08-06）**
- `backend/src/main/java/com/checkba/service/AuthAbuseGuard.java` — 注册闸（`security.registration-mode: open|closed`，默认 open）+ 登录失败锁定（IP+用户名 5 次失败锁 10 分钟）+ 注册按 IP 限频（10/小时）。进程内内存计数，**多实例部署必须前置 nginx limit_req**；local-mode 全部旁路。
- `backend/src/main/java/com/checkba/service/account/AwdkLoginService.java` — `POST /api/auth/awdk-login`（匿名端点，AuthController）：awdk_ Key 调官网 `/api/account/me` 实时校验 → `account_binding` 映射（键是官网稳定 `accountId`，官网侧已实施并进了权威契约与 contract-check）→ 首登 `UserService.registerExternal` 建无密码用户（`awd_` 前缀）→ `DeviceTokenService.issue` 签发 awdt_ → **顺手为该用户取一把 per-user 平台 AI key**（`PlatformAiKeyService.tryProvision`，失败绝不拖垮桥接）。开关 `security.awdk-login-enabled` 默认 false。
- `backend/src/main/java/com/checkba/service/account/MachineAccountGuard.java` — server 模式下 `AccountController` 全部端点与 `GET /api/entitlements` 仅 admin 可用（账户连接/权益缓存是机器级状态，普通租户 disconnect 一下全服平台 AI 通道就断）；local-mode 恒放行一字不动。
- `model/entity/AccountBinding.java` + `repository/AccountBindingRepository.java` — 官网账户 → server 用户映射表；awdk_ 明文**不落库**（每次桥接重验官网）。
- `service/UserSessionService.java` + `model/entity/UserSession.java` — 浏览器登录会话 DB 落库（2026-08-07，
  替代 AuthController 进程内 SESSION_STORE）：哈希落库、7 天滑动过期、lastUsedAt 写回节流（1 分钟）、
  每日定时清理；重启不掉线。awdt_ 设备令牌与 local-mode 免登不走这条路，行为一字未变。

**per-user 平台 AI key（2026-08-07，多租户计费隔离）**
- 设计文档 `docs/superpowers/specs/2026-08-07-per-user-platform-ai-key.md`（含三方案的安全边界比较与已确认决策）。
- `service/ai/PlatformAiKeyService.java` — **per-user 密钥的唯一出口**：`resolve/provision/tryProvision/refresh/evict/markVerified/status`。
  `isBound()`（该用户有 `account_binding`）与 `multiTenant()`（本实例存在任一绑定）是两条路由判据。
- `service/ai/PlatformAiKeyCipher.java` — AES-256-GCM 落库加密，密文形态 `v1:iv:tag:cipher`
  与官网仓 `lib/openrouter-keys.ts` **逐字对齐**；构造器里带**启动强不变式**（见地雷 17）。
- `model/entity/PlatformAiKey.java` + `repository/PlatformAiKeyRepository.java` — 每用户至多一行，
  存密文 + 指纹 + limitUsd + fetchedAt/lastVerifiedAt。**刻意不挂在 `account_binding` 上**：
  那张表是纯身份映射且每次桥接都要读，塞密文会改变它的安全等级。
- `service/ai/PlatformAiUserScope.java` — 「这次调用花谁的额度」的线程作用域。
  设置点共 10 处：`AiAgentController`（对话与 PPT 生成两条异步入口共用这一处 run）、
  `AgentOrchestrator` 四处（handleUserMessage 入口 run、标题生成 runAsync wrap、
  **onComplete / onError 两个回调 run**、LLM 重试 scheduler wrap）、
  **`ToolRegistry.execute`（按 `ctx.userId()` call，2026-08 新增）**、`MemoryPipelineService`、
  `SubAgentService.dispatch`（优先按 `parentCtx.userId()` 显式重建，缺 userId 才回落 `current()`）、
  `MatterClassifierService`、`AutoTaggingService`。**跨线程提交必须 `wrap`**。
  （AiChatService 那一处随 v1 同步对话通道删除消失。）
  ToolRegistry 这一处是 2026-08 补的 P0：工具方法内部发起的 LLM 调用（sub-agent 派发、
  deep_search 的查询扩展）此前跑在没有作用域的线程上，平台通道取不到 per-user 密钥。
- `controller/PlatformAiKeyController.java` — `/api/platform-ai/key/{status,refresh}`，
  **会话级不是机器级**（不走 `MachineAccountGuard`——那道闸管的是整台服务器的账户连接）。
- 插件侧：`office-addin/taskpane/components/SettingsView.vue` 的「AI 额度」卡片
  + `taskpane/lib/api.js` 的 `fetchPlatformAiStatus` / `refreshPlatformAiKey`。

**设备令牌的本机签发（2026-08-07，Office 插件接入）**
- `AuthController.issueLocalDeviceToken` — `POST /api/auth/device-token/issue-local`（body `{name}` 可空）：
  **仅 local-mode**，身份走会话解析（免登下恒为本机用户），签发 `deviceTokenService.issue`，明文只返回一次。
  为什么另开端点：`/api/auth/device-token` 是密码入口（与 /login 同锁定同二次验证闸），本机免登用户没有密码。
  安全边界不新开口子：LocalModeLoopbackGuard + LocalModeAccessFilter 保证只有本机进程可达；
  非 local-mode 回业务错误（团队服务器仍只有账号密码一条路）。护栏 `AuthControllerLocalDeviceTokenTest`。
- 前端 UI：`userprofile.vue`「插件访问令牌」分组（仅桌面显示）：生成（备注名+明文一次性展示+复制）/列表/撤销；
  api.js `issueLocalDeviceToken/listDeviceTokens/revokeDeviceToken`。

**登录二次验证的判定出口（2026-08-07）**
- `backend/src/main/java/com/checkba/service/auth/SecondFactorService.java` — **`required(user)` 是唯一判定出口**，
  返回 `NONE / TOTP / MAIL / SMS`。优先级 **TOTP > 邮箱 > 短信**：TOTP 零成本、无国界、不受运营商报备与
  SIM 交换影响；**邮箱排在短信之前是成本决策**（短信按条计费，邮件几乎免费），短信降为未绑邮箱时的兜底。
  两条密码入口（`/login`、`/device-token`）都只接 `AuthController.secondFactorChallenge()` 这一个私有方法——
  **新增第三条密码入口时必须接它**，漏一条等于没设闸。
- 同文件管认证器绑定：`startSetup`（生成密钥，尚未启用）→ `activate`（验一次码才启用）→
  `disable`（**必须带当前码**，否则被借用的会话能直接摘掉二次验证）→ `resetByAdmin`（认证器丢失的唯一出路）。
- `service/totp/TotpService.java` — RFC 6238 纯算法（HMAC-SHA1，不引依赖），
  护栏是 **RFC 6238 Appendix B 官方测试向量**（`TotpServiceTest`）。测试要造合法码用
  `src/test/.../totp/TotpTestCodes`（放在 totp 包的 test 源码里，刻意不把「凭密钥造码」提升为生产 API）。
- **TOTP 重放拦截**：`User.totpLastUsedStep` 记录已消费的时间片，同一枚码在其 30 秒窗口内只能用一次。
- 端点：`/api/auth/totp/{setup,activate,disable}`（需登录）、`/api/auth/totp/reset/{userId}`（仅 admin）。
- 前端：`userprofile.vue` 设置页「账号安全」的认证器分区（二维码用 `qrcode` 懒加载渲染，
  **otpauth URI 含密钥，只在前端本地转二维码，不走任何图片服务**）；`login.vue` 按 4005 的
  `data.method` 区分 totp/mail/sms 三种步骤（totp 不发码、不显示重发倒计时；**mail 与 sms 的重发要打
  各自的端点**，打错会被判成「未绑定手机号」）。

**登录短信验证（2026-08-06，sms-auth-integration）**
- `backend/src/main/java/com/checkba/service/sms/SmsService.java` — 阿里云 dysmsapi 发送，**刻意不引 SDK**
  （JDK HttpClient 直签 HMAC-SHA1，保补丁通道资格；签名算法有真机对拍向量护栏 `SmsServiceTest`）。
  阿里云原始错误 Message 只进日志不进用户文案。
- `service/auth/VerificationCodeStore.java`（原 `service/sms/SmsCodeStore.java`，2026-08-08 更名）——
  验证码生命周期，**短信与邮件共用一套**：6 位数字、5 分钟 TTL、一次性核销、单码 5 次验错作废、
  60 秒重发冷却、单 target 日上限 10 条；内存只存 SHA-256。发送失败调 `invalidate` 回滚冷却（日配额不回滚）。
  `target` 短信是规范化手机号、邮件是规范化邮箱，两者不会撞键（手机号里没有 `@`）。
- `service/sms/SmsAuthService.java` — 流程编排：scene 隔离（login 码≠bind 码）、号码规范化与校验、
  绑定唯一性（发码与确认两处都查）。`active()` = server 模式 && 任一通道可用；local-mode 恒旁路。
- **通道按号码归属地分流**（`SmsGateway` 接口，2026-08-07）：`SmsService` 收大陆号（阿里云），
  `TwilioSmsGateway` 收境外号（Twilio Messages API，同样不引 SDK）。两条独立开关，只配国内的部署
  遇到境外号会明确回「该号码所在地区暂不支持短信验证」，而不是发出去再失败。
  **存储形态**：大陆号一律 11 位裸号（`+86` 前缀会被剥掉，否则同一个号能因写法不同绕过唯一性绑到两个账号），
  境外号存 E.164。Twilio 侧的各国合规（Sender ID / 10DLC / DLT）在控制台的 Messaging Service 里配，
  代码只认 `messaging-service-sid`——加国家不需要改代码发版。
- `User.phone`（unique 列）+ `UserRepository.findByPhone`。
- AuthController：`/login` 与 `/device-token` 同一道闸——已绑手机号且启用时缺 `smsCode` 回
  **code 4005** + `{smsRequired, phoneMasked}`（与 4001/4003 同族，前端 api.js 据此切验证码步骤）；
  存量未绑定用户不拦。`POST /api/auth/sms/send-code`（scene=login 须带正确用户名密码且与 /login 共用
  失败锁定，**否则就是免锁定的密码试探口**；scene=bind 须已登录）、`POST /api/auth/sms/bind`。
  IP 维度限频在 `AuthAbuseGuard.checkCodeSendRate`（20 条/小时，**短信与邮件共用这一把闸**——
  各开一把等于换个通道就能绕过限频）。
- `/api/auth/me` 多回 `smsAuthEnabled` + `phoneMasked` + `mailAuthEnabled` + `emailMasked`
  （空串=未绑定，Map 不收 null；该 Map 已 12 项，超过 `Map.of` 的 10 对上限，用的是 `Map.ofEntries`）；
  绑定 UI 在 `userprofile.vue` 设置 tab「账号安全」，登录验证码步骤在 `login.vue`。

**登录邮箱验证（2026-08-08，PR#320）**
- `backend/src/main/java/com/checkba/service/mail/MailAuthService.java` — 流程编排，与 `SmsAuthService` 同构。
  三个场景 `mail-bind / mail-login / mail-signin` **互不通用**：绑定码是已登录态下发的，
  若能用于免密登录，等于把低权限操作兑换成完整登录。
- `service/mail/`（`MailGateway` / `SmtpMailGateway` / `DomesticMailGateway` / `GlobalMailGateway` / `MailRouter`）——
  `MailRouter` 按**收件域名**选路，国内主流邮箱走阿里云 DirectMail（`dm.aiworkdeck.com`），
  其余走 Resend（`send.aiworkdeck.com`，兜底通道）。**刻意不用 Spring Boot 的 `spring.mail.*`**——
  那套只装配得出一个 `JavaMailSender`，两条必须并存。`@Order` 钉死顺序，兜底通道排错会把 QQ/163 全吃掉。
- `User.verifiedEmail`（**新增的 unique 列**）+ `UserRepository.findByVerifiedEmail`。
  **不要给资料字段 `User.email` 加唯一约束**：它是历史自由填写的，有重复与空串，
  `ddl-auto: update` 加约束会在脏数据上失败。`verifiedEmail` 只由「收到码并验过」写入。
- 端点：`POST /api/auth/mail/send-code`（scene=login/bind，与短信同构）、`POST /api/auth/mail/bind`、
  `POST /api/auth/mail-login/send-code` + `/verify`（免密登录两步）。
- **免密登录的三条红线**：① 独立开关 `mail.passwordless-login-enabled` 且**默认关**——它是一条新的匿名
  登录入口，邮箱被盗即账号被盗；② 未注册邮箱**不发信但照常返回**，回包对「已注册/未注册」完全一致，
  否则这个端点就是账号枚举器；③ 验码失败**必须计入登录锁定**（锁定键=规范化后的邮箱）——单枚码的
  尝试上限只管那一枚，换一枚重来不受限，不接锁定就是个 6 位码爆破口。
- 配置 `mail.*`（application.yml）：两条通道各自 `enabled/host/port/username/password/from`，
  默认全关。Resend 的 SMTP 用户名是**字面量 `resend`**、密码填 API key；`from` 必须含 `@`
  （回落到 username 会拼出非法发件人，只在发信那一刻才炸，所以 `enabled()` 里就判掉）。
  阿里云那条的密码是控制台「发信地址 → 设置SMTP密码」设的，**不是 AccessKey**。
- 配置 `sms.*`（application.yml）：`SMS_AUTH_ENABLED` 默认 false；AK/SK 走 `SMS_ACCESS_KEY_ID/SECRET`
  环境变量（RAM 子用户仅授 AliyunDysmsFullAccess），签名/模板默认 `京微资易科技`/`SMS_483655011`
  （旧签名 `京微资易` 已在阿里云控制台删除，2026-08-06 重建为新签名）。
  **签名的运营商报备状态是外部前置条件**：2026-08-07 实测联通已通（真机送达），移动/电信报备中，
  未通的运营商发送会 PORT_NOT_REGISTERED；报备状态用 `GetSmsSign` API 可查。

**站点（双主站，2026-08-08）**
- 设计文档 `docs/superpowers/specs/2026-08-08-dual-site-architecture.md`（含实施记录一节，与设计有出入以那节为准）。
- 两个站分的是**商业与合规**：币种、支付通道、发票、适用法、ICP 备案、默认语言、
  telemetry 落点、registry 落点。国内站 `www.aiworkdeck.com`（北京 ECS、微信支付、人民币、中国法），
  国际站 `www.workdeck.ai`（新加坡 ECS、Stripe、美元、香港主体）。**两站账户不互通**是接受的代价。
- **绝不按站点过滤模型清单**：桌面端所有 OpenRouter 请求从用户本机直连 openrouter.ai
  （`ai.model.open-router.base-url`，平台通道 AWD_CLOUD 刻意只读 yml 的 baseUrl），
  能不能用某个模型由出口 IP 决定、由 OpenRouter 运行时返 403，我们没有任何开关能用注册地解锁境外模型。
- `service/site/SiteProfileService.java` — 站点的**唯一解析出口**：`currentSite/profile/baseUrl/
  displayName/availableSites/multiSite/otherSites/isPinned`。`pinnedTo(baseUrl)` 是单测与
  「站点无关」场景的构造入口。
- `service/site/SiteEnvironmentPostProcessor.java` + `resources/META-INF/spring/
  org.springframework.boot.env.EnvironmentPostProcessor.imports` — 启动期按 `site.json`
  改写四个属性（`ai.account.base-url`、`ai.plugins.registry-url`、`ai.skills.registry-url`、
  `telemetry.ingest-url`）。**在属性层解析，是为了让 `service/ai/` 下的三个消费方一行都不用改。**
  属性源插在 `systemEnvironment` 之后：压过 application.yml，输给环境变量（本地联调靠这条）。
- `service/site/SiteStateFile.java` — `~/.aiworkdeck/site.json`，**刻意不依赖 Spring 与 Jackson**
  （要在容器起来之前读一次）。站点是**机器级**状态，与 license/account 同目录同规格；
  刻意不进数据库——`local.identity.selectedUserId` 进数据库是因为它是指向库内 user 表的外键必须同生共死，
  站点描述的是「这台机器面向哪个商业实体」，还原旧库不该把站点还原掉。
- `service/site/SiteSwitchService.java` — 切站编排，**是 `persistSelection` 的唯一合法调用方**。
- `controller/SiteController.java` — `GET /api/site`、`POST /api/site/select`，**匿名端点**
  （选站发生在解锁之前），靠 `LocalModeAccessFilter` 兜着，做法同 `POST /api/license/deactivate`。
- `ai.account.sites.*`（application.yml）— 站点表；`intl.enabled` 在国际站上线前为 false，
  可选站点 < 2 时前端整个不渲染站点 UI。
- 前端：`frontend/src/utils/siteLinks.js`（官网链接的唯一出口，替代 7 处硬编码）、
  `pages/unlock/unlock.vue`（站点行 + 错配一键救济）、`pages/admin/admin.vue`「账户与用量」的站点子区。

**配置**
- `security.local-mode`（`application-desktop.yml:36` 为 true，默认 false = 团队服务器模式）。
- `ai.account.base-url`（`application.yml:97-98`，默认 `https://www.aiworkdeck.com`；**强制 https**，回环 http 例外供本地联调）。
- `security.license.dir`（默认 `${user.home}/.aiworkdeck`）——license/account/entitlements/platform-ai-key/storage-location 五个状态文件都落这里。
- `security.registration-mode`（默认 open）与 `security.awdk-login-enabled`(默认 false)——两者都只影响 server 模式；官方托管的插件云后端应配 closed + true。
- **官方托管实例已上线**（2026-08-07）：addin.aiworkdeck.com，北京 ECS 与官网共机；专用 profile
  `application-cloud.yml`（PG + pgvector、RemoteIpValve——不开的话反代后按 IP 的失败锁定退化成全站一把锁）；
  部署材料与实录见 `deploy/cloud/README.md`。
- `security.platform-key-secret`（`AWD_PLATFORM_KEY_SECRET`，默认空）——per-user 平台密钥的落库加密密钥。
  **`awdk-login-enabled=true` 时必配，缺失直接拒绝启动**（见地雷 17）。
- `sms.enabled`（`SMS_AUTH_ENABLED`，默认 false）——大陆短信通道开关，仅 server 模式生效；官方托管的插件云后端应配 true + 注入 AK/SK 环境变量。
- `sms.intl.enabled`（`SMS_INTL_ENABLED`，默认 false）+ `TWILIO_ACCOUNT_SID/AUTH_TOKEN/MESSAGING_SERVICE_SID`——境外短信通道。
  **认证器（TOTP）不需要任何配置**，server 模式恒可用，是国际用户的推荐路径。

## 核心契约

### 试用码格式与验签

`AWD-T-` + RFC4648 大写 base32（无 padding；连字符只作分组，解析时连同空白一起剥掉，大小写不敏感）。
解码后**恰为 70 字节** = `payload(6B)` + `Ed25519 签名(64B，对 payload 签)`；
`payload = [0x01 版本, 0x01 类型 trial, iat_u32_BE 签发秒级时间戳]`。
公钥内置于 backend resources，**离线验签，解锁全程不联网**。
验签只校验签名与结构，**不校验 iat 过期**——试用码目前是永久有效的公开码（README「获取与解锁」一节公布），
解锁页「获取试用码」按钮（`unlock.vue` 的 `TRIAL_CODE_URL`）指向 `https://github.com/zeweihan/aiworkdeck#readme`，
是整篇 README 的锚点而非节锚点：挪动章节位置不影响它，但**删改 README 里的这枚码，等于弄坏产品里的一个按钮**。
签发脚本在官网仓（PR-W4），改格式必须两仓同步。

### 账户 Key（awdk_）

`awdk_` + 43 字符 base64url。官网只存 SHA-256 哈希，明文只在生成时显示一次，每账户最多 3 把，可随时吊销。
桌面端把它落在 `account.json`，**绝不回给前端**——`AccountService.status()` 只暴露 `keyMasked`
（`awdk_****` + 末 4 位）。需要自行向官网发鉴权请求的服务（当前只有广场付费项下载）走 `currentKeyOrNull()`，
其余一律走 `fetchXxx` 方法。

### 解锁状态与宽限

| 模式 | 判定 | 宽限 |
|---|---|---|
| `trial` | 离线验签通过即解锁，无到期 | 不需要（本就离线） |
| `account` | `POST {base}/api/license/verify-key` 回 `valid:true` 即解锁 | 30 天未联网复验则 `unlocked:false`，提示联网重验 |
| 非 local-mode | 团队服务器部署**不设解锁门** | `status()` 恒 `{unlocked:true, mode:"account", plan:"paid"}` |

在线校验的状态码分类是**跨两个服务共用的一条判据**（`LicenseService.callVerifyKey` 与 `AccountService.handle`）：
4xx = 明确拒绝（清除本地授权 / UNAUTHORIZED），**5xx 与超时 = 不可达**（保留授权，走宽限）。
服务器故障不等于凭据失效——反过来判会让用户一断网就掉线。

### Entitlement 命名空间

| 形式 | 例 | 谁定义 |
|---|---|---|
| 本地功能键 | `app.unlocked`、`clipboard.unlimited`、`stage.unlimited`、`plan.pro`（预留） | `FeatureCatalog` 常量 |
| 付费 Skill | `skill:<id>` | `MarketPurchaseGate.skillFeature` |
| 付费插件 | `plugin:<id>` | `MarketPurchaseGate.pluginFeature` |

三者在官网 `/api/account/entitlements` 的同一个列表里。**新增 SKU 要改三处**：桌面 `FeatureCatalog`、
前端 `useEntitlement.js` 的 `FEATURES`、官网的权益命名表（官网生成兑换码时按同一张表做白名单校验，对不上就是发出去的码兑不了）。
`skill:` / `plugin:` 只能用 `MarketPurchaseGate` 的两个工厂方法构造，**绝不拿广场条目 id 直接当 feature 查**
（一个 id 叫 `clipboard.unlimited` 的 Skill 就能蹭到本地 SKU 的权益）。

`GET /api/entitlements` 的 `features` 与 `catalog` 是两个不同的问题，不许混：
**`features` 只含已拥有的**（「出现在列表里 = 已拥有」是这个字段名唯一自然的读法，
前端 `useEntitlement` 就是这么用的），`catalog` 才是目录全集带 `enabled` 标志。

账户型权益的失效有两条互不替代的路：**联系不上官网** → 30 天宽限，超期整体回落「未拥有」
（不是「保持拥有」，否则永久离线 = 永久买断）；**官网明确拒绝（401/403 = Key 已吊销）** → 立刻清缓存，
不吃宽限（拿宽限兜吊销等于用户止损后付费功能还能再用一个月）。缓存另外只在账户仍连接时生效——
删掉 `account.json` 权益一起失效，避免「手写一份缓存」变成免费解锁。

### 官网 API 契约（摘要）

**权威文档是官网仓 `doc/desktop-contract.md`**（人读版）+ `scripts/contract-check.mts`（机器可执行版），
改端点必须同时改那两处。下面只是桌面侧用到的部分，与权威文档冲突时以权威文档为准。

| 端点 | 鉴权 | 桌面调用点 |
|---|---|---|
| `POST /api/license/verify-key` → `{valid, plan}` | 匿名 | `LicenseService.callVerifyKey` |
| `GET /api/account/me` → `{username, displayName, balanceCents, plan, createdAt}` | Bearer | `connect()` 校验 Key、`fetchProfile()` 取余额 |
| `GET /api/account/entitlements` → `{entitlements:[{feature, purchasedAt, orderId}]}` | Bearer | `fetchEntitlements()` |
| `GET /api/account/ledger?limit=50` → `{entries:[...]}` | Bearer | `fetchLedger()`，桌面只挑 `kind=ai_alloc` 显示 |
| `GET /api/account/ai-usage`(*) → `{configured, hasKey, limitUsd, usageUsd, remainingUsd, keyMasked}` | Bearer | `fetchAiUsage()` |
| `POST /api/account/ai-key` → `{openrouterKey, limitUsd}`（幂等） | Bearer | `PlatformAiChannel.fetch()`；409 `no_allocation` = 还没分配额度 |
| `GET /api/registry/{skills,plugins}/{id}/bundle`、`/file` | 付费项要 Bearer 且已购，否则 402 | `SkillMarketService` / `PluginMarketService` |

(*) `ai-usage` 是唯一一条**权威文档也没收录**的端点：官网仓的 `doc/desktop-contract.md` 与
`scripts/contract-check.mts` 里都搜不到它，实现只在官网仓 `app/api/account/ai-usage/route.ts`
（那里还多返回 `exchangeRate` / `marginMultiplier` / `disabled` 三个字段，桌面端没用）。
上表这一行的字段以该 route 为准；官网仓补齐这条端点 + contract-check 之前，改它两侧不会有任何护栏提醒。

三处与总 Spec §9 字面不同、**以实现与官网契约为准**：`verify-key` 的 `plan` 是 `paid|free`（不是 `trial|paid`）；
`ledger` 返回 `{entries:[...]}`（不是裸数组）；`ai-usage` 这个端点总 Spec 里压根没有。
另外 `POST /api/account/redeem`（兑换码 `AWDG-XXXX-XXXX-XXXX`）**官网已实现但桌面端尚未接入**——
桌面解锁门只认 `AWD-T-` 试用码与 `awdk_` 账户 Key，兑换在官网账户页完成后靠权益同步生效。

`ai-usage` 要按「可能不存在」处理：拿不到只降级额度这一段（`quotaAvailable:false`），
余额与账本照常，前端显示「—」而**不能把 0 当成剩余额度**。

### 计费两套口径不得合并

`TokenUsage.costSource` 区分：`platform`（平台通道，cost 先留空、由 `PlatformUsageAccountant` 异步补真实扣费）
与 `estimate`（BYOK，单价表本地估算，**不是账单**）。`GET /api/account/usage` 把 `local`（本机统计）
与 `platform`（官网结算）分成两段返回，界面上分别标注，不做加总。
cost 为 null 原样保留 —— 对账未完成时显示「待结算」，绝不顶成 0。

## 已知地雷

0. **客户端定期外发通道现有四条**：entitlement 刷新（10 分钟陈旧阈值）、插件吊销名单（24h）、
   启动 license 复验，以及**匿名使用统计上报**（TelemetryUploadService，启动 + 24h，
   POST {telemetry.ingest-url}/rollup 与 /events；开关在 system_setting 的
   telemetry.rollup.enabled 默认开 / telemetry.events.enabled 默认关，设置页「数据统计」可关）。
   README 隐私口径已随 PR 改为「匿名聚合统计默认开启可关闭」，动上报行为要同步 legal/PRIVACY.md。
1. **响应带 code=4010 会被前端当成掉线**。PR4-0 起 `frontend/src/services/api.js` 只认
   code=4010 判定未登录（已不做「登录/未授权/请先」中文子串匹配），命中就清本地会话
   （浏览器端还跳登录页）。账户未连接、未分配额度、付费项未购买全是**业务错误不是掉线**，
   必须走 code=1 信封、绝不带 4010。
   护栏：`AccountServiceTest.accountMessagesDoNotLookLikeAuthErrors`、两个 market 测试里的 `assertNotMistakenForLogout`。
2. **local-mode 与团队服务器模式行为差异是一整套，不是一个开关**。`EntitlementService` 是**按本机**的
   （数据源是 `~/.aiworkdeck` 的 license/account 状态，没有 userId 维度），团队服务器上权益恒为空集。
   因此这些地方必须「非 local-mode 一律不限制 / 恒为正式版」：`LicenseService.status/activate/deactivate`、
   `ClipboardService`、`StageQuotaService.limited()`、`LocalIdentityController` 三个端点、
   `StorageLocationController`（整块仅 local-mode 可用）。**照着执行的话，团队案件库的每个成员都会被截到
   20 条剪贴板 / 20 个文件且永远无法解锁**。反过来，`AccountController` / `EntitlementController` /
   两个 `market/list` 必须**要求登录**——`LocalModeAccessFilter` 在 server 模式整体短路，
   而 `deploy/web/nginx.conf.example` 把 `/api/` 整段反代出去，漏检等于把账户隐私与「断开连接」暴露给匿名请求。
3. **免登消掉了事实上的 CSRF 防线，靠 `LocalModeAccessFilter` 补**。免登前「必须带自定义头 `X-Session-Id`」
   会让跨源请求触发预检而被浏览器拦死；免登后不带任何头也能过鉴权，而 `CorsConfig` 只是不回显 ACAO、
   并不阻断请求本身（multipart 表单提交这类简单请求照样执行到 controller）。过滤器补三条，
   **只在 local-mode 生效，server 模式一字不变**：
   - 非安全方法（非 GET/HEAD/OPTIONS/TRACE）带了 Origin 且不在白名单 → 403（字面量 `"null"` 也不在白名单）。
     **缺 Origin 一律放行**——实测打包态渲染进程（`file://` + `webSecurity=false`）发出的 POST/multipart 完全不带 Origin，
     开发态与 e2e 是 `localhost:5173/5174` 命中白名单。改这条判据前先确认这三种形态都还能通。
   - `remoteAddr` 非回环 → 403（只信 `getRemoteAddr()`，不看 `X-Forwarded-For`）。
   - 出现任何反代痕迹头（`X-Forwarded-*` / `X-Real-IP` / `Forwarded`）→ 403。这不是信任这些头，
     而是把它们的出现当作「这台机器被反代了」的信号（团队版基线里 nginx 与后端同机，反代过来的 remoteAddr 恰是 127.0.0.1）。
   过滤器 order 是 `HIGHEST_PRECEDENCE`，排在 `CorsConfig` 之前——被拒的请求不该拿到任何 CORS 响应头。
   Origin 白名单判据复用 `CorsConfig.isTrustedOrigin`，而它第一行是 `if (allowAll) return true`：
   `security.cors.allow-all=true` 会把上面第一条闸整体打开。该项目前任何 yml 里都没配（默认 false），
   属潜伏逃生门——排查「闸门为什么没触发」时先看它。
   `POST /api/license/deactivate`、`POST /api/local-identity/select` 这类匿名 POST 全靠它兜着。
4. **身份解析在多候选时不猜，交给用户选**。老安装的库里常有多个历史账号，且 `admin` 往往是空壳
   （真机实测：admin 1 项目 0 文件，用户数据 6 项目 21 文件在 `hanzewei` 名下）。回落 admin 会让老用户
   解锁后看到一个近乎空的工作区——**数据没丢，只是全部不可见**，比报错更难排查。
   `localUserId()` 恒返回非 null（90 余处调用方，返回 null 等于全站 500），待选定时临时落在数据量最大的候选上
   但**不写持久化**；真正的门在前端 launch 页读 `needsSelection` 分流到选择页。
   选择结果存 `SystemSetting` 而不是 `~/.aiworkdeck/identity.json`：存的是指向同一个库里 user 表的外键，
   放进被指向的库才能与数据同生共死（还原旧库时指针跟着回退）；license/account 描述的是机器授权，独立于库是刻意的。
   测试账号前缀白名单（`qa_bot_` / `claude-e2e` / `e2e_keepalive`）**保守到只排除脚手架自造账号**，
   真实账号一个都不许被误排。`displayName` 改名收窄到 `username=admin`——改真实账号的名字会让用户在选择页里认不出自己。
5. **免费额度只隐藏、不删数据**。这是本领域最硬的一条红线：剪贴板是**查询侧过滤**（超出的行留在库里，
   解锁后原样可见），缓存区是**移入时拒绝**（区内已有文件一个都不动，移出方向永不拦截）。
   全 diff 里没有任何 delete/purge 路径被额度逻辑触发，任何「顺手清理超额记录」的改动都是回归。
   同理，自选存储位置的迁移是**复制 → 校验 → 换指针，原目录完整保留为备份**，绝不先删后搬。
6. **权益失效不等于把人锁在外面**。`StorageLocationService.applyOnStartup` 是无条件的：Key 被吊销 /
   断开账户 / 离线超宽限之后，数据仍在自选路径上照常读写。所以 `GET /api/storage/location` 与
   `POST /api/storage/location/reset`（恢复默认，只换指针不搬不删）**不设权益闸**，付费闸只留在
   「改到新的自选位置」这一个动作上。设置页整块也不按权益隐藏，未解锁时显示 `UnlockHint` 与只读路径。
7. **付费闸门在官网侧，本地缓存只用于标注**。`entitlements.json` 未签名是**有意接受**的：
   两个本地 SKU 的判定本就完全在本地执行，签名只是抬高门槛；真要钱的两条路各有服务端闸门
   （付费 bundle 下载由官网 402 把关、平台 AI 额度由 OpenRouter 侧的 key limit 强制执行）。
   因此 `MarketPurchaseGate.purchased()` 只做 UI 标注，缓存陈旧时按已购乐观放行、由 402 兜底——
   比在本地拦住一个真已购的用户更不容易出错。
8. **平台 AI 通道取不到 key 时绝不静默回退 BYOK**（会花用户自己的钱）；未连接账户时该供应商展示但不可选，
   点击给引导——隐藏会让用户发现不了，直接可选会拖到发消息才报错。断开账户要 `demotePlatformProvider()`，
   否则界面显示平台通道正常选中、实际每条消息都报未连接账户。
9. **凭据类 JSON 一律用 `AccountService.stateMapper()`**。Jackson 默认开着 `INCLUDE_SOURCE_IN_LOCATION`，
   解析失败时异常 message 带原文片段，而这些文件里都是明文密钥、解析失败点普遍 `log.warn(..., e.getMessage())`——
   一次半截写入就能把 0600 的密钥复制进 0644 的日志。落盘后统一 `restrictPermissions()` 收敛到 0600（Windows 静默跳过）。
10. **本地起官网联调要走回环 http**。`AccountEndpoint.requireSecure` 强制 https 但放行回环
    （`localhost` / `127.0.0.0/8` / `::1`），主机判据用 IPv4 字面量正则而非前缀——`startsWith("127.")`
    会放行 `127.0.0.1.evil.com`。另外 `HttpAccountTransport` 固定 HTTP/1.1：JDK HttpClient 默认 HTTP_2，
    对明文地址先发 h2c 升级，Next 开发服务器收到后不回字节，上层只看到「无法连接服务器」。
11. **广场付费项的价格必须服务端自查**，不信前端传来的 `priceCents`（等于让客户端决定闸门何时生效）。
    价格没查到时本机有 Key 就照带 Bearer（分不清「真免费」与「付费但价格没查到」，不带的话后者官网必 402，
    真已购用户会被反过来指控没付费）。`normalizePrice` 有 ¥100,000 上限——官网写超 int 范围的值会被截断成
    1215752191，原样展示就是 ¥12,157,521.91 的假价。
12. **awdk 桥的映射键只认官网稳定 `accountId`**，缺失时 MALFORMED 拒绝，**绝不回落 username**
    （可改名，改名后会凭空生出第二个 server 用户），也**绝不把官网用户名绑到本服务器已有的
    同名账号**（等于账户接管）——首登一律新建 `awd_` 前缀用户。桥接建的是无密码账户：
    口令列存 `UserService.EXTERNAL_ACCOUNT_MARK` 哨兵 + 随机料，`login()` 见前缀直接拒绝；
    动密码兼容逻辑时别把这个分支删了（删了哨兵检查后还有随机料兜底，但两道都在才算安全）。
13. **锁定拒绝不计入失败计数**。AuthController 里锁定检查（`checkLoginAttempt`）与凭据校验
    分属两个 try：锁定期内的轮询若也 `recordLoginFailure` 会把锁无限续期。同理 awdk-login
    只有官网明确 401/403（UNAUTHORIZED）才计失败，网络不可达不消耗尝试次数。

14. **首启向导不预选 AI 供应商**。曾经预选「本地 Ollama」，没装 Ollama 的用户一路点「完成设置」，
    要到发第一条消息才收到 Connection refused（`ChatModelFactory` 只在 OPENROUTER 下防回退 Ollama，
    反向没有保护）。现在 `activeProvider` 初值是空串、由用户显式选，唯一的例外是「已连接账户且已分配额度」
    时自动预选平台通道——用账户 Key 解锁的人买的就是这条通道，不该再被引导去配别家的 Key。
    向导提交前的空值拦截在 `handleSubmit`，后端 `WizardController` 也拒空 `activeProvider`（两道都在才算数）。
    取值本身的合法性（三档枚举）在 `AdminConfigController.toSettingsUpdates`，两个入口共用。

15. **向导里每一条「下一步」都必须能在向导里做完**。平台通道曾经在未连接账户时置灰 +
    提示「进入产品后在系统管理粘贴 Key」——那是死路：试用码解锁的用户在向导里无论如何都点不亮它，
    只能先随便选一家凑合。现在 `AWD_CLOUD` 恒可选，选中就地展开连接块（`handleConnectAccount` 调
    `POST /api/account/connect`，与 admin 页 `onConnectAccount` 同链路：连接 → 重取状态 →
    `refreshEntitlements(true)`），已连接但没分配额度时给「前往官网分配额度 + 重新检查」两个动作。
    两个前置条件缺任一时 `handleSubmit` 拦住提交并指出下一步——**闸门从「不可选」挪到了「不可提交」，
    不是取消了**。
    同一条规则的第二个实例（2026-08）：选「本地 Ollama」时向导就地探测（`GET /api/ai/ollama/probe`，
    选中即探一次 + 「重新检测」按钮），服务没起或目标模型没 pull 都由 `handleSubmit` 拦住，
    并把 `ollama pull <模型名>` 与下载地址原样摆出来给用户复制。模型名取探测结果而不是前端写死，
    因为它现在可以在 admin 里改（`ai.ollama.modelName`）。探测端点必须允许匿名调用——
    全新安装走向导时还没有任何会话。

16. **全新安装必须先钉 `system.wizard.completed=false`**（`DataInitializer`，仅 admin 不存在时写）。
    `WizardController.isInitialized()` 在标记不存在时退回存量兜底「system_setting 非空即已初始化」，
    而首启链上 `LocalIdentityService.commit()` 解析本机身份就会写下 `local.identity.selectedUserId`
    一行——launch 页查身份在查向导之前，于是**全新安装反而整个跳过首启向导**（真机复现过：
    解锁后直接进个人中心，`ai.activeProvider` 一直空着，要到发第一条消息才发现）。
    护栏 `config/DataInitializerTest`：全新装写标记、存量库一个字都不许改（改了等于把匿名提交窗口重开）。

17. **平台通道的 key 是「谁的额度」，多租户下缺身份必须报错而不是回落**。
    OpenRouter 的额度上限是 per-key 的，一把机器级 key 就是一个共享额度池——回落等于拿别人的钱花，
    且对账差分会把 A 的消费记到 B 头上。`PlatformAiChannel.resolveOrThrow` 的四分支要背下来：
    local-mode 恒走机器级（**一字不动**）；server + 已桥接 → per-user；
    server + **本实例存在任一绑定**（`multiTenant()`）时，未桥接用户与缺身份**一律拒绝**；
    server + 一个绑定都没有的团队服务器 → 机器级路径与改动前逐字一致。
    第三条用 `multiTenant()` 而不是「非 local-mode」来收口，是为了让没人桥接的团队服务器
    不会因为某处漏传身份就被打断——严格判据只落在真正的多租户实例上。
    身份靠 `PlatformAiUserScope`（ThreadLocal），**池线程不会自动继承**（继承的是创建者而非提交者），
    每个跨线程提交点都要 `wrap`；漏一处的后果被设计成「那条路报错」而不是「那条路记错账」。
    新增 AI 入口（新的 @Async / executor.submit / runAsync）时必须一并接上，否则多租户下那条路取不到 key。
    另外 `security.platform-key-secret` 在 `awdk-login-enabled=true` 时**缺失即拒绝启动**
    （`PlatformAiKeyCipher` 构造器）——明文降级是典型的潜伏逃生门，这里刻意不留。

18. **切到平台通道有两个入口，跨境同意闸必须两个都设**。同意（个保法第三十九条）的判定与
    拒绝文案在 `AdminConfigController.crossBorderBlockReason(ai, settings)`（静态，与
    `toSettingsUpdates` 同款），**管理后台 `updateAdminConfig` 与首启向导 `WizardController.initialize`
    都要调它**。这条是踩出来的：闸门最初只在管理后台，而向导走的是 `toSettingsUpdates` 静态映射、
    完全绕过闸门——偏偏向导才是用户选平台通道的主入口（AWD_CLOUD 恒可选，见地雷 15），
    于是勾选框摆在了没人必须经过的页面上，同意形同装饰。
    对应地，向导也必须自带同意勾选框（只加后端闸会让 AWD_CLOUD 在向导里不可提交，违反地雷 15）；
    **两处初值都必须是未勾选**——预先勾选的同意在个保法下无效。
    `crossBorderConsent` 只在选平台通道时才进 payload：其余档位带 `false` 会把已有同意误撤回。
    护栏：`CrossBorderConsentTest`（判定本身：版本作废、文案红线）
    + `CrossBorderConsentGateSharedTest`（这道闸没有第二份实现、没有入口漏掉它）。

19. **切站不是「换个域名」，是换了一个商业实体，清理表必须背下来**（双主站设计 §2.4）。
    删：`account.json`、`entitlements.json`、`platform-ai-key.json`、`license.json` 中 **mode=account** 的票据。
    留：`license.json` 中 **mode=trial** 的票据（试用码是内置公钥离线验签的，与站点无关；
    抹掉等于把一个只想换站看看的试用用户直接踢回未解锁页）、`storage-location`、项目数据库。
    另必须调 `ChatModelFactory.demotePlatformProvider()`——不降级会出现「界面显示平台通道正常选中、
    实际每条消息都报未连接账户」（同地雷 8）。护栏 `SiteSwitchServiceTest`。
    切站的生效范围**有意分成两段**：账户/解锁门当场改指向，广场与统计上报下次启动才改
    （在属性层固化），`select` 因此回 `restartRecommended:true`。

20. **站点错配的文案不许指控用户的 Key**（双主站设计 §2.6）。另一个站的 Key 拿到本站必然
    「verify-key 回 `valid:false`」或「账户端点 401」，而 Key 是好的——只说「Key 无效或已被撤销」
    会让用户去官网重新生成一把，回来再撞一次，且没有任何线索指向真正的原因。
    多站形态下 `LicenseService.invalidKeyMessage` 与 `AccountService.unauthorizedMessage`
    会点名当前站与另一站。**信封照旧不得带 code=4010**（地雷 1；文案写「切换站点后重试」是当年子串判定时代的遗留措辞，保留无害）。护栏 `SiteMismatchMessageTest`。
    **刻意不做「拿同一把 Key 依次探测两个站」**：`awdk_` 是明文 bearer 凭据，
    把它发给一个不是它签发方的服务器等于向第三方泄露一把有效凭据；
    两站由同一团队运营不改变这个判断——今天成立不等于第二方托管实例出现后仍成立。

21. **官网侧的反代绝不能对 `/api/` 做 301/302**。桌面端 `HttpAccountTransport` 用 JDK HttpClient，
    默认 `Redirect.NEVER`；一个 301 会被 `AccountService.handle` 判成「预期外状态」→ MALFORMED，
    用户看到「官网返回了预期外的状态」而无从下手。这条在 workdeck.ai 的新加坡 vhost 上是真实风险：
    那里按访客 IP 做 geo 分流，境内访客默认 301 回国内站，而「注册在国际站、人在境内」是合理场景。
    必须给 `^~ /api/` 与 `^~ /update/` 加优先级更高的 location 绕开 geo 判断。

22. **机器级缓存装的是账户级内容，换账号必须整套作废——而且不能只靠调用方记得**。
    连接账户有两个入口，作废动作原先只写在 `AccountController.connect` 里；
    解锁页粘 `awdk_` 走的 `LicenseController.activate` 只调了 `entitlementService.refreshAsync()`，
    于是从解锁页换一个**没充值的新账号**进来时，上一个账号的平台 AI 密钥、已购权益、用量基线
    三样原封不动留着：新账号照样能花上一个账号的 OpenRouter 额度，也继承了它买过的付费项。
    偏偏解锁页才是主入口（用账户 Key 解锁的人走的就是那条路）。
    修法是**两层都要在**：① 动作收进 `AccountSwitchCleanup`，两个入口共用；
    ② 平台密钥缓存自己记 `owner` 指纹，归属对不上就丢弃重取——
    「记得清缓存」是会忘的，「归属对不上就不认」不会。
    存量缓存文件没有 `owner`，读到 null 视为不匹配、重取一次重新绑定；平台通道本来就要联网打
    OpenRouter，不存在「离线靠这份缓存续命」的场景。护栏：`PlatformAiKeyOwnershipTest`、
    `LicenseControllerEditionTest.activateReportsAccountConnected`。

23. **「没充值不能用」不能只靠取 key 那一刻的 409**。官网对零余额账户在 `POST /api/account/ai-key`
    回 409 `no_credits`，但那只在**本地没有缓存 key** 时才会被问到；本地已经有一把没花完的 key 时
    `PlatformAiChannel.apiKey()` 根本不联网。OpenRouter 侧的 per-key limit 管的是「这把 key 花了多少」，
    不是「这个人还有没有钱」。补这一段的是 `PlatformCreditsGate`，三条判据顺序不能换：
    ① **只管机器级路径**（`usesMachineKey`）——per-user 路径的额度在官网签发 key 时已按人闸住，
    且本端拿不到对方的 awdk_ Key，查也查不了；
    ② **确知为 0 才拦**——`GET /api/account/ai-usage` 在上游不可达时仍回 200 + 真实 `creditsCents`，
    只有网络失败/端点缺失/字段缺失这三种「不知道」一律放行（同地雷 6：权益失效不等于把人锁在外面），
    且**不保留上一次的 0**，否则一次抖动就把刚充完值的人锁住；
    ③ **首次同步、之后后台刷新**（60 秒保鲜）——全新的零余额账户第一条消息就必须被拦住，
    所以第一次不能异步；之后不给每条消息加一次官网往返。
    文案照旧不得含「登录」「未授权」「请先」（地雷 1）。护栏：`PlatformCreditsGateTest`。

## 平台服务网关（2026-08-17 起，分六批 P0-P5）

把「用户自己去 8 家供应商开账号填 23 个字段」收敛成「只填一把官网 `awdk_`」。
设计文档 `docs/superpowers/specs/2026-08-17-unified-credits-service-gateway-design.md`
（含四视角审阅后的修订一，**与实现有出入以代码为准**）。

**通路分三条，不是一条**（改这块前先读设计 §3）：
- **AI/OpenRouter 保持凭证下发 + 桌面直连**，不进网关。那条「所有 OpenRouter 请求从
  用户本机出口发出」的红线不能破——改成从 ECS 代理，第一步就连不上，还会把全体用户的
  模型可用性锁死在我们机房的位置。
- **其余七家走网关代理**（`POST {site}/api/gateway/{service}/{op}`）。它们没有一家支持
  「给终端用户签发带限额的子密钥」，凭证下发等于把公司账号发给所有人。
- **本地模型**（ASR 新增、TTS 的 Kokoro 已有）作隐私档。

**桌面侧文件**
- `service/platform/PlatformGatewayClient.java` — **唯一出站出口**。记账/幂等/错误分类
  三样每接一家都要用，散出去抄到第三家必漏。网络失败**带同一幂等键**重试一次
  （换新键 = 放弃幂等 = 第一次若已扣费就是扣两次）。
  三个入口：`call(service, op, ...)` 通用同步（响应 `{ok,data,billing}`）、
  `postJson(path, ..., idempotent, timeout)` / `getJson(path, timeout)` 给响应形态不同的
  端点用（今天只有语音那条异步链路）、`connected()` 供各服务判 platform 档的「已配置」。
- `service/platform/PlatformGatewayTransport.java` + `HttpPlatformGatewayTransport.java` — 出站缝，
  超时**按服务给**（不沿用 `AccountTransport` 写死的 5 秒，OCR/TTS/听悟建任务超 5 秒是常态）。
- `service/platform/GatewayException.java` — 八档 Kind。**刻意不复用 `AccountException.Kind`**：
  那个把 5xx 一律归 NETWORK、文案「请检查网络后重试」，把我们的故障说成用户的网络问题。
- `service/platform/ExternalServiceProvider.java` — 六家服务的描述表 + 档位枚举。
  语音合成不在其中：云端 ElevenLabs 已整体移除，只剩本机 Kokoro 一条路，没有档可分。
- `service/platform/ExternalProviderResolver.java` — **档位判定的唯一出口**，D5 的闸在这里。
- `service/platform/ExternalProviderBackfill.java` — 存量回填，启动期跑一次。
- `controller/PlatformServiceController.java` — `/api/platform-services{,/{service}/provider}`，
  机器级状态，`MachineAccountGuard` 把关。
- `config/GlobalExceptionHandler` 的 `handleGateway` — `GatewayException` → `code=1` +
  `gatewayKind` + `canUseOwnKey`。**不接这条的话网关异常会落到兜底 handler 被压成
  一句「服务器内部错误」**，三类故障的区分（错误码族存在的全部理由）当场作废。

**其余四家（P4）：分档一律落在各自 service 的一个缝上**
- `service/OcrService.recognizeGeneral` — platform 档走 `ocr/recognize`，完全不碰
  `AliyunOcrClientFactory`；两档产出同一个 `OcrResult(text, raw)`。
- `service/TtsService` — **不分档**：语音合成只有本机 Kokoro 一条路（D7）。
  P4 曾给它接过 `platform | byok | local` 三档，随 ElevenLabs 整体移除一并撤掉。
- `service/QichachaService.fetchEciInfoResult` — 分档的唯一缝，`searchCompany`（DTO）与
  `queryEciInfoJson`（给 AI 的原始 JSON）都从它取数。
- `service/TushareService.callTushare` — 分档的唯一缝（上游本来就只有一个统一入口），
  上面那几个解析函数一行未改。平台档失败**抛出而不是回 null**：null 在上层眼里是
  「这家公司没数据」，会让「未开放/余额不足」伪装成一次查不到。
- `service/ai/tools/LegalTools.callPkulaw` — 法宝的双档分发。platform 档走
  `pkulaw/{工具名}`，**响应正文仍由 `McpResponseParser` 在桌面端解析**（两档共用一个
  解析器，法宝改格式只会改一处）。
- `service/ai/tools/EnterpriseDataTools`（**新**）— `qichacha_query` / `tushare_query`
  两个一等工具，补上 `PythonTools` 停掉的那条路。它们调的是上面两个 service，
  所以**两档都能用**，不是「平台档专用」。
- `service/ai/tools/PythonTools.credentialEnvArgs()` — platform 档下**不注入**
  `TUSHARE_TOKEN` / `QICHACHA_KEY` / `QICHACHA_SECRET`（见地雷 33）。

**配置面（P5，前端）**
- `frontend/src/config/platformServices.js` — 七项服务的**展示元数据**（名字/描述 i18n 键 +
  `LOCAL_TIER_READY`）。权威源仍是后端的 `ExternalServiceProvider.ALL`，本表只补界面叫法；
  后端多出的服务以 key 原样显示，不静默漏掉。`LOCAL_TIER_READY.asr=false` 是**本地 ASR 未随包
  发出**的唯一开关，P3 落地时连同「切换时就地探一次 + 下载模型」一起翻牌。
- `frontend/src/locales/{zh-CN,en-US}/platform.js` — 新命名空间，向导与 admin 面板共用。
- `frontend/src/pages/admin/admin.vue` 的 `platform` 面板 — 七行档位 + 每行的
  「使用自己的 Key（高级）」折叠区（**21 个 BYOK 字段从「系统配置」搬到了这里**，
  `config` 面板只剩 OpenRouter 那两个）。深链 `?nav=platform&service=<key>` 就地展开某一项。
- `frontend/src/pages/wizard/wizard.vue` 步骤 2 — 从三组共 9 个输入框换成「平台服务总览 +
  就地连账户」，默认展开，**不拦提交**（向导只拦 AI 供应商那一项）。
- `frontend/src/components/MeetingRecordingPanel.vue` — 录音开始**之前**显示档位与就绪状态，
  「录音不出本机」开关在本地引擎就绪前一律置灰（理由见地雷 33）。
- `frontend/src/services/api.js` 的 `getPlatformServices` / `setPlatformServiceProvider`。

界面侧的三条硬口径（改这几个文件前先看）：
1. **档位一律读接口的 `provider`**，不按凭证是否为空推断——存量机器上这两件事经常对不上。
2. **`platformAvailable=false` 时「平台代采」这个选项整个不出现**（不是置灰的第三项）并给说明，
   D5 的表达在 UI 上必须是「没有这个选择」，不是「有但点不了」。
3. **切档立刻写库，凭证字段仍走「保存配置」**。两者混在一个保存按钮下，用户点完下拉看不到
   任何变化会以为没生效；切档失败**不改本地状态**，重拉一次让界面回到真相。

**语音转写（P2）**
- `service/meeting/MeetingTranscriptionService.java` — **分档在编排层**。platform 档
  完全不碰 `MeetingOssClient` / `TingwuClient`（那两个实现一字未动，只服务 byok 档）。
  不在接口内部分档：两条路的失败语义完全不同，塞一起错误分类就再也拆不开。
- `MeetingRecording.gatewayTaskId` 是**新列**，与 `tingwuTaskId` 不是一回事，也不许合并——
  一个查 `/api/gateway/asr/task/{id}`，一个查听悟 OpenAPI。**轮询走哪条路由由这两列决定，
  不由当前档位设置决定**：用户转写途中切档，按设置分派就会拿网关的 taskId 去问听悟，
  结果是永远查不到的任务 + 永远结不了的预扣。
- `isConfigured()` 按档分：platform 档「已连账户」即算配好，byok 档仍要那 5 个凭证。
- 直传是**普通 HTTP PUT**（`BinaryUploader` 接缝），OSS SDK 不引到这条路上：签名官网签好，
  客户端只负责发字节。`Content-Type` 进了 OSS 签名，必须逐字用 ticket 下发的那个值。

**官网侧文件**（`aiworkdeckweb`，PR #56 = P0，#57 = P2，#58 = P4）
- `lib/gateway/{errors,config,pricing,idempotency,spend,asr}.ts`
- `lib/gateway/adapters/`（一家一个文件 + `index.ts` 汇总）：`search / ocr / tts /
  qichacha / tushare / pkulaw`。adapter **只做两件事**：参数 → 上游请求、上游响应 →
  `{units, data}`；记账/幂等/定价一律在 route 里统一处理。`asr` 不在注册表里（异步长任务，走自己的三端点）
- `app/api/gateway/[service]/[op]/route.ts`、`app/api/gateway/pricing/route.ts`、
  `app/api/gateway/asr/{ticket,submit,task/[id]}/route.ts`
- 迁移 11：`service_pricing` / `gateway_request` / `gateway_hold` 三张表 + 重建 `wallet_ledger`
  扩 `service_spend` kind；迁移 12：`gateway_hold.meta`（JSON，长任务自己的状态，语音存 objectKey）；
  迁移 13：五家的定价行（`INSERT OR IGNORE`，绝不覆盖线上已调过的价），
  **企查查与法宝的 `enabled` 初值是 0**
- 过期 hold 的回收挂在已有的 `POST /api/admin/ai/reconcile` cron 上，
  **回收前先跑 `sweepExpiredAsrObjects()` 删中转音频**（置 released 之后就查不到对象键了）
- `ali-oss` / `@alicloud/tingwu20230930` / `@alicloud/ocr-api20210707` / `@darabonba/typescript`
  必须进 `next.config.ts` 的 `serverExternalPackages`：
  它们底层用运行时 require 加载可选依赖，打包器静态分析不到，直接把 build 判成失败
- 验证 `scripts/verify-gateway.mts`（63 项，已进 CI）

### 网关的核心契约

| 事项 | 规则 |
|---|---|
| ledger kind | **只加一个 `service_spend`**，服务名进 `meta.service`。加 kind 要同步改三处，而服务会一直加 |
| 计价 | `service_pricing` 是唯一权威。**客户端既不传价格也不传计量**——申报值只用于预扣估算与余额闸，受 `maxUnitsPerCall` 约束 |
| 扣费时机 | 同步调用「预检余额 → 按上游真实计量事后扣」；预扣只用于异步长任务（今天只有 asr） |
| 预扣 | 三行账 `hold` → `hold_release` → `settle`，**②③ 同事务**；`gateway_hold` 落库 + 服务端超时回收 |
| 余额不足 | `409 no_credits`，**绝不 401/403** |
| 幂等 | 会扣费的 POST 必须带 `Idempotency-Key`（8-128 位 `[A-Za-z0-9_-]`），服务端去重回放 |
| 语音 | 唯一的异步长任务，三步：`asr/ticket`（查余额 + 签直传凭证，不扣费不要幂等键）→ 桌面直传 OSS → `asr/submit`（预扣 + 建听悟任务）→ `asr/task/{id}`（轮询 + 结算 + 删对象）。**余额闸在 ticket**，不让用户白传两小时录音才被拒 |

同步入口的 service/op 全集（人读版在官网 `doc/desktop-contract.md`，改端点两处同步）：

| service/op | 计量单位与真实来源 |
|---|---|
| `search/web` | `call`，一次调用 = 1 |
| `ocr/recognize` | `page`，一次调用 = 一页（RecognizeAllText 是按图片的接口，返回里没有可计费页数字段；**刻意不拿 `subImageCount` 顶替**，那是区域数会成倍多收） |
| `tts/speech` | `kchar`，取上游 `character-cost` 响应头；缺失时按服务端数出的**实际送出文本**长度（不是另报的数字） |
| `tts/voices` | `call`，定价 0。**必须单列一行**，落到 kchar 通配行上就是「列个音色也按合成价收钱」 |
| `qichacha/eci_info` | `call`，一次调用 = 1 |
| `tushare/query` | `call`，一次调用 = 1 |
| `pkulaw/{search_article,get_article,get_law_list,law_recognition}` | `call`，一次调用 = 1。op 就是法宝的工具名，**MCP 端点写死在服务端** |

### 已知地雷（网关）

24. **平台网关只在 local-mode 开放（D5），不是遗漏**。非 local-mode（团队自建服务器、
    `addin.aiworkdeck.com` 云实例、Office 插件）恒 `byok`，与改造前逐字一致。
    原因：`awdk_` 明文永不落库，server 侧对已桥接用户根本没有可打网关的 Bearer 凭据
    （`PlatformAiUserScope` 给的是 userId 不是凭据）；AI 能做 per-user 是因为 OpenRouter
    支持签发子密钥，而其余七家没有。硬用机器级 Key 顶上 = 全体租户共花公司账户的 Credits，
    一个租户写脚本刷就是刷我们自己的钱。闸在 `ExternalProviderResolver.resolve` 一处，
    不要在调用点各判一遍。

25. **存量档位必须显式回填，不能靠默认值**。`SystemSettingService.get(key, default)` 只在行
    不存在时回落，存量库里没有 `external.<service>.provider` 这一行，升级后一律静默取
    新默认值 `platform`——而用户填过的 23 个字段一个没丢，就在库里躺着却不再被用。
    两类用户同时坏：自带阿里云 OCR/Tushare 订阅的律所**为同一项服务付两遍钱**；
    从未连账户的用户看到「昨天好好的」变成「余额不足」。
    `ExternalProviderBackfill` 在启动期跑一次：已有非空 BYOK 凭证 → byok；都没有 → platform。

26. **曾经还有一步「档位自身的 yml/env 默认值优先」，那一步只为 TTS 存在**
    （打包态注入 `EXTERNAL_TTS_PROVIDER=local`，而 `system_setting` 里没有这一行；
    只按凭证推断会写成 platform，本地引擎当场失效——一次静默的功能回归）。
    语音合成移除云端档后没有服务再需要它，已连同那个注入一起删除。
    **将来若有服务再引入 env 级档位默认值，这一步要连同它一起加回来**，否则会重演那次回归。

27. **平台档失败绝不静默回落 BYOK**（同地雷 8）。回落会去花用户自己的 Key。
    正确做法是给出可读的失败原因 + 「改用自己的 Key」的指路。
    `GatewayException.suggestsByok()` 决定要不要摆这个入口，**除「Key 无效」与我们自己的
    参数 bug 外一律摆**——尤其 `NOT_CONNECTED`：用试用码解锁、根本不打算连账户的用户
    （README 公开试用码是主要获客入口），自备 Key 是他唯一的出路，只提示「去连账户」等于把他堵死。
    AI 工具里的网关调用**不抛异常打断整轮对话**：返回一段说明文本让模型基于已有信息继续，
    与「未配置」那条既有分支同一口径。

28. **网关不可达的文案必须明说「不是你的网络问题」**。账户通道那句
    「无法连接 AI Workdeck 服务器，请检查网络后重试」会让用户去重启路由器，
    而真实原因往往是我们正在发版。三类故障（未开放 / 上游挂 / 我们挂）在用户眼里长得一样，
    下一步却完全不同，必须分开。护栏 `PlatformGatewayClientTest.unreachableSaysNotYourNetwork`。

29. **预签名 PUT 绑不住请求体大小**。OSS 的 v1 与 v4 签名都只覆盖
    方法 / 对象键 / Content-Type / 有效期，**没有任何字段能约束 Content-Length**，
    所以「申报 1 分钟传两小时」这个绕过余额闸的做法只能在服务端拦：
    `/asr/submit` 先 HEAD 一次对象、按申报时长复核体积，超了就删对象再拒。
    ticket 下发的 `maxUploadBytes` 只是给客户端自检用的，不是闸。

30. **听悟的 `GetTaskInfo` 不返回时长**。它只有 `taskStatus` 和四个结果 URL，
    所以「计价以上游真实计量为准」这条红线在语音上的落点是
    **从转写结果里最后一个词的 `End` 时间戳算真实分钟数**
    （`actualMinutesFromTranscription`）。取不到时回落到预扣估算——宁可少收不可乱收。

31. **听悟的 `taskStatus` 有四个值不是三个**：ONGOING / COMPLETED / FAILED / **INVALID**。
    只按前三个写分支的话，INVALID 会一路落到「还在跑」，桌面端永远轮询下去、
    钱一直被预扣占着直到 TTL。两侧的终态判定都要带上它。

32. **platform 档下的中转音频有两道删除，两道都要**：转写完成/失败时代码删，
    过期回收时 `sweepExpiredAsrObjects` 删，OSS 生命周期规则（前缀 `asr/`，1 天）兜底。
    规则的天数**必须大于** `GW_HOLD_TTL_MINUTES`，否则会在任务还能正常完成时把音频抽走。
    配置写在官网 `DEPLOY.md` §7.1。

33. **企查查与 Tushare 有一条不经过 Java 的出站路径**：`PythonTools` 把
    `TUSHARE_TOKEN` / `QICHACHA_KEY` / `QICHACHA_SECRET` 注入 Python 子进程，
    AI 写的脚本从用户本机直连上游。platform 档下没有可注入的凭证（凭证在官网，
    下发给每台机器等于把公司账号发给所有人），所以 `injectableCredentials()` **一个都不注入**，
    改由 `EnterpriseDataTools` 的 `qichacha_query` / `tushare_query` 代它调。
    不这么做的话，脚本拿到空 token，失败会表现成「查不到数据」而不是「未配置」。
    顺带：脚本那条路是唯一一条 AI 能循环打出几百次上游调用的口子，收进 Java 侧才受任务级上限约束。
    取值与过滤是**两件必须都做的事**，合在 `injectableCredentials()` 一个方法里：
    取值走库优先（`system_setting` 有就用它，#383 修的；只读 yml 的话用户填了 Key 仍拿到空值，
    脚本不报「未配置」只会查不到数据，比报错难查得多），注入按档过滤（platform 档一个不发）。
    改这里务必两条一起看——只保一条就会退回其中一个 bug。

34. **两家上游用响应体而不是 HTTP 状态表达失败**：企查查「查无此企业」回的是
    HTTP 200 + `Status=201`，Tushare 积分不足/限频回的是 HTTP 200 + `code=40203`。
    网关侧只按 `res.ok` 判成功，就会给一次什么都没查到的调用收全价——
    两家都必须看响应体，非成功一律 `upstream_failed` 且**一分不扣**。

35. **新增 AI 工具组件要同步 `RealToolBeans.instantiateAll()`**（测试侧）。
    漏了的话该组件的工具在回放评测里根本不注册，可见性断言写了也是空的；
    护栏是 `EvalToolBeanParityTest`，会直接点名漏掉的类。
36. **「录音不出本机」这类开关在引擎就绪之前不许留在打开态**。
    把开关做成「能打开、录完两小时才在转写那一刻炸」，用户只剩「放弃这份录音」或
    「关掉开关传上云」两条路——后者与他打开开关的目的正好相反。这不是体验差，
    是把用户推回他主动规避掉的合规风险里。判据在
    `frontend/src/config/platformServices.js` 的 `LOCAL_TIER_READY`，
    **admin 的档位下拉与会议面板的开关共用它**（只关一处 = 另一处仍能切进去）。
    P3 起它不再是写死的常量：`refreshLocalAsrReadiness()` 打
    `GET /api/asr/local/probe` 回填，用 `reactive` 装着——普通对象改了不触发 computed
    重算，探测结果会拖到下次进页面才生效。切换时**就地探一次**，未就绪就不写档位
    （开关自然回到关闭态）并就地给下载入口。
    同理，档位与就绪状态必须摆在**录音开始之前**，不能拖到转写那一刻才暴露。

37. **本地 ASR 的探测必须能分开「服务没起」与「模型没下」**。两者的下一步毫无共同点：
    前者重启应用，后者要下 1.5GB 模型。合并成一句「不可用」等于让律师猜。
    为此 `asr-service` 与 kokoro 有一处**刻意的不同**：kokoro 的 descriptor 用
    `enabled` 把服务门在模型上（没模型不起进程），而 asr-service **无论有没有模型都启动**
    ——不起进程的话探测只剩「服务没起」一种结论，用户照提示重启一万次也不会有模型。
    模型懒加载，空跑一个 FastAPI 进程只占几十 MB。

38. **本地转写没有说话人分离，而且慢**。faster-whisper 不提供分离能力
    （pyannote 要 HF token + 许可协议 + 额外几百 MB 模型，与零配置直接冲突），
    所有段落 `speaker="1"`。速度实测约实时的 1.5 倍（M 系列 CPU、medium int8 + VAD，
    两小时的会要跑一小时上下）。两条都必须写在界面上——让用户以为两档等价，
    他会拿本地档去录一场需要区分发言人的听证会。
    `LocalAsrClient.ProbeResult.diarization` 从 `/health` 读而不是前端写死，
    换引擎时界面自动跟上。

39. **local 档全程没有 taskId，所以「被关机打断」必须另有判据**。
    云端两档靠 `gatewayTaskId` / `tingwuTaskId` 恢复轮询，本地档整段推理就在
    `MeetingTranscriptionService` 的后台执行器里跑完，重启后库里只剩一个「转写中」。
    而 `startTranscription` 对 TRANSCRIBING 是幂等返回——用户连「重试转写」都点不动，
    会议永远挂着。判据是进程内的 `inFlight` 集合：两个 taskId 都为空且不在集合里 =
    上次运行被打断，落 FAILED 并说明「录音本身完好，重新提交即可」。
    云端两档的转码上传阶段同样落在这个窗口里，这条顺带补上了那个既有缺口。

## 验证

- 后端：`cd backend && mvn test`（**JDK 21，系统默认 25 会 SIGBUS**）。本领域相关用例：
  `service/LicenseServiceTest`、`service/TrialCodeVerifierTest`、`service/LocalIdentityServiceTest`、
  `service/LocalIdentityRealShapeIntegrationTest`（真机形态种子库跑选择链路）、
  `service/account/AccountServiceTest`、`service/account/AccountEndpointTest`、
  `service/entitlement/EntitlementServiceTest`、`service/entitlement/FeatureCatalogTest`、
  `service/quota/StageQuotaServiceTest`、`service/storage/StorageLocationServiceTest`、
  `service/ClipboardQuotaTest`、`service/ai/PlatformUsageAccountantTest`、`service/ai/ChatModelFactoryTest`、
  `service/ai/{PluginMarketServiceTest, skill/SkillMarketServiceTest}`、
  `config/LocalModeAccessFilterTest`、`config/LocalModeLoopbackGuardTest`；
  server 模式加固：`service/AuthAbuseGuardTest`、`service/account/AwdkLoginServiceTest`、
  `service/account/MachineAccountGuardTest`、`controller/AuthControllerHardeningTest`、
  `controller/AccountControllerMachineScopeTest`、`service/UserServiceTest`（无密码账户分支）；
  站点：`service/site/SiteProfileServiceTest`（三级优先级、钉住判定、启动期校验全部 enabled 站点）、
  `service/site/SiteEnvironmentPostProcessorTest`（属性注入与优先级，含「环境变量必须压过站点注入」）、
  `service/site/SiteSwitchServiceTest`（切站清理表逐项）、
  `service/site/SiteMismatchMessageTest`（错配文案点名站点 + 三个掉线子串的红线）；
  per-user 平台密钥：`service/ai/PlatformAiKeyCipherTest`、`service/ai/PlatformAiKeyServiceTest`、
  `service/ai/PlatformAiChannelRoutingTest`（四种形态的取 key 路由）、
  `service/ai/PlatformAiUserScopeTest`、`controller/PlatformAiKeyControllerTest`；
  白嫖闸：`service/ai/PlatformCreditsGateTest`（余额闸三条判据）、
  `service/ai/PlatformAiKeyOwnershipTest`（换账号不复用旧 key、存量文件重新绑定）；
  平台服务网关：`service/platform/ExternalServiceDualTierRoutingTest`（P4 五家的双档路由：
  平台档不碰 BYOK 实现 / BYOK 档一次网关都不打 / 平台档失败不静默回落；
  另含 `PythonTools.credentialEnvArgs` 的注入判据与两个新工具的中文显示名）、
  `service/platform/PlatformGatewayClientTest`（错误分类、幂等重试带同一个键、
  七种失败形态的文案都不含三个掉线子串）、`service/platform/ExternalProviderResolverTest`
  （D5 的 local-mode 闸）、`service/platform/ExternalProviderBackfillTest`（存量回填四种形态）、
  `service/meeting/MeetingTranscriptionPlatformPathTest`（platform 档不碰 OSS/听悟两个接口、
  失败不回落 byok、轮询路由跟落库的 taskId 走）、`service/meeting/MeetingTranscriptionServiceTest`
  （byok 档行为不变的基线）。
- 官网侧（`aiworkdeckweb`）：`scripts/verify-gateway.mts` 45 项 + `contract-check.mts` 的网关段，
  **必须在空目录里跑、必须用 nvm v22 全路径**（`/usr/bin/node` v20 碰库会段错误）。
- 前端：`cd frontend && npm run check:emits` + `npm run build:h5`。
- 端到端（同样在 `frontend/` 下跑）：`cd frontend && npm run test:app-e2e`
  （**J1 就是首启解锁门旅程**，用试用码解锁；其余旅程 local-mode 免登直达）。
  `cd frontend && npm run test:desktop-e2e` 的 provision 会自动用试用码解锁并置向导。改解锁门/启动链必跑这两套。
- 手工复验跨站防护（PR-A 安全修复时用过的配方）：`curl` 分别打无 Origin、`http://localhost:5174`、
  恶意 Origin、带 `X-Forwarded-For` 四种形态的 POST，前两者应通过、后两者应 403。
- 试用码本身可离线复验：base32 解码后应为 70 字节、`payload[0..1] == 0x01 0x01`，
  再用 `backend/src/main/resources/license/trial-public-key.pem` 验 Ed25519 签名。
