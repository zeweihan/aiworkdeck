# AI 对话整套能力改造 · 跨 session 工作计划（dev-board#779）

> **For agentic workers:** 本计划按「一张卡 = 一个任务书 = 一个 Opus 子代理在独立 worktree 实施，主会话审查后提交」执行（superpowers:subagent-driven-development）。每张卡的逐步骤细节由实施代理在卡内按 brainstorming → TDD 自行展开，本文只钉任务边界、验收判据与验证命令。状态以本文「状态表」与 dev-board 卡为准。

**Goal:** 把 2026-09-22 审查（报告全文在 dev-board#779 评论，原始证据在 `~/aiworkdeck-qa/reports/ai-chat-audit-2026-09-22/`）的全部发现按三个批次改完，对外只交终态。

**Architecture:** 后端 Spring（编排器 AgentOrchestrator / ToolRegistry / ContextAssemblerService / EditorBridgeService）+ 前端 uni-app（ChatInterface.vue / useAgentStream.js / project-overview.vue）+ LOWA worker（office_thread.js）。领域契约以 `.claude/agents/ai-chat.md`、`ai-doc-bridge.md`、`doc-editor.md`、`sidebar-shell.md` 为准，改了契约同一 PR 更新文档。

**Spec:** 审查报告 = 规格来源；每条发现的 file:line 证据与 proposed_fix 在 `~/aiworkdeck-qa/reports/ai-chat-audit-2026-09-22/audit-raw.json`（按 finding id 查）。

## 长期原则（20 年产品，利他优先；所有卡的隐含要求）

1. **永不静默销毁用户数据**：回退前先自动存分支；取消、出错都要把已生成内容与执行日志落库；覆盖文件必须进版本记录。
2. **用户看得见、摸得着、摘得掉**：任何进入模型上下文的东西（当前文档、附件、图片、降级）在界面上有对应的可见表达与移除入口；任何上限（文件数、字符数、图片数）在触发前拦截并明示。
3. **数据模型先于 UI**：fork 记 `parentConversationId` + `branchFromMessageId`；消息与附件建持久关联；这些即使本批 UI 不用也先落库，为将来多版本、历史回放留路。
4. **声明优于清单**：工具的宿主依赖、需确认、文件效果都写在 `@ToolMeta`，不再靠名字前缀 if 链。
5. **单一事实来源**：上限值只在后端配置，经 `GET /api/ai/config` 下发；语言文案全走 i18n（禁硬编码英文）；模型能力位只从模型目录来。
6. **不改模型行为的先做，改模型行为的先建基线**：工具渐进披露、提示词重组前先补回放评测的「工具选择正确率」用例。
7. **兼容旧客户端**：SSE 事件只增不改名；Office/WPS 任务窗格忽略未知字段，桌面端与插件的行为分叉要收敛而不是再分叉。
8. 通用红线沿用 CLAUDE.md：SPDX 头、不新增出站请求、无 emoji、浅色外壳、公开契约常量不改名。

## Global Constraints

- JDK 21 跑 `mvn`；前端 npm；编辑与构建在同一棵 worktree。
- 子代理禁止 commit / push / stash；主会话审 diff、看测试输出原文、看截图后再提交，一卡一（或多卡合一）PR，合并 master。
- 每张卡收尾：更新对应领域文档 → 落实记录评论 → 状态「待复测」（纯内部改动直接「已复测」关卡）。
- 派工档位：涉及编排器 / 取消 / 状态机 / 桥接 / 锚点 / 数据模型 → `model: opus`；纯文案、i18n、单文件小改 → `model: sonnet`。审查与裁决只在主会话。
- 冲突组：同组的卡串行或由同一代理做（见每卡「冲突组」）。`CI`=ChatInterface.vue，`UAS`=useAgentStream.js，`PO`=project-overview.vue，`AO`=AgentOrchestrator.java，`CAS`=ContextAssemblerService.java，`DET`=DocumentEditTools.java。

---

## 批次一：P0 与实测硬伤（10 张卡，先做）

