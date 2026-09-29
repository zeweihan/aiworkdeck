# 计划审阅：可编辑计划 + 批注（dev-board#1022）

日期：2026-09-29。状态：设计已由维护者确认（09:14），待实施。

## 1. 问题

计划模式（PLAN）下模型出一份计划，对话里的计划卡只有「按此推进 / 修订」。「修订」是一个裸
textarea，改完看不出哪里是自己改的，也没有批注；用户不满意就只能否决再让模型重出，陷入
批准/否决的死循环。Antigravity 的做法是把计划当文件改，还能对选中段落写 comment。

## 2. 已定决策（维护者 2026-09-29）

1. **批注与改动一起提交，模型直接执行**。不再回一版修订计划等第二次批准。
2. **在工作台的 md 标签里改**（像 Antigravity 改文件），批注也在标签里做；对话里的计划卡只留
   按钮与状态。
3. 「放弃修改」把文件恢复成基线并清掉批注（不是只关标签）。
4. 批注表按文件通用建，不只服务计划；将来 md/txt 文件复用同一套。

## 3. 现状事实（探查 2026-09-29，file:line 以当时为准）

- 计划卡由 `frontend/src/components/ArtifactCard.vue` 渲染，唯一使用点 `RootBubble.vue:39-49`，
  `actionable = isLatest && !bubble.isStreaming`。审批栏 `showApprovalBar = isPlanType && actionable
  && status==='draft'`；「按此推进」与「修订」在同一个 v-if 块里。
- 模型输出 `<artifact type="implementation_plan|task_list|plan" name="…">…</artifact>`。流式层
  `AgentStreamHandler.java:485-542` 发 SSE `artifact {operation:"create", id, type, status:"draft",
  data:{content}}`，**不带 fileName / filePath / name**。缓冲超 2000 字未闭合时按原文冲出去
  （:592-598），由前端 `useAgentStream.js:2281-2295` 兜底解析标签。
- 落盘在整轮结束后：`AgentOrchestrator.java:2040-2098` 正则重解析 content，文件名 `<name>.md`
  （截 30 字、替换非法字符；无 name 为 `Plan.md` / `Task List.md`），
  `projectFileService.saveArtifactFile(projectId, conversationId, filename, content, userId)`
  存到「AI 助手文件/<会话文件夹>/」，再以 text_delta 追加一行 markdown 引用
  「> 已保存到项目文件：<相对路径>」（`artifactSavedNoticeDelta`，:2765-2769）。
- 只有 `implementation_plan` 停机等审批（AWAITING_APPROVAL，:2103-2114）。
- 审批回路：`handleArtifactApprove`（`ChatInterface.vue:3955-3976`）拼 prompt 当普通用户消息发，
  displayText 只作展示；后端**无任何特殊处理**；artifact status 永远 draft，resolved 只是客户端
  `localResolved`；历史回放用同一解析器重建，按钮只靠 isLatest 控制。
- md 标签编辑器是 `PlainTextEditor.vue`（CodeMirror 6：`@codemirror/state|view|commands|language`，
  `@codemirror/lang-markdown`），有编辑/预览切换、自动保存走通用字节接口、`reloadFromBackend()`。
- ArtifactCard / 审批 / 修订目前**零测试**。

## 4. 设计

### 4.1 计划文件与卡片的绑定

- 后端 `saveArtifactFile` 成功后，在既有「已保存到项目文件」text_delta 之前，追加发一条 SSE
  `artifact {operation:"saved", id:<artifactId>, fileId, filePath:<相对路径>, type}`。artifactId
  要与流式层 create 事件的 id 一致：编排器重解析时按出现顺序与流式层 create 的 id 对齐
  （流式层把已发出的 artifact id 按顺序记在 handler 上，编排器落盘时取同序号；对不上就只发
  fileId + filePath，前端按 filePath 兜底匹配）。
- 前端 `useAgentStream.handleArtifactEvent` 处理 `saved`：给对应 artifact 补 `fileId / filePath`。
- 历史回放没有 saved 事件：`ArtifactCard` 点「打开修订」时若无 fileId，取气泡正文里
  「已保存到项目文件：」那行的路径，调新增接口 `GET /api/projects/{pid}/files/resolve?path=<相对路径>`
  （返回 fileId，404 即没有）解析；再解析不到才退回既有的卡内 textarea 修订。
- `RootBubble` 给 ArtifactCard 传 `bubble.content`（供解析路径）或直接传解析后的 `savedPath`。

### 4.2 审阅记录（后端）

新表（ddl-auto 建，`com.checkba.model.entity`）：

- `project_file_review`：`id, project_id, file_id, conversation_id, artifact_id(nullable),
  baseline_text(CLOB), status('open'|'submitted'|'discarded'), created_by, created_at, updated_at`。
  一个文件同一时刻至多一条 open 记录（唯一约束 file_id + status='open' 由服务层保证）。
- `project_file_review_comment`：`id, review_id, from_line, to_line, quoted_text, body,
  created_at`。锚点 = 行号区间 + 引用原文；行号只是提示，重定位以 quoted_text 为准。

REST（`/api/projects/{pid}/files/{fileId}/review`，走既有 `X-Session-Id` 与项目读写权限）：

- `POST` `{conversationId, artifactId, baselineText}` → 幂等：已有 open 记录直接返回它（不覆盖
  基线）。返回 `{review, comments[]}`。
