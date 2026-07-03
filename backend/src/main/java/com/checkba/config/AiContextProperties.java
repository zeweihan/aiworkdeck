package com.checkba.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 上下文与压缩相关配置（Phase 2：消灭散落在代码里的硬编码常量）。
 *
 * 配置前缀：ai.context
 *
 * 覆盖范围：
 * - token 预算（总预算 + 各项预留），支持按模型覆盖总预算
 * - token 估算系数（chars-per-token）
 * - 压缩各层的保留条数
 * - 文件大小/数量/字符数上限
 * - OCR 支持的扩展名列表
 */
@Component
@ConfigurationProperties(prefix = "ai.context")
public class AiContextProperties {

    /** 上下文总 token 预算（默认基于 GPT-4 128K / Gemini 1M 的保守值） */
    private int maxContextTokens = 100000;

    /** system prompt 预留 token */
    private int systemPromptReserve = 8000;

    /** 记忆注入预留 token */
    private int memoryReserve = 5000;

    /** 模型回复预留 token */
    private int responseReserve = 8000;

    /** token 估算系数：每个 token 约折合多少字符（中文约 1-2 token/字，英文约 0.25-0.5 token/字） */
    private double charsPerToken = 2.0;

    /**
     * 按模型覆盖 token 总预算。
     * key 为模型标识（小写），支持精确匹配或子串包含匹配（如 "gemini" 可匹配 "google/gemini-2.0-flash"）。
     */
    private Map<String, Integer> modelTokenBudgets = new HashMap<>();

    /** 压缩各层的保留条数配置 */
    private Compression compression = new Compression();

    /** 文件上下文的大小/数量上限配置 */
    private Files files = new Files();

    /** 支持 OCR 的文件扩展名列表 */
    private List<String> ocrExtensions = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp", "pdf");

