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

    // 记忆系统组件（读侧：写侧见 MemoryPipelineService）
    private final MemoryManager memoryManager;
    private final ContextCompressor contextCompressor;

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

        // 1. Build Dynamic System Prompt
        StringBuilder systemText = new StringBuilder();
        
        // Load Base Prompt
        try {
            org.springframework.core.io.ClassPathResource resource = new org.springframework.core.io.ClassPathResource("prompts/system_prompt.md");
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
        String enforcement = """

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
- **STOP ONLY** when you output `<artifact type="implementation_plan">`. Wait for user approval.
- **DO NOT STOP** for `<artifact type="task_list">` - continue execution immediately after.
- `<walkthrough>` does NOT trigger stop. It is only a brief summary.

## Output Structure (REQUIRED ORDER)
1. `<thinking>` - Brief intent analysis (always required)
2. `<title>` - Session title (complex tasks only)
3. `<process>` - Tool invocations (if any)
4. `<artifact>` - Only `implementation_plan` or `task_list` (if applicable)
5. `<final>` - **MAIN ANSWER** (REQUIRED for all non-chitchat responses)
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
        systemText.append(getModeConstraints(agentMode));

        // [Injection] Skill（Phase 3B，规范见 docs/SKILL_SPEC.md）：
        // 用户输入命中触发词时把 skill 的 prompt 模板注入本轮系统消息。
        // ASK 模式跳过（skill 指引以工具流程为主，与 ASK 禁用工具的约束冲突）；未命中不注入（行为保持）。
        if (agentMode != AgentMode.ASK) {
            skillRouter.match(userPrompt)
                    .ifPresent(skill -> systemText.append(skillRouter.promptInjectionFor(skill)));
        }

        // [Injection] State with Phase
        systemText.append("\n\n# Current Context\n[SYSTEM INJECTION]");
        
        // CRITICAL: Inject current system time (important for legal/financial data accuracy)
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        String formattedTime = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss (EEEE)", java.util.Locale.CHINESE));
        systemText.append("\n- **Current System Time**: ").append(formattedTime);
        systemText.append("\n  - 这是当前真实时间，所有涉及\"最新\"、\"最近\"、\"当前\"的数据查询必须基于此时间判断。");
        systemText.append("\n  - 如果查询的数据日期早于此时间超过合理范围（如股票收盘价应为最近交易日），请明确告知用户数据的实际日期。");
        
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
                    systemText.append(content != null ? fenceSafe(content) : "[Empty or unreadable file]");
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

            String content = resolveActiveDocumentContent(activeContext);
            ClientCapabilityService.Capability capability = clientCapabilityService.capabilityOf(conversationId);

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
                            systemText.append("（Excel 没有修订机制）。本会话没有 doc_* / sheet_* 工具，也没有 Word 面的 office_* 工具。\n\n");
                        }
                        case POWERPOINT -> {
                            systemText.append("该演示文稿在用户本机的 Microsoft PowerPoint 中打开，各页文本已随本请求内联注入下方。");
                            systemText.append("读取/修改它一律使用 office_ppt_* 工具（office_ppt_get_slides / ");
                            systemText.append("office_ppt_replace_text），替换直接生效（PowerPoint 没有修订机制）。");
                            systemText.append("本会话没有 doc_* 工具，也没有 Word 面的 office_* 工具。\n\n");
                        }
                        default -> {
                            systemText.append("该文档在用户本机的 Microsoft Word 中打开，正文已随本请求内联注入下方。");
                            systemText.append("读取/修改它一律使用 office_* 工具（office_get_text / office_search / ");
                            systemText.append("office_replace_text / office_insert_text / office_add_comment 等），");
                            systemText.append("修改会以 Word 原生修订形式呈现。本会话没有 doc_* 工具。\n\n");
                        }
                    }
                }
                case NONE -> {
                    systemText.append("当前客户端没有文档编辑执行器：正文仅供阅读分析，");
                    systemText.append("请以文字形式给出分析结论或修改建议，不要尝试调用文档编辑工具。\n\n");
                }
                default -> {
                    systemText.append("所有 doc_* 编辑/读取工具直接作用于该文档——**无需也不要**调用 ");
                    systemText.append("`doc_list_project_files` 或 `doc_open_file` 去重新发现/打开它；");
                    systemText.append("只有用户明确要操作**其他**文档时才需要那两个工具。\n\n");
                }
            }

            if (content != null && !content.isEmpty()) {
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
                String readHint = switch (capability) {
                    case OFFICE -> switch (clientCapabilityService.officeHostOf(conversationId)) {
                        case EXCEL -> "[内容暂不可读，可用 office_excel_get_range 直接读取]";
                        case POWERPOINT -> "[内容暂不可读，可用 office_ppt_get_slides 直接读取]";
                        default -> "[正文暂不可读，可用 office_get_text 直接读取]";
                    };
                    case NONE -> "[正文暂不可读]";
                    default -> "[正文暂不可读，可用 doc_get_document_text 直接分段读取]";
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
                systemText.append("\n\n# 项目记忆（长期记忆）\n");
                systemText.append(pm.toCoreContext());
            }
            
            // 注入相关的结构化记忆
            List<MemoryEntry> relevantMemories = memoryManager.retrieveMemories(
                    projectIdLong, userPrompt, null, 5);
            if (!relevantMemories.isEmpty()) {
                systemText.append("\n\n# 相关记忆（证据账本）\n");
                systemText.append(memoryManager.formatAsEvidenceLedger(relevantMemories));
            }
        }

        // 3. 注入用户级记忆（跨项目：偏好、行文习惯、常用表达）
        if (userId != null) {
            List<MemoryEntry> userMemories = memoryManager.retrieveUserMemories(userId, 5);
            if (!userMemories.isEmpty()) {
                systemText.append("\n\n# 用户偏好与习惯（跨项目记忆）\n");
                systemText.append("以下是该用户长期积累的偏好与习惯，输出时应遵循：\n");
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
        String docLabel = activeDocDisplayName(activeContext.getName());
        return switch (capability) {
            case OFFICE -> switch (officeHost) {
                case EXCEL -> "\n\n[系统提醒] 用户此刻在 Microsoft Excel 中打开着工作簿" + docLabel + "，"
                        + "活动工作表内容已内联注入 system prompt 的 <active_document>，可直接阅读分析。"
                        + "用户未指明别的文件时，「这个」「当前表格」「改一下」等都指它——"
                        + "读取/修改一律调用 office_excel_* 工具（office_excel_get_range / "
                        + "office_excel_set_values / office_excel_search），写入直接生效（Excel 没有修订机制）。";
                case POWERPOINT -> "\n\n[系统提醒] 用户此刻在 Microsoft PowerPoint 中打开着演示文稿" + docLabel + "，"
                        + "各页文本已内联注入 system prompt 的 <active_document>，可直接阅读分析。"
                        + "用户未指明别的文件时，「这个」「当前演示文稿」「改一下」等都指它——"
                        + "读取/修改一律调用 office_ppt_* 工具（office_ppt_get_slides / "
                        + "office_ppt_replace_text），替换直接生效（PowerPoint 没有修订机制）。";
                default -> "\n\n[系统提醒] 用户此刻在 Microsoft Word 中打开着文档" + docLabel + "，"
                        + "其正文已内联注入 system prompt 的 <active_document>，可直接阅读分析。"
                        + "用户未指明别的文档时，「这个」「当前文档」「修订一下」等都指它——"
                        + "需要修改文档时调用 office_* 工具（office_replace_text / office_insert_text / "
                        + "office_add_comment 等）落到 Word，修改会以 Word 原生修订形式呈现。";
            };
            case NONE -> "\n\n[系统提醒] 用户当前查看的文档是" + docLabel + "，"
                    + "其正文见 system prompt 的 <active_document>，仅供阅读分析。"
                    + "本会话的客户端没有文档编辑执行器，请以文字形式给出结论或修改建议，"
                    + "不要尝试调用文档编辑工具。";
            default -> "\n\n[系统提醒] 编辑器中当前已打开文档" + docLabel + "（id="
                    + activeContext.getId() + "），其正文见 system prompt 的 <active_document>。"
                    + "用户未指明别的文档时，「这个」「当前文档」「修订一下」等都指它——"
                    + "直接调用 doc_* 工具操作，**禁止**再调 doc_list_project_files 或 doc_open_file 去重新发现或打开它。";
        };
    }

    /** 内联正文防滥用上限：超出即截断（客户端可随请求直接携带正文，不能无限吃内存）。 */
    private static final int MAX_INLINE_CONTENT_CHARS = 200_000;

    /**
     * 活跃文档正文来源二选一：
     * 1) 请求随带的内联正文（Office 插件等场景——文档在客户端本地，后端没有可读的 fileId）优先；
     * 2) 否则走既有 read_document(fileId) 路径。
     * 两条路径产出同格式正文，后续统一由调用方做 CDATA 包裹与 maxCharsPerFile 截断。
     */
    private String resolveActiveDocumentContent(
            com.checkba.controller.ai.AiAgentController.ContextItem activeContext) {
        String inline = activeContext.getInlineContent();
        if (inline != null && !inline.isEmpty()) {
            if (inline.length() > MAX_INLINE_CONTENT_CHARS) {
                inline = inline.substring(0, MAX_INLINE_CONTENT_CHARS)
                        + "\n... [TRUNCATED - Inline content too long]";
            }
            return inline;
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

1. **自动执行**: 可以自动调用工具完成任务，无需等待用户确认
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
}
