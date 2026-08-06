# AI Workdeck 产品埋点与匿名使用统计体系设计

- 日期：2026-08-06
- 状态：设计定稿，待实施
- 决策人：韩泽伟
- 关联：README 隐私承诺修订、官网仓 ingest 端点（跨仓）

## 1. 目标

三件事合一：

1. **产品埋点**：功能使用、活跃、留存数据，支撑市场/融资汇报与产品迭代。
2. **脱敏后的法律事项类型统计**：用户在处理哪类法律事务的分布（只要类型，绝不要内容），支撑行业报告、赋能行业发展。
3. **定期报告产出能力**：官网侧管理看板 + 可导出的聚合数据。

非目标（本期不做）：第三方分析 SDK（PostHog/神策等）、漏斗级实时分析、A/B 实验框架。

## 2. 已拍板的关键决策

| 决策点 | 结论 |
|---|---|
| 上报默认状态 | **默认开，可随时关闭**（opt-out）。同步修订 README 中英文隐私承诺为「匿名聚合使用统计默认开启，可一键关闭；文档与对话内容永不离开本机」 |
| 上报粒度 | **分级**：Tier 1 日聚合计数默认开；Tier 2 脱敏事件流单独开关、**默认关** |
| 一期范围 | **一步到位含上报**：本地账本 + 插桩 + 设置开关 + 官网 ingest + 管理看板 |
| 事项分类口径 | **skill category 映射为主，AI 意图分类兜底**（未命中 skill 的对话由平价模型打一个枚举标签，仅标签落库，原文不留） |

## 3. 隐私红线（不可协商，测试锁定）

以下字段**永不出本机**，由白名单机制在采集层强制执行：

- 文件名、文件路径、项目名（`UserActivityLog.targetName` 的教训）
- 对话消息文本、prompt、AI 回复内容
- `ConversationSummary` 的 keyPoints / legalReferences / mentionedEntities
- 原始 `conversationId`（`conv-${Date.now()}` 含时间戳可预测；Tier 2 关联键用 HMAC 派生）
- 用户名、账户 key、任何 PII

允许出本机的只有：**枚举值**（事件名、工具名、action 名、模型名、供应商、skill id、事项类别、终态、平台）与**数值**（计数、耗时、token 量、文件数）。

与既有承诺的关系：README 现行承诺是「默认无遥测」。本设计改为「匿名聚合统计默认开启」，属于公开口径变更，必须与代码同 PR 修订 `README.md`（约 :220）与 `README.zh-CN.md`（约 :217 及 :131-172 数据处理章节），并新增 `legal/PRIVACY.md` 说明采集内容、开关位置、关闭方式。

## 4. 总体架构

```
桌面端（本机）                                官网服务器（aiworkdeck.com）
┌────────────────────────────────┐
│ 12 个收口点插桩                  │
│   ↓ TelemetryService.record()  │           ┌──────────────────────┐
│ telemetry_event 本地表（明细）    │           │ POST /api/telemetry/  │
│   ↓ 每日聚合                    │  Tier 1   │   rollup（日聚合）      │
│ telemetry_daily_rollup（日汇总） │──24h────→│ POST /api/telemetry/  │
│   ↓（Tier 2 开启时）             │  Tier 2   │   events（脱敏事件流）  │
│ 事件流批量脱敏导出                │──24h────→│   ↓ 落库               │
│                                │           │ 管理看板（admin 口令）   │
│ 设置页开关 / 本地使用统计页        │           └──────────────────────┘
└────────────────────────────────┘
```

- 上报调度照抄 `PluginRevocationService` 模式：启动时 + 每 24 小时一次，5 秒超时，失败静默、下轮重试（本地 rollup 带 `uploaded` 标记，补传最多回溯 30 天）。
- 后端纯本地（desktop profile）与云端部署（server profile）都插桩；上报开关按部署模式区分，参照 `open-local` 的 profile 门控先例。

## 5. 客户端设计（本仓）

### 5.1 标识

- **安装 ID**：`~/.aiworkdeck/install-id`，首启生成 UUID，匿名、按安装唯一。与既有 `local.mv.db` / `entitlements.json` 同目录同模式。
- **安装密钥**：`~/.aiworkdeck/install-secret`，随机 32 字节，仅本机持有、永不上传。Tier 2 事件的会话关联键 = `SHA-256(installSecret || conversationId)` 前 16 位十六进制，防止时间戳泄露与跨装置关联。

### 5.2 新增表（JPA `ddl-auto: update`，免迁移脚本）

