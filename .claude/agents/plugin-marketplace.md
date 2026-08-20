---
name: plugin-marketplace
description: 插件市场领域。任务涉及插件广场页、在线 Skill 广场 registry 同步、skill 安装/卸载/启停 API、与官网仓库的市场契约时，先读本文档再动代码。
---

# 插件市场（Marketplace）领域地图

职责边界：插件广场页面、在线广场 registry 同步与安装。skill 的解析/注入机制属 plugin-system 领域；官网仓库（website/）不在本仓库。

## 关键文件

**前端（2026-08 二改：VS Code 扩展栏形态，主入口）**
- `frontend/src/components/MarketSidebarPanel.vue` — **左栏列表面板**（rail 广场按钮 → `toggleLeftPane('market')` 打开）：顶部搜索（过滤全部分组）+ 两个折叠分组「已安装（含重扫按钮）/ Marketplace」（dev-board#67 起在线 Skill 广场与插件广场收进同一个「Marketplace」组）。**每个分组内部是横向标签**（`.msb-tabs` 胶囊）：已安装 = [插件 | Skill]（`installedTab`，插件在前 = 面板型 skill ∪ JAR/Web 插件，Skill = 纯对话型，判据仍是 `isPanelSkill`）；Marketplace = [Skill | 插件]（`marketTab`）。竖着叠分组会把后一组推出视口，是当初被用户打回的形态，别改回去。行内布局：分类图标+名称+一行描述+版本·下载·分类，行内快捷安装钮，点行 emit `open-detail`；面板型深底图标（`.msb-row-glyph.is-plugin`）。标签标题复用 `sectionPluginTitle`/`sectionSkillTitle` 词条，与 `MarketPane.vue` 整页版「已安装」tab 的分区一致。
- **「语音」合并插件（dev-board#66）**：概念模型是**左栏一个图标 = 一个插件，skill 只在 AI 对话生效**。语音合成（text-to-speech）与会议录音（meeting-recorder）共占 rail 'voice' 一个面板位，广场三处（左栏列表 / 整页已安装 / 详情页）都必须显示**一个**「语音」条目——分组定义 `VOICE_PLUGIN_GROUP` 与合成视图 `buildVoiceGroupSkill()` 在 `leftSidebarPlugins.js`，detail 的 spec 带 `group:true`（'voice' 不是 registry 条目，详情页不去在线广场查它）。启停一体：开关一次翻全部成员，后端 `SkillRegistry` 每次扫描后还做状态收敛（任一启用 → 全部启用），防「tab 可见但 kick-off 命不中 skill」的静默断裂。新增面板内多 tab 的合并插件时照这套（分组定义 + 三处 UI + 后端收敛名单）。
- `frontend/src/components/MarketDetailPane.vue` — **中栏详情 tab**（overview `openMarketDetail(spec)` 打开，`tabType:'market-detail'`、id=`market-detail_{kind}_{id}`、单例、isTabVisible 常显）：头部图标+衬线标题+作者/版本/下载+动作区（Skill：安装/更新/卸载/生效方式三档；插件：安装带权限确认/启停 switch/卸载），正文触发词「」排版、能力、详细信息（标识/来源/主页，主页 emit open-url 走浏览器 tab）。
- 两件通过 `uni.$emit('awd:market-changed')`（详情→列表）与 `'awd:market-changed-from-sidebar'`（列表→详情）互相刷新；组件卸载时 $off，不涉页面栈多实例地雷。
- `frontend/src/components/MarketPane.vue` — 原整页版（深绿 hero 三 tab），现有两个宿主：admin 页内嵌
  （`admin.vue` 的 `activeNav==='plugins'` 分支，`<MarketPane :standalone="false">`，与其余设置项一致的页内切换）
  与 `frontend/src/pages/plugin-market/plugin-market.vue` 薄壳独立页（`:standalone="true"`，仅保留给直链）。
  **视觉规范以官网 `aiworkdeckweb/DESIGN.md` 为准**；新两件是浅色工作台密度形态（VS Code 扩展栏/详情页结构 + 产品浅色绿系）。
