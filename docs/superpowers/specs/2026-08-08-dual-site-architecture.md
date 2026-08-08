# 双主站架构设计（aiworkdeck.com / workdeck.ai）

状态：设计稿，待确认后实施
日期：2026-08-08
涉及仓库：`checkba_cloud`（桌面/后端）、`aiworkdeckweb`（官网）
相关文档：官网仓 `doc/desktop-contract.md`、`.claude/agents/licensing-billing.md`、
`docs/superpowers/specs/2026-08-05-commercialization-redesign.md`

---

## 0. 一句话结论

给桌面端补一个**机器级的「当前站点」**概念，让 `ai.account.base-url` 及其同族的四个站点相关地址
统一从一个解析出口取值；官网仓则用**同一套代码、两个实例、两份数据**跑出国内站与国际站。
两站的 `/api/` 契约**逐字相同**——契约不分叉是这套方案能只改一处的前提。

存量安装在没有 `site.json` 时行为与今天逐字一致，这是整个方案的兼容性锚点。

---

## 1. 分的是什么，不分的是什么

### 1.1 分（商业与合规）

| 维度 | 国内站 www.aiworkdeck.com | 国际站 www.workdeck.ai |
|---|---|---|
| 币种与定价 | CNY，Credits 锚定人民币 | USD，Credits 锚定美元 |
| 支付通道 | 微信支付（已上线）/ 支付宝（待做） | Stripe 或 MoR（见 §11 决策 5） |
| 发票与收据 | 增值税发票（人工） | 电子收据 + 税号字段，VAT/GST 由通道处理 |
| 适用法与争议解决 | 中国法，北京仲裁委员会一裁终局 | 另立一份，不沿用北仲（见 §7） |
| ICP 备案 | 页脚必须标注 | 无备案，页脚不得出现备案号 |
| 默认语言 | zh | en |
| telemetry ingest | 落北京实例 | 落新加坡实例 |
| 插件 / Skill registry | 各自实例的 registry | 同左 |
| 账户体系 | 独立 | 独立 |

### 1.2 不分（明确写下来，防止后来人顺手拆了）

- **模型可用性**。桌面端所有 OpenRouter 请求从用户本机直连 `openrouter.ai`
  （`ai.model.open-router.base-url`），平台通道 `AWD_CLOUD` 刻意只读 yml 的 baseUrl。
  能不能用某个模型由用户机器的出口 IP 决定，OpenRouter 运行时返 403。
  **任何按站点过滤模型清单的逻辑都是错的**，因为它既解锁不了也拦不住。
- **插件签名公钥** `ai.plugins.registry-public-key`。两站共用一把，否则国内站签发的插件在国际站装不上，
  等于把插件生态劈成两半。私钥仍只在国内站服务器的 `AWD_PLUGIN_SIGNING_KEY`。
- **试用码公钥** `backend/src/main/resources/license/trial-public-key.pem`。试用码离线验签、全程不联网，
  与站点无关。
- **`/api/` 契约**。两站端点集合、字段名、类型、状态码语义逐字相同（见 §5）。
- **桌面端更新通道**。`desktop/main/services/update-service.js` 的 manifest 走
  `www.aiworkdeck.com/update/desktop/manifest.json` + GitHub Releases 兜底。分发不是商业合规问题，
  GitHub 兜底已覆盖境外可达性。Phase 1 不动。
- **反馈收件箱** `feedback.upload.url`（`addin.aiworkdeck.com`）。这是维护者侧的运维通道不是产品功能。
  Phase 1 不动，但**必须在国际站隐私政策里如实披露反馈内容会传到中国境内服务器**（见 §7 与 §11 决策 4）。

---

## 2. 桌面端：站点作为一等概念

### 2.1 站点标识落在哪里（决策）

**结论：机器级，落 `~/.aiworkdeck/site.json`（0600，与同目录五个状态文件同规格），不跟着 `account.json` 走，也不进数据库。**

理由，按重要性排序：

1. **站点必须在没有任何账户连接时就已知**。解锁页需要它来决定 `POST {base}/api/license/verify-key` 打向哪台服务器；
   `LicenseService` 的账户 Key 校验发生在 `AccountService.connect()` **之前**（`LicenseController.activate`
   先 activate 再 connectAccountIfKey）。把站点塞进 `account.json` 会造成先有鸡还是先有蛋。
2. **`AwdkLoginService`（server 模式桥接）根本不读 `account.json`**，但同样需要知道打向哪个站。
3. **不进数据库**，理由与 `local.identity.selectedUserId` 刻意进数据库的理由正好互补：
   身份选择存的是指向同一个库里 user 表的外键，必须与数据同生共死；而 license/account/site
   描述的是**机器授权与机器面向哪个商业实体**，独立于库是刻意的（还原旧库不该把站点还原掉）。

`site.json` 结构：

