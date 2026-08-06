# 产品埋点与匿名使用统计 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/ANALYTICS_TELEMETRY_DESIGN.md` 落地：桌面端本地事件账本 + 12 收口点插桩 + 日聚合上报 + 设置开关 + 本地统计页；官网仓匿名 ingest + 融资演示级管理看板。

**Architecture:** 桌面端唯一采集入口 `TelemetryService`（白名单强制、异步、静默失败），本地永远记录，两个开关只控制出本机；上报照抄 `PluginRevocationService` 的「启动 + 24h + 静默失败」模式。官网 better-sqlite3 追加迁移 v6，两个匿名 ingest 端点，admin 口令看板。

**Tech Stack:** Java 17 / Spring Boot（JPA ddl-auto）、uni-app Vue3、Next.js 16 + better-sqlite3。

## Global Constraints

- 全局禁 emoji（代码/UI/文档/commit）。
- 本机 `mvn` 必须 JDK 21（`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`）。
- 前端 npm 不是 pnpm。
- 隐私红线字段（文件名/路径/项目名/消息文本/摘要实体/原始 conversationId/PII）永不进入上报负载，白名单测试锁定。
- 改 `AgentOrchestrator` 构造器必须同步 `EvalHarness`（历史踩过两次）。
- `docs/` 在 .gitignore，入库要 `git add -f`。
- 官网仓当前检出在 `claude/plugin-accountid-cors` 且有未提交改动：**必须用独立 git worktree 从 `origin/master` 开分支**，不碰现有检出。
- 官网视觉遵循其 `DESIGN.md`（衬线、无 emoji）；看板要达到融资演示级。
- 领域文档维护：契约变更同 PR 更新 `.claude/agents/*.md`。

---

## A 桌面端（本仓，分支 claude/ide-analytics-tracking-7c47b9）

### Task A1: 安装标识 InstallIdentityService

**Files:**
- Create: `backend/src/main/java/com/checkba/service/telemetry/InstallIdentityService.java`
- Test: `backend/src/test/java/com/checkba/service/telemetry/InstallIdentityServiceTest.java`

**Produces:** `String installId()`（UUID 字符串，持久化 `<dataDir>/install-id`）、`String convKey(String conversationId)`（SHA-256(installSecret || conversationId) 前 16 hex；secret 32 字节随机持久化 `<dataDir>/install-secret`，权限 0600）。dataDir 取 `security.license.dir`（默认 `~/.aiworkdeck`，与 EntitlementService 同源），可用 `@Value` 注入以便测试指向临时目录。

- [ ] 测试：首次调用生成并落盘；二次调用读回同值；convKey 对同输入稳定、不含原始 id 子串、长度 16。
- [ ] 实现 + `mvn -pl . test -Dtest=InstallIdentityServiceTest`（JDK 21）通过。
- [ ] Commit `feat(telemetry): 安装标识与会话关联键派生`。

### Task A2: 账本实体与仓储

**Files:**
- Create: `backend/src/main/java/com/checkba/model/entity/TelemetryEvent.java`（id, ts(Instant), eventName, attrs TEXT, convKey(16), appVersion）
- Create: `backend/src/main/java/com/checkba/model/entity/TelemetryDailyRollup.java`（id, date(LocalDate unique), payload TEXT, uploaded bool, uploadedAt）
- Create: `backend/src/main/java/com/checkba/repository/TelemetryEventRepository.java`、`TelemetryDailyRollupRepository.java`

**Produces:** repo 方法：`findByTsBetween`, `deleteByTsBefore(Instant)`；rollup：`findByDate`, `findByUploadedFalseAndDateAfter(LocalDate)`。JPA ddl-auto 建表，无迁移脚本。

- [ ] 实体 + 仓储，编译过即可（行为在 A3/A5 测）。Commit 并入 A3。

### Task A3: TelemetryService + 白名单

**Files:**
- Create: `backend/src/main/java/com/checkba/service/telemetry/TelemetryAttrWhitelist.java`
- Create: `backend/src/main/java/com/checkba/service/telemetry/TelemetryService.java`
- Test: `backend/src/test/java/com/checkba/service/telemetry/TelemetryServiceTest.java`

