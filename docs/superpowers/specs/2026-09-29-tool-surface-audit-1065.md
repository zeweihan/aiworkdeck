# AI 工具面审计（dev-board#1065）

- 卡号：dev-board#1065（承接 #1064：活跃文档类目裁剪 / 渐进披露）
- 日期：2026-09-29
- 基线提交：`202c2d3c`（HEAD）。同一 worktree 里另有未提交的 #1064 改动（`ToolDisclosurePolicy` / `ToolDiscoveryTools` / `AgentOrchestrator` / `ContextAssemblerService` / `SkillRouter` / `application.yml` 等 10 个文件），本文一律按 HEAD 取证，行号都是 HEAD 的；与之相关处标「#1064 在途」。
- 维护者原话（2026-09-29 13:42 / 13:43）：「我觉得，要遍历一下这些工具，看看是否每个都合理，有没有重复，以及，对于各场景来说是不是完整？是不是需要补充其他兄弟的这种 tool？」「包括要学习一下 Claude，对吧？人家的 20 几个核心的 tool 是怎么做的，那我们是不是每一个都有，以及是不是能对齐？就是人家那个 20 多个，那咱们是多少个核心的？咱们是不是也先发核心的？」
- 取证方式：脚本解析 `backend/src/main/java/com/checkba/service/ai/tools/*.java` 全部 `@Tool(`（剔除注释、展开字符串常量、识别前后两种 `@ToolMeta` 位置），按 `ToolDisclosurePolicy.categoryOf` 与 `ClientCapabilityService.isToolVisible` 的规则逐字复现类目与会话可见性；描述互指用正则扫工具名。凡标「推断」「未验证」的都没有跑代码证实。
- 文档性质：T-xx 条目的「证据」抄自审计草稿；「裁决」来自主会话（Fable）2026-09-29 14:20 的裁决文件，是裁决而非已验证的落地结果。落地状态以各批次 PR 与 CI 为准，本文不作担保。
- 全表（296 个 @Tool，每个一行）见文末附录。

## 1. 数字概览

| 项 | 数 |
|---|---|
| 登记的 @Tool 总数（31 个 AgentToolComponent） | **296**（预期约 204，差额主要是 Office 任务窗格的 80 个 office_*，外加 ref_* 4 个） |
| `offerToModel=false`（只登记不下发） | 7：`doc_debug_revisions`、`delete_file`、`search_knowledge_base`、`deep_search`、`pptx_open_file`、`pptx_apply_format`、`pptx_edit_page` |
| 声明 `requiresHost=LOWA` | 12 |
| 各会话静态可见数（不含账户/Docker/披露开关等运行期闸） | LOWA-docx 158、LOWA-xlsx 125、LOWA-pptx 120、Office-Word 125、Office-Excel 113、Office-PPT 100、none 82 |
| @Tool 描述总字数 / @P 说明总字数 | 62131 / 22949 |
| CORE（`ToolDisclosurePolicy.java:70-105`）名字数 | **54**（含 `list_tools`；主会话原记 55） |
| CORE 与会话求交 | LOWA-docx 43、LOWA-xlsx 27、LOWA-pptx 26、Office-Word 23、Office-Excel 18、Office-PPT 18、none 18 |
| 按类目 | core 54、office 75、slides 31、format 23、spreadsheet 22、pdf 13、files 12、revision 9、table 7、plugin 6、litigation 6、memory 6、task 6、enterprise-data 5、**misc 5**、template 4、evidence 4、reference 4、meeting 2、legal 1、python 1 |

docx 会话 CORE 求交 43 比 ai-chat.md 记录的 41 多 2 个：#810 之后新进核心集的 `ask_user`（#868）与 `move_to_trash`（#1044）。

按影响排序的前 10 条（草稿排序）：T-01、T-03、T-04、T-11、T-10、T-02、T-09、T-18、T-05/T-06/T-14（三组同义工具）、Step 4 的 Skill 与 ToolSearch 缺失（T-17 及第 3 节）。

## 2. 与 Claude Code 常驻核心工具的对照

| Claude Code 工具 | 我们的对位 | 差距 | 建议 |
|---|---|---|---|
| Read（路径 + offset/limit，能读图片） | `extract_file_text` / `read_document`（id）、`read_file`（路径）、`doc_get_document_text`（打开的 docx，分段）、`office_get_text`、`pdf_inspect` | 部分：id 式读取无续读（T-04）；两个同义入口（T-05）；读图片只能拿 OCR 文字，模型不能「看」项目里的图片（视觉直送只在用户挂附件 / 当前标签是图片时发生，ai-chat.md「图片多模态」节） | 合并为一个 id 式读取并加 offset/maxChars；是否给模型「看图」工具另议（langchain4j 0.36 的工具结果只能是文本，需要走下一轮用户消息注入图片块） |
| Write | `write_file`（纯文本，只能写根目录）、`write_docx`、`doc_start_stream`、`text_write_file`（LOWA） | 部分：写入落点能力不一致（T-07） | `write_file` 加 `parentFolderId`，`scan_files` 下线 |
| Edit（old_string 必须唯一，否则失败；可 replace_all） | `doc_find_replace`（缺省全替）、`doc_replace_at_anchor`（先 `doc_find_text` 拿锚点，两步）、`office_replace_text`（缺省第一处）、`text_find_replace`、`memory_edit`（唯一一个有唯一性检查） | 部分：**没有「不唯一即拒绝」的一步式替换**；缺省值四族不一（T-17） | 给 `doc_find_replace` / `text_find_replace` 加 Edit 语义：`replaceAll` 未显式给且命中 >1 时不改、回命中清单 |
| Glob | `search_project_files`（glob + 文件名包含，最多 50 条） | 基本对齐；描述错（T-02） | 修描述 |
| Grep | 无。`doc_search_related_docs` 名不副实（T-03） | **缺失** | 新增 `search_project_content` 包 `ContentSearchService`（已支持正则/整词/类型/标签过滤） |
| Bash | `run_python`（Docker 沙箱，本机无 Docker 时不下发，`PythonTools.java:77`） | 部分，且在多数律师机器上不可用 | 维持；不建议为律师场景扩 shell |
| BashOutput / KillShell（后台任务） | 无。长任务（AI PPT 等）在 `BackgroundTaskService`，模型不能查进度或取消；取消只有用户端点 | 缺失（低优先） | 暂不做 |
| Agent（subagent_type + model） | `dispatch_subtask(task_description, expected_output, tool_scope)` | 部分：无类型、无按次选模型（全局 `ai.subagentModel`）、同步阻塞 | 低优先；如做，先加 `model` 档位（辅助/主模型两档） |
| SendMessage（续问已派子代理） | 无 | 缺失（低优先） | 暂不做 |
| WebFetch | `browse_url(url)` | 基本对齐（没有「按问题抽取」的 prompt 参数；截断上限未核对） | 维持 |
| WebSearch | `search_web(query)` | 对齐；参数无 @P（T-21） | 补 @P |
| TodoWrite | `todo_write` | 对齐（整表覆写、同时只一项进行中） | 维持 |
| AskUserQuestion | `ask_user`（2-4 选项、说明、多选、自动「其他」） | 对齐，且有真实模型评测支撑 | 维持 |
| Skill（按名显式调用） | 无。skill 只有两种激活方式：用户消息命中触发词（`SkillRouter.java:157`、`:186` 的 `containsTrigger`）与前端随请求带 `skillIds`（`SkillRouter.java:219-248`） | **缺失**：模型意识到「这件事有专门的 skill」时无法主动加载，只能依赖用户措辞恰好命中触发词 | 新增 `use_skill(skillId)` + 在 `list_tools` 目录里列出可用 skill；激活效果（prompt 注入与工具白名单）按「一轮内工具集不变」契约**下一轮生效**，与 `list_tools` 展开同一机制 |
| ToolSearch（关键词搜索或 `select:名字` 加载 schema） | `list_tools(category)`（`ToolDiscoveryTools.java:59`），默认不下发（`isAvailable` 看披露开关，`:54`） | 部分：**只能按类目展开**，没有关键词检索，也不能按名字直接拿某一个工具的 schema；类目命名失配又削弱了它（T-19） | 给 `list_tools` 加 `query`（按名字与描述关键词匹配）与 `names`（按名字精确展开）；#1064 在途会让它默认可用，届时这条更急 |
| EnterPlanMode / ExitPlanMode | 模式由用户选（`AgentMode` ASK/PLAN/AGENT，`ContextAssemblerService.java:561-573`）；模型「交计划等批准」= 输出 `implementation_plan` artifact，编排器停机等审批（`AgentOrchestrator.java:2111-2115`），计划审阅卡与编辑器审阅见 ai-chat.md | 部分：Exit 有等价物（artifact 协议，而非工具）；模型不能主动「进入」只读的计划模式（AGENT 模式提示词只写 "If task becomes complex, switch to PLAN phase"，`:573`，但没有机制收紧工具） | 维持 artifact 协议；如要对齐，可让 `implementation_plan` 出现前的轮次按 PLAN 口径只下发只读工具——代价大，不建议本卡做 |
| NotebookEdit | 不适用 | — | — |
| LSP（符号导航） | 最接近的是文档结构导航：`doc_get_outline`、`doc_get_clauses`、`doc_audit_structure`（仅 LOWA-docx） | 类比意义上部分；Word 任务窗格与 PDF 没有结构导航 | 视场景 13 需求补 Word 面 |

草稿的三处重点核实：ToolSearch 缺口属实（`list_tools` 唯一参数是 `category`，未知类目直接报错，`ToolDiscoveryTools.java:81`）；Skill 缺口属实（全仓 `@Tool` 无 skill 相关工具）；Edit 式唯一性只有 `memory_edit` 有（`MemoryTools.java:572`）。全项目正则检索缺失但后端能力现成（T-03）。

裁决对应：Read 续读 → T-04/T-05（批次 1）；Write 落点 → T-07（批次 1）；Glob → T-02（批次 1）；Grep → T-03（批次 1）；Skill 与 ToolSearch → 批次 4；Edit 唯一性、BashOutput/KillShell、Agent 按次选模型、SendMessage、Bash 对位 → 刻意不做（第 6 节）。

## 3. 发现清单

体例：证据抄自审计草稿；「裁决」= 主会话裁决（做 / 并入第二步 / 刻意不做 / 另开卡）；「落地位置」= 批次。批次含义见裁决文件 A 段：批次 1 文件与读取面；批次 2 描述与失败判定；批次 3 宿主声明与类目；批次 4 对齐 Claude Code。「本卡 PR」在 #1064 第一步 PR 合入之后、第二步 PR 之前，各批独立 worktree 链式合并。

### a. 重复 / 近重复

**T-01 权威文件清单只在 LOWA 会话可见（影响最大）**
- 证据：`doc_list_project_files` 实现只查 `projectFileRepository`，纯后端（`DocumentEditTools.java:62-110`），但带 `doc_` 前缀，`ClientCapabilityService.isLowaTool`（`ClientCapabilityService.java:328-333`）判为 LOWA 专属，Office / none 会话里 `isToolVisible` 返回 false（`:242-276`）。11 个在 Office / none 可见的工具描述把它当 fileId 来源：`search_project_files`、`read_file`、`list_files`、`read_document`、`create_folder`、`rename_project_file`、`move_project_file`、`list_project_folders`、`pdf_list_files`、`pptx_list_files`、`office_insert_image`（例 `LegalTools.java:91` "(from doc_list_project_files)"、`FileTools.java:994` "IDs from doc_list_project_files"）。`pdf_list_files`（`PdfTools.java:61`）与 `pptx_list_files`（`PptxTools.java:113`）自称「doc_list_project_files 的子集，请直接用它」。`prompts/tools-office-word.md:57-59` 只提 `pdf_list_files` / `pptx_list_files` / `read_document`，没给 docx / txt / 图片 fileId 来源。
- 推断（未跑）：模型按描述调 `doc_list_project_files` 得 "Tool not found"，或退回 `search_project_files`（而它的描述说自己不带 fileId，见 T-02）。
- 草稿建议：改名为不带前缀的 `list_project_files`（旧名 `offerToModel=false`），或为纯后端 doc_ 工具开例外。
- 裁决：做，且不改名——名字是提示词与十几处描述的公开口径；在 `ClientCapabilityService` 加「纯后端 doc_ 工具」例外表 `{doc_list_project_files}`，`ClientCapabilityDocKindTest` / `ClientCapabilityServiceTest` 加断言，三档会话都可见。落地：批次 1。

**T-02 `search_project_files` 描述与实现相反，且在核心集**
- 证据：描述 "Returns paths only, NO database fileId"（`FileTools.java:74-76`），实现逐条附 `(fileId=N)`（`FileTools.java:146-153`）。与 #807 在 `list_files` 上修过的是同一类病，`ToolChoiceSurfaceTest.java:150-154` 只守了 `list_files`。示例参数 `'*Controller.java'` / `'User*.java'`（`FileTools.java:78`）是开发者口吻。
- 裁决：做。描述改为「返回路径并附 fileId」，示例换 `*起诉状*`；`ToolChoiceSurfaceTest` 加同款断言。落地：批次 1。

**T-03 没有跨项目文件全文检索；`doc_search_related_docs` 自称搜内容实为只搜文件名**
- 证据：描述「根据关键词在文件名和文档内容中搜索」（`DocumentEditTools.java:741`），实现只做 `f.getName().contains(keyword)`，只看可编辑文档，内容搜索是 `// TODO: 集成 ProjectRagService 进行内容搜索`（`:758`）；无命中时返回「项目中的前 10 个可编辑文档」。现成的 `ContentSearchService.searchContent`（`service/ContentSearchService.java:92`）请求体支持 `query / caseSensitive / wholeWord / useRegex / includePatterns / excludePatterns / fileTypes / tagIds`（`model/dto/SearchRequest.java:13-22`），走 `ProjectFileTextCacheService` 缓存 + 并行抽取，只被 UI 的 `SearchController.java:45` 调用。`ProjectRagService` + `DynamicContentRetriever`（`@Component`）全仓零注入点。
- 裁决：做。新增 `search_project_content(query, useRegex?, wholeWord?, fileTypes?)` 包 `ContentSearchService.searchContent`，返回「文件名 (fileId=N) + 命中行/片段」，上限 50 条、每条 200 字符，进 CORE；`doc_search_related_docs` 改 `offerToModel=false`。Claude Code `Grep` 的对位。落地：批次 1。

**T-04 id 式读取无续读，截断后没有工具能读后半段**
- 证据：`extract_file_text(fileId)`（`FileTools.java:281`）、`read_document(fileId)`（`LegalTools.java:91`）经 `ToolFileGuard.capToolText` 截到 80000 字符；截断文案指的两条路——已打开就用 `doc_get_document_text(startParagraph…)`，否则先检索定位再读该段（`ToolFileGuard.java:75-83`）——前者只对 LOWA 打开的那份有效，后者没有任何工具能「读未打开文件的某一段」。对照：`office_get_text(startChar, maxChars)`（`OfficeEditTools.java:235`）、`pdf_inspect(fileId, pageIndex, offset)`（`PdfTools.java:96`）有续读。
- 裁决：做。`extract_file_text` 加可选 `offset` / `maxChars`（照 `office_get_text` 形状，回执带 `nextStart`），`ToolFileGuard` 截断文案改成「用 offset=N 续读」。落地：批次 1。

**T-05 `read_document` 与 `extract_file_text` 是同一抽取器的两个入口，都在核心集**
- 证据：都走 `ProjectFileTextExtractor` + `capToolText`（`LegalTools.java:91-137`、`FileTools.java:281-345`）。差异三处：文件夹 id 只有 `extract_file_text` 认（`FileTools.java:302-313`）；纯文本 GBK 解码只有 `read_document` 有（「刻意不并进抽取器」，`LegalTools.java:142-145`）；参数类型 String vs Long。`ToolChoiceSurfaceTest.java:107-126` 只要求三者（含 `read_file`）互相点名；`read_document` 是 `ai.skills.base-tools` 的兜底工具（`application.yml:474`）。GBK 读出差异只是按注释推断，未实测。
- 裁决：做。GBK 纯文本路径并进 `ProjectFileTextExtractor`（先写一条 GBK txt 的测试证明两入口读出一致），`read_document` 改 `offerToModel=false`；`application.yml` `base-tools` 与 `EvalHarness` 里同值改 `extract_file_text`；六个 skill.yml 的注释同步。`read_file`（按路径）保留。落地：批次 1。

**T-06 移动 / 改名 / 建目录五个工具职能重叠**
- 证据：`move_file`（路径，`FileTools.java:645`）、`move_project_file`（id，`:1027`）、`move_files_batch`（路径批量，核心，`:701`）、`rename_project_file`（id，`:1011`）、`create_folder`（核心，`:994`）。`move_files_batch` 已能单条使用、边移边改名、自动补建目标文件夹，但描述末尾又说 "For a single file keep using move_file"。
- 裁决：做，与草稿有差异。`move_file` 改 `offerToModel=false`，`move_files_batch` 描述改为「单个也用本工具」；`create_folder` 与 `move_to_trash` **留在核心**（用户会直说「建个文件夹」「删掉」，#1044 的理由仍成立）。落地：批次 1。