```json
{ "site": "cn", "chosenAt": "2026-08-08T00:00:00Z", "chosenBy": "user" }
```

`chosenBy` 取 `user` / `default`：只有 `user` 才是用户的显式选择。文件缺失 = `default` + `cn`，
与今天的行为逐字一致。

### 2.2 配置形态

`application.yml` 的 `ai.account` 段改为：

```yaml
ai:
  account:
    # 站点固定值（团队服务器/私有部署/本地联调用）。非空时**钉死站点**，忽略 site.json。
    # 本地起官网联调仍走这里：http://localhost:3000（AccountEndpoint 的回环例外不变）。
    base-url: ""
    # local-mode 下 site.json 缺失时的默认站点
    default-site: cn
    sites:
      cn:
        enabled: true
        display-name: AI Workdeck 国内站
        base-url: https://www.aiworkdeck.com
        registry-base-url: https://www.aiworkdeck.com/api/registry
        telemetry-ingest-url: https://www.aiworkdeck.com/api/telemetry
        account-page-url: https://www.aiworkdeck.com/zh/account
      intl:
        # Phase 1 合并时为 false：站点选择器只在启用站点 >= 2 时出现，
        # 国际站真正上线（Phase 2）前用户看不到任何变化
        enabled: false
        display-name: AI Workdeck International
        base-url: https://www.workdeck.ai
        registry-base-url: https://www.workdeck.ai/api/registry
        telemetry-ingest-url: https://www.workdeck.ai/api/telemetry
        account-page-url: https://www.workdeck.ai/en/account
```

优先级：`ai.account.base-url` 非空 > `site.json`（仅 local-mode）> `default-site`。

**非 local-mode 不读 `site.json`**：团队服务器与插件云后端面向哪个站是部署决策，不是某个用户的选择。
`application-cloud.yml` 显式配 `ai.account.base-url`（现状已如此，一字不用改）。

### 2.3 唯一解析出口

新增 `backend/src/main/java/com/checkba/service/account/SiteProfileService.java`：

```
currentSite()        -> String            当前站点 id
profile()            -> SiteProfile       当前站点的四个地址 + 展示名
profileOf(siteId)    -> SiteProfile
availableSites()     -> List<SiteProfile> enabled 的站点（供前端渲染选择器）
select(siteId)       -> SwitchResult      切站（见 2.4）
pinned()             -> boolean           是否被 ai.account.base-url 钉死
```

启动期对**所有 enabled 站点**的 base-url 跑一遍 `AccountEndpoint.requireSecure`，保留今天的
fail-fast 语义（今天是构造器里校验一个值，改后是校验 N 个值，强度不降）。

改造点（把构造器里的 `@Value String baseUrl` 换成注入 `SiteProfileService`，`this.baseUrl` 字段
换成 `site.profile().baseUrl()` 调用）：

| 文件 | 现状 |
|---|---|
| `service/LicenseService.java:52-56` | `@Value ai.account.base-url` |
| `service/account/AccountService.java:42-46` | 同上 |
| `service/account/AwdkLoginService.java:63-71` | 同上 |
| `service/ai/PluginMarketService.java:65` | `@Value ai.plugins.registry-url` |
| `service/ai/skill/SkillProperties.java:31` | `registryUrl` 默认值 |
| `service/telemetry/TelemetryUploadService.java:61` | `@Value telemetry.ingest-url` |

后三个保留各自的 `@Value` 作为**显式覆盖**（非空即用，用于私有 registry / 自建 telemetry），
为空时回落到 `SiteProfileService`。这样现有 yml 里的显式值不需要在同一个 PR 里全删。

### 2.4 切站语义（这是最容易做错的一块）

切站 = 换了一个**完全不同的商业实体**，本地一切从那个实体拿来的东西都必须作废。

| 状态 | 切站时 | 理由 |
|---|---|---|
| `account.json` | **删除** | Key 属于旧站，在新站上必然 401 |
| `entitlements.json` | **删除** | 权益是旧站发的 |
| `platform-ai-key.json` | **删除** | runtime key 由旧站 provision，额度记在旧站账上 |
| `license.json` mode=`account` | **删除** | 授权票据是旧站 verify-key 发的 |
| `license.json` mode=`trial` | **保留** | 试用码离线验签、站点无关；抹掉等于把人踢回未解锁 |
| `storage-location` | **保留** | 与站点无关 |
| 项目数据库 | **保留** | 与站点无关 |

另需调 `ChatModelFactory.demotePlatformProvider()`：切站后平台通道必然取不到 key，
不降级会出现「界面显示平台通道正常选中、每条消息都报未连接账户」（licensing-billing 地雷 8 的同一个坑）。

切站是**破坏性动作**，`POST /api/site/select` 要求前端二次确认，弹窗明写会清掉哪些东西。
若目标站点 == 当前站点，直接返回成功不做任何清理（幂等）。

