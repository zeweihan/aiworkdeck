# AI 编排器架构基线（AI Orchestrator Architecture）

> 状态：现行基线。Phase 1（PR #83）、Phase 2（PR #84）、Phase 2.5（WPS 遗留命名清理）已合并。
>
> 说明：Phase 1 曾撰写过本文档但未随 PR 入库，本文为按 PR #83/#84 实际代码重建的版本，
> 以当前代码为准。后续架构变更必须同步更新本文。

## 1. 分层基线

自上而下七层，依赖只允许向下：

| 层 | 职责 | 关键类 |
|---|---|---|
| 接入层 | HTTP/SSE 出入口、鉴权（session→userId）、DTO | `AiChatController`、`AiAgentController` |
| 编排层 | Agent 循环（Thought→Action→Observation）、取消、SSE 事件分发 | `AgentOrchestrator`、`XmlToolCallParser` |
| 业务编排 | 同步 chat 流程、多模态组装、助手实例管理 | `AiChatService`、`AiAssistantService`、`MultiModalContentService`、`GeminiCacheService` |
| 上下文层 | 消息栈组装、上下文压缩、法律信息保护、文件内容加载 | `ContextAssemblerService`、`ContextCompressor`、`LegalInfoProtector`、`FileContextLoader`、`FileContentExtractorService` |
| 能力层（工具） | 工具注册与分发、插件懒注册、服务端上下文注入、编辑器桥接 | `ToolRegistry`、`AgentToolComponent`、`@ToolMeta`、`ToolContextHolder`、`EditorBridgeService` |
| 记忆层 | 读侧检索（拟人化排序）、写侧管线（Episode/项目记忆/MemCell） | `MemoryManager`、`MemoryPipelineService`、`MemCellExtractor`、`AgenticRetriever` |
| 模型/基础设施 | 模型工厂、向量存储、配置 | `ChatModelFactory`、`PgVectorConfig`、`AiModelProperties`、`AiContextProperties` |

## 2. 五条不变式

任何 AI 功能开发不得破坏以下不变式（重建版，语义与 Phase 1 一致）：

1. **编排器不 import 具体工具类。** 新增工具只实现 `AgentToolComponent` + 标注 `@ToolMeta`，
   由 `ToolRegistry` 自动注册；插件 JAR 工具懒注册。严禁改 `AgentOrchestrator` 加分发分支。
2. **记忆读写侧分离。** 读侧 = `ContextAssemblerService`（组装时检索注入，不做提取式写入）；
   写侧 = `MemoryPipelineService`（每轮循环结束后异步触发）。检索命中的
   `lastAccessedAt/accessCount` 元数据更新属读侧统计，在 `MemoryManager` 内部完成。
3. **身份字段服务端注入。** `projectId/conversationId/userId` 由 `ToolContextHolder` 每次调用
   装填，LLM 传入的同名参数一律忽略，防伪造跨项目 ID。
4. **SSE 事件字典与前端契约不动。** 事件名、chat 接口出入参结构是对外契约，重构不得更改；
   新增能力用新事件/新字段向后兼容。
5. **行为保持 + 配置外置。** 重构默认行为保持（默认配置值 = 原硬编码值）；新增限值/阈值一律
   进 `@ConfigurationProperties`（`ai.context.*` 等）或资源文件，不写死在代码里。

## 3. 关键机制速查

### 工具层（Phase 1）
- 内置工具：实现 `AgentToolComponent` 的 Bean 自动注册；`@ToolMeta` 声明中文显示名/分类/文件副作用。
- XML `<tool_code>` 兜底协议解析在 `XmlToolCallParser`（多行 Python/三引号/JSON 风格/`<ctrl46>`/最长工具名优先，全容错）。
- 工具名别名：`ToolRegistry.TOOL_NAME_ALIASES`（旧名 → 真实工具名），原生 function calling
  与 XML 兜底两条分发路径都先做别名解析——工具更名一律走该机制灰度，禁止一刀切。

### 工具命名与别名（Phase 2.5）

编辑器已从 WPS WebOffice 全面迁移到 LibreOffice（Epic #43 / #79 / #81），Phase 2.5 将
LLM 面的 30 个文档编辑工具 `wps_*` 更名为 `doc_*`（宿主类 `WpsTools` → `DocumentEditTools`，
`WpsActionService` → `EditorBridgeService`，`WpsResultController` → `EditorResultController`），
`pptx_*` 不变。

- **别名清单**：全部 30 条 `wps_*` → 同名 `doc_*`（如 `wps_find_replace` → `doc_find_replace`），
  另有历史别名 `search_laws` → `search_web`。完整列表见 `ToolRegistry.TOOL_NAME_ALIASES`。
