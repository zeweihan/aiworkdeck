package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.model.ai.AgentMode;
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

    @org.springframework.beans.factory.annotation.Autowired
    public AiAgentController(SseEmitterService sseEmitterService,
                            AgentOrchestrator agentOrchestrator,
                            com.checkba.service.ProjectAiMessageService messageService,
                            com.checkba.service.ai.BackgroundTaskService backgroundTaskService,
                            com.checkba.service.ai.tools.PptxTools pptxTools,
                            com.checkba.service.ai.TodoListService todoListService) {
        this.sseEmitterService = sseEmitterService;
        this.agentOrchestrator = agentOrchestrator;
        this.messageService = messageService;
        this.backgroundTaskService = backgroundTaskService;
        this.pptxTools = pptxTools;
        this.todoListService = todoListService;
    }

    /**
     * 建立 SSE 连接。
     * 前端应在进入聊天界面时调用此接口。
     */
    @GetMapping(value = "/connect/{conversationId}", produces = "text/event-stream")
    public SseEmitter connect(@PathVariable String conversationId, 
                              @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                              HttpServletResponse response) {
        // 添加响应头禁用缓冲，确保流式响应实时到达客户端
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("X-Accel-Buffering", "no");  // 禁用 Nginx 代理缓冲
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        
        // Validate user if needed
        Long userId = AuthController.getUserIdFromSession(sessionId); // Optional validation
        
        log.info("Client connecting to SSE: conversationId={}, userId={}", conversationId, userId);
        SseEmitter emitter = sseEmitterService.createConnection(conversationId);
        
        // Check for active stream recovery
        String snapshot = agentOrchestrator.getRecoverySnapshot(conversationId);
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

        return emitter;
    }

    /**
     * 发送用户消息 (触发 Agent 思考)。
     * 这是一个异步接口，立刻返回 200 OK，后续通过 SSE 推送结果。
     */
    @PostMapping("/chat")
    public ResponseEntity<?> startSession(@RequestBody AgentChatRequest request,
                                          @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = null;
        if (sessionId != null) {
            userId = AuthController.getUserIdFromSession(sessionId);
        }
        
        log.info("Received Agent Chat Request: project={}, conversation={}, mode={}, msg={}", 
                request.getProjectId(), request.getConversationId(), request.getAgentMode(), request.getMessage());

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
        Long userId = null;
        if (sessionId != null) {
            userId = AuthController.getUserIdFromSession(sessionId);
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
     * 获取指定会话中当前活跃的后台任务。
     * 用于前端断线重连后恢复进度条显示。
     */
    @GetMapping("/tasks/active")
    public ResponseEntity<?> getActiveTasks(@RequestParam String conversationId) {
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
            return ResponseEntity.status(403).body("{\"status\":\"error\", \"message\":\"无权操作该会话\"}");
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
        Long userId = null;
        if (sessionId != null) {
            userId = AuthController.getUserIdFromSession(sessionId);
        }
        
        log.info("Received PPT Generation Request: topic={}, editable={}", request.getTopic(), request.isExportEditable());
        
        // Create final variable for lambda capture
        final Long effectiveUserId = userId != null ? userId : 10001L;
        
        // Asynchronous execution via PptxTools (which handles background task creation internally)
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            pptxTools.performPptGenerationWithProgress(
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
        });
        
        return ResponseEntity.ok().body("{\"status\":\"ok\", \"message\":\"PPT generation started\"}");
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
        private String model; // e.g. "anthropic/claude-3.5-sonnet"
        private String mode;  // Agent 模式: ASK, PLAN, AGENT (默认 AGENT)
        private java.util.List<String> fileIds; // Legacy: Context files to inject 
        private java.util.List<ContextItem> contextItems; // New: Full context metadata
        private ContextItem activeContext; // NEW: Auto-detected active tab (current document)

        public Long getProjectId() { return projectId; }
        public void setProjectId(Long projectId) { this.projectId = projectId; }
        public String getConversationId() { return conversationId; }
        public void setConversationId(String conversationId) { this.conversationId = conversationId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
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
        
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @com.fasterxml.jackson.annotation.JsonProperty("isDir")
        public boolean isDir() { return isDir; }
        public void setIsDir(boolean isDir) { this.isDir = isDir; }
        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }
    }
}