### K1 回退修复：id 契约 + 语义统一 + 回退前自动存分支
- 审查依据：D-02、D-03、D-06 第一步。冲突组：CI/UAS + 后端 AiAgentController/ProjectAiMessageService。
- 任务：① 用户气泡持有落库后的 `project_ai_message.id`（receipt / input_applied 载荷带上，`useAgentStream.js` 写进气泡；拿不到时 rollback 定位键改 clientRequestId）；② 语义统一为 edit-and-resend：后端 `truncateHistory` 删除目标及其后（复合条件 createdAt>= 且 id>=），前端注释与控制器注释同步改；③ 确认框不再是「删除且无法恢复」：确认即先调 fork 存档（标题「<原标题> · 回退前存档」）再截断，弹窗文案改写；④ fork 端点与 `ai_conversation` 表加 `parentConversationId` / `branchFromMessageId` 两列（K18 复用）。
- 验收：服务层测试「回退后按会话读历史，目标与其后都不在」；控制器测试「messageId 为字符串时 400 带可读文案」；e2e（app-e2e 新增一步）：发消息 → 回退 → 确认 → 输入框回填原文、历史无重复用户消息、近期对话里多一条存档会话。
- 验证：`mvn -B test -Dtest='*Rollback*,*AiAgentController*'`；`npm run test:app-e2e`（相关旅程）。

### K2 ToolRegistry.LEGACY_DEFAULTS 删除会改错文档的静默默认值
- 依据：A4 + 复核补漏。冲突组：ToolRegistry（独立）。
- 任务：删 `doc_get_paragraph.paragraphIndex`、`doc_modify_paragraph.paragraphIndex`、`doc_find_replace.replaceAll` 三条；保留其余；让工具自己的「参数必填」守卫真正执行。
- 验收：单测「doc_modify_paragraph 缺 paragraphIndex 返回 paragraphIndex is required 而不是执行」；「doc_find_replace 缺 replaceAll 时只替换第一处或返回要求明示」；回放评测 10 组不回归。
- 验证：`mvn -B test -Dtest='ToolRegistry*,OrchestratorReplayEvalTest'`。

### K3 `doc_read_paragraphs` 幻影引用改为真实分页工具
- 依据：A5/B-01。冲突组：ToolFileGuard / EditorBridgeService / DET（小改）。
- 任务：5 处引用改成 `doc_get_document_text(startParagraph=…, maxParagraphs=…)` 的可行动说明；OversizedToolResultRecoveryTest:123 断言随之改；grep 全仓（含 prompts/ 与 skills/）确保零残留。
- 验收：`grep -rn doc_read_paragraphs backend/ frontend/src` 为 0；截断提示单测。
- 验证：`mvn -B test -Dtest='OversizedToolResult*,ToolFileGuard*'`。

### K4 取消真正生效：流式检查点 + okhttp cancel + 桥调用即时中止 + 执行日志落库
- 依据：C-03、D-01、D-10 + 实测（取消后又跑 82 秒；桥上等 180 秒）。冲突组：AO / OpenRouterStreamingChatModel / EditorBridgeService / AgentStreamHandler。
- 任务：① 聊天主链路改用 `generateCancellable`，RunGuard 持有 Call，`setCancelled` 时 `call.cancel()`；② `onToken`/`onReasoning` 里检查取消标志，命中即停止转发并走 handleCancellation；③ EditorBridgeService 等待桥回包的 future 在取消时立即完成为 CANCELLED（不等超时）；④ handleCancellation 落 executionLog + partialContent + `[已中断]`，正文为空也落；⑤ `setCancelled` 返回 boolean，`/cancel` 响应带 `cancelled`，前端据此显示「已停止」或「该轮次已经结束」；⑥ 文案仍说「正在停止」直到收到 cancelled 事件（前端 abort 改成先发 cancel 再拆 SSE，或保留 SSE 到收到 cancelled，二选一并更新 ai-chat.md:257）。
- 验收：单测「取消后 500ms 内 stream handler 不再收到 token」；「桥调用等待中取消，200ms 内 handleCancellation 被调且落库含 tool_code 日志」；后端实测（用 `~/aiworkdeck-qa/reports/ai-chat-audit-2026-09-22/live/harness.mjs` 的取消场景）cancel→终态 < 2 秒。
- 验证：`mvn -B test -Dtest='AgentOrchestrator*,*Cancel*'` + harness 实跑数字贴卡。