- `GET` → 当前 open 记录与批注；没有返回 204。
- `POST /comments` `{fromLine, toLine, quotedText, body}` / `DELETE /comments/{cid}`。
- `POST /submit` → status=submitted，返回快照（供前端拼消息）。
- `POST /discard` → status=discarded，并把文件内容写回 baseline_text（服务端直接覆盖字节 +
  signalChange，前端随后 `reloadFromBackend()`）。

### 4.3 标签里的审阅态（前端）

- `openFile` 支持 `review: {reviewId 或 fileId, artifactId, conversationId, baselineText}` 选项，
  由 ArtifactCard 的「打开修订」传入；标签重新打开（刷新后）时由 PlainTextEditor 自己
  `GET review` 拿回 open 记录，所以审阅态不依赖标签元数据。
- PlainTextEditor 在 `review` 存在时：
  - 顶部审阅条 `PlanReviewBar.vue`：「计划审阅 · N 处改动 · M 条批注」+「按修订版推进」+「放弃修改」。
  - CM6 扩展 `utils/planReviewExtensions.js`（零依赖纯函数 + 扩展工厂，可单测）：
    - **改动装饰**：`lineDiff(baseline, current)` 行级 LCS（复用 ArtifactCard 里的 `lineDiffStats`
      路数，抽到 `utils/lineDiff.js`），新增/改动行 line decoration `cm-review-edited`（浅琥珀底
      `--awd-review-edited-bg` + 左侧 3px 标记条 `--awd-review-edited-bar`），删除处 block widget
      `cm-review-deleted`（灰色一行「已删除 k 行」，title 为原文）。颜色令牌加进唯一色源
      （见记忆 color-system-bamboo-moon），保持浅色。
    - **批注**：选区非空时在选区末尾浮出「+」按钮（tooltip 扩展）；点击弹出小输入框（textarea +
      保存/取消），保存即 `POST comments`；被批注文字 mark decoration `cm-review-commented`；
      右侧窄栏 `PlanReviewComments.vue` 列出「引用原文 → 评论」，可删（`DELETE`）。
    - **重锚定**：文档变化后按 quoted_text 在当前文本里搜索（先原区间附近，再全文），找不到的
      批注在右栏标「原文已改动」，仍参与提交。
  - 只在编辑态生效；预览态不画标记。自动保存照旧。
- ArtifactCard：审批栏改为「打开修订」+「按此推进」；有 open 记录时显示「修订中 · N 处改动 ·
  M 条批注」（由 PlainTextEditor 经全局 store / 事件回传，或卡片自己轮询 GET review），提交后显示
  「已按修订版推进」。既有 textarea 修订只在解析不到 fileId 时出现。

### 4.4 提交与回喂

「按修订版推进」（审阅条与卡片都可触发，同一函数 `submitPlanReview`）：

1. 先 flush 自动保存（复用 `flushActiveDocument`），再 `POST submit` 拿快照。
2. 拼 prompt（zh/en 随应用语言）：

   ```
   我已在计划文件中修订并加了批注，请以下方修订版计划为准直接执行；批注是对相应段落的补充要求，
   执行到该段时必须照办。
   改动摘要：共 N 处（+a 行 / -b 行）。
   批注（M 条）：
   1. 针对「<引用原文>」：<评论>
   ...
   修订版计划全文：
   <当前文件全文>
   ```
   displayText：「已按修订版推进（N 处改动、M 条批注）」。`sendMessage({mode:'AGENT'})`，与现有
   `handleArtifactApprove` 同一出口。
3. 标签退出审阅态（标记与右栏消失，文件保持修订后内容），卡片置 resolved。
4. 没有任何改动也没有批注时，「按修订版推进」等价于「按此推进」（prompt 用既有确认语）。

### 4.5 放弃修改

`POST discard` → 服务端把文件字节写回基线 → 前端 `reloadFromBackend()` → 退出审阅态；卡片回到
「打开修订 / 按此推进」。

### 4.6 不做的事

- 不做计划的多版本对比、不做批注回复线程、不做模型「回一版修订计划」（决策 1 明确不要）。
- 不改 `task_list` 的不停机行为。
- 不把批注写进 md 正文。

## 5. 测试与验收

- 纯函数单测（`node --test`）：`lineDiff` 装饰区间；批注重锚定（原文挪动 / 删除）；
  `buildPlanReviewPrompt` 拼装（含 0 改动 0 批注退化为确认语）；`isUserQuestionAwaiting` 不受影响。
- 后端单测：review 幂等创建、批注增删、submit 快照、discard 覆盖字节并 signalChange、
  resolve-by-path、saved 事件的 id 对齐（含对不上时只发 fileId 的退化）。
- 真渲染走查（H5 dev + 无头浏览器，照 `tests/project-home` 既有真渲染路数）：打开一份计划文件
  进审阅态 → 改两行 → 选一段加批注 → 截图核对琥珀标记、删除标记、右栏 → 点「按修订版推进」→
  断言 `POST /api/agent/chat` 的 message 含修订全文与批注、displayText 是人话；再走一遍「放弃修改」
  断言文件回到基线。
- 领域文档：`.claude/agents/ai-chat.md`「计划审批卡」一节按本契约重写；`doc-editor.md` 或
  `sidebar-shell.md` 记 PlainTextEditor 的 review 选项与 CM6 扩展位置。

## 6. 落地顺序（供 writing-plans 拆解）

1. 后端：saved 事件 + resolve-by-path + review 表/REST + 测试。
2. 前端纯函数：lineDiff / 重锚定 / prompt 拼装 + 测试。
3. PlainTextEditor 审阅态：扩展、审阅条、右栏、提交/放弃。
4. ArtifactCard / RootBubble / useAgentStream 接线 + openFile review 选项。
5. 真渲染走查 + 文档。