- `frontend/src/config/icons.js` — `catContract/catLitigation/catCompliance/catResearch/catCorporate/catOffice/catOther` 七枚分类图标，**与官网 `components/skills/CategoryIcon.tsx` 的映射一一对应**，改一边必须同步另一边，否则同一个 Skill 在官网与桌面端长相不同。
- `frontend/src/services/api.js` :407-485 — plugins、skills、skills/market 三组 HTTP 封装。
- 入口：`frontend/src/pages/admin/admin.vue` 系统管理侧边栏项 `{key:'plugins', label:'插件广场'}`——2026-08 起**页内切换**
  （onNavTap 不再 navigateTo，内容区内嵌 MarketPane；`?nav=plugins` 深链同样可达）。**leftSidebarPlugins.js 不含市场入口**（那是 IDE 左栏业务插件位）。

**后端**
- `backend/src/main/java/com/checkba/controller/ai/SkillController.java` — /api/skills：list、{id}/enable|disable、rescan、market/list|install|uninstall。
- `backend/src/main/java/com/checkba/controller/ai/PluginController.java` — /api/plugins：list、启停、rescan、market/list|install|uninstall、market/sync-revoked。
- `backend/src/main/java/com/checkba/service/ai/PluginMarketService.java` — **在线插件安装与验签**（Ed25519 公钥内置，逐文件 SHA-256 校验，临时目录+原子移动，装后默认禁用）。
- `backend/src/main/java/com/checkba/service/ai/PluginRevocationService.java` — 平台封禁列表同步（启动时 + 每 24h），命中强制禁用且不可重新启用。
- `backend/src/main/java/com/checkba/service/ai/skill/SkillMarketService.java` — 在线广场客户端。市场契约见 `docs/SKILL_SPEC.md` §8 / §8.1（端点表、registry 与 bundle 的字段契约、id 正则与卸载守卫），实现细节以此类 Javadoc 为准。
- `backend/src/main/java/com/checkba/service/ai/skill/SkillRegistry.java` — 本地扫描/启停/rescan。
- `backend/src/main/java/com/checkba/service/ai/skill/SkillProperties.java` — ai.skills.*（dir/base-tools/registry-url/ttl）；registry-url 默认 `https://www.aiworkdeck.com/api/registry/skills`（application.yml ~:163）。

## 插件在线分发（JAR）

**规范：`docs/PLUGIN_DISTRIBUTION.md`（跨仓库契约的权威定义）。改这条链路先读它。**

与 Skill 分发的根本区别：插件是可执行代码、与宿主同 JVM 同权限，因此
**必须人工审核 + 平台签名 + 客户端验签**，且没有自动通过路径。Skill 是纯文本，
登录即发布，两套流程不要混用。

- 状态机 pending → approved（签名上架）/ rejected，已上架可 revoke 封禁。
- 签名 Ed25519，覆盖包内每个文件的 SHA-256；canonical JSON 两端必须逐字节一致
  （键序 files < id < publishedAt < version），改任一侧都要重跑
  `CrossLanguageSignatureTest` 对拍。
- 公钥配 `ai.plugins.registry-public-key`，**默认留空即拒绝一切在线安装**。
- 安装后插件默认禁用，配合「禁用即不加载 JAR」，用户确认前不执行任何插件代码。
- 官网侧实现在 aiworkdeckweb：`lib/plugins-store.ts`（受理检查）、
  `lib/plugin-signing.ts`（签名）、`lib/plugin-scan.ts`（常量池扫描 + permissions 交叉验证）、
  `app/[lang]/plugins/submit`（提交页）、`app/[lang]/admin/PluginReview.tsx`（审核台）。

### 三方 Web 插件（Phase B，2026-08-19）

提交包新增 `web/` 目录，`manifest.frontendEntry` 指向其中（`web/index.html`）。
**纯 web 插件可以没有 JAR**——不进 JVM，风险量级低一档；`web/` 下的文件与 JAR 一样进
`files` 哈希表、被同一个签名覆盖。JS 没有常量池，自动扫描降级为「外联 URL 字面量提取 +
权限交叉验证」，以人工审核为主。