### K5 菜单「停止当前任务」同时停止 AI 生成
- 依据：D-04。冲突组：CI（小）。模型：sonnet。
- 任务：`menuStop` 先 `await abort()`（isStreaming 时）再取消后台任务；返回值区分；JSDoc 改。
- 验收：commands 测试或组件测试「isStreaming 时 menuStop 调用 abort」。验证：`npm run test:commands` + 相关单测。

### K6 拖拽入上下文：全局变量清空 + 标签页拖入 + 本机文件拖入 + 死代码清理
- 依据：实测（`__checkbaDraggedFile` 不清空）、F8、E-11、E-13/F9。冲突组：PO / CI（拖拽段）。
- 任务：① `handleAiDrop` 消费后 `document.__checkbaDraggedFile = null`；② `draggingTab` 存在时按 fileId 取 tab 的 {id,name,fileType,wpsFileId} 交 `addFile`，非文件标签静默忽略；③ 三种私有格式落空后检查 `e.dataTransfer.files`，有文件走 `confirmUploadAndAddContext` 同一条上传路；④ 删 CI 里两块 `isDragging` 浮层，宿主高亮落点改到输入框；⑤ 所有新文案走 i18n。
- 验收：puppeteer（CDP `Input.setInterceptDrags`）三场景：文件树→已添加；标签页→已添加；上一个文件残留后误落→不再顶包；本机文件→上传并添加。截图贴卡。
- 验证：`npm run test:app-e2e` 新增拖拽步；配方见 `~/aiworkdeck-qa/reports/ai-chat-audit-2026-09-22/ui/`。

### K7 待处理消息删除同步 + 用户消息已读/未读角标 + 刷新后恢复上次会话
- 依据：D-05（含实测删除后气泡仍在）、实测「打断后刷新回到空会话」。冲突组：CI / UAS / AgentInbox。
- 任务：① 删除 pending 项时同步移除对话流里同 messageId 的气泡；② 用户气泡按 `receiptState` 渲染角标：pending → 淡态 + 「尚未读取 · 立即调整/排队中」，applied → 无角标或「已送达」，两套 locale；③ 插话气泡与待处理区做视觉关联（同一条只在一处显示正文，另一处显示引用行）；④ 工作台记住每个项目最后打开的会话（uni storage），刷新后恢复而不是空会话。
- 验收：组件测试（tests/chat-presentation-ui）「删除 pending 后 bubbles 无该 id」「pending 气泡带角标」；e2e：打断→刷新→仍在该会话。

### K8 虚拟标签页不当活跃文档 + Error 返回值等同空白
- 依据：E-3。冲突组：PO（currentActiveTab）/ CAS（注入前判空）。
- 任务：前端过滤 `tabType==='web'`、非数字 id 的 artifact/market/insight 标签；后端注入 `<active_document>` 前把 `Error:`/`Warning:` 前缀返回值当空白走 readHint。
- 验收：CAS 单测「read_document 返回 Error… 时 system 不含 <active_document> 正文」；前端单测 currentActiveTab 对 web/artifact 标签返回 null。

### K9 锚点错位：`find_text_locations` 与 `set_selection` 对同一 anchor 解析不一致
- 依据：UI 实测 t3.json / t3run.log（`__ai_anchor_1` find 在正文首段、set_selection 落到签章页，两次复现）。冲突组：office_thread.js（doc-editor 领域）+ DET。模型：opus，agent 类型 doc-editor。
- 任务：先用 lowa-e2e 真引擎复现（用「股份认购协议.docx」同款夹具：标题在首页、签章页重复标题），定位是锚点按文本匹配到了重复文本、还是锚点 id 与 range 映射被 find 的后续调用覆盖；修复后 find→select→insert 三步落点一致。
- 验收：lowa-e2e 新增用例「文档含两处相同标题时 anchor 指向 find 报告的那一处」；还原病灶即转红（assert 锚点唯一 + 改后形态出现）。
- 验证：`npm run test:lowa-e2e`。

