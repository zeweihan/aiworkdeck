package com.checkba.service.ai;

import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.ai.AgentMode;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.service.LangText;
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

    /** 步数预算耗尽的用户提示（抽成方法便于按应用语言断言，见 AgentTextLanguageTest）。 */
    static String maxDepthNotice() {
        return LangText.of(
                "\n\n> 本轮已达最大执行步数（" + MAX_LOOP_DEPTH + " 步），先暂停。已完成的修改均已生效，点击下方「继续」按钮可接着执行剩余任务。",
                "\n\n> This run reached the maximum step budget (" + MAX_LOOP_DEPTH + " steps) and is paused. All completed changes have taken effect; click the Continue button below to carry on with the remaining work.");
    }
    // 工具连续失败达到该次数后，向模型注入强提示要求收敛
    private static final int CONSECUTIVE_FAILURE_NUDGE = 3;

    /**
     * 工具返回空白时替入上下文的占位说明。
     *
     * <p>不能原样把空串交给 {@code ToolExecutionResultMessage.from}：它的
     * {@code ensureNotBlank(text, "text")} 会抛异常，一个返回空串的工具就掀翻整轮对话。
     * 英文是给模型看的（与其它工具错误串同语种），不进用户可见文案。
     */
    public static final String BLANK_TOOL_OUTPUT =
            "Error: the tool returned no output. Treat this as a failure: do not assume the operation "
                    + "succeeded — verify with a read-only tool, or tell the user what is missing.";

    // LLM 失败自动重试：退避档位与次数上限按错误类型区分（见 LlmErrorClassifier.Kind），
    // 且仅在本轮尚未流出任何 token 时重放（对话状态未被污染，重放安全且用户无感知重复内容）；
    // 重试预算耗尽或模型下线时改走故障转移链（ai.failover），仍在同一计费通道内换模型

    // 流无活动看门狗：超过该秒数没有任何 token 到达即判定本轮停滞（配合 timeout 调大后的兜底）
    private static final int STREAM_INACTIVITY_TIMEOUT_SECONDS = 180;
    // 首字节时限：一个 token 都没到过时用这条（短得多）。停滞时限要照顾"生成长工具参数时中途静默"，
    // 但"从头到尾零字节"没有这种正当理由，让用户干等满 180s 纯粹是白等
    private static final int STREAM_FIRST_TOKEN_TIMEOUT_SECONDS = 60;
    private static final java.util.concurrent.ScheduledExecutorService LLM_RETRY_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "llm-retry-scheduler");
                t.setDaemon(true);
                return t;
            });

    /**
     * 单次 Agent 运行的循环守卫状态（随递归传递）：
     * 打转检测 + 连续失败计数 + 模型故障转移进度，防止模型原地打转耗尽步数预算。
     */
    private static class RunGuard {
        // 原地打转检测：滑动窗口，识别 A/A/A 与 A/B/A/B 两种重复模式，先干预后熔断
        final StuckDetector stuck = new StuckDetector();
        // 已经试过并失败的模型（含当前模型），故障转移时跳过
        final Set<String> triedModels = new java.util.LinkedHashSet<>();
        int consecutiveFailures;
        // 本轮 LLM 调用的失败重试次数（每个成功完成的轮次、每次切模型后清零）
        int llmRetries;
        // 截断/未闭合 <tool_code> 的纠正轮次数（防纠正本身进入死循环）
        int malformedToolRounds;
        // 上下文溢出后的强制压缩重试次数（每个成功完成的轮次清零；上限 1，压不动就终态）
        int overflowCompactions;
        // 当前活跃文档 ID（来自 activeContext 或 doc_open_file），用于修改前自动创建检查点
        Long activeFileId;
        // 活跃文档名（仅用于给模型的反馈文案）
        String activeFileName;
    }

    /**
     * skill_update 载荷序列化用。刻意做成静态字段而不是构造器注入的 bean——
     * 本类的构造器一动就必须同步 EvalHarness（领域文档里踩过三次的地雷），
     * 为了一个无状态的 JSON 序列化器付这个代价不值。
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper SKILL_UPDATE_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // 取消状态管理：存储被取消的会话ID
    private final Set<String> cancelledConversations = ConcurrentHashMap.newKeySet();
    // 存储当前活跃会话的已生成内容（用于取消时保存部分内容）
    private final Map<String, StringBuilder> activeStreamContent = new ConcurrentHashMap<>();
    // 本轮 ASSISTANT 消息的行 ID：同一轮内的增量保存/最终保存更新同一行，跨轮次互不覆盖
    private final Map<String, Long> activeAssistantMessageId = new ConcurrentHashMap<>();
    // 本轮开始时记下的 SSE 连接代次：收尾调用 sseEmitterService.close() 时原样带回，
    // 防止跑了半天的旧一轮在收尾时把期间用户重连建立的新连接误杀（见 SseEmitterService.close 注释）
    private final Map<String, Long> turnConnectionEpoch = new ConcurrentHashMap<>();

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
    private final com.checkba.config.AiFailoverProperties failoverProperties;
    private final com.checkba.service.ai.context.RunLoopCompactor runLoopCompactor;
    // 埋点（隐私红线与字段白名单见 service/telemetry；构造器变更需同步 EvalHarness）
    private final com.checkba.service.telemetry.TelemetryService telemetryService;
    private final com.checkba.service.telemetry.TelemetryTurnTracker telemetryTurnTracker;
    private final com.checkba.service.telemetry.MatterClassifierService matterClassifierService;

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
        turnConnectionEpoch.remove(conversationId);
    }

    /**
     * 收尾关闭本轮 SSE 连接：带上本轮开始时记下的代次，交给 SseEmitterService 做匹配判断。
     * 只处理这一处的误杀，不动取消/恢复相关逻辑。
     */
    private void closeSse(String conversationId) {
        sseEmitterService.close(conversationId, turnConnectionEpoch.getOrDefault(conversationId, 0L));
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
     * 终止路径专用的落库：落库失败只记日志，不能盖掉原始异常，也不能让后续收尾动作被跳过。
     */
    private void saveAssistantMessageQuietly(String conversationId, String projectId, Long userId, String content) {
        try {
            saveAssistantMessage(conversationId, projectId, userId, content);
        } catch (Exception persistError) {
            log.warn("Failed to persist terminal assistant message for conversation {}", conversationId, persistError);
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
            String contentToSave = partialContent + LangText.of("\n\n[已中断]", "\n\n[Interrupted]");
            saveAssistantMessage(conversationId, projectId, userId, contentToSave);
            log.info("Saved partial content ({} chars) for cancelled conversation: {}", partialContent.length(), conversationId);
        }
        
        agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.CANCELLED);
        // 发送取消事件
        sseEmitterService.send(conversationId, "cancelled",
                "{\"message\":\"" + LangText.of("用户已停止生成", "Generation stopped by the user") + "\"}");
        closeSse(conversationId);
        
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
        // 「执行成功」不等于「改过文件」：查找替换一次都没命中时照发 MODIFIED，
        // 用户会被告知本轮改了这个文件，去找却找不到任何改动。
        if (!meta.fileEffect().isEmpty() && result.fileChanged()) {
            String fileName = meta.fileArg().isEmpty() ? null : extractArg(argsJson, meta.fileArg());
            if (fileName == null || fileName.isEmpty()) {
                fileName = "Current Document";
            }
            notifyFileChange(conversationId, fileName, meta.fileEffect());
        }
    }


    /**
     * SSE {@code skill_update}：把本轮真正生效的 skill 清单推给前端
     * （载荷 {@code {"skills":[{"id","name","source"}]}}，source = auto | manual）。
     *
     * <p>刻意"每轮必发、空也发"——前端拿它做整表覆写，漏发一次上一轮的 chip 就会一直挂着，
     * 用户以为某个 skill 还生效着。name 已由 SkillRouter 按应用语言解析好。
     *
     * <p>推送失败只 log：一个提示 chip 不该让对话中断（与 plan_update 同口径）。
     */
    private void sendSkillUpdate(String conversationId,
                                 List<com.checkba.service.ai.skill.SkillRouter.ActiveSkill> active) {
        try {
            List<java.util.Map<String, String>> skills = active.stream()
                    .map(a -> java.util.Map.of(
                            "id", a.definition().getId(),
                            "name", a.displayName(),
                            "source", a.source()))
                    .toList();
            sseEmitterService.send(conversationId, "skill_update",
                    SKILL_UPDATE_MAPPER.writeValueAsString(java.util.Map.of("skills", skills)));
        } catch (Exception e) {
            log.warn("Failed to push skill_update for {}", conversationId, e);
        }
    }

    /**
     * 处理用户消息 (入口)
     */
    @Async("taskExecutor") // Run in separate thread
    public void handleUserMessage(AiAgentController.AgentChatRequest request, Long userId) {
        // 平台通道按用户计费（server 模式多租户）：整轮循环——含其中同步调用的上下文组装、
        // 记忆检索、子 Agent、故障转移换模型——都在这个身份作用域内取 key。
        // 本方法体里另有跨线程提交（标题生成），必须各自用 PlatformAiUserScope.wrap 重放。
        PlatformAiUserScope.run(userId, () -> handleUserMessageInScope(request, userId));
    }

    private void handleUserMessageInScope(AiAgentController.AgentChatRequest request, Long userId) {
        String conversationId = request.getConversationId();
        String projectId = String.valueOf(request.getProjectId());
        AgentMode agentMode = request.getAgentMode(); // 获取 Agent 模式
        
        // 初始化取消状态和内容收集器（新的一轮：清掉上一轮的 ASSISTANT 行 ID，本轮回复必须落新行）
        cancelledConversations.remove(conversationId);
        activeStreamContent.put(conversationId, new StringBuilder());
        activeAssistantMessageId.remove(conversationId);
        // 本轮开始时先取一次 SSE 连接代次存起来：本轮期间无论重试/递归多少层，
        // 收尾时各处 close() 都带这同一个值，与用户中途重连产生的新代次区分开
        turnConnectionEpoch.put(conversationId, sseEmitterService.currentEpoch(conversationId));
        // 埋点轮次上下文先于 mark 建立：终态由 AgentRunStateService.mark 单点合成 ai.turn
        java.util.Map<String, Object> turnAttrs = new java.util.HashMap<>();
        turnAttrs.put("mode", agentMode == null ? null : agentMode.name());
        turnAttrs.put("model", request.getModel());
        // 附件数按真正生效的那条通道数：contextItems 是今天的主路径（桌面端与插件端都发它），
        // 只数 legacy 的 fileIds 会把绝大多数带附件的轮次记成 0——图片直送上线后，
        // 「有多少轮带了附件」正是要看的东西。字段名与口径不变，不涉及埋点白名单。
        turnAttrs.put("attachmentCount", request.getContextItems() != null
                ? request.getContextItems().size()
                : (request.getFileIds() == null ? 0 : request.getFileIds().size()));
        turnAttrs.put("hasPinnedSkill", request.getPinnedSkillId() != null && !request.getPinnedSkillId().isEmpty());
        telemetryTurnTracker.startTurn(conversationId, turnAttrs);
        // 状态登记：循环开跑（会话列表状态点/切回续流判断都依赖它）。
        // 起跑这一次带上 projectId/userId 写进持久化记录——进程被杀后的启动回收靠它归属会话；
        // 同时把上次遗留的 INTERRUPTED 覆盖掉（用户点「继续」走的就是这条路）。
        agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.RUNNING,
                request.getProjectId(), userId);
        
        try {
            log.info("Agent Loop Started: conv={}, model={}, mode={}, msg={}", conversationId, request.getModel(), agentMode, request.getMessage());
            
            // 1. 保存用户消息 (Save only user message first; assistant saved after stream completes)
            // displayText 是「发送内容 ≠ 显示内容」通道（契约 D）：content 留给模型（计划审批卡
            // 回喂的修订版全文这类细节必须给全），displayContent 才是用户气泡里那句人话。
            // 缺省 null = 与本通道不存在时完全一致；上下文组装一律只读 content。
            messageService.saveMessage(
                projectId, userId, conversationId, "USER", request.getMessage(), request.getDisplayText()
            );
            
            // 1.1 首次对话时异步生成对话标题
            List<com.checkba.model.entity.ProjectAiMessage> existingMsgs = messageService.listByConversationId(conversationId);
            if (existingMsgs.size() <= 1) { // Only the user message we just saved
                final String convId = conversationId;
                final String userMsg = request.getMessage();
                // 跨线程提交：身份不会自动传递（池线程继承的是创建者而非提交者），显式重放
                CompletableFuture.runAsync(PlatformAiUserScope.wrap(() -> {
                    try {
                        log.info("Generating conversation title for: {}", convId);
                        // 起标题走辅助模型（ai.auxModel → yml ai.aux-model）：用户看不见但每个新会话都跑一次，
                        // 写死模型 ID 等于把「换便宜模型省钱」这件事绕开了
                        dev.langchain4j.model.chat.ChatLanguageModel titleModel = chatModelFactory.getAuxChatModel();
                        String title = messageService.generateConversationTitle(userMsg, titleModel);
                        messageService.updateConversationTitle(convId, title);
                        log.info("Conversation title generated: {} -> {}", convId, title);
                        // Notify frontend of title update
                        sseEmitterService.send(convId, "title_update", "{\"title\":\"" + title.replace("\"", "\\\"").replace("\n", " ") + "\"}");
                    } catch (Exception e) {
                        log.warn("Failed to generate conversation title for {}", convId, e);
                    }
                }));
            }
            
            // 1.2 Skill 激活（Phase 3B）：手动选择 ∪ 触发词自动命中；一个都不生效时行为与现状一致。
            // ASK 模式下 skill 整体不生效（不传工具、ContextAssembler 也跳过注入），
            // 因此手动选择在 ASK 下不参与——让"面板上亮着 skill、实际什么都没注入"这种
            // 显示与实际不一致的状态压根不出现。
            boolean skillsEffective = agentMode != AgentMode.ASK;
            skillRouter.activateForTurn(conversationId, request.getMessage(), request.getPinnedSkillId(),
                    skillsEffective ? request.getSkillIds() : null);
            // 把本轮真正生效的清单告诉前端（自动命中的那枚在面板里会闪一下）。
            // 空列表也发：前端靠它把上一轮的 chip 清掉。
            sendSkillUpdate(conversationId,
                    skillsEffective ? skillRouter.activeSkills(conversationId) : List.of());

            // 1.3 事项类型 AI 兜底分类：仅会话首轮且未命中 skill（skill 命中由 SkillRouter 产出类别）；
            // 异步、开关关闭时 no-op，绝不阻塞对话主链路
            if (existingMsgs.size() <= 1) {
                matterClassifierService.classifyAsync(conversationId, request.getMessage(),
                        skillRouter.activeSkill(conversationId).isPresent());
            }

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
                // 绝不能用 m.text()：langchain4j 0.36 里 UserMessage.text() 就是 singleText()，
                // 「文本 + 图片」的多模态消息会直接抛 RuntimeException。而 slf4j 的参数是
                // **提前求值**的，日志级别停在 INFO 也照样执行——图片一上线，本轮就死在
                // assemble 返回之后、generate 之前，被下面的兜底 catch 变成一句
                // 没头没尾的 "Internal Error"，日志里没有任何指向图片的线索。
                log.debug("  - Role: {}, Content length: {}, Images: {}", m.type(),
                        com.checkba.service.ai.context.ChatMessageText.of(m).length(),
                        com.checkba.service.ai.context.ChatMessageText.imageCountOf(m));
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
            // 用户消息在本方法开头已落库：这里不补一条 ASSISTANT，刷新页面后这一轮就只剩用户
            // 自己的问题，看起来像 AI 完全没回应。落的正是推给用户的那句文案（不加前缀）。
            saveAssistantMessageQuietly(conversationId, projectId, userId, e.getMessage());
            closeSse(conversationId);
        } catch (Exception e) {
            log.error("Agent Loop Error for conversation: " + conversationId, e);
            String errorText = "Internal Error: " + e.getMessage();
            agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.ERROR);
            sseEmitterService.send(conversationId, "error", errorText);
            // 同上：历史里必须留下这一轮出过错的痕迹，且与 SSE 推出去的是同一串文本
            saveAssistantMessageQuietly(conversationId, projectId, userId, errorText);
            closeSse(conversationId);
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
            String notice = maxDepthNotice();
            sendTextDelta(conversationId, notice);
            String persisted = (executionLog.length() > 0 ? executionLog.toString() : "") + notice;
            saveAssistantMessage(conversationId, projectId, userId, persisted);
            agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.PAUSED);
            // status=paused 让前端渲染一键「继续」按钮（区别于 finished 的正常收尾）
            sseEmitterService.send(conversationId, "bubble_end", "{\"status\":\"paused\",\"reason\":\"max_depth\"}");
            closeSse(conversationId);
            clearCancelledState(conversationId);
            return;
        }
        
        // Ask 模式限制递归深度为 1（不允许工具调用后的循环）
        if (agentMode == AgentMode.ASK && depth > 0) {
            log.info("Ask mode: stopping loop at depth {}", depth);
            agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.FINISHED);
            sseEmitterService.send(conversationId, "bubble_end", "{}");
            closeSse(conversationId);
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
            modelId,
            turnConnectionEpoch.getOrDefault(conversationId, 0L)
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
        // 平台通道身份必须在回调线程里重建：流式回调跑在 HTTP 客户端的线程上，
        // handleUserMessage 在 taskExecutor 线程建立的 PlatformAiUserScope 不跟着走。
        // 本回调里同步做的这些事都要取 per-user 平台密钥：工具分发、自动 compaction（摘要模型）、
        // 递归下一轮 generate、故障转移换模型——缺身份会被 PlatformAiChannel 判成
        // 「本次 AI 调用未携带用户身份」，在编排器这层看起来就是「平台通道不可用」整轮终止。
        handler.setOnComplete(response -> PlatformAiUserScope.run(userId, () -> {
          try {
            // 重试/溢出预算的清零不在此处——必须等空响应判定之后（见下）：空响应也会走到
            // onComplete，在判定前清零会让它每次都从第 1 次重试起步，变成无限重试循环。
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

            // 空响应当瞬时错误重试，不当正常收尾（对标 dsh EMPTY_RESPONSE 教训）：
            // 上游偶发「正常终止 + 零内容零工具调用」，落到默认收尾会静默 FINISHED——
            // 用户面前一片空白、没有错误提示、没有任何重试入口，循环也无从接续。
            // 零 token 流出保证重放不会给用户看重复内容；空的 AiMessage 不入栈
            //（空 assistant 轮次发回服务商是另一类 400 的成因）。
            if (isEmptyResponse(aiMessage, handler.hasStreamedTokens())) {
                handleEmptyResponse(model, messages, conversationId, projectId, userId, modelId,
                        depth, executionLog, agentMode, guard);
                return;
            }

            // 本轮真正成功完成（有内容或有工具调用）：清零瞬时错误重试预算与溢出压缩预算
            //（额度按轮计，不跨轮累积；溢出预算跨轮清零对标 dsh——长任务里
            // 「涨到溢出→压缩→继续跑→再涨到溢出」是合法路径）
            guard.llmRetries = 0;
            guard.overflowCompactions = 0;

            // LENGTH 截断轮的工具调用一律不执行（对标 dsh：max-tokens 时丢弃 tool-call 块）：
            // 参数被砍半后 JSON 解析失败还好（已有回喂纠正），恰好仍可解析才最危险——
            // 半篇正文的 write_file 会直接覆盖掉用户的文件。带截断 tool_calls 的 AiMessage
            // 也不入栈：不执行又入栈会让 OpenAI 兼容通道以「tool_calls 无配对结果」400。
            if (isTruncatedToolCallRound(response.finishReason(), aiMessage)) {
                if (guard.malformedToolRounds < 2) {
                    guard.malformedToolRounds++;
                    log.warn("LENGTH-truncated native tool calls for {} (correction round {}), asking model to re-emit",
                            conversationId, guard.malformedToolRounds);
                    messages.add(dev.langchain4j.data.message.UserMessage.from(
                            "[系统提醒] 你上一条输出因达到单次输出长度上限被截断，其中的工具调用参数不完整、"
                            + "没有被执行。请把动作拆小，重新、完整地输出这次工具调用；一次只做一步也可以。"));
                    runLoop(model, messages, conversationId, projectId, userId, modelId,
                            depth + 1, executionLog, agentMode, guard);
                    return;
                }
                // 纠正预算耗尽：按「存档 + 请示」暂停（同步数预算收尾的语义），绝不执行截断的调用
                log.warn("LENGTH-truncated tool calls persisted after corrections for {}, pausing", conversationId);
                String notice = LangText.of(
                        "\n\n> 模型输出连续多次达到长度上限、工具调用无法完整发出，先暂停。点击下方「继续」按钮可接着执行。",
                        "\n\n> The model's output kept hitting the length limit and the tool call could not be emitted in full; pausing here. Click the Continue button below to resume.");
                sendTextDelta(conversationId, notice);
                String truncPersisted = (executionLog.length() > 0 ? executionLog.toString() : "")
                        + (aiMessage.text() != null ? aiMessage.text() : "") + notice;
                saveAssistantMessage(conversationId, projectId, userId, truncPersisted);
                agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.PAUSED);
                sseEmitterService.send(conversationId, "bubble_end", "{\"status\":\"paused\",\"reason\":\"max_tokens\"}");
                closeSse(conversationId);
                clearCancelledState(conversationId);
                return;
            }

            messages.add(aiMessage);

            // 注意：本轮生成内容已由 onToken 回调逐 token 累加进 activeStreamContent，
            // 此处不可再 append aiMessage.text()，否则取消/断线恢复的快照内容会翻倍。
            String aiContent = aiMessage.text();

            // 1. Check for Native Tool Requests (Priority 1)
            if (aiMessage.hasToolExecutionRequests()) {
                log.info("Detected Native Tool Requests: {}", aiMessage.toolExecutionRequests());

                // 打转首次干预的提示语（本轮末位追加一条），null 表示未检出
                String stuckNudge = null;

                // Execute Native Tools (统一分发，无需感知具体工具)
                for (dev.langchain4j.agent.tool.ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    // 慢工具执行期的取消响应点。此前 isCancelled 只在 runLoop 入口与本回调开头各查一次，
                    // 于是「停止」按钮在 dispatch_subtask（可跑 630 秒）或 AI PPT（十几分钟）中间
                    // 完全不生效——用户看到的是按了没反应、还得继续等。一处检查覆盖所有慢工具：
                    // 一轮里剩下的工具全部不再执行。已经跑完的工具副作用无法回滚（这是取消的固有语义）。
                    if (isCancelled(conversationId)) {
                        log.info("Conversation {} cancelled before tool {}, skipping remaining tools",
                                conversationId, req.name());
                        handleCancellation(conversationId, projectId, userId);
                        return;
                    }
                    // 面板可见性：原生工具调用复用 <process> XML 协议推送给前端，
                    // 与模型自发的 XML tool_code 走同一套任务卡渲染管线（修复：原生轮次面板无任何输出，看似卡死）
                    String displayName = toolRegistry.resolve(req.name())
                            .map(ToolRegistry.RegisteredTool::displayName)
                            .orElse(req.name());
                    sendTextDelta(conversationId, String.format("<process name=\"%s\"><tool_code>%s(%s)</tool_code></process>",
                            displayName.replace("\"", "'"), req.name(),
                            AgentTagProtocol.escape(truncate(req.arguments(), 200))));

                    String result;
                    boolean success;
                    StuckDetector.Verdict verdict = guard.stuck.record(req.name(), req.arguments());
                    if (verdict == StuckDetector.Verdict.CIRCUIT_BREAK) {
                        // 二次检出熔断：不执行，直接把守卫反馈作为工具结果回给模型
                        log.warn("Stuck detector circuit break for {}: {}", conversationId, guard.stuck.lastPattern());
                        result = stuckCircuitBreakFeedback(guard.stuck.lastPattern());
                        success = false;
                    } else {
                        if (verdict == StuckDetector.Verdict.INTERVENE) {
                            log.warn("Stuck detector intervention for {}: {}", conversationId, guard.stuck.lastPattern());
                            stuckNudge = guard.stuck.lastPattern();
                        }
                        ToolRegistry.ToolResult toolResult = dispatchTool(req.name(), req.arguments(),
                                Long.parseLong(projectId), conversationId, userId, modelId, guard);
                        result = toolResult.output();
                        success = toolResult.success();
                    }
                    // 空输出归一：langchain4j 的 ToolExecutionResultMessage.from 对空白 text 抛
                    // ensureNotBlank，一个返回空串的工具就能掀翻整轮对话（用户看到
                    // 「Callback Error: text cannot be null or blank」）。任何工具返回空都不允许
                    // 掀翻整轮——换成显式说明并按失败处理，走连续失败纠正回路让模型换个思路。
                    if (!org.springframework.util.StringUtils.hasText(result)) {
                        result = BLANK_TOOL_OUTPUT;
                        success = false;
                    }
                    result = appendFailureNudge(guard, result, success);

                    // Determine status for history and display
                    String nativeToolStatus = success ? "SUCCESS" : "FAILURE";

                    // Log for history persistence (include status attribute)
                    // 先落执行日志再入栈：入栈那步一旦抛异常（历史上就是上面的 ensureNotBlank），
                    // 排在它后面的 append 不会执行，崩溃轮的过程卡整段丢失、历史里无从回放
                    executionLog.append(String.format("<process name=\"%s\"><tool_code>%s(%s)</tool_code><tool_output status=\"%s\">%s</tool_output></process>\n",
                        displayName.replace("\"", "'"), req.name(), AgentTagProtocol.escape(req.arguments()),
                        nativeToolStatus, AgentTagProtocol.escape(result)));

                    // 载荷先截断再中和：截断口径按原文字数（与前端「...(截断)」提示一致），
                    // 中和只保证载荷不会顶掉外层标签（AgentTagProtocol，两侧契约）
                    sendTextDelta(conversationId, String.format("<tool_output status=\"%s\">%s</tool_output>",
                            nativeToolStatus,
                            AgentTagProtocol.escape(truncate(result, toolOutputDisplayLimit(req.name())))));

                    messages.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(req, result));
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

                // 打转干预挂在整栈末位：只写进 system prompt 的行为约束会被弱模型稳定无视，
                // 末位才是注意力最高的位置（PR#209 实证）
                if (stuckNudge != null) {
                    messages.add(dev.langchain4j.data.message.UserMessage.from(stuckInterventionMessage(stuckNudge)));
                }

                // 增量保存：在工具执行后立即保存AI消息和工具输出，防止对话中断导致上下文丢失
                String intermediateContent = (aiContent != null ? aiContent : "") + "\n" + executionLog.toString();
                saveAssistantMessage(conversationId, projectId, userId, intermediateContent);
                log.info("Intermediate save after native tool execution for conversation: {}", conversationId);

                // 反问优先于递归：模型在同一轮里既调了工具又问了问题时，继续递归会把问题埋在
                // 后续输出里、模型自己接着猜下去（正是 <question> 要阻止的事）。工具已经跑完、
                // 结果已落库，此处停机等回答即可。
                if (containsQuestion(aiContent)) {
                    stopForUserQuestion(conversationId, projectId, userId, intermediateContent);
                    return;
                }

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
                // 打转首次干预的提示语（本轮末位追加一条），null 表示未检出
                String stuckNudge = null;

                for (XmlToolCallParser.ParsedCall call : xmlToolCallParser.parse(content)) {
                    // 同原生分支：慢工具中间也要能取消。XML 兜底是弱模型的主路径，
                    // 只修原生分支等于「换个模型停止键就又不灵了」
                    if (isCancelled(conversationId)) {
                        log.info("Conversation {} cancelled before XML tool {}, skipping remaining tools",
                                conversationId, call.toolName());
                        handleCancellation(conversationId, projectId, userId);
                        return;
                    }
                    String code = call.rawCode();
                    log.info("Parsed Tool Code: {}", code);

                    String result;
                    boolean xmlToolSuccess;
                    ToolRegistry.ToolResult toolResult = null;
                    StuckDetector.Verdict verdict = guard.stuck.record(call.toolName(), call.argsJson());
                    if (verdict == StuckDetector.Verdict.CIRCUIT_BREAK) {
                        // 二次检出熔断：不执行，直接把守卫反馈作为工具结果回给模型
                        log.warn("Stuck detector circuit break for {}: {}", conversationId, guard.stuck.lastPattern());
                        result = stuckCircuitBreakFeedback(guard.stuck.lastPattern());
                        xmlToolSuccess = false;
                    } else {
                        if (verdict == StuckDetector.Verdict.INTERVENE) {
                            log.warn("Stuck detector intervention for {}: {}", conversationId, guard.stuck.lastPattern());
                            stuckNudge = guard.stuck.lastPattern();
                        }
                        toolResult = dispatchTool(call.toolName(), call.argsJson(),
                                Long.parseLong(projectId), conversationId, userId, modelId, guard);
                        result = toolResult.found()
                                ? toolResult.output()
                                : "Unknown tool in custom parser: " + code;
                        xmlToolSuccess = toolResult.found() && toolResult.success();
                    }
                    // 空输出归一（与原生分支同口径，见上）：XML 兜底路径不会因空白抛
                    // ensureNotBlank（feedbackMsg 有模板包裹），但「Output: 空」配上下面那句
                    // 成功断言，等于告诉模型「跑成功了、只是没输出」——模型转头就收尾。
                    if (!org.springframework.util.StringUtils.hasText(result)) {
                        result = BLANK_TOOL_OUTPUT;
                        xmlToolSuccess = false;
                    }
                    result = appendFailureNudge(guard, result, xmlToolSuccess);

                    // Add Result to History
                    String statusPrefix = xmlToolSuccess ? "SUCCESS" : "FAILURE";

                    // Enhancement for Write Tools: Append explicit success for file creation/modification
                    // The model often sees JSON IDs (wps_file_id) and thinks it failed or needs to do more.
                    if ("SUCCESS".equals(statusPrefix) && call.toolName().startsWith("write_")) {
                         result += "\n\n(System Note: File operation completed successfully.)";
                    }

                    // Explicitly tell the model to EVALUATE - with strict anti-over-execution instructions.
                    // 收敛指令**只在成功时**给：这段文案里的 "The tool executed successfully" 原来是
                    // 无条件拼进去的，与同一条消息里的 Status: FAILURE 直接打架，紧跟着还催
                    // 「output <final> IMMEDIATELY」。XML 兜底是弱模型的主路径，而末位/最强指令
                    // 会赢（PR#209 实证）——工具失败时模型被引导去宣布任务完成，用户看到的就是
                    // 「AI 说做完了，其实什么都没发生」。失败时改成纠错指令，与原生分支语义一致。
                    String instruction = xmlToolSuccess
                            ? "(CRITICAL INSTRUCTION: The tool executed successfully. Now compare with the ORIGINAL user request. If the SPECIFIC task the user asked for is complete, output `<final>` IMMEDIATELY. DO NOT perform additional operations unless the user EXPLICITLY requested them. For example, if user asked to 'delete the 3rd z' and you deleted it, you are DONE - do not delete other z's.)"
                            : "(CRITICAL INSTRUCTION: The tool FAILED - the operation did NOT take effect. Do NOT claim the task is done. Read the Output above, then either fix the call (correct arguments, or verify state with a read-only tool) or use `<final>` to tell the user plainly what failed and why. Never report success for a failed tool.)";
                    String feedbackMsg = String.format("[System Tool Execution Log]\nTool: %s\nStatus: %s\nOutput: %s\n\n%s",
                        code, statusPrefix, result, instruction);

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
                        : (toolResult != null && toolResult.tool() != null ? toolResult.tool().displayName()
                                : LangText.of("工具执行", "Tool execution"));
                    executionLog.append(String.format("<process name=\"%s\"><tool_code>%s</tool_code><tool_output status=\"%s\">%s</tool_output></process>\n",
                        processNameForLog, AgentTagProtocol.escape(code), statusPrefix,
                        AgentTagProtocol.escape(result)));

                    // Emit explicit tool_output for frontend parser with status attribute
                    // NOTE: Do NOT wrap in <process> - the tool_output belongs to the existing process
                    // that contained the tool_code.
                    String toolOutputXml = String.format("<tool_output status=\"%s\">%s</tool_output>",
                        statusPrefix, AgentTagProtocol.escape(result));
                    // 走 sendTextDelta 而不是自己拼 JSON：此处原来的手写转义漏了反斜杠，
                    // 输出里带 Windows 路径或 JSON 字符串时整条 text_delta 在前端 JSON.parse 失败
                    sendTextDelta(conversationId, toolOutputXml);

                    toolExecuted = true;
                }

                if (toolExecuted) {
                     // 打转干预挂在整栈末位（同原生分支：system prompt 里的约束会被弱模型无视）
                     if (stuckNudge != null) {
                         messages.add(dev.langchain4j.data.message.UserMessage.from(stuckInterventionMessage(stuckNudge)));
                     }
                     // 增量保存：在XML工具执行后立即保存AI消息和工具输出，防止对话中断导致上下文丢失
                     String intermediateXmlContent = content + "\n" + executionLog.toString();
                     saveAssistantMessage(conversationId, projectId, userId, intermediateXmlContent);
                     log.info("Intermediate save after XML tool execution for conversation: {}", conversationId);

                     // 反问优先于递归（同原生分支）
                     if (containsQuestion(content)) {
                         stopForUserQuestion(conversationId, projectId, userId, intermediateXmlContent);
                         return;
                     }

                     // Recurse with executionLog
                     runLoop(model, messages, conversationId, projectId, userId, modelId, depth + 1, executionLog, agentMode, guard);
                     return;
                }
            }

            // 2.5 截断的工具调用（F-10）：输出里有 <tool_code> 却没有闭合标签——多为
            // max_tokens/上游截断，解析器提不出任何调用。此前会落到"正常收尾"静默结束，
            // 任务做一半、无错误提示、无继续按钮。这里回喂提示让模型重发，最多纠正 2 轮。
            // 含 <question> 时不走纠正回路：模型问了问题、同时输出被截断，此刻正确的动作是
            // 停下来等回答（下面 3.2），而不是催它重发工具调用——那等于让它带着未决问题继续猜。
            if (agentMode != AgentMode.ASK && !containsQuestion(content) && endsWithUnclosedTag(content)) {
                if (guard.malformedToolRounds < 2) {
                    guard.malformedToolRounds++;
                    log.warn("Truncated tool_code detected for {} (correction round {}), asking model to re-emit",
                            conversationId, guard.malformedToolRounds);
                    messages.add(dev.langchain4j.data.message.UserMessage.from(
                            "[系统提醒] 你上一条输出中的标签未闭合（<tool_code> / <todo_write> / <final> 之一，内容被截断），"
                            + "这次输出没有生效。请重新、完整地输出这一步；参数很长时先拆小，"
                            + "或改用能直接把内容写进文档的工具。若任务其实已完成，请直接输出最终总结。"));
                    runLoop(model, messages, conversationId, projectId, userId, modelId,
                            depth + 1, executionLog, agentMode, guard);
                    return;
                }
                // 两轮纠正还在截断：多半是这次工具调用的参数太长（整张表、整段正文回抄）。
                // 「静默收尾」会让用户看着一个做了一半的任务发呆——这里把原因追进正文，
                // 让人知道该怎么办（换更小的参数、或用能自己写文档的工具）。
                log.warn("Truncated tool_code persisted after corrections for {}, finishing with a visible note", conversationId);
                content = content + "\n\n> 上一步的工具调用参数太长，模型输出被截断了两次，这一步没有执行完。"
                        + "常见原因是要写进文档的表格或正文被整段塞进了工具参数。"
                        + "可以让我把这一步拆小（分几次写），或改用能直接把内容写进文档的工具重试。";
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
                    closeSse(conversationId);
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

            // 3.2 反问停机（<question>）：模型缺关键前提、不敢猜，等用户回答。
            // 放在 artifact 之后：同一轮既给计划又问问题时，计划审批（含 artifact 落盘）优先，
            // 那条路本来就要用户点头，问题正文照样已经流给用户看了。
            if (containsQuestion(content)) {
                String fullContent = executionLog.length() > 0 ? executionLog.toString() + content : content;
                stopForUserQuestion(conversationId, projectId, userId, fullContent);
                return;
            }

            // 3.3 正文被长度上限截断（LENGTH 且无工具调用）：不能装作正常完成。
            // 半句话戛然而止的回答按「暂停 + 继续」收尾，语义同步数预算耗尽（存档 + 请示）；
            // 刻意不触发记忆管线与版本落档——本轮没有真正结束，续写完成的那轮一并跑。
            if (response.finishReason() == dev.langchain4j.model.output.FinishReason.LENGTH) {
                log.warn("Answer truncated by output length limit for {}, pausing for continuation", conversationId);
                String notice = LangText.of(
                        "\n\n> 回答达到单次输出长度上限被截断，点击下方「继续」按钮可接着生成。",
                        "\n\n> The answer was cut off by the per-response output length limit. Click the Continue button below to keep generating.");
                sendTextDelta(conversationId, notice);
                String truncContent = (executionLog.length() > 0 ? executionLog.toString() + content : content) + notice;
                saveAssistantMessage(conversationId, projectId, userId, truncContent);
                agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.PAUSED);
                sseEmitterService.send(conversationId, "bubble_end", "{\"status\":\"paused\",\"reason\":\"max_tokens\"}");
                closeSse(conversationId);
                clearCancelledState(conversationId);
                activeStreamContent.remove(conversationId);
                return;
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
            closeSse(conversationId);
            // 清理取消状态
            clearCancelledState(conversationId);
            activeStreamContent.remove(conversationId); // CLEANUP
          } catch (Exception e) {
            // 确保异常时也能正确结束 bubble，避免前端一直显示加载状态。
            //
            // 走 finishWithError 而不是只发一个 SSE：原来这里不落库，于是异常终止的那一轮
            // 在历史里一个字都没有——用户当时看到过工具在跑、看到过半截回复，刷新后全没了
            // （「历史对话吃消息」）。对照 handleStreamErrorTerminal 与 handleCancellation：
            // 两者都存了部分内容。executionLog 一并带上，崩溃轮的过程卡才能回放。
            log.error("Error in onComplete callback for conversation: " + conversationId, e);
            finishWithError(conversationId, projectId, userId,
                    LlmErrorClassifier.INTERNAL_ERROR_MARKER + ": Callback Error: " + e.getMessage(),
                    executionLog);
          } finally {
            // 流式回调运行在可复用线程池线程上，用完清理 ThreadLocal，防止会话串号
            editorBridgeService.clearCurrentConversationId();
          }
        }));

        // 流式出错处置（零 token 流出才允许重放，否则用户会看到重复内容）：
        // 1. 限流/瞬时错误 → 按类型退避重试本轮（限流 30/60s，瞬时 8/16/32s）
        // 2. 重试预算耗尽或模型下线（404）→ 换备选模型继续，SSE 明说切了哪个
        // 3. 其余 → 终态清理（关 emitter + 复位状态，避免 SSE 挂到超时、前端永久加载）
        // 同 onComplete：错误回调也在 HTTP 线程上，换模型要取平台密钥，身份必须重建
        handler.setOnError(err -> PlatformAiUserScope.run(userId, () -> {
            if (isCancelled(conversationId)) {
                handleStreamErrorTerminal(conversationId, projectId, userId, err, null);
                return;
            }
            LlmErrorClassifier.Kind kind = LlmErrorClassifier.classify(err);
            boolean replayable = !handler.hasStreamedTokens();

            if (replayable && kind.retryable() && guard.llmRetries < kind.maxRetries()) {
                int attempt = ++guard.llmRetries;
                long delaySec = kind.retryDelaySeconds(attempt);
                log.warn("LLM error [{}] for {} (attempt {}/{}), retrying in {}s: {}",
                        kind, conversationId, attempt, kind.maxRetries(), delaySec, String.valueOf(err));
                // 限流与故障文案分开：用户看到「服务不可用」而实际是限流排队，会误判成产品坏了
                sendTextDelta(conversationId, String.format(
                        kind == LlmErrorClassifier.Kind.RATE_LIMITED
                                ? LangText.of("\n\n> 模型限流等待中，%d 秒后自动继续（第 %d/%d 次）…\n\n",
                                        "\n\n> The model is rate limited; continuing automatically in %d s (attempt %d/%d)…\n\n")
                                : LangText.of("\n\n> 模型服务暂时不可用，%d 秒后自动重试（第 %d/%d 次）…\n\n",
                                        "\n\n> The model service is temporarily unavailable; retrying in %d s (attempt %d/%d)…\n\n"),
                        delaySec, attempt, kind.maxRetries()));
                // 定时器是自己的单线程池：身份同样要显式重放，否则重放的这一轮取不到平台密钥
                LLM_RETRY_SCHEDULER.schedule(PlatformAiUserScope.wrap(() -> {
                    try {
                        // 同 depth 重放本轮：messages 只在 onComplete 里被追加，失败轮未污染上下文
                        runLoop(model, messages, conversationId, projectId, userId, modelId,
                                depth, executionLog, agentMode, guard);
                    } catch (Exception retryEx) {
                        log.error("Retry runLoop failed for {}", conversationId, retryEx);
                        handleStreamErrorTerminal(conversationId, projectId, userId, retryEx, null);
                    }
                }), delaySec, java.util.concurrent.TimeUnit.SECONDS);
                return;
            }

            // 上下文溢出的被动恢复通道（对标 dsh context-overflow）：主动压缩靠估算 token 触发，
            // 中文语料按 chars-per-token=2 估会系统性低估，估漏时服务商用 400 兜底证实。
            // 重试凭证 = 压缩确实缩小了消息栈（compact 返回了新实例）；压不动就直接终态，
            // 原样重发必然再撞同一个 400。预算 1 次/轮，成功轮清零（见 onComplete）。
            if (replayable && kind == LlmErrorClassifier.Kind.CONTEXT_OVERFLOW
                    && guard.overflowCompactions < 1) {
                guard.overflowCompactions++;
                if (forceCompactAfterOverflow(messages, conversationId, modelId)) {
                    log.warn("Context overflow for {} confirmed by provider, retrying after forced compaction",
                            conversationId);
                    sendTextDelta(conversationId, LangText.of(
                            "\n\n> 对话上下文超出模型窗口，已自动压缩较早的内容后重试…\n\n",
                            "\n\n> The conversation exceeded the model's context window; earlier content was compacted automatically, retrying…\n\n"));
                    try {
                        runLoop(model, messages, conversationId, projectId, userId, modelId,
                                depth, executionLog, agentMode, guard);
                    } catch (Exception retryEx) {
                        log.error("Post-compaction retry failed for {}", conversationId, retryEx);
                        handleStreamErrorTerminal(conversationId, projectId, userId, retryEx, null);
                    }
                    return;
                }
                log.warn("Context overflow for {} but compaction could not shrink the stack, giving up",
                        conversationId);
            }

            if (replayable && failoverProperties.isEnabled() && kind.failoverable()) {
                guard.triedModels.add(modelId == null ? "" : modelId.toLowerCase(java.util.Locale.ROOT));
                // 地域拒绝（403 region）时候选必须收窄成区域无关模型：境内从一个国际档模型
                // 切到另一个国际档只会再撞一次同样的 403，白花一次请求还把 triedModels 填满。
                //
                // 消息栈里有图像内容块时同样要收窄成支持视觉的候选：故障转移**只换 modelId、
                // 不换消息栈**（switchToFailoverModel 拿同一个 messages 引用重放），切到读不了图的
                // 备用模型会把 image 块原样重发，换来一个上游 400，而第一个模型的图像 token
                // 已经花掉了；更坏的是这个 400 会被当成新一轮错误继续往下切，一次烧完整条链。
                // 收窄后一个候选都不剩就走终态处置——比白花几次请求诚实。
                String next = nextFailoverModel(failoverProperties.getModels(), modelId, guard.triedModels,
                        kind.requiresRegionAgnosticFailover(),
                        com.checkba.service.ai.context.ChatMessageText.containsImage(messages));
                if (next != null && switchToFailoverModel(next, kind, err, messages, conversationId,
                        projectId, userId, modelId, depth, executionLog, agentMode, guard)) {
                    return;
                }
            }
            handleStreamErrorTerminal(conversationId, projectId, userId, err, kind);
        }));

        // 无活动看门狗：timeout 调大后，"流悄悄停了但不回调"的场景由它兜底终止本轮
        handler.armInactivityWatchdog(STREAM_FIRST_TOKEN_TIMEOUT_SECONDS, STREAM_INACTIVITY_TIMEOUT_SECONDS);

        // 自动 compaction：消息栈在长任务里只增不减，撑破上下文会 400 或质量塌方。
        // 原地替换（而不是换个列表实例）——递归各层与两处回调共享同一个 messages 引用
        compactIfNeeded(messages, conversationId, modelId);

        // Execute Generation with Tools
        // Ask 模式：不传递工具，禁止工具调用
        if (agentMode == AgentMode.ASK) {
            log.info("Ask mode: generating without tools");
            model.generate(messages, handler);
        } else {
            // Agent 和 Plan 模式：传递工具规格（内置 + 插件，统一来自注册表）
            // 会话客户端能力过滤（Phase C：office/lowa/none）在注册表内完成；
            // Skill 命中时由 SkillRouter 做可见性白名单裁剪（Phase 3B，未命中原样返回）
            List<ToolSpecification> allTools = skillRouter.visibleTools(conversationId, toolRegistry.getAllSpecifications(conversationId));
            model.generate(messages, allTools, handler);
        }
    }

    /**
     * 切到备选模型重放本轮。返回 true 表示本次错误已被接管（调用方不要再走终态处置）。
     *
     * <p>计费红线：这里只换 modelId，通道仍由 ChatModelFactory.resolveProvider() 决定。
     * 平台通道下拿不到密钥会抛 AccountException，此时直接把中文文案透给用户并终止，
     * 绝不退回 BYOK——那会拿用户自己的 key 花钱（Spec §3）。
     */
    private boolean switchToFailoverModel(String nextModelId, LlmErrorClassifier.Kind kind, Throwable err,
                                          java.util.List<dev.langchain4j.data.message.ChatMessage> messages,
                                          String conversationId, String projectId, Long userId,
                                          String failedModelId, int depth, StringBuilder executionLog,
                                          AgentMode agentMode, RunGuard guard) {
        log.warn("Failing over from {} to {} for {} [{}]: {}",
                failedModelId, nextModelId, conversationId, kind, String.valueOf(err));

        StreamingChatLanguageModel nextModel;
        try {
            nextModel = chatModelFactory.getStreamingChatModel(nextModelId);
        } catch (com.checkba.service.account.AccountException ae) {
            log.info("故障转移中止，平台通道不可用 [{}]，会话 {}: {}", ae.getKind(), conversationId, ae.getMessage());
            agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.ERROR);
            sseEmitterService.send(conversationId, "error", ae.getMessage());
            closeSse(conversationId);
            clearCancelledState(conversationId);
            return true;
        } catch (Exception e) {
            log.error("Failed to create failover model {} for {}", nextModelId, conversationId, e);
            return false;
        }
        if (nextModel == null) {
            log.error("Failover model {} unavailable for {}", nextModelId, conversationId);
            return false;
        }

        // 换模型等于换了一条通道，重试预算重新计
        guard.llmRetries = 0;
        sendTextDelta(conversationId, String.format(
                LangText.of("\n\n> 模型「%s」%s，已自动切换到备用模型「%s」继续本轮任务。\n\n",
                        "\n\n> Model \"%s\" %s; automatically switched to fallback model \"%s\" to continue this round.\n\n"),
                failedModelId, kind.userFacingReason(), nextModelId));
        try {
            runLoop(nextModel, messages, conversationId, projectId, userId, nextModelId,
                    depth, executionLog, agentMode, guard);
        } catch (Exception e) {
            log.error("Failover runLoop failed for {}", conversationId, e);
            handleStreamErrorTerminal(conversationId, projectId, userId, e, null);
        }
        return true;
    }

    /**
     * 流式错误的终态处置（重试预算耗尽 / 不可重试错误 / 已流出部分内容）：
     * 发 error 事件、保存部分内容、关流、复位状态。原 setOnError 内联逻辑提取而来。
     *
     * @param kind 错误分类，可为 null（取消分支/重放失败等拿不到分类的路径）。
     *             非 null 时经 {@link LlmErrorClassifier#taggedErrorMessage} 给 SSE 载荷加机器可读标记：
     *             地域拒绝会带上 AI_REGION_BLOCKED，前端据此把上游英文原文换成中文引导
     *             （见 useAgentStream.js 的 includes 检测）。不带标记的话前端只能显示英文原文。
     */
    private void handleStreamErrorTerminal(String conversationId, String projectId, Long userId,
                                           Throwable err, LlmErrorClassifier.Kind kind) {
        String message = kind == null
                ? err.getMessage()
                : LlmErrorClassifier.taggedErrorMessage(kind, err.getMessage());
        finishWithError(conversationId, projectId, userId, "Stream Error: " + message, null);
    }

    /**
     * 错误终态的统一收尾：发 error 事件、保存已生成内容、关流、复位状态。
     *
     * <p>{@link #handleStreamErrorTerminal}（流式传输出错）与 onComplete 的 catch
     * （回调内部一致性错误）共用这一条路径——两处都必须落库，否则那一轮在历史里
     * 一个字都没有。
     *
     * @param ssePayload 直接下发给前端的 error 载荷，标记（AI_REGION_BLOCKED /
     *                   AI_INTERNAL_ERROR 等）由调用方拼好
     * @param executionLog 本轮已产生的工具过程日志，可为 null；非空时一并落库，
     *                     崩溃轮的过程卡才能在历史里回放
     */
    private void finishWithError(String conversationId, String projectId, Long userId,
                                 String ssePayload, StringBuilder executionLog) {
        sseEmitterService.send(conversationId, "error", ssePayload);
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
        String logText = executionLog != null ? executionLog.toString() : "";
        if (!partialContent.isEmpty() || !logText.isEmpty()) {
            saveAssistantMessage(conversationId, projectId, userId,
                    logText + partialContent
                            + LangText.of("\n\n[生成出错，已中断]", "\n\n[Generation error, interrupted]"));
        }
        closeSse(conversationId);
        clearCancelledState(conversationId);
        editorBridgeService.clearCurrentConversationId();
    }

    /**
     * 超阈值时原地折叠中段消息。压缩失败/未触发都原样保留，绝不因此让一轮对话失败。
     */
    private void compactIfNeeded(java.util.List<dev.langchain4j.data.message.ChatMessage> messages,
                                 String conversationId, String modelId) {
        try {
            java.util.List<dev.langchain4j.data.message.ChatMessage> compacted =
                    runLoopCompactor.compact(messages, modelId);
            if (compacted != messages) {
                log.info("Compacted context for {}: {} -> {} messages",
                        conversationId, messages.size(), compacted.size());
                // 先拷贝再清空：压缩结果若含 subList 视图，clear() 会把它一并清空，
                // 之后 addAll 进去的就是空列表——静默丢掉整个上下文
                java.util.List<dev.langchain4j.data.message.ChatMessage> snapshot =
                        new java.util.ArrayList<>(compacted);
                messages.clear();
                messages.addAll(snapshot);
            }
        } catch (Exception e) {
            log.warn("Context compaction skipped for {} due to error", conversationId, e);
        }
    }

    /**
     * 空响应判定（对标 dsh EMPTY_RESPONSE）：无工具调用、正文空白、且流式过程零 token。
     * 最后一个条件是双保险——只要有 token 给用户看过，就绝不能悄悄重放（会看到重复内容）。
     */
    static boolean isEmptyResponse(dev.langchain4j.data.message.AiMessage aiMessage, boolean streamedTokens) {
        if (streamedTokens) return false;
        if (aiMessage == null) return true;
        return !aiMessage.hasToolExecutionRequests()
                && (aiMessage.text() == null || aiMessage.text().isBlank());
    }

    /**
     * LENGTH 截断轮判定：finishReason=LENGTH 且带工具调用。此时参数大概率不完整，
     * 「恰好仍可解析」比「解析失败」更危险（半截参数的写类工具会造成半篇覆盖），一律不执行。
     * finishReason 为 null（部分通道不回）时不判截断，行为与改造前一致。
     */
    static boolean isTruncatedToolCallRound(dev.langchain4j.model.output.FinishReason finishReason,
                                            dev.langchain4j.data.message.AiMessage aiMessage) {
        return finishReason == dev.langchain4j.model.output.FinishReason.LENGTH
                && aiMessage != null && aiMessage.hasToolExecutionRequests();
    }

    /**
     * 空响应处置：按瞬时错误的退避预算重试本轮（空 AiMessage 不入栈，栈未污染可同 depth 重放）；
     * 预算耗尽转终态错误——比静默 FINISHED 强，用户至少知道出了什么事、可以重发。
     */
    private void handleEmptyResponse(StreamingChatLanguageModel model,
                                     java.util.List<dev.langchain4j.data.message.ChatMessage> messages,
                                     String conversationId, String projectId, Long userId, String modelId,
                                     int depth, StringBuilder executionLog, AgentMode agentMode, RunGuard guard) {
        LlmErrorClassifier.Kind kind = LlmErrorClassifier.Kind.TRANSIENT;
        if (guard.llmRetries < kind.maxRetries()) {
            int attempt = ++guard.llmRetries;
            long delaySec = kind.retryDelaySeconds(attempt);
            log.warn("Empty LLM response for {} (attempt {}/{}), retrying in {}s",
                    conversationId, attempt, kind.maxRetries(), delaySec);
            sendTextDelta(conversationId, String.format(
                    LangText.of("\n\n> 模型返回了空响应，%d 秒后自动重试（第 %d/%d 次）…\n\n",
                            "\n\n> The model returned an empty response; retrying in %d s (attempt %d/%d)…\n\n"),
                    delaySec, attempt, kind.maxRetries()));
            // 同 onError 的重试路径：定时器线程不继承平台通道身份，必须显式重建
            LLM_RETRY_SCHEDULER.schedule(PlatformAiUserScope.wrap(() -> {
                try {
                    runLoop(model, messages, conversationId, projectId, userId, modelId,
                            depth, executionLog, agentMode, guard);
                } catch (Exception retryEx) {
                    log.error("Empty-response retry failed for {}", conversationId, retryEx);
                    handleStreamErrorTerminal(conversationId, projectId, userId, retryEx, null);
                }
            }), delaySec, java.util.concurrent.TimeUnit.SECONDS);
            return;
        }
        log.error("Empty LLM response persisted after retries for {}", conversationId);
        handleStreamErrorTerminal(conversationId, projectId, userId,
                new IllegalStateException(LangText.of("模型连续返回空响应，请稍后重发这条消息",
                        "The model kept returning empty responses; please resend this message later")), kind);
    }

    /**
     * 溢出后的强制压缩：跳过阈值判断做剪枝 + 折叠，原地替换消息栈（同 compactIfNeeded 的
     * 先拷贝再清空口径）。返回 true = 确实缩小了，可以重放一次。
     */
    private boolean forceCompactAfterOverflow(java.util.List<dev.langchain4j.data.message.ChatMessage> messages,
                                              String conversationId, String modelId) {
        try {
            java.util.List<dev.langchain4j.data.message.ChatMessage> compacted =
                    runLoopCompactor.forceCompact(messages, modelId);
            if (compacted == messages) {
                return false;
            }
            log.info("Forced compaction after overflow for {}: {} -> {} messages",
                    conversationId, messages.size(), compacted.size());
            java.util.List<dev.langchain4j.data.message.ChatMessage> snapshot =
                    new java.util.ArrayList<>(compacted);
            messages.clear();
            messages.addAll(snapshot);
            return true;
        } catch (Exception e) {
            log.warn("Forced compaction after overflow failed for {}", conversationId, e);
            return false;
        }
    }

    // =================================================================================
    // Loop guard & SSE helpers
    // =================================================================================

    /**
     * 通过 text_delta 事件向前端推送一段内容（与流式 token 走同一渲染管线）。
     */
    /**
     * 推一段正文。
     *
     * <p>信封必须用真正的 JSON 序列化器拼（dev-board#288）：手写的四条 replace
     * 只处理了 \\ " \n \r，**漏掉了制表符与其余控制字符**。模型从表格/代码块里带出一个
     * Tab（法律文书里的表格内容极常见），这条 text_delta 就是非法 JSON——
     * 客户端 `JSON.parse` 抛错后按原文渲染，用户看到的是 `{"content":"…` 这一串信封本身。
     * Jackson 的 writeValueAsString 会把 U+0000-U+001F 全部转义，这类问题一次性绝迹。
     */
    private void sendTextDelta(String conversationId, String content) {
        sseEmitterService.send(conversationId, "text_delta", jsonContentEnvelope(content));
    }

    /** {"content": "..."} 信封，转义交给 Jackson。序列化失败时退回不带正文的空信封而不是发出非法 JSON。 */
    static String jsonContentEnvelope(String content) {
        try {
            return "{\"content\":" + SKILL_UPDATE_MAPPER.writeValueAsString(content == null ? "" : content) + "}";
        } catch (Exception e) {
            return "{\"content\":\"\"}";
        }
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s
                : s.substring(0, tagSafeCut(s, max)) + LangText.of("...(截断)", "...(truncated)");
    }

    /**
     * 截断点回退：不许把切口留在一个还没闭合的协议标签形状中间。
     *
     * <p>截断先于 {@link AgentTagProtocol#escape} 发生（截断口径按原文字数，与前端
     * 「...(截断)」提示一致）。切口若落在 {@code <tool_output status="SUC} 这种半截标签里，
     * 它没有 {@code >}，中和认不出、原样放行；紧随其后的是截断后缀和外层自己的
     * {@code </tool_output>}，前端 tagRegex 的 {@code [^>]*} 属性段会一路吃到那个闭合标签的
     * {@code >}——真正的闭合被当成属性吞掉，本轮剩下的正文全被塞进折叠区、工具行一直转圈。
     *
     * <p>只回退「还能长成已知标签」的形状：合同里的 {@code <甲方}、比较符 {@code a < b}
     * 不是标签形状，照旧按字数切，展示内容不凭空少一截。
     */
    private static int tagSafeCut(String s, int max) {
        int lt = s.lastIndexOf('<', max - 1);
        if (lt < 0) return max;
        // 切口之前已经闭合过，这个 '<' 不是半截标签
        if (s.lastIndexOf('>', max - 1) > lt) return max;
        boolean closing = lt + 1 < max && s.charAt(lt + 1) == '/';
        String body = s.substring(closing ? lt + 2 : lt + 1, max);
        for (String tag : AgentTagProtocol.TAGS) {
            // 标签名还没写全，或标签名已写全、后面正在写属性
            if (tag.startsWith(body)
                    || (body.startsWith(tag) && Character.isWhitespace(body.charAt(tag.length())))) {
                return lt;
            }
        }
        return max;
    }

    /** 工具输出在面板上的默认展示上限（历史落库存的是全文，这里只是 SSE 载荷） */
    private static final int TOOL_OUTPUT_DISPLAY_LIMIT = 4000;
    /** 结果型工具的展示上限：它们的输出本身就是要给用户核验的成果 */
    private static final int RESULT_TOOL_OUTPUT_DISPLAY_LIMIT = 16000;
    /**
     * 「输出即成果」的工具：截断会砍掉用户最需要核对的那一段，
     * 且 dispatch_subtask 的输出是 JSON（截断后前端结构化子任务卡直接解析失败退回裸文本）。
     * 放宽只影响 SSE 载荷大小，不进上下文、不影响 token 与计费。
     */
    private static final Set<String> RESULT_HEAVY_TOOLS =
            Set.of("dispatch_subtask", "extract_file_text", "pdf_inspect",
                    // 结构审计报告本身就是给用户核对的成果（编号/引用/算术/修订清单），砍到 4000 用户看不到后半
                    "doc_audit_structure");

    /** 该工具的面板展示上限。前端截断提示按 {@code ...(截断)} 后缀判定，不要在文案里写死字数。 */
    static int toolOutputDisplayLimit(String toolName) {
        return toolName != null && RESULT_HEAVY_TOOLS.contains(toolName)
                ? RESULT_TOOL_OUTPUT_DISPLAY_LIMIT
                : TOOL_OUTPUT_DISPLAY_LIMIT;
    }

    /** 反问标签的起始形态：{@code <question>}、带属性的 {@code <question type=...>}、以及跨行写法都算。 */
    private static final java.util.regex.Pattern QUESTION_TAG_START =
            java.util.regex.Pattern.compile("<question(?=[\\s/>])", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 助手正文里是否含反问标签。
     *
     * <p>刻意只认起始标签：模型把 {@code </question>} 漏掉（截断/笔误）时，问题正文已经流给
     * 用户看了，此时按「有问题」停机远好过当成正常收尾——后者会让用户对着一个没有下文的
     * 问句，而会话状态显示已完成。
     */
    /** 开了必须闭的结构标签（截断守卫用）。 */
    private static final java.util.List<String> TRUNCATION_GUARDED_TAGS =
            java.util.List.of("tool_code", "todo_write", "final");

    /**
     * 输出是不是在某个结构标签里被截断了。
     *
     * <p>原来只看 `<tool_code>`：模型在 `<todo_write>` 的长 JSON 里被切断时不算，回合就静默收尾——
     * 尽调起草实测一轮里连丢四个回合（每次只输出 12-500 字符就断），用户看到的是「什么都没发生」。
     */
    static boolean endsWithUnclosedTag(String content) {
        if (content == null || content.isBlank()) return false;
        for (String tag : TRUNCATION_GUARDED_TAGS) {
            // 开标签用前缀匹配：截断可能发生在开标签自己身上（实测最短的一次只输出了 "<todo_write"）
            int open = content.lastIndexOf("<" + tag);
            if (open < 0) continue;
            int close = content.lastIndexOf("</" + tag + ">");
            if (close < open) return true;
        }
        return false;
    }

    static boolean containsQuestion(String content) {
        return content != null && QUESTION_TAG_START.matcher(content).find();
    }

    /**
     * 反问停机：保存本轮回复、打 AWAITING_INPUT 状态点、发 bubble_end 关流，**不递归**。
     *
     * <p>形态照抄 implementation_plan 的停机待审批：答案是**下一轮普通用户消息**，
     * 不做「工具调用里阻塞等人类回答」——工具分发跑在流式回调线程上，撞 600s callTimeout
     * 与 180s 无活动看门狗，taskExecutor 也只有 16/32；而律师完全可能关掉 app 明天再来答。
     *
     * <p>刻意不触发记忆管线与版本落档（与待审批一致）：本轮还没结束，用户答完的那一轮
     * 收尾时会一并跑。
     *
     * <p>连续反问不会被守卫误伤：RunGuard（含 StuckDetector 滑动窗口、步数预算、重试预算）
     * 每次 handleUserMessage 新建，而用户的回答就是新一轮消息，所以「答一个又被问下一个」
     * 是两个独立的 run；StuckDetector 也只记录工具调用签名，反问本身根本不进窗口。
     * 若哪天把 RunGuard 改成跨轮复用，必须让反问轮不计入打转窗口与步数预算。
     */
    private void stopForUserQuestion(String conversationId, String projectId, Long userId, String persistedContent) {
        log.info("Detected <question> for {}, stopping loop and waiting for user answer", conversationId);
        saveAssistantMessage(conversationId, projectId, userId, persistedContent);
        agentRunStateService.mark(conversationId, AgentRunStateService.RunStatus.AWAITING_INPUT);
        // status=awaiting_input：会话列表显示「待回答」（区别于待审批），前端解锁输入区
        sseEmitterService.send(conversationId, "bubble_end", "{\"status\":\"awaiting_input\"}");
        closeSse(conversationId);
        clearCancelledState(conversationId);
    }

    /**
     * 打转首次干预的提示语。必须作为独立消息挂在整栈末位——只写进 system prompt 的行为约束
     * 会被弱模型稳定无视（PR#209 真机实证）。
     */
    static String stuckInterventionMessage(String pattern) {
        return "[系统提醒] 你" + (pattern == null ? "在重复相同的操作序列" : pattern)
                + "，再这样下去无法推进任务。请换一种思路：改用其他工具、调整参数，"
                + "或先用读取类工具确认当前真实状态；如果任务其实已经完成，请直接输出最终总结。";
    }

    /**
     * 打转二次检出的熔断反馈：本次调用不执行，反馈作为工具结果回给模型。
     * 保留 Error 前缀——ToolResult.success() 与前端任务卡都按它判定失败。
     */
    static String stuckCircuitBreakFeedback(String pattern) {
        return "Error: 检测到你" + (pattern == null ? "在重复相同的操作序列" : pattern)
                + "，且系统已提醒过一次，本次调用已被拦截。"
                + "请不要原样重试：换一种方法（其他工具、调整参数或先读取文档确认状态）；"
                + "如果任务已无法继续，请输出 <final> 向用户说明目前进展和遇到的问题。";
    }

    /**
     * 故障转移候选：按配置顺序挑第一个「不是当前模型、没试过、且在白名单内」的模型。
     *
     * <p>必须在白名单内：非白名单模型会被 ChatModelFactory 静默回落到默认模型，切了等于没切。
     * 只换模型不换通道——通道由 ChatModelFactory.resolveProvider() 决定，与 modelId 无关，
     * 平台通道（AWD_CLOUD）永远拿平台密钥，不存在被切回 BYOK 花用户自己 key 的路径。
     */
    static String nextFailoverModel(List<String> candidates, String currentModel, Set<String> tried) {
        return nextFailoverModel(candidates, currentModel, tried, false, false);
    }

    static String nextFailoverModel(List<String> candidates, String currentModel, Set<String> tried,
                                    boolean regionAgnosticOnly) {
        return nextFailoverModel(candidates, currentModel, tried, regionAgnosticOnly, false);
    }

    /**
     * 同上，额外支持两维收窄。
     *
     * @param regionAgnosticOnly true 时只接受 Region.GLOBAL 的候选。地域拒绝（403 region）就必须这样：
     *                           境内网络下换一个同样是 INTERNATIONAL 的模型只会再撞一次 403。
     *                           收窄后可能一个候选都不剩——那就走终态处置，比白花几次请求诚实。
     * @param visionOnly         true 时只接受支持视觉输入的候选。消息栈里有图像内容块时必须这样：
     *                           故障转移只换 modelId 不换消息栈，切给读不了图的模型是一个必然的 400。
     */
    static String nextFailoverModel(List<String> candidates, String currentModel, Set<String> tried,
                                    boolean regionAgnosticOnly, boolean visionOnly) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String model = candidate.trim();
            if (model.equalsIgnoreCase(currentModel)) {
                continue;
            }
            if (tried != null && tried.contains(model.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            AllowedModels allowed = AllowedModels.fromId(model);
            if (allowed == null) {
                continue;
            }
            if (regionAgnosticOnly && allowed.getRegion() != AllowedModels.Region.GLOBAL) {
                continue;
            }
            if (visionOnly && !allowed.isVision()) {
                continue;
            }
            return model;
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