### 2.5 站点选择落在哪个环节（决策）

**结论：解锁页 `unlock.vue` 是主入口，设置页「账户与用量」是常驻入口。启动分流页与首启向导都不放。**

推演过程：

- **`launch.vue` 不放**。它是纯分流页，加一道人人必过的选站屏会拖慢所有人（包括只用试用码的用户，
  他们根本不需要站点），且违背「启动链不承载业务 UI」。
- **`wizard.vue` 不放**。首启向导在解锁**之后**才跑（launch: license → identity → wizard）。
  站点错了在解锁那一步就已经炸了，向导里再选是迟到的。另有并行 session 在改向导，避让。
- **`admin.vue` 不能只放这里**。同样是迟到——用户已经在解锁页被 401 挡住了。
- **`unlock.vue` 是唯一正确的位置**：这是站点第一次真正生效的地方
  （`POST {base}/api/license/verify-key`，见 `LicenseService.callVerifyKey`）。

形态上**不做成强制的一步**，做成解锁卡片上的一行低调切换 + 失败时的救济路径：

```
[ 粘贴试用码或账户 Key                     ]
[            解  锁                        ]
获取试用码 | 获取正式版 | 站点：国内站 ▾
```

`enabled` 站点只有一个时这一行整个不渲染（Phase 1 合并后用户看不到任何变化）。

默认站点的选取：`ai.account.default-site`（= `cn`）。**不做 IP/时区自动探测**——
探错的代价是用户在解锁页被莫名其妙地拒绝，而收益只是省一次点击。

### 2.6 站点错配的错误提示（关键设计）

这是整个方案里用户最容易撞上的墙，必须专门设计，不能靠通用文案。

现状：国际站账户的 Key 粘到国内站，`POST /api/license/verify-key` 回 `200 {valid:false}`，
桌面端 `LicenseService` 报「试用码或账户 Key 无效」；若走 `AccountService.connect()`
则是 401 → 「账户 Key 无效或已被撤销，请到官网账户页重新生成」。
**两条文案都在指控用户的 Key 坏了，而 Key 其实是好的。** 用户会去官网重新生成一把，再撞一次。

改法：

1. `LicenseService` / `AccountService` 的失效文案在**启用站点 >= 2** 时追加一句：
   > 该 Key 在「国内站」上无效。如果你的账户注册在「AI Workdeck International」，切换站点后重试。
2. 解锁页在这条错误下方直接给一个「切到国际站并重试」按钮，一次点击完成
   `POST /api/site/select` + 重新 activate。
3. **文案红线**：`frontend/src/services/api.js:249` 对 `code:1` 的 message 做子串匹配识别未登录，
   命中「登录」「未授权」「请先」会清本地会话。上面的文案已绕开这三个子串——
   写「切换站点后重试」而不是「请先切换站点」。护栏加进
   `AccountServiceTest.accountMessagesDoNotLookLikeAuthErrors`。

**不做自动探测重试**（拿同一把 Key 依次打两个站直到有一个通过）。理由是安全的：
`awdk_` 是明文 bearer 凭据，把它发给一个不是它签发方的服务器，等于向第三方泄露一把有效凭据。
两个站点由同一个团队运营不改变这个判断——今天成立不等于第二方托管实例出现后仍成立，
而那时这段代码不会有人回头改。宁可让用户点一次。

### 2.7 前端硬编码站点 URL 清单

统一收敛到新的 `frontend/src/utils/siteLinks.js`（从 `GET /api/site` 拿当前站点的
`accountPageUrl` / `baseUrl`，带内存缓存 + 兜底常量）：

| 文件 | 现状 | 处置 |
|---|---|---|
| `pages/unlock/unlock.vue:45` | `OFFICIAL_SITE_URL` | 改（Phase 1） |
| `pages/admin/admin.vue:1227` | `ACCOUNT_SITE_URL` | 改（Phase 1，属「账户与用量」分区） |
| `components/UnlockHint.vue:15` | `DEFAULT_LINK_URL` | 改（Phase 1） |
| `utils/marketPricing.js:8` | `SITE_BASE` | 改（Phase 1） |
| `pages/project-overview/stagingArea.js:147` | 账户页链接 | 改（Phase 1） |
| `pages/project-overview/project-overview.vue:2627` | 官网链接 | 改（Phase 1） |
| `pages/wizard/wizard.vue:178` | `ACCOUNT_SITE_URL` | **Phase 3**，见下 |
| `pages/admin/admin.vue:1555` | 更新页 fallback | **不改**，更新通道站点无关（§1.2） |

`wizard.vue:178` 推迟：该常量服务于「AI 供应商选择」块里的账户连接分支，与并行 session 的作用域紧邻。
Phase 1 只建好 `siteLinks.js`，等并行 session 合并后再单独一个小 PR 替换这一行。
**这条要写进 PR 描述的交接说明**，否则会被当成漏改。