    /**
     * 解析指定模型的上下文总 token 预算：
     * 1. 精确匹配 modelTokenBudgets 的 key（不区分大小写）
     * 2. 子串匹配：key 包含于模型标识中
     * 3. 否则返回默认 maxContextTokens
     */
    public int maxContextTokensFor(String modelKey) {
        if (modelKey == null || modelKey.isBlank() || modelTokenBudgets.isEmpty()) {
            return maxContextTokens;
        }
        String normalized = modelKey.toLowerCase();
        Integer exact = modelTokenBudgets.get(normalized);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, Integer> e : modelTokenBudgets.entrySet()) {
            if (normalized.contains(e.getKey().toLowerCase())) {
                return e.getValue();
            }
        }
        return maxContextTokens;
    }

    public static class Compression {
        /** 使用已有摘要压缩时，摘要之后保留的最近消息条数 */
        private int keepRecentWithSummary = 10;

        /** 触发"摘要旧消息"所需的最少消息条数（低于此值不做摘要） */
        private int minMessagesForSummarize = 6;

        /** 摘要旧消息时保留的最近消息条数 */
        private int keepRecentOnSummarize = 4;

        /** 激进压缩时保留的最近消息条数 */
        private int keepRecentAggressive = 2;

        /** 不触发压缩时，历史消息的最大保留条数 */
        private int maxHistoryMessages = 30;

        /** 工具输出超过该字符数时触发压缩 */
        private int toolOutputMaxChars = 2000;

        /** 工具输出压缩后的目标字符数 */
        private int toolOutputTargetChars = 1500;

        public int getKeepRecentWithSummary() { return keepRecentWithSummary; }
        public void setKeepRecentWithSummary(int keepRecentWithSummary) { this.keepRecentWithSummary = keepRecentWithSummary; }
        public int getMinMessagesForSummarize() { return minMessagesForSummarize; }
        public void setMinMessagesForSummarize(int minMessagesForSummarize) { this.minMessagesForSummarize = minMessagesForSummarize; }
        public int getKeepRecentOnSummarize() { return keepRecentOnSummarize; }
        public void setKeepRecentOnSummarize(int keepRecentOnSummarize) { this.keepRecentOnSummarize = keepRecentOnSummarize; }
        public int getKeepRecentAggressive() { return keepRecentAggressive; }
        public void setKeepRecentAggressive(int keepRecentAggressive) { this.keepRecentAggressive = keepRecentAggressive; }
        public int getMaxHistoryMessages() { return maxHistoryMessages; }
        public void setMaxHistoryMessages(int maxHistoryMessages) { this.maxHistoryMessages = maxHistoryMessages; }
        public int getToolOutputMaxChars() { return toolOutputMaxChars; }
        public void setToolOutputMaxChars(int toolOutputMaxChars) { this.toolOutputMaxChars = toolOutputMaxChars; }
        public int getToolOutputTargetChars() { return toolOutputTargetChars; }
        public void setToolOutputTargetChars(int toolOutputTargetChars) { this.toolOutputTargetChars = toolOutputTargetChars; }
    }

    public static class Files {
        /** 单文件大小上限（字节），超过则跳过提取 */
        private long maxFileSizeBytes = 10 * 1024 * 1024;

        /** 一次上下文注入的最大文件数量（跨文件夹共享配额） */
        private int maxFilesPerContext = 10;

        /** 单文件注入的最大字符数（超出截断） */
        private int maxCharsPerFile = 50000;

        /** 文件夹扫描时单文件的最大字符数（超出截断） */
        private int folderFileMaxChars = 20000;

        /** chat 接口普通文件上下文的最大字符数 */
        private int chatContextMaxChars = 6000;

        /** chat 接口文件夹上下文的最大字符数 */
        private int chatFolderContextMaxChars = 50000;

        /** chat 接口选区内容的最大字符数 */
        private int chatSelectionMaxChars = 1500;

        public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
        public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }
        public int getMaxFilesPerContext() { return maxFilesPerContext; }
        public void setMaxFilesPerContext(int maxFilesPerContext) { this.maxFilesPerContext = maxFilesPerContext; }
        public int getMaxCharsPerFile() { return maxCharsPerFile; }
        public void setMaxCharsPerFile(int maxCharsPerFile) { this.maxCharsPerFile = maxCharsPerFile; }
        public int getFolderFileMaxChars() { return folderFileMaxChars; }
        public void setFolderFileMaxChars(int folderFileMaxChars) { this.folderFileMaxChars = folderFileMaxChars; }
        public int getChatContextMaxChars() { return chatContextMaxChars; }
        public void setChatContextMaxChars(int chatContextMaxChars) { this.chatContextMaxChars = chatContextMaxChars; }
        public int getChatFolderContextMaxChars() { return chatFolderContextMaxChars; }
        public void setChatFolderContextMaxChars(int chatFolderContextMaxChars) { this.chatFolderContextMaxChars = chatFolderContextMaxChars; }
        public int getChatSelectionMaxChars() { return chatSelectionMaxChars; }
        public void setChatSelectionMaxChars(int chatSelectionMaxChars) { this.chatSelectionMaxChars = chatSelectionMaxChars; }
    }

    public int getMaxContextTokens() { return maxContextTokens; }
    public void setMaxContextTokens(int maxContextTokens) { this.maxContextTokens = maxContextTokens; }
    public int getSystemPromptReserve() { return systemPromptReserve; }
    public void setSystemPromptReserve(int systemPromptReserve) { this.systemPromptReserve = systemPromptReserve; }
    public int getMemoryReserve() { return memoryReserve; }
    public void setMemoryReserve(int memoryReserve) { this.memoryReserve = memoryReserve; }
    public int getResponseReserve() { return responseReserve; }
    public void setResponseReserve(int responseReserve) { this.responseReserve = responseReserve; }
    public double getCharsPerToken() { return charsPerToken; }
    public void setCharsPerToken(double charsPerToken) { this.charsPerToken = charsPerToken; }
    public Map<String, Integer> getModelTokenBudgets() { return modelTokenBudgets; }
    public void setModelTokenBudgets(Map<String, Integer> modelTokenBudgets) { this.modelTokenBudgets = modelTokenBudgets; }
    public Compression getCompression() { return compression; }
    public void setCompression(Compression compression) { this.compression = compression; }
    public Files getFiles() { return files; }
    public void setFiles(Files files) { this.files = files; }
    public List<String> getOcrExtensions() { return ocrExtensions; }
    public void setOcrExtensions(List<String> ocrExtensions) { this.ocrExtensions = ocrExtensions; }
}