**T-07 写文件四条路，描述不互指；`scan_files` 只因 `write_file` 不能写子文件夹而存在**
- 证据：`write_file` 只能写到项目根，写子文件夹要「写完再调 scan_files 登记」（`FileTools.java:381-383`）；`scan_files` 描述 "Repair DB inconsistency"（`:584`）。`text_write_file`（覆盖已有纯文本）声明 LOWA（`TextFileEditTools.java:62`），Office / none 会话能新建纯文本却不能覆盖。`write_docx`（`FileTools.java:439`）与 `doc_start_stream`（`DocumentEditTools.java:283`）都能新建 docx，描述互不点名。
- 裁决：做。`write_file` 加 `parentFolderId`（与 `write_docx` 对齐），`scan_files` 改 `offerToModel=false`；`write_docx` / `doc_start_stream` 描述各加判据（长篇/要边写边看 → stream；一次落盘或非 LOWA 会话 → write_docx）。落地：批次 1。

**T-12 记忆工具面：13 个登记、11 个下发，两套写入两套检索，`query_memory` 自称「唯一」**
- 证据：核心集的 `save_memory` / `query_memory`，加编排器无条件保留的 `memory_list/read/search/write/edit/delete`（`AgentOrchestrator.java:54-57`，skill 白名单裁不掉），加 `get_user_profile` / `get_project_context` / `get_conversation_summary` / `update_project_info`。`query_memory` 描述「这是检索项目记忆的唯一工具」（`MemoryTools.java:195`），`memory_search`（`:520`）同时下发且检索同一份 Markdown 记忆——`save_memory` 写入经 `MemoryManager.java:113` 的 `migrateLegacyEntry` 进 Markdown 文档服务。`ToolChoiceSurfaceTest.java:60` 候选清单没把 `memory_search` 算竞争者。
- 裁决：做。`query_memory` 删「唯一」措辞；`save_memory`/`query_memory` 与 `memory_*` 各写一句心智模型（记一条 / 找一条 vs 管理记忆文件本身）。落地：批次 2。

**T-13 三个「取上下文」工具与每轮自动注入重复；一个与领域文档矛盾**
- 证据：`get_project_context` 返回 `pm.toCoreContext()`（`MemoryTools.java:335-365`），同一段每轮已注入系统提示易变段（`ContextAssemblerService.java:1006-1007`）；`get_user_profile`（`MemoryTools.java:145`）与每轮注入的「用户偏好与习惯」（`ContextAssemblerService.java:1025`）重叠；`get_conversation_summary`（`MemoryTools.java:440`）与压缩摘要重叠。ai-chat.md 称 `get_conversation_summary` 已「只登记不下发」，代码里**没有** `offerToModel=false`。`update_project_info` 描述仅 27 字（`MemoryTools.java:371`），被归进 enterprise-data（HEAD `ToolDisclosurePolicy.java:139-140` 的 `name:update_project_info`），ai-chat.md 记录生产库这五列非空计数为 0。
- 裁决：做。三个 `get_*` 改 `offerToModel=false`；ai-chat.md 那句改成事实；`update_project_info` 归类见 T-19（改归 memory）。落地：批次 2（类目部分在批次 3）。

**T-14 文档内「替换 / 删除」有八条路，其中三条没有判据句**
- 证据（均 `DocumentEditTools.java`）：`doc_find_replace`（核心,`:514`）、`doc_replace_at_anchor`（核心,`:892`）、`doc_replace_selection`（核心,`:613`）、`doc_replace_nth_match`（`:541`）、`doc_modify_paragraph`（`:686`）、`doc_delete_text`（核心,`:594`,32 字）、`doc_delete_match`（`:571`,39 字）、`doc_delete_selection`（`:912`）。`doc_replace_nth_match` 自称「要删除就传空串」；`doc_find_text` 描述已把推荐路径定为「anchorId → doc_replace_at_anchor」（`:471-474`）。
- 推断：删一句话时在 `doc_delete_text` / `doc_delete_match` / `doc_find_replace("")` 之间随机。
- 裁决：做。`doc_delete_text` / `doc_delete_match` / `doc_replace_nth_match` 改 `offerToModel=false`，`doc_delete_text` 移出 CORE；`doc_modify_paragraph` 描述写清「只在整段重写时用」；删除统一为 `doc_replace_at_anchor(anchorId, "")` / `doc_find_replace(…, "")`，两者描述各加一句。检查回放用例里引用被下线工具的改成新路径（用例是契约，改前先跑基线）。落地：批次 3。

**T-15 选区类工具：两个近乎相同的「看选区」，一个与设计原则矛盾的「设选区」**
- 证据：`doc_get_selection`（核心,`DocumentEditTools.java:421`,39 字）被 `doc_get_cursor_context`（核心,`:834`）覆盖；`doc_set_selection(start,end)` 用「0-based 字符索引」（`:453-456`），而同文件设计注释写「禁止使用整数字符偏移（跨富文本必然错位）」（`:796`）。
- 裁决：做。`doc_set_selection` 改 `offerToModel=false`；`doc_get_selection` 改 `offerToModel=false` 并移出 CORE（`doc_get_cursor_context` 覆盖）。落地：批次 3。

**T-16 「在某句之前/之后插入」在核心集里是断链，LOWA 与 Office 两面插入原语形状不一致**
- 证据：LOWA 要三步 `doc_select_anchor`（核心）→ `doc_collapse_cursor`（format 类目，非核心,`DocumentEditTools.java:877`）→ `doc_insert_at_cursor`（核心,28 字,`:631`）；Office 面一步：`office_insert_text(text, anchorText, position)`（`OfficeEditTools.java:621`）。`doc_insert_under_heading`（核心,27 字）只覆盖「标题下」。
- 推断：渐进披露开启时找不到 collapse，退而用 `doc_replace_at_anchor` 整体替换模拟插入（修订颗粒度依赖逐字照抄）。
- 裁决：做。`doc_insert_at_cursor` 加可选 `anchorId` + `position(before|after)`：服务端按 select_anchor → collapse_cursor → insert_at_cursor 三条既有桥命令串行组合，单测断言命令序列；`frontend/tests/lowa-e2e` 加一条无头用例验证「在某句之后插入」真落在锚点后（判据读回正文）。之后 `doc_insert_under_heading` 移出 CORE、`doc_collapse_cursor` 描述指向新参数。落地：批次 3。

**T-27 按类型的专用清单三重**
- 证据：`pptx_search_files`（32 字,`PptxTools.java:148`）、`pptx_list_files`（`:113`）、`pdf_list_files`（`PdfTools.java:61`）与 `search_project_files` / `doc_list_project_files` 重叠，存在理由只是 T-01。`prompts/tools-lowa.md:253` 写着「所有 pdf_* 工具的 fileId 从这里拿」，与 `doc_list_project_files` 自称权威清单冲突。
- 裁决：做，随 T-01。三者改 `offerToModel=false`；`prompts/tools-*.md`、`tools-office-word.md` 里指向它们的句子改指 `doc_list_project_files`。落地：批次 1。

同族平行、不算重复：`doc_find_text` / `office_search` / `sheet_search`，`doc_*` / `office_*` / `slide_*` / `sheet_*` 各套编辑原语按会话能力与活跃文档类型互斥下发，同一会话里从不同时出现。已知跨族坑（`slide_add_page` 插在之后 vs `office_ppt_add_slide` 插在之前、表格行号 1 基 vs 0 基）已由 `ToolChoiceSurfaceTest.java:173-194` 守着。

### b. 死工具、怪工具

**T-09 下发工具的描述指向已下线工具**
- 证据：`pptx_inspect_format` 描述末句「修改 PPT 文本或格式前必须先调用本工具获取定位索引，再用 pptx_apply_format 执行修改」（`PptxTools.java:634`），而 `pptx_apply_format` 已是 `offerToModel=false`（`PptxTools.java:690`，#808 下线理由：改磁盘字节后强制 reload、丢未保存修改；0 基索引与 slide_* 相反）。脚本扫全部 289 个下发工具描述，全仓仅此一处。推断：经 XML 兜底路径调到被下线的工具，触发下线要防的两个坑。
- 裁决：做。描述改指 `slide_*`；`ToolChoiceSurfaceTest` 加规则「下发工具的描述不得点名 `offerToModel=false` 的工具」（扫全部 spec）。落地：批次 2。

**T-10 同一管线只有收尾声明了 LOWA，前置步骤在 Office / none 可见**
- 证据：`litigation_render`、`litigation_timeline_render` 声明 `requiresHost=LOWA`，但 `litigation_reference`（`LitigationVisualTools.java:146`）、`litigation_checkpoint`（`:170`）、`litigation_timeline_start`（`LitigationTimelineTools.java:122`）、`litigation_timeline_step`（`:216`）不声明；`litigation_checkpoint` 与 `litigation_timeline_start` 描述还点名了那些会话里不可见的 `litigation_render`。同形：`pptx_generate` 声明 LOWA，而 `pptx_generate_outline` / `pptx_refine_outline` / `pptx_get_project_pages` / `pptx_check_service` / `pptx_export_editable`（后三个要 `serviceProjectId`，只能来自 `pptx_generate`）都不声明；`plugin_dev_scaffold` 描述让用 `text_write_file` / `text_find_replace`（LOWA）改源码（`PluginDevTools.java:28`）。
- 裁决：做。上述 4 个 litigation 与 5 个 pptx 前置工具跟随收尾声明 `requiresHost=LOWA`；`plugin_dev_scaffold` 描述改成不点名；`ToolDeclarationContractTest` 逐名清单同步。落地：批次 3。

**T-11 33 处失败返回不带 `Error` / `错误` 前缀，被判成功**
- 证据：`ToolResult.success()` 只认前缀（`ToolRegistry.java:178-189`）。`return "…失败…"` / `"…出错…"` 判 SUCCESS：`MemoryTools` 9 处（如 `:138`、`:563/568/572`）、`PptxTools` 12 处（如 `:288`、`:464`、`:555`）、`LitigationVisualTools` 6 处（如 `:200`、`:321`）、`LitigationTimelineTools` 6 处（如 `:189`、`:330`）。另两处语义相近：`LegalTools` 的「法规检索本次不可用」（刻意不打断整轮）、`MeetingTools` 的「该会议不属于当前项目」。后果同 `ToolFailureClassificationTest` 已修那批：绿勾、`consecutiveFailures` 清零、纠正回路不触发、埋点记成功。
- 裁决：做。33 处加 `Error: ` / `错误：` 前缀；`ToolFailureClassificationTest` 加源码扫描断言。落地：批次 2。

**T-24 `litigation_timeline_start` 绕开统一抽取器，扫描件不 OCR**
- 证据：用 `documentTextService.extractText(pf)`（`LitigationTimelineTools.java:158`），不走 `ProjectFileTextExtractor`（ai-chat.md 记载其余读取入口 #800 起全部收敛到它，含 OCR 与落库缓存）；扫描件回「请先用 OCR（如 pdf_to_word 的 OCR 路线）转出文字版」（`:179`），而 `extract_file_text` 本来就自动 OCR，`pdf_to_word` 又是 LOWA 专属。
- 裁决：做。改走 `ProjectFileTextExtractor`，删掉「先用 pdf_to_word OCR」那句。落地：批次 2。

**T-26 开发者向工具在律师会话里常驻**
- 证据：`capability_list/install/apply/select`（`CapabilityTools.java:39/74/121/144`）与 `plugin_dev_scaffold/install`（`PluginDevTools.java:28/53`）Host NONE、默认下发到所有会话；`scan_files` 同理。#1064 在途会在开着文档的会话里默认藏掉 plugin 类目，纯对话与 Office 会话仍常驻。
- 裁决：做。`capability_*` / `plugin_dev_*` 改 `offerToModel=false`，由 `plugin-dev` skill 的 `allowed_tools`（restrict）显式启用——先核对 `SkillRouter` 白名单能否放行 `offerToModel=false` 的工具；不能就改成「挂在 plugin 类目 + 纯对话会话也默认藏」。`scan_files` 见 T-07。落地：批次 3。

其它登记但怪的（仅事实，无独立裁决）：只有服务端注入参数、对模型零参数的工具（`doc_list_project_files`、`scan_files`、`meeting_list_recordings`、`pdf_list_files`、`list_project_folders`、`pptx_list_files`、`tag_list`，以及 `office_get_selection` / `office_get_comments` / `office_excel_get_overview` / `office_ppt_get_slides` / `office_get_revisions`）本身没问题，注入参数已在 schema 里剥掉（`ToolRegistry.java:311`）；`delete_file`（`FileTools.java:635-641`）与 `doc_debug_revisions`（`DocumentEditTools.java:2856`）已 `offerToModel=false`，合理；`pptx_check_service` 描述要求「生成前应先调用」（`PptxTools.java:276`），每次生成多一个往返，可由 `pptx_generate` 自检吸收（另开卡 dev-board#1072 一并处理）。

### c. 描述质量

**T-08 提示词与描述要求模型传 schema 里已不存在的参数（projectId）**
- 证据：`doc_start_stream` 描述「重要：创建新文件时必须提供 fileName 和 projectId 参数」（`DocumentEditTools.java:283`），而 `projectId` 是服务端强注入参数，#810 起从下发 schema 剥掉（`ToolRegistry.java:50`、`:311-322`）。提示词以 `工具名(…projectId…)` 写签名：`tools-lowa.md` 10 处（含 `:171` 示例 `projectId=123`）、`tools-none.md` 5 处、英文版各同数、`system_prompt.md` 3 处。`tools-none.md` 把 `list_files(dirPath)` 写成错参数名（实参 `subPath`，`FileTools.java:211`），`write_docx(name, markdown_content, projectId)` 靠 `ARG_ALIASES`（`ToolRegistry.java:53-62`）兜。
- 裁决：做。提示词片段（`tools-lowa.md` / `tools-none.md` 中英、`system_prompt.md`）签名去掉 `projectId`，`list_files(dirPath)` 改 `subPath`；`SystemPromptToolVisibilityContractTest` 加「反引号签名里的参数名必须存在于该工具下发 schema」。落地：批次 2。

**T-17 四族「查找替换」缺省值不一致**
- 证据：`doc_find_replace` 缺省替换全部（`DocumentEditTools.java:521`、`:532`）；`office_replace_text` 缺省第一处（`OfficeEditTools.java:310`）；`slide_replace_text` 缺省第一处（`SlideEditTools.java:180`）；`text_find_replace` 的 `replaceAll` 必填 boolean（`TextFileEditTools.java:104`）。唯一「必须恰好命中一次」检查在 `memory_edit`（`MemoryTools.java:572`）。
- 裁决：刻意不做（改 Edit 语义）。理由：律师最常见操作是「全文把甲方改成买方」，命中 >1 先回清单会每次多一轮；修订模式已是安全网。只统一四族描述、把各自缺省写明。写进 ai-chat.md「刻意没修」段。

**T-21 核心集里 7 个工具描述不足 45 字；13 个工具的参数没有 @P**
- 证据：`doc_get_paragraph` 15 字（`DocumentEditTools.java:669`）、`doc_get_outline` 21（`:711`）、`doc_insert_under_heading` 27（`:723`）、`doc_insert_at_cursor` 28（`:631`）、`doc_delete_text` 32（`:594`）、`doc_get_selection` 39（`:421`）、`doc_open_file` 41（`:136`）。非核心更短的：`doc_redo` 15、`doc_reject_all_revisions` 18、`get_conversation_summary` 25、`update_project_info` 27、`pptx_search_files` 32、`doc_search_related_docs` 35、`pptx_refine_outline` 37、`pptx_get_project_pages` 38、`doc_delete_match` 39、`doc_set_selection` 41、`pptx_check_service` 41。无 @P：`law_search`、`law_search_keyword`、`law_recognition`、`get_law_article`（`LegalTools.java:167-202`）、`search_web`、`browse_url`（`WebTools.java:84/213`）、`read_document`、`read_file`、`qichacha_query`、`qichacha_ipr`、`tushare_query`、`run_python`、`dispatch_subtask`。推断（未验证）：langchain4j 0.36 对无 @P 参数一律标 required，`law_search_keyword(title, fulltext)` 两个都成必填，实现里二者都可选（`LegalTools.java:180-188`）。
- 裁决：做。核心集里 7 个短描述各补一句「与哪个兄弟的区别」；13 个无 @P 的补 @P，可选的标 `required=false`（先用 `ToolSpecifications.toolSpecificationFrom` 写一条测试证明无 @P 参数在 0.36 下是否 required，结果写进 PR）。落地：批次 2。