### 2.8 新增端点

`backend/src/main/java/com/checkba/controller/SiteController.java`，与 `LicenseController` 同族（匿名端点——
选站发生在解锁之前，此时没有任何身份可言；由 `LocalModeAccessFilter` 的三条闸兜底，
同 `POST /api/license/deactivate`）：

- `GET  /api/site` → `{ current, pinned, sites: [{ id, displayName, baseUrl, accountPageUrl }] }`
- `POST /api/site/select` `{ site }` → 执行 §2.4 的清理，返回新状态

`pinned=true`（`ai.account.base-url` 非空）时 `select` 回业务错误，不静默无视。
非 local-mode 时 `sites` 只回当前一个、`pinned=true`。

`GET /api/license/status` 增回 `site` 一个字段（供顶栏 chip 与解锁页渲染），
维持它「只读组合、绝不回写 license.json」的性质。

---

## 3. 官网侧：一套代码，两个实例，两份数据

### 3.1 部署形态

今天：北京 ECS `8.152.169.44` 跑 Next 应用；新加坡 `8.219.94.204` 是**缓存反代**，回源北京，
`workdeck.ai` 由新加坡 nginx 按访客 IP 做 geo 分流（境内 301 到 aiworkdeck.com，境外反代直出）。

改后：新加坡 ECS 上**新起一个独立的 Next 实例**（同一份代码，`AWD_SITE=intl`，独立 `data/` 目录、
独立 SQLite、独立 pm2 进程）。`workdeck.ai` 的 nginx 从「反代回北京」改成「反代到本机的 intl 实例」。
`www.aiworkdeck.com` 在新加坡的缓存反代职责**一字不动**。

两个实例的数据完全隔离：`data/awd.db`、`data/users.json`、`data/skills.json`、`data/plugins.json` 各一份。

官网仓没有 CI，两台机都要手动部署（`doc/DEPLOY.md` 需补国际站那条命令）。

### 3.2 站点配置

新增 `lib/site-config.ts`，`AWD_SITE` 环境变量驱动（缺省 `cn`，保证现有部署零改动）：

```ts
export const SITE = {
  id: 'cn' | 'intl',
  currency: 'CNY' | 'USD',
  currencySymbol: '¥' | '$',
  defaultLocale: 'zh' | 'en',
  domain: 'www.aiworkdeck.com' | 'www.workdeck.ai',
  paymentChannel: 'wxpay' | 'stripe',
  showIcpFiling: true | false,
  legalEntity: ...,        // 见 §7
  otherSite: { domain, label },  // 用于「你要找的可能是另一个站」引导
}
```

改造点：

- `lib/legal-entity.ts` — 现在是单一常量，改成按站点导出；`site`/`icpFiling` 字段国际站为空。
  **保持「单一来源」的性质不变**，只是从一个常量变成一个按站点取值的函数。
- `lib/price.ts` 的 `formatPriceYuan(cents)` → `formatPrice(cents)`，符号与小数位从 `SITE` 取。
  这是全站价格显示的唯一出口，改一处即可。
- `app/api/payment/create/route.ts` — 按 `SITE.paymentChannel` 路由到 `lib/wxpay.ts` 或新的支付通道模块。
  订单表的 `amountCents` 语义变成「站点币种的最小单位」，**不需要改 schema**；
  但 `orders` 表建议加一列 `currency`（迁移只许追加，见 `lib/db.ts` 的 MIGRATIONS 约定），
  免得将来对账时靠部署环境反推币种。
- `app/api/account/ai-usage/route.ts` 的 `exchangeRate`（CNY→USD，默认 7.3）在国际站为 `1`。
  `marginMultiplier` 两站相同。**桌面端不用改**：它已经把 `exchangeRate` 当服务端下发的值读。
- 页脚 ICP 行按 `SITE.showIcpFiling` 渲染。**国际站页脚出现备案号是硬错误**（宣称了不存在的备案）。
- `i18n-config.ts` 的 `defaultLocale` 从 `SITE.defaultLocale` 取。两站都保留 zh/en 两种语言
  （国际站的中文用户、国内站的英文用户都存在），只是默认落地语言不同。

### 3.3 nginx 分流必须改的一点（高危）

新加坡当前 vhost `/www/server/panel/vhost/nginx/workdeck.ai.conf` 里，`location /` 内按
`geo $awd_from_cn` 对境内访客 `301` 到 `https://www.aiworkdeck.com$request_uri`。

**这条规则一旦覆盖 `/api/`，桌面端就会以一种极难排查的方式坏掉**：

- 用户在中国境内、站点选了 `intl`（合理场景：注册在国际站的人回国出差）
- 桌面端 `HttpAccountTransport` 用 JDK HttpClient，**默认 `Redirect.NEVER`，不跟随 301**
- 于是 `verify-key` 拿到 301，`AccountService.handle` 判为「预期外状态（301）」→ MALFORMED
- 用户看到「官网返回了预期外的状态」，无从下手