### K10 十个 Writer 写入原语补 `@ToolMeta(fileEffect=MODIFIED)`
- 依据：B-02。冲突组：DET（注解）。模型：sonnet 也可，但验收要主会话核。
- 任务：doc_replace_nth_match / doc_delete_match / doc_delete_text / doc_replace_selection / doc_insert_at_cursor / doc_replace_at_anchor 等 34 个缺 @ToolMeta 的方法逐个补齐（写入类 MODIFIED，读取类 NONE），并加一条契约测试「DET 里所有 @Tool 都有 @ToolMeta」。
- 验收：契约测试；回放评测「本轮只用 doc_insert_at_cursor 也建检查点」。

## 批次二：体验跃迁（S 到 M）

### K11 复制 + 重新生成
- 依据：F2、F6、D-07。冲突组：RootBubble / ProcessCard / CI。
- 任务：助手气泡常显复制键（不受 showUseInDocument 门控，复制 Markdown 纯文本化结果）；工具卡「复制调用 / 复制输出」；「重新生成」= 复用 K1 的截断（保留用户消息）+ 重发，同样先存分支。
- 验收：组件测试；e2e 点复制后 `navigator.clipboard.readText()` 等于正文。

### K12 钢琴键会话导航 ChatTurnRail.vue
- 依据：F1、F1-DESIGN、复核补漏（`.message-list` 无 position:relative 且 overflow hidden，需先调容器）。冲突组：CI 模板/样式。
- 任务：新组件 `components/AgentMessage/ChatTurnRail.vue`，props `turns`（复用 chatTurns）、`activeKey`；emits `jump`；静息 12px 刻度列在面板右缘，hover 展开 200px 浮层显示每轮提问 label 与状态色（running/queued/awaiting/idle 用 --awd-* 令牌）；IntersectionObserver 跟随当前轮高亮；点击调 `navigateToMessage`；长会话（200 轮）不掉帧。放 AI 面板内部，不做第五列。
- 验收：tests/chat-presentation-ui 新增：刻度数=用户轮数、点击滚到对应轮、滚动时 activeKey 变化；截图两张（静息/展开）贴卡。

### K13 运行状态条显示工具名与秒数 + token 用量一行
- 依据：F5、F3-①。冲突组：RootBubble / CI（status-bar）。模型：sonnet。
- 任务：运行态 activityLabel = 「正在 {displayName} · {秒}」；把注释掉的 tokenUsage 块放回成低调一行「本轮 N tokens」。
- 验收：组件测试。

### K14 上下文层：当前文档 chip + 去掉附件互斥 + 附件跨轮保留 + 消息↔附件持久化
- 依据：E-8、E-4、E-2、E-9、E-10 + 原则 2/3。冲突组：CI / UAS / CAS + 新表。
- 任务：① 输入框上方「当前文档 · 名称」chip，可摘除（本轮不带）；② 去掉 `!hasFiles && !hasImages` 互斥，有附件时活跃文档仍带（正文或只带壳由后端 readHint 分支决定）；③ 发送后 contextFiles 保留为淡态，可一键沿用/移除，换会话才清；④ 新表 `project_ai_message_attachment`（messageId, fileId, kind, ocrText/visionUsed），历史回放重建附件 chip；⑤ 发送前对活跃 LOWA 实例做有超时的 flushSave；⑥ 活跃文档截断上限独立配置项，触发截断时前端可见提示。
- 验收：CAS 单测（互斥去除、readHint）；前端组件测试（chip 摘除后请求体无 activeContext）；e2e 两轮追问第二轮模型能引用附件。