**T-22 超过 600 字的描述**
- 证据（字数降序）：`ask_user` 1348（判据，保留；ai-chat.md 记录真实模型评测 8/8 依赖）、`pptx_apply_format` 1104（已不下发）、`litigation_render` 998（约一半是七种 layout 枚举，可挪 @P）、`move_files_batch` 947、`list_tools` 855、`litigation_checkpoint` 790、`move_to_trash` 772、`litigation_timeline_step` 743、`dispatch_subtask` 731、`sheet_write_cells` 717、`litigation_timeline_start` 700、`list_files` 633。草稿结论：超长描述大多是判据不是水分，真正成本在「整类工具在不相关会话里常驻」，那正是 #1064 要解决的。
- 裁决：只做一项——`litigation_render` 七种 layout 枚举挪进 @P。其余保留。落地：批次 2。

**T-23 描述点名了当前会话看不见的工具（除 T-01/T-09/T-10 外）**
- 证据：`doc_restore_checkpoint` 在 pptx 会话可见，描述说「优先使用 doc_undo」（`CheckpointTools.java:24`），而 `doc_undo` 在 pptx 会话不下发、在 Impress 上也无效（`ClientCapabilityService.java:201-204`）；`docx_inspect_template` 在所有会话可见，指向仅 LOWA-docx 的 `doc_apply_style_profile`（`TemplateTools.java:53`）；`pdf_inspect` 在 Office / none 可见，指向 LOWA 的 `pdf_to_word`（`PdfTools.java:96`）。
- 裁决：做。`doc_restore_checkpoint` 在 pptx 会话不提 `doc_undo`；`docx_inspect_template` / `pdf_inspect` 加「在桌面端会话里」限定。落地：批次 2。

### d. misc 兜底与类目

**T-20 落进 misc 的工具**
- 证据：静态 5 个：`get_user_profile`、`get_project_context`、`get_conversation_summary`、`search_knowledge_base`、`deep_search`（后两个 `offerToModel=false`）。`ToolDisclosurePolicyTest.java:53-67` 上限 12，今天没超。推断（未验证具体名单）：运行期由 `PluginService` 注册的 JAR 插件工具（`ToolRegistry.java:397-399`）名字不命中任何类目规则，全落 misc。
- 裁决：做。运行期插件工具单独成类 `plugin-tools`：`ToolRegistry` 暴露 `pluginToolNames()`，policy 归类时先查它。前三个静态项见 T-13。落地：批次 3。

**T-19 类目命名与归属失配**
- 证据：`format` 是 `doc_` 通配兜底（HEAD `ToolDisclosurePolicy.java:131`），23 个成员里至少 10 个不是「格式」：`doc_goto`、`doc_set_selection`、`doc_replace_nth_match`、`doc_delete_match`、`doc_modify_paragraph`、`doc_search_related_docs`、`doc_collapse_cursor`、`doc_delete_selection`、`doc_redo`、`doc_insert_image`（外加脚注/尾注/分隔符）。`tag_list/tag_file/tag_remove_from_file` 归 `task`（`:145`）；`update_project_info`、`web_verify_import` 归 `enterprise-data`；`dd_export`、`text_write_file`、`text_find_replace` 归 `files`；`revision` 里混着三个批注工具。
- 裁决：做。`format` 拆出 `edit`（`doc_goto` / `doc_collapse_cursor` / `doc_delete_selection` / `doc_redo` / `doc_select_*` / `doc_modify_paragraph` 等定位与删改）；`tag` 独立；`update_project_info` → memory；`dd_export` / `web_verify_import` → evidence；`doc_restore_checkpoint` → revision（移出 CORE，`doc_undo` 留）。`ToolDisclosurePolicyTest` 覆盖面断言同步。落地：批次 3。

**T-18 Excel / PPT 任务窗格在核心集里没有专用工具，而末位提醒点名的正是它们**
- 证据：CORE 的 5 个 `office_*` 全是 Word 面（`ToolDisclosurePolicy.java:103-105`），Office-Excel / Office-PPT 会话求交后只剩 18 个通用工具；系统提示与末位提醒要求「读取/修改一律使用 office_excel_get_range / …」「office_ppt_get_slides / …」（`ContextAssemblerService.java:789`、`:808`、`:958-959`、`:1771`、`:1787`）。docx 同理：末位提醒点名的 `doc_insert_table`（`:1844`）不在核心。渐进披露默认关，今天不出事；一旦打开，提示词与下发集当场矛盾（`SystemPromptToolVisibilityContractTest` 只看能力档不看披露开关）。
- 裁决：并入第二步（渐进披露默认开）。核心集改「通用段 + 按宿主段」，见第 5 节。真实模型 10x3x2 复跑在这套核心集上做，完成率必须 10/10。

**T-25 产品里已有、但没做成工具的「兄弟能力」**
- 证据：复制文件 `ProjectFileService.batchCopy`（`:1172`）；导出 PDF——编辑器命令 `export_pdf` 已在桥超时表里（`EditorBridgeService.java:125`）；全文检索 `ContentSearchService`（T-03）；案例检索——依据窗格法宝案例通道（`.claude/agents/doc-insight.md:147`）；发起音频转写 `MeetingRecordingService.registerExisting`（`:105`）；删除事项 `ProjectTaskService.deleteTask`（`:231`，可能刻意）；文档对比 `FileController.java:605` 的 `/compare` 只抽两份文本给前端做 diff，草稿不建议另做工具；幻灯片插图、Word 任务窗格大纲/条款/结构审计见第 4 节场景 12、13。
- 裁决：分项。做：`copy_files(fileIds, targetFolderId?)` 包 `batchCopy`，files 类目（批次 1）；`doc_export_pdf(fileId?)`，LOWA 声明，走既有桥命令 `export_pdf`，`frontend/tests/lowa-e2e` 无头用例验证导出文件落进项目树（批次 3）；全文检索见 T-03。刻意不做：事项删除、文档对比。另开卡：案例检索、发起音频转写、幻灯片插图、Word 任务窗格对位工具（第 7 节）。

### e. 对齐 Claude Code（无 T 编号，出自草稿 Step 4）

- ToolSearch 对位：`list_tools` 只能按类目展开。裁决：做。`list_tools` 加 `query`（按名字与描述关键词匹配，返回命中工具全签名并展开其类目）与 `names`（按名精确展开）；无参索引末尾列出可用 skill 一行（id + 一句话）。落地：批次 4。
- Skill 对位：模型无法主动加载 skill。裁决：做。新增 `use_skill(skillId)`，返回该 skill 的 prompt 正文作为工具结果，并把其 `allowed_tools` 覆盖的类目加进 `expandedToolCategories`（下一轮生效，沿用同一机制）；不在本轮改 system prompt（assemble 每条消息只调一次）；`ORCHESTRATION_TOOLS` 与 CORE 都加它。回放用例：「不带触发词的股东会核验问题 → use_skill → 下一轮白名单工具可见」。落地：批次 4。

## 4. 场景完整性

「核心」= 在 CORE 里；「类目」= 需 `list_tools` 展开（仅当渐进披露开启时才有意义，默认关时全部直接下发）。

| # | 场景（会话） | 今天的工具链 | 缺环 / 别扭处 | 缺环处置 |
|---|---|---|---|---|
| 1 | 读活跃 docx 并回答（LOWA） | 活跃文档正文每轮已内联注入；需要时 `doc_get_document_text`/`doc_find_text`/`doc_get_clauses`（核心） | 无缺环。`doc_get_outline`、`doc_get_paragraph`、`doc_get_selection` 可被 `doc_get_document_text` 与 `doc_get_cursor_context` 覆盖（T-15/T-21） | 做（T-15 批次 3；三者移出核心并入第二步） |
| 2 | 改措辞留修订（LOWA） | `doc_find_text` → `doc_replace_at_anchor` 或 `doc_find_replace`（核心） | 无缺环。风险：`doc_find_replace` 缺省全替（T-17）；删除路径过多（T-14） | T-14 做；T-17 刻意不做 |
| 3 | 起草新文档 | LOWA：`doc_start_stream` / `write_docx`（核心）→ `doc_apply_standard_format`（核心）或 `doc_apply_style_profile`；模板：`list_contributed_templates` → `create_file_from_template`；Office/none：只有 `write_docx` | 插表格：末位提醒要求 `doc_insert_table` 一次提交（`ContextAssemblerService.java:1844`），它在 table 类目、不在核心。放进新文件夹：`create_folder`（核心）→ id；放进已有文件夹要 `list_project_folders`（files 类目） | 做（`doc_insert_table` 进 docx 段核心，并入第二步；`create_folder` 留核心；`write_file` 加 `parentFolderId`，T-07） |
| 4 | 审查合同并加批注（LOWA） | `doc_get_document_text` → `doc_audit_structure` → `doc_get_clauses` → `doc_find_text` → `doc_add_comment` / `doc_replace_at_anchor`（全核心）；对方修订与既有批注在 revision 类目 | 无缺环。Word 任务窗格没有 `audit_structure` / `get_clauses` / `get_outline` 对位工具（见 13） | 另开卡（Word 任务窗格对位工具） |
| 5 | 查法条 / 案例并引用 | `law_search`、`law_search_keyword`、`get_law_article`（核心）、`law_recognition`（legal） | 案例检索没有工具：法宝案例通道只接给依据窗格（`doc-insight.md:147`），模型只能 `search_web`。账户未连时四个法规工具与 `search_web` 都不下发（运行期闸），此时无任何法源 | 另开卡（案例检索工具） |
| 6 | 查企业工商 | `qichacha_query`、`qichacha_ipr`、`tushare_query`（enterprise-data），`web_verify_import` | 涉诉 / 失信 / 司法风险没有直接查询工具，只能导入网核包或公网搜（未验证 `QichachaService` 是否已有对应接口）。#1064 在途会在开着文档时默认藏掉本类目，靠关键词放回 | 另开卡 dev-board#1072（先核 QichachaService 现有接口与套餐；`web_verify_import` 归类见 T-19） |
| 7 | PDF 转 Word / 高亮 / 脱敏 | `pdf_inspect`（全会话）→ `pdf_to_word` / `pdf_highlight` / `pdf_redact` / `pdf_annotate` / `pdf_replace_text`（LOWA 声明）；页操作 6 个全会话 | 扫描件脱敏做不了：`pdf_inspect` 描述明说无文本层时无法做文本定位类操作（`PdfTools.java:101`）。身份证等扫描件恰是律师最常见脱敏对象。Office/none 整组写操作不可用（刻意） | 另开卡（扫描件 PDF 脱敏，需 OCR 坐标回写） |
| 8 | 时间轴 / 关系图 | `litigation_reference` → `litigation_checkpoint` → `litigation_render`；`litigation_timeline_start` → `_step`xN → `_timeline_render` | Office/none 会话走到出图一步工具消失（T-10）；扫描件材料不 OCR（T-24） | 做（T-10 批次 3；T-24 批次 2） |
| 9 | 事项与日程 | `task_create` / `task_list` / `task_update`（task） | 没有删除事项（`ProjectTaskService.deleteTask` 在 `:231`，未暴露；可能刻意，只能 `status=DONE`）。改期、提醒（`remindBefore`）齐全 | 刻意不做（破坏性且无回收站；`status=DONE/CANCELLED` 够用） |
| 10 | 会议录音转写与纪要 | `meeting_list_recordings` → `meeting_get_transcript` → `write_docx` / `doc_start_stream`；音频文件也可 `extract_file_text` 直接拿转写稿 | 不能由模型发起转写（`MeetingRecordingService.registerExisting` 在 `:105`，工具面只有读）；`meeting_get_transcript` 与 `extract_file_text(音频)` 两条路重叠 | 另开卡（meeting_transcribe） |
| 11 | xlsx 会话（LOWA） | 核心 `sheet_create_file`/`sheet_get_overview`/`sheet_read_range`/`sheet_write_cells` + spreadsheet 18 个 + `doc_undo` | 无缺环。`sheet_find_replace` 描述说「成批改写一律用本工具」却不在核心 | 做（`sheet_find_replace` 进 xlsx 段核心，并入第二步） |
| 12 | pptx 会话（LOWA） | 核心 `slide_get_overview`/`slide_set_shape_text`/`slide_add_page` + slides 类目 19 个 slide_* + `pptx_generate` 等 | 不能往幻灯片插图片（slide_* 与 office_ppt_* 都没有图片工具；Word 两面都有）；没有复制页；撤销在 Impress 无效，只剩整轮回滚 `doc_restore_checkpoint` | 另开卡（slide_insert_image 与复制页） |
| 13 | Word 任务窗格（office_*） | 核心 `office_get_text`/`office_search`/`office_insert_text`/`office_replace_text`/`office_replace_batch`；大改走 `office_pass_step` | 没有项目文件清单（T-01）；没有大纲 / 条款结构 / 结构审计 / 撤销对位工具；读取只有按字符分页 | 清单缺失：做（T-01）；对位工具：另开卡 |
| 14 | 多文件项目整理 | 清单（LOWA：`doc_list_project_files`；其它：`search_project_files` / `list_files`）→ `create_folder` / `move_files_batch` / `rename_project_file` / `move_to_trash` | 不能复制文件（`ProjectFileService.batchCopy` 在 `:1172`，UI 有工具没有）；Office/none 清单缺失（T-01）；移动工具重叠（T-06） | 做（`copy_files` 批次 1；T-01、T-06 批次 1） |
| 15 | 记忆 | `save_memory` / `query_memory`（核心）+ `memory_*` 六个（编排器强制保留） | 无缺环，问题在重复（T-12/T-13） | 做（批次 2） |
| 16 | 子任务派发与反问 | `dispatch_subtask`、`ask_user`、`todo_write`（核心，且 skill 裁不掉，`SkillRouter.java:62`） | 子任务不能指定类型或模型（全局 `ai.subagentModel`），不能续问（无 SendMessage 对位），同步阻塞最长 630 秒 | 刻意不做（没有真实需求） |

## 5. 核心集瘦身

### 草稿提案（目标约 25-30）

原则：核心集改「通用段 + 按宿主段」，与会话求交后每种会话落在 20-30；不在核心的工具仍登记，可经 `list_tools` 当轮 XML 调用或下一轮原生调用（`ToolDisclosurePolicy` 三条安全性质不变）。

- 通用段（15-16）：`list_tools`、`todo_write`、`ask_user`、`dispatch_subtask`、文件清单、`search_project_files`、新增 `search_project_content`、`extract_file_text`、`write_docx`、`move_files_batch`、`query_memory`、`save_memory`、`law_search`、`get_law_article`、`search_web`、`browse_url`。
- LOWA-docx +13（合计约 29）：`doc_open_file`、`doc_get_document_text`、`doc_find_text`、`doc_get_clauses`、`doc_audit_structure`、`doc_find_replace`、`doc_replace_at_anchor`、`doc_insert_at_cursor`（加锚点参数后）、`doc_start_stream`、`doc_insert_table`、`doc_add_comment`、`doc_undo`、`doc_apply_standard_format`。
- LOWA-xlsx +6（约 22）：`sheet_create_file`、`sheet_get_overview`、`sheet_read_range`、`sheet_write_cells`、`sheet_find_replace`、`doc_undo`。
- LOWA-pptx +5（约 21）：`slide_get_overview`、`slide_get_page`、`slide_set_shape_text`、`slide_replace_text`、`slide_add_page`。
- Office-Word +6（约 22）：`office_get_text`、`office_search`、`office_insert_text`、`office_replace_text`、`office_replace_batch`、`office_add_comment`。
- Office-Excel +5（约 21）：`office_excel_get_overview`、`office_excel_get_range`、`office_excel_set_values`、`office_excel_search`、`office_excel_replace`。
- Office-PPT +4（约 20）：`office_ppt_get_slides`、`office_ppt_replace_text`、`office_ppt_format_text`、`office_ppt_add_slide`。
- 草稿另提议移出 `create_folder`、`move_to_trash`、`doc_get_cursor_context`（与 `doc_get_selection` 二选一）；提醒：渐进披露今天默认关（`application.yml:497`，HEAD），ai-chat.md 记录的真实模型 A/B 里 3 次有 1 次跑偏，核心集怎么定只在开关翻开或 #1064 类目裁剪默认开之后才影响真实用户。

### 裁决（B 段，并入第二步 PR，渐进披露默认开）

与草稿的差异：多留 `create_folder`、`move_to_trash`、`use_skill`；`doc_get_cursor_context` 留在 docx 段。