因此 Phase 2 必须在 workdeck.ai 的 vhost 里加**优先级更高的前缀 location** 绕开 geo 判断：

```nginx
location ^~ /api/    { ... proxy_pass 到本机 intl 实例；不做任何 geo 301 ... }
location ^~ /update/ { ... 同上 ... }
```

同理，`/[lang]/account` 与 `/[lang]/legal/` 也应绕开 301：账户页与法律文本是「我的账户在哪个站」
的功能页，境内访客也必须能打开自己的国际站账户。营销页（首页、skills、plugins 列表）
维持 geo 301 不变——那才是 301 想要达成的商业默认。

301 落地的国内站页面上再补一行「你要找的是国际站？」的链接（带 `?site=intl` 或
`awd_site=intl` cookie 让下次不再跳），这是唯一的逃生门，不能没有。

### 3.4 registry 与创作者分成

每个站点服务自己的 `/api/registry/*`。付费条目的闸门（402）读的是**本站**的 entitlements，
所以内容与账户必须同站。

内容层面有三个选择，Phase 2 之前要拍板（§11 决策 3）：
- 全量镜像（同一份 skills/plugins，价格按币种各标一份）
- 只镜像免费条目，付费条目先只在国内站卖
- 各站独立策展

创作者七三分成在国际站涉及美元结算与跨境付款，Phase 2 内**不做**：国际站 registry 先只上
官方与免费条目，把付费投稿闸掉（`/[lang]/skills/submit` 在 `SITE.id === 'intl'` 时隐藏定价字段）。

### 3.5 telemetry

`telemetry.ingest-url` 随站点走（§2.2）。国际站实例有自己的 `lib/telemetry-store.ts` 落点。
后台看板 `/[lang]/admin/telemetry` 各看各的。**不做跨站聚合**——聚合需要把国际用户的数据搬回境内，
是 §7 要避免的事。要看全局数字就人工加两个数。

---

## 4. 桌面端 UI 是中文的（必须先说清楚的一个洞）

`frontend/` 里 `vue-i18n` 只在 `package.json` 的依赖里，`src/` 下一处 `$t()` 都没有——
桌面产品是**纯中文**的。国际站卖的是这个中文产品。

这不是本任务能顺手解决的（桌面端 i18n 是独立的大工程），但它决定了 workdeck.ai 的商业口径，
必须由你拍板（§11 决策 1）。本设计的做法是把两件事解耦：

- 站点缝（Phase 1）**现在就做**，它是纯基建，做完不改变任何用户可见行为；
- workdeck.ai 什么时候作为商业站开卖，取决于你对「英文站 + 中文产品」的判断。

---

## 5. 契约影响

### 5.1 契约本身不分叉（不变式）

**两站的 `/api/` 端点集合、字段名、类型、状态码语义逐字相同。**
这是「桌面端只改一个解析出口」能成立的前提，也是 `scripts/contract-check.mts`
能一份脚本管两站的前提。任何「国际站多一个字段 / 少一个端点」的提议都要先推翻这条不变式。

站点差异只体现在**值**上，不体现在**形状**上：
- `exchangeRate` = 7.3 / 1
- `balanceCents` / `priceCents` 的币种含义不同（由站点决定，不由字段决定）
- `plan`、`entitlements[].feature`、`kind` 枚举完全一致

### 5.2 `doc/desktop-contract.md` 要加的内容

1. 顶部「生产地址」从一行改成两行，并写明**两站账户不互通**。
2. 新增一节「站点（2026-08）」：
   - 两站契约逐字相同；桌面端靠 `~/.aiworkdeck/site.json` 决定打向哪个站
   - `verify-key` 在**非签发站**上回 `200 {valid:false}` 而不是 4xx，与今天的语义一致
     （桌面端把 4xx 判为 INVALID 并抹掉 license.json；站点选错不该有这个后果）
   - **反代红线**：`/api/` 与 `/update/` 不得对任何来源做 301/302。
     桌面端 HTTP 客户端不跟随重定向，301 会被判成 MALFORMED（§3.3）
   - `amountCents` 是站点币种的最小单位；币种由站点决定
3. `GET /api/account/ai-usage` 顺手补进权威文档——它今天是唯一一条只在实现里、
   `doc/desktop-contract.md` 与 `contract-check.mts` 都搜不到的端点
   （licensing-billing 领域文档已记这笔账）。双站会让这个缺口更危险：
   两个实例的这条端点漂移了没有任何护栏会响。

### 5.3 `scripts/contract-check.mts` 要加的内容

1. 补 `ai-usage` 的字段断言（补上 5.2 第 3 条的缺口）。
2. 新增 `AWD_SITE` 维度：脚本跑两遍（`AWD_SITE=cn` / `AWD_SITE=intl`），断言
   **两遍产出的端点集合与字段集合完全相同**。这条断言就是 5.1 不变式的机器可执行版本。
