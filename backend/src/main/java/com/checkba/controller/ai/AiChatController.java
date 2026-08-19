package com.checkba.controller.ai;

import com.checkba.controller.AuthController;
import com.checkba.service.LangText;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ai.AiDocxExportService;
import com.checkba.service.ai.AiAssistantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * AI Chat 周边 HTTP 接入层（历史会话、助手清单、公共配置、导出 Word）。
 *
 * 只负责 HTTP 出入口、鉴权（session → userId）与 DTO 定义。
 *
 * 历史背景：同步对话端点 POST /api/ai/chat 是 v1 通道，早已被 AiAgentController
 * 的 SSE 链路取代。前端唯一调用方（project-overview.vue 的 handleAiSend）在 AI 面板
 * 换成 ChatInterface 组件后模板里已无任何绑定，且请求体还漏传了 contexts 与
 * assistantId——即双重死代码，本次供应商体系改造中一并移除，连带 AiChatService、
 * MultiModalContentService 与两个 Gemini 类。
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiChatController.class);

    private final AiAssistantService aiAssistantService;
    private final ProjectAiMessageService projectAiMessageService;
    private final AiDocxExportService aiDocxExportService;
    private final com.checkba.service.ai.ChatModelFactory chatModelFactory;
    private final com.checkba.service.ai.ConversationFileChangeService conversationFileChangeService;
    private final com.checkba.repository.TokenUsageRepository tokenUsageRepository;
    private final com.checkba.service.ai.AgentRunStateService agentRunStateService;
    private final com.checkba.service.ai.PlatformAiChannel platformAiChannel;

    public AiChatController(
            AiAssistantService aiAssistantService,
            ProjectAiMessageService projectAiMessageService,
            AiDocxExportService aiDocxExportService,
            com.checkba.service.ai.ChatModelFactory chatModelFactory,
            com.checkba.service.ai.ConversationFileChangeService conversationFileChangeService,
            com.checkba.repository.TokenUsageRepository tokenUsageRepository,
            com.checkba.service.ai.AgentRunStateService agentRunStateService,
            com.checkba.service.ai.PlatformAiChannel platformAiChannel) {
        this.aiAssistantService = aiAssistantService;
        this.projectAiMessageService = projectAiMessageService;
        this.aiDocxExportService = aiDocxExportService;
        this.chatModelFactory = chatModelFactory;
        this.conversationFileChangeService = conversationFileChangeService;
        this.tokenUsageRepository = tokenUsageRepository;
        this.agentRunStateService = agentRunStateService;
        this.platformAiChannel = platformAiChannel;
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
             // 用「可用」而非「归属」：新会话还没有消息，前端一进项目就会来拉一次，
             // 拿严格归属判会把每个新会话都挡成 403
             if (!projectAiMessageService.canUseConversation(conversationId, userId)) {
                 return ResponseEntity.status(403).body(LangText.of("无权查看该会话", "You do not have permission to view this conversation"));
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
        Long userId = AuthController.getUserIdFromSession(sessionId);
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
        if (!projectAiMessageService.canUseConversation(conversationId, userId)) {
            return ResponseEntity.status(403).body(LangText.of("无权查看该会话", "You do not have permission to view this conversation"));
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
     *
     * <p>activeProvider 直接取 {@link com.checkba.service.ai.ChatModelFactory#resolveProvider()}，
     * 不再自己「读 setting、拿不到就回退 yml」——那份复制出来的解析有两处对不上真实路由：
     * DB 里存了认不出的值时它原样返回（路由却已回退 yml），且回退链末端写死了字面量
     * "OLLAMA"。三档收敛后前端拿这个字段决定「模式选择器给不给 Agent/Plan」
     * （本地档只支持 ASK），一旦答错就是把云端用户误锁成只能问答。
     * 供应商的唯一口径在工厂里，这里只做透出。
     */
    @GetMapping("/config")
    public ResponseEntity<?> getAiConfig(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        String activeProvider = chatModelFactory.resolveProvider().name();

        // Return a simple map or DTO
        Map<String, Object> config = new java.util.HashMap<>();
        config.put("activeProvider", activeProvider);
        // 平台通道「AI WorkDeck 云端」是否可选：未连接官网账户时前端不展示该供应商。
        // server 模式多租户下密钥是 per-user 的，判据也必须按人算——机器级的答案会
        // 让没直连账户的租户看到一个选了就报错的供应商。
        config.put("platformAiAvailable",
                platformAiChannel.availableFor(AuthController.getUserIdFromSession(sessionId)));
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
                return ResponseEntity.badRequest().body(LangText.of("项目 ID 不能为空", "Project ID is required"));
            }
            String fileName = request.getFileName();
            if (fileName == null || fileName.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(LangText.of("文件名不能为空", "File name is required"));
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
            return ResponseEntity.status(500).body(LangText.of("导出 Word 失败: ", "Failed to export Word: ") + e.getMessage());
        }
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
