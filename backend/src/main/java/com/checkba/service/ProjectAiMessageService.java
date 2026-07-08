package com.checkba.service;

import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.ProjectAiMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectAiMessageService {

    private final ProjectAiMessageRepository repository;

    public void saveUserAndAssistantMessage(String projectIdStr, Long userId, String conversationId, String userContent, String assistantContent) {
        if (projectIdStr == null) {
            return;
        }
        Long projectId;
        try {
            projectId = Long.parseLong(projectIdStr);
        } catch (NumberFormatException e) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();

        ProjectAiMessage userMsg = new ProjectAiMessage();
        userMsg.setProjectId(projectId);
        userMsg.setUserId(userId);
        userMsg.setRole("USER");
        userMsg.setContent(userContent);
        userMsg.setConversationId(conversationId);
        userMsg.setCreatedAt(now);
        repository.save(userMsg);

        ProjectAiMessage aiMsg = new ProjectAiMessage();
        aiMsg.setProjectId(projectId);
        aiMsg.setUserId(userId);
        aiMsg.setRole("ASSISTANT");
        aiMsg.setContent(assistantContent);
        aiMsg.setConversationId(conversationId);
        aiMsg.setCreatedAt(LocalDateTime.now());
        repository.save(aiMsg);
    }

    // Deprecated or Legacy support
    public void saveUserAndAssistantMessage(String projectIdStr, Long userId, String userContent, String assistantContent) {
        saveUserAndAssistantMessage(projectIdStr, userId, null, userContent, assistantContent);
    }

    /**
     * Save a single message (user OR assistant) to the database.
     * Used for streaming scenarios where assistant response comes after user message.
     * 
     * 对于 ASSISTANT 消息，如果最后一条同角色消息是最近 30 秒内创建的（即同一个对话轮次），
     * 则更新它而不是创建新的。这样可以避免增量保存和最终保存产生重复消息。
     */
    public void saveMessage(String projectIdStr, Long userId, String conversationId, String role, String content) {
        if (projectIdStr == null || role == null) {
            return;
        }
        Long projectId;
        try {
            projectId = Long.parseLong(projectIdStr);
        } catch (NumberFormatException e) {
            return;
        }
        
        // 对于 ASSISTANT 消息，检查是否需要更新而不是新建
        if ("ASSISTANT".equalsIgnoreCase(role) && conversationId != null) {
            java.util.Optional<ProjectAiMessage> lastMsgOpt = repository.findLastByConversationIdAndRole(conversationId, "ASSISTANT");
            if (lastMsgOpt.isPresent()) {
                ProjectAiMessage lastMsg = lastMsgOpt.get();
                // 如果最后一条 ASSISTANT 消息是 30 秒内创建的，认为是同一个对话轮次，更新它
                java.time.LocalDateTime threshold = java.time.LocalDateTime.now().minusSeconds(30);
                if (lastMsg.getCreatedAt() != null && lastMsg.getCreatedAt().isAfter(threshold)) {
                    // 更新已有消息而不是创建新的
                    lastMsg.setContent(content);
                    lastMsg.setCreatedAt(java.time.LocalDateTime.now()); // 更新时间戳
                    repository.save(lastMsg);
                    return;
                }
            }
        }
        
        // 否则创建新消息
        ProjectAiMessage msg = new ProjectAiMessage();
        msg.setProjectId(projectId);
        msg.setUserId(userId);
        msg.setRole(role.toUpperCase());
        msg.setContent(content);
        msg.setConversationId(conversationId);
        msg.setCreatedAt(java.time.LocalDateTime.now());
        repository.save(msg);
    }

    public List<ProjectAiMessage> listByProject(Long projectId) {
        return repository.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    public List<ProjectAiMessage> listByProjectAndUser(Long projectId, Long userId) {
        if (userId == null) {
            return listByProject(projectId);
        }
        return repository.findByProjectIdAndUserIdOrderByCreatedAtAsc(projectId, userId);
    }

    public List<ProjectAiMessage> listByConversationId(String conversationId) {
        return repository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /** 校验会话是否属于该用户（用于回滚等破坏性操作的越权防护），据首条消息的 userId 判定。 */
    public boolean isConversationOwnedBy(String conversationId, Long userId) {
        if (userId == null) return false;
        return repository.findFirstByConversationId(conversationId)
                .map(m -> userId.equals(m.getUserId()))
                .orElse(false);
    }

    public List<java.util.Map<String, Object>> listConversations(Long projectId, Long userId) {
        List<Object[]> results = repository.findConversationSummaries(projectId, userId);
        return results.stream()
                .filter(row -> row[0] != null) // Filter out items with null conversationId
                .map(row -> {
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("conversationId", row[0]);
                    map.put("updatedAt", row[1]);
                    String lastMessage = row[2] != null ? row[2].toString() : "";
                    String storedTitle = row.length > 3 && row[3] != null ? row[3].toString() : null;
                    String firstUserMessage = row.length > 4 && row[4] != null ? row[4].toString() : "";
                    // 优先使用 LLM 生成的 conversationTitle，fallback 到 cleanTitle
                    map.put("title", storedTitle != null && !storedTitle.isBlank() ? storedTitle : cleanTitle(lastMessage));
                    // 使用 extractPreview 提取有意义的预览内容，若为空则回退到用户第一条消息
                    String preview = extractPreview(lastMessage);
                    if (preview.isEmpty() || preview.length() < 5) {
                        // 回退到用户第一条消息（清理后）
                        preview = truncatePreview(firstUserMessage.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim());
                    }
                    map.put("lastMessage", preview);
                    return map;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Clean conversation title by stripping common XML tags like <thinking>, <process>, etc.
     */
    private String cleanTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return "新对话";
        }
        // Remove common XML tags
        String cleaned = rawTitle
            .replaceAll("(?s)<thinking>.*?</thinking>", "")
            .replaceAll("(?s)<process[^>]*>.*?</process>", "")
            .replaceAll("(?s)<step>.*?</step>", "")
            .replaceAll("(?s)<tool_code>.*?</tool_code>", "")
            .replaceAll("(?s)<tool_output>.*?</tool_output>", "")
            .replaceAll("(?s)<artifact[^>]*>.*?</artifact>", "")
            .replaceAll("(?s)<final>.*?</final>", "")
            .replaceAll("<[^>]+>", "") // Remove any remaining tags
            .trim();
        
        // Truncate to reasonable length for display
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100) + "...";
        }
        
        return cleaned.isEmpty() ? "新对话" : cleaned;
    }

    /**
     * 提取有意义的预览内容，优先从 <final> 标签中提取，
     * 过滤掉 tool_code, tool_output, thinking, process 等技术性标签内容
     */
    private String extractPreview(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "";
        }
        
        // 1. 优先提取 <final> 标签内容（LLM的最终输出）
        java.util.regex.Pattern finalPattern = java.util.regex.Pattern.compile("<final>([\\s\\S]*?)</final>");
        java.util.regex.Matcher finalMatcher = finalPattern.matcher(rawContent);
        if (finalMatcher.find()) {
            String finalContent = finalMatcher.group(1).trim();
            // 清理 final 内容中的 markdown 和多余空白
            finalContent = finalContent
                .replaceAll("```[a-z]*\\n?", "")
                .replaceAll("\\*\\*", "")
                .replaceAll("\\s+", " ")
                .trim();
            if (!finalContent.isEmpty() && finalContent.length() > 3) {
                return truncatePreview(finalContent);
            }
        }
        
        // 2. 移除所有技术性标签及其内容
        String cleaned = rawContent
            .replaceAll("(?s)<thinking>.*?</thinking>", "")
            .replaceAll("(?s)<process[^>]*>.*?</process>", "")
            .replaceAll("(?s)<step>.*?</step>", "")
            .replaceAll("(?s)<tool_code>.*?</tool_code>", "")
            .replaceAll("(?s)<tool_output[^>]*>.*?</tool_output>", "")
            .replaceAll("(?s)<artifact[^>]*>.*?</artifact>", "")
            .replaceAll("(?s)<root_bubble[^>]*>.*?</root_bubble>", "")
            .replaceAll("<[^>]+>", "") // 移除任何剩余的标签
            .replaceAll("```[a-z]*\\n?", "") // 移除代码块标记
            .replaceAll("\\*\\*", "") // 移除加粗标记
            .replaceAll("\\s+", " ") // 合并多余空白
            .trim();
        
        // 3. 过滤明显是代码的内容（以 import, def, function, class 等开头）
        if (cleaned.matches("^(import |def |function |class |const |let |var |public |private ).*")) {
            return "";
        }
        
        return truncatePreview(cleaned);
    }

    /**
     * 截断预览内容到合理长度
     */
    private String truncatePreview(String content) {
        if (content == null) return "";
        // 取前80个字符，找到自然断点（句号、逗号、空格等）
        if (content.length() <= 80) {
            return content;
        }
        String truncated = content.substring(0, 80);
        // 尝试在标点或空格处截断
        int lastBreak = Math.max(
            truncated.lastIndexOf('。'),
            Math.max(truncated.lastIndexOf('，'), 
                Math.max(truncated.lastIndexOf(' '), truncated.lastIndexOf('、')))
        );
        if (lastBreak > 40) {
            return truncated.substring(0, lastBreak + 1) + "...";
        }
        return truncated + "...";
    }

    /**
     * 调用 LLM 生成对话标题（基于用户第一条消息）
     */
    public String generateConversationTitle(String userMessage, dev.langchain4j.model.chat.ChatLanguageModel model) {
        String prompt = "请为以下用户问题生成一个简短的对话标题（不超过15个字，不要标点符号，只输出标题本身）:\n" + userMessage;
        try {
            String title = model.generate(prompt);
            // Clean any XML tags or extra formatting the model might output
            title = title.replaceAll("<[^>]+>", "").replaceAll("```[a-z]*", "").trim();
            title = title.replaceAll("^[\"']+|[\"']+$", ""); // Remove quotes
            if (title.length() > 30) title = title.substring(0, 30);
            return title.isEmpty() ? "新对话" : title;
        } catch (Exception e) {
            return "新对话";
        }
    }

    /**
     * 更新对话的第一条消息的标题字段
     */
    @org.springframework.transaction.annotation.Transactional
    public void updateConversationTitle(String conversationId, String title) {
        java.util.Optional<ProjectAiMessage> firstMsgOpt = repository.findFirstByConversationId(conversationId);
        if (firstMsgOpt.isPresent()) {
            ProjectAiMessage firstMsg = firstMsgOpt.get();
            firstMsg.setConversationTitle(title);
            repository.save(firstMsg);
        }
    }

    public java.util.Optional<ProjectAiMessage> findById(Long id) {
        return repository.findById(id);
    }

    @org.springframework.transaction.annotation.Transactional
    public void truncateHistory(String conversationId, Long messageId) {
        ProjectAiMessage message = repository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + messageId));
        
        if (!message.getConversationId().equals(conversationId)) {
            throw new IllegalArgumentException("Message does not belong to conversation: " + conversationId);
        }

        repository.deleteByConversationIdAndCreatedAtAfter(conversationId, message.getCreatedAt());
    }
}


