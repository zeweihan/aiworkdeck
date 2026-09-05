# 支付路径与会员体系（2026-08-27，dev-board#183-191）

用户目标：让用户感知投入与付费属性，提供顺畅付费路径。三块工作：
桌面端内嵌余额+充值、会员体系（成长值+律师职级）、设置页精简。

## 1. 断头路根因（#183）

桌面端所有付费引导（UnlockHint「了解详情」、「去充值」等）都 `openExternalUrl(accountPageUrl())`
外链官网 `/zh/account`——用户浏览器上没有官网登录态，落地是登录墙，看不到充值入口。
根治 = 桌面端内嵌充值（走 awdk_ Bearer，不依赖浏览器登录态），付费引导全部改为应用内跳转
「设置 → 账户与用量」。

## 2. 会员体系（#185）

### 成长值

```
growthPoints = floor(累计充值分 / 10) + floor(累计消费分 / 20)
```

即充值 1 元 = 10 成长值，消费 1 元 = 5 成长值（充值并用完 = 15/元）。
- 累计充值：`wallet_ledger` kind='recharge' 的 amountCents 求和（恒为正）。
- 累计消费：kind IN ('ai_alloc','purchase','service_spend') 且非预扣（meta.phase 缺失或 ='settle'）
  的负数行取绝对值求和。退款不回扣成长值（成长值只增不减，刻意从宽）。

### 等级（律师职级，7 档）

| level | key | 中文 | EN | 门槛(成长值) | 充值赠送‰ |
|---|---|---|---|---|---|
| 1 | paralegal | 律师助理 | Paralegal | 0 | 0 |
| 2 | associate | 正式律师 | Associate | 300 | 10 |
| 3 | lead | 主办律师 | Lead Counsel | 1,500 | 20 |
| 4 | senior | 资深律师 | Senior Counsel | 5,000 | 40 |
| 5 | salaried-partner | 授薪合伙人 | Salaried Partner | 15,000 | 60 |
| 6 | equity-partner | 权益合伙人 | Equity Partner | 50,000 | 80 |
| 7 | managing-partner | 管理委员会合伙人 | Managing Partner | 150,000 | 100 |

参照：L2≈充 30 元；L7≈充 1 万元并用完。

### 权益（只承诺已实现的）

- 各等级充值赠送：充值到账时按**含本笔后的成长值**定档，赠送 `floor(amountCents×‰/1000)`，
  以 reward 批次入账（24 个月有效、不可退现——与两类批次红线一致，topup 退款路径不受影响），
  ledgerKind 复用现有 `gift`（不扩 kind 枚举），sourceKind='membership_bonus'，meta 带 refOrderId+permille。
- 等级徽章展示（桌面端顶栏/账户页、官网账户页）。
- L4+ 新功能优先体验、L5+ 专属支持通道（运营承诺，页面文案从轻）。

### 官网 API（进 desktop-contract.md + contract-check.mts）

`GET /api/account/membership`（Bearer awdk_ 或 Cookie）：

```
200 { growthPoints, topupCents, spendCents,
      tier: {key, level, nameZh, nameEn, bonusPermille},
      nextTier: {key, level, nameZh, nameEn, threshold, remainingPoints} | null,
      tiers: [{key, level, nameZh, nameEn, threshold, bonusPermille}] }   // 全表，客户端据此渲染规则页
401 {error:"unauthorized"}
```

两站字段集合与语义逐字相同（契约不变式）。

### 官网页面

- 账户页「资产」组：会员卡片（等级徽章+成长值进度+下一等级+规则页链接），放 WalletSection 旁。
- 公开规则页 `/{lang}/membership`：等级表、成长值算法、各档权益。中英词典、全站禁 emoji、衬线视觉。

## 3. 桌面端（#184 + #183）

### 后端（AccountController/AccountService 新增，转发官网）

- `GET /api/account/balance` — 轻端点：fetchProfile 的 balanceCents/plan + membership 摘要
  （tier level/nameZh/nameEn），AccountService 内做短 TTL 缓存（余额 60s、membership 10min）。