**Produces:**
```java
public void record(String eventName, Map<String,Object> attrs);            // 异步、吞异常
public void recordConv(String eventName, String conversationId, Map<String,Object> attrs); // 带 convKey
```
白名单：静态 Map<eventName, Set<允许字段>>，值仅接受 String(枚举语义)/Number/Boolean；未知事件名整条丢弃并计 `telemetry.dropped`；白名单外字段剔除。事件名集合与设计 5.4 一致：`app.start / ai.turn / ai.tool / ai.model / editor.action / editor.bridge / skill.activated / skill.lifecycle / plugin.lifecycle / project.created / file.changed / version.op / ui.nav`。

- [ ] 测试：合法事件落库；`fileName`/`message`/`path` 等违禁字段被剔除；未知事件不落库；record 内部抛异常不外溢（mock repo 抛错）。
- [ ] 实现（@Async("taskExecutor") 或内部单线程 executor——照 orchestrator 用法选 taskExecutor）+ 每日清理 90 天前明细（@Scheduled 每日一次）。
- [ ] `mvn test -Dtest=TelemetryServiceTest` 通过，Commit `feat(telemetry): 事件账本与白名单采集服务`。

### Task A4: 开关与设置端点

**Files:**
- Modify: 复用 `SystemSettingService`；Create: `backend/src/main/java/com/checkba/controller/TelemetryController.java`

**Produces:** 设置键 `telemetry.rollup.enabled`（缺省 true）/ `telemetry.events.enabled`（缺省 false）。端点：
- `GET /api/telemetry/settings` → `{rollupEnabled, eventsEnabled}`
- `POST /api/telemetry/settings` body 同上（既有会话鉴权模式，照 ActivityLogController）
- `POST /api/telemetry/event` body `{eventName, attrs}` → 前端事件入账本（仅白名单事件 `editor.action / ui.nav / app.start` 允许经此进入）
- `GET /api/telemetry/summary?days=30` → 本地统计页数据（聚合查询：轮次、工具 Top、editor.action agent/人工计数、bySkill、byMatterCategory、token 合计——token 查 token_usage 表分 costSource）

- [ ] 测试：开关默认值；POST 后 GET 读回；/event 拒绝白名单外事件名。
- [ ] Commit `feat(telemetry): 开关设置与前端事件入口`。

### Task A5: 后端插桩（12 收口点中的服务端 9 处）

**Files (Modify):**
- `service/ai/AgentOrchestrator.java:309` handleUserMessage 起点（mode/model/attachmentCount/hasPinnedSkill 记 `ai.turn.start` 合并进 ai.turn 由终态补齐——实现取巧：start 存内存 Map<conversationId, TurnCtx>，mark 终态时合成一条 `ai.turn`）
- `service/ai/AgentRunStateService.java:41` mark() 终态（7 态 + durationMs）
- `service/ai/AgentOrchestrator.java:180` dispatchTool（toolName/success/durationMs/fileEffect/fromPlugin → `ai.tool`）
- `service/ai/ChatModelFactory.java:280/:74`（provider/targetModel → `ai.model`）
- `service/ai/skill/SkillRouter.java:88` activateForTurn（skillId/how → `skill.activated`）
- `service/ai/EditorBridgeService.java:213`（action/outcome/durationMs → `editor.bridge`）
- `service/ai/skill/SkillRegistry.java:181` + `controller/ai/PluginController.java:69,76,120,138`（→ `skill.lifecycle`/`plugin.lifecycle`）
- `service/ProjectService.java:36`、`service/LocalProjectService.java:74`、`version/cloud/CloudSyncService.java:221`（→ `project.created`）
- `service/ProjectFileService.java:1194` signalChange（→ `file.changed` 仅计数）
- `version/WorkSessionService.java:298/endSession` + `version/VersionController.java:138 onVersionError`（→ `version.op`）
- 后端启动完成事件（ApplicationReadyEvent → `app.start`，appVersion 从既有版本来源取）
- **同步 `EvalHarness` 构造器**（TelemetryService 注入 orchestrator 后）

**注意：** 插桩必须全部 try/catch 包裹或经 record() 内部吞错；不改变任何业务返回值与异常路径。

- [ ] 每处插桩后跑相关既有测试：`mvn test`（全量，JDK 21）green。
- [ ] Commit `feat(telemetry): 服务端九处收口点插桩`。

### Task A6: 事项分类（skill category + AI 兜底）