**`telemetry_event`**（本地明细账本，与 `user_activity_log` 严格分离，不污染工时计费数据）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | 自增 |
| ts | Instant | 事件时间 |
| eventName | String | 事件名（见 5.4 分类法） |
| attrs | TEXT(JSON) | **仅白名单字段** |
| convKey | String(16) | HMAC 派生会话关联键，可空 |
| appVersion | String | 版本号 |

**`telemetry_daily_rollup`**：`date`、`payload`（JSON，见 5.5）、`uploaded`（bool）、`uploadedAt`。

本地保留策略：明细 90 天滚动清理，rollup 保留 1 年。

### 5.3 核心服务（新包 `com.checkba.service.telemetry`）

- **`TelemetryService`**：唯一采集入口 `record(String eventName, Map<String,Object> attrs)`。异步（复用 taskExecutor）、任何异常吞掉不影响业务、内部先过 `TelemetryAttrWhitelist` 再落库。**本地账本永远记录**（纯本地数据，与既有 `token_usage`/`user_activity_log` 本地记录同先例），两个开关只控制"出本机"的上报，语义干净：关开关 = 数据不外发，本地统计页不受影响。
- **`TelemetryAttrWhitelist`**：每个事件名对应允许的字段集与类型（枚举/数值/布尔）。白名单外字段**静默丢弃并计数**（`telemetry.dropped` 自监控指标）。单元测试锁定：塞入 fileName/message 等字段必须被丢弃。
- **`TelemetryRollupService`**：每日聚合 + 启动时补算昨日。
- **`TelemetryUploadService`**：24h 调度上报，Tier 1 rollup 必传（开关开时），Tier 2 事件流仅在独立开关开启时批量传（单批上限 5000 条，本地压缩）。
- **`MatterClassifierService`**：事项类型分类，见 5.6。

### 5.4 事件分类法 v1（12 个收口点）

| 事件名 | 插桩位置 | 白名单字段 |
|---|---|---|
| `app.start` | 后端启动完成 | appVersion, platform, profile |
| `ai.turn` | `AgentOrchestrator.handleUserMessage`（发起）+ `AgentRunStateService.mark`（7 种终态与耗时） | mode, model, provider, outcome, durationMs, attachmentCount, hasPinnedSkill |
| `ai.tool` | `AgentOrchestrator.dispatchTool`（原生 + XML 兜底两路收口） | toolName, success, durationMs, fileEffect, fromPlugin |
| `ai.model` | `ChatModelFactory`（真实落地模型，非请求传入值） | provider, targetModel |
| `editor.action` | `libreofficeExecutorClient.executeCommand`（前端，AI 与人工全收口） | action, agent(bool), success, durationMs, whitelistRejected |
| `editor.bridge` | `EditorBridgeService.executeEditorCommand`（服务端往返与超时） | action, outcome(ok/timeout/error), durationMs |
| `skill.activated` | `SkillRouter.activateForTurn` | skillId, how(pinned/matched) |
| `skill.lifecycle` / `plugin.lifecycle` | `SkillRegistry.setEnabled`、`PluginController` 安装/卸载/启停 | id, op |
| `project.created` | `ProjectService.createProject` / `LocalProjectService.openLocalFolder` / `CloudSyncService.cloneFromCloud` | kind(managed/local/cloud), reused, importedCount |
| `file.changed` | `ProjectFileService.signalChange` | （仅计数） |
| `version.op` | `WorkSessionService.ensureSession/endSession` + `VersionController.onVersionError`（失败率） | op, ok |
| `ui.nav` | 前端 `App.vue` `uni.addInterceptor`（页面路由）+ `panelSwitching.toggleLeftPane`（面板，区分收展/切换三分支） | page 或 panelKey, branch |

token/成本**不新埋**：`token_usage` 表已有，rollup 直接聚合查询（注意 `costSource` 的 platform/estimate 两套口径不得合并）。

前端事件经新端点 `POST /api/telemetry/event` 入账本（走既有 `X-Session-Id` 鉴权；与 `/api/activity/log` 平行，互不复用）。

已知地雷：改 `AgentOrchestrator` 构造器注入 TelemetryService 时**必须同步 EvalHarness**（历史上已踩两次）。

### 5.5 Tier 1 日聚合负载（唯一默认出本机的数据）

