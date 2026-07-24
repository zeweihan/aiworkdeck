# King IDE - AI Agent 架构与开发规范 (Spec v1.8)

## 1. 系统概览 (Overview)

v1.7 新增 **"任务分级路由 (Task Routing)"**、**"上下文 ID 注入"** 与 **"WPS 安全写入协议"**。
v1.8 新增 **"文件化产物生命周期 (File-based Artifact Lifecycle)"**：产物以 .md 文件形式存储于项目目录并支持多标签页编辑。

---

## 2. 通信协议 (SSE Protocol v1.2)

### 2.2 事件字典 (Event Dictionary)

| Event Type | Payload | 描述 |
| --- | --- | --- |
| `bubble_start` | `{ bubbleId, title, type: "chat|plan|execution" }` | 开启气泡 |
| `text_delta` | `{ content }` | Markdown 增量内容 |
| `step_update` | `{ bubbleId, stepId, status: "loading|done" }` | 更新步骤进度 |
| `artifact` | `{ operation: "create|update|resolve", id, type, status?, data?, meta? }` | 产物操作 |
| `client_action`（命令） | `{ tool: "editor_command", action, params, requestId, conversationId }` | 编辑器命令（前端执行后 POST `/api/ai/agent/editor-result` 回传结果）；同步打开为 `action: "doc_open_file_sync"` |
| `client_action`（打开/刷新） | `{ action: "doc_open_file" \| "doc_reload_file", fileId, fileName, fileType, wpsFileId, ... }` | 单向打开/刷新编辑器文档 |
| `doc_stream_data` | `{ content }` | 编辑器实时流式写入增量（doc_start_stream 激活后） |
| `bubble_end` | `{ bubbleId, status: "finished" }` | 气泡结束 |

> **双轨迁移期（AI_ARCHITECTURE.md Phase 3）**：以上编辑器契约的旧名
> `wps_stream_data` / `tool: "wps_command"` / `wps_open_file(_sync)` / `wps_reload_file` /
> 路由 `/wps-result` 仍由后端按"新名在前、旧名在后"双发（路由为别名保留），前端凭
> "先见新名"丢弃旧名去重；兼容一个发布周期后摘旧名。

### 2.3 事件扩充说明
- **`artifact` - 命名与存储规则**:
  - 前端收到 `artifact: create` 后，将在项目根目录的 `AI助手文件夹/` 下创建对应的 `.md` 文件。
  - **命名约定**:
    - `type: "task_list"` -> `AI任务清单.md`
    - `type: "implementation_plan"` -> `AI实施计划.md`
    - 其他 -> `AI产物_{id}.md`
- **`artifact` - 审批流 (Approval Workflow)**:
  1. **展示**: 前端在气泡中展示产物预览及"打开编辑"、"批准执行"按钮。
  2. **编辑**: 点击"打开编辑"，IDE 开启新标签页并加载上述 `.md` 文件，允许用户物理修改。
  3. **批准**: 点击"批准执行"，前端将读取文件的**最新物理内容**，并作为审批确认发送给后端。后端以此内容作为后续执行的唯一依据。
- **`artifact` - `operation: 'resolve'`**: 告知前端产物已通过审批。前端将气泡状态转变为"绿色历史记录" (Green Checkmark Bar)，内容变为只读快照。
- **`client_action` - `options.track_changes`**: 告知前端 WPS Bridge 在写入前是否开启"修订模式"。默认应为 `true`（安全留痕）。

---

## 3. 核心架构：后端编排中间件 (Agent Orchestrator)

### 3.5 任务分级路由 (Task Routing Strategy)
后端 Orchestrator 根据 LLM 的意图标签（Intent Tag）动态决定流程复杂度：

| Level | 意图 | 流程 |
| --- | --- | --- |
| **L1: Chitchat** | 问候/闲聊 | 直接输出文本，不触发任何 Artifact。 |
| **L2: Simple Execution** | 简单指令（如"查下《公司法》第16条"） | **直通车**：跳过 Task List 和 Plan 生成，直接调用工具，返回结果。 |
| **L3: Complex Workflow** | 文书起草/合规审查 | **完整流程**：生成 Task List -> 生成 Plan -> 等待确认 -> 逐步执行。 |

### 3.6 上下文 ID 注入 (Context Injection)
- **规则**: 在每次调用 LLM 前，中间件必须将当前 `TaskList ID`、`Plan ID` 及其状态快照拼接到 System Message 中。
- **目的**: 确保 LLM 知道"现在正在处理什么任务"，以便输出精准的 `<task_update id="...">` 标签。

---

## 4. 后端开发规范 (Backend)

### 4.4 标签容错与直通车
- **直通车判定**: 若 LLM 返回意图为 L2，Orchestrator 应跳过 `artifact: create` 的强制流程。
- **容错处理**: 若 LLM 输出非法 XML 或引用不存在的 `itemId`，静默丢弃并记录 Error Log。

### 4.5 物理文件对齐
- **原则**: 后端在接收到用户的 `approve` 请求时，必须能够处理随请求发送的最新文件内容。LLM 的后续计划必须基于此内容进行调整（如果用户做了修改）。

---

## 5. 提示词工程 (Prompt Engineering)

（与 Prompt v1.2 对齐）

---

## 6. 前端开发规范 (Frontend)

### 6.2 轻量级渲染 (Simple Bubble)
（同 v1.6）

### 6.3 WPS 安全写入协议 (Safety Write Protocol)
- **默认开启修订**: 当收到 `client_action: wps_write` 且 `options.track_changes !== false` 时，前端 Bridge **必须先**调用：
  ```javascript
  wps.ActiveDocument.TrackRevisions = true;
  ```
- **原因**: 保护用户原始文档，体现律师专业性（留痕存证）。
- **用户可关闭**: 仅当后端明确传递 `track_changes: false` 时，方可关闭修订模式。

---

## 7. 迭代演进 (Roadmap)

* **Phase 2 (产物与状态深度对齐)**:
    * 实现 3 级路由策略。
    * 后端增加 Context ID 注入逻辑。
    * 前端实现 .md 文件化产物编辑流。
    * 前端 WPS Bridge 增加 `TrackRevisions` 开关控制。