客户端侧：`controller/ai/PluginWebController`（`GET /api/plugin-web/{id}/**`，服务
`plugins/<id>/web/`，只服务已启用插件，CSP 按 manifest `network` 权限放开
`connect-src`）+ `PluginPane.vue` 的 sandbox iframe 与 postMessage 桥。
形态、协议与 SDK 契约见 `docs/PLUGIN_SPEC.md` §8 与 `.claude/agents/plugin-system.md`。

`manifest.packs: ["<packId>"]`：`PluginMarketService.install` 成功后逐个
`NativePackService.installAsync`；**装不上不回滚插件只记 WARN**——pack 有自己的状态机与重试面，
一次网络抖动不该吃掉刚装好的插件。三方插件要带重资源走这条路，不撑大 registry 的 20 MB 受理线。

## 原生资源包（native pack）分发（2026-08）

**规范：`docs/NATIVE_PACK_DISTRIBUTION.md`（第四种分发形态的权威定义）。**

- 后端 `service/pack/NativePackService` + `controller/PackController`（/api/packs：list、{id}/status、{id}/info、{id}/install、{id}/uninstall）。签名沿用插件 registry 密钥对（`ai.plugins.registry-public-key`，未配置即拒装），但盖在 manifest **原始字节**上（旁挂 .sig），不走 canonical JSON。
- 下载**不经官网应用层**：镜像静态直出 `https://{www.aiworkdeck.com|workdeck.ai}/plugin-packs/<id>/…`（`ai.packs.base-urls`），断点续传（.part + Range）+ 压缩包哈希 + 包内 `contents.sha256` 逐文件复核 + 原子指针切换。
- 前端：MarketSidebarPanel / MarketDetailPane 对 `packId` 非空且 `packReady:false` 的面板 skill 显示「需下载资源包」与字节级进度；LitigationVisualPanel 顶部有下载状态条。
- 三方 pack 提交/审核/签名在官网仓（`lib/packs-store.ts`、admin PackReview、`GET /api/registry/packs/revoked`），发布件出到 outbox 后由服务器侧脚本上架静态目录，新加坡镜像 SG 侧拉取。
- pack 发布链：`.github/workflows/pack-release.yml`（tag `pack-<id>-v<ver>`）出未签名产物，`deploy/publish-pack.sh` 负责服务器侧签名（私钥不离开官网机）、双机上架与指针切换。

## 官网 registry 契约

