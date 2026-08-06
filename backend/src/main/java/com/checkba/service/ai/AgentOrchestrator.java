package com.checkba.service.ai;

import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.ai.AgentMode;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.service.ProjectAiMessageService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 核心编排器（编排层）。
 * 只负责循环控制与流程编排：
 * 1. 组装上下文（委托 ContextAssemblerService）
 * 2. 调用 LLM（委托 ChatModelFactory）
 * 3. 处理流式响应（委托 AgentStreamHandler）
 * 4. 分发工具（委托 ToolRegistry / XmlToolCallParser——编排器不感知任何具体工具）
 * 5. 维护循环、取消与增量持久化
 * 6. 循环结束后触发记忆写入管线（委托 MemoryPipelineService）
 */
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentOrchestrator.class);

    // 循环步数预算：达到上限时优雅收尾（保存进度 + 告知用户可继续），而不是静默中断
    private static final int MAX_LOOP_DEPTH = 30;
    // 同一工具+同参数连续重复调用达到该次数后，拒绝执行并要求模型换思路
    private static final int MAX_IDENTICAL_TOOL_CALLS = 3;
    // 工具连续失败达到该次数后，向模型注入强提示要求收敛
    private static final int CONSECUTIVE_FAILURE_NUDGE = 3;

    // LLM 瞬时错误（429/5xx/超时/断连）自动重试：指数退避 8/16/32s，仅在本轮
    // 尚未流出任何 token 时重放（对话状态未被污染，重放安全且用户无感知重复内容）
    private static final int MAX_LLM_RETRIES = 3;
    private static final int LLM_RETRY_BASE_SECONDS = 8;
    // 流无活动看门狗：超过该秒数没有任何 token 到达即判定本轮停滞（配合 timeout 调大后的兜底）
    private static final int STREAM_INACTIVITY_TIMEOUT_SECONDS = 180;
    private static final java.util.concurrent.ScheduledExecutorService LLM_RETRY_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "llm-retry-scheduler");
                t.setDaemon(true);
                return t;
            });

    /**
     * 单次 Agent 运行的循环守卫状态（随递归传递）：
     * 重复调用检测 + 连续失败计数，防止模型原地打转耗尽步数预算。
     */
    private static class RunGuard {
        String lastCallSignature;
        int repeatCount;
        int consecutiveFailures;
        // 本轮 LLM 调用的瞬时错误重试次数（每个成功完成的轮次清零）
        int llmRetries;
        // 截断/未闭合 <tool_code> 的纠正轮次数（防纠正本身进入死循环）
        int malformedToolRounds;
        // 当前活跃文档 ID（来自 activeContext 或 doc_open_file），用于修改前自动创建检查点
        Long activeFileId;
        // 活跃文档名（仅用于给模型的反馈文案）
        String activeFileName;
    }

    // 取消状态管理：存储被取消的会话ID
    private final Set<String> cancelledConversations = ConcurrentHashMap.newKeySet();
    // 存储当前活跃会话的已生成内容（用于取消时保存部分内容）
    private final Map<String, StringBuilder> activeStreamContent = new ConcurrentHashMap<>();
    // 本轮 ASSISTANT 消息的行 ID：同一轮内的增量保存/最终保存更新同一行，跨轮次互不覆盖
    private final Map<String, Long> activeAssistantMessageId = new ConcurrentHashMap<>();

    private final ChatModelFactory chatModelFactory;
    private final ProjectAiMessageService messageService;
    private final SseEmitterService sseEmitterService;
    private final TokenUsageService tokenUsageService;
    private final ContextAssemblerService contextAssemblerService;
    private final ToolRegistry toolRegistry;
    private final com.checkba.service.ai.skill.SkillRouter skillRouter;
    private final XmlToolCallParser xmlToolCallParser;
    private final com.checkba.service.ai.memory.MemoryPipelineService memoryPipelineService;
    private final com.checkba.service.ProjectFileService projectFileService;
    private final EditorBridgeService editorBridgeService;
    private final ConversationFileChangeService conversationFileChangeService;
    private final TodoListService todoListService;
    private final DocumentCheckpointService documentCheckpointService;
    private final AgentRunStateService agentRunStateService;
    private final com.checkba.version.WorkSessionService workSessionService;
    // 埋点（隐私红线与字段白名单见 service/telemetry；构造器变更需同步 EvalHarness）
    private final com.checkba.service.telemetry.TelemetryService telemetryService;
    private final com.checkba.service.telemetry.TelemetryTurnTracker telemetryTurnTracker;

    // ==================== 取消功能相关方法 ====================

    /**
     * 标记会话为已取消
     */
    public void setCancelled(String conversationId) {
        log.info("Cancelling conversation: {}", conversationId);
        cancelledConversations.add(conversationId);
    }

    /**
     * 检查会话是否被取消
     */
    public boolean isCancelled(String conversationId) {
        return cancelledConversations.contains(conversationId);
    }

    /**
     * 清理取消状态
     */
    private void clearCancelledState(String conversationId) {
        cancelledConversations.remove(conversationId);
        activeStreamContent.remove(conversationId);
        activeAssistantMessageId.remove(conversationId);
    }

    /**
     * 保存/更新本轮 ASSISTANT 消息：首次保存插入新行并记住行 ID，
     * 本轮内后续（增量/最终）保存更新同一行，避免重复；新的一轮从新行开始，不会覆盖上一轮回复。
     */
    private void saveAssistantMessage(String conversationId, String projectId, Long userId, String content) {
        Long id = messageService.upsertAssistantMessage(
                projectId, userId, conversationId, activeAssistantMessageId.get(conversationId), content);
        if (id != null) {
            activeAssistantMessageId.put(conversationId, id);
        }
    }

    /**
     * 处理取消：保存已生成的部分内容
     */
    private void handleCancellation(String conversationId, String projectId, Long userId) {
        log.info("Handling cancellation for conversation: {}", conversationId);
        
        // 获取已生成的部分内容
        StringBuilder contentBuilder = activeStreamContent.get(conversationId);
        String partialContent = contentBuilder != null ? contentBuilder.toString() : "";
        
        // 如果有部分内容，保存并标记为已中断
        if (!partialContent.isEmpty()) {
            String contentToSave = partialContent + "\n\n[已中断]";
            saveAssistantMessage(conversationId, projectId, userId, contentToSave);
            log.info("Saved partial content ({} chars) for cancelled conversation: {}", partialContent.length(), conversationId);
        }
        
        agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.CANCELLED);
        // 发送取消事件
        sseEmitterService.send(conversationId, "cancelled", "{\"message\":\"用户已停止生成\"}");
        sseEmitterService.close(conversationId);
        
        // 清理状态
        clearCancelledState(conversationId);
    }
    
    /**
     * 获取指定会话的当前恢复快照 (用于断线重连)
     * 返回目前正在生成的流式内容
     */
    public String getRecoverySnapshot(String conversationId) {
        StringBuilder sb = activeStreamContent.get(conversationId);
        if (sb != null && sb.length() > 0) {
            return sb.toString();
        }
        return null;
    }

    // ==================== 工具分发（统一走 ToolRegistry，编排器不感知具体工具） ====================

    /** 埋点：工具调用结果（仅工具名/成败/耗时等枚举与数值，参数内容不采集） */
    private void recordToolTelemetry(String toolName, ToolRegistry.ToolResult result,
                                     String conversationId, long durationMs) {
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("toolName", toolName);
        attrs.put("success", result != null && result.success());
        attrs.put("durationMs", durationMs);
        if (result != null && result.tool() != null) {
            attrs.put("fromPlugin", result.tool().fromPlugin());
            if (result.tool().meta() != null && result.tool().meta().fileEffect() != null) {
                attrs.put("fileEffect", result.tool().meta().fileEffect());
            }
        }
        telemetryService.recordConv("ai.tool", conversationId, attrs);
    }

    /**
     * 分发一次工具调用并处理声明式副作用（文件变更通知、文件树刷新）。
     * 修改类工具（fileEffect=MODIFIED）执行前自动为活跃文档创建本轮检查点。
     */
    private ToolRegistry.ToolResult dispatchTool(String toolName, String argsJson,
                                                 Long projectId, String conversationId,
                                                 Long userId, String modelId, RunGuard guard) {
        // 检查点：第一个修改类工具执行前快照活跃文档（恢复类工具自身除外）
        if (guard != null && !"doc_restore_checkpoint".equals(toolName)) {
            boolean modifies = toolRegistry.resolve(toolName)
                    .map(t -> t.meta() != null && "MODIFIED".equals(t.meta().fileEffect()))
                    .orElse(false);
            if (modifies && guard.activeFileId != null) {
                documentCheckpointService.ensureCheckpoint(conversationId, guard.activeFileId);
            }
        }

        // 活跃文档确定性兜底：提示词层面的约束对弱模型不可靠（system prompt 声明与末位提醒
        // 都被无视过），这里在分发层直接拦截，不再指望模型自觉。
        if (guard != null && guard.activeFileId != null && "doc_open_file".equals(toolName)) {
            String shortCircuit = activeDocOpenShortCircuit(
                    guard.activeFileId, guard.activeFileName, extractArg(argsJson, "fileId"));
            if (shortCircuit != null) {
                log.info("[ActiveDoc] 短路 doc_open_file：目标就是已打开的活跃文档 id={}", guard.activeFileId);
                return new ToolRegistry.ToolResult(
                        shortCircuit, toolRegistry.resolve(toolName).orElse(null), true);
            }
        }

        com.checkba.service.ai.tools.ToolContext ctx =
                new com.checkba.service.ai.tools.ToolContext(projectId, conversationId, userId, modelId);
        long toolStartMs = System.currentTimeMillis();
        ToolRegistry.ToolResult result = toolRegistry.execute(toolName, argsJson, ctx);
        recordToolTelemetry(toolName, result, conversationId, System.currentTimeMillis() - toolStartMs);
        applyToolSideEffects(result, argsJson, conversationId);

        // 模型仍去列文件时，把活跃文档钉在结果里，让它下一轮自己纠回来（不阻断跨文档场景）
        if (guard != null && guard.activeFileId != null
                && "doc_list_project_files".equals(toolName) && result.success()) {
            result = new ToolRegistry.ToolResult(
                    appendActiveDocNotice(result.output(), guard.activeFileId, guard.activeFileName),
                    result.tool(), result.found());
        }

        // 跟踪活跃文档：模型中途打开新文档时，后续检查点跟着切换
        if (guard != null && "doc_open_file".equals(toolName)) {
            try {
                String fid = extractArg(argsJson, "fileId");
                if (fid != null && !fid.isEmpty()) {
                    Long opened = Long.parseLong(fid.trim());
                    guard.activeFileName = activeDocNameAfterOpen(guard.activeFileId, guard.activeFileName, opened);
                    guard.activeFileId = opened;
                }
            } catch (Exception ignore) { /* fileId 非数字时保持原值 */ }
        }
        return result;
    }

    /**
     * 模型中途 doc_open_file 后，活跃文档名该保留还是作废。
     *
     * <p>切到别的文档时必须作废：否则后续短路反馈/列表加钉会拿**旧名配新 id**，等于主动喂给
     * 模型一条错误信息。这一层拿不到新文档名，置 null 即回退为通称「当前文档」。
     */
    static String activeDocNameAfterOpen(Long previousId, String previousName, Long openedId) {
        return openedId != null && openedId.equals(previousId) ? previousName : null;
    }

    /** 活跃文档的展示名：名字缺失（含模型中途切文档后作废的情况）时回退为通称，避免出现「《null》」。 */
    private static String activeDocDisplayName(String activeFileName) {
        return (activeFileName == null || activeFileName.isBlank()) ? "当前文档" : "《" + activeFileName + "》";
    }

    /**
     * doc_open_file 打的就是当前已打开的活跃文档时，返回给模型的短路反馈；否则返回 null（正常执行）。
     *
     * <p>省掉一整轮「SSE 下发打开指令 → 等前端回执」的往返。跨文档场景（requestedFileId 指向别的
     * 文档）不受影响，照常执行。
     */
    static String activeDocOpenShortCircuit(Long activeFileId, String activeFileName, String requestedFileId) {
        if (activeFileId == null || requestedFileId == null || requestedFileId.isBlank()) {
            return null;
        }
        try {
            if (!activeFileId.equals(Long.parseLong(requestedFileId.trim()))) {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        // 返回纯文本而非手拼 JSON：doc_open_file 本身返回的就是纯文本，格式保持一致；
        // 手拼 JSON 遇到文件名里的引号/反斜杠（macOS 合法字符）会产出坏 JSON。
        return activeDocDisplayName(activeFileName) + "（id=" + activeFileId
                + "）本来就在编辑器中打开着，无需打开，可直接进行后续操作。"
                + "请直接调用 doc_* 工具对它操作，不要再调 doc_open_file 或 doc_list_project_files。";
    }

    /**
     * doc_list_project_files 的结果尾部钉上活跃文档提示，让走神的模型下一轮自己纠回来。
     */
    static String appendActiveDocNotice(String listOutput, Long activeFileId, String activeFileName) {
        if (activeFileId == null) {
            return listOutput;
        }
        return (listOutput == null ? "" : listOutput)
                + "\n\n[系统提醒] 用户此刻打开的是 " + activeDocDisplayName(activeFileName)
                + "（id=" + activeFileId + "）。"
                + "若本次任务针对的就是它，直接用 doc_* 工具操作即可，不要再调 doc_open_file。";
    }

    /**
     * 根据 @ToolMeta 元数据处理工具副作用（取代原先散落在手写分发链里的硬编码通知）。
     */
    private void applyToolSideEffects(ToolRegistry.ToolResult result, String argsJson, String conversationId) {
        if (!result.success() || result.tool() == null || result.tool().meta() == null) {
            return;
        }
        com.checkba.service.ai.tools.ToolMeta meta = result.tool().meta();
        if (meta.refreshFiles()) {
            sseEmitterService.send(conversationId, "client_action", "{\"action\":\"refresh_files\"}");
        }
        if (!meta.fileEffect().isEmpty()) {
            String fileName = meta.fileArg().isEmpty() ? null : extractArg(argsJson, meta.fileArg());
            if (fileName == null || fileName.isEmpty()) {
                fileName = "Current Document";
            }
            notifyFileChange(conversationId, fileName, meta.fileEffect());
        }
    }


    /**
     * 处理用户消息 (入口)
     */
    @Async("taskExecutor") // Run in separate thread
    public void handleUserMessage(AiAgentController.AgentChatRequest request, Long userId) {
        String conversationId = request.getConversationId();
        String projectId = String.valueOf(request.getProjectId());
        AgentMode agentMode = request.getAgentMode(); // 获取 Agent 模式
        
        // 初始化取消状态和内容收集器（新的一轮：清掉上一轮的 ASSISTANT 行 ID，本轮回复必须落新行）
        cancelledConversations.remove(conversationId);
        activeStreamContent.put(conversationId, new StringBuilder());
        activeAssistantMessageId.remove(conversationId);
        // 状态登记：循环开跑（会话列表状态点/切回续流判断都依赖它）
        // 埋点轮次上下文先于 mark 建立：终态由 AgentRunStateService.mark 单点合成 ai.turn
        java.util.Map<String, Object> turnAttrs = new java.util.HashMap<>();
        turnAttrs.put("mode", agentMode == null ? null : agentMode.name());
        turnAttrs.put("model", request.getModel());
        turnAttrs.put("attachmentCount", request.getFileIds() == null ? 0 : request.getFileIds().size());
        turnAttrs.put("hasPinnedSkill", request.getPinnedSkillId() != null && !request.getPinnedSkillId().isEmpty());
        telemetryTurnTracker.startTurn(conversationId, turnAttrs);
        agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.RUNNING);
        
        try {
            log.info("Agent Loop Started: conv={}, model={}, mode={}, msg={}", conversationId, request.getModel(), agentMode, request.getMessage());
            
            // 1. 保存用户消息 (Save only user message first; assistant saved after stream completes)
            messageService.saveMessage(
                projectId, userId, conversationId, "USER", request.getMessage()
            );
            
            // 1.1 首次对话时异步生成对话标题
            List<com.checkba.model.entity.ProjectAiMessage> existingMsgs = messageService.listByConversationId(conversationId);
            if (existingMsgs.size() <= 1) { // Only the user message we just saved
                final String convId = conversationId;
                final String userMsg = request.getMessage();
                CompletableFuture.runAsync(() -> {
                    try {
                        log.info("Generating conversation title for: {}", convId);
                        // Use a lightweight model for title generation
                        dev.langchain4j.model.chat.ChatLanguageModel titleModel = chatModelFactory.getChatModel("deepseek/deepseek-v4-flash");
                        String title = messageService.generateConversationTitle(userMsg, titleModel);
                        messageService.updateConversationTitle(convId, title);
                        log.info("Conversation title generated: {} -> {}", convId, title);
                        // Notify frontend of title update
                        sseEmitterService.send(convId, "title_update", "{\"title\":\"" + title.replace("\"", "\\\"").replace("\n", " ") + "\"}");
                    } catch (Exception e) {
                        log.warn("Failed to generate conversation title for {}", convId, e);
                    }
                });
            }
            
            // 1.2 Skill 激活（Phase 3B）：用户钉选优先，否则触发词匹配；都未命中时行为与现状一致
            skillRouter.activateForTurn(conversationId, request.getMessage(), request.getPinnedSkillId());

            // 2. Build Context & History Message Stack (Spec v1.8)
            log.info("Assembling full message context for conversation: {}", conversationId);
            // TODO: Get taskListId/planId from session if available
            String taskListId = null; 
            String planId = null;
            
            java.util.List<dev.langchain4j.data.message.ChatMessage> messages = contextAssemblerService.assemble(
                conversationId, 
                request.getMessage(), 
                request.getContextItems() != null ? request.getContextItems() : 
                    convertFileIdsToContextItems(request.getFileIds()),
                request.getActiveContext(), // NEW: Pass active document context
                taskListId,
                planId,
                projectId,
                agentMode,
                userId,
                request.getModel()
            );
            
            log.info("Message assembly complete. Total messages: {}", messages.size());
            log.debug("Detailed Message Stack:");
            for (dev.langchain4j.data.message.ChatMessage m : messages) {
                log.debug("  - Role: {}, Content length: {}", m.type(), m.text().length());
            }

            // 3. 获取流式模型
            log.info("Getting streaming model: {}", request.getModel());
            StreamingChatLanguageModel model = chatModelFactory.getStreamingChatModel(request.getModel());
            
            if (model == null) {
                throw new RuntimeException("Could not create streaming model for ID: " + request.getModel());
            }

            // 4. Start Loop
            log.info("Starting runLoop for conversation: {}, mode: {}", conversationId, agentMode);
            // Track tool executions for history persistence
            StringBuilder executionLog = new StringBuilder();
            RunGuard guard = new RunGuard();
            // 记录活跃文档 ID（修改前自动检查点的目标）；每轮一个独立检查点
            documentCheckpointService.clearForNewRun(conversationId);
            if (request.getActiveContext() != null && request.getActiveContext().getId() != null) {
                try {
                    guard.activeFileId = Long.parseLong(request.getActiveContext().getId().trim());
                    guard.activeFileName = request.getActiveContext().getName();
                } catch (NumberFormatException ignore) { /* 非数字 ID（如临时文件）不做检查点 */ }
            }
            runLoop(model, messages, conversationId, projectId, userId, request.getModel(), 0, executionLog, agentMode, guard);

        } catch (com.checkba.service.account.AccountException e) {
            // 账户/额度类失败不是「内部错误」，而是用户可自行处理的状态
            // （如「请先在官网账户页分配 AI 额度」）。原样透出中文文案，不加英文前缀，
            // 也不打 ERROR 级日志——这条路径在未分配额度时每发一条消息都会走到
            log.info("平台通道不可用 [{}]，会话 {}: {}", e.getKind(), conversationId, e.getMessage());
            agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.ERROR);
            sseEmitterService.send(conversationId, "error", e.getMessage());
            sseEmitterService.close(conversationId);
        } catch (Exception e) {
            log.error("Agent Loop Error for conversation: " + conversationId, e);
            agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.ERROR);
            sseEmitterService.send(conversationId, "error", "Internal Error: " + e.getMessage());
            sseEmitterService.close(conversationId);
        }
    }

    private void runLoop(StreamingChatLanguageModel model,
                         java.util.List<dev.langchain4j.data.message.ChatMessage> messages,
                         String conversationId, String projectId, Long userId, String modelId, int depth,
                         StringBuilder executionLog, AgentMode agentMode, RunGuard guard) {

        // 检查是否被取消
        if (isCancelled(conversationId)) {
            log.info("Conversation {} was cancelled, stopping loop at depth {}", conversationId, depth);
            handleCancellation(conversationId, projectId, userId);
            return;
        }

        if (depth > MAX_LOOP_DEPTH) {
            // 步数预算耗尽：不是报错，而是"存档 + 请示"——保存进度、明确告知用户、干净收尾。
            log.warn("Agent loop reached max depth {} for conversation {}, stopping gracefully", MAX_LOOP_DEPTH, conversationId);
            String notice = "\n\n> 本轮已达最大执行步数（" + MAX_LOOP_DEPTH + " 步），先暂停。已完成的修改均已生效，点击下方「继续」按钮可接着执行剩余任务。";
            sendTextDelta(conversationId, notice);
            String persisted = (executionLog.length() > 0 ? executionLog.toString() : "") + notice;
            saveAssistantMessage(conversationId, projectId, userId, persisted);
            agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.PAUSED);
            // status=paused 让前端渲染一键「继续」按钮（区别于 finished 的正常收尾）
            sseEmitterService.send(conversationId, "bubble_end", "{\"status\":\"paused\",\"reason\":\"max_depth\"}");
            sseEmitterService.close(conversationId);
            clearCancelledState(conversationId);
            return;
        }
        
        // Ask 模式限制递归深度为 1（不允许工具调用后的循环）
        if (agentMode == AgentMode.ASK && depth > 0) {
            log.info("Ask mode: stopping loop at depth {}", depth);
            agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.FINISHED);
            sseEmitterService.send(conversationId, "bubble_end", "{}");
            sseEmitterService.close(conversationId);
            clearCancelledState(conversationId);
            return;
        }
        
        // 设置当前会话 ID 到 EditorBridgeService，以便文档编辑工具可以发送 SSE 事件
        editorBridgeService.setCurrentConversationId(conversationId);

        AgentStreamHandler handler = new AgentStreamHandler(
            sseEmitterService, 
            conversationId, 
            tokenUsageService, 
            projectId, 
            userId, 
            modelId
        );
        

        // 实时更新当前生成的内容 (用于断线重连恢复)
        handler.setOnToken(token -> {
            StringBuilder sb = activeStreamContent.get(conversationId);
            if (sb != null) {
                sb.append(token);
            }
        });

        // 编辑器实时流式写入拦截（双轨迁移：新名 doc_stream_data 必须先于旧名 wps_stream_data 发出，
        // 前端以"先见新名"判定新后端并丢弃旧名去重；一个发布周期后摘旧名，见 docs/AI_ARCHITECTURE.md Phase 3）
        handler.setOnEditorStream(token -> {
            if (editorBridgeService.isStreamingMode(conversationId)) {
                sseEmitterService.send(conversationId, "doc_stream_data", java.util.Map.of("content", token));
                sseEmitterService.send(conversationId, "wps_stream_data", java.util.Map.of("content", token));
            }
        });
        
        // Callback for Loop
        handler.setOnComplete(response -> {
          try {
            // 本轮成功完成：清零瞬时错误重试预算（重试额度按轮计，不跨轮累积）
            guard.llmRetries = 0;
            // Unconditionally turn off streaming mode when generation ends
            boolean wasStreaming = editorBridgeService.isStreamingMode(conversationId);
            editorBridgeService.setStreamingMode(conversationId, false);
            // 通知前端流式写入结束：消费端冲掉缓冲后命令 worker 收尾
            //（写掉未换行的尾行/未闭合的尾表并复位 markdown 转换状态机）
            if (wasStreaming) {
                sseEmitterService.send(conversationId, "doc_stream_end", java.util.Map.of("status", "finished"));
            }

            // 检查是否被取消
            if (isCancelled(conversationId)) {
                log.info("Conversation {} was cancelled during streaming", conversationId);
                handleCancellation(conversationId, projectId, userId);
                return;
            }
            
            // 确保在回调线程中也能访问 conversationId（解决 ThreadLocal 线程隔离问题）
            editorBridgeService.setCurrentConversationId(conversationId);
            
            dev.langchain4j.data.message.AiMessage aiMessage = response.content();
            messages.add(aiMessage);

            // 注意：本轮生成内容已由 onToken 回调逐 token 累加进 activeStreamContent，
            // 此处不可再 append aiMessage.text()，否则取消/断线恢复的快照内容会翻倍。
            String aiContent = aiMessage.text();

            // 1. Check for Native Tool Requests (Priority 1)
            if (aiMessage.hasToolExecutionRequests()) {
                log.info("Detected Native Tool Requests: {}", aiMessage.toolExecutionRequests());

                // Execute Native Tools (统一分发，无需感知具体工具)
                for (dev.langchain4j.agent.tool.ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    // 面板可见性：原生工具调用复用 <process> XML 协议推送给前端，
                    // 与模型自发的 XML tool_code 走同一套任务卡渲染管线（修复：原生轮次面板无任何输出，看似卡死）
                    String displayName = toolRegistry.resolve(req.name())
                            .map(ToolRegistry.RegisteredTool::displayName)
                            .orElse(req.name());
                    sendTextDelta(conversationId, String.format("<process name=\"%s\"><tool_code>%s(%s)</tool_code></process>",
                            displayName.replace("\"", "'"), req.name(), truncate(req.arguments(), 200)));

                    String result;
                    boolean success;
                    String guardVerdict = checkRepeatedCall(guard, req.name(), req.arguments());
                    if (guardVerdict != null) {
                        // 重复调用熔断：不执行，直接把守卫反馈作为工具结果回给模型
                        log.warn("Loop guard tripped for {}: tool={} repeated {} times", conversationId, req.name(), guard.repeatCount);
                        result = guardVerdict;
                        success = false;
                    } else {
                        ToolRegistry.ToolResult toolResult = dispatchTool(req.name(), req.arguments(),
                                Long.parseLong(projectId), conversationId, userId, modelId, guard);
                        result = toolResult.output();
                        success = toolResult.success();
                    }
                    result = appendFailureNudge(guard, result, success);
                    messages.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(req, result));

                    // Determine status for history and display
                    String nativeToolStatus = success ? "SUCCESS" : "FAILURE";
                    sendTextDelta(conversationId, String.format("<tool_output status=\"%s\">%s</tool_output>",
                            nativeToolStatus, truncate(result, 4000)));

                    // Log for history persistence (include status attribute)
                    executionLog.append(String.format("<process name=\"%s\"><tool_code>%s(%s)</tool_code><tool_output status=\"%s\">%s</tool_output></process>\n",
                        displayName.replace("\"", "'"), req.name(), req.arguments(), nativeToolStatus, result));
                }

                // 防走神注入（Claude Code system-reminder 模式）：每次工具执行后带上任务清单状态，
                // 防止长任务中模型忘记目标。刚更新过清单的轮次不注入（避免重复唠叨）。
                boolean justWroteTodo = aiMessage.toolExecutionRequests().stream()
                        .anyMatch(r -> "todo_write".equals(r.name()));
                if (!justWroteTodo) {
                    String reminder = todoListService.reminder(conversationId);
                    if (reminder != null) {
                        messages.add(dev.langchain4j.data.message.UserMessage.from("[系统提醒] " + reminder));
                    }
                }

                // 增量保存：在工具执行后立即保存AI消息和工具输出，防止对话中断导致上下文丢失
                String intermediateContent = (aiContent != null ? aiContent : "") + "\n" + executionLog.toString();
                saveAssistantMessage(conversationId, projectId, userId, intermediateContent);
                log.info("Intermediate save after native tool execution for conversation: {}", conversationId);

                runLoop(model, messages, conversationId, projectId, userId, modelId, depth + 1, executionLog, agentMode, guard);
                return;
            }
            
            String content = aiMessage.text();
            if (content == null) content = "";

            // 2. Check for XML Tool Requests (Fallback for Root Bubble Protocol)
            // Pattern: <tool_code>legal_tools.method(args)</tool_code> OR <code>...</code>
            // We need to parse this manually because we forced XML output in System Prompt.
            if (agentMode != AgentMode.ASK && xmlToolCallParser.containsToolCall(content)) {
                log.info("Detected XML Tool Code in content. Parsing...");

                // 提取LLM选择的process name，用于历史记录保存时保持一致性
                String llmProcessName = xmlToolCallParser.extractProcessName(content).orElse(null);

                boolean toolExecuted = false;

                for (XmlToolCallParser.ParsedCall call : xmlToolCallParser.parse(content)) {
                    String code = call.rawCode();
                    log.info("Parsed Tool Code: {}", code);

                    String result;
                    boolean xmlToolSuccess;
                    ToolRegistry.ToolResult toolResult = null;
                    String guardVerdict = checkRepeatedCall(guard, call.toolName(), call.argsJson());
                    if (guardVerdict != null) {
                        // 重复调用熔断：不执行，直接把守卫反馈作为工具结果回给模型
                        log.warn("Loop guard tripped for {}: tool={} repeated {} times", conversationId, call.toolName(), guard.repeatCount);
                        result = guardVerdict;
                        xmlToolSuccess = false;
                    } else {
                        toolResult = dispatchTool(call.toolName(), call.argsJson(),
                                Long.parseLong(projectId), conversationId, userId, modelId, guard);
                        result = toolResult.found()
                                ? toolResult.output()
                                : "Unknown tool in custom parser: " + code;
                        xmlToolSuccess = toolResult.found() && toolResult.success();
                    }
                    result = appendFailureNudge(guard, result, xmlToolSuccess);

                    // Add Result to History
                    String statusPrefix = xmlToolSuccess ? "SUCCESS" : "FAILURE";

                    // Enhancement for Write Tools: Append explicit success for file creation/modification
                    // The model often sees JSON IDs (wps_file_id) and thinks it failed or needs to do more.
                    if ("SUCCESS".equals(statusPrefix) && call.toolName().startsWith("write_")) {
                         result += "\n\n(System Note: File operation completed successfully.)";
                    }

                    // Explicitly tell the model to EVALUATE - with strict anti-over-execution instructions
                    String feedbackMsg = String.format("[System Tool Execution Log]\nTool: %s\nStatus: %s\nOutput: %s\n\n(CRITICAL INSTRUCTION: The tool executed successfully. Now compare with the ORIGINAL user request. If the SPECIFIC task the user asked for is complete, output `<final>` IMMEDIATELY. DO NOT perform additional operations unless the user EXPLICITLY requested them. For example, if user asked to 'delete the 3rd z' and you deleted it, you are DONE - do not delete other z's.)",
                        code, statusPrefix, result);

                    // 防走神注入：任务清单状态随工具反馈一起带回（刚更新清单的轮次不注入）
                    if (!"todo_write".equals(call.toolName())) {
                        String reminder = todoListService.reminder(conversationId);
                        if (reminder != null) {
                            feedbackMsg += "\n\n[系统提醒] " + reminder;
                        }
                    }

                    messages.add(dev.langchain4j.data.message.UserMessage.from(feedbackMsg));

                    // Log for history persistence (include status attribute)
                    // 优先使用LLM选择的process name，否则用工具元数据里的中文显示名
                    String processNameForLog = (llmProcessName != null && !llmProcessName.isEmpty())
                        ? llmProcessName
                        : (toolResult != null && toolResult.tool() != null ? toolResult.tool().displayName() : "工具执行");
                    executionLog.append(String.format("<process name=\"%s\"><tool_code>%s</tool_code><tool_output status=\"%s\">%s</tool_output></process>\n",
                        processNameForLog, code, statusPrefix, result));

                    // Emit explicit tool_output for frontend parser with status attribute
                    // NOTE: Do NOT wrap in <process> - the tool_output belongs to the existing process
                    // that contained the tool_code.
                    String toolOutputXml = String.format("<tool_output status=\"%s\">%s</tool_output>",
                        statusPrefix, result);
                    sseEmitterService.send(conversationId, "text_delta", "{\"content\":\"" + toolOutputXml.replace("\"", "\\\"").replace("\n", "\\n") + "\"}");

                    toolExecuted = true;
                }

                if (toolExecuted) {
                     // 增量保存：在XML工具执行后立即保存AI消息和工具输出，防止对话中断导致上下文丢失
                     String intermediateXmlContent = content + "\n" + executionLog.toString();
                     saveAssistantMessage(conversationId, projectId, userId, intermediateXmlContent);
                     log.info("Intermediate save after XML tool execution for conversation: {}", conversationId);

                     // Recurse with executionLog
                     runLoop(model, messages, conversationId, projectId, userId, modelId, depth + 1, executionLog, agentMode, guard);
                     return;
                }
            }

            // 2.5 截断的工具调用（F-10）：输出里有 <tool_code> 却没有闭合标签——多为
            // max_tokens/上游截断，解析器提不出任何调用。此前会落到"正常收尾"静默结束，
            // 任务做一半、无错误提示、无继续按钮。这里回喂提示让模型重发，最多纠正 2 轮。
            if (agentMode != AgentMode.ASK
                    && content.contains("<tool_code>") && !content.contains("</tool_code>")) {
                if (guard.malformedToolRounds < 2) {
                    guard.malformedToolRounds++;
                    log.warn("Truncated tool_code detected for {} (correction round {}), asking model to re-emit",
                            conversationId, guard.malformedToolRounds);
                    messages.add(dev.langchain4j.data.message.UserMessage.from(
                            "[系统提醒] 你上一条输出中的 <tool_code> 标签未闭合（内容可能被截断），"
                            + "该工具调用没有被执行。请重新、完整地输出这次工具调用；"
                            + "若任务其实已完成，请直接输出最终总结。"));
                    runLoop(model, messages, conversationId, projectId, userId, modelId,
                            depth + 1, executionLog, agentMode, guard);
                    return;
                }
                log.warn("Truncated tool_code persisted after corrections for {}, finishing normally", conversationId);
            }

            // 3. Check for Artifacts
            // - Task List: Do NOT stop loop anymore (User Requirement). Backend maintains it or just logs it.
            // - Implementation Plan: STOP LOOP for approval.
            
            // FIRST: Strip any markdown code block wrappers that LLM may have added
            String cleanedContent = content;
            cleanedContent = cleanedContent.replaceAll("^```(?:xml|html|markdown)?\\s*\\n?", "");
            cleanedContent = cleanedContent.replaceAll("\\n?```\\s*$", "");
            cleanedContent = cleanedContent.replaceAll("```(?:xml|html|markdown)?\\s*\\n", "");
            cleanedContent = cleanedContent.replaceAll("\\n```", "");
            
            if (cleanedContent.contains("<artifact") && (cleanedContent.contains("type=\"implementation_plan\"") || cleanedContent.contains("type=\"task_list\""))) {
                // Parse full artifact
                String type = "unknown";
                if (cleanedContent.contains("type=\"implementation_plan\"")) type = "implementation_plan";
                else if (cleanedContent.contains("type=\"task_list\"")) type = "task_list";
                
                // Extract name attribute if present
                String artifactName = null;
                java.util.regex.Pattern namePattern = java.util.regex.Pattern.compile("<artifact[^>]*name=\"([^\"]+)\"[^>]*>");
                java.util.regex.Matcher nameMatcher = namePattern.matcher(cleanedContent);
                if (nameMatcher.find()) {
                    artifactName = nameMatcher.group(1).trim();
                    // Sanitize for filename (max 30 chars, remove special chars)
                    artifactName = artifactName.replaceAll("[/\\\\:*?\"<>|]", "_");
                    if (artifactName.length() > 30) artifactName = artifactName.substring(0, 30);
                }
                
                // Extract Content inside tags
                String artifactContent = "";
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("<artifact[^>]*>([\\s\\S]*?)</artifact>");
                java.util.regex.Matcher m = p.matcher(cleanedContent);
                if (m.find()) {
                    artifactContent = m.group(1).trim();
                } else {
                     // Fallback: Try to extract everything after the opening artifact tag
                     int start = cleanedContent.indexOf(">" , cleanedContent.indexOf("<artifact"));
                     int end = cleanedContent.indexOf("</artifact>");
                     if (start > 0 && end > start) {
                         artifactContent = cleanedContent.substring(start + 1, end).trim();
                     } else {
                         artifactContent = cleanedContent; // Last resort fallback
                     }
                }
                
                // Determine filename: prefer extracted name, fallback to default
                String filename;
                if (artifactName != null && !artifactName.isEmpty()) {
                    filename = artifactName + ".md";
                } else {
                    filename = (type.equals("task_list") ? "Task List" : "Plan") + ".md";
                }
                
                log.info("Artifact detected: type={}, name={}, contentLength={}", type, filename, artifactContent.length());
                
                try {
                     projectFileService.saveArtifactFile(Long.valueOf(projectId), conversationId, filename, artifactContent, userId);
                     log.info("Artifact Saved: path=AI Assistant Files/{}/{}", conversationId, filename);
                } catch (Exception e) {
                     log.error("Failed to save artifact file", e);
                }

                if (type.equals("implementation_plan")) {
                    log.info("Detected Implementation Plan. STOPPING LOOP for user approval.");
                    // Save assistant message with execution log prepended
                    String fullContent = executionLog.length() > 0 ? executionLog.toString() + content : content;
                    saveAssistantMessage(conversationId, projectId, userId, fullContent);
                    agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.AWAITING_APPROVAL);
                    // 发送 bubble_end 表示当前响应结束（等待用户审批）
                    sseEmitterService.send(conversationId, "bubble_end", "{\"status\":\"awaiting_approval\"}");
                    sseEmitterService.close(conversationId);
                    clearCancelledState(conversationId);
                    return; // Stop and wait for user action
                }
            }
            
            // 3.1 Check for Title (Update Conversation Title)
            // Pattern: <title>Title Content</title>
            if (content.contains("<title>")) {
                java.util.regex.Pattern pTitle = java.util.regex.Pattern.compile("<title>([\\s\\S]*?)</title>");
                java.util.regex.Matcher mTitle = pTitle.matcher(content);
                if (mTitle.find()) {
                    String newTitle = mTitle.group(1).trim();
                    if (!newTitle.isEmpty()) {
                        // Truncate to 30 chars for folder safety
                        if (newTitle.length() > 30) newTitle = newTitle.substring(0, 30);
                        
                        log.info("Updating Conversation Title to: {}", newTitle);
                        try {
                            // Update Folder Name in "AI Assistant Files"
                            projectFileService.renameConversationFolder(conversationId, newTitle, userId);
                        } catch (Exception e) {
                             log.warn("Failed to update conversation folder title", e);
                        }
                    }
                }
            }

            // 4. Default: Loop Finished
            log.info("Agent Loop Finished for {}", conversationId);
            if (!content.isEmpty()) {
                // Prepend execution log for history persistence
                String fullContent = executionLog.length() > 0 ? executionLog.toString() + content : content;
                saveAssistantMessage(conversationId, projectId, userId, fullContent);
            }
            // 触发记忆写入管线（异步：对话摘要 / 项目记忆 / MemCell 原子记忆提取）
            try {
                memoryPipelineService.onConversationTurnCompleted(
                        conversationId, projectId, userId, new java.util.ArrayList<>(messages));
            } catch (Exception memEx) {
                log.warn("Failed to trigger memory pipeline for {}", conversationId, memEx);
            }
            // 版本记录：AI 轮次真正结束后落一笔 AI 署名存档（失败绝不阻断）
            try {
                workSessionService.commitAiRound(Long.parseLong(projectId), userId);
            } catch (Exception vEx) {
                log.warn("AI 轮次版本落档失败: project={}", projectId, vEx);
            }
            agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.FINISHED);
            // 发送 bubble_end 表示整个循环真正结束
            sseEmitterService.send(conversationId, "bubble_end", "{\"status\":\"finished\"}");
            sseEmitterService.close(conversationId);
            // 清理取消状态
            clearCancelledState(conversationId);
            activeStreamContent.remove(conversationId); // CLEANUP
          } catch (Exception e) {
            // 确保异常时也能正确结束 bubble，避免前端一直显示加载状态
            log.error("Error in onComplete callback for conversation: " + conversationId, e);
            agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.ERROR);
            sseEmitterService.send(conversationId, "error", "Callback Error: " + e.getMessage());
            sseEmitterService.close(conversationId);
            clearCancelledState(conversationId);
            activeStreamContent.remove(conversationId); // CLEANUP
          } finally {
            // 流式回调运行在可复用线程池线程上，用完清理 ThreadLocal，防止会话串号
            editorBridgeService.clearCurrentConversationId();
          }
        });

        // 流式出错处置：瞬时错误且零 token 已流出 → 指数退避自动重试本轮；
        // 否则终态清理（关 emitter + 复位状态，避免 SSE 挂到超时、前端永久加载）
        handler.setOnError(err -> {
            boolean retryable = !handler.hasStreamedTokens()
                    && guard.llmRetries < MAX_LLM_RETRIES
                    && isTransientLlmError(err)
                    && !isCancelled(conversationId);
            if (retryable) {
                int attempt = ++guard.llmRetries;
                long delaySec = (long) LLM_RETRY_BASE_SECONDS << (attempt - 1); // 8/16/32s
                log.warn("Transient LLM error for {} (attempt {}/{}), retrying in {}s: {}",
                        conversationId, attempt, MAX_LLM_RETRIES, delaySec, String.valueOf(err));
                sendTextDelta(conversationId, String.format(
                        "\n\n> 模型服务暂时不可用，%d 秒后自动重试（第 %d/%d 次）…\n\n",
                        delaySec, attempt, MAX_LLM_RETRIES));
                LLM_RETRY_SCHEDULER.schedule(() -> {
                    try {
                        // 同 depth 重放本轮：messages 只在 onComplete 里被追加，失败轮未污染上下文
                        runLoop(model, messages, conversationId, projectId, userId, modelId,
                                depth, executionLog, agentMode, guard);
                    } catch (Exception retryEx) {
                        log.error("Retry runLoop failed for {}", conversationId, retryEx);
                        handleStreamErrorTerminal(conversationId, projectId, userId, retryEx);
                    }
                }, delaySec, java.util.concurrent.TimeUnit.SECONDS);
                return;
            }
            handleStreamErrorTerminal(conversationId, projectId, userId, err);
        });

        // 无活动看门狗：timeout 调大后，"流悄悄停了但不回调"的场景由它兜底终止本轮
        handler.armInactivityWatchdog(STREAM_INACTIVITY_TIMEOUT_SECONDS);

        // Execute Generation with Tools
        // Ask 模式：不传递工具，禁止工具调用
        if (agentMode == AgentMode.ASK) {
            log.info("Ask mode: generating without tools");
            model.generate(messages, handler);
        } else {
            // Agent 和 Plan 模式：传递工具规格（内置 + 插件，统一来自注册表）
            // Skill 命中时由 SkillRouter 做可见性白名单裁剪（Phase 3B，未命中原样返回）
            List<ToolSpecification> allTools = skillRouter.visibleTools(conversationId, toolRegistry.getAllSpecifications());
            model.generate(messages, allTools, handler);
        }
    }

    /**
     * 流式错误的终态处置（重试预算耗尽 / 不可重试错误 / 已流出部分内容）：
     * 发 error 事件、保存部分内容、关流、复位状态。原 setOnError 内联逻辑提取而来。
     */
    private void handleStreamErrorTerminal(String conversationId, String projectId, Long userId, Throwable err) {
        sseEmitterService.send(conversationId, "error", "Stream Error: " + err.getMessage());
        agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.ERROR);
        boolean wasStreamingOnError = editorBridgeService.isStreamingMode(conversationId);
        editorBridgeService.setStreamingMode(conversationId, false);
        // 出错也要让 worker 收尾（否则 markdown 状态机残留半行/半张表），须在 close 之前发
        if (wasStreamingOnError) {
            sseEmitterService.send(conversationId, "doc_stream_end", java.util.Map.of("status", "error"));
        }
        // 保存已生成的部分内容，避免"当时看到了回复、历史里却没有"
        StringBuilder sb = activeStreamContent.get(conversationId);
        String partialContent = sb != null ? sb.toString() : "";
        if (!partialContent.isEmpty()) {
            saveAssistantMessage(conversationId, projectId, userId, partialContent + "\n\n[生成出错，已中断]");
        }
        sseEmitterService.close(conversationId);
        clearCancelledState(conversationId);
        editorBridgeService.clearCurrentConversationId();
    }

    /**
     * 瞬时错误分类（对标 OpenHands RetryMixin）：限流/服务端错误/超时/断连可重试，
     * 4xx 参数与鉴权类错误不可重试（重放也不会好，且可能重复扣费探测）。
     */
    static boolean isTransientLlmError(Throwable err) {
        for (Throwable t = err; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.util.concurrent.TimeoutException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.io.InterruptedIOException
                    || t instanceof java.io.IOException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase(java.util.Locale.ROOT);
                // 明确不可重试：客户端参数/鉴权错误
                if (m.contains("status code: 400") || m.contains("status code: 401")
                        || m.contains("status code: 403") || m.contains("status code: 404")) {
                    return false;
                }
                if (m.contains("status code: 429") || m.contains("status code: 5")
                        || m.contains("rate limit") || m.contains("overloaded")
                        || m.contains("timeout") || m.contains("timed out")
                        || m.contains("connection reset") || m.contains("stream was reset")
                        || m.contains("unexpected end of stream") || m.contains("canceled")) {
                    return true;
                }
            }
        }
        return false;
    }

    // =================================================================================
    // Loop guard & SSE helpers
    // =================================================================================

    /**
     * 通过 text_delta 事件向前端推送一段内容（与流式 token 走同一渲染管线）。
     */
    private void sendTextDelta(String conversationId, String content) {
        String esc = content.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
        sseEmitterService.send(conversationId, "text_delta", "{\"content\":\"" + esc + "\"}");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(截断)";
    }

    /**
     * 重复调用检测：同一工具+同参数连续调用达到上限时返回守卫反馈（调用方应跳过执行），否则返回 null。
     */
    private String checkRepeatedCall(RunGuard guard, String toolName, String args) {
        String signature = toolName + "|" + (args == null ? "" : args);
        if (signature.equals(guard.lastCallSignature)) {
            guard.repeatCount++;
        } else {
            guard.lastCallSignature = signature;
            guard.repeatCount = 1;
        }
        if (guard.repeatCount >= MAX_IDENTICAL_TOOL_CALLS) {
            return String.format("Error: 检测到你已连续 %d 次以完全相同的参数调用 %s，本次调用已被系统拦截。" +
                    "请不要原样重试：换一种方法（其他工具、调整参数或先读取文档确认状态）；" +
                    "如果任务已无法继续，请输出 <final> 向用户说明目前进展和遇到的问题。",
                    guard.repeatCount, toolName);
        }
        return null;
    }

    /**
     * 连续失败计数：失败累计到阈值时在工具结果后追加收敛提示，成功则清零。
     */
    private String appendFailureNudge(RunGuard guard, String result, boolean success) {
        if (success) {
            guard.consecutiveFailures = 0;
            return result;
        }
        guard.consecutiveFailures++;
        if (guard.consecutiveFailures >= CONSECUTIVE_FAILURE_NUDGE) {
            return result + String.format("\n\n(System Note: 已连续 %d 次工具执行失败。请停止当前思路，" +
                    "先用读取类工具确认文档当前状态，或输出 <final> 向用户说明遇到的问题，不要继续盲目重试。)",
                    guard.consecutiveFailures);
        }
        return result;
    }

    // =================================================================================
    // Helper to notify frontend of file changes (Added/Modified)
    // =================================================================================
    private void notifyFileChange(String conversationId, String fileName, String changeType) {
        try {
            // Determine pure filename if path is given
            String name = fileName;
            if (name.contains("/") || name.contains("\\")) {
                java.nio.file.Path p = java.nio.file.Paths.get(name);
                name = p.getFileName().toString();
            }
            
            // Send SSE event to frontend
            String json = String.format("{\"fileName\":\"%s\", \"changeType\":\"%s\"}", 
                name.replace("\"", "\\\""), changeType);
            sseEmitterService.send(conversationId, "file_change", json);
            
            // Persist to database for history retrieval
            conversationFileChangeService.saveFileChange(conversationId, name, changeType);
        } catch (Exception e) {
            log.warn("Failed to notify file change", e);
        }
    }

    // Simple naive JSON extractor for single String arg tools
    private String extractArg(String jsonArgs, String key) {
        if (jsonArgs == null) return "";
        // using hutool or jackson is better. 
        // e.g. {"fileId": "123"}
        try {
            cn.hutool.json.JSONObject obj = cn.hutool.json.JSONUtil.parseObj(jsonArgs);
            return obj.getStr(key);
        } catch (Exception e) {
            return jsonArgs; // fallback
        }
    }
    


    /**
     * Clean XML control tags from LLM output before saving to DB.
     * These tags are for streaming display only and should not be persisted.
     */
    private String cleanXmlTags(String content) {
        if (content == null) return "";
        
        // Remove markdown code block wrappers that LLM sometimes outputs
        // ```xml, ```html, ``` etc.
        String cleaned = content.replaceAll("^```(?:xml|html|markdown)?\\s*\\n?", "");
        cleaned = cleaned.replaceAll("\\n?```\\s*$", "");
        cleaned = cleaned.replaceAll("```(?:xml|html|markdown)?\\s*\\n", "");
        cleaned = cleaned.replaceAll("\\n```", "");
        
        // Remove bubble_type tags: <bubble_type mode="..." />
        cleaned = cleaned.replaceAll("<bubble_type[^>]*/?>", "");
        
        // Remove artifact tags but KEEP the content inside
        // <artifact type="...">content</artifact> -> content
        cleaned = cleaned.replaceAll("<artifact[^>]*>", "");
        cleaned = cleaned.replaceAll("</artifact>", "");
        
        // Remove task_update tags: <task_update id="..." status="..." />
        cleaned = cleaned.replaceAll("<task_update[^>]*/?>", "");
        
        // Remove tool_code, tool_use tags
        cleaned = cleaned.replaceAll("<tool_code[^>]*>[\\s\\S]*?</tool_code>", "");
        cleaned = cleaned.replaceAll("<tool_use[^>]*>[\\s\\S]*?</tool_use>", "");
        cleaned = cleaned.replaceAll("<tool_code[^>]*/?>", "");
        cleaned = cleaned.replaceAll("<tool_use[^>]*/?>", "");
        
        // Keep <final> tag content but remove the tags themselves
        // <final>content</final> -> content
        cleaned = cleaned.replaceAll("<final>", "");
        cleaned = cleaned.replaceAll("</final>", "");
        
        // Clean up multiple consecutive newlines
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        
        return cleaned.trim();
    }

    private java.util.List<com.checkba.controller.ai.AiAgentController.ContextItem> convertFileIdsToContextItems(java.util.List<String> fileIds) {
        if (fileIds == null) return null;
        return fileIds.stream().map(id -> {
            com.checkba.controller.ai.AiAgentController.ContextItem item = new com.checkba.controller.ai.AiAgentController.ContextItem();
            item.setId(id);
            item.setIsDir(false);
            return item;
        }).collect(java.util.stream.Collectors.toList());
    }


}
