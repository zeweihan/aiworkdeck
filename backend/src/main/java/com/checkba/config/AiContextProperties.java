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

    /** 运行中自动 compaction（runLoop 每轮 generate 前的超阈值摘要）配置 */
    private Compaction compaction = new Compaction();

    /** 文件上下文的大小/数量上限配置 */
    private Files files = new Files();

    /** 支持 OCR 的文件扩展名列表 */
    private List<String> ocrExtensions = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp", "pdf");

    /** 图片视觉直送（多模态）配置 */
    private Vision vision = new Vision();

    /**
     * 图片视觉直送。
     *
     * <p><b>为什么 extensions 不复用 ocrExtensions</b>：两张表**真的不一样**——ocrExtensions 含 pdf，
     * 而 PDF 不能直送。langchain4j-open-ai 0.36 的 {@code InternalOpenAiHelper.toOpenAiContent}
     * 只认 TextContent / ImageContent 两种，{@code PdfFileContent} 会抛
     * {@code IllegalArgumentException: Unknown content type}（真 jar 探针实测）；
     * 而且 PDF 现有的 OCR 路径是 PDFBox 逐页渲染再逐页 OCR、上限 20 页，行为差异巨大。
     * 所以 PDF 一律继续走 OCR，两张表分开维护、各自写清楚，而不是拿一张表打包两件事。
     */
    public static class Vision {
        /** 可以直送模型的图片扩展名。**刻意不含 pdf**，理由见 {@link Vision} 的类注释。 */
        private List<String> extensions = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp");

        /**
         * 单张图片的字节上限，超限的那张降级走 OCR 并明示。
         *
         * <p>必须自己设闸：{@code ProjectFileService.getFileBytes} 一路 readAllBytes 没有任何上限，
         * 今天图片不会撑爆堆纯粹是因为 {@code FileContentExtractorService} 那道 10MB 闸挡在 OCR 前面——
         * 跳过 OCR 直读字节等于绕开它。取值与那道闸对齐（10MB），别在这里另立一个数。
         */
        private long maxImageBytes = 10 * 1024 * 1024L;

        /** 单轮最多直送几张图，多出来的降级走 OCR 并明示。base64 会把体积再放大约 1.33 倍。 */
        private int maxImagesPerTurn = 4;

        /**
         * 压缩阈值判断时每张图片折算多少 token。
         *
         * <p>**必须给一个非零值**：{@code RunLoopCompactor} 的估算只累加 TextContent，图片贡献 0 字符，
         * 于是带大图的栈永远触发不了主动压缩、只能等服务商 400；而超限恢复通道剪不动图片，
         * 会判「压不动」直接终态——表现是带图的长会话到某个点开始每次必死。
         * **也绝不能拿 base64 字符串长度去算**：一张 500KB 的图按 charsPerToken=2.0 算出来是
         * 33 万 token，会让每一轮都强制压缩、把真正的上下文全折没。
         * 1200 是 HIGH detail 下的量级估计，方向刻意偏高（早压缩好过撞 400）。
         */
        private int tokenEstimatePerImage = 1200;

        public List<String> getExtensions() { return extensions; }
        public void setExtensions(List<String> extensions) { this.extensions = extensions; }
        public long getMaxImageBytes() { return maxImageBytes; }
        public void setMaxImageBytes(long maxImageBytes) { this.maxImageBytes = maxImageBytes; }
        public int getMaxImagesPerTurn() { return maxImagesPerTurn; }
        public void setMaxImagesPerTurn(int maxImagesPerTurn) { this.maxImagesPerTurn = maxImagesPerTurn; }
        public int getTokenEstimatePerImage() { return tokenEstimatePerImage; }
        public void setTokenEstimatePerImage(int v) { this.tokenEstimatePerImage = v; }
    }

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

    /**
     * 运行中自动 compaction：长任务的 runLoop 消息栈只增不减，撑破上下文会 400 或质量塌方。
     * 与 Compression 的区别是触发时机——这一套跑在 runLoop 内、必须保住工具调用与结果的配对。
     */
    public static class Compaction {
        /** 总开关；关掉后 runLoop 不做任何自动压缩（行为与加固前一致） */
        private boolean enabled = true;

        /** 触发比例：估算 token 超过「历史可用预算 × 该比例」时压缩 */
        private double triggerRatio = 0.8;

        /** 压缩时保留的最近消息条数（会向前扩展以免拆散工具调用与结果） */
        private int keepRecent = 8;

        /** 中段消息少于该条数就不压缩：短会话压了没收益，还平白丢上下文 */
        private int minMiddleMessages = 4;

        /** 中段摘要的字符上限 */
        private int digestMaxChars = 4000;

        /** 摘要里单条消息保留的字符数 */
        private int perMessageChars = 240;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getTriggerRatio() { return triggerRatio; }
        public void setTriggerRatio(double triggerRatio) { this.triggerRatio = triggerRatio; }
        public int getKeepRecent() { return keepRecent; }
        public void setKeepRecent(int keepRecent) { this.keepRecent = keepRecent; }
        public int getMinMiddleMessages() { return minMiddleMessages; }
        public void setMinMiddleMessages(int minMiddleMessages) { this.minMiddleMessages = minMiddleMessages; }
        public int getDigestMaxChars() { return digestMaxChars; }
        public void setDigestMaxChars(int digestMaxChars) { this.digestMaxChars = digestMaxChars; }
        public int getPerMessageChars() { return perMessageChars; }
        public void setPerMessageChars(int perMessageChars) { this.perMessageChars = perMessageChars; }
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
    public Compaction getCompaction() { return compaction; }
    public void setCompaction(Compaction compaction) { this.compaction = compaction; }
    public Files getFiles() { return files; }
    public void setFiles(Files files) { this.files = files; }
    public List<String> getOcrExtensions() { return ocrExtensions; }
    public void setOcrExtensions(List<String> ocrExtensions) { this.ocrExtensions = ocrExtensions; }
    public Vision getVision() { return vision; }
    public void setVision(Vision vision) { this.vision = vision; }
}