### K15 `@` 引用选择器 + 右键「加入 AI 对话」+ 「+」按钮可选项目文件
- 依据：E-5、F7-①。冲突组：CI 输入框 / FileTree 菜单。
- 任务：输入 `@` 弹项目文件模糊搜索（复用 QuickOpenPanel 检索），选中调 `addFile`；FileTree 右键加「加入 AI 对话」；「+」对话框加「从项目选择」页签。范围先只项目文件（记忆/参考来源另议）。
- 验收：e2e 键入 `@股份` 选中后出现 context-tag；右键路径截图。

### K16 输入框键位与可聚焦性
- 依据：F7-③、F11。冲突组：CI 输入区。模型：sonnet。
- 任务：输入框局部 `@keydown.esc`（流式=abort，非流式=清草稿；不进 config/commands）；Cmd/Ctrl+Enter 发送；上箭头在空输入框时回填上一条用户消息；发送/停止键改 `<button>` 并打平样式；三个下拉选项加 tabindex/role。
- 验收：`npm run test:commands` 不回归；组件测试键位。

### K17 会话搜索 / 重命名 / 删除 / 置顶前端接线
- 依据：F10 + 复核补漏（后端 title/delete 端点已在 AiChatController.java:111/129）。冲突组：PO 历史下拉。模型：sonnet。
- 任务：下拉顶部过滤框（纯前端）；行内重命名（POST title）；删除（DELETE，带确认，产物与检查点提示）；置顶需后端 metadata 加字段。
- 验收：e2e 重命名后列表更新；删除后列表无该项。

### K18 从此分叉（非破坏）
- 依据：D-06、F4。依赖 K1 的表字段。冲突组：CI 气泡 footer / 后端 fork。
- 任务：fork 端点加 `untilMessageId`；用户气泡「回退」旁加「从此分叉」，成功后宿主切到新会话；历史列表显示分支来源角标。
- 验收：服务层测试 fork 截断正确且父子字段落库；e2e 分叉后原会话完整。

### K19 工具可见性声明化 + 撤销放回 + skill tool_policy + 裁掉停用/调试工具
- 依据：A3/B-03、A9、A2、A15 + 复核补漏（pptx_/pdf_ 在 Office 会话空转）。冲突组：ToolMeta / ClientCapabilityService / SkillRouter / 各 tool 组件注解。
- 任务：① `@ToolMeta` 加 `requiresHost = NONE|LOWA|OFFICE`，`ClientCapabilityService.isToolVisible` 改按声明过滤，pptx_open_file/pptx_generate/pptx_apply_format/litigation_render/text_write_file 等标 LOWA；② `KIND_AGNOSTIC_LOWA_TOOLS` 加 doc_undo/doc_redo（先在 Calc/Impress 真机验证 undo 生效）；③ SkillDefinition 加 `tool_policy: passthrough|restrict`，未声明 allowed_tools 默认 passthrough；④ delete_file / doc_debug_revisions 只裁 spec 不裁 execute。
- 验收：ClientCapabilityService 单测三档 × 三宿主矩阵；SkillRouter 单测 passthrough；回放评测不回归；ToolSchemaBudgetTest 数字更新贴卡。

### K20 PDF 文字层优先 + 抽取缓存 + 三口径统一
- 依据：C-01/E-1 + 复核补漏（三条 PDF 口径）。冲突组：LegalTools / FileTools / FileContentExtractorService / FileContextLoader / CAS。
- 任务：`read_document` 与 `read_file` 收敛到 ProjectFileTextExtractor 的「文字层优先、抽不出才 OCR」；OCR 结果按 fileId+mtime+size 落库缓存（新表，跨重启）；文件夹路径与直接拖路径口径一致；20 页上限只对 OCR 路径生效且明示；活跃文档正文缓存复用同一缓存。
- 验收：单测文本型 PDF 零 OCR 调用；同一 PDF 两轮只抽一次；实测（harness）带 PDF 附件的第二轮 prep < 100ms。