- 通用段：`list_tools`、`use_skill`、`todo_write`、`ask_user`、`dispatch_subtask`、`doc_list_project_files`、`search_project_files`、`search_project_content`、`extract_file_text`、`write_docx`、`create_folder`、`move_files_batch`、`move_to_trash`、`query_memory`、`save_memory`、`law_search`、`get_law_article`、`search_web`、`browse_url`（实数 19；裁决原文写「通用 16」，19 为逐名计数）。
- docx 段：`doc_open_file`、`doc_get_document_text`、`doc_find_text`、`doc_get_clauses`、`doc_audit_structure`、`doc_find_replace`、`doc_replace_at_anchor`、`doc_insert_at_cursor`、`doc_start_stream`、`doc_insert_table`、`doc_add_comment`、`doc_undo`、`doc_apply_standard_format`、`doc_get_cursor_context`（逐名 14；裁决原文写「+13」）。
- xlsx +6、pptx +5、Office-Word +6、Office-Excel +5、Office-PPT +4，名单照草稿。裁决预期：docx 会话约 33，其余 22-25。
- 移出核心的 12 个：`read_document`、`law_search_keyword`、`doc_get_outline`、`doc_get_paragraph`、`doc_get_selection`、`doc_replace_selection`、`doc_select_anchor`、`doc_select_paragraph`、`doc_delete_text`、`doc_insert_under_heading`、`doc_restore_checkpoint`、`doc_get_comments`。
- 验收：真实模型 10x3x2 复跑在这套核心集上做，完成率必须 10/10。

## 6. 刻意不做

写进 ai-chat.md「刻意没修」段。

- T-17 `doc_find_replace` 缺省全替不改：律师最常见的就是「全文把甲方改成买方」，改成「命中 >1 先回清单」每次多一轮；修订模式已是安全网。只统一四族描述、把各自缺省写明。
- 事项删除不做工具：破坏性且无回收站；`status=DONE/CANCELLED` 够用。
- 子任务按次选模型 / SendMessage 续问：没有真实需求，先不做。
- 文档对比工具：两次 `extract_file_text` 等价。
- Bash 对位：律师机器不扩 shell。
- BashOutput/KillShell 对位（后台任务查询取消）：低优先，不做。

## 7. 另开卡

需要引擎或插件端新能力，不属于工具面盘点。

- 幻灯片插图（`slide_insert_image`，Impress UNO 插图）与复制页（场景 12）。
- Word 任务窗格的大纲 / 条款 / 结构审计对位工具（office-addin 三宿主；场景 4、13）。
- 案例检索工具（法宝案例通道从依据窗格抽成工具，doc-insight 域；场景 5）。
- 模型发起音频转写（`meeting_transcribe`；场景 10）。
- 扫描件 PDF 脱敏（需 OCR 坐标回写，pdf 域；场景 7）。

## 8. 未能验证 / 需留意

1. 同一 worktree 有另一会话在改 #1064（未提交）。本文按 HEAD；若 #1064 合入，T-18、T-20、T-22、T-26 与对照表 ToolSearch 一行需按它重看（它让 `list_tools` 在开着文档的会话默认可用，并按类目默认藏 pdf / litigation / slides / enterprise-data / plugin / meeting / python）。
2. 无 @P 参数在 langchain4j 0.36 下是否全部标 required（T-21）是推断，未跑 `ToolSpecifications` 验证（批次 2 裁决要求先写测试证明）。
3. `read_document` 与 `extract_file_text` 对 GBK 纯文本读出不同文字（T-05）只是按 `LegalTools.java:142-145` 注释推断，未实测。
4. 运行期插件 JAR 工具与 MCP 工具的名字与类目归属（T-20）未验证；附录只含源码里的 296 个 @Tool。
5. `QichachaService` 是否已有司法风险 / 涉诉接口（场景 6）未查。
6. `browse_url` 的截断上限未核对。
7. 所有「模型会怎么做」都是按描述推断，未跑真实模型。
8. 描述字数与 @P 字数是源码字符数，不是上线字节；上线字节用 `ToolSchemaBudgetTest.wireBytes()` 重测。
9. 抽查：裁决方对 T-01/02/03/09/13 与 HEAD 源码做过抽查，其余条目沿用草稿证据。

---

## 附录：工具清单（Step 1 全表）

以下内容原样取自 `tool-inventory-1065.md`（数据本体）。


基线：提交 202c2d3c（HEAD）。同一 worktree 里另有未提交的 dev-board#1064 改动（ToolDisclosurePolicy / ToolDiscoveryTools 等 10 个文件），本表一律按 HEAD 解析，不含那批改动。生成方式：解析 `backend/src/main/java/com/checkba/service/ai/tools/*.java` 中全部 `@Tool(` 注解（31 个 AgentToolComponent，已剔除注释），工具名 = 方法名（langchain4j 默认）。类目按 `ToolDisclosurePolicy.categoryOf`（ToolDisclosurePolicy.java:70-151，categoryOf 在 :174）的规则逐字复现；「可见会话」按 `ClientCapabilityService.isToolVisible`（ClientCapabilityService.java:242-276）+ `@ToolMeta.requiresHost` 复现，未计入运行期闸（账户未连接时 8 个外部数据工具不下发、本机无 Docker 时 run_python 不下发、渐进披露关闭时 list_tools 不下发）与 skill 白名单。
「模型可见参数」已剔除服务端强注入的 projectId / conversationId / userId（ToolRegistry.java:50、311）。「描述字数」= @Tool 文本字符数（常量已展开；少数 int 常量未展开，显示为 {常量名}）；「@P 字数」= 非注入参数的 @P 说明字符数合计。二者都不是上线字节（上线字节见 ToolSchemaBudgetTest.wireBytes），只作相对成本参考。
动态插件工具（PluginService 加载的 JAR 工具）与 MCP 工具不在本表内（运行期注册，源码里没有 @Tool）。

### 汇总

- 登记总数：**296**；其中 `offerToModel=false`（只登记不下发）7 个，实际可下发 289 个。
- 描述总字数 62131；@P 说明总字数 22949。
- 按类目：core 54、table 7、revision 9、evidence 4、template 4、format 23、spreadsheet 22、slides 31、office 75、pdf 13、litigation 6、reference 4、memory 6、enterprise-data 5、legal 1、meeting 2、task 6、files 12、plugin 6、python 1、misc 5
- 按可见会话：全部会话 86、LOWA-docx 59、Office-Word 39、Office-Excel 27、LOWA-xlsx 25、LOWA-pptx 22、Office-PPT 14、LOWA(声明) 12、LOWA(任意类型) 6、Office(全宿主) 4、LOWA-docx/xlsx 2
- 按会话实际可见（静态闸，不含运行期闸）：LOWA-docx 158、LOWA-xlsx 125、LOWA-pptx 120、Office-Word 125、Office-Excel 113、Office-PPT 100、none 82。
- 核心集（CORE，ToolDisclosurePolicy.java:70-105）共 54 个名字；与会话求交后：LOWA-docx 43、LOWA-xlsx 27、LOWA-pptx 26、Office-Word 23、Office-Excel 18、Office-PPT 18、none 18。

### 全表

