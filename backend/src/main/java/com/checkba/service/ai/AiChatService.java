package com.checkba.service.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.controller.ai.AiChatController.AiChatContext;
import com.checkba.controller.ai.AiChatController.AiChatRequest;
import com.checkba.controller.ai.AiChatController.AiChatResponse;
import com.checkba.model.ai.AiAssistantConfig;
import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.SystemSettingService;
import com.checkba.service.ai.context.FileContextLoader;
import com.checkba.service.ai.context.FileContentExtractorService;
import com.checkba.service.ai.context.ProjectContextHolder;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 同步 Chat 接口（/api/ai/chat）的业务编排服务。
 *
 * 职责：会话 ID 处理、助手配置解析、带上下文的 prompt 构建、
 * 多模态消息组装、token 用量记录、历史落库。
 *
 * 文件读取/OCR → FileContextLoader；多模态分类与内容构建 → MultiModalContentService；
 * 助手实例 → AiAssistantService。控制器只保留 HTTP 出入口、鉴权与 DTO。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final AiAssistantService aiAssistantService;
    private final ProjectAiMessageService projectAiMessageService;
    private final SystemSettingService systemSettingService;
    private final ProjectFileService projectFileService;
    private final FileContextLoader fileContextLoader;
    private final FileContentExtractorService fileContentExtractorService;
    private final MultiModalContentService multiModalContentService;
    private final TokenUsageService tokenUsageService;
    private final AiContextProperties contextProperties;

    public AiChatService(AiAssistantService aiAssistantService,
                         ProjectAiMessageService projectAiMessageService,
                         SystemSettingService systemSettingService,
                         ProjectFileService projectFileService,
                         FileContextLoader fileContextLoader,
                         FileContentExtractorService fileContentExtractorService,
                         MultiModalContentService multiModalContentService,
                         TokenUsageService tokenUsageService,
                         AiContextProperties contextProperties) {
        this.aiAssistantService = aiAssistantService;
        this.projectAiMessageService = projectAiMessageService;
        this.systemSettingService = systemSettingService;
        this.projectFileService = projectFileService;
        this.fileContextLoader = fileContextLoader;
        this.fileContentExtractorService = fileContentExtractorService;
        this.multiModalContentService = multiModalContentService;
        this.tokenUsageService = tokenUsageService;
        this.contextProperties = contextProperties;
    }

    /**
     * 处理一次同步 chat 请求（含多模态与上下文注入）。
     */
    public AiChatResponse chat(AiChatRequest request, Long userId) {
        // 平台通道按用户计费：整条同步链路都在该身份作用域内取 key
        return PlatformAiUserScope.call(userId, () -> chatInScope(request, userId));
    }

    private AiChatResponse chatInScope(AiChatRequest request, Long userId) {
        try {
            ProjectContextHolder.setProjectId(request.getProjectId());

            // 0. Handle Conversation ID
            String conversationId = request.getConversationId();
            if (!StringUtils.hasText(conversationId)) {
                conversationId = java.util.UUID.randomUUID().toString();
            }

            // 1. Get Assistant Config
            String assistantId = StringUtils.hasText(request.getAssistantId()) ? request.getAssistantId() : "default";
            Map<String, AiAssistantConfig> currentAssistants = aiAssistantService.loadAssistants();
            AiAssistantConfig assistantConfig = currentAssistants.get(assistantId);

            // 2. Build Final Prompt with Context
            String modelKey = (request.getModel() != null ? request.getModel() : "default").toLowerCase();
            String finalPrompt = buildPromptWithContext(request, assistantConfig, true);

            // 3. Get LLM Service
            ProjectAssistant assistant = aiAssistantService.getAssistant(request.getModel(), assistantConfig);
            dev.langchain4j.service.Result<String> result;

            // Handle Multi-Modal Context (Image/Video) or Native PDF for Gemini
            List<AiChatContext> contexts = consolidateContexts(request);
            List<Content> mediaContents = multiModalContentService.buildMediaContents(contexts, modelKey);

            if (!mediaContents.isEmpty()) {
                List<Content> multiModalContents = new ArrayList<>();
                // Always add text prompt first
                multiModalContents.add(TextContent.from(finalPrompt));
                multiModalContents.addAll(mediaContents);
                result = assistant.chat(UserMessage.from(multiModalContents));
            } else {
                // Text Only
                result = assistant.chat(finalPrompt);
            }

            // Extract content and usage
            String response = result.content();
            if (result.tokenUsage() != null) {
                tokenUsageService.recordUsage(
                        Long.parseLong(request.getProjectId()),
                        userId,
                        request.getModel(),
                        result.tokenUsage(),
                        conversationId
                );
            }

            // 4. Record History
            try {
                projectAiMessageService.saveUserAndAssistantMessage(
                        request.getProjectId(),
                        userId,
                        conversationId,
                        request.getMessage(),
                        response
                );
            } catch (Exception logEx) {
                log.warn("Failed to save AI chat history for project {}", request.getProjectId(), logEx);
            }

            return new AiChatResponse(response, conversationId);

        } catch (com.checkba.service.account.AccountException e) {
            // 账户/额度类失败是用户可自行处理的状态，中文文案原样透出（同 AgentOrchestrator）
            log.info("平台通道不可用 [{}]: {}", e.getKind(), e.getMessage());
            return new AiChatResponse(e.getMessage(), request.getConversationId());
        } catch (Exception e) {
            log.error("Error during AI chat", e);
            return new AiChatResponse("Sorry, I encountered an error: " + e.getMessage(), request.getConversationId());
        } finally {
            ProjectContextHolder.clear();
        }
    }

    /**
     * 合并上下文：contexts 列表优先，其次兼容单个 context 字段。
     */
    private List<AiChatContext> consolidateContexts(AiChatRequest request) {
        List<AiChatContext> contexts = request.getContexts();
        if (contexts == null) {
            contexts = new ArrayList<>();
            if (request.getContext() != null) {
                contexts.add(request.getContext());
            }
        }
        return contexts;
    }

    /**
     * 构建带上下文的完整 prompt（系统设定 + 助手指令 + 文件上下文 + 用户请求）。
     */
    String buildPromptWithContext(AiChatRequest request, AiAssistantConfig assistantConfig, boolean includeContext) {
        List<AiChatContext> contexts = consolidateContexts(request);

        StringBuilder builder = new StringBuilder();

        // 1. Inject Prompt (User Override or System)
        // 1.1 Dynamic System Prompt from Admin (Model Specific)
        String modelKey = (request.getModel() != null ? request.getModel() : "default").toLowerCase();
        String dynamicSystemKey = null;
        if (modelKey.contains("gemini") || modelKey.contains("google")) {
            dynamicSystemKey = "ai.systemPrompt.GEMINI";
        } else if (modelKey.contains("local") || modelKey.contains("ollama")) {
            dynamicSystemKey = "ai.systemPrompt.OLLAMA";
        }

        String dynamicSystemPrompt = dynamicSystemKey != null ? systemSettingService.get(dynamicSystemKey, "") : "";
        if (StringUtils.hasText(dynamicSystemPrompt)) {
            builder.append("【系统设定】\n").append(dynamicSystemPrompt).append("\n\n");
        }

        if (assistantConfig != null) {
            String promptToUse = assistantConfig.getSystemPrompt();
            String label = "【系统指令】";

            // "User Prompt Prevails" Logic
            if (StringUtils.hasText(assistantConfig.getUserPrompt())) {
                promptToUse = assistantConfig.getUserPrompt();
                label = "【用户自定义指令】(已覆盖系统默认)";
            }

            if (StringUtils.hasText(promptToUse)) {
                builder.append(label).append("\n").append(promptToUse).append("\n\n");
            }
        }

        if (!includeContext || contexts.isEmpty()) {
            builder.append(request.getMessage());
            return builder.toString();
        }

        builder.append("【当前上下文】\n");

        for (AiChatContext ctx : contexts) {
            if (ctx == null) continue;

            builder.append("--- 文件: ")
                    .append(StringUtils.hasText(ctx.getFileName()) ? ctx.getFileName() : "未命名文件");
            if (StringUtils.hasText(ctx.getFileType())) {
                builder.append(" (").append(ctx.getFileType()).append(")");
            }
            builder.append(" ---\n");

            String documentText = resolveDocumentText(ctx, request, modelKey);

            String selection = safeContextBlock(ctx.getSelectionText(), contextProperties.getFiles().getChatSelectionMaxChars());
            if (StringUtils.hasText(selection)) {
                builder.append("选区内容:\n```\n")
                        .append(selection)
                        .append("\n```\n");
            }
            // For folders, we allow larger context
            int maxChars = "folder".equals(ctx.getFileType())
                    ? contextProperties.getFiles().getChatFolderContextMaxChars()
                    : contextProperties.getFiles().getChatContextMaxChars();
            String document = safeContextBlock(documentText, maxChars);
            if (StringUtils.hasText(document)) {
                builder.append("正文内容:\n```\n")
                        .append(document)
                        .append("\n```\n");
            }
            builder.append("\n");
        }

        builder.append("\n【用户请求】\n")
                .append(request.getMessage());
        return builder.toString();
    }

    /**
     * 解析单个上下文的正文内容：
     * - 前端已带 documentText 时直接使用
     * - 文件夹：递归收集文件夹内容
     * - 普通文件：多模态可直传的跳过提取，否则走 FileContextLoader（OCR/标准提取）
     */
    private String resolveDocumentText(AiChatContext ctx, AiChatRequest request, String modelKey) {
        String documentText = ctx.getDocumentText();
        if (StringUtils.hasText(documentText)) {
            return documentText;
        }

        if ("folder".equals(ctx.getFileType()) && StringUtils.hasText(ctx.getFileId())) {
            try {
                Long folderId = Long.parseLong(ctx.getFileId());
                return fileContextLoader.collectFolderContent(Long.parseLong(request.getProjectId()), folderId);
            } catch (Exception e) {
                return "[System: Failed to load folder content: " + e.getMessage() + "]";
            }
        }

        if (!StringUtils.hasText(ctx.getFileId())) {
            return documentText;
        }

        try {
            Long fileId = Long.parseLong(ctx.getFileId());
            com.checkba.model.entity.ProjectFile fileEntity = projectFileService.getFile(fileId);
            if (fileEntity == null || !StringUtils.hasText(fileEntity.getFilePath())) {
                return documentText;
            }

            String fType = ctx.getFileType() != null ? ctx.getFileType().toLowerCase() : "";
            boolean isMediaFile = fType.matches("pdf|jpg|jpeg|png|gif|bmp|webp|image") ||
                    fileContentExtractorService.isOcrSupported(fileEntity.getName());
            boolean canDirectSend = multiModalContentService.canDirectSend(modelKey, fType);

            if (isMediaFile) {
                log.info("Process Context File: name={}, type={}, isMultiModal={}, canDirectSend={}",
                        fileEntity.getName(), fType, multiModalContentService.isMultiModalCapable(modelKey), canDirectSend);

                if (canDirectSend) {
                    // 多模态模型：跳过文本提取，将在多模态路径直传原文件
                    log.info("-> Skip extraction for multimodal (Direct Send)");
                    return "[多模态模型将直接处理该文件，无需文本提取]";
                }
            }

            log.info("-> Text extraction needed. Retrieving file content...");
            return fileContextLoader.extractFileText(fileEntity);
        } catch (Exception e) {
            log.warn("Failed to read context file content in backend", e);
            return "[System: Error reading file content: " + e.getMessage() + "]";
        }
    }

    private String safeContextBlock(String raw, int maxLen) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String cleaned = raw.trim();
        if (cleaned.length() <= maxLen) {
            return cleaned;
        }
        return cleaned.substring(0, maxLen) + "\n...[上下文截断 " + (cleaned.length() - maxLen) + " 字]";
    }
}
