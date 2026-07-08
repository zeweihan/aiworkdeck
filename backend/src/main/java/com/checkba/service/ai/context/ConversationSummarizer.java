package com.checkba.service.ai.context;

import com.checkba.model.entity.ConversationSummary;
import com.checkba.service.ai.ChatModelFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话摘要生成器（Episode 生成器）
 * 使用 LLM 生成智能摘要，针对法律领域定制
 * 
 * 借鉴 EverMemOS 的 Episode 概念，生成结构化的情景记忆：
 * - 事件级摘要
 * - 参与者信息
 * - 时间线
 * - 情景分类
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationSummarizer {

    private final ChatModelFactory chatModelFactory;
    private final LegalInfoProtector legalInfoProtector;

    private static final String SUMMARY_PROMPT = """
        你是一个法律项目助理，需要为以下对话生成摘要。
        
        ## 要求
        1. 保留所有法律引用（法律法规名称、条款编号）
        2. 保留所有关键数字（日期、金额、比例、期限）
        3. 保留所有当事人信息（公司名称、自然人姓名）
        4. 保留所有重要决策和结论
        5. 摘要长度控制在 500 字以内
        
        ## 输出格式（严格按此格式输出）
        【项目概况】
        简述项目基本情况
        
        【关键信息】
        - 当事人: ...
        - 交易金额: ...
        - 关键日期: ...
        
        【讨论要点】
        1. ...
        2. ...
        
        【决策/结论】
        - ...
        
        【待办事项】
        - ...（如无则写"无"）
        
        ## 对话内容
        %s
        """;

    private static final String QUICK_SUMMARY_PROMPT = """
        请用100字以内概括以下对话的核心内容，保留关键的法律引用、金额和日期：
        
        %s
        """;

    /**
     * 生成完整对话摘要
     */
    public SummaryResult generateSummary(List<ChatMessage> messages) {
        log.info("Generating full conversation summary for {} messages", messages.size());
        
        String content = formatMessages(messages);
        String prompt = String.format(SUMMARY_PROMPT, content);
        
        try {
            ChatLanguageModel model = chatModelFactory.getChatModel("google/gemini-2.0-flash-exp:free");
            String summaryText = model.generate(prompt);
            
            // 清理输出
            summaryText = cleanSummaryOutput(summaryText);
            
            // 解析摘要结构
            return parseSummary(summaryText, messages);
        } catch (Exception e) {
            log.error("Failed to generate summary: {}", e.getMessage(), e);
            // 降级到快速摘要
            return fallbackSummary(messages);
        }
    }

    /**
     * 生成快速摘要（用于即时压缩）
     */
    public String generateQuickSummary(List<ChatMessage> messages) {
        log.info("Generating quick summary for {} messages", messages.size());
        
        // 首先提取法律关键信息
        StringBuilder keyInfo = new StringBuilder();
        Set<String> legalRefs = new LinkedHashSet<>();
        Set<String> amounts = new LinkedHashSet<>();
        Set<String> dates = new LinkedHashSet<>();
        
        for (ChatMessage msg : messages) {
            String text = extractText(msg);
            if (text != null) {
                legalRefs.addAll(legalInfoProtector.extractLegalReferences(text));
                amounts.addAll(legalInfoProtector.extractAmounts(text));
                dates.addAll(legalInfoProtector.extractDates(text));
            }
        }
        
        // 构建简化内容用于摘要
        String content = formatMessagesCompact(messages);
        
        try {
            ChatLanguageModel model = chatModelFactory.getChatModel("google/gemini-2.0-flash-exp:free");
            String summary = model.generate(String.format(QUICK_SUMMARY_PROMPT, content));
            summary = cleanSummaryOutput(summary);
            
            // 确保法律关键信息被保留
            StringBuilder result = new StringBuilder(summary);
            if (!legalRefs.isEmpty()) {
                result.append("\n【法律引用】").append(String.join("、", legalRefs));
            }
            if (!amounts.isEmpty()) {
                result.append("\n【金额】").append(String.join("、", amounts));
            }
            if (!dates.isEmpty()) {
                result.append("\n【日期】").append(String.join("、", dates));
            }
            
            return result.toString();
        } catch (Exception e) {
            log.warn("Quick summary failed, using fallback: {}", e.getMessage());
            return generateFallbackQuickSummary(messages, legalRefs, amounts, dates);
        }
    }

    /**
     * 格式化消息用于摘要
     */
    private String formatMessages(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        
        for (ChatMessage msg : messages) {
            String text = extractText(msg);
            if (text == null) continue;
            
            String role = msg instanceof UserMessage ? "用户" : "助手";
            
            // 清理 XML 标签
            text = cleanXmlTags(text);
            
            // 截断过长的消息
            if (text.length() > 2000) {
                text = text.substring(0, 2000) + "...[已截断]";
            }
            
            sb.append(String.format("[%d] %s: %s\n\n", index++, role, text));
        }
        
        return sb.toString();
    }

    /**
     * 格式化消息（紧凑版）
     */
    private String formatMessagesCompact(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        
        for (ChatMessage msg : messages) {
            String text = extractText(msg);
            if (text == null) continue;
            
            // 清理 XML 标签
            text = cleanXmlTags(text);
            
            // 只保留前 500 字符
            if (text.length() > 500) {
                text = text.substring(0, 500) + "...";
            }
            
            String role = msg instanceof UserMessage ? "Q" : "A";
            sb.append(role).append(": ").append(text).append("\n");
        }
        
        // 限制总长度
        String result = sb.toString();
        if (result.length() > 3000) {
            result = result.substring(0, 3000) + "...[已截断]";
        }
        
        return result;
    }

    /**
     * 提取消息文本
     */
    private String extractText(ChatMessage msg) {
        if (msg instanceof UserMessage um) {
            // 安全提取：多模态消息 singleText() 会抛异常
            return um.contents().stream()
                    .filter(dev.langchain4j.data.message.TextContent.class::isInstance)
                    .map(c -> ((dev.langchain4j.data.message.TextContent) c).text())
                    .collect(java.util.stream.Collectors.joining(" "));
        } else if (msg instanceof AiMessage am) {
            return am.text();
        }
        return null;
    }

    /**
     * 清理 XML 标签
     */
    private String cleanXmlTags(String text) {
        // 移除 thinking, process, tool_code, tool_output 等标签内容
        text = text.replaceAll("(?s)<thinking>.*?</thinking>", "");
        text = text.replaceAll("(?s)<process[^>]*>.*?</process>", "");
        text = text.replaceAll("(?s)<tool_code>.*?</tool_code>", "[工具调用]");
        text = text.replaceAll("(?s)<tool_output[^>]*>.*?</tool_output>", "[工具结果]");
        text = text.replaceAll("<[^>]+>", "");
        text = text.replaceAll("\\s+", " ");
        return text.trim();
    }

    /**
     * 清理摘要输出
     */
    private String cleanSummaryOutput(String summary) {
        // 移除 markdown 代码块标记
        summary = summary.replaceAll("```[a-z]*\\n?", "");
        summary = summary.replaceAll("```", "");
        // 移除多余空行
        summary = summary.replaceAll("\\n{3,}", "\n\n");
        return summary.trim();
    }

    /**
     * 解析摘要结构
     */
    private SummaryResult parseSummary(String summaryText, List<ChatMessage> messages) {
        SummaryResult result = new SummaryResult();
        result.setSummaryText(summaryText);
        
        // 提取关键点
        result.setKeyPoints(extractSection(summaryText, "讨论要点"));
        
        // 提取待办事项
        result.setPendingTasks(extractSection(summaryText, "待办事项"));
        
        // 从原始消息中提取法律引用
        Set<String> legalRefs = new LinkedHashSet<>();
        for (ChatMessage msg : messages) {
            String text = extractText(msg);
            if (text != null) {
                legalRefs.addAll(legalInfoProtector.extractLegalReferences(text));
            }
        }
        result.setLegalReferences(new ArrayList<>(legalRefs));
        
        // 提取提及的实体
        result.setMentionedEntities(extractEntities(summaryText));
        
        return result;
    }

    /**
     * 提取摘要中的某个部分
     */
    private List<String> extractSection(String text, String sectionName) {
        List<String> items = new ArrayList<>();
        
        Pattern pattern = Pattern.compile("【" + sectionName + "】\\s*([\\s\\S]*?)(?=【|$)");
        Matcher matcher = pattern.matcher(text);
        
        if (matcher.find()) {
            String section = matcher.group(1);
            // 提取列表项
            Pattern itemPattern = Pattern.compile("[-•\\d+\\.)]\\s*(.+)");
            Matcher itemMatcher = itemPattern.matcher(section);
            while (itemMatcher.find()) {
                String item = itemMatcher.group(1).trim();
                if (!item.isEmpty() && !item.equals("无")) {
                    items.add(item);
                }
            }
        }
        
        return items;
    }

    /**
     * 提取实体（公司名称等）
     */
    private List<String> extractEntities(String text) {
        List<String> entities = new ArrayList<>();
        
        // 匹配公司名称
        Pattern companyPattern = Pattern.compile("([\\u4e00-\\u9fa5]+(?:股份|集团|科技|投资|控股)?有限(?:责任)?公司)");
        Matcher matcher = companyPattern.matcher(text);
        while (matcher.find()) {
            String company = matcher.group(1);
            if (!entities.contains(company)) {
                entities.add(company);
            }
        }
        
        return entities;
    }

    /**
     * 降级摘要（LLM 失败时使用）
     */
    private SummaryResult fallbackSummary(List<ChatMessage> messages) {
        SummaryResult result = new SummaryResult();
        
        // 提取法律关键信息
        Set<String> legalRefs = new LinkedHashSet<>();
        Set<String> amounts = new LinkedHashSet<>();
        Set<String> dates = new LinkedHashSet<>();
        StringBuilder contentSummary = new StringBuilder();
        
        for (ChatMessage msg : messages) {
            String text = extractText(msg);
            if (text != null) {
                legalRefs.addAll(legalInfoProtector.extractLegalReferences(text));
                amounts.addAll(legalInfoProtector.extractAmounts(text));
                dates.addAll(legalInfoProtector.extractDates(text));
            }
        }
        
        contentSummary.append("【对话摘要】\n");
        contentSummary.append("共 ").append(messages.size()).append(" 条消息\n\n");
        
        if (!legalRefs.isEmpty()) {
            contentSummary.append("【法律引用】\n");
            legalRefs.forEach(ref -> contentSummary.append("- ").append(ref).append("\n"));
        }
        
        if (!amounts.isEmpty()) {
            contentSummary.append("\n【关键金额】\n");
            amounts.forEach(amt -> contentSummary.append("- ").append(amt).append("\n"));
        }
        
        if (!dates.isEmpty()) {
            contentSummary.append("\n【关键日期】\n");
            dates.forEach(date -> contentSummary.append("- ").append(date).append("\n"));
        }
        
        result.setSummaryText(contentSummary.toString());
        result.setLegalReferences(new ArrayList<>(legalRefs));
        result.setKeyPoints(new ArrayList<>());
        result.setPendingTasks(new ArrayList<>());
        result.setMentionedEntities(new ArrayList<>());
        
        return result;
    }

    /**
     * 生成降级快速摘要
     */
    private String generateFallbackQuickSummary(List<ChatMessage> messages,
                                                  Set<String> legalRefs,
                                                  Set<String> amounts,
                                                  Set<String> dates) {
        StringBuilder sb = new StringBuilder();
        sb.append("对话包含 ").append(messages.size()).append(" 条消息。");
        
        if (!legalRefs.isEmpty()) {
            sb.append("\n涉及法规: ").append(String.join("、", legalRefs));
        }
        if (!amounts.isEmpty()) {
            sb.append("\n涉及金额: ").append(String.join("、", amounts));
        }
        if (!dates.isEmpty()) {
            sb.append("\n涉及日期: ").append(String.join("、", dates));
        }
        
        return sb.toString();
    }

    // ==================== Episode 结构化生成 ====================

    /**
     * 生成完整的 Episode（结构化情景记忆）
     * 包含事件列表、参与者、时间线等
     */
    public EpisodeResult generateEpisode(List<ChatMessage> messages, String conversationId, Long projectId) {
        log.info("Generating Episode for {} messages, conversationId={}", messages.size(), conversationId);
        
        // 1. 生成基础摘要
        SummaryResult summary = generateSummary(messages);
        
        // 2. 提取事件列表
        List<Map<String, Object>> events = extractEvents(messages);
        
        // 3. 提取参与者
        List<Map<String, String>> participants = extractParticipants(messages);
        
        // 4. 生成时间线
        List<Map<String, Object>> timeline = generateTimeline(messages, summary);
        
        // 5. 确定情景类型
        String episodeType = determineEpisodeType(messages, summary);
        
        // 6. 生成情景标题
        String episodeTitle = generateEpisodeTitle(summary, messages);
        
        // 7. 提取情景结果/结论
        String episodeOutcome = extractOutcome(summary);
        
        EpisodeResult result = new EpisodeResult();
        result.setSummaryResult(summary);
        result.setEvents(events);
        result.setParticipants(participants);
        result.setTimeline(timeline);
        result.setEpisodeType(episodeType);
        result.setEpisodeTitle(episodeTitle);
        result.setEpisodeOutcome(episodeOutcome);
        
        log.info("Episode generated: type={}, events={}, participants={}", 
                episodeType, events.size(), participants.size());
        
        return result;
    }

    /**
     * 从对话中提取事件列表
     */
    private List<Map<String, Object>> extractEvents(List<ChatMessage> messages) {
        List<Map<String, Object>> events = new ArrayList<>();
        int eventIndex = 0;
        
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            String text = extractText(msg);
            if (text == null) continue;
            
            text = cleanXmlTags(text);
            String actor = msg instanceof UserMessage ? "user" : "assistant";
            
            // 检测关键事件（决策、结论、问题等）
            String action = detectAction(text);
            if (action != null) {
                Map<String, Object> event = new HashMap<>();
                event.put("index", eventIndex++);
                event.put("messageIndex", i);
                event.put("actor", actor);
                event.put("action", action);
                event.put("content", text.length() > 200 ? text.substring(0, 200) + "..." : text);
                events.add(event);
            }
        }
        
        return events;
    }

    /**
     * 检测消息中的关键动作
     */
    private String detectAction(String text) {
        if (text.contains("决定") || text.contains("确定")) return "decision";
        if (text.contains("结论") || text.contains("综上")) return "conclusion";
        if (text.contains("建议")) return "suggestion";
        if (text.contains("?") || text.contains("？") || text.contains("吗")) return "question";
        if (text.contains("核查") || text.contains("审查")) return "review";
        if (text.contains("《") && text.contains("》")) return "legal_reference";
        if (text.length() > 100) return "discussion";
        return null;
    }

    /**
     * 提取对话参与者
     */
    private List<Map<String, String>> extractParticipants(List<ChatMessage> messages) {
        List<Map<String, String>> participants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        
        for (ChatMessage msg : messages) {
            String role = msg instanceof UserMessage ? "user" : "assistant";
            if (!seen.contains(role)) {
                Map<String, String> participant = new HashMap<>();
                participant.put("role", role);
                participant.put("name", role.equals("user") ? "用户" : "AI助手");
                participants.add(participant);
                seen.add(role);
            }
        }
        
        return participants;
    }

    /**
     * 生成时间线
     */
    private List<Map<String, Object>> generateTimeline(List<ChatMessage> messages, SummaryResult summary) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        
        // 开始节点
        Map<String, Object> start = new HashMap<>();
        start.put("type", "start");
        start.put("label", "对话开始");
        start.put("messageIndex", 0);
        timeline.add(start);
        
        // 从摘要的关键点生成中间节点
        if (summary.getKeyPoints() != null) {
            for (int i = 0; i < summary.getKeyPoints().size(); i++) {
                Map<String, Object> node = new HashMap<>();
                node.put("type", "keypoint");
                node.put("label", summary.getKeyPoints().get(i));
                node.put("order", i + 1);
                timeline.add(node);
            }
        }
        
        // 结束节点
        Map<String, Object> end = new HashMap<>();
        end.put("type", "end");
        end.put("label", "对话结束");
        end.put("messageIndex", messages.size() - 1);
        timeline.add(end);
        
        return timeline;
    }

    /**
     * 确定情景类型
     */
    private String determineEpisodeType(List<ChatMessage> messages, SummaryResult summary) {
        String allText = summary.getSummaryText();
        if (allText == null) allText = "";
        
        // 统计关键词出现频率
        int decisionCount = countOccurrences(allText, "决定", "确定", "批准", "同意");
        int reviewCount = countOccurrences(allText, "核查", "审查", "验证", "检查");
        int researchCount = countOccurrences(allText, "法规", "法律", "《", "条");
        int draftingCount = countOccurrences(allText, "起草", "撰写", "编写", "文书");
        
        // 根据关键词频率确定类型
        if (draftingCount > 2) return "drafting";
        if (reviewCount > 2) return "review";
        if (researchCount > 3) return "research";
        if (decisionCount > 2) return "decision";
        
        return "discussion";
    }

    /**
     * 计算关键词出现次数
     */
    private int countOccurrences(String text, String... keywords) {
        int count = 0;
        for (String keyword : keywords) {
            int index = 0;
            while ((index = text.indexOf(keyword, index)) != -1) {
                count++;
                index += keyword.length();
            }
        }
        return count;
    }

    /**
     * 生成情景标题
     */
    private String generateEpisodeTitle(SummaryResult summary, List<ChatMessage> messages) {
        // 尝试从摘要中提取标题
        if (summary.getSummaryText() != null) {
            String text = summary.getSummaryText();
            // 尝试提取第一行作为标题
            int firstNewline = text.indexOf("\n");
            if (firstNewline > 0 && firstNewline < 100) {
                String firstLine = text.substring(0, firstNewline).trim();
                firstLine = firstLine.replaceAll("【.*?】", "").trim();
                if (firstLine.length() > 5 && firstLine.length() < 50) {
                    return firstLine;
                }
            }
        }
        
        // 从第一条用户消息提取
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage um) {
                String text = um.singleText();
                if (text != null && text.length() > 0) {
                    text = cleanXmlTags(text);
                    if (text.length() > 30) {
                        text = text.substring(0, 30) + "...";
                    }
                    return text;
                }
            }
        }
        
        return "对话记录";
    }

    /**
     * 提取情景结论/结果
     */
    private String extractOutcome(SummaryResult summary) {
        // 尝试从待办事项或决策中提取
        if (summary.getPendingTasks() != null && !summary.getPendingTasks().isEmpty()) {
            return "待办: " + String.join("; ", summary.getPendingTasks());
        }
        
        // 从摘要中提取结论部分
        if (summary.getSummaryText() != null) {
            String text = summary.getSummaryText();
            Pattern pattern = Pattern.compile("【决策/结论】([\\s\\S]*?)(?=【|$)");
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String outcome = matcher.group(1).trim();
                if (!outcome.isEmpty() && !outcome.equals("-")) {
                    return outcome;
                }
            }
        }
        
        return null;
    }

    /**
     * 摘要结果
     */
    @Data
    public static class SummaryResult {
        private String summaryText;
        private List<String> keyPoints;
        private List<String> legalReferences;
        private List<String> mentionedEntities;
        private List<String> pendingTasks;
        
        /**
         * 转换为 ConversationSummary 实体
         */
        public ConversationSummary toEntity(String conversationId, Long projectId, Long userId) {
            return ConversationSummary.builder()
                    .conversationId(conversationId)
                    .projectId(projectId)
                    .userId(userId)
                    .summaryText(summaryText)
                    .keyPoints(keyPoints)
                    .legalReferences(legalReferences)
                    .mentionedEntities(mentionedEntities)
                    .pendingTasks(pendingTasks)
                    .build();
        }
    }

    /**
     * Episode 完整结果（包含结构化信息）
     */
    @Data
    public static class EpisodeResult {
        private SummaryResult summaryResult;
        private List<Map<String, Object>> events;
        private List<Map<String, String>> participants;
        private List<Map<String, Object>> timeline;
        private String episodeType;
        private String episodeTitle;
        private String episodeOutcome;
        private List<Long> relatedMemCellIds;

        /**
         * 转换为 ConversationSummary 实体（完整版）
         */
        public ConversationSummary toEntity(String conversationId, Long projectId, Long userId) {
            ConversationSummary entity = summaryResult.toEntity(conversationId, projectId, userId);
            entity.setEvents(events);
            entity.setParticipants(participants);
            entity.setTimeline(timeline);
            entity.setEpisodeType(episodeType);
            entity.setEpisodeTitle(episodeTitle);
            entity.setEpisodeOutcome(episodeOutcome);
            entity.setRelatedMemCellIds(relatedMemCellIds);
            return entity;
        }
    }
}