### K21 降级与上限可见：`/api/ai/config` 下发 + 前端前置拦截 + SSE 降级事件
- 依据：E-6、E-7、E-10、E-14、E-15 + 复核补漏（文件夹绕闸、图片不计配额）。冲突组：AiConfig 控制器 / CI / CAS。
- 任务：config 下发 maxFilesPerContext / maxCharsPerFile / vision.maxImagesPerTurn / maxImageBytes；`addFile` 到顶拒绝并提示；图片超数/超大就地提示；拖入项目图片在非视觉模型下常驻提示（照插件 visionNotice）；后端新增 SSE 事件 `context_notice`（降级/截断/丢弃，写进 ai-chat.md 事件清单，插件忽略即可）；Ollama 档 vision 位归一为 false；文件夹计数递增。
- 验收：CAS 单测各上限；前端组件测试提示出现；e2e 贴 5 张图看到提示。

### K22 steer 待处理项「立即发送」
- 依据：D-08。冲突组：AgentInbox / AgentInboxController。模型：sonnet。
- 任务：判据改「目标模式 steer 且当前无活跃轮次」即起跑；前端 v-if 放开。验收：控制器单测。

### K23 桌面端上送 X-Client-Instance 与 Last-Event-ID
- 依据：D-09。冲突组：UAS connectSSE。模型：sonnet。
- 任务：照 office-addin/taskpane/lib/sse.js 搬；重连补发去重。验收：单测重连带头；手工两窗口不互顶（截图）。

## 批次三：能力补齐与性能（M）

### K24 Excel 查找替换（sheet_find_replace + office_excel_replace）
- 依据：A16/B-04。冲突组：DET sheet_* / office_thread.js / OfficeEditTools / officeExecutor.js。插件按「双边三端」标准（Office/WPS）。
- 验收：lowa-e2e Calc 用例；插件单测。

### K25 PDF 页操作：合并 / 拆分 / 提页 / 删页 / 旋转 / 编页码 + pdf_inspect 续读
- 依据：B-12/A17、B-13。冲突组：PdfTools / PdfEditService。
- 验收：单测每个操作对合成 PDF 的页数与顺序；pdf_inspect offset 参数。

### K26 Office 桥超时表补齐 + sheet/slide 写入类进超时表 + office_get_text 分页
- 依据：B-05、B-06、B-17。冲突组：OfficeBridgeService / EditorBridgeService / OfficeEditTools / officeExecutor.js。
- 验收：parity 测试同表；office_get_text(offset,limit) 单测；OfficePassChunker 口径不变。

### K27 工具描述与别名清理
- 依据：A11（删 search_laws 别名）、A12（position 命名/基数统一：新增明确参数名，旧参数保位弃用）、A13（三记忆工具合并为 query_memory(depth)）、A14/B-10（read_document 描述补全、指向 extract_file_text）、B-07（doc_format_table 列宽参数拒绝并明说）、B-11（doc_list_project_files 一次列全类型并带 fileId）、list_files 描述纠正。
- 验收：回放评测「工具选择正确率」用例（先补基线再改）；ToolRegistry 别名单测。

### K28 PPTX 单一权威面（slide_*）
- 依据：B-09。任务：pptx_apply_format / pptx_edit_page / pptx_open_file 从模型工具面撤下（只裁 spec），pptx_* 只留生成与导出；文档更新。验收：能力矩阵单测。

### K29 系统提示按客户端能力分段
- 依据：实测发现 A（none/Office 会话被教 doc_*）。冲突组：CAS / prompts。任务：工具指引段按 capability 拆成三份片段拼装；回放评测覆盖三档。验收：Office 会话首轮不出现「Tool not found」（插件实跑贴卡）。

### K30 工具渐进披露（需先有基线）
- 依据：A1/C-07。前置：K27 的回放基线。任务：类目 + 常用 20 条 + `list_tools(category)` 展开；XML 兜底协议同步。验收：回放基线不降、promptTokens 与首字实测贴卡。

### K31 前端长会话性能
- 依据：C-05、C-10、C-12、F12。任务：chatTurns 增量维护；MarkdownPreview 增量渲染不清选区；历史接口分页；先造 200 轮夹具量拐点再决定虚拟化。验收：夹具下 3000 token 回答主线程占用与改前对比贴卡。