- **保留期**：`wps_*` 别名自 0.4.x 引入，**至少保留两个发布版本，移除不早于 0.6.0**；
  移除前需确认线上老对话历史中旧名调用率足够低。
- 数据库历史消息中的旧工具名只影响展示（displayName 兜底"工具执行"），不做数据迁移。

### 编辑器前后端契约（Phase 2.5 保持旧名）

以下 `wps_*` 字符串是前后端契约（事件字典见 `docs/ai_agent_dev.md` §2.2），受不变式 4 约束，
Phase 2.5 未改动，命名迁移列入 Phase 3：

| 契约 | 现名 |
|-----|------|
| SSE 流式写入事件 | `wps_stream_data` |
| SSE client_action 命令载荷 | `tool: "wps_command"`（内含 action 字典）|
| SSE client_action 打开/刷新 | `action: "wps_open_file"` / `"wps_reload_file"` |
| 同步打开命令 | `wps_open_file_sync` |
| 结果回调路由 | `POST /api/ai/agent/wps-result` |
| write_docx 输出 JSON 键 | `wps_file_id`（前端有字符串匹配逻辑）|
| 文件实体字段 | `ProjectFile.wpsFileId` |

### 子 Agent（Phase 3C 多智能体协作第一阶段）

主循环通过 `dispatch_subtask(task_description, expected_output, tool_scope)` 工具
（`SubAgentTools`，普通 `AgentToolComponent`，不变式 1 保持——编排器无感知）把自包含的
复杂子问题委派给 `SubAgentService`（`service/ai/subagent/`）。子 Agent = 独立消息栈 +
受限工具集 + 独立模型 + 主循环的极简版双协议循环（原生 function calling + XML 兜底），
**不复用**主循环的 SSE 推流与消息持久化，中间过程不进对话历史，只把最终结构化结果
（success / result / error / toolsUsed / rounds，JSON）作为工具输出返回。

约束清单（限值全部在 `ai.subagent.*` / `SubAgentProperties`，不变式 5）：

| 约束 | 机制 | 默认值 |
|-----|------|-------|
| 轮数上限 | 循环轮数达 `max-rounds` 即返回明确失败结果 | 6 |
| 并行度 | 专用线程池（与主循环 taskExecutor 隔离），池大小 = 同时运行子任务上限 | 3 |
| token 预算 | 每轮前按 `token-budget × chars-per-token` 估算消息栈字符数，超限中止 | 30000 |
| 超时 | `dispatch` 阻塞等待 `timeout-seconds`，超时取消任务并返回错误结果，不挂死主循环 | 180s |
| 防递归 | 双防线：子 Agent 工具集无条件排除 `dispatch_subtask`；分发前二次拦截返回明确拒绝错误 | — |
| 身份继承 | 子 Agent 工具调用的 `ToolContext` 继承主会话 projectId/conversationId/userId（不变式 3），模型可独立（`ai.subagent.model`，留空继承主会话） | — |

子任务开始/结束向主会话推送 **新增** SSE 事件 `subtask_progress`（结构对齐
`TaskProgressEvent`，taskType=SUBTASK / source=SUB_AGENT / stage=started|succeeded|failed），
既有事件字典不变（不变式 4）。回放评测见 `cases-subagent.json`；
循环上限/预算/超时/防递归/身份继承由 `SubAgentServiceTest` 单测守护。

### 记忆作用域（Phase 1）
`MemoryEntry.scope`：`user`（跨项目偏好）/ `project`（默认）/ `conversation` / `file`（配 `sourceFileId`）/ `global`（通用知识）。

### 记忆写入管线（Phase 1 接线，Phase 2 增强）
每轮 Agent 循环结束后 `MemoryPipelineService` 异步执行：
- Episode 摘要（消息数 ≥ 15）
- 项目记忆正则提取（每轮，低成本）
- MemCell LLM 提取（消息数 ≥ 4），写入走 `MemoryManager.saveMemoryDeduplicated`：
  向量近重复检测（cos ≥ 0.95，即 relevanceScore ≥ 0.975），保留 importance 更高者。

### 拟人化检索排序（Phase 2）
检索综合分 = **隐含重要性 × 时间衰减 × 随机抖动**（`MemoryManager`）：
- 隐含重要性 = importance + 受保护 0.15 + 类型加成（决策 0.10 / 结论 0.08 / 引用 0.05 / 偏好 0.03）
- 时间衰减 = 0.5^(距上次命中天数 / (30 天 × 记忆强度))；记忆强度 = 1 + ln(1 + accessCount)
  （复述效应）；受保护记忆不衰减
- 随机性 = 分数 ±5% 抖动 + 10% 概率"偶然想起"候选池冷门记忆
- RRF 混合检索融合分应用同样因子；检索命中批量更新 `lastAccessedAt/accessCount`