3. 断言 `lib/site-config.ts` 在两个取值下都能解析出完整配置（currency / paymentChannel /
   legalEntity / showIcpFiling），且 `intl` 的 `showIcpFiling === false`。

### 5.4 `.claude/agents/licensing-billing.md` 要加的内容

按 CLAUDE.md 的维护规则，同一个 PR 里更新：关键文件地图加 `SiteProfileService` / `SiteController` /
`site.json`；核心契约加「切站清理表」；已知地雷加两条——「`/api/` 不许 301」与
「站点错配的文案不得指控 Key 无效」。

---

## 6. 用户面的说明与选站引导

两站账户不互通是接受的代价，但**用户必须在付钱之前就知道**。

- **官网 `/[lang]/start`**（新用户唯一落点）：加一段「选择你的站点」，说明两站账户独立、
  币种与支付方式不同，并给出「我在中国大陆 / 我在其他地区」的引导。
- **两站的账户页顶部**：一行常驻说明 + 指向另一站的链接
  （「这是国内站账户。国际站 workdeck.ai 是独立的账户体系，余额与已购不互通。」）。
- **301 落地页**：见 §3.3，必须有逃生门。
- **桌面解锁页**：站点切换器 + §2.6 的救济路径。
- **桌面设置页「账户与用量」**：当前站点常驻显示，切站按钮 + 二次确认弹窗列清会被清掉的东西。
- **两站的注册页**：不做拦截（拦不住也不该拦），但在提交按钮附近说明本站的币种与支付方式。

---

## 7. 法务

### 7.1 国际站需要独立的两份文本，不是翻译

现有 `content/legal/{terms,privacy}-{zh,en}.ts` 的 `-en` 版本是**国内站条款的英文版**
（缔约主体北京京微资易、北仲一裁终局、依据《民法典》《个人信息保护法》）。
国际站直接套用是错的：把境外消费者拉进北京仲裁，在多数消费者保护法域下属于不可执行条款，
写了等于没写，还会连累整条的可信度。

处置：新增 `content/legal/{terms,privacy}-intl-{zh,en}.ts` 四份，
`app/[lang]/legal/*` 按 `SITE.id` 选取。**起草口径沿用现有的「按可主张写，不堆免责」**
（现有文本的责任限额条明文保留故意、重大过失与人身损害的责任，那一句在国际版里同样不能删）。

缔约主体是 §11 决策 5 的直接后果，两者必须一起定。

### 7.2 三条既有欠账被双站放大

memory `website-legal-docs` 记的三条，在国际站语境下从「短期够用」变成「上线前必须清」：

| 欠账 | 国内站现状 | 国际站的问题 |
|---|---|---|
| 无自助注销账户 | 政策写了 30 日内删除，走邮件人工 | GDPR 第 17 条的删除权有一个月硬期限且必须可自助行使；靠邮件人工是明确的不合规 |
| 遥测 24 个月留存无清理任务 | `lib/telemetry-store.ts` 只有聚合没有过期删除 | 同上，存储限制原则（GDPR 第 5(1)(e) 条）；且国际站的遥测落在新加坡，多一个跨境环节要交代 |
| 出境「单独同意」无 UI | 按个保法第三十九条写了会取得单独同意，但桌面端用平台 AI 通道时没有独立勾选 | 国际站的数据流向是「用户 → 新加坡实例 → OpenRouter」，另加反馈流向境内（§1.2），两条都要在政策里如实写明并取得同意 |

**建议：把「自助注销 + 遥测过期清理」作为国际站上线的前置条件先做掉。**
这两件都不大（一个删除流程 + 一个 cron，与 `scripts/backup-db.mjs` 同一台机同一种挂法），
做完之后国内站的合规水位也一起抬上去了。「出境单独同意」的 UI 与文本口径二选一，
在 Phase 2 内定。

### 7.3 桌面仓 `legal/PRIVACY.md`

telemetry ingest 落点随站点变化，属于「采集口径变了」，按 memory `website-legal-docs` 的规则
必须同步改。国际站上线时同步补一段说明遥测落新加坡、反馈落境内。

---

## 8. 分期与两仓改动清单

### Phase 1：桌面端站点缝（checkba_cloud，一个 PR）

用户可见变化：**零**（`intl` 站点 `enabled: false`，选择器不渲染，`site.json` 不存在时行为逐字如今天）。

后端新增
- `service/account/SiteProfile.java`（不可变记录：id / displayName / baseUrl / registryBaseUrl / telemetryIngestUrl / accountPageUrl）
- `service/account/SiteProperties.java`（`@ConfigurationProperties("ai.account")`）
- `service/account/SiteProfileService.java`（唯一解析出口 + `site.json` 读写 + 切站清理编排）
- `controller/SiteController.java`（`GET /api/site`、`POST /api/site/select`）