### K32 首字前准备链并行化 + 缓存名单 + Dispatcher
- 依据：C-02、C-06、C-08、C-09、C-13、C-14 + 复核补漏（读路径同步写、请求体三遍序列化）。任务：记忆三段检索并行；touchLastAccessedAt 异步；基底 prompt 缓存；gpt-5.6-terra/gemini-3.6-flash 拆 system 实测后进 VERIFIED_MULTI_SYSTEM；OkHttp Dispatcher 上限；压缩阈值双扣修正与 system 超窗可见提示；text_delta 按帧合并。验收：harness prep 数字前后对比贴卡。

### K33 Office 插件 Word 面补齐 + contract-review skill Office 面
- 依据：B-16、A10、复核补漏（office_delete_comment 不对称）。任务：office_delete_comment / office_insert_toc / office_set_page_setup；skill.yml 加 office_replace_batch / office_pass_step / office_reply_comment；三族对拍 CI 校验。双边三端标准。

### K34 音频附件可行动提示 + 转写稿关联
- 依据：E-12。任务：先做可行动文案（S）；再把已转写音频与转写稿建关联，附件带音频自动注入转写稿（M）。

### K35 领域文档漂移修正（未被其他卡覆盖的）
- 依据：doc_drift 六条。任务：ai-chat.md:257 abort 顺序；ai-chat.md:182 矛盾句；ai-doc-bridge.md handleEditorCommand 地雷已修；SSE 双轨摘旧名计划；并发口径改为 inbox 串行化；action 数 98。纯文档，sonnet。

### K36 SSE 双轨摘 wps_command 旧名
- 依据：B-15。前置：确认无旧客户端。任务：只发 editor_command，前端 latch 分支删除。验收：desktop-e2e 保存链路。

### K37 meeting-recorder / listing-pathway 白名单补编辑面 + 触发词收紧
- 依据：K19 实施时发现（与 A2 同形态更隐蔽）。冲突组：backend/skills yml / cases-skill.json。
- 任务：白名单补 doc_*/office_* 读写基本面；短触发词改整词/短语匹配；删 yml 里「已知风险」段。
- 验收：回放评测「命中后仍可见 doc_insert_at_cursor / office_insert_text」；触发词单测。

---

## 状态表（主会话每合一张卡就更新；新 session 从第一张非「已合并」的卡接着做）

