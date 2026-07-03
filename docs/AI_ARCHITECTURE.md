# AI 编排器架构基线（重建版）

> 本文件在 PR#83 描述中被引用但当时未实际入库；现随 Phase 2.5（WPS 遗留命名清理）
> 补建，内容依据 PR#83/#84 记录与当前代码重建。SSE 事件字典等接口细节以
> `docs/ai_agent_dev.md` 为准。

## 1. 分层概览

```
接入层   AiChatController（薄壳）→ AiChatService（业务）
上下文层 ContextAssemblerService（只读组装）+ FileContextLoader（文件/OCR/文件夹读取）
编排层   AgentOrchestrator（ReAct 循环；不 import 任何具体工具类）
协议层   XmlToolCallParser（<tool_code> 兜底协议，容错解析）
能力层   ToolRegistry（内置工具自动注册 + 插件 JAR 懒注册 + 统一分发）
桥接层   EditorBridgeService（SSE client_action ↔ 前端嵌入式 LibreOffice 编辑器）
记忆层   写侧 MemoryPipelineService / 读侧 MemoryManager（五作用域，拟人化检索排序）
```

- 配置外置：上下文限值统一在 `AiContextProperties`（yml 前缀 `ai.context`，
  token 预算可按模型覆盖）；法律信息保护正则在 `resources/legal/protected-patterns.yml`。
- 记忆五作用域：user / project / conversation / file / global。

## 2. 工具层（ToolRegistry）

- 内置工具：实现 `AgentToolComponent` 标记接口的 Bean 中的 `@Tool` 方法自动注册；
  插件 JAR 工具懒注册。**新增工具严禁改 AgentOrchestrator**。
- `@ToolMeta` 提供中文显示名 / 分类 / 文件副作用元数据。
- `projectId` / `conversationId` / `userId` 由服务端 `ToolContext` 强制注入，
  LLM 传值一律忽略。
- `TOOL_NAME_ALIASES`：旧工具名 → 真实工具名的灰度更名机制，原生 function calling
  与 XML 兜底协议两条分发路径都会先做别名解析。

### 2.1 工具命名规范与现行别名（Phase 2.5）

编辑器已从 WPS WebOffice 全面迁移到 LibreOffice（Epic #43 / #79 / #81），
Phase 2.5 将 LLM 面的 30 个文档编辑工具 `wps_*` 更名为 `doc_*`
（宿主类 `WpsTools` → `DocumentEditTools`），`pptx_*` 不变。

- **别名清单**：全部 30 条 `wps_*` → 同名 `doc_*`（如 `wps_find_replace` →
  `doc_find_replace`），另有历史别名 `search_laws` → `search_web`。
  完整列表见 `ToolRegistry.TOOL_NAME_ALIASES`。
- **保留期**：wps_* 别名自 0.4.x 引入，**至少保留两个发布版本，移除不早于
  0.6.0**；移除前需确认线上老对话历史中旧名调用率足够低。
- 数据库历史消息中的旧工具名只影响展示（displayName 兜底"工具执行"），
  不做数据迁移。

## 3. 前后端契约（保持旧名）

以下 `wps_*` 字符串是前后端契约（见 `docs/ai_agent_dev.md` §2.2），
Phase 2.5 **未改动**，命名迁移列入 Phase 3：

| 契约 | 现名 |
|-----|------|
| SSE 流式写入事件 | `wps_stream_data` |
| SSE client_action 命令载荷 | `tool: "wps_command"`（内含 action 字典）|
| SSE client_action 打开/刷新 | `action: "wps_open_file"` / `"wps_reload_file"` |
| 同步打开命令 | `wps_open_file_sync` |
| 结果回调路由 | `POST /api/ai/agent/wps-result` |
| write_docx 输出 JSON 键 | `wps_file_id`（前端有字符串匹配逻辑）|
| 文件实体字段 | `ProjectFile.wpsFileId` |

## 4. Phase 路线图

- **Phase 1（PR#83，已合并）**：ToolRegistry 统一工具层、XML 协议解析器抽取、
  记忆五作用域 + 写入管线接线。
- **Phase 2（PR#84，已合并）**：控制器瘦身（AiChatController 847→290 行）、
  `ai.context` 配置外置、拟人化记忆检索排序（隐含重要性 × 时间衰减 × 随机）。
- **Phase 2.5（本次）**：WPS 遗留命名清理——内部类名 editor 化、LLM 面工具名
  `wps_*` → `doc_*`（别名灰度）、前端注释/内部命名 editor 化。
- **Phase 3（待办清单）**：
  - [ ] **SSE 事件名双轨迁移**：`wps_stream_data` → `doc_stream_data`、
        `client_action.tool: wps_command` → `editor_command`、
        `wps_open_file(_sync)` / `wps_reload_file` → `doc_*`、
        路由 `/wps-result` → `/editor-result`。方案：后端双发（新旧事件并行）、
        前端双听，观察一个发布周期后摘旧名；**合并前必须在 Electron 桌面端
        真机实测文档打开、查找替换、流式写入三条链路**。
  - [ ] `wps_*` 工具名别名移除（不早于 0.6.0，见 §2.1）。
  - [ ] 前端零散 WPS 遗留：`VariablePanel.getWps` prop、`ChatInterface.vue`
        `wps-tip-*` CSS 类、`ProjectFile.wpsFileId` 字段语义梳理。
  - [ ] 插件广场沙箱 / MCP SDK 标准化 / Skill 体系 / 多智能体（Phase 1 遗留规划）。

## 5. 不变式

> 原五条不变式全文未随 PR#83 入库；以下为依据 PR 记录与代码重建的强约束，
> 后续修订以本节为准。

1. **编排器不感知具体工具**：AgentOrchestrator 不 import 任何具体工具类；
   新增工具只实现 `AgentToolComponent` 或放入 plugins/，不改编排器。
2. **服务端上下文不可伪造**：`projectId/conversationId/userId` 一律由
   `ToolContext` 注入，LLM 传值忽略。
3. **读写分离**：ContextAssembler（读侧）不做写操作；记忆写入只走
   `MemoryPipelineService`。
4. **配置外置**：上下文限值改 `ai.context` yml，不在代码里加常量；
   法律保护模式改 `protected-patterns.yml`。
5. **契约兼容**：SSE 事件字典、参数别名、遗留缺省值等对外行为保持；
   任何更名必须走别名/双轨灰度，禁止一刀切。