- `GET /api/account/membership` — 全量转发。
- `POST /api/account/recharge` `{amountCents}` — 转发官网 `POST /api/payment/create`
  `{amount, kind:'recharge', idempotencyKey}`（Bearer），返回 `{present, codeUrl?, qrCode?, redirectUrl?, outTradeNo, amount}`。
- `GET /api/account/recharge/status?outTradeNo=` — 转发官网 `/api/payment/query`。
- 红线：未连接账户/官网不可达都是业务错误（code=1 信封），绝不回 4xx/4010；
  换账户作废动作走 AccountSwitchCleanup（新缓存要挂进去）。
- 官网侧前置：`/api/payment/create`、`/api/payment/query` 需接受 Bearer awdk_
  （resolveKeyUser ?? getSessionUser 模式），并登记进 desktop-contract.md。

### 前端

- 顶栏 `.header-right`（header-tools 与 header-account 之间）：余额 chip「¥ x.xx」+ 等级徽章短名，
  点击 `openSettingsTab({nav:'account'})`；mount/onShow/充值成功后刷新（uni.$emit 事件）。
- 账户与用量分区顶部：会员钱包卡（余额大字 + 充值主按钮 + 等级徽章 + 成长值进度条 + 等级规则展开表）。
- 充值弹窗：档位 ¥50/100/300 + 自定义；wxpay 用 `qrcode` 库把 codeUrl 转二维码站内轮询；
  stripe `openExternalUrl(redirectUrl)` + 站内轮询；成功后刷新余额并 emit。
- UnlockHint 默认动作：从外链 accountPageUrl() 改为应用内打开设置「账户与用量」。

## 3.5 登录处的用户协议（2026-08-27 追加指示）

维护者要求登录时挂用户协议、把各类同意写进协议，主体境内=北京京微资易科技有限公司、
境外=Zhen Shan Mei Grace Legacy Limited。落地裁决：**不另起草新文档**——官网已有
结构化《服务条款》《隐私政策》（content/legal/ 四变体一组，主体经 lib/site-config 的
legalEntity 按站点插值，两家主体与指示完全一致，且已覆盖 Credits 24 个月/不可提现、
AI 免责、仲裁等条款），再起草一份会造成两约并存的解释冲突。桌面解锁页改为：

- 勾选框 A「我已阅读并同意《服务条款》与《隐私政策》」——链接按站点+界面语言打开
  `{siteBaseUrl}/{zh|en}/legal/{terms|privacy}`；
- 勾选框 B 跨境传输单独同意（个保法 39 条）——**刻意不并入协议一揽子**，打包的不叫
  单独同意（2026-08-08 既有红线维持）；「查看告知」弹窗展示全文；
- 两枚都不预勾选，三个页签（登录/注册/试用码 Key）共用同一提交闸；
- 同意留痕：`POST /api/license/agreement`（匿名，LocalModeAccessFilter 兜底）写
  SystemSetting `legal.userAgreement.{version,acceptedAt}`，版本常量在 unlock.vue
  的 AGREEMENT_VERSION，协议实质变更时改成新日期。

## 4. 设置精简（#186-#191）

- 删「平台服务」分区（nav `platform` + 模板 + 专属逻辑）；花费闸门卡（低余额阈值）移入「账户与用量」；
  所有跳 `nav:'platform'` 的深链改指 `account`。（#186）
- system 组新顺序：account, ai, updates, components, cloud, memory, telemetry, feedback, plugins。（#187/#188）
- 数据统计卡片：KPI 卡等高、左右栏对齐。（#189）
- 插件广场嵌入态与设置分区布局协调（MarketPane standalone:false 形态）。（#190）
- 向导下线（#191）：解锁页 unlock.vue 增加跨境同意勾选（绝不预勾选、独立文案块，不并入 ToS），
  解锁成功后调既有 `POST /api/admin/wizard` 写 `{ai:{activeProvider:'AWD_CLOUD', crossBorderConsent:true}}`；
  launch.vue/login.vue 删向导分流；AdminPane 删「重新运行首次向导」；删 wizard 页面与路由；
  后端 WizardController/WizardStateService 原样保留（解锁页在用）。
  背景：静态默认供应商是 open-router（BYOK），此前全靠向导首启写 AWD_CLOUD+同意，裸删向导会让新装机 AI 直接哑掉。