**Files:**
- Modify: `service/ai/skill/SkillDefinition.java`（+`category` 字段）与 skill.yml 解析处（SkillRegistry 扫描逻辑）
- Modify: `backend/skills/*/skill.yml`（内置 skill 补 `category:`；股东大会核查/上市路径 → 资本市场证券）
- Create: `backend/src/main/java/com/checkba/service/telemetry/MatterClassifierService.java`
- Test: `backend/src/test/java/com/checkba/service/telemetry/MatterClassifierServiceTest.java`

**Produces:** 枚举 `MatterCategory`（11 值，见设计 5.6）。`classifyAsync(conversationId, firstUserMessage)`：仅当 rollup 开关开 && 会话未分类 && 未命中 skill；调 `ChatModelFactory.getChatModel(平价模型)` 单标签输出，严格解析（非枚举→其他法律事务）；结果 record 到该会话的 `ai.turn` 事件 attrs.matterCategory（或独立事件 `matter.classified`，实现取后者更简单——**取独立事件**）。SkillRouter 命中时直接 record `matter.classified`（category 来自 skill.category）。

- [ ] 测试：枚举外输出归错类兜底；skill 命中不调 LLM（mock）；开关关不运行。
- [ ] Commit `feat(telemetry): 法律事项类型分类（skill category + AI 兜底）`。

### Task A7: 日聚合与上报

**Files:**
- Create: `backend/src/main/java/com/checkba/service/telemetry/TelemetryRollupService.java`、`TelemetryUploadService.java`
- Modify: `backend/src/main/resources/application.yml`（`telemetry.ingest-url: https://www.aiworkdeck.com/api/telemetry`，desktop profile 同）
- Test: `backend/src/test/java/com/checkba/service/telemetry/TelemetryRollupServiceTest.java`

**Produces:** rollup payload 完全按设计 5.5（installId/date/appVersion/platform/counters/byProvider/byModel/byTool/bySkill/byMatterCategory/tokens/activeMinutes）。`rollupFor(LocalDate)` 幂等 upsert 本地表。Upload：`@PostConstruct` 守护线程 + `@Scheduled` 24h（照 PluginRevocationService.java:34-45 模式），补传 `uploaded=false && date>今-30d`，HttpClient 5s 超时、失败静默；Tier 2 开时 POST 批量事件（单批 5000，字段仍过白名单二次校验）。

- [ ] 测试：造明细→rollup 数字正确（含 token 分 costSource、activeMinutes 按事件时间去重分钟数）；开关关→upload no-op（mock transport 断言零调用）；服务器 500→uploaded 保持 false。
- [ ] Commit `feat(telemetry): 日聚合与 24 小时匿名上报`。

### Task A8: 前端插桩 + 设置分区 + 本地统计页

**Files:**
- Create: `frontend/src/utils/telemetryClient.js`（`track(eventName, attrs)`：POST /api/telemetry/event，失败静默、防抖合批可后置不做——YAGNI，单发即可）
- Modify: `frontend/src/composables/libreofficeExecutorClient.js:153`（action/__agent/success/durationMs/whitelistRejected → editor.action）
- Modify: `frontend/src/pages/project-overview/panelSwitching.js:5`（三分支 → ui.nav {panelKey, branch}）
- Modify: `frontend/src/App.vue` onLaunch 加 `uni.addInterceptor`（navigateTo/reLaunch/redirectTo → ui.nav {page}）
- Modify: `frontend/src/pages/admin/admin.vue`（新「数据统计」分区：两开关 + 永不采集清单说明 + 打开本地统计）
- Create: 本地统计视图（并入 admin.vue 数据统计分区展示，**不新开页面**——页面栈多实例是已知地雷，admin 内分区最稳）：调 `GET /api/telemetry/summary`，展示轮次/工具 Top/AI vs 人工编辑量/事项分布/токен合计。样式跟 admin 现有分区，无 emoji。
- Modify: `frontend/src/services/api.js`（新增 telemetry 三个 API 封装）

- [ ] `npm run check:emits`（既有 CI 检查）+ 手动 lint 通过；桌面 dev 起后端点开设置页验证开关与统计出数。
- [ ] Commit `feat(telemetry): 前端插桩、设置分区与本地使用统计`。

### Task A9: 公开口径修订

**Files:**
- Modify: `README.md`（约 :220）、`README.zh-CN.md`（约 :217 与 :131-172 表格加一行「使用统计 | 匿名聚合计数，默认开启可关闭 | 仅计数出本机」）
- Create: `legal/PRIVACY.md`（采集什么/不采集什么/开关位置/关闭效果/Tier 2 说明/联系方式）