| 卡 | dev-board# | 批次 | 冲突组 | 状态 | PR |
|---|---|---|---|---|---|
| K1 回退修复 | #780 | 1 | CI/UAS/后端 | 已合并 | #917 |
| K2 LEGACY_DEFAULTS | #781 | 1 | ToolRegistry | 已合并 | #911 |
| K3 doc_read_paragraphs | #782 | 1 | ToolFileGuard | 已合并 | #911 |
| K4 取消真正生效 | #783 | 1 | AO/OpenRouter/Bridge | 已合并 | #915 |
| K5 菜单停止 | #784 | 1 | CI | 已合并 | #913 |
| K6 拖拽 | #785 | 1 | PO/CI | 已合并 | #914 |
| K7 待处理同步+角标+恢复会话 | #786 | 1 | CI/UAS/Inbox | 已合并 | #913 |
| K8 虚拟标签 | #787 | 1 | PO/CAS | 已合并 | #914 |
| K9 锚点错位 | #788 | 1 | office_thread/DET | 已合并 | #912 |
| K10 ToolMeta 补齐 | #789 | 1 | DET | 已合并 | #911 |
| K11 复制+重新生成 | #790 | 2 | RootBubble/CI | 已合并 | #920 |
| K12 钢琴键 | #791 | 2 | CI | 已合并 | #918 |
| K13 状态条+token | #792 | 2 | RootBubble/CI | 已合并 | #920 |
| K14 上下文层 | #793 | 2 | CI/UAS/CAS/新表 | 已合并 | #928 |
| K15 @ 引用 | #794 | 2 | CI/FileTree | 已合并 | #932 |
| K16 键位 | #795 | 2 | CI | 已合并 | #932 |
| K17 会话管理 | #796 | 2 | PO | 已合并 | #923 |
| K18 从此分叉 | #798 | 2 | CI/fork | 已合并 | #924 |
| K19 工具可见性声明化 | #799 | 2 | ToolMeta/Capability/SkillRouter | 已合并 | #921 |
| K20 PDF 文字层 | #800 | 2 | LegalTools/Extractor/CAS | 已合并 | #919 |
| K21 降级与上限可见 | #801 | 2 | Config/CI/CAS | 已合并 | #928 |
| K22 steer 立即发送 | #802 | 2 | Inbox | 已合并 | #923 |
| K23 X-Client-Instance | #803 | 2 | UAS | 已合并 | #923 |
| K24 Excel 替换 | #804 | 3 | DET/office_thread/Office | 已合并 | #934 |
| K25 PDF 页操作 | #805 | 3 | PdfTools | 已合并 | #929 |
| K26 Office 桥超时+分页 | #806 | 3 | Bridge/OfficeEditTools | 已合并 | #930 |
| K27 工具描述清理 | #807 | 3 | 多 tool 组件 | 已合并 | #933 |
| K28 PPTX 权威面 | #808 | 3 | PptxTools | 已合并 | #933 |
| K29 提示按能力分段 | #809 | 3 | CAS/prompts | 待合并 | #938 |
| K30 渐进披露 | #810 | 3 | ToolRegistry/AO | 已提交待链式合并 |  |
| K31 长会话性能 | #811 | 3 | CI/Markdown | 已合并 | #936 |
| K32 准备链并行化 | #812 | 3 | CAS/Memory/OpenRouter | 已提交待链式合并 |  |
| K33 Office Word 面 | #813 | 3 | OfficeEditTools/skill | 已合并 | #930 |
| K34 音频附件 | #814 | 3 | LegalTools | 已合并 | #931 |
| K35 文档漂移 | #815 | 3 | docs | 已合并（关卡） | #926 |
| K36 SSE 摘旧名 | #816 | 3 | Bridge/UAS | 已合并 | #937 |
| K37 skill 白名单补编辑面 | #818 | 2 | skills yml/回放用例 | 已合并 | #925 |

## 派工模板（主会话给每个实施代理的任务书骨架）

```
你在独立 worktree <路径> 里实施 dev-board#<N>（计划 docs/superpowers/plans/2026-09-22-ai-chat-overhaul.md 的 K<x>）。
禁止 commit / push / stash。先读 .claude/agents/<领域>.md 与卡片正文；审计证据在 ~/aiworkdeck-qa/reports/ai-chat-audit-2026-09-22/audit-raw.json（finding id 见卡）。
按 brainstorming → 写失败测试 → 最小实现 → 跑测试 的顺序做；改了契约同一批更新领域文档。
汇报：改动文件清单、测试命令与输出原文（不许只说通过）、截图路径（UI 卡必需）、未验证项、worktree 路径。
```

## 主会话审查清单（每张卡）

1. `git -C <worktree> diff --stat` 与关键 hunk 亲眼读；每一行能追溯到卡。
2. 测试命令与输出原文；UI 卡看截图；实测数字贴卡。
3. 提交（Co-Authored-By 尾注）→ PR → 合并 master → 领域文档已更新 → 落实记录评论 → 状态「待复测」→ 本表更新。
4. 同冲突组的下一张卡 rebase 到最新 master 再派。

## 跨 session 接力协议

新 session 开头说一句：**「读 `docs/superpowers/plans/2026-09-22-ai-chat-overhaul.md` 与 dev-board#779，按状态表从第一张非『已合并』的卡继续，按 CLAUDE.md 第 0 节派工」**。SessionStart 钩子还会自动带上 `.remember/remember.md` 的交接（每次合卡后主会话更新它）。判断某卡是否已做：看 dev-board 卡状态与 PR 链接，不看本地分支。