### 配置外置（Phase 2）
- `ai.context.*`（`AiContextProperties`）：token 预算（`model-token-budgets` 按模型覆盖，
  modelKey 由编排器穿透到压缩器）、chars-per-token、压缩保留条数、文件大小/数量/字符上限、OCR 扩展名。
- 法律保护正则：`src/main/resources/legal/protected-patterns.yml`，`LegalInfoProtector` 只加载与匹配。
- 向量维度：`PgVectorConfig` 从 `EmbeddingModel.dimension()` 动态读取，失败回退 1536。
  注意：切换 embedding 模型（维度变化）需 DROP 旧表由 `createTable` 重建。

## 4. Phase 路线图

- **Phase 1（PR #83，已完成）**：ToolRegistry 统一工具层、XML 协议解析抽取、记忆五作用域、
  MemoryPipelineService 接线、AgentOrchestrator 1671→660 行。
- **Phase 2（PR #84，已完成）**：AiChatController 847→290 行（业务下沉专职 Service）、
  ContextAssembler 不再读文件系统、AiContextProperties 配置外置、法律正则外置、
  拟人化记忆检索排序、MemCell 语义去重、向量维度动态化。
- **Phase 2.5（已完成）**：WPS 遗留命名清理——内部类名 editor 化（DocumentEditTools /
  EditorBridgeService / EditorResultController）、LLM 面工具名 `wps_*` → `doc_*`
  （TOOL_NAME_ALIASES 别名灰度）、system_prompt 同步、前端注释/内部命名 editor 化。
- **Phase 3（计划）**：
  - [ ] **SSE 事件名双轨迁移**：`wps_stream_data` → `doc_stream_data`、
        `client_action.tool: wps_command` → `editor_command`、
        `wps_open_file(_sync)` / `wps_reload_file` → `doc_*`、路由 `/wps-result` →
        `/editor-result`。方案：后端双发（新旧事件并行）、前端双听，观察一个发布周期后
        摘旧名；**合并前必须在 Electron 桌面端真机实测文档打开、查找替换、流式写入三条链路**。
  - [ ] `wps_*` 工具名别名移除（不早于 0.6.0，见 §3「工具命名与别名」）。
  - [ ] 前端零散 WPS 遗留：`VariablePanel.getWps` prop、`ChatInterface.vue` `wps-tip-*`
        CSS 类、`ProjectFile.wpsFileId` 字段语义梳理。
  - [x] **插件广场 MVP**（PR #88）：manifest 规范 v1（见 docs/PLUGIN_SPEC.md，新增
        permissions/tools/author/homepage）、启停持久化（`system_setting` key =
        `ai.plugins.disabled`，JSON 数组，默认全启用）、`PluginService.isEnabled()` /
        `getPluginIdForTool()` 查询接口、重扫接口、前端插件广场页
        （pages/plugin-market，入口在系统管理侧边栏）；示例插件 examples/hello-plugin/。
  - [x] **插件启停接入 ToolRegistry**（Phase 3A）：三处消费点（`getAllSpecifications` /
        `toolNamesLongestFirst` / `resolve`）经 `pluginToolEnabled()` 过滤——
        `pid == null || pluginService.isEnabled(pid)` 才可见（内置工具 `pid == null`
        不受影响）。禁用后 LLM 看不到规格、XML 解析不识别、分发 not found；启用即恢复。
        启停查询走内存缓存 + TTL 重读（`ai.plugins.disabled-cache-ttl-ms`，默认 5s），
        工具调用高频路径不打库。
  - [x] **manifest permissions 分发前校验**（Phase 3A，规范 v2 见 docs/PLUGIN_SPEC.md §3）：
        工具级 `tools[].permissions` 声明所需能力，分发前校验「所需 ⊆ 插件声明」，
        未声明即拒绝并返回明确错误（`PluginService.missingPermissionsForTool()` +
        `ToolRegistry.execute()`）。
  - [ ] 插件进程级运行时沙箱：v2 权限校验是分发层的诚实声明模型，插件代码仍与宿主同进程；
        真正强制 file/network 隔离需进程级沙箱（独立进程 + IPC 或 SecurityManager 替代方案），
        列为后续项。
  - [x] **多智能体协作第一阶段（Phase 3C）**：`dispatch_subtask` 委派子任务
        （见 §3「子 Agent」）——独立消息栈/受限工具集/独立模型/轮数与预算与超时限值
        （`ai.subagent.*`）/防递归/身份继承，新增 SSE 事件 `subtask_progress`。
  - [ ] MCP SDK 标准化、Skill 体系、多智能体协作后续阶段（子任务规划器、跨子任务共享记忆）。