- [ ] 中英措辞一致：「匿名聚合使用统计默认开启，可在设置一键关闭；文档与对话内容永不离开本机」。
- [ ] Commit `docs: 隐私口径修订与 PRIVACY.md`。

### Task A10: 领域文档 + 全量验证 + PR

- [ ] 更新 `.claude/agents/ai-chat.md`（orchestrator 构造器新依赖、telemetry 采集点契约）、`sidebar-shell.md`（admin 新分区）、`licensing-billing.md`（新外发通道）。
- [ ] `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test` 全量 green。
- [ ] `cd frontend && npm run check:emits`。
- [ ] `git push -u origin claude/ide-analytics-tracking-7c47b9` + `gh pr create` + 合并 master（auto 模式拦截时提示用户 Bypass——见既有合并权限地雷）。

## B 官网仓（/Users/zewei/Documents/2024-2044/5-Tech/1-1 aiworkdeckweb）

### Task B1: worktree + 迁移 v6

- [ ] `git worktree add ../aiworkdeckweb-telemetry -b feat/telemetry-ingest origin/master`
- [ ] `lib/db.ts` MIGRATIONS 追加 version 6：
```sql
CREATE TABLE telemetry_rollup (
  installId TEXT NOT NULL, date TEXT NOT NULL, appVersion TEXT, platform TEXT,
  payload TEXT NOT NULL, receivedAt TEXT NOT NULL,
  PRIMARY KEY (installId, date));
CREATE INDEX idx_tr_date ON telemetry_rollup (date);
CREATE TABLE telemetry_event (
  id INTEGER PRIMARY KEY AUTOINCREMENT, installId TEXT NOT NULL, ts TEXT NOT NULL,
  eventName TEXT NOT NULL, convKey TEXT, appVersion TEXT, attrs TEXT, receivedAt TEXT NOT NULL);
CREATE INDEX idx_te_install ON telemetry_event (installId, ts);
```

### Task B2: ingest 端点

- Create: `app/api/telemetry/rollup/route.ts`（POST，匿名；校验 installId UUID 形状、date ISO、payload ≤64KB、counters 全数值；`(installId,date)` upsert；简单内存限流每 IP 每分钟 60 次）
- Create: `app/api/telemetry/events/route.ts`（POST 批量 ≤5000 条、单条字段白名单同桌面端事件名集合、总体 ≤2MB）
- Create: `lib/telemetry-store.ts`（写入与聚合查询统一入口）

- [ ] `npm run typecheck` 过；curl 本地 dev 验证 upsert 幂等与校验拒绝。

### Task B3: admin 聚合 API + 融资演示级看板

- Create: `app/api/admin/telemetry/route.ts`（`isAdminRequest` 鉴权；返回：日活/周活/月活序列、安装总数与新增、版本分布、byTool/bySkill/byMatterCategory Top、留存粗算（按 installId 首见 cohort）、token 总量趋势）
- Create: `app/[lang]/admin/telemetry/page.tsx` + 组件（遵循站点 DESIGN.md 衬线视觉、无 emoji；**融资演示级**：大数字 KPI 卡（活跃安装/月活/AI 对话总量/文档处理量）、活跃度面积图、事项类型分布横条图、版本采用堆叠图、留存热力表；图表用轻量内联 SVG 组件——站内无图表库，不引重依赖，自绘 SVG 保持风格一致；提供日期区间选择与 CSV 导出按钮（`/api/admin/telemetry?format=csv`））
- Modify: admin 导航加入口。

- [ ] `npm run typecheck` + `npm run build` 过；本地 dev 用 seed 数据截图自查视觉。
- [ ] Commit + push + `gh pr create`（合并后**服务器部署由用户手动**，PR 描述里写清）。

## 验证总清单（对应设计 §7）

- [ ] 白名单违禁字段测试（A3）
- [ ] rollup 聚合正确性含 costSource 分口径（A7）
- [ ] 上报静默失败与补传（A7）
- [ ] 开关语义即时生效（A4/A7）
- [ ] 分类器兜底与 skill 短路（A6）
- [ ] mvn 全量 + check:emits + 官网 typecheck/build（A10/B3）
- [ ] 端到端：桌面 dev 指向本地官网 dev ingest，验证 rollup 入库与看板出数（B3 后做一次）
