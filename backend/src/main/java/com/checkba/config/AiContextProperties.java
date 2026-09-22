// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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

    /**
     * 上下文总 token 预算的**兜底值**：只在模型标识解析不出上下文长度时才用到
     *（白名单外的模型 id、Ollama 本地模型、modelKey 为 null 的调用）。
     *
     * <p>白名单内的模型走 {@code AllowedModels.getContextLength()} 派生（见 {@link #maxContextTokensFor}），
     * 不再吃常数。旧值 100000 的依据是「GPT-4 128K」，而今天白名单里最小的
     * Claude Haiku 4.5 也有 20 万，多数是 100 万。用 10 万当总预算的后果是每条稍长的会话
     * 都在首 token 之前插一次<b>同步 LLM 摘要调用</b>（ContextCompressor → ConversationSummarizer），
     * 白白多等一整个模型往返，还把真实上下文压没了。
     *
     * <p><b>为什么兜底值也一起抬到 20 万</b>：{@link #systemPromptReserve} 同时从 8000 改到了
     * 60000（那才是真实前缀体量）。兜底值若还停在 10 万，解析不出上下文长度的模型
     *（白名单外 id / 本地 Ollama）的「历史可用预算」会从 79000 掉到 27000 —— 比改动前更早触发
     * 同步摘要，与本次「提速」的目标正好相反。20 万取白名单里最小的那一条，
     * 保证这次改动<b>只会放宽、不会收紧任何一个模型</b>。
     */
    private int maxContextTokens = 200000;

    /**
     * 派生总预算时给模型标称上下文留的余量比例（0.85 = 用 85%）。
     *
     * <p>标称上下文是「输入 + 输出」的总和，而 responseReserve 只覆盖我们预期的回复长度；
     * 再加上 chars/token=2.0 这个估算系数对中文系统性偏乐观，顶着标称值用必然会撞 400。
     * 撞了也不是没救（CONTEXT_OVERFLOW 有专用恢复通道），但那要白烧一次请求。
     */
    private double modelBudgetHeadroom = 0.85;

    /**
     * system prompt 预留 token。
     *
     * <p><b>60000 是实测值，不是拍的</b>：本机 telemetry 的每轮 promptTokens 约 5 万，
     * 其中约 2/3 是工具 schema（202 个工具 59045 token，裁到 16 个只剩 18854）。
     * 旧值 8000 与真实固定前缀差了近一个数量级，于是「历史可用预算」被系统性高估
     * 7 倍有余——压缩该触发时不触发（等服务商 400），不该触发时因为总预算只有 10 万
     * 反而提前触发。这一项与 {@link #maxContextTokens} 的错误方向相反，互相掩盖了很久。
     */
    private int systemPromptReserve = 60000;

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

    /**
     * 扫描件 PDF 走 OCR 时最多识别几页。
     *
     * <p><b>只约束 OCR 路径</b>：带文字层的 PDF 由 PDFBox 整篇抽取，没有页数上限
     *（dev-board#800 之前所有 PDF 都走 OCR，于是一份 300 页的招股书只有前 20 页进了上下文）。
     * OCR 这条留上限是因为它按页花时间、平台档还按页扣 Credits，一份几百页的扫描件
     * 能把一轮对话拖死；触发时会在正文末尾明写「仅识别前 N 页」，不让模型把看到的当全部。
     */
    private int ocrMaxPdfPages = 20;

    /** 正文抽取结果的跨重启缓存 */
    private TextCache textCache = new TextCache();

    /**
     * 正文抽取结果缓存（dev-board#800，表 project_file_text_cache）。
     *
     * <p>键是 fileId，失效判据是物理文件的 mtime + size。缓存的是抽取结果本身，
     * 与「一轮里的重复抽取」那层 32 条内存 LRU（DocumentTextService）并存、互不替代。
     */
    public static class TextCache {

        /** 关掉就是回到每次重抽的旧行为（排障用；正常不该关）。 */
        private boolean enabled = true;

        /**
         * 单条正文的字符上限，超出不缓存。
         *
         * <p>100 万字符约 2MB（Java String 的 UTF-16 在库里按 UTF-8 存约 1-3MB）。
         * 取这个数的理由：注入上下文的单文件上限是 {@code files.max-chars-per-file}=5 万，
         * 工具输出上限 8 万，都远在其下；真正会超的是「整篇几百页」那种，
         * 而它们本来就会被截断后才用，缓存全文只是白占库容。
         */
        private int maxTextChars = 1_000_000;

        /**
         * 总行数上限，超出按 created_at 淘汰最旧的。
         *
         * <p>2000 × 平均几十 KB ≈ 几十到一百 MB 量级，桌面端 H2 单文件库也扛得住；
         * 同时 2000 份文件足够覆盖一个活跃项目的全部材料，日常命中率不会被淘汰打断。
         */
        private int maxEntries = 2000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxTextChars() { return maxTextChars; }
        public void setMaxTextChars(int maxTextChars) { this.maxTextChars = maxTextChars; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
    }

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
     * <ol>
     *   <li>精确匹配 modelTokenBudgets 的 key（不区分大小写）——显式配置永远最优先；</li>
     *   <li>子串匹配：key 包含于模型标识中；</li>
     *   <li><b>按 {@code com.checkba.service.ai.AllowedModels} 的标称上下文长度派生</b>
     *       （× {@link #modelBudgetHeadroom}）；</li>
     *   <li>都不命中（白名单外 id / Ollama 本地模型 / modelKey 为 null）时返回兜底
     *       {@link #maxContextTokens}。</li>
     * </ol>
     *
     * <p>第 3 步是 dev-board#729 ④ 加的。此前 {@code modelTokenBudgets} 一直是空的，
     * 于是所有模型都吃 10 万这个常数——而生产默认模型 deepseek-v4-flash 的真实上下文是
     * 1,048,576。把 100 万的窗口当 10 万用，代价是每条长一点的会话都在首 token 之前
     * 多插一次同步 LLM 摘要调用。
     *
     * <p><b>只会变大不会变小</b>：白名单里最小的是 Claude Haiku 4.5 的 20 万，
     * ×0.85 = 17 万，仍高于原来的 10 万。所以这条改动不会让任何模型比以前更早触发压缩。
     */
    public int maxContextTokensFor(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return maxContextTokens;
        }
        String normalized = modelKey.toLowerCase();
        if (!modelTokenBudgets.isEmpty()) {
            Integer exact = modelTokenBudgets.get(normalized);
            if (exact != null) {
                return exact;
            }
            for (Map.Entry<String, Integer> e : modelTokenBudgets.entrySet()) {
                if (normalized.contains(e.getKey().toLowerCase())) {
                    return e.getValue();
                }
            }
        }
        com.checkba.service.ai.AllowedModels known = com.checkba.service.ai.AllowedModels.fromId(modelKey);
        if (known != null) {
            int derived = (int) Math.floor(known.getContextLength() * modelBudgetHeadroom);
            if (derived > 0) {
                return derived;
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
    public double getModelBudgetHeadroom() { return modelBudgetHeadroom; }
    public void setModelBudgetHeadroom(double modelBudgetHeadroom) { this.modelBudgetHeadroom = modelBudgetHeadroom; }
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
    public int getOcrMaxPdfPages() { return ocrMaxPdfPages; }
    public void setOcrMaxPdfPages(int ocrMaxPdfPages) { this.ocrMaxPdfPages = ocrMaxPdfPages; }
    public TextCache getTextCache() { return textCache; }
    public void setTextCache(TextCache textCache) { this.textCache = textCache; }
    public Vision getVision() { return vision; }
    public void setVision(Vision vision) { this.vision = vision; }
}
