package com.checkba.service.ai.context;

import com.checkba.config.AiContextProperties;
import com.checkba.model.entity.ConversationSummary;
import com.checkba.model.entity.ProjectMemory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 上下文压缩器
 * 实现智能上下文压缩，在保留关键信息的前提下减少 token 使用
 *
 * Token 预算、估算系数与各层保留条数均由 AiContextProperties（ai.context.*）配置，
 * token 总预算支持按模型覆盖（ai.context.model-token-budgets）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContextCompressor {

    private final LegalInfoProtector legalInfoProtector;
    private final ConversationSummarizer conversationSummarizer;
    private final AiContextProperties contextProperties;

    /**
     * 压缩消息历史
     * @param messages 原始消息列表
     * @param projectMemory 项目记忆
     * @param conversationSummary 已有的对话摘要
     * @param targetTokens 目标 token 数量
     * @return 压缩后的消息列表
     */
    public List<ChatMessage> compress(List<ChatMessage> messages,
                                       ProjectMemory projectMemory,
                                       ConversationSummary conversationSummary,
                                       int targetTokens) {
        int currentTokens = estimateTokens(messages);
        
        log.info("Context compression: currentTokens={}, targetTokens={}, messageCount={}",
                currentTokens, targetTokens, messages.size());

        if (currentTokens <= targetTokens) {
            // 无需压缩
            return messages;
        }

        List<ChatMessage> result = new ArrayList<>();

        // 第一层：如果有已存在的摘要，使用它代替旧消息
        if (conversationSummary != null && conversationSummary.getSummaryText() != null) {
            result.add(SystemMessage.from("[对话历史摘要]\n" + conversationSummary.getSummaryText()));

            // 只保留摘要之后的新消息（简化处理：保留最近 N 条消息）
            int keepRecent = Math.min(contextProperties.getCompression().getKeepRecentWithSummary(), messages.size());
            for (int i = messages.size() - keepRecent; i < messages.size(); i++) {
                result.add(messages.get(i));
            }
            
            int newTokens = estimateTokens(result);
            if (newTokens <= targetTokens) {
                log.info("Compression using existing summary: {} -> {} tokens", currentTokens, newTokens);
                return result;
            }
        }

        // 第二层：移除冗余信息并压缩单条消息
        result = removeRedundancy(messages);
        int afterRedundancy = estimateTokens(result);
        log.debug("After removing redundancy: {} tokens", afterRedundancy);
        
        if (afterRedundancy <= targetTokens) {
            return result;
        }

        // 第三层：压缩工具调用结果
        result = compressToolResults(result);
        int afterToolCompress = estimateTokens(result);
        log.debug("After compressing tool results: {} tokens", afterToolCompress);
        
        if (afterToolCompress <= targetTokens) {
            return result;
        }

        // 第四层：生成摘要替换旧消息
        result = summarizeOldMessages(result, targetTokens);
        int afterSummarize = estimateTokens(result);
        log.debug("After summarizing old messages: {} tokens", afterSummarize);
        
        if (afterSummarize <= targetTokens) {
            return result;
        }

        // 第五层：激进压缩 - 只保留最关键的信息
        result = aggressiveCompress(result, projectMemory, targetTokens);
        
        int finalTokens = estimateTokens(result);
        log.info("Final compression result: {} -> {} tokens ({}% reduction)", 
                currentTokens, finalTokens, 
                String.format("%.1f", (1 - (double)finalTokens / currentTokens) * 100));

        return result;
    }

    /**
     * 估算 token 数量
     */
    public int estimateTokens(List<ChatMessage> messages) {
        int totalChars = 0;
        for (ChatMessage msg : messages) {
            String text = extractText(msg);
            if (text != null) {
                totalChars += text.length();
            }
        }
        return (int) (totalChars / contextProperties.getCharsPerToken());
    }

    /**
     * 估算单条消息的 token 数量
     */
    public int estimateTokens(String text) {
        if (text == null) return 0;
        return (int) (text.length() / contextProperties.getCharsPerToken());
    }

    /**
     * 提取消息文本
     */
    private String extractText(ChatMessage msg) {
        if (msg instanceof UserMessage um) {
            // 不能用 singleText()：多模态消息（文本 + 图片）会直接抛 RuntimeException，
            // 而本方法整条链路没有 try/catch，异常会一路冒到上下文组装、整轮对话挂掉。
            return ChatMessageText.of(um);
        } else if (msg instanceof AiMessage am) {
            return am.text();
        } else if (msg instanceof SystemMessage sm) {
            return sm.text();
        }
        return null;
    }

    /**
     * 第一层：移除冗余信息
     */
    private List<ChatMessage> removeRedundancy(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();
        Set<String> seenContent = new HashSet<>();
        
        for (ChatMessage msg : messages) {
            String text = extractText(msg);
            if (text == null) {
                result.add(msg);
                continue;
            }
            
            // 移除 XML 标签中的冗余内容（如重复的 thinking 标签）
            text = removeRedundantTags(text);
            
            // 检查是否与之前的消息高度相似
            String normalized = normalizeForComparison(text);
            if (normalized.length() > 50 && seenContent.contains(normalized)) {
                log.debug("Skipping duplicate message");
                continue;
            }
            seenContent.add(normalized);
            
            // 重建消息
            if (msg instanceof UserMessage um) {
                // 含图像内容块的用户消息**原样保留、不重建**：UserMessage.from(text) 只装得下文本，
                // 重建等于把图片静默剥掉——不报错、不留日志，表现是「有时候能看图有时候看不见」。
                result.add(ChatMessageText.imageCountOf(um) > 0 ? um : UserMessage.from(text));
            } else if (msg instanceof AiMessage) {
                result.add(AiMessage.from(text));
            } else {
                result.add(msg);
            }
        }
        
        return result;
    }

    /**
     * 移除冗余的 XML 标签内容
     */
    private String removeRedundantTags(String text) {
        // 移除多余的 thinking 标签内容（保留第一个）
        text = text.replaceAll("(?s)(<thinking>.*?</thinking>).*?(<thinking>.*?</thinking>)", "$1");
        
        // 移除空的标签
        text = text.replaceAll("<[^/>]+></[^>]+>", "");
        
        // 压缩多余空白
        text = text.replaceAll("\\n{3,}", "\n\n");
        
        return text.trim();
    }

    /**
     * 标准化文本用于比较
     */
    private String normalizeForComparison(String text) {
        // 先归一化再截断：substring 上界必须用归一化后的长度，用原串长度会在归一化变短时越界 StringIndexOutOfBounds
        String normalized = text.replaceAll("\\s+", " ")
                .replaceAll("<[^>]+>", "")
                .toLowerCase();
        return normalized.substring(0, Math.min(200, normalized.length()));
    }

    /**
     * 第二层：压缩工具调用结果
     */
    private List<ChatMessage> compressToolResults(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();
        
        for (ChatMessage msg : messages) {
            String text = extractText(msg);
            if (text == null) {
                result.add(msg);
                continue;
            }
            
            // 压缩 tool_output 内容
            if (text.contains("<tool_output>")) {
                text = compressToolOutput(text);
            }
            
            // 重建消息
            if (msg instanceof UserMessage um) {
                // 含图像内容块的用户消息**原样保留、不重建**：UserMessage.from(text) 只装得下文本，
                // 重建等于把图片静默剥掉——不报错、不留日志，表现是「有时候能看图有时候看不见」。
                result.add(ChatMessageText.imageCountOf(um) > 0 ? um : UserMessage.from(text));
            } else if (msg instanceof AiMessage) {
                result.add(AiMessage.from(text));
            } else {
                result.add(msg);
            }
        }
        
        return result;
    }

    /**
     * 压缩工具输出
     */
    private String compressToolOutput(String text) {
        // 提取并压缩 tool_output 内容
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(<tool_output[^>]*>)(.*?)(</tool_output>)", 
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(text);
        
        while (matcher.find()) {
            result.append(text, lastEnd, matcher.start());
            
            String openTag = matcher.group(1);
            String content = matcher.group(2);
            String closeTag = matcher.group(3);
            
            // 如果输出超过上限字符数，截断
            if (content.length() > contextProperties.getCompression().getToolOutputMaxChars()) {
                // 保留法律关键信息
                LegalInfoProtector.CompressedResult compressed =
                        legalInfoProtector.safeCompress(content, contextProperties.getCompression().getToolOutputTargetChars());
                content = compressed.getContent();
                if (compressed.isWasCompressed()) {
                    content += "\n[输出已压缩，保留关键信息]";
                }
            }
            
            result.append(openTag).append(content).append(closeTag);
            lastEnd = matcher.end();
        }
        
        result.append(text.substring(lastEnd));
        return result.toString();
    }

    /**
     * 第三层：摘要旧消息
     */
    private List<ChatMessage> summarizeOldMessages(List<ChatMessage> messages, int targetTokens) {
        if (messages.size() <= contextProperties.getCompression().getMinMessagesForSummarize()) {
            // 消息太少，不需要摘要
            return messages;
        }

        List<ChatMessage> result = new ArrayList<>();

        // 保留最近 N 条消息
        int keepRecent = contextProperties.getCompression().getKeepRecentOnSummarize();
        List<ChatMessage> oldMessages = messages.subList(0, messages.size() - keepRecent);
        List<ChatMessage> recentMessages = messages.subList(messages.size() - keepRecent, messages.size());
        
        // 为旧消息生成摘要
        String summary = conversationSummarizer.generateQuickSummary(oldMessages);
        
        // 添加摘要作为系统消息
        result.add(SystemMessage.from("[对话历史摘要]\n" + summary));
        
        // 添加最近的消息
        result.addAll(recentMessages);
        
        return result;
    }

    /**
     * 第四层：激进压缩
     */
    private List<ChatMessage> aggressiveCompress(List<ChatMessage> messages, 
                                                   ProjectMemory projectMemory,
                                                   int targetTokens) {
        List<ChatMessage> result = new ArrayList<>();
        
        // 构建核心上下文
        StringBuilder coreContext = new StringBuilder();
        coreContext.append("[压缩上下文 - 仅保留核心信息]\n\n");
        
        // 添加项目核心信息
        if (projectMemory != null) {
            coreContext.append("## 项目信息\n");
            coreContext.append(projectMemory.toCoreContext());
            coreContext.append("\n");
        }
        
        // 提取所有消息中的法律关键信息
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
        
        if (!legalRefs.isEmpty()) {
            coreContext.append("## 法律引用\n");
            legalRefs.forEach(ref -> coreContext.append("- ").append(ref).append("\n"));
            coreContext.append("\n");
        }
        
        if (!amounts.isEmpty()) {
            coreContext.append("## 关键金额\n");
            amounts.forEach(amt -> coreContext.append("- ").append(amt).append("\n"));
            coreContext.append("\n");
        }
        
        if (!dates.isEmpty()) {
            coreContext.append("## 关键日期\n");
            dates.forEach(date -> coreContext.append("- ").append(date).append("\n"));
            coreContext.append("\n");
        }
        
        result.add(SystemMessage.from(coreContext.toString()));

        // 只保留最近 N 条消息
        int keepRecent = Math.min(contextProperties.getCompression().getKeepRecentAggressive(), messages.size());
        for (int i = messages.size() - keepRecent; i < messages.size(); i++) {
            result.add(messages.get(i));
        }
        
        return result;
    }

    /**
     * 检查是否需要压缩（使用默认 token 预算）
     */
    public boolean needsCompression(List<ChatMessage> messages) {
        return needsCompression(messages, null);
    }

    /**
     * 检查是否需要压缩（token 预算按模型解析）
     */
    public boolean needsCompression(List<ChatMessage> messages, String modelKey) {
        int tokens = estimateTokens(messages);
        return tokens > getAvailableTokensForHistory(modelKey);
    }

    /**
     * 获取可用于历史的 token 预算（默认预算）
     */
    public int getAvailableTokensForHistory() {
        return getAvailableTokensForHistory(null);
    }

    /**
     * 获取可用于历史的 token 预算（按模型解析总预算后扣除各项预留）
     */
    public int getAvailableTokensForHistory(String modelKey) {
        return contextProperties.maxContextTokensFor(modelKey)
                - contextProperties.getSystemPromptReserve()
                - contextProperties.getMemoryReserve()
                - contextProperties.getResponseReserve();
    }

    /**
     * 压缩统计结果
     */
    @Data
    @AllArgsConstructor
    public static class CompressionStats {
        private int originalTokens;
        private int compressedTokens;
        private int originalMessageCount;
        private int compressedMessageCount;
        private double compressionRatio;
        
        public static CompressionStats of(int originalTokens, int compressedTokens,
                                          int originalMessageCount, int compressedMessageCount) {
            double ratio = originalTokens > 0 ? (double) compressedTokens / originalTokens : 1.0;
            return new CompressionStats(originalTokens, compressedTokens, 
                    originalMessageCount, compressedMessageCount, ratio);
        }
    }
}