后端改造
- `service/LicenseService.java` / `service/account/AccountService.java` / `service/account/AwdkLoginService.java` — 注入 `SiteProfileService`
- `service/ai/PluginMarketService.java` / `service/ai/skill/SkillProperties.java` / `service/telemetry/TelemetryUploadService.java` — 显式值优先、否则从站点取
- `controller/LicenseController.java` — `status()` 加 `site` 字段
- `service/LicenseService.java` / `service/account/AccountService.java` — 多站时的失效文案（§2.6）
- `resources/application.yml` — `ai.account` 段重写（**不碰 `ai.model.*` 与 `ai.subagent.*`**）

前端
- `utils/siteLinks.js` 新增
- `pages/unlock/unlock.vue` — 站点行 + 错误救济按钮
- `pages/admin/admin.vue` — 只改「账户与用量」分区：当前站点 + 切站 + 二次确认（**不碰 `aiProviderOptions` 及 AI 相关表单**）
- `components/UnlockHint.vue`、`utils/marketPricing.js`、`pages/project-overview/stagingArea.js`、`pages/project-overview/project-overview.vue` — 换成 `siteLinks`

文档
- `.claude/agents/licensing-billing.md` 同 PR 更新（§5.4）

### Phase 2：官网国际站实例（aiworkdeckweb，一至两个 PR）+ 桌面开关

官网
- `lib/site-config.ts` 新增；`lib/legal-entity.ts`、`lib/price.ts`、`i18n-config.ts`、页脚组件改造
- 支付通道抽象 + 国际通道接入（决策 5 定了才能写）
- `orders` 表加 `currency` 列（追加式迁移）
- `content/legal/*-intl-*.ts` 四份 + `app/[lang]/legal/*` 按站点取
- `app/[lang]/start` 选站引导；两站账户页的互指说明
- `doc/desktop-contract.md` + `scripts/contract-check.mts` 同步（§5.2 / §5.3）
- `doc/DEPLOY.md` 补国际站部署命令

服务器
- 新加坡起 intl 实例（独立 `data/`、pm2 进程、`AWD_SITE=intl`）
- `workdeck.ai` vhost 改造：`^~ /api/`、`^~ /update/`、`/[lang]/account`、`/[lang]/legal/` 绕开 geo 301（§3.3）
- 301 落地页的逃生门

桌面
- `ai.account.sites.intl.enabled: true`（一行），随下一个版本发布

前置（建议同期做掉）
- 自助注销账户；遥测过期清理 cron（§7.2）

### Phase 3：收尾

- `pages/wizard/wizard.vue:178` 换 `siteLinks`（等并行 session 合并后）
- 反馈收件箱按站点路由（决策 4）
- registry 内容策略落地（决策 3）
- 更新 manifest 是否分站（默认不分）

---

## 9. 迁移与回滚

**迁移**：存量安装无 `site.json` → `default-site: cn` → 所有地址与今天逐字相同。
不需要任何数据迁移，不需要用户做任何事。

**回滚（桌面）**：整个 PR revert 即可。`site.json` 变成一个无人读的遗留文件，无害
（`~/.aiworkdeck` 下已有五个状态文件，多一个不影响任何逻辑）。
若已有用户切到 `intl` 再回滚，他们的 `account.json` 已被切站清理掉，需要重新粘 Key——
这是 Phase 2 上线后才可能出现的场景，届时回滚要配一条公告。

**回滚（官网）**：国际站实例是**纯增量**，国内站一字未动。回滚 = 停掉新加坡的 intl 进程 +
把 `workdeck.ai` vhost 恢复成今天的缓存反代形态。国内站不受影响。
`AWD_SITE` 缺省为 `cn`，即使代码合了但环境变量没配，北京实例行为不变。

**不可逆的部分**：国际站一旦开始收款并发放 Credits，就不能简单地关站——
那是真实的用户债权。所以 Phase 2 的支付开关要与站点开关分开，
支持「站点可访问但暂不开放充值」的中间态。

---

## 10. 验证

后端（**JDK 21，系统默认 25 会 SIGBUS**）：

```bash
cd backend && mvn test
```

新增用例：
- `service/account/SiteProfileServiceTest` — 三级优先级（pinned > site.json > default）、
  非 local-mode 不读 `site.json`、启动期对所有 enabled 站点跑 `requireSecure`
- `service/account/SiteSwitchTest` — 切站清理表逐项断言：清四个、留 trial license 与 storage-location
- `controller/SiteControllerTest` — pinned 时 select 返回业务错误
- `service/account/AccountServiceTest` 扩充 — 站点错配文案不含「登录/未授权/请先」三个子串

前端：

```bash
cd frontend && npm run check:emits && npm run build:h5
```

端到端（改了解锁门与启动链，两套都必跑）：