- **列表**：`GET {registryUrl}` → skill 元数据 JSON 数组，字段对应 MarketSkillView：id/name/description/icon/version/author/authorDisplayName/triggers[]/allowedTools[]/downloads/updatedAt/homepage/**priceCents/pricingModel**（`installed`、`purchased` 由本地判定）。
- **下载**：`GET {registryUrl}/{id}/bundle` → `{id, version, files:{"skill.yml":"…","prompt.md":"…"}}`；只认白名单键 skill.yml/prompt.md（BUNDLE_FILES），值必须字符串，缺任一安装失败。
- HTTP：hutool，连接 5s/读 10s 超时；`httpGet(url, bearer)` 是可覆写测试 seam，返回 `RegistryReply(status, bytes)`（状态码交调用方判，402 不在 seam 里抛）；无鉴权的 `httpGet(url)` 是它的薄包装。

## 付费项（PR-D，2026-08）

> 账户连接、entitlement 判定、官网 API 契约的全貌在 `.claude/agents/licensing-billing.md`；本节只讲广场这条链路。

- **契约**：registry 列表含 `priceCents`（分，0=免费）与 `pricingModel`（当前只有 `once`）；付费项的
  `bundle` / `file` 端点要求 `Authorization: Bearer awdk_` 且已购，否则 402
  `{code:"payment_required", priceCents, itemName}`。已购清单来自官网 `GET /api/account/entitlements`。
- **单一判定出口**：`backend/src/main/java/com/checkba/service/market/MarketPurchaseGate.java`。
  Skill 与插件两条链路共用它做「免费判定 / 未连接账户拦截 / 402 翻译 / 分转元」四件事，
  不要在各自 service 里再写一套文案。
- **feature 命名空间**：`skill:<id>` / `plugin:<id>`，与本地 SKU 键（`clipboard.unlimited` 等
  FeatureCatalog 常量）在同一个 entitlements 列表里但语义不同。只用
  `MarketPurchaseGate.skillFeature/pluginFeature` 构造，**绝不拿条目 id 直接当 feature 查**。
- **降级三条**（都有单测钉住）：
  1. registry 的 `priceCents` 缺失 / 负数 / 超上限（¥100,000，`MarketPurchaseGate.normalizePrice`）
     → 一律归一为 0 = 免费。旧 registry 上不能把免费项锁住；畸形值也不能展示成假价格，
     真付费项由官网 402 兜底，不会因此白拿。前端 `marketPricing.priceCentsOf` 同口径再兜一次；
  2. 安装前查元数据拿价格失败（列表不可达）→ 按免费继续（网络抖动不该连免费项都装不上），
     但**本机有账户 Key 就照样附上 Bearer**（`bearerForUnknownPrice`）：这一步分不清「真免费」
     与「付费但价格没查到」，不带 Key 的话后者官网必 402，一个真已购的用户会被反过来指控没付费；
     免费项的 bundle/file 端点不看 Authorization，附上无副作用；
  3. 免费项**不带** Authorization、不查账户——bundle/file 请求逐字节与改造前一致。
     注意这不等于「不多一次往返」：`install()` 会先拉一次 registry 列表拿价格，**免费项也走这一步**。
- **价格必须服务端自查**：`install()` 先拉一次 registry 列表定价，不信前端传来的 priceCents，
  否则等于让客户端决定付费闸门何时生效。这也是免费项唯一多出来的一次往返，别为了省它按前端值分流。
- **错误信封红线**：付费闸门是业务错误不是掉线，**响应不许带 code=4010**——
  PR4-0 起 `frontend/src/services/api.js` 只认 code=4010 判定未登录（已不做「登录/未授权/请先」
  中文子串匹配），命中就清本地会话，浏览器端还会跳登录页。两个测试文件里的 `assertNotMistakenForLogout` 钉住了这条。
- **402 ≠ 用户没买过**：本机未连账户时官网无从查购买记录，`paymentRequired` 在这种情况下
  说的是「去连接账户」而不是「去购买」。
- **前端**：`frontend/src/utils/marketPricing.js` 是价格展示与状态判定的唯一出口
  （`priceLabel` / `paidState` / `canInstall` / `purchaseUrl`），MarketSidebarPanel、MarketDetailPane、
  MarketPane 三处共用。四态：免费 / 已购（直接装）/ 未购已连账户（「购买」外链）/ 未连账户（「需连接账户」跳设置）。
- **购买外链地雷**：官网**没有** `/zh/plugins/{id}` 路由（只有 `/zh/plugins` 列表页带购买按钮），
  registry 里插件 `homepage` 默认值指向的路径并不存在，拿它当购买入口会 404。
  购买链接一律用 `purchaseUrl(kind, id)`，且走 `openExternalUrl`（系统浏览器）——
  支付要用用户已登录的浏览器会话，内嵌 tab 里付不了。
- 广场列表响应额外带 `accountConnected`，省得前端为一个布尔再打一次 `/api/account/status`。
  代价是 `GET /market/list`（Skill 与插件两个）**要求登录**：`purchased` / `accountConnected`
  是账户隐私，团队服务器部署下不能匿名可读（与 EntitlementController 同口径）。
- **账户状态变了要广播**：设置页连接/断开账户后 `admin.vue` 发
  `awd:market-changed` + `awd:market-changed-from-sidebar` 两个事件（两个订阅方：左栏
  MarketSidebarPanel / 中栏 MarketDetailPane，整页 MarketPane 也订了前者）。
  不发的话——设置页是 `navigateTo` 打开的、广场那页并不销毁——用户从「需连接账户」点进设置、
  连完账户返回，广场还是旧数据，再点又回设置页，转不出去。

## 安装/卸载链路

- 安装：前端 POST /api/skills/market/install {id} → admin 校验 → `requireValidId`（正则 `^[a-z0-9][a-z0-9-]{1,49}$`，兼防路径穿越）→ 下载 bundle → 写 `{ai.skills.dir}/{id}/` 两文件 → `rescan()`。**重装即覆盖更新**。
- 卸载：来自插件的 skill 拒绝卸载（走插件管理）；canonical 路径校验防符号链接逃逸，只删 skills 正下方子目录。
- **安装目标目录 == 内置 skill 扫描目录**（默认 `backend/skills/`），在线安装的与内置的（listing-pathway）并列共存、同一套扫描/启停。
- 注册表离线只影响"在线广场"区块（marketError 区块内提示，不 500），本地插件/skill 不受影响。

## 数据模型

**插件与 skill 不入库，文件系统为准**。数据库唯一相关表 `system_setting`（config_key/value）：`ai.skills.disabled`、`ai.plugins.disabled` 各存禁用 id 的 JSON 数组，内存缓存 TTL 5s，默认全启用。

## 鉴权

写操作（启停/rescan/install/uninstall）需管理员：`X-Session-Id` 头 → userId → AdminAccessService.isAdmin（桌面单机=全员管理员）；list 类登录即可。

## 已知地雷

- 官网侧 Skill 广场在独立仓库（website/，不在本仓库），改契约要两边同步（参考 skill-marketplace 双仓 PR 惯例）；官网提交表单曾因 invalid_id 挡掉投稿，id 校验规则两侧必须一致。
- **registry 的 `icon` 字段是 emoji（如 "◆" "🚀"），前端一律不渲染它**——图标从 category 推导。渲染 `icon` 就等于把 emoji 放回界面，触全站红线。
- 分类筛选依赖后端 `MarketSkillView.category`，该字段 #198 才加。**跑在旧后端（≤ v0.8.0）上时分类会全归「其他」，这是后端版本旧，不是前端 bug**；排查前先 `curl /api/skills/market/list` 看响应里有没有 category。
- 桌面端 9696 是真实后端端口，测试市场功能别 mock 错对象。
- bundle files 白名单意味着官网新增文件类型（如图标文件）需要同时改 BUNDLE_FILES 和官网打包端。
- **Web 插件的 SDK 有三份副本**：源头 `sdk/plugin-sdk/awd-plugin-sdk.js`、官网模板 `lib/plugin-template.ts` 的 `WEB_SDK_JS`、示例 `examples/hello-web-plugin/web/awd-plugin-sdk.js`，必须逐字节一致；桥协议还有第四处实现（`PluginPane.vue` 宿主端）。改协议是四处同批次的事，单改一处的表现是插件卡在「等待宿主握手」。
- **本仓 `skills/<id>/skill.yml` 里的 `category` 字段，和这里说的 `MarketSkillView.category`（contract/litigation/compliance/… 七类 + icons.js/CategoryIcon.tsx 图标映射）是两套完全不同的东西，只是字段名撞了**：前者是 `SkillDefinition.category`，取值来自 `MatterCategory` 枚举的中文 display（如「合规监管」「争议解决」），只在命中触发词时喂 `matter.classified` 埋点用（见 `SkillRouter.java`），`SkillController.SkillView` 压根不把它序列化进 `/api/skills/list` 响应；后者是**在线 registry**（网站提交时选的 category）才有的字段，只出现在 `GET /api/skills/market/list` 的 `MarketSkillView` 里。随包本地内置的 skill（诉讼可视化、会议录音、脱敏这类，从未经过官网提交流程）在市场面板「已安装」列表里的图标走的是 `isPanelSkill` 判定出的 `ICONS.panelLeft`，根本不读 `category`——本地 skill.yml 的 `category` 值不需要、也不应该对着 icons.js 的七个英文 key 去选，对着 `MatterCategory.java` 的中文枚举值选就对了（2026-08-19 脱敏改造踩过这个概念混淆，核实后确认两者无关联）。

## 验证

- 后端：`cd backend && mvn test`（JDK 21；SkillMarketService 有测试 seam）。
- 页面：dev 起后端(9696)+前端(5173) 从 admin 页进插件广场手测；或 `npm run test:app-e2e`。
