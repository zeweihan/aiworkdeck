package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.model.ai.AgentMode;
import com.checkba.model.entity.ConversationSummary;
import com.checkba.model.entity.MemoryEntry;
import com.checkba.model.entity.ProjectMemory;
import com.checkba.service.ai.context.ContextCompressor;
import com.checkba.service.ai.context.FileContextLoader;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.ai.memory.MemoryManager;
import com.checkba.service.ai.tools.LegalTools;
import com.checkba.service.ProjectAiMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service to assemble context from files and other sources.
 * Injects <file> tags into the System Message.
 *
 * 增强功能：
 * - 智能上下文压缩
 * - 记忆系统集成
 * - 法律信息保护
 *
 * Phase 2：本服务只负责"组装消息"，不再直接读文件系统——
 * 文件/文件夹内容加载统一走 FileContextLoader。
 */
@Service
@RequiredArgsConstructor
public class ContextAssemblerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContextAssemblerService.class);

    private final LegalTools legalTools;
    private final ProjectAiMessageService messageService;
    private final FileContextLoader fileContextLoader;
    private final AiContextProperties contextProperties;
    private final com.checkba.service.ai.skill.SkillRouter skillRouter;
    private final ClientCapabilityService clientCapabilityService;
    private final InlineContentCache inlineContentCache;

    // 记忆系统组件（读侧：写侧见 MemoryPipelineService）
    private final MemoryManager memoryManager;
    private final ContextCompressor contextCompressor;

    // 应用语言（EN 版 PR5）：en-US 时选英文 system prompt 与各硬编码段的英文文本；
    // zh-CN 路径的代码与文本一字不动（中文版行为保持逐字节一致是硬约束）。
    private final com.checkba.service.AppLanguageService appLanguageService;

    /**
     * Assembles the full message stack for the LLM.
     * 1. System Message (Prompt + State + File Context + Mode Constraints)
     * 2. History Messages (Last 20)
     * 3. User Message (Current Prompt)
     * 
     * @param agentMode Agent 运行模式 (ASK, PLAN, AGENT)
     * @param activeContext NEW: 当前激活标签页（自动上下文，可为null）
     * @param modelKey 当前使用的模型标识（用于按模型解析 token 预算，可为 null）
     */
    public java.util.List<dev.langchain4j.data.message.ChatMessage> assemble(
            String conversationId,
            String userPrompt,
            java.util.List<com.checkba.controller.ai.AiAgentController.ContextItem> contextItems,
            com.checkba.controller.ai.AiAgentController.ContextItem activeContext,
            String taskListId,
            String planId,
            String projectId,
            AgentMode agentMode,
            Long userId,
            String modelKey) {

        java.util.List<dev.langchain4j.data.message.ChatMessage> messages = new java.util.ArrayList<>();

        // 应用语言：本次组装全程按它二选一（协议面 zh/en 完全一致，只有措辞与 Language 行不同）
        final boolean english = appLanguageService.isEnglish();

        // 1. Build Dynamic System Prompt
        StringBuilder systemText = new StringBuilder();

        // Load Base Prompt
        try {
            org.springframework.core.io.ClassPathResource resource = new org.springframework.core.io.ClassPathResource(
                    english ? "prompts/system_prompt.en.md" : "prompts/system_prompt.md");
            if (english && !resource.exists()) {
                // 英文资源缺失时回退中文版：协议面（标签/停机条件/工具约定）不能丢
                resource = new org.springframework.core.io.ClassPathResource("prompts/system_prompt.md");
            }
            if (resource.exists()) {
                systemText.append(org.springframework.util.StreamUtils.copyToString(resource.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            } else {
                systemText.append("You are a helpful AI Assistant.");
            }
        } catch (Exception e) {
            log.error("Failed to load system prompt for assembly", e);
            systemText.append("You are a helpful AI Assistant.");
        }

        // Determine current phase based on state
        String currentPhase = determinePhase(planId, taskListId);

        // [Injection] Enforcement (HIGHEST PRIORITY)
        // zh/en 差异只有 Language 一节（SIMPLIFIED CHINESE ONLY -> ENGLISH ONLY）与
        // artifact 命名示例；停机条件/输出顺序/工具规则两版逐条一致。
        String enforcement = english ? ENFORCEMENT_EN : """

# SYSTEM ENFORCEMENT (HIGHEST PRIORITY - READ CAREFULLY)

## CRITICAL: Raw XML Output (MUST READ FIRST)
- **DO NOT** wrap your output in markdown code blocks. No ```xml or ``` around tags.
- Output XML tags directly: `<thinking>...</thinking>` NOT ```xml\n<thinking>```
- VIOLATION OF THIS RULE WILL BREAK THE SYSTEM.

## Language
- SIMPLIFIED CHINESE ONLY for all user-facing output.

## Chitchat / Simple Q&A
- OMIT `<title>` and `<process>` tags entirely.
- Just output plain text response.

## Stop Conditions (CRITICAL)
- **STOP** when you output `<artifact type="implementation_plan">`. Wait for user approval.
- **ALSO STOP** when you output `<question>`: the turn ends there. Do NOT call any more
  tools and do NOT keep drafting in the same turn - the user's answer arrives as a new
  message. Use it only when a missing premise would make the deliverable wrong
  (see the Clarification section for exactly when to ask and when not to).
- **DO NOT STOP** for `<artifact type="task_list">` - continue execution immediately after.
- `<walkthrough>` does NOT trigger stop. It is only a brief summary.

## Output Structure (REQUIRED ORDER)
1. `<thinking>` - Brief intent analysis (always required)
2. `<title>` - Session title (complex tasks only)
3. `<process>` - Tool invocations (if any)
4. `<artifact>` - Only `implementation_plan` or `task_list` (if applicable)
5. `<final>` - **MAIN ANSWER** (REQUIRED for all non-chitchat responses)
   - EXCEPTION: when the turn ends with `<question>`, `<final>` is NOT required and you
     SHOULD omit it. Do NOT invent an answer just to satisfy this rule - you are asking
     precisely because you do not have one yet.
6. `<walkthrough>` - Brief 3-5 sentence past-tense summary (OPTIONAL)

## Final Answer Rules
- **Main Answer**: MUST be inside `<final>...</final>` tag.
- **Walkthrough**: ONLY for process summary. NEVER duplicate main answer here.
- **Forbidden**: Do NOT use `type="summary"` or `type="walkthrough"` as artifact types.

## Artifact Naming Rules
- When creating an artifact, you MUST include a `name` attribute with a specific, descriptive name (max 15 chars).
- Example: `<artifact type="implementation_plan" name="外汇管控架构备忘录">...`
- BAD: "Plan", "Implementation Plan". GOOD: Short descriptive names like "10号文备忘录".

## Tool Execution Rules
- When you output `<tool_code>`, STOP and wait for `<tool_output>`.
- When you receive `TOOL_RESULT`, you MUST continue execution. Do NOT ask "should I continue?".
- Do NOT output `<final>` in the same turn as `<tool_code>`.
""";
        systemText.append(enforcement);

        // [Injection] Mode-Specific Constraints (CRITICAL)
        systemText.append(english ? getModeConstraintsEn(agentMode) : getModeConstraints(agentMode));

        // [Injection] Skill（Phase 3B，规范见 docs/SKILL_SPEC.md）：
        // 把本轮生效的每个 skill 的 prompt 模板注入系统消息。
        //
        // **读的是编排器 activateForTurn 登记下来的生效集合，不是在这里重新 match(userPrompt)**：
        // 重新匹配等于只认触发词，用户手动选的 skill 会被裁了工具却拿不到 prompt——
        // 这正是旧 pinnedSkillId 的那个静默 bug（工具可见性与 prompt 注入走了两条不同的判据）。
        // 生效集合的口径唯一收敛在 SkillRouter.activateForTurn。
        //
        // ASK 模式跳过（skill 指引以工具流程为主，与 ASK 禁用工具的约束冲突）；
        // 一个都不生效时不注入（行为保持）。
        if (agentMode != AgentMode.ASK) {
            for (com.checkba.service.ai.skill.SkillRouter.ActiveSkill active
                    : skillRouter.activeSkills(conversationId)) {
                systemText.append(skillRouter.promptInjectionFor(active.definition()));
            }
        }

        // [Injection] State with Phase
        systemText.append("\n\n# Current Context\n[SYSTEM INJECTION]");
        
        // CRITICAL: Inject current system time (important for legal/financial data accuracy)
        // 时区两版都固定 Asia/Shanghai（英文版改用户时区是另一个问题，本 PR 不扩权）。
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        if (english) {
            String formattedTime = now.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy HH:mm:ss (EEEE)", java.util.Locale.ENGLISH));
            systemText.append("\n- **Current System Time**: ").append(formattedTime);
            systemText.append("\n  - This is the actual current time. Every query involving \"latest\", \"recent\", or \"current\" data must be judged against it.");
            systemText.append("\n  - If retrieved data is dated materially earlier than this time (e.g. a stock closing price should be from the most recent trading day), state the data's actual date explicitly to the user.");
        } else {
        String formattedTime = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss (EEEE)", java.util.Locale.CHINESE));
        systemText.append("\n- **Current System Time**: ").append(formattedTime);
        systemText.append("\n  - 这是当前真实时间，所有涉及\"最新\"、\"最近\"、\"当前\"的数据查询必须基于此时间判断。");
        systemText.append("\n  - 如果查询的数据日期早于此时间超过合理范围（如股票收盘价应为最近交易日），请明确告知用户数据的实际日期。");
        }
        
        systemText.append("\n- **Current Agent Mode**: ").append(agentMode != null ? agentMode.name() : "AGENT");
        systemText.append("\n- Current Phase: ").append(currentPhase);
        systemText.append("\n- Current Project ID: ").append(projectId != null ? projectId : "unknown");
        systemText.append("\n- Current Task List ID: ").append(taskListId != null ? taskListId : "null");
        systemText.append("\n- Current Plan ID: ").append(planId != null ? planId : "null");

        // Phase-specific instructions
        systemText.append("\n\n## Phase Instructions\n");
        switch (currentPhase) {
            case "PLAN":
                systemText.append("- You are in PLANNING phase. Output `implementation_plan` if task is complex, then STOP.\n");
                systemText.append("- Do NOT execute tools until user approves the plan.\n");
                break;
            case "EXECUTE":
                systemText.append("- You are in EXECUTION phase. The plan has been approved.\n");
                systemText.append("- Output `task_list` first (optional), then execute tools.\n");
                systemText.append("- After completion, output `<final>` with main answer.\n");
                break;
            case "CHAT":
            default:
                systemText.append("- Simple chat/Q&A mode. Just respond directly.\n");
                systemText.append("- If task becomes complex, switch to PLAN phase.\n");
                break;
        }

        // [Injection] Context Items (Files & Folders)
        if (contextItems != null && !contextItems.isEmpty()) {
            systemText.append("\n\n# User Context Files\n");
            systemText.append("The user has provided the following files/folders for context:\n");

            int maxFiles = contextProperties.getFiles().getMaxFilesPerContext();
            int maxCharsPerFile = contextProperties.getFiles().getMaxCharsPerFile();
            int totalFileCount = 0; // Limit files max across all items

            for (com.checkba.controller.ai.AiAgentController.ContextItem item : contextItems) {
                log.info("[Context] Processing item: id={}, name={}, isDir={}, fileType={}",
                         item.getId(), item.getName(), item.isDir(), item.getFileType());

                if (totalFileCount >= maxFiles) {
                    systemText.append("\n[System Note: Context limit reached (").append(maxFiles)
                              .append(" files max). Remaining items ignored.]\n");
                    break;
                }

                if (item.isDir()) {
                    // Folder Logic
                    systemText.append("\n## Folder: ").append(item.getName())
                              .append(" (ID: ").append(item.getId()).append(")\n");

                    String folderContent = fileContextLoader.buildFolderContext(item.getId(), projectId, totalFileCount);
                    systemText.append(folderContent);

                    // Update count based on how many files were read in folder?
                    // buildFolderContext returns string, we need to pass counter reference or approximate.
                    // Let's refine buildFolderContext to assume it consumes remaining slots.
                    // Actually, simpler: just let buildFolderContext run and we don't strictly update 'totalFileCount'
                    // precisely here unless we return a count object.
                    // For simplicity, we assume a folder consumes slots.
                    // Better: Pass proper AtomicInteger to buildFolderContext.
                } else {
                    // Single File Logic
                    String content = legalTools.read_document(item.getId());
                    // Truncate if too long
                    if (content != null && content.length() > maxCharsPerFile) {
                        content = content.substring(0, maxCharsPerFile) + "\n... [TRUNCATED - File too long]";
                    }
                    systemText.append("<file id=\"").append(item.getId())
                              .append("\" name=\"").append(attrSafe(item.getName())).append("\"><![CDATA[\n");
                    // 判空白而不只判 null：抽不出正文时（扫描件、抽取失败）拿到的是空串，
                    // 原来会往上下文里注入一段空 CDATA——模型看到「文件在这儿但里面什么都没有」，
                    // 于是转头自己再调一次读取工具。可见地写明读不出来才有下一步。
                    systemText.append(content != null && !content.isBlank()
                            ? fenceSafe(content) : "[Empty or unreadable file]");
                    systemText.append("\n]]></file>\n");
                    totalFileCount++;
                }
            }
        }

        // [Injection] Active Document Context (auto-detected current tab)
        // This is injected when no explicit context is provided but user is viewing a document
        // LLM decides whether to use this based on user's instruction
        if (activeContext != null && activeContext.getId() != null && !activeContext.getId().isEmpty()) {
            log.info("[Context] Injecting active document: id={}, name={}, inline={}",
                     activeContext.getId(), activeContext.getName(),
                     activeContext.getInlineContent() != null && !activeContext.getInlineContent().isEmpty());

            String content = resolveActiveDocumentContent(activeContext, conversationId);
            ClientCapabilityService.Capability capability = clientCapabilityService.capabilityOf(conversationId);

            if (english) {
                // 英文版整段声明 + 按能力分支的工具指引（协议内容与中文版逐条对应）
                systemText.append(activeDocumentGuidanceEn(activeContext, capability, conversationId));
            } else {
            systemText.append("\n\n# Active Document (当前活跃文档)\n");
            systemText.append("该文档（id=").append(activeContext.getId())
                      .append(", name=").append(activeContext.getName())
                      .append("）**已在编辑器中打开**，就是用户此刻正在看的文档。");
            systemText.append("用户说\"修订一下\"\"这个文档\"\"当前文档\"或未指明对象时，默认就是指它。\n");
            switch (capability) {
                case OFFICE -> {
                    // 宿主细分（Word/Excel/PowerPoint）：三类宿主的工具集互不相通，点错就是死路径
                    switch (clientCapabilityService.officeHostOf(conversationId)) {
                        case EXCEL -> {
                            systemText.append("该工作簿在用户本机的 Microsoft Excel 中打开，活动工作表内容已随本请求内联注入下方。");
                            systemText.append("读取/修改它一律使用 office_excel_* 工具（office_excel_get_range / ");
                            systemText.append("office_excel_set_values / office_excel_search），写入直接生效");
                            systemText.append("（Excel 没有修订机制）。表格格式/结构调整（单元格格式/边框/行列/合并/排序/工作表/冻结/公式）");
                            systemText.append("用对应 office_excel_* 工具（office_excel_format_cells / office_excel_set_borders / ");
                            systemText.append("office_excel_edit_rows_cols / office_excel_merge_cells / office_excel_sort_range / ");
                            systemText.append("office_excel_manage_sheets / office_excel_freeze_panes / office_excel_set_formulas / ");
                            systemText.append("office_excel_set_autofilter / office_excel_conditional_format）。");
                            systemText.append("改表前可先用 office_excel_get_overview 看工作表清单与各表尺寸，");
                            systemText.append("office_excel_select_range 可把用户视图定位到某处。");
                            systemText.append("单元格批注用 office_excel_add_comment / office_excel_get_comments / office_excel_reply_comment / ");
                            systemText.append("office_excel_resolve_comment / office_excel_delete_comment；数据验证用 office_excel_set_data_validation；");
                            systemText.append("图表用 office_excel_add_chart；命名区域用 office_excel_define_name；工作表保护用 office_excel_protect_sheet；");
                            systemText.append("行列分组用 office_excel_group_rows_cols；基础透视表用 office_excel_add_pivot_table。");
                            systemText.append("本会话没有 doc_* / sheet_* 工具，也没有 Word 面的 office_* 工具。\n\n");
                        }
                        case POWERPOINT -> {
                            systemText.append("该演示文稿在用户本机的 Microsoft PowerPoint 中打开，各页文本已随本请求内联注入下方。");
                            systemText.append("读取/修改它一律使用 office_ppt_* 工具（office_ppt_get_slides / office_ppt_replace_text / ");
                            systemText.append("office_ppt_format_text 排版文字、office_ppt_add_slide / office_ppt_delete_slide / ");
                            systemText.append("office_ppt_move_slide 管理页面、office_ppt_add_text_box / office_ppt_add_shape 插入文本框与形状、");
                            systemText.append("office_ppt_get_slide_details / office_ppt_delete_shape 精确定位并删除形状），");
                            systemText.append("写入直接生效（PowerPoint 没有修订机制，删改无法通过审阅面板撤销）。");
                            systemText.append("表格用 office_ppt_add_table 插入、office_ppt_table_read / office_ppt_table_set_cell 读写单元格；");
                            systemText.append("超链接用 office_ppt_set_hyperlink。");
                            systemText.append("本会话没有 doc_* 工具，也没有 Word 面的 office_* 工具。\n\n");
                        }
                        default -> {
                            systemText.append("该文档在用户本机的 Microsoft Word 中打开，正文已随本请求内联注入下方。");
                            systemText.append("读取/修改它一律使用 office_* 工具（office_get_text / office_search / ");
                            systemText.append("office_replace_text / office_insert_text / office_add_comment / ");
                            systemText.append("office_format_text / office_set_paragraph_format / office_get_formatting / ");
                            systemText.append("office_set_numbering / office_format_table / office_apply_standard_format 等），");
                            systemText.append("修改会以 Word 原生修订形式呈现。");
                            systemText.append("文档排版（字体/字号/行距/缩进/对齐/下划线/删除线/自动编号/表格边框；");
                            systemText.append("整篇按律所标准格式化用 office_apply_standard_format）用 office_format_text 与 ");
                            systemText.append("office_set_paragraph_format。表格建改用 office_insert_table / office_table_read / ");
                            systemText.append("office_table_set_cell / office_table_add_row / office_table_delete_row / ");
                            systemText.append("office_table_add_col / office_table_delete_col（改前先用 office_table_read 看清坐标，");
                            systemText.append("删行删列不进修订、只能靠撤销）；分页/分节符用 office_insert_break；超链接用 ");
                            systemText.append("office_set_hyperlink；页眉页脚（仅首节）用 office_edit_header_footer；");
                            systemText.append("批注用 office_get_comments / office_reply_comment / office_resolve_comment。");
                            systemText.append("修订接受/拒绝用 office_get_revisions 先看列表、再用 office_accept_revision / office_reject_revision" +
                                    "（单条按序号或 acceptAll/rejectAll 全部）；脚注/尾注用 office_insert_footnote / office_insert_endnote；");
                            systemText.append("图片插入用 office_insert_image（fileId 指项目文件，上限 2MB）；套用已命名样式用 office_apply_style；");
                            systemText.append("内容控件用 office_manage_content_control；文档属性（标题/作者等）用 office_set_document_properties。");
                            systemText.append("本会话没有 doc_* 工具。\n\n");
                        }
                    }
                }
                case NONE -> {
                    systemText.append("当前客户端没有文档编辑执行器：正文仅供阅读分析，");
                    systemText.append("请以文字形式给出分析结论或修改建议，不要尝试调用文档编辑工具。\n\n");
                }
                default -> {
                    // LOWA 会话按文档类型三分支：doc_*(Writer) / sheet_*(Calc) / slide_*(Impress)，
                    // 三套原语互不相通（xModel.getText() 等 Writer 专属调用在其他文档类型上必然失败）。
                    switch (lowaDocKind(activeContext)) {
                        case "sheet" -> {
                            systemText.append("这是一份电子表格，读取/修改一律使用 sheet_* 工具" +
                                    "（sheet_get_overview 先看工作表结构、sheet_read_range / sheet_write_cells 读写单元格），" +
                                    "写入直接生效（Calc 没有修订机制）——**无需也不要**调用 ");
                            systemText.append("`doc_list_project_files` 或 `doc_open_file` 去重新发现/打开它；");
                            systemText.append("只有用户明确要操作**其他**文档时才需要那两个工具。本会话没有 doc_* 工具。\n\n");
                        }
                        case "slide" -> {
                            systemText.append("这是一份演示文稿，读取/修改一律使用 slide_* 工具" +
                                    "（slide_get_overview 先看幻灯片总览、slide_get_page 看某页明细、" +
                                    "slide_set_shape_text / slide_replace_text 改文字、slide_write_notes 改备注），" +
                                    "写入直接生效（PPT 没有修订机制，误改用 doc_restore_checkpoint 回滚）——**无需也不要**调用 ");
                            systemText.append("`doc_list_project_files` 或 `doc_open_file` 去重新发现/打开它；");
                            systemText.append("只有用户明确要操作**其他**文档时才需要那两个工具。本会话没有 doc_* 工具。");
                            systemText.append("页与形状结构用 slide_add_page / slide_delete_page / slide_move_page / " +
                                    "slide_set_layout 增删移页与设版式、slide_add_text_box / slide_add_shape 插文本框与形状、" +
                                    "slide_delete_shape / slide_set_shape_geometry 删形状与调整位置尺寸。");
                            systemText.append("文字格式（字体/字号/粗斜体/下划线/删除线/颜色/对齐）用 slide_format_text、" +
                                    "形状填充边框透明度用 slide_format_shape；表格建改用 slide_add_table / slide_table_read / " +
                                    "slide_table_set_cell / slide_table_set_style；超链接用 slide_set_hyperlink。\n\n");
                        }
                        case "text" -> {
                            systemText.append("这是一份纯文本文件（txt/md），在轻量文本编辑器中打开，没有修订机制。" +
                                    "读取用 extract_file_text，修改用 text_write_file（整篇覆盖）或 " +
                                    "text_find_replace（字面量查找替换），改动直接生效、自动进入版本记录并同步刷新" +
                                    "用户打开的文本标签——**无需也不要**对它调用 doc_* / sheet_* / slide_* 工具。\n\n");
                        }
                        default -> {
                            systemText.append("所有 doc_* 编辑/读取工具直接作用于该文档——**无需也不要**调用 ");
                            systemText.append("`doc_list_project_files` 或 `doc_open_file` 去重新发现/打开它；");
                            systemText.append("只有用户明确要操作**其他**文档时才需要那两个工具。\n\n");
                        }
                    }
                }
            }
            } // end zh active-document guidance

            // 同 <file> 段：空白正文等于没读到，走下面的 readHint 分支明说「内容暂不可读」，
            // 别注入一段空 CDATA 让模型以为文档本身是空的
            if (content != null && !content.isBlank()) {
                // Truncate if too long
                int maxCharsPerFile = contextProperties.getFiles().getMaxCharsPerFile();
                if (content.length() > maxCharsPerFile) {
                    content = content.substring(0, maxCharsPerFile) + "\n... [TRUNCATED - File too long]";
                }

                systemText.append("<active_document id=\"").append(activeContext.getId())
                          .append("\" name=\"").append(attrSafe(activeContext.getName())).append("\"><![CDATA[\n");
                systemText.append(fenceSafe(content));
                systemText.append("\n]]></active_document>\n");
            } else {
                // 正文暂时读不到也要保留文档标识，模型仍可用读取类工具（按会话能力）直接读
                String readHint = english ? readHintEn(capability, activeContext, conversationId) : switch (capability) {
                    case OFFICE -> switch (clientCapabilityService.officeHostOf(conversationId)) {
                        case EXCEL -> "[内容暂不可读，可用 office_excel_get_range 直接读取]";
                        case POWERPOINT -> "[内容暂不可读，可用 office_ppt_get_slides 直接读取]";
                        default -> "[正文暂不可读，可用 office_get_text 直接读取]";
                    };
                    case NONE -> "[正文暂不可读]";
                    default -> switch (lowaDocKind(activeContext)) {
                        case "sheet" -> "[内容暂不可读，可用 sheet_get_overview / sheet_read_range 直接读取]";
                        case "slide" -> "[内容暂不可读，可用 slide_get_overview / slide_get_page 直接读取]";
                        case "text" -> "[内容暂不可读，可用 extract_file_text 直接读取]";
                        default -> "[正文暂不可读，可用 doc_get_document_text 直接分段读取]";
                    };
                };
                systemText.append("<active_document id=\"").append(activeContext.getId())
                          .append("\" name=\"").append(attrSafe(activeContext.getName()))
                          .append("\">").append(readHint).append("</active_document>\n");
            }
        }

        // 设置上下文（供 MemoryTools 使用）
        ProjectContextHolder.setProjectId(projectId);
        ProjectContextHolder.setConversationId(conversationId);
        if (userId != null) {
            ProjectContextHolder.setUserId(userId);
        }

        // 2. 注入项目记忆（如果存在）
        Long projectIdLong = null;
        try {
            projectIdLong = projectId != null ? Long.parseLong(projectId) : null;
        } catch (NumberFormatException e) {
            // ignore
        }
        
        if (projectIdLong != null) {
            Optional<ProjectMemory> projectMemoryOpt = memoryManager.getProjectMemory(projectIdLong);
            if (projectMemoryOpt.isPresent()) {
                ProjectMemory pm = projectMemoryOpt.get();
                systemText.append(english ? "\n\n# Project Memory (long-term)\n" : "\n\n# 项目记忆（长期记忆）\n");
                systemText.append(pm.toCoreContext());
            }
            
            // 注入相关的结构化记忆
            List<MemoryEntry> relevantMemories = memoryManager.retrieveMemories(
                    projectIdLong, userPrompt, null, 5);
            if (!relevantMemories.isEmpty()) {
                systemText.append(english ? "\n\n# Relevant Memories (evidence ledger)\n" : "\n\n# 相关记忆（证据账本）\n");
                systemText.append(memoryManager.formatAsEvidenceLedger(relevantMemories));
            }
        }

        // 3. 注入用户级记忆（跨项目：偏好、行文习惯、常用表达）
        if (userId != null) {
            List<MemoryEntry> userMemories = memoryManager.retrieveUserMemories(userId, 5);
            if (!userMemories.isEmpty()) {
                systemText.append(english
                        ? "\n\n# User Preferences and Habits (cross-project memory)\n"
                        : "\n\n# 用户偏好与习惯（跨项目记忆）\n");
                systemText.append(english
                        ? "The following are this user's long-standing preferences and habits; follow them in your output:\n"
                        : "以下是该用户长期积累的偏好与习惯，输出时应遵循：\n");
                for (MemoryEntry mem : userMemories) {
                    systemText.append("- ");
                    if (mem.getMemoryKey() != null) {
                        systemText.append(mem.getMemoryKey()).append(": ");
                    }
                    systemText.append(mem.getMemoryValue()).append("\n");
                }
            }
        }

        messages.add(dev.langchain4j.data.message.SystemMessage.from(systemText.toString()));

        // 3. 加载对话历史并进行智能压缩
        List<com.checkba.model.entity.ProjectAiMessage> historyEntities = messageService.listByConversationId(conversationId);
        
        // 转换为 ChatMessage 列表
        java.util.List<dev.langchain4j.data.message.ChatMessage> historyMessages = new java.util.ArrayList<>();
        for (com.checkba.model.entity.ProjectAiMessage entity : historyEntities) {
            if ("USER".equalsIgnoreCase(entity.getRole())) {
                historyMessages.add(dev.langchain4j.data.message.UserMessage.from(entity.getContent()));
            } else if ("ASSISTANT".equalsIgnoreCase(entity.getRole())) {
                historyMessages.add(dev.langchain4j.data.message.AiMessage.from(entity.getContent()));
            }
        }
        
        // 检查是否需要压缩（token 预算按模型解析，可在 ai.context.model-token-budgets 覆盖）
        if (contextCompressor.needsCompression(historyMessages, modelKey)) {
            log.info("Context compression triggered: {} messages, estimated {} tokens",
                    historyMessages.size(), contextCompressor.estimateTokens(historyMessages));

            // 获取已有的对话摘要
            ConversationSummary existingSummary = memoryManager.getConversationSummary(conversationId)
                    .orElse(null);

            // 获取项目记忆
            ProjectMemory pm = projectIdLong != null ?
                    memoryManager.getProjectMemory(projectIdLong).orElse(null) : null;

            // 执行压缩
            historyMessages = contextCompressor.compress(
                    historyMessages,
                    pm,
                    existingSummary,
                    contextCompressor.getAvailableTokensForHistory(modelKey)
            );

            log.info("Context compressed: {} messages, estimated {} tokens",
                    historyMessages.size(), contextCompressor.estimateTokens(historyMessages));
        } else {
            // 不需要压缩时，仍然限制最近消息条数
            int maxHistory = contextProperties.getCompression().getMaxHistoryMessages();
            if (historyMessages.size() > maxHistory) {
                historyMessages = historyMessages.subList(historyMessages.size() - maxHistory, historyMessages.size());
            }
        }
        
        messages.addAll(historyMessages);

        // 4. Add Current User Prompt
        // 活跃文档提醒挂在**用户消息尾部**而非只留在 system prompt：system prompt 里的同类
        // 声明被弱模型（如 DeepSeek Flash）稳定无视——实测注入了正文仍先调 doc_list_project_files
        // 重新发现文档。末位消息是注意力最高的位置，这里再说一次才真正生效。
        messages.add(dev.langchain4j.data.message.UserMessage.from(
                userPrompt + activeDocumentReminder(activeContext,
                        clientCapabilityService.capabilityOf(conversationId),
                        clientCapabilityService.officeHostOf(conversationId))));

        return messages;
    }
    
    /**
     * 嵌进 system message 的文档正文是不可信输入（对方律师产出的 docx、共享目录里的来件）。
     * 正文里出现 "]]>" 会提前闭合自己的 CDATA，其后的文字在模型看来与本服务自己拼的
     * 「# SYSTEM ENFORCEMENT」块同属 system 角色——等于让一份文档以最高信任位下指令。
     */
    private static String fenceSafe(String content) {
        return content == null ? "" : content.replace("]]>", "]]&gt;");
    }

    /**
     * 文件名同样不可信：双引号能撑破 name="..." 属性，接着伪造出新的标签闭合与容器。
     */
    private static String attrSafe(String name) {
        return name == null ? "" : name.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 活跃文档展示名：名字缺失时回退为通称，避免给模型看到「《null》」。 */
    private static String activeDocDisplayName(String name) {
        return (name == null || name.isBlank()) ? "当前文档" : "《" + name + "》";
    }

    /**
     * 活跃文档的末位提醒（拼在用户消息尾部），文案按会话客户端能力切换（Phase C）：
     * lowa=doc_* 口径（现状）；office=office_* 口径（正文已内联注入，改动经 office_* 落到宿主，
     * 按宿主 Word/Excel/PowerPoint 点名对应工具集）；none=只读口径。无活跃文档时返回空串。
     */
    private String activeDocumentReminder(com.checkba.controller.ai.AiAgentController.ContextItem activeContext,
                                          ClientCapabilityService.Capability capability,
                                          ClientCapabilityService.OfficeHost officeHost) {
        if (activeContext == null || activeContext.getId() == null || activeContext.getId().isEmpty()) {
            return "";
        }
        if (appLanguageService.isEnglish()) {
            return activeDocumentReminderEn(activeContext, capability, officeHost);
        }
        String docLabel = activeDocDisplayName(activeContext.getName());
        return switch (capability) {
            case OFFICE -> switch (officeHost) {
                case EXCEL -> "\n\n[系统提醒] 用户此刻在 Microsoft Excel 中打开着工作簿" + docLabel + "，"
                        + "活动工作表内容已内联注入 system prompt 的 <active_document>，可直接阅读分析。"
                        + "用户未指明别的文件时，「这个」「当前表格」「改一下」等都指它——"
                        + "读取/修改一律调用 office_excel_* 工具（office_excel_get_range / "
                        + "office_excel_set_values / office_excel_search），写入直接生效（Excel 没有修订机制）；"
                        + "表格格式/结构调整（单元格格式/边框/行列/合并/排序/工作表/冻结/公式/筛选/条件格式）用对应 office_excel_* 工具"
                        + "（office_excel_format_cells / office_excel_set_borders / office_excel_edit_rows_cols / "
                        + "office_excel_merge_cells / office_excel_sort_range / office_excel_manage_sheets / "
                        + "office_excel_freeze_panes / office_excel_set_formulas / office_excel_set_autofilter / "
                        + "office_excel_conditional_format），office_excel_get_overview 可先看全局、"
                        + "office_excel_select_range 可定位视图。单元格批注/数据验证/图表/命名区域/工作表保护/行列分组/"
                        + "基础透视表分别用 office_excel_add_comment 等批注四件套 / office_excel_set_data_validation / "
                        + "office_excel_add_chart / office_excel_define_name / office_excel_protect_sheet / "
                        + "office_excel_group_rows_cols / office_excel_add_pivot_table。";
                case POWERPOINT -> "\n\n[系统提醒] 用户此刻在 Microsoft PowerPoint 中打开着演示文稿" + docLabel + "，"
                        + "各页文本已内联注入 system prompt 的 <active_document>，可直接阅读分析。"
                        + "用户未指明别的文件时，「这个」「当前演示文稿」「改一下」等都指它——"
                        + "读取/修改一律调用 office_ppt_* 工具（office_ppt_get_slides / office_ppt_replace_text / "
                        + "office_ppt_format_text / office_ppt_add_slide / office_ppt_delete_slide / "
                        + "office_ppt_move_slide / office_ppt_add_text_box / office_ppt_add_shape / "
                        + "office_ppt_get_slide_details / office_ppt_delete_shape），"
                        + "写入直接生效（PowerPoint 没有修订机制，删改无法通过审阅面板撤销）。"
                        + "表格用 office_ppt_add_table / office_ppt_table_read / office_ppt_table_set_cell；"
                        + "超链接用 office_ppt_set_hyperlink。";
                default -> "\n\n[系统提醒] 用户此刻在 Microsoft Word 中打开着文档" + docLabel + "，"
                        + "其正文已内联注入 system prompt 的 <active_document>，可直接阅读分析。"
                        + "用户未指明别的文档时，「这个」「当前文档」「修订一下」等都指它——"
                        + "需要修改文档时调用 office_* 工具（office_replace_text / office_insert_text / "
                        + "office_add_comment / office_format_text / office_set_paragraph_format / "
                        + "office_set_numbering / office_format_table / office_apply_standard_format 等）落到 Word，"
                        + "修改会以 Word 原生修订形式呈现；文档排版（字体/字号/行距/缩进/对齐/下划线/删除线/"
                        + "自动编号/表格边框；整篇按律所标准格式化用 office_apply_standard_format）"
                        + "用 office_format_text 与 office_set_paragraph_format；表格建改用 office_insert_table / "
                        + "office_table_read / office_table_set_cell / office_table_add_row / office_table_delete_row / "
                        + "office_table_add_col / office_table_delete_col；分页/分节符用 office_insert_break；"
                        + "超链接用 office_set_hyperlink；页眉页脚（仅首节）用 office_edit_header_footer；"
                        + "批注用 office_get_comments / office_reply_comment / office_resolve_comment；"
                        + "修订接受/拒绝先 office_get_revisions 再 office_accept_revision / office_reject_revision；"
                        + "脚注/尾注用 office_insert_footnote / office_insert_endnote；图片插入用 office_insert_image；"
                        + "已命名样式用 office_apply_style；内容控件用 office_manage_content_control；"
                        + "文档属性用 office_set_document_properties。";
            };
            case NONE -> "\n\n[系统提醒] 用户当前查看的文档是" + docLabel + "，"
                    + "其正文见 system prompt 的 <active_document>，仅供阅读分析。"
                    + "本会话的客户端没有文档编辑执行器，请以文字形式给出结论或修改建议，"
                    + "不要尝试调用文档编辑工具。";
            default -> switch (lowaDocKind(activeContext)) {
                case "sheet" -> "\n\n[系统提醒] 编辑器中当前已打开电子表格" + docLabel + "（id="
                        + activeContext.getId() + "），其结构/内容见 system prompt 的 <active_document>。"
                        + "用户未指明别的文档时，「这个」「当前表格」「改一下」等都指它——"
                        + "直接调用 sheet_* 工具操作（Calc 没有修订机制，写入直接生效），"
                        + "**禁止**再调 doc_list_project_files 或 doc_open_file 去重新发现或打开它。";
                case "slide" -> "\n\n[系统提醒] 编辑器中当前已打开演示文稿" + docLabel + "（id="
                        + activeContext.getId() + "），其结构/内容见 system prompt 的 <active_document>。"
                        + "用户未指明别的文档时，「这个」「当前演示文稿」「改一下」等都指它——"
                        + "直接调用 slide_* 工具操作（PPT 没有修订机制，写入直接生效，误改用 doc_restore_checkpoint 回滚），"
                        + "页与形状结构（插删移页/设版式/插文本框与形状/删形状/调整位置尺寸）用 slide_add_page / "
                        + "slide_delete_page / slide_move_page / slide_set_layout / slide_add_text_box / "
                        + "slide_add_shape / slide_delete_shape / slide_set_shape_geometry，"
                        + "文字格式用 slide_format_text、形状样式用 slide_format_shape，"
                        + "表格用 slide_add_table / slide_table_read / slide_table_set_cell / slide_table_set_style，"
                        + "超链接用 slide_set_hyperlink，"
                        + "**禁止**再调 doc_list_project_files 或 doc_open_file 去重新发现或打开它。";
                case "text" -> "\n\n[系统提醒] 用户此刻打开的是纯文本文件" + docLabel + "（id="
                        + activeContext.getId() + "），其内容见 system prompt 的 <active_document>。"
                        + "用户未指明别的文件时，「这个」「当前文件」「改一下」等都指它——"
                        + "读取用 extract_file_text，修改用 text_write_file / text_find_replace"
                        + "（写入直接生效并进入版本记录），**禁止**对它调用 doc_* / sheet_* / slide_* 工具。";
                default -> "\n\n[系统提醒] 编辑器中当前已打开文档" + docLabel + "（id="
                        + activeContext.getId() + "），其正文见 system prompt 的 <active_document>。"
                        + "用户未指明别的文档时，「这个」「当前文档」「修订一下」等都指它——"
                        + "直接调用 doc_* 工具操作，**禁止**再调 doc_list_project_files 或 doc_open_file 去重新发现或打开它。";
            };
        };
    }

    /**
     * LOWA 会话的文档类型三分支判据：docx→doc_*（返回 "doc"）、xlsx→sheet_*（返回 "sheet"）、
     * pptx→slide_*（返回 "slide"）。优先取 fileType（后端已知扩展名，无点号），
     * 缺失时退回文件名后缀。三套原语互不相通，判据错了就是模型调用会死路径。
     */
    private static String lowaDocKind(com.checkba.controller.ai.AiAgentController.ContextItem activeContext) {
        String ext = activeContext.getFileType();
        if (ext == null || ext.isBlank()) {
            String name = activeContext.getName();
            int dot = name == null ? -1 : name.lastIndexOf('.');
            ext = (dot >= 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";
        }
        ext = ext.toLowerCase(java.util.Locale.ROOT);
        if (ext.startsWith("xls") || ext.startsWith("et") || "csv".equals(ext)) return "sheet";
        if (ext.startsWith("ppt") || "odp".equals(ext) || "potx".equals(ext)) return "slide";
        // 纯文本（dev-board#37 起走轻量文本编辑器，不进 LOWA）：text_* 后端直改口径
        if ("txt".equals(ext) || "md".equals(ext) || "markdown".equals(ext)) return "text";
        return "doc";
    }

    /** 内联正文防滥用上限：超出即截断（客户端可随请求直接携带正文，不能无限吃内存）。 */
    private static final int MAX_INLINE_CONTENT_CHARS = 200_000;

    /**
     * 活跃文档正文来源三选一：
     * 1) 请求随带的内联正文（Office 插件等场景——文档在客户端本地，后端没有可读的 fileId）优先，
     *    同时按会话存入 InlineContentCache 供后续「省传」轮次取用；
     * 2) 只带内联正文哈希（文档自上一轮起没变，客户端省掉了整篇正文的上行）：凭哈希查缓存，
     *    命中即复用；未命中（缓存已被 LRU 驱逐、或哈希对不上说明文档已改）返回 null，
     *    由调用方按「正文暂不可读」现状处理——模型可改用读取类工具，不报错；
     * 3) 两者都没有时走既有 read_document(fileId) 路径。
     * 三条路径产出同格式正文，后续统一由调用方做 CDATA 包裹与 maxCharsPerFile 截断。
     */
    private String resolveActiveDocumentContent(
            com.checkba.controller.ai.AiAgentController.ContextItem activeContext,
            String conversationId) {
        String inline = activeContext.getInlineContent();
        if (inline != null && !inline.isEmpty()) {
            if (inline.length() > MAX_INLINE_CONTENT_CHARS) {
                // 超限正文不入缓存：缓存的内存上界按每条 200k 字符估算
                return inline.substring(0, MAX_INLINE_CONTENT_CHARS)
                        + "\n... [TRUNCATED - Inline content too long]";
            }
            inlineContentCache.put(conversationId, inline);
            return inline;
        }
        String hash = activeContext.getInlineContentHash();
        if (hash != null && !hash.isBlank()) {
            String cached = inlineContentCache.get(conversationId, hash);
            if (cached == null) {
                log.info("[Context] Inline content hash miss for conversation {}, falling back to no inline body",
                        conversationId);
            }
            return cached;
        }
        return legalTools.read_document(activeContext.getId());
    }

    /**
     * Determines the current phase based on plan and task list state.
     * - CHAT: No plan, no task list (simple conversation)
     * - PLAN: User request may need planning (no approved plan yet)
     * - EXECUTE: Plan approved, ready to execute
     */
    private String determinePhase(String planId, String taskListId) {
        // If we have a plan ID, we're in EXECUTE phase (plan was approved)
        if (planId != null && !planId.equals("null") && !planId.isEmpty()) {
            return "EXECUTE";
        }
        // If we have a task list but no plan, we're also in EXECUTE (simple execution)
        if (taskListId != null && !taskListId.equals("null") && !taskListId.isEmpty()) {
            return "EXECUTE";
        }
        // Default to CHAT for new conversations
        return "CHAT";
    }

    /**
     * Legacy support for basic context assembly.
     */
    public String assembleContext(List<String> fileIds) {
        // (Existing logic kept for compatibility or internal use if needed)
        if (fileIds == null || fileIds.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String fileId : fileIds) {
            sb.append("<file id=\"").append(fileId).append("\">\n")
              .append(legalTools.read_document(fileId))
              .append("\n</file>\n");
        }
        return sb.toString();
    }

    /**
     * 根据 Agent 模式生成对应的提示词约束。
     * 
     * - ASK: 纯对话模式，禁止工具调用
     * - PLAN: 规划模式，必须先生成计划并等待确认
     * - AGENT: 自动执行模式（默认行为）
     */
    private String getModeConstraints(AgentMode mode) {
        if (mode == null) mode = AgentMode.AGENT;
        
        return switch (mode) {
            case ASK -> """

# MODE OVERRIDE: ASK MODE (纯对话模式)

**CRITICAL CONSTRAINTS - YOU MUST FOLLOW THESE RULES:**

1. **FORBIDDEN ACTIONS** - 以下操作在 Ask 模式下完全禁止：
   - DO NOT output `<tool_code>` tags - 不允许调用任何工具
   - DO NOT output `<artifact>` tags - 不生成任何计划或任务清单
   - DO NOT output `<process>` tags - 不执行任何操作流程
   - DO NOT use any tools (search_web, read_document, write_docx, etc.)

2. **ALLOWED ACTIONS** - 在 Ask 模式下你只能：
   - 直接回答用户问题（使用 `<thinking>` + 纯文本或 `<final>` 标签）
   - 基于已有上下文（文件内容、历史记录）进行分析和解答
   - 提供建议和意见，但不执行任何操作
   - 如果用户请求需要工具才能完成，请告知用户切换到 Agent 模式

3. **OUTPUT FORMAT**:
   <thinking>分析用户意图...</thinking>
   
   <final>
   直接回答用户问题的内容...
   </final>

4. **IMPORTANT**: 如果用户询问需要查询法规、搜索网络、读取文档或创建文件的问题，
   你应该基于你的知识库回答，或者建议用户切换到 Agent 模式以获取实时信息。
""";
            case PLAN -> """

# MODE OVERRIDE: PLAN MODE (规划模式)

**CRITICAL CONSTRAINTS - YOU MUST FOLLOW THESE RULES:**

1. **MANDATORY PLANNING** - 必须先生成计划：
   - 对于任何非简单问答的请求，你必须先输出 `<artifact type="implementation_plan">`
   - 计划必须详细列出将要执行的步骤、使用的工具、预期产出
   - 输出计划后立即停止，等待用户确认

2. **NO EXECUTION UNTIL APPROVED** - 未经确认不得执行：
   - 在用户明确批准计划之前，禁止使用 `<tool_code>` 调用任何工具
   - 如果用户说"确认"、"同意"、"执行"等确认词，则可以开始执行
   - 执行时按照计划中的步骤逐一进行

3. **PLAN OUTPUT FORMAT**:
   <thinking>分析任务复杂度和所需步骤...</thinking>
   
   <title>任务标题</title>
   
   <artifact type="implementation_plan" name="计划名称">
   ## 任务目标
   [描述要完成什么]
   
   ## 执行步骤
   1. [步骤1描述] - 使用工具: xxx
   2. [步骤2描述] - 使用工具: xxx
   3. ...
   
   ## 预期产出
   - [产出1]
   - [产出2]
   
   请确认是否按此计划执行？
   </artifact>
   
   (STOP HERE - 等待用户确认)

4. **SIMPLE QUESTIONS**: 对于简单问答（如打招呼、概念解释），可以直接回答，无需生成计划。
""";
            case AGENT -> """

# MODE: AGENT MODE (自动执行模式)

当前处于 Agent 模式，这是默认的完整功能模式：

1. **自动执行**: 可以自动调用工具完成任务，无需等待用户确认。但**缺少影响成果正确性的
   前提时用 `<question>` 先问**（判据见 Clarification 一节：能从文档/项目文件/历史/记忆里
   查到的先用工具查，别问；只有猜错会让整份产出作废的前提才问）
2. **智能规划**: 对于复杂任务可以生成 `task_list`（但不会停止等待确认）
3. **工具使用**: 可以使用所有可用工具（搜索、读写文件、法律研究等）
4. **正常流程**: 按照标准的 [Thought -> Action -> Observation] 循环执行

## 精确执行原则 (CRITICAL - 必须遵守)
- **严格遵循用户请求的边界**：只执行用户明确要求的操作
- 如果用户说"删除第三个z"，就**只删除第三个z**，不要删除第二个、第四个或任何其他z
- 完成用户**明确请求的任务**后，立即输出 `<final>` 结束
- **禁止**自作主张继续执行"相关"或"类似"的额外操作
""";
        };
    }

    // ==================== 英文文本（EN 版 PR5） ====================
    // 协议面（标签名/工具名/停机条件/输出顺序）与上方中文版逐条一致，只有措辞、
    // Language 行与示例不同。改中文版对应段时这里必须同步（反之亦然）。

    private static final String ENFORCEMENT_EN = """

# SYSTEM ENFORCEMENT (HIGHEST PRIORITY - READ CAREFULLY)

## CRITICAL: Raw XML Output (MUST READ FIRST)
- **DO NOT** wrap your output in markdown code blocks. No ```xml or ``` around tags.
- Output XML tags directly: `<thinking>...</thinking>` NOT ```xml\n<thinking>```
- VIOLATION OF THIS RULE WILL BREAK THE SYSTEM.

## Language
- ENGLISH ONLY for all user-facing output.

## Chitchat / Simple Q&A
- OMIT `<title>` and `<process>` tags entirely.
- Just output plain text response.

## Stop Conditions (CRITICAL)
- **STOP** when you output `<artifact type="implementation_plan">`. Wait for user approval.
- **ALSO STOP** when you output `<question>`: the turn ends there. Do NOT call any more
  tools and do NOT keep drafting in the same turn - the user's answer arrives as a new
  message. Use it only when a missing premise would make the deliverable wrong
  (see the Clarification section for exactly when to ask and when not to).
- **DO NOT STOP** for `<artifact type="task_list">` - continue execution immediately after.
- `<walkthrough>` does NOT trigger stop. It is only a brief summary.

## Output Structure (REQUIRED ORDER)
1. `<thinking>` - Brief intent analysis (always required)
2. `<title>` - Session title (complex tasks only)
3. `<process>` - Tool invocations (if any)
4. `<artifact>` - Only `implementation_plan` or `task_list` (if applicable)
5. `<final>` - **MAIN ANSWER** (REQUIRED for all non-chitchat responses)
   - EXCEPTION: when the turn ends with `<question>`, `<final>` is NOT required and you
     SHOULD omit it. Do NOT invent an answer just to satisfy this rule - you are asking
     precisely because you do not have one yet.
6. `<walkthrough>` - Brief 3-5 sentence past-tense summary (OPTIONAL)

## Final Answer Rules
- **Main Answer**: MUST be inside `<final>...</final>` tag.
- **Walkthrough**: ONLY for process summary. NEVER duplicate main answer here.
- **Forbidden**: Do NOT use `type="summary"` or `type="walkthrough"` as artifact types.

## Artifact Naming Rules
- When creating an artifact, you MUST include a `name` attribute with a specific, descriptive name (max 15 chars).
- Example: `<artifact type="implementation_plan" name="SPA Redline">...`
- BAD: "Plan", "Implementation Plan". GOOD: Short descriptive names like "FX Memo".

## Tool Execution Rules
- When you output `<tool_code>`, STOP and wait for `<tool_output>`.
- When you receive `TOOL_RESULT`, you MUST continue execution. Do NOT ask "should I continue?".
- Do NOT output `<final>` in the same turn as `<tool_code>`.
""";

    /** 模式约束的英文版（结构与 {@link #getModeConstraints} 逐条对应）。 */
    private String getModeConstraintsEn(AgentMode mode) {
        if (mode == null) mode = AgentMode.AGENT;

        return switch (mode) {
            case ASK -> """

# MODE OVERRIDE: ASK MODE (conversation-only)

**CRITICAL CONSTRAINTS - YOU MUST FOLLOW THESE RULES:**

1. **FORBIDDEN ACTIONS** - the following are completely prohibited in Ask mode:
   - DO NOT output `<tool_code>` tags - no tool calls of any kind
   - DO NOT output `<artifact>` tags - no plans or task lists
   - DO NOT output `<process>` tags - no operation flows
   - DO NOT use any tools (search_web, read_document, write_docx, etc.)

2. **ALLOWED ACTIONS** - in Ask mode you may ONLY:
   - Answer the user's question directly (using `<thinking>` + plain text or the `<final>` tag)
   - Analyze and explain based on context you already have (file contents, conversation history)
   - Offer advice and opinions, without performing any operation
   - If the user's request requires tools to complete, tell the user to switch to Agent mode

3. **OUTPUT FORMAT**:
   <thinking>Analyzing the user's intent...</thinking>

   <final>
   The direct answer to the user's question...
   </final>

4. **IMPORTANT**: If the user asks something that would require statutory research, web
   search, reading a document, or creating a file, answer from your own knowledge, or
   suggest that the user switch to Agent mode to get real-time information.
""";
            case PLAN -> """

# MODE OVERRIDE: PLAN MODE

**CRITICAL CONSTRAINTS - YOU MUST FOLLOW THESE RULES:**

1. **MANDATORY PLANNING** - a plan must come first:
   - For any request beyond simple Q&A, you MUST first output `<artifact type="implementation_plan">`
   - The plan MUST spell out the steps to be executed, the tools to be used, and the expected deliverables
   - After outputting the plan, stop immediately and wait for the user's confirmation

2. **NO EXECUTION UNTIL APPROVED** - do not execute before confirmation:
   - Until the user has explicitly approved the plan, calling any tool via `<tool_code>` is FORBIDDEN
   - If the user says "confirm", "agreed", "go ahead", "proceed", or similar words of approval, you may begin execution
   - When executing, follow the plan's steps one by one

3. **PLAN OUTPUT FORMAT**:
   <thinking>Analyzing the task's complexity and the steps required...</thinking>

   <title>Task title</title>

   <artifact type="implementation_plan" name="Plan name">
   ## Objective
   [What is to be accomplished]

   ## Steps
   1. [Step 1 description] - tools: xxx
   2. [Step 2 description] - tools: xxx
   3. ...

   ## Expected Deliverables
   - [Deliverable 1]
   - [Deliverable 2]

   Shall I proceed with this plan?
   </artifact>

   (STOP HERE - wait for the user's confirmation)

4. **SIMPLE QUESTIONS**: for simple Q&A (greetings, concept explanations), answer directly - no plan needed.
""";
            case AGENT -> """

# MODE: AGENT MODE (autonomous execution)

You are in Agent mode, the default full-capability mode:

1. **Autonomous execution**: you may call tools to complete the task without waiting for
   user confirmation. BUT **when a premise that affects the correctness of the deliverable
   is missing, ask first with `<question>`** (the standard is in the Clarification section:
   anything you can find in the document / project files / history / memory, look up with
   tools instead of asking; ask only about premises where a wrong guess would void the
   entire deliverable)
2. **Smart planning**: for complex tasks you may produce a `task_list` (which does NOT stop and wait for confirmation)
3. **Tool use**: all available tools may be used (search, file read/write, legal research, etc.)
4. **Normal flow**: follow the standard [Thought -> Action -> Observation] loop

## Precise Execution Principle (CRITICAL - MUST follow)
- **Strictly respect the boundary of the user's request**: perform only what the user explicitly asked for
- If the user says "delete the 3rd z", delete **only the 3rd z** - not the 2nd, the 4th, or any other z
- After finishing the task the user **explicitly requested**, output `<final>` immediately and end
- It is **FORBIDDEN** to continue on your own initiative with "related" or "similar" extra operations
""";
        };
    }

    // 活跃文档指引（system prompt 段）的英文分支文本，与中文 switch 各分支逐条对应
    private static final String EN_GUIDE_OFFICE_EXCEL = """
This workbook is open in Microsoft Excel on the user's machine; the active worksheet's content is inlined below with this request.
Read and modify it exclusively with the office_excel_* tools (office_excel_get_range / office_excel_set_values / office_excel_search); writes take effect immediately (Excel has no track-changes mechanism).
For formatting and structural changes (cell formats / borders / rows and columns / merging / sorting / worksheets / freezing / formulas), use the corresponding office_excel_* tools (office_excel_format_cells / office_excel_set_borders / office_excel_edit_rows_cols / office_excel_merge_cells / office_excel_sort_range / office_excel_manage_sheets / office_excel_freeze_panes / office_excel_set_formulas / office_excel_set_autofilter / office_excel_conditional_format).
Before changing the sheet you may first call office_excel_get_overview to see the worksheet list and each sheet's dimensions; office_excel_select_range can move the user's view to a location.
Cell comments use office_excel_add_comment / office_excel_get_comments / office_excel_reply_comment / office_excel_resolve_comment / office_excel_delete_comment; data validation uses office_excel_set_data_validation; charts use office_excel_add_chart; named ranges use office_excel_define_name; sheet protection uses office_excel_protect_sheet; row/column grouping uses office_excel_group_rows_cols; basic pivot tables use office_excel_add_pivot_table.
This session has no doc_* / sheet_* tools, and none of the Word-side office_* tools.

""";

    private static final String EN_GUIDE_OFFICE_PPT = """
This presentation is open in Microsoft PowerPoint on the user's machine; the text of each slide is inlined below with this request.
Read and modify it exclusively with the office_ppt_* tools (office_ppt_get_slides / office_ppt_replace_text / office_ppt_format_text for text and formatting; office_ppt_add_slide / office_ppt_delete_slide / office_ppt_move_slide for slide management; office_ppt_add_text_box / office_ppt_add_shape to insert text boxes and shapes; office_ppt_get_slide_details / office_ppt_delete_shape to locate precisely and delete shapes); writes take effect immediately (PowerPoint has no track-changes mechanism - deletions and edits cannot be undone from a review panel).
Tables: office_ppt_add_table to insert, office_ppt_table_read / office_ppt_table_set_cell to read and write cells; hyperlinks: office_ppt_set_hyperlink.
This session has no doc_* tools, and none of the Word-side office_* tools.

""";

    private static final String EN_GUIDE_OFFICE_WORD = """
This document is open in Microsoft Word on the user's machine; its body text is inlined below with this request.
Read and modify it exclusively with the office_* tools (office_get_text / office_search / office_replace_text / office_insert_text / office_add_comment / office_format_text / office_set_paragraph_format / office_get_formatting / office_set_numbering / office_format_table / office_apply_standard_format, etc.); edits appear as native Word tracked changes.
Document formatting (font / size / line spacing / indentation / alignment / underline / strikethrough / automatic numbering / table borders; to format the whole document to the firm's house style use office_apply_standard_format) is done with office_format_text and office_set_paragraph_format.
Tables are built and edited with office_insert_table / office_table_read / office_table_set_cell / office_table_add_row / office_table_delete_row / office_table_add_col / office_table_delete_col (call office_table_read first to see the exact coordinates; row and column deletions are NOT tracked as revisions and can only be reversed by undo).
Page and section breaks use office_insert_break; hyperlinks use office_set_hyperlink; headers and footers (first section only) use office_edit_header_footer; comments use office_get_comments / office_reply_comment / office_resolve_comment.
To accept or reject revisions, first list them with office_get_revisions, then office_accept_revision / office_reject_revision (a single revision by index, or acceptAll/rejectAll for all); footnotes and endnotes use office_insert_footnote / office_insert_endnote; image insertion uses office_insert_image (fileId refers to a project file, 2MB limit); named styles use office_apply_style; content controls use office_manage_content_control; document properties (title/author, etc.) use office_set_document_properties.
This session has no doc_* tools.

""";

    private static final String EN_GUIDE_NONE = """
The current client has no document-editing executor: the body text is provided for reading and analysis only.
Give your conclusions or suggested edits in prose; do not attempt to call document-editing tools.

""";

    private static final String EN_GUIDE_LOWA_SHEET = """
This is a spreadsheet. Read and modify it exclusively with the sheet_* tools (sheet_get_overview first to see the worksheet structure, sheet_read_range / sheet_write_cells to read and write cells); writes take effect immediately (Calc has no track-changes mechanism). You need NOT - and must NOT - call `doc_list_project_files` or `doc_open_file` to rediscover or reopen it; those two tools are needed only when the user explicitly wants to work on a DIFFERENT document. This session has no doc_* tools.

""";

    private static final String EN_GUIDE_LOWA_TEXT = """
This is a plain-text file (txt/md), open in the lightweight text editor; it has no track-changes mechanism. Read it with extract_file_text; modify it with text_write_file (whole-file overwrite) or text_find_replace (literal find and replace). Changes take effect immediately, enter the version history automatically, and refresh the user's open text tab. You need NOT - and must NOT - call doc_* / sheet_* / slide_* tools on it.

""";

    private static final String EN_GUIDE_LOWA_SLIDE = """
This is a presentation. Read and modify it exclusively with the slide_* tools (slide_get_overview first for the deck overview, slide_get_page for one slide's details, slide_set_shape_text / slide_replace_text to change text, slide_write_notes to change speaker notes); writes take effect immediately (presentations have no track-changes mechanism; roll back mistakes with doc_restore_checkpoint). You need NOT - and must NOT - call `doc_list_project_files` or `doc_open_file` to rediscover or reopen it; those two tools are needed only when the user explicitly wants to work on a DIFFERENT document. This session has no doc_* tools.
Slide and shape structure: slide_add_page / slide_delete_page / slide_move_page / slide_set_layout to add, delete, move slides and set layouts; slide_add_text_box / slide_add_shape to insert text boxes and shapes; slide_delete_shape / slide_set_shape_geometry to delete shapes and adjust position and size.
Text formatting (font / size / bold and italic / underline / strikethrough / color / alignment) uses slide_format_text; shape fill, border, and transparency use slide_format_shape; tables use slide_add_table / slide_table_read / slide_table_set_cell / slide_table_set_style; hyperlinks use slide_set_hyperlink.

""";

    private static final String EN_GUIDE_LOWA_DOC = """
All doc_* editing and reading tools act directly on this document. You need NOT - and must NOT - call `doc_list_project_files` or `doc_open_file` to rediscover or reopen it; those two tools are needed only when the user explicitly wants to work on a DIFFERENT document.

""";

    /** 活跃文档指引（system prompt 段）英文版：整段声明 + 按能力/宿主/文档类型分支的工具指引。 */
    private String activeDocumentGuidanceEn(
            com.checkba.controller.ai.AiAgentController.ContextItem activeContext,
            ClientCapabilityService.Capability capability,
            String conversationId) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n# Active Document\n");
        sb.append("This document (id=").append(activeContext.getId())
          .append(", name=").append(activeContext.getName())
          .append(") **is open in the editor** - it is the document the user is looking at right now. ");
        sb.append("When the user says \"revise this\", \"this document\", \"the current document\", or gives no target, they mean this document by default.\n");
        switch (capability) {
            case OFFICE -> {
                switch (clientCapabilityService.officeHostOf(conversationId)) {
                    case EXCEL -> sb.append(EN_GUIDE_OFFICE_EXCEL);
                    case POWERPOINT -> sb.append(EN_GUIDE_OFFICE_PPT);
                    default -> sb.append(EN_GUIDE_OFFICE_WORD);
                }
            }
            case NONE -> sb.append(EN_GUIDE_NONE);
            default -> {
                switch (lowaDocKind(activeContext)) {
                    case "sheet" -> sb.append(EN_GUIDE_LOWA_SHEET);
                    case "slide" -> sb.append(EN_GUIDE_LOWA_SLIDE);
                    case "text" -> sb.append(EN_GUIDE_LOWA_TEXT);
                    default -> sb.append(EN_GUIDE_LOWA_DOC);
                }
            }
        }
        return sb.toString();
    }

    /** 正文暂不可读提示的英文版（分支与中文 readHint switch 逐条对应）。 */
    private String readHintEn(ClientCapabilityService.Capability capability,
                              com.checkba.controller.ai.AiAgentController.ContextItem activeContext,
                              String conversationId) {
        return switch (capability) {
            case OFFICE -> switch (clientCapabilityService.officeHostOf(conversationId)) {
                case EXCEL -> "[Content temporarily unreadable - read it directly with office_excel_get_range]";
                case POWERPOINT -> "[Content temporarily unreadable - read it directly with office_ppt_get_slides]";
                default -> "[Body temporarily unreadable - read it directly with office_get_text]";
            };
            case NONE -> "[Body temporarily unreadable]";
            default -> switch (lowaDocKind(activeContext)) {
                case "sheet" -> "[Content temporarily unreadable - read it directly with sheet_get_overview / sheet_read_range]";
                case "slide" -> "[Content temporarily unreadable - read it directly with slide_get_overview / slide_get_page]";
                case "text" -> "[Content temporarily unreadable - read it directly with extract_file_text]";
                default -> "[Body temporarily unreadable - read it in chunks with doc_get_document_text]";
            };
        };
    }

    /** 活跃文档展示名（英文）：双引号包裹，名字缺失时回退通称。 */
    private static String activeDocDisplayNameEn(String name) {
        return (name == null || name.isBlank()) ? "the current document" : "\"" + name + "\"";
    }

    /** 末位提醒（用户消息尾部）的英文版，分支与中文 {@code activeDocumentReminder} 逐条对应。 */
    private String activeDocumentReminderEn(com.checkba.controller.ai.AiAgentController.ContextItem activeContext,
                                            ClientCapabilityService.Capability capability,
                                            ClientCapabilityService.OfficeHost officeHost) {
        String docLabel = activeDocDisplayNameEn(activeContext.getName());
        return switch (capability) {
            case OFFICE -> switch (officeHost) {
                case EXCEL -> "\n\n[System reminder] The user currently has the workbook " + docLabel
                        + " open in Microsoft Excel; the active worksheet's content is inlined in the system prompt's "
                        + "<active_document> and can be read and analyzed directly. "
                        + "Unless the user names another file, \"this\", \"the current spreadsheet\", \"change it\", "
                        + "and the like refer to this workbook - read and modify it exclusively via the office_excel_* tools "
                        + "(office_excel_get_range / office_excel_set_values / office_excel_search); "
                        + "writes take effect immediately (Excel has no track-changes mechanism); "
                        + "formatting and structural changes (cell formats / borders / rows and columns / merging / sorting / "
                        + "worksheets / freezing / formulas / filters / conditional formats) use the corresponding office_excel_* tools "
                        + "(office_excel_format_cells / office_excel_set_borders / office_excel_edit_rows_cols / "
                        + "office_excel_merge_cells / office_excel_sort_range / office_excel_manage_sheets / "
                        + "office_excel_freeze_panes / office_excel_set_formulas / office_excel_set_autofilter / "
                        + "office_excel_conditional_format); office_excel_get_overview shows the big picture first and "
                        + "office_excel_select_range positions the view. Cell comments / data validation / charts / "
                        + "named ranges / sheet protection / row-column grouping / basic pivot tables use, respectively, "
                        + "the office_excel_add_comment comment suite / office_excel_set_data_validation / "
                        + "office_excel_add_chart / office_excel_define_name / office_excel_protect_sheet / "
                        + "office_excel_group_rows_cols / office_excel_add_pivot_table.";
                case POWERPOINT -> "\n\n[System reminder] The user currently has the presentation " + docLabel
                        + " open in Microsoft PowerPoint; the text of each slide is inlined in the system prompt's "
                        + "<active_document> and can be read and analyzed directly. "
                        + "Unless the user names another file, \"this\", \"the current deck\", \"change it\", "
                        + "and the like refer to this presentation - read and modify it exclusively via the office_ppt_* tools "
                        + "(office_ppt_get_slides / office_ppt_replace_text / office_ppt_format_text / office_ppt_add_slide / "
                        + "office_ppt_delete_slide / office_ppt_move_slide / office_ppt_add_text_box / office_ppt_add_shape / "
                        + "office_ppt_get_slide_details / office_ppt_delete_shape); writes take effect immediately "
                        + "(PowerPoint has no track-changes mechanism - deletions and edits cannot be undone from a review panel). "
                        + "Tables use office_ppt_add_table / office_ppt_table_read / office_ppt_table_set_cell; "
                        + "hyperlinks use office_ppt_set_hyperlink.";
                default -> "\n\n[System reminder] The user currently has the document " + docLabel
                        + " open in Microsoft Word; its body text is inlined in the system prompt's <active_document> "
                        + "and can be read and analyzed directly. "
                        + "Unless the user names another document, \"this\", \"the current document\", \"revise it\", "
                        + "and the like refer to this document - to modify it, call the office_* tools "
                        + "(office_replace_text / office_insert_text / office_add_comment / office_format_text / "
                        + "office_set_paragraph_format / office_set_numbering / office_format_table / "
                        + "office_apply_standard_format, etc.), which write to Word, and edits appear as native Word "
                        + "tracked changes. Document formatting (font / size / line spacing / indentation / alignment / "
                        + "underline / strikethrough / automatic numbering / table borders; whole-document house-style "
                        + "formatting via office_apply_standard_format) uses office_format_text and "
                        + "office_set_paragraph_format; tables are built and edited with office_insert_table / "
                        + "office_table_read / office_table_set_cell / office_table_add_row / office_table_delete_row / "
                        + "office_table_add_col / office_table_delete_col; page and section breaks use office_insert_break; "
                        + "hyperlinks use office_set_hyperlink; headers and footers (first section only) use "
                        + "office_edit_header_footer; comments use office_get_comments / office_reply_comment / "
                        + "office_resolve_comment; to accept or reject revisions, office_get_revisions first, then "
                        + "office_accept_revision / office_reject_revision; footnotes and endnotes use "
                        + "office_insert_footnote / office_insert_endnote; image insertion uses office_insert_image; "
                        + "named styles use office_apply_style; content controls use office_manage_content_control; "
                        + "document properties use office_set_document_properties.";
            };
            case NONE -> "\n\n[System reminder] The document the user is currently viewing is " + docLabel
                    + "; its body text is in the system prompt's <active_document> and is for reading and analysis only. "
                    + "This session's client has no document-editing executor: give your conclusions or suggested edits "
                    + "in prose, and do not attempt to call document-editing tools.";
            default -> switch (lowaDocKind(activeContext)) {
                case "sheet" -> "\n\n[System reminder] The editor currently has the spreadsheet " + docLabel
                        + " (id=" + activeContext.getId() + ") open; its structure and content are in the system prompt's "
                        + "<active_document>. Unless the user names another document, \"this\", \"the current spreadsheet\", "
                        + "\"change it\", and the like refer to it - operate on it directly with the sheet_* tools "
                        + "(Calc has no track-changes mechanism; writes take effect immediately). "
                        + "Calling doc_list_project_files or doc_open_file to rediscover or reopen it is **FORBIDDEN**.";
                case "slide" -> "\n\n[System reminder] The editor currently has the presentation " + docLabel
                        + " (id=" + activeContext.getId() + ") open; its structure and content are in the system prompt's "
                        + "<active_document>. Unless the user names another document, \"this\", \"the current deck\", "
                        + "\"change it\", and the like refer to it - operate on it directly with the slide_* tools "
                        + "(presentations have no track-changes mechanism; writes take effect immediately; roll back "
                        + "mistakes with doc_restore_checkpoint). Slide and shape structure (add/delete/move slides, "
                        + "set layouts, insert text boxes and shapes, delete shapes, adjust position and size) uses "
                        + "slide_add_page / slide_delete_page / slide_move_page / slide_set_layout / slide_add_text_box / "
                        + "slide_add_shape / slide_delete_shape / slide_set_shape_geometry; text formatting uses "
                        + "slide_format_text; shape styling uses slide_format_shape; tables use slide_add_table / "
                        + "slide_table_read / slide_table_set_cell / slide_table_set_style; hyperlinks use "
                        + "slide_set_hyperlink. "
                        + "Calling doc_list_project_files or doc_open_file to rediscover or reopen it is **FORBIDDEN**.";
                case "text" -> "\n\n[System reminder] The user currently has the plain-text file " + docLabel
                        + " (id=" + activeContext.getId() + ") open; its content is in the system prompt's "
                        + "<active_document>. Unless the user names another file, \"this\", \"the current file\", "
                        + "\"change it\", and the like refer to it - read it with extract_file_text and modify it with "
                        + "text_write_file / text_find_replace (writes take effect immediately and enter the version "
                        + "history). Calling doc_* / sheet_* / slide_* tools on it is **FORBIDDEN**.";
                default -> "\n\n[System reminder] The editor currently has the document " + docLabel
                        + " (id=" + activeContext.getId() + ") open; its body text is in the system prompt's "
                        + "<active_document>. Unless the user names another document, \"this\", \"the current document\", "
                        + "\"revise it\", and the like refer to it - operate on it directly with the doc_* tools. "
                        + "Calling doc_list_project_files or doc_open_file to rediscover or reopen it is **FORBIDDEN**.";
            };
        };
    }
}
