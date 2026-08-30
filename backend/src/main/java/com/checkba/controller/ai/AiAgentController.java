package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.model.ai.AgentMode;
import com.checkba.service.LangText;
import com.checkba.service.ai.SseEmitterService;
import com.checkba.service.ai.AgentOrchestrator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 核心 Agent 控制器 (v2)。
 * 取代旧的 AiChatController，使用 SSE 进行全双工（逻辑上）通信。
 */
@RestController
@RequestMapping("/api/agent")
public class AiAgentController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiAgentController.class);

    private final SseEmitterService sseEmitterService;
    private final AgentOrchestrator agentOrchestrator;
    private final com.checkba.service.ProjectAiMessageService messageService;
    private final com.checkba.service.ai.BackgroundTaskService backgroundTaskService;

    private final com.checkba.service.ai.tools.PptxTools pptxTools;
    private final com.checkba.service.ai.TodoListService todoListService;
    private final com.checkba.service.ai.AgentRunStateService agentRunStateService;
    private final com.checkba.service.ProjectMemberService projectMemberService;
    private final com.checkba.service.ai.ClientCapabilityService clientCapabilityService;
    private final com.checkba.service.ai.subagent.SubAgentService subAgentService;

    @org.springframework.beans.factory.annotation.Autowired
    public AiAgentController(SseEmitterService sseEmitterService,
                            AgentOrchestrator agentOrchestrator,
                            com.checkba.service.ProjectAiMessageService messageService,
                            com.checkba.service.ai.BackgroundTaskService backgroundTaskService,
                            com.checkba.service.ai.tools.PptxTools pptxTools,
                            com.checkba.service.ai.TodoListService todoListService,
                            com.checkba.service.ai.AgentRunStateService agentRunStateService,
                            com.checkba.service.ProjectMemberService projectMemberService,
                            com.checkba.service.ai.ClientCapabilityService clientCapabilityService,
                            com.checkba.service.ai.subagent.SubAgentService subAgentService) {
        this.sseEmitterService = sseEmitterService;
        this.agentOrchestrator = agentOrchestrator;
        this.messageService = messageService;
        this.backgroundTaskService = backgroundTaskService;
        this.pptxTools = pptxTools;
        this.todoListService = todoListService;
        this.agentRunStateService = agentRunStateService;
        this.projectMemberService = projectMemberService;
        this.clientCapabilityService = clientCapabilityService;
        this.subAgentService = subAgentService;
    }

    /**
     * 会话可用性校验：会话尚无消息时视为新会话，任何已登录用户都可占用；
     * 一旦有了历史，只有首条消息的作者可以再连接/续写。
     * conversationId 由前端按 conv-<毫秒时间戳> 生成、并非机密，若不校验归属，
     * 猜到即可接管他人的 SSE 输出流（含文档正文与 editor 指令的 requestId）。
     */
    private boolean canUseConversation(String conversationId, Long userId) {
        // 口径收敛到 ProjectAiMessageService：此前这条规则只在本控制器有，
        // AiChatController 取历史用的是严格归属，导致新会话一进项目就 403
        return messageService.canUseConversation(conversationId, userId);
    }

    /**
     * 建立 SSE 连接。
     * 前端应在进入聊天界面时调用此接口。
     */
    @GetMapping(value = "/connect/{conversationId}", produces = "text/event-stream")
    public ResponseEntity<SseEmitter> connect(@PathVariable String conversationId,
                              @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                              @RequestHeader(value = "X-Client-Instance", required = false) String clientInstance,
                              @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                              HttpServletResponse response) {
        // 归属校验：emitter 表只按 conversationId 索引且新连接直接覆盖旧连接，
        // 此前 userId 仅用于打日志，任何人猜到会话 ID 即可劫持他人的整条输出流
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (!canUseConversation(conversationId, userId)) {
            return ResponseEntity.status(403).build();
        }

        // 添加响应头禁用缓冲，确保流式响应实时到达客户端
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("X-Accel-Buffering", "no");  // 禁用 Nginx 代理缓冲
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");

        log.info("Client connecting to SSE: conversationId={}, userId={}", conversationId, userId);
        // clientInstance = 任务窗格实例身份（缺省 null：桌面端与旧版插件不上送，行为不变）。
        // 换了实例时后端做一次性移交而不是无声互顶，见 SseEmitterService.createConnection。
        // lastEventId = SSE 规范的断点续传游标（缺省 null：桌面端与旧版插件不上送，行为不变）。
        SseEmitter emitter = sseEmitterService.createConnection(conversationId, clientInstance, lastEventId);
        
        // Check for active stream recovery
        String snapshot = agentOrchestrator.getRecoverySnapshot(conversationId);
        // RUNNING 但快照为空（上下文组装中/首 token 未到/纯 function-calling 轮）也必须发
        // state_recovery：前端靠它重建气泡指针，否则后续 text_delta 乃至 bubble_end 全被
        // 空指针守卫丢弃，isStreaming 永久锁死（F-06 确定性 hang）
        if (snapshot == null
                && com.checkba.service.ai.AgentRunStateService.RunStatus.RUNNING.name()
                        .equals(agentRunStateService.statusName(conversationId))) {
            snapshot = "";
        }
        if (snapshot != null) {
            log.info("Recovering active stream for conversation: {} ({} chars)", conversationId, snapshot.length());
            // Send recovery event immediately after connection established
            // Use a small delay or ensure SseEmitterService sends it properly
            // SseEmitterService.createConnection sends initial "connected" event.
            // We can send this right after.
            sseEmitterService.send(conversationId, "state_recovery", "{\"content\":\"" +
                snapshot.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") +
                "\"}");
        }

        // 重连后把当前任务清单重推给前端（常驻进度卡恢复）
        todoListService.resendToFrontend(conversationId);

        // 告知前端此会话的运行状态（RUNNING=续流中 / PAUSED=渲染继续按钮 /
        // AWAITING_APPROVAL=等审批 / AWAITING_INPUT=等用户回答模型的反问 /
        // null=本进程内没跑过）——切回会话重连时，前端靠它决定展示「运行中」还是纯静态历史。
        // 载荷用 AgentRunStateService.statusName() 原样透出枚举名（大写），新增状态无需改本处代码。
        String runStatus = agentRunStateService.statusName(conversationId);
        sseEmitterService.send(conversationId, "run_state",
                "{\"status\":" + (runStatus == null ? "null" : "\"" + runStatus + "\"") + "}");

        return ResponseEntity.ok(emitter);
    }

    /**
     * 发送用户消息 (触发 Agent 思考)。
     * 这是一个异步接口，立刻返回 200 OK，后续通过 SSE 推送结果。
     */
    @PostMapping("/chat")
    public ResponseEntity<?> startSession(@RequestBody AgentChatRequest request,
                                          @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        // 越权校验：此前未登录也会起循环，且 projectId 完全由请求体给定——
        // ToolRegistry 把 projectId 强制注入工具参数只挡得住 LLM，挡不住 HTTP 调用方，
        // 于是「按项目隔离」的工具反而成了跨租户读写别家文档的入口
        if (userId == null) {
            return ResponseEntity.status(401).body("{\"status\":\"error\", \"message\":\"请先登录\"}");
        }
        if (request.getProjectId() == null || !projectMemberService.hasReadPermission(request.getProjectId(), userId)) {
            return ResponseEntity.status(403).body("{\"status\":\"error\", \"message\":\"" +
                    LangText.of("无权访问该项目", "You do not have access to this project") + "\"}");
        }
        if (!canUseConversation(request.getConversationId(), userId)) {
            return ResponseEntity.status(403).body("{\"status\":\"error\", \"message\":\"" +
                    LangText.of("无权操作该会话", "You do not have permission for this conversation") + "\"}");
        }
        // message 为空/纯空白必须在入口拒绝：一旦落库，ContextAssemblerService 回放历史时
        // langchain4j 的 UserMessage.from(text) 会对空白文本抛异常——存量脏数据已经在
        // ContextAssemblerService 里加了容错，但新请求应该在这里就被挡下，不该先污染会话。
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return ResponseEntity.status(400).body("{\"status\":\"error\", \"message\":\"" +
                    LangText.of("消息内容不能为空", "Message cannot be empty") + "\"}");
        }

        log.info("Received Agent Chat Request: project={}, conversation={}, mode={}, msg={}",
                request.getProjectId(), request.getConversationId(), request.getAgentMode(), request.getMessage());

        // 会话级客户端能力登记（Phase C）：lowa（默认，主前端）/ office（Office 插件）/ none（纯对话）；
        // office 会话再按宿主细分（word / excel / powerpoint，缺省 word），工具可见性按宿主过滤
        clientCapabilityService.record(request.getConversationId(), request.getClientCapability(),
                request.getOfficeHost());

        agentOrchestrator.handleUserMessage(request, userId);
        
        return ResponseEntity.ok().build();
    }

    /**
     * 取消正在进行的 AI 生成。
     * 前端调用此接口通知后端停止生成。
     */
    @PostMapping("/cancel/{conversationId}")
    public ResponseEntity<?> cancelGeneration(@PathVariable String conversationId,
                                              @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        // 归属校验：否则任何人猜到会话 ID 就能掐断别人正在跑的生成
        if (!canUseConversation(conversationId, userId)) {
            return ResponseEntity.status(403).body("{\"status\":\"error\", \"message\":\"" +
                    LangText.of("无权操作该会话", "You do not have permission for this conversation") + "\"}");
        }

        log.info("Cancel request received: conv={}, user={}", conversationId, userId);
        
        try {
            agentOrchestrator.setCancelled(conversationId);
            return ResponseEntity.ok().body("{\"status\":\"ok\", \"message\":\"Cancellation requested\"}");
        } catch (Exception e) {
            log.error("Cancel failed", e);
            return ResponseEntity.status(500).body("{\"status\":\"error\", \"message\":\"Cancel failed\"}");
        }
    }

    /**
     * 停止一个正在跑的子任务（长任务可控：只掐这一个子任务，主循环继续）。
     *
     * <p>与 /cancel/{conversationId} 的区别：那个掐整轮生成，这个只掐一个 dispatch_subtask，
     * 主循环会拿到「用户停了这个子任务」的结果继续往下走——所以<b>不打运行状态点</b>：
     * 会话仍是 RUNNING，没有新增终止分支（PR#173 契约要求的 mark 只针对轮次终态）。
     *
     * <p>文案只说「正在停止」：cancel(true) 打不断已经发出去的 HTTP 调用，
     * 最坏浪费一次在途 LLM 调用后才真正停下（见 SubAgentService#cancel）。
     */
    @PostMapping("/subtask/cancel")
    public ResponseEntity<?> cancelSubtask(@RequestBody SubtaskCancelRequest request,
                                           @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (!canUseConversation(request.getConversationId(), userId)) {
            return ResponseEntity.status(403).body("{\"status\":\"error\", \"message\":\"" +
                    LangText.of("无权操作该会话", "You do not have permission for this conversation") + "\"}");
        }
        // 第二道校验在服务里：子任务必须登记在这个会话名下（光有会话权限还不够）
        boolean requested = subAgentService.cancel(request.getSubtaskId(), request.getConversationId());
        if (!requested) {
            return ResponseEntity.status(404)
                    .body("{\"status\":\"error\", \"message\":\"" +
                            LangText.of("该子任务已经结束，无需停止", "This subtask has already finished; no need to stop it") + "\"}");
        }
        log.info("Subtask cancel requested: conv={}, subtask={}, user={}",
                request.getConversationId(), request.getSubtaskId(), userId);
        return ResponseEntity.ok().body("{\"status\":\"ok\", \"message\":\"" +
                LangText.of("正在停止该子任务", "Stopping this subtask") + "\"}");
    }

    /**
     * 停止一个正在跑的后台任务（PPT 生成等）。
     *
     * <p>{@code BackgroundTaskService.cancelTask} 早就实现完整，但全仓一个调用方都没有——
     * 前端的进度卡因此只能干等到超时。本端点把它接上。
     *
     * <p>同样只说「正在停止」：取消只改任务簿记并广播 background_task_complete，
     * 已经交给 pptx-service 的活儿会继续跑完、文件照样落盘。
     */
    @PostMapping("/tasks/cancel")
    public ResponseEntity<?> cancelBackgroundTask(@RequestBody TaskCancelRequest request,
                                                  @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (!canUseConversation(request.getConversationId(), userId)) {
            return ResponseEntity.status(403).body("{\"status\":\"error\", \"message\":\"" +
                    LangText.of("无权操作该会话", "You do not have permission for this conversation") + "\"}");
        }
        boolean cancelled = backgroundTaskService.cancelTask(request.getTaskId(), request.getConversationId());
        if (!cancelled) {
            return ResponseEntity.status(404)
                    .body("{\"status\":\"error\", \"message\":\"" +
                            LangText.of("该任务已经结束，无需停止", "This task has already finished; no need to stop it") + "\"}");
        }
        log.info("Background task cancel requested: conv={}, task={}, user={}",
                request.getConversationId(), request.getTaskId(), userId);
        return ResponseEntity.ok().body("{\"status\":\"ok\", \"message\":\"" +
                LangText.of("正在停止该任务", "Stopping this task") + "\"}");
    }

    /**
     * 获取指定会话中当前活跃的后台任务。
     * 用于前端断线重连后恢复进度条显示。
     */
    @GetMapping("/tasks/active")
    public ResponseEntity<?> getActiveTasks(@RequestParam String conversationId,
                                            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        // 归属校验：任务信息带文件名与进度描述，不能按会话 ID 裸查
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (!canUseConversation(conversationId, userId)) {
            return ResponseEntity.status(403).body("{\"status\":\"error\", \"message\":\"" +
                    LangText.of("无权操作该会话", "You do not have permission for this conversation") + "\"}");
        }
        try {
            // Get active tasks from BackgroundTaskService
            java.util.List<com.checkba.model.ai.TaskInfo> tasks = 
                backgroundTaskService.getActiveTasksForConversation(conversationId);
            
            return ResponseEntity.ok(tasks);
        } catch (Exception e) {
            log.error("Failed to get active tasks", e);
            return ResponseEntity.status(500).body("{\"status\":\"error\", \"message\":\"Failed to get tasks\"}");
        }
    }

    /**
     * Rollback history to a specific message.
     * Everything after this message will be deleted.
     */
    @PostMapping("/history/rollback")
    public ResponseEntity<?> rollbackHistory(@RequestBody RollbackRequest request,
                                             @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = (sessionId != null) ? AuthController.getUserIdFromSession(sessionId) : null;
        // 归属校验：此前 userId 为 null 也会执行截断（破坏性），且不校验会话归属
        if (userId == null || !messageService.isConversationOwnedBy(request.getConversationId(), userId)) {
            return ResponseEntity.status(403).body("{\"status\":\"error\", \"message\":\"" +
                    LangText.of("无权操作该会话", "You do not have permission for this conversation") + "\"}");
        }
        log.info("Rollback request: conv={}, msgId={}, user={}", request.getConversationId(), request.getMessageId(), userId);

        try {
            messageService.truncateHistory(request.getConversationId(), request.getMessageId());
            return ResponseEntity.ok().body("{\"status\":\"ok\", \"message\":\"History rolled back\"}");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("{\"status\":\"error\", \"message\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            log.error("Rollback failed", e);
            return ResponseEntity.status(500).body("{\"status\":\"error\", \"message\":\"Internal Error\"}");
        }
    }

    /**
     * 执行 PPT 生成 (由前端 UI 确认后调用)
     */
    @PostMapping("/ppt/generate")
    public ResponseEntity<?> performPptGeneration(@RequestBody PptGenerationRequest request,
                                               @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        // 越权校验：此前未登录会顶着写死的 10001 号用户往请求体给定的项目里写文件
        if (userId == null) {
            return ResponseEntity.status(401).body("{\"status\":\"error\", \"message\":\"请先登录\"}");
        }
        if (request.getProjectId() == null || !projectMemberService.hasWritePermission(request.getProjectId(), userId)) {
            return ResponseEntity.status(403).body("{\"status\":\"error\", \"message\":\"" +
                    LangText.of("无权写入该项目", "You do not have write access to this project") + "\"}");
        }

        log.info("Received PPT Generation Request: topic={}, editable={}", request.getTopic(), request.isExportEditable());
        
        // Create final variable for lambda capture
        final Long effectiveUserId = userId;
        
        // Asynchronous execution via PptxTools (which handles background task creation internally)
        // 平台通道按用户计费：后台线程上显式建立身份作用域，否则多租户下取不到 key
        java.util.concurrent.CompletableFuture.runAsync(() ->
            com.checkba.service.ai.PlatformAiUserScope.run(effectiveUserId, () -> {
                String outcome = pptxTools.performPptGenerationWithProgress(
                    request.getTopic(),
                    request.getProjectId(),
                    request.getParentId(),
                    request.getFileName(),
                    request.getStyle(),
                    request.getLanguage(),
                    request.getModelId(),
                    request.getConversationId(),
                    effectiveUserId,
                    request.isExportEditable()
                );
                persistPptOutcome(request, effectiveUserId, outcome);
            }));

        return ResponseEntity.ok().body("{\"status\":\"ok\", \"message\":\"PPT generation started\"}");
    }

    /**
     * PPT 生成结束后把结果落成一条 ASSISTANT 消息。
     *
     * <p>此前这段返回文本被整个丢弃：文件确实生成了，但对话历史里一个字都没有。
     * 后果有两条——① 主 Agent 完全不知道这个文件存在，用户下一句「把刚生成的 PPT 改一下」
     * 它只能重新列文件猜；② 刷新页面后用户自己也看不出发生过什么（进度卡是内存态）。
     *
     * <p>用契约 D 的双通道：{@code content} 给模型（fileId、PPTX 服务项目 ID、可编辑与否、
     * 后续可用工具全在里面），{@code displayContent} 给用户一句人话——那段带工具名的长文本
     * 直接进气泡是机器口吻。落库失败只 log：PPT 已经生成好了，不能因为记一笔失败而报错。
     */
    private void persistPptOutcome(PptGenerationRequest request, Long userId, String outcome) {
        if (request.getConversationId() == null || outcome == null || outcome.isBlank()) {
            return;
        }
        try {
            boolean ok = outcome.startsWith("PPTX 生成成功");
            String label = request.getFileName() != null && !request.getFileName().isBlank()
                    ? request.getFileName()
                    : brief(request.getTopic(), 30);
            // 失败时首行本来就是可读中文（「PPTX 生成失败: …」/「错误：…」），直接当显示文案
            String display = ok
                    ? LangText.of("已生成 PPT：" + label + "。文件已放入项目文件树，可以直接打开，也可以让我继续修改。",
                            "PPT generated: " + label + ". The file has been added to the project file tree — you can open it directly, or ask me to keep editing it.")
                    : outcome.split("\n", 2)[0].trim();
            messageService.saveMessage(String.valueOf(request.getProjectId()), userId,
                    request.getConversationId(), "ASSISTANT", outcome, display);
        } catch (Exception e) {
            log.warn("PPT 生成结果落库失败（不影响已生成的文件）: {}", e.getMessage());
        }
    }

    private String brief(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }

    /** 子任务停止请求体（两个字段都必填：会话用来鉴权，subtaskId 指定停哪一个） */
    @Data
    public static class SubtaskCancelRequest {
        private String conversationId;
        private String subtaskId;
    }

    /** 后台任务停止请求体（同上，taskId 是 registerTask 返回的 UUID） */
    @Data
    public static class TaskCancelRequest {
        private String conversationId;
        private String taskId;
    }

    @Data
    public static class PptGenerationRequest {
        private String topic;
        private Long projectId;
        private Long parentId;
        private String fileName;
        private String style;
        private String language;
        private String modelId;
        private String conversationId;
        private boolean exportEditable;
    }

    @Data
    public static class RollbackRequest {
        private String conversationId;
        private Long messageId; // The ID of the message to revert TO (keep this one, delete newer)

        public String getConversationId() { return conversationId; }
        public void setConversationId(String conversationId) { this.conversationId = conversationId; }
        public Long getMessageId() { return messageId; }
        public void setMessageId(Long messageId) { this.messageId = messageId; }
    }
    
    public static class AgentChatRequest {
        private Long projectId;
        private String conversationId;
        private String message;
        /**
         * 可选：这条消息在用户气泡里显示的文本（契约 D「发送内容 ≠ 显示内容」）。
         * 落库进 project_ai_message.display_content；<b>模型永远只看 message</b>。
         * 缺省 null = 与本字段不存在时完全一致（存量客户端不受影响）。
         *
         * <p>用途：点一个按钮/选项时，message 要带模型需要的细节（如计划审批卡回喂的
         * 「已修订 N 处 + 修订版全文」），displayText 只给用户一句人话（「按此推进」）。
         * 反问的选项本来就短、像用户自己打的，message 直接用选项原文、本字段可省。
         */
        private String displayText;
        private String model; // e.g. "anthropic/claude-3.5-sonnet"
        private String mode;  // Agent 模式: ASK, PLAN, AGENT (默认 AGENT)
        private java.util.List<String> fileIds; // Legacy: Context files to inject 
        private java.util.List<ContextItem> contextItems; // New: Full context metadata
        private ContextItem activeContext; // NEW: Auto-detected active tab (current document)
        /**
         * @deprecated 单选时代的字段，已收编为「只有一项的 {@link #skillIds}」。
         * 保留只为兼容不发 skillIds 的存量客户端；新客户端一律用 skillIds。
         */
        @Deprecated
        private String pinnedSkillId;
        /**
         * 可选：用户在对话面板里主动选择的 Skill id 列表，本轮<b>强制生效</b>——
         * 同时注入 prompt 与参与工具可见性（两者口径同源，见 SkillRouter.activateForTurn）。
         *
         * <p>与触发词自动命中取<b>并集</b>；无效 id（不存在/已停用/当前语言不可用）静默忽略。
         * <b>无状态</b>：后端不持久化，前端每次请求携带——用户勾掉一个，下一条消息就真的不带它。
         * ASK 模式下整体不生效（该模式不传工具、也不注入 skill 指引）。
         */
        private java.util.List<String> skillIds;
        /**
         * 可选：客户端文档编辑能力（lowa / office / none，Phase C）。
         * 缺省按 lowa 处理，兼容不发送该字段的存量主前端。
         */
        private String clientCapability;
        /**
         * 可选：clientCapability=office 时的宿主细分（word / excel / powerpoint）。
         * 缺省按 word 处理，兼容不发送该字段的存量 Word 插件。
         */
        private String officeHost;

        public Long getProjectId() { return projectId; }
        public void setProjectId(Long projectId) { this.projectId = projectId; }
        public String getConversationId() { return conversationId; }
        public void setConversationId(String conversationId) { this.conversationId = conversationId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getDisplayText() { return displayText; }
        public void setDisplayText(String displayText) { this.displayText = displayText; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        /**
         * 获取解析后的 AgentMode 枚举。
         * 默认返回 AGENT 模式。
         */
        public AgentMode getAgentMode() { return AgentMode.fromString(mode); }
        public java.util.List<String> getFileIds() { return fileIds; }
        public void setFileIds(java.util.List<String> fileIds) { this.fileIds = fileIds; }
        public java.util.List<ContextItem> getContextItems() { return contextItems; }
        public void setContextItems(java.util.List<ContextItem> contextItems) { this.contextItems = contextItems; }
        public ContextItem getActiveContext() { return activeContext; }
        public void setActiveContext(ContextItem activeContext) { this.activeContext = activeContext; }
        @Deprecated
        public String getPinnedSkillId() { return pinnedSkillId; }
        @Deprecated
        public void setPinnedSkillId(String pinnedSkillId) { this.pinnedSkillId = pinnedSkillId; }
        public java.util.List<String> getSkillIds() { return skillIds; }
        public void setSkillIds(java.util.List<String> skillIds) { this.skillIds = skillIds; }
        public String getClientCapability() { return clientCapability; }
        public void setClientCapability(String clientCapability) { this.clientCapability = clientCapability; }
        public String getOfficeHost() { return officeHost; }
        public void setOfficeHost(String officeHost) { this.officeHost = officeHost; }
    }
    
    /**
     * Context item representing a file or folder provided by user.
     */
    public static class ContextItem {
        private String id;
        private String name;
        @com.fasterxml.jackson.annotation.JsonProperty("isDir")
        private boolean isDir;
        private String fileType;
        /**
         * 可选：客户端随请求内联携带的文档正文（Office 插件场景——文档在客户端本地，
         * 后端没有对应 fileId 可读）。非空时上下文组装直接采用它，不再走 read_document。
         */
        private String inlineContent;
        /**
         * 可选：inlineContent 的 SHA-256 十六进制（Office 插件的「正文省传」）。
         * 同一会话内文档没变时客户端只上送本字段、不再重传整篇正文，
         * 后端凭它从 InlineContentCache 取回上一轮的正文；未命中即按「无内联正文」处理。
         */
        private String inlineContentHash;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @com.fasterxml.jackson.annotation.JsonProperty("isDir")
        public boolean isDir() { return isDir; }
        public void setIsDir(boolean isDir) { this.isDir = isDir; }
        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }
        public String getInlineContent() { return inlineContent; }
        public void setInlineContent(String inlineContent) { this.inlineContent = inlineContent; }
        public String getInlineContentHash() { return inlineContentHash; }
        public void setInlineContentHash(String inlineContentHash) { this.inlineContentHash = inlineContentHash; }
    }
}