| # | 工具名 | 组件:行 | 类目 | 可见会话 | requiresHost | 下发 | fileEffect | 模型可见参数（个数） | 描述字数 | @P字数 | 用途（取自描述首句） |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `ask_user` | AskUserTools:28 | core | 全部会话 | NONE | 是 | - | question, options, multi_select, header (4) | 1348 | 404 | Ask the user ONE clarifying question and END this turn. |
| 2 | `doc_restore_checkpoint` | CheckpointTools:24 | core | LOWA(任意类型) | NONE | 是 | MODIFIED | - (0) | 110 | 0 | 【恢复】把文档恢复到本轮开始前的快照（检查点）。 |
| 3 | `doc_audit_structure` | DocumentAuditTools:48 | core | LOWA-docx | NONE | 是 | - | - (0) | 267 | 0 | 【看·审查合同必用】对当前打开的文档做一次机械核对并返回报告：全文字形（繁體/简体）与混入段落、各套条款编号是否连续（第X条 / N. |
| 4 | `doc_list_project_files` | DocumentEditTools:63 | core | LOWA(任意类型) | NONE | 是 | - | - (0) | 389 | 0 | 项目文件的权威清单，一次列全：Word / Excel / PPT / PDF / 纯文本(txt,md,csv…) / 图片 / 其他，每条给出 fileId、名称与类型标注。 |
| 5 | `doc_open_file` | DocumentEditTools:136 | core | LOWA(任意类型) | NONE | 是 | - | fileId (1) | 41 | 33 | 打开指定文档进行编辑。 |
| 6 | `doc_start_stream` | DocumentEditTools:283 | core | LOWA(任意类型) | NONE | 是 | MODIFIED | fileId, fileName, parentFolderId (3) | 364 | 128 | 开始实时流式写入文档。 |
| 7 | `doc_get_selection` | DocumentEditTools:421 | core | LOWA-docx | NONE | 是 | - | - (0) | 39 | 0 | 获取文档中当前选区的文本内容和位置信息。 |
| 8 | `doc_find_text` | DocumentEditTools:471 | core | LOWA-docx | NONE | 是 | - | keyword, matchCase (2) | 321 | 22 | 【找】在文档中查找文本。 |
| 9 | `doc_find_replace` | DocumentEditTools:514 | core | LOWA-docx | NONE | 是 | MODIFIED | findText, replaceText, replaceAll (3) | 241 | 47 | 在文档中查找并替换文本。 |
| 10 | `doc_delete_text` | DocumentEditTools:594 | core | LOWA-docx | NONE | 是 | MODIFIED | text, deleteAll (2) | 32 | 25 | 删除文档中的文本内容。 |
| 11 | `doc_replace_selection` | DocumentEditTools:613 | core | LOWA-docx | NONE | 是 | MODIFIED | text (1) | 130 | 9 | 替换当前选区（或光标位置）的文本内容。 |
| 12 | `doc_insert_at_cursor` | DocumentEditTools:631 | core | LOWA-docx | NONE | 是 | MODIFIED | text (1) | 28 | 8 | 在文档的当前光标位置插入文本内容。 |
| 13 | `doc_get_paragraph` | DocumentEditTools:669 | core | LOWA-docx | NONE | 是 | - | paragraphIndex (1) | 15 | 43 | 获取文档中指定段落的文本内容。 |
| 14 | `doc_get_outline` | DocumentEditTools:711 | core | LOWA-docx | NONE | 是 | - | - (0) | 21 | 0 | 获取文档的大纲结构，包括各级标题及其位置。 |
| 15 | `doc_insert_under_heading` | DocumentEditTools:723 | core | LOWA-docx | NONE | 是 | MODIFIED | headingText, content (2) | 27 | 19 | 在文档的指定标题下方插入新内容。 |
| 16 | `doc_get_document_text` | DocumentEditTools:799 | core | LOWA-docx | NONE | 是 | - | startParagraph, maxParagraphs (2) | 102 | 32 | 【看】分段读取文档正文。 |
| 17 | `doc_get_clauses` | DocumentEditTools:818 | core | LOWA-docx | NONE | 是 | - | - (0) | 272 | 0 | 【看】识别合同/协议的条款结构。 |
| 18 | `doc_get_cursor_context` | DocumentEditTools:834 | core | LOWA-docx | NONE | 是 | - | - (0) | 50 | 0 | 【看】查看当前光标/选区周围的文本（选中内容、前后文、所在段落）。 |
| 19 | `doc_select_anchor` | DocumentEditTools:846 | core | LOWA-docx | NONE | 是 | - | anchorId (1) | 167 | 26 | 【选】选中 doc_find_text 返回的某个匹配（按 anchorId）。 |
| 20 | `doc_select_paragraph` | DocumentEditTools:862 | core | LOWA-docx | NONE | 是 | - | index (1) | 63 | 9 | 【选】按段落号选中整个段落（0 开始，配合 doc_get_document_text 的编号）。 |
| 21 | `doc_replace_at_anchor` | DocumentEditTools:892 | core | LOWA-docx | NONE | 是 | MODIFIED | anchorId, newText (2) | 269 | 29 | 【改】把某个锚点（anchorId）处的文本替换为新文本，以修订模式进行。 |
| 22 | `doc_apply_standard_format` | DocumentEditTools:1311 | core | LOWA-docx | NONE | 是 | MODIFIED | - (0) | 304 | 0 | 【格式】对整篇文档应用律所标准格式：正文楷体_GB2312/西文 Arial 12 号黑色、两端对齐、段前 0 段后 18 磅、行距最小值 16 磅、首行缩进 2 字符；首段短… |
| 23 | `doc_undo` | DocumentEditTools:1425 | core | LOWA-docx/xlsx | NONE | 是 | MODIFIED | steps (1) | 46 | 9 | 【验/撤销】撤销最近的编辑操作。 |
| 24 | `doc_add_comment` | DocumentEditTools:1457 | core | LOWA-docx | NONE | 是 | MODIFIED | anchorId, comment (2) | 170 | 53 | 【批注】在指定锚点处的文本上添加 Word 批注（comment）。 |
| 25 | `doc_get_comments` | DocumentEditTools:1477 | core | LOWA-docx | NONE | 是 | - | - (0) | 78 | 0 | 【看/批注】列出文档中的全部批注：作者、时间、内容、附着的文本摘要、所在段落、id（用于回复/解决/删除）、是否已解决。 |
| 26 | `sheet_get_overview` | DocumentEditTools:2100 | core | LOWA-xlsx | NONE | 是 | - | - (0) | 110 | 0 | 【表格·看】查看当前打开的电子表格（xlsx）的工作表结构：每张工作表的名称、序号、已用区域和行列数。 |
| 27 | `sheet_read_range` | DocumentEditTools:2113 | core | LOWA-xlsx | NONE | 是 | - | range, sheet, withFormat (3) | 406 | 102 | 【表格·看】读取电子表格指定区域的单元格内容。 |
| 28 | `sheet_write_cells` | DocumentEditTools:2138 | core | LOWA-xlsx | NONE | 是 | MODIFIED | startCell, rowsJson, sheet, inheritFormat (4) | 717 | 94 | 【表格·写】从起始单元格开始按二维数组批量写入。 |
| 29 | `sheet_create_file` | DocumentEditTools:2298 | core | LOWA(任意类型) | NONE | 是 | ADDED | fileName, parentFolderId (2) | 183 | 93 | 【表格·建】在项目中新建一个空白 Excel 表格文件（. |
| 30 | `search_project_files` | FileTools:74 | core | 全部会话 | NONE | 是 | - | fileNamePattern, dirPath (2) | 261 | 156 | Locate a file by NAME PATTERN. |
| 31 | `extract_file_text` | FileTools:281 | core | 全部会话 | NONE | 是 | - | fileId (1) | 566 | 126 | Extract the full plain text of a project file (pdf/docx/xlsx/doc, images etc. |
| 32 | `write_docx` | FileTools:439 | core | 全部会话 | NONE | 是 | ADDED | fileName, markdownContent, parentFolderId, styleProfileJson (4) | 214 | 131 | 【STRICTLY NEW FILES ONLY】Create a NEW . |
| 33 | `move_files_batch` | FileTools:701 | core | 全部会话 | NONE | 是 | - | movesJson (1) | 947 | 67 | Move MANY project files/folders in ONE call (file tree and storage stay in sync). |
| 34 | `move_to_trash` | FileTools:812 | core | 全部会话 | NONE | 是 | - | targetsJson (1) | 772 | 91 | Move project files/folders to the project RECYCLE BIN (recoverable - this is NOT a perma… |
| 35 | `create_folder` | FileTools:994 | core | 全部会话 | NONE | 是 | - | folderName, parentFolderId (2) | 182 | 61 | Create a new folder in the project file tree. |
| 36 | `read_document` | LegalTools:91 | core | 全部会话 | NONE | 是 | - | fileId (1) | 550 | 0 | Read a project file's full plain text by its database fileId (from doc_list_project_file… |
| 37 | `law_search` | LegalTools:167 | core | 全部会话 | NONE | 是 | - | query (1) | 140 | 0 | Search for laws and regulations using PKULaw MCP Semantic Search. |
| 38 | `law_search_keyword` | LegalTools:180 | core | 全部会话 | NONE | 是 | - | title, fulltext (2) | 95 | 0 | Search for laws by keywords in title or fulltext. |
| 39 | `get_law_article` | LegalTools:202 | core | 全部会话 | NONE | 是 | - | title, number (2) | 140 | 0 | Get the full content of a specific law article by its title and article number. |
| 40 | `save_memory` | MemoryTools:79 | core | 全部会话 | NONE | 是 | - | type, key, value, isProtected, scope, sourceFileId (6) | 110 | 283 | 保存重要信息到记忆中。 |
| 41 | `query_memory` | MemoryTools:195 | core | 全部会话 | NONE | 是 | - | query, type, scope, sourceFileId, depth, limit (6) | 453 | 267 | 在本项目的记忆里找此前的决策、结论、事实与约定。 |
| 42 | `office_get_text` | OfficeEditTools:235 | core | Office-Word | NONE | 是 | - | startChar, maxChars (2) | 303 | 101 | 读取当前 Word 文档的正文纯文本，分页返回。 |
| 43 | `office_search` | OfficeEditTools:269 | core | Office-Word | NONE | 是 | - | query (1) | 50 | 6 | 在当前 Word 文档中查找文本，返回命中数量与每处命中所在段落的上下文。 |
| 44 | `office_replace_text` | OfficeEditTools:287 | core | Office-Word | NONE | 是 | MODIFIED | searchText, replaceText, replaceAll (3) | 178 | 43 | 在当前 Word 文档中查找并替换文本，修改以 Word 原生修订（Track Changes）形式呈现。 |
| 45 | `office_replace_batch` | OfficeEditTools:317 | core | Office-Word | NONE | 是 | MODIFIED | editsJson (1) | 435 | 58 | 在当前 Word 文档中一次完成多处查找替换，全部以 Word 原生修订（Track Changes）形式呈现。 |
| 46 | `office_insert_text` | OfficeEditTools:621 | core | Office-Word | NONE | 是 | MODIFIED | text, anchorText, position (3) | 173 | 72 | 在当前 Word 文档中插入文本，插入以 Word 原生修订（Track Changes）形式呈现。 |
| 47 | `slide_get_overview` | SlideEditTools:54 | core | LOWA-pptx | NONE | 是 | - | - (0) | 147 | 0 | 【幻灯片·看】查看当前打开的演示文稿（pptx/odp）总览：每页的页码、名称、版式、母版、标题文字、形状数、是否有备注、是否含表格。 |
| 48 | `slide_set_shape_text` | SlideEditTools:146 | core | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, shapeName, text (3) | 187 | 57 | 【幻灯片·写】整体覆盖指定形状（文本框/标题/占位符）的文字（不是追加）。 |
| 49 | `slide_add_page` | SlideEditTools:202 | core | LOWA-pptx | NONE | 是 | MODIFIED | position, layout, title, body, insertAfterPage (5) | 459 | 156 | 【幻灯片·写】插入一页新幻灯片。 |
| 50 | `dispatch_subtask` | SubAgentTools:40 | core | 全部会话 | NONE | 是 | - | task_description, expected_output, tool_scope (3) | 731 | 0 | Delegate a self-contained subtask to an independent sub-agent that runs its own tool-use… |
| 51 | `todo_write` | TodoTools:25 | core | 全部会话 | NONE | 是 | - | todos (1) | 160 | 146 | 维护本轮工作的任务清单（整表覆写）。 |
| 52 | `list_tools` | ToolDiscoveryTools:59 | core | 全部会话 | NONE | 是 | - | category (1) | 855 | 82 | List the tools that exist but are NOT in the small default set you were given. |
| 53 | `search_web` | WebTools:84 | core | 全部会话 | NONE | 是 | - | query (1) | 128 | 0 | Search the web using Bocha AI. |
| 54 | `browse_url` | WebTools:213 | core | 全部会话 | NONE | 是 | - | url (1) | 51 | 0 | Browse a specific URL and extract its main content. |
| 55 | `doc_insert_table` | DocumentEditTools:1122 | table | LOWA-docx | NONE | 是 | MODIFIED | rowsJson, headerRow (2) | 118 | 39 | 【插入】在光标处插入一张表格，自动套标准表格式（Grid 1. |
| 56 | `doc_table_read` | DocumentEditTools:1152 | table | LOWA-docx | NONE | 是 | - | tableIndex, tableName, maxRows, maxCols (4) | 199 | 64 | 【看/表格】把文档里的一张表读成二维数组（行列数 + 每格文本），改表格前必须先用它看清现状。 |
| 57 | `doc_table_set_cell` | DocumentEditTools:1177 | table | LOWA-docx | NONE | 是 | MODIFIED | cell, text, tableIndex, tableName (4) | 176 | 65 | 【改/表格】改表格里一个单元格的文本（整格替换）。 |
| 58 | `doc_table_add_row` | DocumentEditTools:1205 | table | LOWA-docx | NONE | 是 | MODIFIED | position, count, tableIndex, tableName, insertBeforeRow1Based (5) | 230 | 111 | 【改/表格】给表格插入空白行。 |
| 59 | `doc_table_delete_row` | DocumentEditTools:1222 | table | LOWA-docx | NONE | 是 | MODIFIED | position, count, tableIndex, tableName, rowNumber1Based (5) | 200 | 97 | 【改/表格】删除表格的整行。 |
| 60 | `doc_table_add_col` | DocumentEditTools:1242 | table | LOWA-docx | NONE | 是 | MODIFIED | position, count, tableIndex, tableName, insertBeforeColumn (5) | 159 | 123 | 【改/表格】给表格插入空白列。 |
| 61 | `doc_table_delete_col` | DocumentEditTools:1259 | table | LOWA-docx | NONE | 是 | MODIFIED | position, count, tableIndex, tableName, columnRef (5) | 157 | 103 | 【改/表格】删除表格的整列。 |
| 62 | `doc_reply_comment` | DocumentEditTools:1490 | revision | LOWA-docx | NONE | 是 | MODIFIED | commentId, text (2) | 95 | 34 | 【批注】回复一条已有批注（commentId 来自 doc_get_comments 返回的 id）。 |
| 63 | `doc_resolve_comment` | DocumentEditTools:1512 | revision | LOWA-docx | NONE | 是 | MODIFIED | commentId, resolved (2) | 60 | 56 | 【批注】把一条批注标记为已解决/取消已解决（commentId 来自 doc_get_comments 返回的 id）。 |
| 64 | `doc_delete_comment` | DocumentEditTools:1533 | revision | LOWA-docx | NONE | 是 | MODIFIED | commentId (1) | 65 | 26 | 【批注】删除一条批注（commentId 来自 doc_get_comments 返回的 id）。 |
| 65 | `doc_list_revisions` | DocumentEditTools:1554 | revision | LOWA-docx | NONE | 是 | - | - (0) | 120 | 0 | 【看/修订】列出文档当前的全部修订记录：index（0 开始）、类型（Insert/Delete/. |
| 66 | `doc_accept_revision` | DocumentEditTools:1567 | revision | LOWA-docx | NONE | 是 | MODIFIED | index (1) | 45 | 32 | 【修订】接受一条修订（index 来自 doc_list_revisions，0 开始）。 |
| 67 | `doc_reject_revision` | DocumentEditTools:1577 | revision | LOWA-docx | NONE | 是 | MODIFIED | index (1) | 45 | 32 | 【修订】拒绝一条修订（index 来自 doc_list_revisions，0 开始）。 |
| 68 | `doc_accept_all_revisions` | DocumentEditTools:1599 | revision | LOWA-docx | NONE | 是 | MODIFIED | - (0) | 45 | 0 | 【修订】一次性接受文档中的全部修订。 |
| 69 | `doc_reject_all_revisions` | DocumentEditTools:1606 | revision | LOWA-docx | NONE | 是 | MODIFIED | - (0) | 18 | 0 | 【修订】一次性拒绝文档中的全部修订。 |
| 70 | `doc_debug_revisions` | DocumentEditTools:2857 | revision | LOWA-docx | NONE | 否 | - | - (0) | 56 | 0 | 调试工具：获取文档中所有修订记录的详细信息，包括修订类型、位置、内容等。 |
| 71 | `doc_link_evidence` | DocumentEditTools:1749 | evidence | LOWA-docx | NONE | 是 | MODIFIED | docFileId, anchorQuote, anchorId, targetsJson, method, relation, note (7) | 568 | 323 | 【证据】把报告里的一句事实陈述与它的底稿文件关联起来（EvidenceLink）：在该文字上套书签 + 超链接，并登记底稿位置。 |
| 72 | `doc_list_evidence` | DocumentEditTools:1823 | evidence | LOWA-docx | NONE | 是 | - | docFileId, fileId, sectionPath, status, limit (5) | 235 | 197 | 【证据】列出报告里已建立的底稿关联（EvidenceLink）：每条含 linkKey、锚定文字、所在章节 sectionPath、状态（active 已核对 / unveri… |
| 73 | `retrieve_evidence` | EvidenceTools:54 | evidence | 全部会话 | NONE | 是 | - | query, limit (2) | 107 | 28 | 检索带溯源的证据。 |
| 74 | `evidence_verify` | EvidenceTools:140 | evidence | 全部会话 | NONE | 是 | - | docFileId, linkKey, scope (3) | 364 | 124 | 【证据】勾稽核查：把报告里已建的底稿关联（EvidenceLink）逐条与底稿对账，只核四类可机器校验的要素——统一社会信用代码（含校验位）、日期、金额与比例（自动换算万元/亿… |
| 75 | `list_contributed_templates` | ContributedTemplateTools:27 | template | 全部会话 | NONE | 是 | - | - (0) | 101 | 0 | 列出插件贡献的文书模板（id/名称/体裁/说明）。 |
| 76 | `create_file_from_template` | ContributedTemplateTools:50 | template | 全部会话 | NONE | 是 | MODIFIED | pluginId, templateId, parentFolderId, name (4) | 146 | 48 | 从插件贡献的模板创建一份项目文件（同名自动加序号）。 |
| 77 | `doc_apply_style_profile` | DocumentEditTools:1342 | template | LOWA-docx | NONE | 是 | MODIFIED | scope (1) | 327 | 50 | 【格式】按项目的模板画像给当前文档套格式：先改 Standard/Heading 1-6/表格样式定义，再按 scope 做最小直接格式。 |
| 78 | `docx_inspect_template` | TemplateTools:53 | template | 全部会话 | NONE | 是 | - | fileIds, options (2) | 554 | 116 | Learn the formatting profile (styleProfile v1 JSON) of one or more team template . |
| 79 | `doc_goto` | DocumentEditTools:436 | format | LOWA-docx | NONE | 是 | - | type, target (2) | 112 | 51 | 把光标移到文档开头或结尾。 |
| 80 | `doc_set_selection` | DocumentEditTools:453 | format | LOWA-docx | NONE | 是 | - | start, end (2) | 41 | 42 | 设置文档的选区范围（精确控制光标/选区）。 |
| 81 | `doc_replace_nth_match` | DocumentEditTools:541 | format | LOWA-docx | NONE | 是 | MODIFIED | findText, replaceText, matchIndex (3) | 173 | 27 | 将文档中第 N 个可见匹配项替换为新文本。 |
| 82 | `doc_delete_match` | DocumentEditTools:571 | format | LOWA-docx | NONE | 是 | MODIFIED | findText, matchIndex (2) | 39 | 23 | 删除文档中第 N 个可见的匹配文本。 |
| 83 | `doc_modify_paragraph` | DocumentEditTools:686 | format | LOWA-docx | NONE | 是 | MODIFIED | paragraphIndex, newText (2) | 124 | 49 | 修改文档中指定段落的文本内容。 |
| 84 | `doc_search_related_docs` | DocumentEditTools:741 | format | LOWA(任意类型) | NONE | 是 | - | keyword (1) | 35 | 21 | 搜索项目中可能需要修改的相关文档。 |
| 85 | `doc_collapse_cursor` | DocumentEditTools:877 | format | LOWA-docx | NONE | 是 | - | to (1) | 95 | 20 | 【选】把光标落到当前选区的开头或结尾（取消选中）。 |
| 86 | `doc_delete_selection` | DocumentEditTools:912 | format | LOWA-docx | NONE | 是 | MODIFIED | - (0) | 81 | 0 | 【改】删除当前选中的文本（以修订模式）。 |
| 87 | `doc_format_selection` | DocumentEditTools:924 | format | LOWA-docx | NONE | 是 | MODIFIED | bold, italic, underline, strikeout, highlight, color, fontSize, fontName, fontNameAsian (9) | 174 | 244 | 【格式】给当前选中的文本设置字符格式：加粗/斜体/下划线/删除线/高亮/字色/字号/字体。 |
| 88 | `doc_set_paragraph_format` | DocumentEditTools:957 | format | LOWA-docx | NONE | 是 | MODIFIED | alignment, headingLevel, lineSpacingMode, lineSpacingValue, spaceBeforePt, spaceAfterPt, firstLineIndentChars, leftIndentPt, rightIndentPt (9) | 352 | 228 | 【格式】设置当前选区所在段落的段落格式：对齐、标题级别、行距、段前段后间距、缩进。 |
| 89 | `doc_set_numbering` | DocumentEditTools:993 | format | LOWA-docx | NONE | 是 | MODIFIED | preset, level (2) | 324 | 56 | 【格式】给当前选区所在段落设置自动编号或项目符号（先选中段落，可跨多段）。 |
| 90 | `doc_format_table` | DocumentEditTools:1015 | format | LOWA-docx | NONE | 是 | MODIFIED | applyStandard, borderWidthPt, fontSizePt, firstRowBold, cellVerticalAlign, columnWidthsPercent, rowHeightPt, rowHeightRule, tableIndex, borderColor, borderStyle, outsideBorderWidthPt, insideBorderWidthPt, headerFill, repeatHeader, columnWidthsCm (16) | 521 | 337 | 【格式】设置光标所在表格的格式（先把光标点进表格，或传 tableIndex 指定第 N 张表，0 开始）。 |
| 91 | `doc_get_formatting` | DocumentEditTools:1297 | format | LOWA-docx | NONE | 是 | - | - (0) | 110 | 0 | 【看/格式】读取当前光标或选区处的完整格式信息：字体（中西文）、字号、加粗/斜体/下划线/删除线、颜色、高亮、段落样式、对齐、行距、段前段后、缩进、编号状态、所在表格（表名/行… |
| 92 | `doc_insert_toc` | DocumentEditTools:1370 | format | LOWA-docx | NONE | 是 | MODIFIED | levels, title, position (3) | 157 | 73 | 【结构】在光标处（position=start 时在文首）插入由标题级别自动生成的目录（真正的目录域，可更新）。 |
| 93 | `doc_set_page_setup` | DocumentEditTools:1391 | format | LOWA-docx | NONE | 是 | MODIFIED | widthMm, heightMm, orientation, marginTopMm, marginBottomMm, marginLeftMm, marginRightMm (7) | 177 | 105 | 【结构】设置纸张与页边距（单位毫米）：widthMm/heightMm 纸张尺寸（A4 为 210x297）、orientation portrait/landscape、ma… |
| 94 | `doc_redo` | DocumentEditTools:1441 | format | LOWA-docx/xlsx | NONE | 是 | MODIFIED | steps (1) | 15 | 9 | 【验/撤销】重做刚撤销的操作。 |
| 95 | `doc_edit_header_footer` | DocumentEditTools:1624 | format | LOWA-docx | NONE | 是 | MODIFIED | target, text, align, pageNumberPattern, fontName, fontSize (6) | 243 | 158 | 【结构】设置文档首节的页眉或页脚文本（只处理第一个页面样式，法律文件极少按节区分页眉页脚）。 |
| 96 | `doc_insert_break` | DocumentEditTools:1656 | format | LOWA-docx | NONE | 是 | MODIFIED | breakType (1) | 89 | 29 | 【结构】在光标处插入分页符或分节符（Word 语义的"下一页"分节符）。 |
| 97 | `doc_insert_footnote` | DocumentEditTools:1673 | format | LOWA-docx | NONE | 是 | MODIFIED | anchorId, text (2) | 91 | 46 | 【插入】在文中位置插入一条脚注（页面底部编号注释），常用于法律文件的引用/释义说明。 |
| 98 | `doc_insert_endnote` | DocumentEditTools:1684 | format | LOWA-docx | NONE | 是 | MODIFIED | anchorId, text (2) | 75 | 46 | 【插入】在文中位置插入一条尾注（文档末尾编号注释）。 |
| 99 | `doc_set_hyperlink` | DocumentEditTools:1710 | format | LOWA-docx | NONE | 是 | MODIFIED | anchorId, url (2) | 179 | 130 | 【格式】给指定锚点处的文本设置超链接。 |
| 100 | `doc_insert_image` | DocumentEditTools:2015 | format | LOWA-docx | NONE | 是 | MODIFIED | fileId (1) | 106 | 16 | 【插入】在光标处插入一张图片。 |
| 101 | `doc_set_style` | DocumentEditTools:2071 | format | LOWA-docx | NONE | 是 | MODIFIED | styleName, kind (2) | 148 | 79 | 【格式】给当前选区套用文档中已有的段落样式或字符样式（按样式名，不是本工具集里的标准格式化）。 |
| 102 | `sheet_select_range` | DocumentEditTools:2185 | spreadsheet | LOWA-xlsx | NONE | 是 | - | range, sheet (2) | 97 | 50 | 【表格·选】选中电子表格的一个区域（视图滚动到该处并高亮，用户能看到 AI 正在操作哪里）。 |
| 103 | `sheet_format_cells` | DocumentEditTools:2204 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | range, bold, italic, underline, fontSize, fontName, color, background, hAlign, vAlign, wrap, numberFormat, sheet (13) | 216 | 306 | 【表格·格式】设置电子表格区域的格式，只传需要改的参数：加粗/斜体/下划线、字号、字体、字色、底色、水平对齐 hAlign(left/center/right/standard… |
| 104 | `sheet_set_borders` | DocumentEditTools:2246 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | range, preset, widthPt, color, sheet (5) | 106 | 105 | 【表格·格式】给电子表格区域设置边框。 |
| 105 | `sheet_set_row_col` | DocumentEditTools:2271 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | range, rowHeightPt, colWidthPt, autoFitRows, autoFitCols, sheet (6) | 121 | 110 | 【表格·格式】设置电子表格的行高列宽，作用于 range 覆盖到的整行/整列。 |
| 106 | `sheet_manage_sheets` | DocumentEditTools:2344 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | op, name, newName, position (4) | 179 | 102 | 【表格·结构】管理工作表：op=add 新建（name+可选 position）、rename 重命名（name+newName）、delete 删除（name，不能删最后一张… |
| 107 | `sheet_edit_rows_cols` | DocumentEditTools:2368 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | op, start, count, sheet (4) | 141 | 113 | 【表格·结构】插入或删除整行/整列。 |
| 108 | `sheet_merge_cells` | DocumentEditTools:2391 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | range, merge, sheet (3) | 55 | 59 | 【表格·结构】合并或取消合并单元格区域。 |
| 109 | `sheet_sort_range` | DocumentEditTools:2411 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | range, byColumn, ascending, hasHeader, sheet (5) | 159 | 102 | 【表格·结构】对区域按某列排序。 |
| 110 | `sheet_set_autofilter` | DocumentEditTools:2437 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | range, enabled, sheet (3) | 62 | 68 | 【表格·结构】给区域加/去自动筛选（表头出现筛选下拉按钮）。 |
| 111 | `sheet_freeze_panes` | DocumentEditTools:2457 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | rows, cols, sheet (3) | 77 | 51 | 【表格·结构】冻结窗格：冻结前 rows 行和前 cols 列（滚动时保持可见，常用 rows=1 冻结表头）；rows=0 且 cols=0 取消冻结。 |
| 112 | `sheet_conditional_format` | DocumentEditTools:2477 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | range, rule, value1, value2, background, color, bold, clear, sheet (9) | 228 | 246 | 【表格·格式】给区域设条件格式：满足条件的单元格自动套指定外观。 |
| 113 | `sheet_add_comment` | DocumentEditTools:2517 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | cell, text, sheet (3) | 93 | 39 | 【表格·批注】给电子表格单元格添加批注。 |
| 114 | `sheet_get_comments` | DocumentEditTools:2544 | spreadsheet | LOWA-xlsx | NONE | 是 | - | sheet (1) | 74 | 25 | 【表格·批注】列出当前工作表的全部单元格批注（单元格地址/作者/日期/内容）。 |
| 115 | `sheet_delete_comment` | DocumentEditTools:2561 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | cell, sheet (2) | 42 | 35 | 【表格·批注】删除指定单元格上的批注。 |
| 116 | `sheet_set_data_validation` | DocumentEditTools:2582 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | range, type, operator, value1, value2, showInputMessage, inputTitle, inputMessage, errorTitle, errorMessage, clear, sheet (12) | 390 | 322 | 【表格·结构】给区域设置数据验证规则。 |
| 117 | `sheet_add_chart` | DocumentEditTools:2628 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | range, chartType, title, name, sheet (5) | 128 | 90 | 【表格·结构】以区域数据建一个图表。 |
| 118 | `sheet_search` | DocumentEditTools:2656 | spreadsheet | LOWA-xlsx | NONE | 是 | - | query, range, matchCase, sheet (4) | 211 | 64 | 【表格·看】在电子表格区域内查找文本（逐格比对字符串值，含公式计算结果）。 |
| 119 | `sheet_find_replace` | DocumentEditTools:2691 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | find, replace, range, matchCase, wholeCell, maxReplacements, sheet (7) | 459 | 185 | 【表格·写】在电子表格区域内查找并替换文本，只改命中的那些格——区域内其他格一个字都不动。 |
| 120 | `sheet_define_name` | DocumentEditTools:2742 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | op, name, range, sheet (4) | 109 | 102 | 【表格·结构】工作簿级命名区域管理。 |
| 121 | `sheet_protect_sheet` | DocumentEditTools:2765 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | action, password, sheet (3) | 43 | 50 | 【表格·结构】保护或取消保护当前工作表（防止误改）。 |
| 122 | `sheet_group_rows_cols` | DocumentEditTools:2788 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | op, range, orient, sheet (4) | 125 | 112 | 【表格·结构】行列分组/大纲（可折叠展开的行列组）。 |
| 123 | `sheet_add_pivot_table` | DocumentEditTools:2814 | spreadsheet | LOWA-xlsx | NONE | 是 | MODIFIED | sourceRange, rowFields, dataField, outputCell, name, sheet (6) | 215 | 129 | 【表格·结构】基础形态的数据透视表：按 rowFields 分组、对 dataField 求和。 |
| 124 | `pptx_list_files` | PptxTools:113 | slides | 全部会话 | NONE | 是 | - | - (0) | 173 | 0 | PPTX 专用清单：等价于 doc_list_project_files 只保留 . |
| 125 | `pptx_search_files` | PptxTools:148 | slides | 全部会话 | NONE | 是 | - | keyword (1) | 32 | 28 | 搜索项目中的 PPTX 演示文稿文件。 |
| 126 | `pptx_open_file` | PptxTools:202 | slides | LOWA(声明) | LOWA | 否 | - | fileId (1) | 56 | 47 | [已由 doc_open_file 取代] 打开指定的 PPTX 文件进行编辑。 |
| 127 | `pptx_check_service` | PptxTools:276 | slides | 全部会话 | NONE | 是 | - | - (0) | 41 | 0 | 检查 PPTX 生成服务是否可用。 |
| 128 | `pptx_generate` | PptxTools:296 | slides | LOWA(声明) | LOWA | 是 | ADDED | topic, parentId, fileName, style, language (5) | 289 | 220 | 根据主题一键生成 PPTX 演示文稿。 |
| 129 | `pptx_generate_outline` | PptxTools:560 | slides | 全部会话 | NONE | 是 | - | topic, language (2) | 51 | 39 | 生成 PPTX 大纲（不生成完整 PPT）。 |
| 130 | `pptx_inspect_format` | PptxTools:634 | slides | 全部会话 | NONE | 是 | - | fileId, slideIndex (2) | 230 | 92 | 读取 PPTX 文件的结构化内容与格式全览（直接读文件，无需在编辑器中打开）。 |
| 131 | `pptx_apply_format` | PptxTools:690 | slides | LOWA(声明) | LOWA | 否 | MODIFIED | fileId, opsJson (2) | 1104 | 81 | [已由 slide_* 取代，改幻灯片请用 slide_set_shape_text / slide_replace_text / slide_format_text] 对 P… |
| 132 | `pptx_edit_page` | PptxTools:783 | slides | 全部会话 | NONE | 否 | - | serviceProjectId, pageId, editInstruction (3) | 74 | 118 | [已由 slide_* 取代] 使用自然语言编辑 PPT 页面图片（改的是 pptx-service 中的生成产物，不是项目文件树里的 PPTX）。 |
| 133 | `pptx_get_project_pages` | PptxTools:816 | slides | 全部会话 | NONE | 是 | - | serviceProjectId (1) | 38 | 14 | 获取项目中的所有页面信息。 |
| 134 | `pptx_refine_outline` | PptxTools:859 | slides | 全部会话 | NONE | 是 | - | serviceProjectId, userRequirement, language (3) | 37 | 81 | 使用自然语言修改 PPT 大纲结构。 |
| 135 | `pptx_export_editable` | PptxTools:899 | slides | 全部会话 | NONE | 是 | - | serviceProjectId, filename, modelId (3) | 270 | 55 | 导出可编辑的 PPTX 文件。 |
| 136 | `slide_get_page` | SlideEditTools:67 | slides | LOWA-pptx | NONE | 是 | - | slideNumber (1) | 195 | 7 | 【幻灯片·看】读取指定页的明细：页面尺寸（磅）、版式、母版、备注文字，以及该页每个形状的名称、类型、位置尺寸（磅）、文字内容；表格形状另带行列数。 |
| 137 | `slide_read_notes` | SlideEditTools:88 | slides | LOWA-pptx | NONE | 是 | - | slideNumber (1) | 57 | 18 | 【幻灯片·看】读取备注页（Speaker Notes）文字。 |
| 138 | `slide_write_notes` | SlideEditTools:104 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, text (2) | 122 | 22 | 【幻灯片·写】整体覆盖指定页的备注页（Speaker Notes）文字（不是追加）。 |
| 139 | `slide_goto` | SlideEditTools:124 | slides | LOWA-pptx | NONE | 是 | - | slideNumber, shapeName (2) | 78 | 52 | 【幻灯片·定位】把编辑器视图切到指定页（用户能看到 AI 正在操作哪一页），可选再选中该页某个形状。 |
| 140 | `slide_replace_text` | SlideEditTools:173 | slides | LOWA-pptx | NONE | 是 | MODIFIED | searchText, replaceText, slideNumber, all (4) | 185 | 56 | 【幻灯片·写】查找并替换文字，覆盖普通文本框、标题、占位符与表格单元格。 |
| 141 | `slide_delete_page` | SlideEditTools:233 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber (1) | 118 | 11 | 【幻灯片·写】删除指定页。 |
| 142 | `slide_move_page` | SlideEditTools:252 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, toPosition (2) | 228 | 25 | 【幻灯片·写】把指定页移动到新位置（重排顺序）。 |
| 143 | `slide_set_layout` | SlideEditTools:276 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, layout, masterName (3) | 276 | 36 | 【幻灯片·写】设置指定页的版式（layout，AutoLayout 常量）和/或母版（masterName，按名字匹配演示文稿现有母版）。 |
| 144 | `slide_add_text_box` | SlideEditTools:305 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, text, left, top, width, height, fontSize, bold, color (9) | 170 | 108 | 【幻灯片·写】在指定页插入一个新文本框。 |
| 145 | `slide_add_shape` | SlideEditTools:341 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, shapeType, left, top, width, height, text, fillColor (8) | 222 | 138 | 【幻灯片·写】在指定页插入一个几何形状：rectangle（矩形）/ ellipse（椭圆）/ triangle（三角形）/ line（直线）。 |
| 146 | `slide_delete_shape` | SlideEditTools:379 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, shapeName (2) | 145 | 46 | 【幻灯片·写】按 shapeName 精确删除指定页的一个形状（来自 slide_get_page 返回的 shapeName）。 |
| 147 | `slide_set_shape_geometry` | SlideEditTools:404 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, shapeName, left, top, width, height (6) | 182 | 76 | 【幻灯片·写】移动和/或改变指定形状的位置尺寸（磅）。 |
| 148 | `slide_format_text` | SlideEditTools:440 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, shapeName, anchorText, fontName, fontSize, bold, italic, underline, strikethrough, color, alignment (11) | 184 | 217 | 【幻灯片·写】设置指定形状文字的字体/字号/粗斜体/下划线/删除线/颜色/段落对齐。 |
| 149 | `slide_format_shape` | SlideEditTools:483 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, shapeName, fillColor, noFill, lineColor, noLine, lineWidthPt, fillTransparency (8) | 142 | 176 | 【幻灯片·写】设置指定形状的填充色/边框颜色与粗细/透明度。 |
| 150 | `slide_add_table` | SlideEditTools:520 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, rows, cols, rowsJson, left, top, width, height (8) | 259 | 196 | 【幻灯片·写】在指定页插入一张表格。 |
| 151 | `slide_table_read` | SlideEditTools:566 | slides | LOWA-pptx | NONE | 是 | - | slideNumber, shapeName, maxRows, maxCols (4) | 111 | 54 | 【幻灯片·看】把指定页一张表格形状读成二维数组（行列数 + 每格文本）。 |
| 152 | `slide_table_set_cell` | SlideEditTools:592 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, shapeName, row, col, text (5) | 169 | 47 | 【幻灯片·写】改指定页一张表格形状的一格文本。 |
| 153 | `slide_table_set_style` | SlideEditTools:623 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, shapeName, headerBold, borderWidthPt, borderColor, columnWidthsJson (6) | 230 | 155 | 【幻灯片·写】给指定页一张表格形状设置整体样式：表头（第一行）加粗、边框颜色与粗细、各列宽度。 |
| 154 | `slide_set_hyperlink` | SlideEditTools:666 | slides | LOWA-pptx | NONE | 是 | MODIFIED | slideNumber, searchText, url (3) | 261 | 34 | 【幻灯片·写】在指定页查找文字并给它加超链接（跳转到网页，不支持表格单元格内文字）。 |
| 155 | `office_get_selection` | OfficeEditTools:260 | office | Office-Word | NONE | 是 | - | - (0) | 32 | 0 | 读取用户当前在 Word 中选中的文本内容。 |
| 156 | `office_pass_step` | OfficeEditTools:426 | office | Office-Word | NONE | 是 | MODIFIED | editsJson, stop (2) | 591 | 119 | 整篇分段过卷：把「整篇/全文/所有」的逐处修改任务按块推进，一次处理一块。 |
| 157 | `office_add_comment` | OfficeEditTools:649 | office | Office-Word | NONE | 是 | MODIFIED | anchorText, comment (2) | 68 | 22 | 在当前 Word 文档中为指定文本添加批注（Word 原生批注）。 |
| 158 | `office_format_text` | OfficeEditTools:674 | office | Office-Word | NONE | 是 | MODIFIED | anchorText, applyToAll, fontName, fontSize, bold, italic, underline, strikeThrough, doubleStrikeThrough, color (10) | 213 | 267 | 在当前 Word 文档中设置指定文本的字符格式：字体、字号、加粗、斜体、下划线、删除线、颜色。 |
| 159 | `office_set_paragraph_format` | OfficeEditTools:730 | office | Office-Word | NONE | 是 | MODIFIED | anchorText, applyToAll, alignment, lineSpacing, spaceBefore, spaceAfter, firstLineIndent, leftIndent, rightIndent, styleBuiltIn (10) | 371 | 265 | 在当前 Word 文档中设置段落格式：对齐、行距、段前段后间距、缩进、标题级别。 |
| 160 | `office_get_formatting` | OfficeEditTools:791 | office | Office-Word | NONE | 是 | - | anchorText (1) | 109 | 21 | 读取当前 Word 文档中某处的现有格式（字符格式 + 所在段落的段落格式）。 |
| 161 | `office_set_numbering` | OfficeEditTools:808 | office | Office-Word | NONE | 是 | MODIFIED | anchorText, paragraphCount, kind (3) | 343 | 77 | 在当前 Word 文档中为一段连续段落设置自动编号或项目符号。 |
| 162 | `office_format_table` | OfficeEditTools:849 | office | Office-Word | NONE | 是 | MODIFIED | tableIndex, borders, borderColor, borderWidth, alignment, headerBold, autoFit, fontSize (8) | 349 | 205 | 在当前 Word 文档中设置某张表格的整体格式：边框、表格对齐、首行加粗、自动调整列宽、全表字号。 |
| 163 | `office_apply_standard_format` | OfficeEditTools:921 | office | Office-Word | NONE | 是 | MODIFIED | scope (1) | 244 | 37 | 把当前 Word 文档整篇（或选区）套用律所标准格式：正文楷体_GB2312/Arial 12 磅两端对齐、段后 18 磅、行距 16 磅、首行缩进 2 字符；主标题 16 磅… |
| 164 | `office_insert_table` | OfficeEditTools:946 | office | Office-Word | NONE | 是 | MODIFIED | rowsJson, headerBold, anchorText, position (4) | 216 | 107 | 在当前 Word 文档中插入一张表格。 |
| 165 | `office_table_read` | OfficeEditTools:994 | office | Office-Word | NONE | 是 | - | tableIndex (1) | 170 | 15 | 把当前 Word 文档里的一张表读成二维数组（行列数 + 每格文本），改表格前必须先用它看清现状。 |
| 166 | `office_table_set_cell` | OfficeEditTools:1013 | office | Office-Word | NONE | 是 | MODIFIED | tableIndex, cell, text (3) | 186 | 51 | 改当前 Word 文档里一张表格中一个单元格的文本（整格替换）。 |
| 167 | `office_table_add_row` | OfficeEditTools:1038 | office | Office-Word | NONE | 是 | MODIFIED | tableIndex, rowIndex, count (3) | 213 | 107 | 给当前 Word 文档里的一张表格插入空白行。 |
| 168 | `office_table_delete_row` | OfficeEditTools:1054 | office | Office-Word | NONE | 是 | MODIFIED | tableIndex, rowIndex, count (3) | 254 | 83 | 删除当前 Word 文档里一张表格的整行。 |
| 169 | `office_table_add_col` | OfficeEditTools:1073 | office | Office-Word | NONE | 是 | MODIFIED | tableIndex, colIndex, count (3) | 287 | 59 | 给当前 Word 文档里的一张表格插入空白列。 |
| 170 | `office_table_delete_col` | OfficeEditTools:1089 | office | Office-Word | NONE | 是 | MODIFIED | tableIndex, colIndex, count (3) | 197 | 38 | 删除当前 Word 文档里一张表格的整列。 |
| 171 | `office_insert_break` | OfficeEditTools:1137 | office | Office-Word | NONE | 是 | MODIFIED | breakType, anchorText, position (3) | 150 | 104 | 在当前 Word 文档中插入分页符或分节符。 |
| 172 | `office_set_hyperlink` | OfficeEditTools:1168 | office | Office-Word | NONE | 是 | MODIFIED | anchorText, url (2) | 149 | 50 | 在当前 Word 文档中为指定文本设置超链接。 |
| 173 | `office_edit_header_footer` | OfficeEditTools:1193 | office | Office-Word | NONE | 是 | MODIFIED | part, text, alignment (3) | 183 | 82 | 编辑当前 Word 文档首节的页眉或页脚文字（覆盖原有内容）。 |
| 174 | `office_get_comments` | OfficeEditTools:1225 | office | Office-Word | NONE | 是 | - | - (0) | 95 | 0 | 读取当前 Word 文档中的全部批注：作者、创建时间、内容、锚点文本摘要、是否已解决、序号（index）。 |
| 175 | `office_reply_comment` | OfficeEditTools:1235 | office | Office-Word | NONE | 是 | MODIFIED | commentId, commentIndex, reply (3) | 117 | 123 | 回复当前 Word 文档中的一条批注。 |
| 176 | `office_resolve_comment` | OfficeEditTools:1258 | office | Office-Word | NONE | 是 | MODIFIED | commentId, commentIndex, resolved (3) | 140 | 147 | 标记当前 Word 文档中的一条批注为已解决（或重新打开）。 |
| 177 | `office_delete_comment` | OfficeEditTools:1279 | office | Office-Word | NONE | 是 | MODIFIED | commentId, commentIndex (2) | 208 | 119 | 删除当前 Word 文档中的一条批注（连同它的全部回复）。 |
| 178 | `office_insert_toc` | OfficeEditTools:1304 | office | Office-Word | NONE | 是 | MODIFIED | levels, title, position (3) | 331 | 87 | 在当前 Word 文档中插入目录——插入的是真正的目录域（TOC field），用户之后在 Word 里「更新域」就能刷新条目与页码，不是一段死文字。 |
| 179 | `office_set_page_setup` | OfficeEditTools:1333 | office | Office-Word | NONE | 是 | MODIFIED | marginTopPt, marginBottomPt, marginLeftPt, marginRightPt, orientation, paperSize (6) | 284 | 115 | 设置当前 Word 文档的页面：纸张、方向、四边页边距。 |
| 180 | `office_excel_get_range` | OfficeEditTools:1380 | office | Office-Excel | NONE | 是 | - | sheetName, rangeAddress, withFormat (3) | 316 | 74 | 读取当前 Excel 工作表的区域值。 |
| 181 | `office_excel_set_values` | OfficeEditTools:1405 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, rangeAddress, valuesJson, inheritFormat (4) | 376 | 121 | 向当前 Excel 工作表的区域写入值（直接生效，Excel 没有修订机制）。 |
| 182 | `office_excel_search` | OfficeEditTools:1461 | office | Office-Excel | NONE | 是 | - | sheetName, query (2) | 171 | 25 | 在当前 Excel 工作表的已用区域中查找文本（大小写不敏感的包含匹配），返回命中单元格地址与内容。 |
| 183 | `office_excel_replace` | OfficeEditTools:1484 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, rangeAddress, find, replace, matchCase, wholeCell, maxReplacements (7) | 454 | 199 | 在 Excel 区域内查找并替换文本，只改命中的那些格——区域内其他格一个字都不动。 |
| 184 | `office_excel_format_cells` | OfficeEditTools:1536 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, rangeAddress, fontName, fontSize, bold, italic, fontColor, fillColor, horizontalAlignment, verticalAlignment, numberFormat, wrapText (12) | 118 | 263 | 设置 Excel 区域的单元格格式：字体、字号、加粗、斜体、字体颜色、填充色、水平对齐、垂直对齐、数字格式、自动换行。 |
| 185 | `office_excel_set_borders` | OfficeEditTools:1596 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, rangeAddress, borders, style, color (5) | 108 | 155 | 设置 Excel 区域的边框：范围（all/outside/inside/none）、线宽（thin/medium/thick）、颜色。 |
| 186 | `office_excel_edit_rows_cols` | OfficeEditTools:1637 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, action, index, count, size (5) | 238 | 159 | 在当前 Excel 工作表插入/删除整行整列，或设置行高/列宽。 |
| 187 | `office_excel_merge_cells` | OfficeEditTools:1681 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, rangeAddress, action (3) | 61 | 53 | 合并或取消合并 Excel 区域的单元格。 |
| 188 | `office_excel_sort_range` | OfficeEditTools:1710 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, rangeAddress, keyColumn, ascending, hasHeader (5) | 91 | 102 | 对 Excel 区域按某一列排序。 |
| 189 | `office_excel_manage_sheets` | OfficeEditTools:1738 | office | Office-Excel | NONE | 是 | MODIFIED | action, sheetName, newName, position (4) | 222 | 91 | 管理 Excel 工作簿的工作表：新增、重命名、删除、移动位置、设为当前活动表。 |
| 190 | `office_excel_freeze_panes` | OfficeEditTools:1778 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, action, count, cellAddress (4) | 198 | 131 | 冻结或取消冻结 Excel 工作表的窗格。 |
| 191 | `office_excel_set_formulas` | OfficeEditTools:1818 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, rangeAddress, formulasJson (3) | 338 | 85 | 向 Excel 区域批量写入公式（直接生效，Excel 没有修订机制）。 |
| 192 | `office_excel_get_overview` | OfficeEditTools:1870 | office | Office-Excel | NONE | 是 | - | - (0) | 108 | 0 | 读取当前 Excel 工作簿总览：所有工作表清单（名称、是否为当前活动表）+ 各表已用区域尺寸（行数/列数/地址，空表为 null）。 |
| 193 | `office_excel_select_range` | OfficeEditTools:1880 | office | Office-Excel | NONE | 是 | - | sheetName, rangeAddress (2) | 94 | 43 | 把用户在 Excel 中的视图定位到指定区域并选中（不修改数据，只是把焦点带过去，常用于向用户展示「你看这里」）。 |
| 194 | `office_excel_set_autofilter` | OfficeEditTools:1899 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, rangeAddress, action (3) | 213 | 71 | 设置 Excel 工作表的自动筛选：apply 套上筛选（在指定区域顶行加下拉箭头，不预设筛选条件）、clear 清除已生效的筛选条件（保留下拉箭头）、remove 彻底移除自… |
| 195 | `office_excel_conditional_format` | OfficeEditTools:1932 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, rangeAddress, ruleType, operator, value1, value2, fillColor, action (8) | 432 | 298 | 给 Excel 区域套用或清除条件格式。 |
| 196 | `office_ppt_get_slides` | OfficeEditTools:2008 | office | Office-PPT | NONE | 是 | - | - (0) | 91 | 0 | 读取当前 PowerPoint 演示文稿各页的文本清单（每页各形状的文字）。 |
| 197 | `office_ppt_replace_text` | OfficeEditTools:2018 | office | Office-PPT | NONE | 是 | MODIFIED | searchText, replaceText (2) | 125 | 26 | 在当前 PowerPoint 演示文稿中跨页查找并替换文本（直接生效，PowerPoint 没有修订机制）。 |
| 198 | `office_ppt_format_text` | OfficeEditTools:2043 | office | Office-PPT | NONE | 是 | MODIFIED | searchText, applyToAll, fontName, fontSize, bold, italic, underline, color (8) | 194 | 174 | 在当前 PowerPoint 演示文稿中查找文本并设置其字符格式：字体、字号、加粗、斜体、下划线、颜色。 |
| 199 | `office_ppt_add_slide` | OfficeEditTools:2093 | office | Office-PPT | NONE | 是 | MODIFIED | position, title, body (3) | 366 | 59 | 在当前 PowerPoint 演示文稿中新增一页幻灯片，可选写入标题与正文文本框。 |
| 200 | `office_ppt_delete_slide` | OfficeEditTools:2117 | office | Office-PPT | NONE | 是 | MODIFIED | slideNumber (1) | 159 | 14 | 删除当前 PowerPoint 演示文稿中的指定幻灯片。 |
| 201 | `office_ppt_add_text_box` | OfficeEditTools:2134 | office | Office-PPT | NONE | 是 | MODIFIED | slideNumber, text, left, top, width, height, fontSize, bold, color (9) | 224 | 140 | 在当前 PowerPoint 演示文稿的指定幻灯片上插入一个文本框。 |
| 202 | `office_ppt_move_slide` | OfficeEditTools:2183 | office | Office-PPT | NONE | 是 | MODIFIED | slideNumber, toPosition (2) | 146 | 27 | 把当前 PowerPoint 演示文稿中的一页幻灯片移动到新位置。 |
| 203 | `office_ppt_add_shape` | OfficeEditTools:2205 | office | Office-PPT | NONE | 是 | MODIFIED | slideNumber, shapeType, left, top, width, height, fillColor (7) | 231 | 138 | 在当前 PowerPoint 演示文稿的指定幻灯片上插入一个几何形状：矩形/椭圆/三角形。 |
| 204 | `office_ppt_get_slide_details` | OfficeEditTools:2253 | office | Office-PPT | NONE | 是 | - | slideNumber (1) | 167 | 12 | 读取当前 PowerPoint 演示文稿中指定一页幻灯片的形状明细：每个形状的 id、类型、位置尺寸（磅）、文字内容（若有）。 |
| 205 | `office_ppt_delete_shape` | OfficeEditTools:2270 | office | Office-PPT | NONE | 是 | MODIFIED | slideNumber, shapeId, textMatch (3) | 197 | 82 | 删除当前 PowerPoint 演示文稿中指定幻灯片上的一个形状。 |
| 206 | `office_excel_add_comment` | OfficeEditTools:2299 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, cellAddress, comment (3) | 125 | 44 | 在 Excel 单元格上添加批注（线程式评论，非旧版批注/Note）。 |
| 207 | `office_excel_get_comments` | OfficeEditTools:2323 | office | Office-Excel | NONE | 是 | - | sheetName, scope (2) | 125 | 72 | 读取当前 Excel 工作表（或整个工作簿）的全部批注线程：单元格地址、楼主内容、作者、是否已解决、回复列表。 |
| 208 | `office_excel_reply_comment` | OfficeEditTools:2344 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, cellAddress, reply (3) | 96 | 44 | 回复 Excel 中某单元格的批注线程。 |
| 209 | `office_excel_resolve_comment` | OfficeEditTools:2368 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, cellAddress, resolved (3) | 85 | 68 | 标记 Excel 中某单元格的批注线程为已解决（或重新打开）。 |
| 210 | `office_excel_delete_comment` | OfficeEditTools:2389 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, cellAddress (2) | 79 | 40 | 删除 Excel 中某单元格的整条批注线程（含全部回复）。 |
| 211 | `office_excel_set_data_validation` | OfficeEditTools:2410 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, rangeAddress, action, type, operator, value1, value2, listSource (8) | 313 | 299 | 给 Excel 区域设置或清除数据验证规则。 |
| 212 | `office_excel_add_chart` | OfficeEditTools:2478 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, dataRangeAddress, chartType, title (4) | 146 | 89 | 在 Excel 工作表插入图表：柱状图/折线图/饼图/条形图。 |
| 213 | `office_excel_define_name` | OfficeEditTools:2513 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, action, name, rangeAddress (4) | 167 | 90 | 新增或删除 Excel 工作簿级命名区域（Named Range）。 |
| 214 | `office_excel_protect_sheet` | OfficeEditTools:2553 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, action, password (3) | 142 | 50 | 保护或解除保护 Excel 工作表（阻止/允许用户编辑单元格）。 |
| 215 | `office_excel_group_rows_cols` | OfficeEditTools:2582 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, rangeAddress, action, by (4) | 181 | 91 | 对 Excel 行或列区域分组/取消分组，形成可折叠大纲。 |
| 216 | `office_excel_add_pivot_table` | OfficeEditTools:2627 | office | Office-Excel | NONE | 是 | MODIFIED | sheetName, sourceRangeAddress, destinationCellAddress, rowFieldsJson, valueFieldsJson, pivotName (6) | 318 | 137 | 在 Excel 中创建一张基础透视表：源区域数据按行字段分组，值字段求和汇总。 |
| 217 | `office_get_revisions` | OfficeEditTools:2684 | office | Office-Word | NONE | 是 | - | - (0) | 111 | 0 | 读取当前 Word 文档中的全部修订（Track Changes）记录：序号（index）、作者、时间、类型（插入/删除/格式）、文本摘要。 |
| 218 | `office_accept_revision` | OfficeEditTools:2694 | office | Office-Word | NONE | 是 | MODIFIED | revisionIndex, acceptAll (2) | 141 | 89 | 接受当前 Word 文档中的修订。 |
| 219 | `office_reject_revision` | OfficeEditTools:2713 | office | Office-Word | NONE | 是 | MODIFIED | revisionIndex, rejectAll (2) | 141 | 89 | 拒绝当前 Word 文档中的修订。 |
| 220 | `office_insert_footnote` | OfficeEditTools:2734 | office | Office-Word | NONE | 是 | MODIFIED | anchorText, text (2) | 119 | 27 | 在当前 Word 文档中为指定文本插入脚注。 |
| 221 | `office_insert_endnote` | OfficeEditTools:2758 | office | Office-Word | NONE | 是 | MODIFIED | anchorText, text (2) | 103 | 27 | 在当前 Word 文档中为指定文本插入尾注。 |
| 222 | `office_insert_image` | OfficeEditTools:2784 | office | Office-Word | NONE | 是 | MODIFIED | fileId, anchorText, position, width (4) | 219 | 105 | 把项目里的一张图片文件插入到当前 Word 文档中（内联图片）。 |
| 223 | `office_apply_style` | OfficeEditTools:2848 | office | Office-Word | NONE | 是 | MODIFIED | anchorText, applyToAll, styleName (3) | 217 | 78 | 给当前 Word 文档中的段落套用一个已命名的样式（内置或自定义，如「标题 1」「正文」「引用」等，样式名须与文档中实际存在的样式名一致，中文文档通常是中文样式名）。 |
| 224 | `office_manage_content_control` | OfficeEditTools:2878 | office | Office-Word | NONE | 是 | MODIFIED | action, anchorText, tag, title, text, keepContent (6) | 360 | 178 | 管理当前 Word 文档中的内容控件（富文本类型，用于绑定/标记文档中的特定区域，如模板填空场景）。 |
| 225 | `office_set_document_properties` | OfficeEditTools:2938 | office | Office-Word | NONE | 是 | MODIFIED | title, subject, author, keywords, comments, category (6) | 105 | 55 | 设置当前 Word 文档的内置属性：标题、主题、作者、关键词、备注、分类。 |
| 226 | `office_ppt_add_table` | OfficeEditTools:2966 | office | Office-PPT | NONE | 是 | MODIFIED | slideNumber, rowsJson, rows, cols, left, top, width, height (8) | 240 | 150 | 在当前 PowerPoint 演示文稿的指定幻灯片上插入一张表格。 |
| 227 | `office_ppt_table_read` | OfficeEditTools:3024 | office | Office-PPT | NONE | 是 | - | slideNumber, shapeId (2) | 141 | 37 | 把当前 PowerPoint 演示文稿指定幻灯片上一张表格读成二维数组。 |
| 228 | `office_ppt_table_set_cell` | OfficeEditTools:3042 | office | Office-PPT | NONE | 是 | MODIFIED | slideNumber, shapeId, row, col, text (5) | 159 | 61 | 改当前 PowerPoint 演示文稿指定幻灯片上一张表格中一个单元格的文本（整格替换）。 |
| 229 | `office_ppt_set_hyperlink` | OfficeEditTools:3069 | office | Office-PPT | NONE | 是 | MODIFIED | slideNumber, searchText, url (3) | 149 | 63 | 在当前 PowerPoint 演示文稿中查找文本并把它设置为超链接。 |
| 230 | `pdf_list_files` | PdfTools:61 | pdf | 全部会话 | NONE | 是 | - | - (0) | 200 | 0 | PDF 专用清单：等价于 doc_list_project_files 只保留 . |
| 231 | `pdf_inspect` | PdfTools:96 | pdf | 全部会话 | NONE | 是 | - | fileId, pageIndex, offset (3) | 394 | 109 | 读取 PDF 文件的逐页文本与基本信息（页数、每页字符数、是否有文本层、当前旋转角度）。 |
| 232 | `pdf_highlight` | PdfTools:127 | pdf | LOWA(声明) | LOWA | 是 | MODIFIED | fileId, text, pageIndex, color, note (5) | 113 | 106 | 在 PDF 中高亮指定文本（所有匹配处）。 |
| 233 | `pdf_annotate` | PdfTools:151 | pdf | LOWA(声明) | LOWA | 是 | MODIFIED | fileId, anchorText, comment, pageIndex (4) | 94 | 61 | 在 PDF 的指定文本旁添加便签批注（锚定第一处匹配）。 |
| 234 | `pdf_redact` | PdfTools:178 | pdf | LOWA(声明) | LOWA | 是 | MODIFIED | fileId, textsJson, pageIndex (3) | 163 | 90 | 对 PDF 做真脱敏：黑框覆盖指定文本，并把涉及的页面转为图片页、彻底移除该页文字层（黑框下的内容无法再复制或提取——单纯画黑框是伪脱敏）。 |
| 235 | `pdf_replace_text` | PdfTools:217 | pdf | LOWA(声明) | LOWA | 是 | MODIFIED | fileId, find, replace, pageIndex (4) | 177 | 55 | PDF 短文本原位替换（白底覆盖+按原字号覆写）。 |
| 236 | `pdf_merge` | PdfTools:275 | pdf | 全部会话 | NONE | 是 | ADDED | fileIdsJson, outputName (2) | 245 | 65 | 把多份 PDF 按给定顺序合并成一册（证据合卷）。 |
| 237 | `pdf_split` | PdfTools:315 | pdf | 全部会话 | NONE | 是 | ADDED | fileId, ranges (2) | 316 | 42 | 按页码范围把一份 PDF 拆成多份：每个逗号分段产出一份新文件。 |
| 238 | `pdf_extract_pages` | PdfTools:358 | pdf | 全部会话 | NONE | 是 | ADDED | fileId, ranges, outputName (3) | 299 | 68 | 从一份 PDF 里提取指定页，产出一份新 PDF（从大卷宗里只要第 10-20 页时用它）。 |
| 239 | `pdf_delete_pages` | PdfTools:387 | pdf | 全部会话 | NONE | 是 | ADDED | fileId, ranges, outputName (3) | 257 | 61 | 删掉一份 PDF 里的指定页，产出一份新 PDF（去掉多余的封面/空白页/重复件）。 |
| 240 | `pdf_rotate_pages` | PdfTools:415 | pdf | 全部会话 | NONE | 是 | ADDED | fileId, ranges, degrees (3) | 316 | 69 | 把 PDF 的指定页旋转（扫描歪了的证据页转正），产出一份新 PDF。 |
| 241 | `pdf_add_page_numbers` | PdfTools:446 | pdf | 全部会话 | NONE | 是 | ADDED | fileId, position, startAt, format (4) | 392 | 122 | 给整册 PDF 逐页写入页码或贝茨编号（Bates number），产出一份新 PDF。 |
| 242 | `pdf_to_word` | PdfTools:486 | pdf | LOWA(声明) | LOWA | 是 | ADDED | fileId, parentId, allowStructuralFallback (3) | 427 | 96 | 把 PDF 转换为可编辑的 Word 文档，自动选择最佳路径：1) 文本型 PDF 走版式级转换（pdf2docx：段落/表格/图片/分栏尽量保留原排版）。 |
| 243 | `litigation_timeline_start` | LitigationTimelineTools:122 | litigation | 全部会话 | NONE | 是 | - | materialFileIds (1) | 700 | 54 | Start the timeline-master pipeline: turn raw case materials (judgment, complaint, defenc… |
| 244 | `litigation_timeline_step` | LitigationTimelineTools:216 | litigation | 全部会话 | NONE | 是 | - | stage, answer, modelFilesJson, emphasisSource (4) | 743 | 267 | Advance the timeline-master pipeline by ONE stage. |
| 245 | `litigation_timeline_render` | LitigationTimelineTools:281 | litigation | LOWA(声明) | LOWA | 是 | ADDED | diagramName, parentFolderId, modelFilesJson (3) | 493 | 198 | Render the timeline and save it into the project. |
| 246 | `litigation_reference` | LitigationVisualTools:146 | litigation | 全部会话 | NONE | 是 | - | name (1) | 574 | 33 | Read one reference document of the litigation-diagram standard. |
| 247 | `litigation_checkpoint` | LitigationVisualTools:170 | litigation | 全部会话 | NONE | 是 | - | semanticMapJson, suggest (2) | 790 | 91 | Generate the three pre-render confirmation questions (structure / style / emphasis) for … |
| 248 | `litigation_render` | LitigationVisualTools:267 | litigation | LOWA(声明) | LOWA | 是 | ADDED | semanticMapJson, diagramName, parentFolderId, mode, formats (5) | 998 | 341 | Draw a litigation diagram (timeline / flowchart / party-relationship) from a semantic ma… |
| 249 | `ref_list` | ReferenceTools:35 | reference | Office(全宿主) | NONE | 是 | - | query, source (2) | 209 | 63 | 列出可作为参考材料的文件：其他打开着的 Office/WPS 文档（open）、当前项目里的文件（cloud）、其他设备上桌面端项目里的文件（desk，那台设备在线才有）、官方… |
| 250 | `ref_read` | ReferenceTools:51 | reference | Office(全宿主) | NONE | 是 | - | ref, locator (2) | 125 | 82 | 读取参考文件的文字。 |
| 251 | `ref_edit` | ReferenceTools:64 | reference | Office(全宿主) | NONE | 是 | - | ref, command, argsJson (3) | 192 | 141 | 修改另一个打开着的文档（只允许 open: 开头的 ref）。 |
| 252 | `ref_open` | ReferenceTools:86 | reference | Office(全宿主) | NONE | 是 | - | ref (1) | 151 | 41 | 请桌面端用系统默认程序打开项目里的文件（只允许 ref_list 标了 openable 的 desk: ref），用于用户要求修改一个尚未打开的文件时。 |
| 253 | `memory_list` | MemoryTools:489 | memory | 全部会话 | NONE | 是 | - | scope (1) | 77 | 28 | 列出 Markdown 长期记忆空间中的文件。 |
| 254 | `memory_read` | MemoryTools:507 | memory | 全部会话 | NONE | 是 | - | scope, path (2) | 76 | 75 | 读取一个 Markdown 长期记忆文件。 |
| 255 | `memory_search` | MemoryTools:520 | memory | 全部会话 | NONE | 是 | - | scope, query (2) | 66 | 31 | 在一个 Markdown 长期记忆空间内搜索标题、路径和正文。 |
| 256 | `memory_write` | MemoryTools:539 | memory | 全部会话 | NONE | 是 | - | scope, path, content, expectedRevision (4) | 82 | 97 | 创建或整篇更新 Markdown 长期记忆。 |
| 257 | `memory_edit` | MemoryTools:555 | memory | 全部会话 | NONE | 是 | - | scope, path, oldText, newText, expectedRevision (5) | 64 | 88 | 对 Markdown 长期记忆做一次精确文本替换。 |
| 258 | `memory_delete` | MemoryTools:584 | memory | 全部会话 | NONE | 是 | - | scope, path, expectedRevision (3) | 56 | 68 | 删除一个 Markdown 长期记忆主题文件。 |
| 259 | `qichacha_query` | EnterpriseDataTools:86 | enterprise-data | 全部会话 | NONE | 是 | - | companyName (1) | 203 | 0 | Look up a Chinese company's business registration record (legal name, registered capital… |
| 260 | `qichacha_ipr` | EnterpriseDataTools:121 | enterprise-data | 全部会话 | NONE | 是 | - | companyName, kind (2) | 322 | 0 | Look up a Chinese company's intellectual-property records by company name or unified soc… |
| 261 | `tushare_query` | EnterpriseDataTools:149 | enterprise-data | 全部会话 | NONE | 是 | - | apiName, paramsJson, fields (3) | 377 | 0 | Query Tushare financial data for Chinese listed companies. |
| 262 | `update_project_info` | MemoryTools:371 | enterprise-data | 全部会话 | NONE | 是 | - | field, value (2) | 27 | 101 | 更新项目的核心信息，如项目名称、交易金额、关键日期等。 |
| 263 | `web_verify_import` | WebVerifyTools:38 | enterprise-data | 全部会话 | NONE | 是 | ADDED | partyName, fileId, unifiedSocialCreditCode, sites, docFileId (5) | 319 | 151 | 把项目里已有的网核压缩包（外部工具导出的 zip，含各站点截图与页面文本）解包落进 _网核/<主体>/ 并自动挂到报告里对应的网络核查段落上。 |
| 264 | `law_recognition` | LegalTools:191 | legal | 全部会话 | NONE | 是 | - | text (1) | 65 | 0 | Identify law names and articles from text and trace their source. |
| 265 | `meeting_list_recordings` | MeetingTools:34 | meeting | 全部会话 | NONE | 是 | - | - (0) | 168 | 0 | List meeting recordings of the current project with their id, title, duration and transc… |
| 266 | `meeting_get_transcript` | MeetingTools:59 | meeting | 全部会话 | NONE | 是 | - | meetingId (1) | 249 | 20 | Read the full diarized transcript of a meeting recording, plus auxiliary material (chapt… |
| 267 | `tag_list` | TagTools:40 | task | 全部会话 | NONE | 是 | - | - (0) | 90 | 0 | 查看当前项目的标签清单，按类型分组（当事人/争议焦点/普通标签）。 |
| 268 | `tag_file` | TagTools:83 | task | 全部会话 | NONE | 是 | - | fileId, tagName, type (3) | 122 | 90 | 给文件打标签，可指定类型（NORMAL 普通 / PARTY 当事人 / ISSUE 争议焦点，缺省 NORMAL）。 |
| 269 | `tag_remove_from_file` | TagTools:134 | task | 全部会话 | NONE | 是 | - | fileId, tagName (2) | 32 | 53 | 移除文件上的某个标签（只解除文件与标签的关联，不删除标签本身）。 |
| 270 | `task_create` | TaskTools:40 | task | 全部会话 | NONE | 是 | - | title, dueDate, dueTime, fileId, type, notes, priority, fileIds, assigneeId, remindBefore (10) | 186 | 369 | 为当前项目创建一条事项（截止日、开庭、会议、待办等），创建后出现在项目日程与事项列表中供用户跟踪，并标记为 AI 建议。 |
| 271 | `task_update` | TaskTools:114 | task | 全部会话 | NONE | 是 | - | taskId, title, dueDate, dueTime, status, type, notes, priority (8) | 177 | 214 | 修改当前项目里已有的一条事项：改日期/时刻（如“把开庭改到下周三”）、改标题、改类型/备注/优先级，或标记完成（status=DONE）/重新打开（status=OPEN）。 |
| 272 | `task_list` | TaskTools:183 | task | 全部会话 | NONE | 是 | - | from, to (2) | 129 | 68 | 查询当前项目的事项列表（截止日、开庭、会议、待办等），可选按日期区间过滤。 |
| 273 | `dd_export` | DdExportTools:29 | files | 全部会话 | NONE | 是 | ADDED | kind, format, docFileId (3) | 257 | 113 | 导出尽调报告的交付件到项目 _交付件/ 文件夹（同名就地覆盖）。 |
| 274 | `read_file` | FileTools:162 | files | 全部会话 | NONE | 是 | - | filePath (1) | 544 | 0 | Read a file's text BY PATH (absolute, or relative to the project root). |
| 275 | `list_files` | FileTools:211 | files | 全部会话 | NONE | 是 | - | subPath (1) | 633 | 89 | PHYSICAL DISK view of one directory under data/projects/{projectId}/: entries in on-disk… |
| 276 | `write_file` | FileTools:381 | files | 全部会话 | NONE | 是 | ADDED | fileName, content (2) | 252 | 66 | Write content to a text file at the project root and register it in the project database… |
| 277 | `scan_files` | FileTools:584 | files | 全部会话 | NONE | 是 | - | - (0) | 108 | 0 | Actively scan the project directory and register any missing files to the database. |
| 278 | `delete_file` | FileTools:636 | files | 全部会话 | NONE | 否 | - | filePath (1) | 65 | 0 | Delete a file. |
| 279 | `move_file` | FileTools:645 | files | 全部会话 | NONE | 是 | - | sourcePath, destPath (2) | 343 | 97 | Move or rename a project file/folder by path (file tree and storage stay in sync). |
| 280 | `rename_project_file` | FileTools:1011 | files | 全部会话 | NONE | 是 | - | fileId, newName (2) | 175 | 25 | Rename a project file or folder (file tree and storage stay in sync). |
| 281 | `move_project_file` | FileTools:1027 | files | 全部会话 | NONE | 是 | - | fileId, targetFolderId (2) | 224 | 75 | Move a project file or folder into another folder (file tree and storage stay in sync). |
| 282 | `list_project_folders` | PptxTools:75 | files | 全部会话 | NONE | 是 | - | - (0) | 198 | 0 | 只列文件夹、不列文件，是 folderId 的来源：write_docx 的 parentFolderId、move_project_file 的 targetFolderId… |
| 283 | `text_write_file` | TextFileEditTools:62 | files | LOWA(声明) | LOWA | 是 | MODIFIED | fileId, content (2) | 189 | 63 | 整篇覆盖写入一个纯文本/代码文件（txt/md/json/js/html/css/yml 等，UTF-8）。 |
| 284 | `text_find_replace` | TextFileEditTools:97 | files | LOWA(声明) | LOWA | 是 | MODIFIED | fileId, find, replace, replaceAll (4) | 155 | 55 | 在纯文本/代码文件（txt/md/json/js/html/css/yml 等）中做字面量查找替换（非正则）。 |
| 285 | `capability_list` | CapabilityTools:39 | plugin | 全部会话 | NONE | 是 | - | - (0) | 85 | 0 | 列出宿主的全部能力槽（可替换的能力，如诉讼可视化出图引擎）、每个槽的候选实现与当前选择。 |
| 286 | `capability_install` | CapabilityTools:74 | plugin | 全部会话 | NONE | 是 | - | url (1) | 255 | 65 | 从一个 GitHub 仓库链接拉取能力包源码并校验，返回「安装计划」（仓库、commit、文件数、声明的权限、落到哪个能力槽、能否自动安装、拒绝理由）。 |
| 287 | `capability_apply` | CapabilityTools:121 | plugin | 全部会话 | NONE | 是 | - | planId (1) | 135 | 29 | 按 capability_install 返回的 planId 真正安装能力包（落盘 + 启用）。 |
| 288 | `capability_select` | CapabilityTools:144 | plugin | 全部会话 | NONE | 是 | - | slot, ref (2) | 118 | 96 | 把某个能力槽切换到指定的候选实现（切换即生效，可回滚）。 |
| 289 | `plugin_dev_scaffold` | PluginDevTools:28 | plugin | 全部会话 | NONE | 是 | ADDED | pluginId, displayName (2) | 224 | 60 | 在当前项目「插件开发/<id>/」下创建一个 Web 插件骨架（manifest. |
| 290 | `plugin_dev_install` | PluginDevTools:53 | plugin | 全部会话 | NONE | 是 | - | folderId (1) | 121 | 68 | 校验并把项目「插件开发」目录下的插件源码安装到本机运行（拷进本机插件目录、热重扫并启用）。 |
| 291 | `run_python` | PythonTools:221 | python | 全部会话 | NONE | 是 | - | code (1) | 341 | 0 | Run Python script for data analysis and computation. |
| 292 | `get_user_profile` | MemoryTools:145 | misc | 全部会话 | NONE | 是 | - | - (0) | 56 | 0 | 获取当前用户的画像信息：跨项目的用户偏好、行文习惯、常用表达等。 |
| 293 | `get_project_context` | MemoryTools:335 | misc | 全部会话 | NONE | 是 | - | - (0) | 34 | 0 | 获取当前项目的核心信息，包括项目类型、交易结构、当事人、关键日期等。 |
| 294 | `search_knowledge_base` | MemoryTools:401 | misc | 全部会话 | NONE | 否 | - | query, limit, scope, sourceFileId (4) | 57 | 128 | [已并入 query_memory(depth="hybrid")] 兼容入口，请改用 query_memory。 |
| 295 | `deep_search` | MemoryTools:423 | misc | 全部会话 | NONE | 否 | - | query, limit, scope, sourceFileId (4) | 55 | 128 | [已并入 query_memory(depth="deep")] 兼容入口，请改用 query_memory。 |
| 296 | `get_conversation_summary` | MemoryTools:440 | misc | 全部会话 | NONE | 是 | - | - (0) | 25 | 0 | 获取当前对话的历史摘要，了解之前讨论的要点和结论。 |