```json
{
  "installId": "uuid",
  "date": "2026-08-06",
  "appVersion": "0.10.x",
  "platform": "darwin-arm64",
  "counters": {"ai.turn": 42, "ai.turn.finished": 39, "editor.action.agent": 120, "...": 0},
  "byProvider": {"OPENROUTER": 40}, "byModel": {"...": 40},
  "byTool": {"doc_replace_text": 55},
  "bySkill": {"shareholder-meeting-verification": 3},
  "byMatterCategory": {"公司治理": 3, "资本市场证券": 1, "非法律事务": 2},
  "tokens": {"prompt": 0, "completion": 0, "byCostSource": {"platform": {}, "estimate": {}}},
  "activeDays": 1, "activeMinutes": 96
}
```

### 5.6 法律事项类型分类

固定枚举 v1（口径粗、稳定、可扩展）：`公司治理`、`资本市场证券`、`并购交易`、`争议解决`、`合同审查起草`、`合规监管`、`知识产权`、`劳动人事`、`破产重整`、`其他法律事务`、`非法律事务`。

两级判定，结果写在会话首轮的 `ai.turn` 事件上：

1. **skill 命中即类型**：`skill.yml` 新增可选字段 `category:`（内置 skill 全部补齐：股东大会核查/上市路径 → 资本市场证券 等）；`SkillDefinition` 加字段。官网 registry 契约同步加 `category`（可选，缺省归「其他法律事务」）。
2. **AI 意图分类兜底**：会话首轮未命中 skill 时，`MatterClassifierService` 异步用最平价模型对**首条用户消息**做单标签分类（严格输出枚举之一）。仅标签落库，原文不留副本、不进事件、不上传。因分类会消耗用户 token，**仅在「分享匿名使用统计」开关开启时运行**；关闭时本地统计页的事项分布仅来自 skill category。分类失败归「其他法律事务」。成本控制：每会话最多一次。

### 5.7 设置与用户可见价值

- **设置页（admin.vue）新增「数据统计」分区**：两个开关（「分享匿名使用统计」默认开；「分享脱敏使用明细」默认关）、采集内容说明（明确列出永不采集清单）、隐私文档链接。落 `system_setting` 表（`telemetry.rollup.enabled` / `telemetry.events.enabled`），关闭即时生效、停止一切上报（本地账本继续为本地统计页服务）。
- **本地使用统计页**：用户看自己的数据——AI 对话轮次、工具调用量、AI 修订被接受数、文档编辑量、事项类型分布、估算节省工时。数据全部来自本地账本，与上报开关无关。这是「采集换价值」的产品面。

## 6. 服务端设计（官网仓，跨仓改动）

- `POST /api/telemetry/rollup`：body 即 5.5 负载；按 `(installId, date)` upsert 幂等；无鉴权（与 registry 同级的匿名端点），限流 + 负载 schema 校验 + 体积上限。
- `POST /api/telemetry/events`：Tier 2 批量事件；同样匿名、限流。
- 存储：网站现有数据库加 `telemetry_rollup` / `telemetry_event` 表。
- **管理看板**（复用 skill 广场的 admin 口令机制）：日活/周活/月活（按 installId）、版本分布、功能使用 Top、模型/供应商分布、skill 与事项类型分布、留存粗算（installId 首见日期 vs 活跃日期）。支持按日期区间导出 CSV（喂融资材料和行业报告）。
- 部署：沿用现有流程，**服务器侧上线由用户手动执行**（同增量更新先例）。

## 7. 验证计划

1. 白名单单元测试：违禁字段必被丢弃；每个事件的字段集与类型锁定。
2. rollup 聚合正确性测试：造明细算日汇总，含 costSource 分口径。
3. 上报静默失败测试：服务器不可达时业务零影响、下轮补传。
4. 开关语义测试：rollup 开关关 → 零外发请求（本地照常记录）；Tier 2 关 → 只传 rollup；两开关状态变更即时生效。
5. 分类器测试：枚举外输出归「其他法律事务」；skill 命中时不调用分类器。
6. `mvn test`（JDK 21）+ 既有 e2e 基线不回归；EvalHarness 构造器同步检查。
7. 端到端：本地起后端 + 指向测试 ingest，验证 24h 调度（可调短）、幂等 upsert、看板出数。

## 8. 交付切分

- PR-1（本仓）：标识 + 账本 + TelemetryService/白名单 + 12 处插桩 + rollup/上报 + 设置分区 + 本地统计页 + skill category + 分类器 + README/PRIVACY 修订。
- 官网仓 change-set：ingest 两端点 + 表 + 管理看板 + registry 契约加 category。
- 领域文档维护：本 PR 触及 ai-chat / ai-doc-bridge / sidebar-shell / licensing-billing 契约的部分，同 PR 更新对应 `.claude/agents/*.md`。