```bash
cd frontend && npm run test:app-e2e
```

```bash
cd frontend && npm run test:desktop-e2e
```

`test:app-e2e` 的 J1 就是首启解锁门旅程（试用码解锁），是这次改动的正面靶子。
基线对照 v0.11.x 的 1031/104/198 判定，**不许把新增的站点行当成回归的借口**。

官网契约：

```bash
mkdir -p /tmp/awd-contract && cd /tmp/awd-contract && npx tsx --tsconfig "/Users/zewei/Documents/2024-2044/5-Tech/1-1 aiworkdeckweb/tsconfig.json" "/Users/zewei/Documents/2024-2044/5-Tech/1-1 aiworkdeckweb/scripts/contract-check.mts"
```

Phase 2 起同一条命令再跑一遍 `AWD_SITE=intl`。

手工复验（Phase 2）：从境内 IP `curl` `https://www.workdeck.ai/api/license/verify-key`，
**必须直接拿到 200，不能是 301**——这是 §3.3 那条红线的验收动作。

---

## 11. 需要你拍板的决策

1. **国际站与中文产品的关系**。桌面端是纯中文的（`vue-i18n` 装了没用）。
   workdeck.ai 是（a）现在就作为商业站开卖，如实说明产品界面为中文；
   （b）先只上站点缝与站点门面，等桌面 i18n 之后再开卖？
   我的建议是 (b)：站点缝零风险先落，商业开闸等产品配得上域名。

2. **首发是否覆盖欧盟**。若覆盖，§7.2 的自助注销与遥测清理是硬前置，且要补 GDPR 的
   数据主体权利入口与 SCC 口径。若首发只覆盖非欧盟地区，可以把这两条排在 Phase 3。
   建议：**把两条欠账现在就做掉**（各自都不大），然后不做地域限制——
   限制地域本身也要写进条款并做技术执行，反而更麻烦。

3. **国际站 registry 的内容策略**：全量镜像 / 只镜像免费条目 / 独立策展。
   建议第二个：先把付费投稿与创作者分成挡在国际站门外，避免美元结算与跨境付款在 Phase 2 里滚雪球。

4. **反馈收件箱**。国际用户的反馈（含日志尾巴与截图）现在会传到 `addin.aiworkdeck.com`（北京）。
   （a）保持单一收件箱并在国际站隐私政策里如实披露；（b）在新加坡另起一个收件箱。
   建议 (a) 先上、Phase 3 视量再拆——但披露那一句在 Phase 2 就必须写进文本。

5. **国际站的收款主体与通道（最高优先级，其余法务文本全部依赖它）**。
   需要核实：Stripe 的支持商户国家/地区列表**不含中国大陆**（含中国香港与新加坡）。
   若属实，北京京微资易科技有限公司无法直接开 Stripe 标准账户，可选路径：
   - 设立香港/新加坡实体做国际站的缔约与收款主体（最干净，成本与周期最高）
   - 用 Merchant of Record（Paddle / Lemon Squeezy 之类）：MoR 作为记录商户对终端用户收款，
     顺带解决欧盟 VAT、各州销售税与消费者撤销权，我方作为供应商与 MoR 结算。
     对当前阶段最省事，代价是费率更高、退款与客服口径受 MoR 约束
   - 暂不开放国际收款，workdeck.ai 只做品牌门面与免费下载（把 Phase 2 的支付部分整段推迟）

   我的建议是 **MoR**：它同时解决了收款主体、税务与消费者保护三件事，而这三件正是
   §7 里最容易做成「文本写了、代码没有」的部分。但这条要你先确认 Stripe 的主体限制，
   我不会替你把这个前提当既成事实。

---

## 12. 明确不做的事

- **不做跨站账户打通**（余额、entitlement、per-user 平台 AI 密钥、awdk_ Key 全部按站）。
- **不做按站点过滤模型清单**（§1.2，做了也无效）。
- **不做拿凭据自动探测站点**（§2.6，等于向非签发方泄露有效凭据）。
- **不做基于 IP/时区的桌面端站点自动选择**（探错的代价远大于省一次点击）。
- **不做两站 telemetry 跨站聚合**（要把国际用户数据搬回境内）。
- **不动 `backend/src/main/java/com/checkba/service/ai/` 下任何文件**（并行 session 作用域），
  `ChatModelFactory.demotePlatformProvider()` 是**调用**不是修改；
  若切站清理必须触达该目录，改为在 `SiteProfileService` 里发一个应用事件、由现有监听方响应，
  并在交接说明里点名。
- **不动 `application.yml` 的 `ai.model.*` 与 `ai.subagent.*` 段**。
- **不动 `wizard.vue` 的 Step 1 供应商选择**（Phase 1 连 `ACCOUNT_SITE_URL` 都不碰，推到 Phase 3）。
- **不动 `admin.vue` 的 `aiProviderOptions` 与 AI 相关表单**。
