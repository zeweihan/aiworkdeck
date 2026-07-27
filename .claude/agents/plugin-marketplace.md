---
name: plugin-marketplace
description: 插件市场领域。任务涉及插件广场页、在线 Skill 广场 registry 同步、skill 安装/卸载/启停 API、与官网仓库的市场契约时，先读本文档再动代码。
---

# 插件市场（Marketplace）领域地图

职责边界：插件广场页面、在线广场 registry 同步与安装。skill 的解析/注入机制属 plugin-system 领域；官网仓库（website/）不在本仓库。

## 关键文件

**前端**
- `frontend/src/pages/plugin-market/plugin-market.vue` — 插件广场单页，「广场 / 已安装」两 tab；广场 tab 内再分 Skill / 插件两个分区（seg-row 切换）。Skill 分区带搜索与七分类 chips；插件分区接在线注册表，安装前弹权限确认。已安装 tab 分插件区与 Skill 区，Skill 行是生效方式三档下拉。
- `frontend/src/services/api.js` :407-485 — plugins、skills、skills/market 三组 HTTP 封装。
- 入口：`frontend/src/pages/admin/admin.vue` :584 系统管理侧边栏项 `{key:'plugins', label:'插件广场', route:'/pages/plugin-market/plugin-market'}`。**leftSidebarPlugins.js 不含市场入口**（那是 IDE 左栏业务插件位）。

**后端**
- `backend/src/main/java/com/checkba/controller/ai/SkillController.java` — /api/skills：list、{id}/enable|disable、rescan、market/list|install|uninstall。
- `backend/src/main/java/com/checkba/controller/ai/PluginController.java` — /api/plugins：list、启停、rescan、market/list|install|uninstall、market/sync-revoked。
- `backend/src/main/java/com/checkba/service/ai/PluginMarketService.java` — **在线插件安装与验签**（Ed25519 公钥内置，逐文件 SHA-256 校验，临时目录+原子移动，装后默认禁用）。
- `backend/src/main/java/com/checkba/service/ai/PluginRevocationService.java` — 平台封禁列表同步（启动时 + 每 24h），命中强制禁用且不可重新启用。
- `backend/src/main/java/com/checkba/service/ai/skill/SkillMarketService.java` — 在线广场客户端（**市场契约的权威定义在此类 Javadoc**，SKILL_SPEC.md §8 未含 market 端点）。
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

## 官网 registry 契约

- **列表**：`GET {registryUrl}` → skill 元数据 JSON 数组，字段对应 MarketSkillView：id/name/description/icon/version/author/authorDisplayName/triggers[]/allowedTools[]/downloads/updatedAt/homepage（`installed` 由本地判定）。
- **下载**：`GET {registryUrl}/{id}/bundle` → `{id, version, files:{"skill.yml":"…","prompt.md":"…"}}`；只认白名单键 skill.yml/prompt.md（BUNDLE_FILES），值必须字符串，缺任一安装失败。
- HTTP：hutool，连接 5s/读 10s 超时；非 200 抛 IllegalStateException；`httpGet` 是可覆写测试 seam。

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
- 桌面端 9696 是真实后端端口，测试市场功能别 mock 错对象。
- bundle files 白名单意味着官网新增文件类型（如图标文件）需要同时改 BUNDLE_FILES 和官网打包端。

## 验证

- 后端：`cd backend && mvn test`（JDK 21；SkillMarketService 有测试 seam）。
- 页面：dev 起后端(9696)+前端(5173) 从 admin 页进插件广场手测；或 `npm run test:app-e2e`。
