package com.checkba.controller.ai;

import com.checkba.config.AiModelProperties;
import com.checkba.controller.AuthController;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.SystemSettingService;
import com.checkba.service.ai.AiChatService;
import com.checkba.service.ai.AiDocxExportService;
import com.checkba.service.ai.AiAssistantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * AI Chat HTTP 接入层。
 *
 * 只负责 HTTP 出入口、鉴权（session → userId）与 DTO 定义；
 * 业务编排下沉至 AiChatService 及各专职 Service（Phase 2 fat controller 治理）。
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiChatController.class);

    private final AiChatService aiChatService;
    private final AiAssistantService aiAssistantService;
    private final ProjectAiMessageService projectAiMessageService;
    private final AiDocxExportService aiDocxExportService;
    private final AiModelProperties aiModelProperties;
    private final SystemSettingService systemSettingService;
    private final com.checkba.service.ai.ConversationFileChangeService conversationFileChangeService;
    private final com.checkba.repository.TokenUsageRepository tokenUsageRepository;
    private final com.checkba.service.ai.AgentRunStateService agentRunStateService;
    private final com.checkba.service.ProjectMemberService projectMemberService;

    public AiChatController(
            AiChatService aiChatService,
            AiAssistantService aiAssistantService,
            ProjectAiMessageService projectAiMessageService,
            AiDocxExportService aiDocxExportService,
            AiModelProperties aiModelProperties,
            SystemSettingService systemSettingService,
            com.checkba.service.ai.ConversationFileChangeService conversationFileChangeService,
            com.checkba.repository.TokenUsageRepository tokenUsageRepository,
            com.checkba.service.ai.AgentRunStateService agentRunStateService,
            com.checkba.service.ProjectMemberService projectMemberService) {
        this.aiChatService = aiChatService;
        this.aiAssistantService = aiAssistantService;
        this.projectAiMessageService = projectAiMessageService;
        this.aiDocxExportService = aiDocxExportService;
        this.aiModelProperties = aiModelProperties;
        this.systemSettingService = systemSettingService;
        this.conversationFileChangeService = conversationFileChangeService;
        this.tokenUsageRepository = tokenUsageRepository;
        this.agentRunStateService = agentRunStateService;
        this.projectMemberService = projectMemberService;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody AiChatRequest request, @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        log.info("Received AI chat request for project {}: {} (model={})", request.getProjectId(), request.getMessage(), request.getModel());

        // 越权校验：projectId 由请求体给定，未登录也能跑，检索会命中该项目的向量库
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(401).body("请先登录");
        }
        Long projectId = parseProjectId(request.getProjectId());
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            return ResponseEntity.status(403).body("无权访问该项目");
        }

        return ResponseEntity.ok(aiChatService.chat(request, userId));
    }

    private Long parseProjectId(String projectId) {
        try {
            return Long.parseLong(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> getChatHistory(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String conversationId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        // 越权校验：conversationId 分支此前在读 session 之前就返回了消息，
        // projectId 分支的 userId=null 又会退化成"整个项目所有人的消息"，
        // 两条路都能匿名读到别家律所的对话正文与工具输出（含文档原文）
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(401).body("请先登录");
        }

        // If conversationId is provided, return specific messages
        if (StringUtils.hasText(conversationId)) {
             if (!projectAiMessageService.isConversationOwnedBy(conversationId, userId)) {
                 return ResponseEntity.status(403).body("无权查看该会话");
             }
             return ResponseEntity.ok(projectAiMessageService.listByConversationId(conversationId));
        }

        // Fallback (or deprecated): List all messages for project/user if conversationId is missing
        // This keeps backward compatibility for now, or returns empty list if we want to enforce sessions.
        if (projectId != null) {
             return ResponseEntity.ok(projectAiMessageService.listByProjectAndUser(projectId, userId));
        }
        return ResponseEntity.ok(java.util.Collections.emptyList());
    }

    @GetMapping("/conversations")
    public java.util.List<Map<String, Object>> getConversations(@RequestParam Long projectId, @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = null;
        if (sessionId != null) {
            userId = AuthController.getUserIdFromSession(sessionId);
        }
        java.util.List<Map<String, Object>> conversations = projectAiMessageService.listConversations(projectId, userId);
        // 合并 Agent 运行状态（RUNNING/PAUSED/AWAITING_APPROVAL/…，null=本进程内没跑过），
        // 历史列表的状态提示点靠它；在控制层合并，持久层不感知 AI 运行时。
        for (Map<String, Object> conv : conversations) {
            Object cid = conv.get("conversationId");
            conv.put("runStatus", cid == null ? null : agentRunStateService.statusName(cid.toString()));
        }
        return conversations;
    }

    /**
     * Get conversation metadata: file changes and token usage for historical display.
     */
    @GetMapping("/conversation/{conversationId}/metadata")
    public ResponseEntity<?> getConversationMetadata(@PathVariable String conversationId,
                                                     @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        // 归属校验：文件变动清单会暴露他人项目的文件名，此前此接口连 session 都不读
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null || !projectAiMessageService.isConversationOwnedBy(conversationId, userId)) {
            return ResponseEntity.status(403).body("无权查看该会话");
        }
        try {
            // Get file changes
            var fileChanges = conversationFileChangeService.findByConversationId(conversationId);
            var fileChangesDto = fileChanges.stream().map(fc -> {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("fileName", fc.getFileName());
                m.put("changeType", fc.getChangeType());
                return m;
            }).collect(java.util.stream.Collectors.toList());

            // Get token usage (sum all usages for this conversation)
            var tokenUsages = tokenUsageRepository.findByConversationId(conversationId);
            int promptTokens = tokenUsages.stream().mapToInt(t -> t.getPromptTokens() != null ? t.getPromptTokens() : 0).sum();
            int completionTokens = tokenUsages.stream().mapToInt(t -> t.getCompletionTokens() != null ? t.getCompletionTokens() : 0).sum();
            int totalTokens = tokenUsages.stream().mapToInt(t -> t.getTotalTokens() != null ? t.getTotalTokens() : 0).sum();

            Map<String, Object> tokenUsage = new java.util.HashMap<>();
            tokenUsage.put("promptTokens", promptTokens);
            tokenUsage.put("completionTokens", completionTokens);
            tokenUsage.put("totalTokens", totalTokens);

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("fileChanges", fileChangesDto);
            result.put("tokenUsage", tokenUsage);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get conversation metadata", e);
            return ResponseEntity.status(500).body("Failed to get metadata: " + e.getMessage());
        }
    }

    @GetMapping("/assistants")
    public java.util.Collection<com.checkba.model.ai.AiAssistantConfig> getAssistants() {
        return aiAssistantService.loadAssistants().values();
    }

    /**
     * Get public AI configuration (e.g. active provider) for all users
     */
    @GetMapping("/config")
    public ResponseEntity<?> getAiConfig() {
        String activeProvider = systemSettingService.get("ai.activeProvider",
                aiModelProperties.getProvider() != null ? aiModelProperties.getProvider().name() : "OLLAMA");

        // Return a simple map or DTO
        Map<String, String> config = new java.util.HashMap<>();
        config.put("activeProvider", activeProvider);
        return ResponseEntity.ok(config);
    }

    /**
     * AI 导出 Word：后端根据 markdown 文本生成 docx 并注册为项目文件。
     */
    @PostMapping("/export-docx")
    public ResponseEntity<?> exportDocx(@RequestBody AiExportDocxRequest request,
                                        @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        try {
            Long userId = AuthController.getUserIdFromSession(sessionId);
            if (userId == null) {
                return ResponseEntity.status(401).body("请先登录");
            }
            Long projectId = request.getProjectId();
            if (projectId == null) {
                return ResponseEntity.badRequest().body("项目 ID 不能为空");
            }
            String fileName = request.getFileName();
            if (fileName == null || fileName.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("文件名不能为空");
            }

            // 如果没有 .docx 后缀，自动补上
            if (!fileName.toLowerCase().endsWith(".docx")) {
                fileName = fileName + ".docx";
            }

            String markdown = request.getMarkdown() != null ? request.getMarkdown() : request.getContent();

            var file = aiDocxExportService.exportMarkdownToDocx(
                    projectId,
                    request.getParentId(),
                    userId,
                    fileName,
                    markdown
            );

            return ResponseEntity.ok(file);
        } catch (Exception e) {
            log.error("AI 导出 Word 失败", e);
            return ResponseEntity.status(500).body("导出 Word 失败: " + e.getMessage());
        }
    }

    public static class AiChatRequest {
        private String projectId;
        private String message;
        private AiChatContext context; // Deprecated, use contexts
        private java.util.List<AiChatContext> contexts; // New
        private String model;
        private String assistantId;
        private String conversationId;

        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public AiChatContext getContext() { return context; }
        public void setContext(AiChatContext context) { this.context = context; }
        public java.util.List<AiChatContext> getContexts() { return contexts; }
        public void setContexts(java.util.List<AiChatContext> contexts) { this.contexts = contexts; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getAssistantId() { return assistantId; }
        public void setAssistantId(String assistantId) { this.assistantId = assistantId; }
        public String getConversationId() { return conversationId; }
        public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    }

    public static class AiChatResponse {
        private String response;
        private String conversationId;
        public AiChatResponse(String response) { this.response = response; }
        public AiChatResponse(String response, String conversationId) {
            this.response = response;
            this.conversationId = conversationId;
        }
        public String getResponse() { return response; }
        public void setResponse(String response) { this.response = response; }
        public String getConversationId() { return conversationId; }
        public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    }

    public static class AiChatContext {
        private String fileId;
        private String fileName;
        private String fileType;
        private String wpsFileId;
        private String selectionText;
        private String documentText;

        public String getFileId() { return fileId; }
        public void setFileId(String fileId) { this.fileId = fileId; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }
        public String getWpsFileId() { return wpsFileId; }
        public void setWpsFileId(String wpsFileId) { this.wpsFileId = wpsFileId; }
        public String getSelectionText() { return selectionText; }
        public void setSelectionText(String selectionText) { this.selectionText = selectionText; }
        public String getDocumentText() { return documentText; }
        public void setDocumentText(String documentText) { this.documentText = documentText; }
    }

    public static class AiExportDocxRequest {
        private Long projectId;
        private Long parentId;
        private String fileName;
        /**
         * 文本内容（优先 markdown）
         */
        private String markdown;
        /**
         * 兼容字段：如果前端还没改成 markdown，可以传 content
         */
        private String content;

        public Long getProjectId() { return projectId; }
        public void setProjectId(Long projectId) { this.projectId = projectId; }
        public Long getParentId() { return parentId; }
        public void setParentId(Long parentId) { this.parentId = parentId; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getMarkdown() { return markdown; }
        public void setMarkdown(String markdown) { this.markdown = markdown; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}
