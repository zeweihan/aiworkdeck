package com.checkba.service.ai;

import java.util.Arrays;
import java.util.List;

/**
 * 允许的模型白名单，同时是模型目录的唯一事实来源。
 *
 * <p>防止前端传递恶意模型 ID 或非预期的高价模型；并通过 {@code GET /api/ai/models}
 * 下发给前端渲染模型选择器——**前端不许再硬编码任何模型清单**（历史上有三份互不同步的清单：
 * 本枚举、ChatInterface.vue 的硬编码数组、project-overview.vue 的死代码，
 * 结果是「后端加模型用户看不到、前端加模型被工厂静默回落默认模型」）。
 *
 * <p><b>精选而非全量</b>：OpenRouter 上有 400 个模型，支持按 {@code supported_parameters=tools}
 * 等服务端筛选，但把 400 个模型丢给律师用户选是负价值。这里只收常用款，每一条都经真机核对
 * （2026-08-10 重新对拍 {@code GET https://openrouter.ai/api/v1/models}）：在线、支持 tools、
 * 不是动态路由别名（{@code openrouter/auto} 之类 pricing 返 -1，静态价格表无法计价）、
 * 不带 {@code :free} 后缀（平台限流 20 RPM）。
 *
 * <p><b>单价会在我们背后变</b>：2026-08-10 那次对拍抓到 GLM-5.2 与 Kimi K2.6 的单价各自涨了
 * 3.0 倍与 1.6 倍，而表里还是旧值——方向是<b>低报</b>，BYOK 的估算成本会系统性偏低，
 * 且不会有任何东西报警（平台通道走真实扣费，看不出来）。所以这张表要定期对拍，
 * 联网护栏是 {@code AllowedModelsLiveContractTest}（需 {@code RUN_LIVE_MODEL_CHECK=1}）。
 *
 * <p><b>区域</b>：{@link Region#INTERNATIONAL} 的模型在国内网络会被 OpenRouter 返回
 * 403 "This model is not available in your region"。OpenRouter 的 API **没有任何字段**
 * 能提前告知地域可用性（{@code /models} 的 region 参数枚举只有 "eu" 且语义是 EU 数据驻留），
 * 所以区域判定只能靠我们自己的信号，见 {@link NetworkRegionService}。
 * 默认模型与故障转移链的候选**必须是 GLOBAL**，否则境内用户的兜底路径本身就是死路。
 *
 * <p><b>分档定价</b>：OpenRouter 对部分模型按输入长度分档涨价（{@code pricing.overrides}），
 * 本枚举里 4 个模型有分档。只存两个单价会在长上下文下系统性低报，所以价格是
 * {@link PriceTier} 列表，按 {@code minPromptTokens} 升序、取最后一个满足的档。
 *
 * <p><b>刻意不建模的两件事</b>：① 提示缓存命中价（{@code input_cache_read}，本枚举里多数模型都有，
 * 约为输入价的 1/10）——langchain4j 0.36 的 TokenUsage 只回 input/output 两个数，
 * 拿不到命中 token 数，无法建模，因此估算值对命中缓存的轮次会偏高；
 * ② {@code toolCalling} 字段——白名单里全部支持 tools 是构造性保证，
 * 由联网对拍测试 {@code AllowedModelsLiveContractTest} 守（需 {@code RUN_LIVE_MODEL_CHECK=1}），
 * 加一个恒为 true 的字段没有消费者。
 */
public enum AllowedModels {

    // ==================== 国内直连（境内外均可用）====================

    DEEPSEEK_V4_FLASH("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash",
            Vendor.DEEPSEEK, Region.GLOBAL, 1_048_576,
            tier(0, 0.14, 0.28)),

    DEEPSEEK_V4_PRO("deepseek/deepseek-v4-pro", "DeepSeek V4 Pro",
            Vendor.DEEPSEEK, Region.GLOBAL, 1_048_576,
            tier(0, 0.435, 0.87)),

    GLM_5_2("z-ai/glm-5.2", "GLM-5.2",
            Vendor.ZHIPU, Region.GLOBAL, 1_048_576,
            tier(0, 0.76, 2.42)),

    KIMI_K2_6("moonshotai/kimi-k2.6", "Kimi K2.6",
            Vendor.MOONSHOT, Region.GLOBAL, 262_144,
            tier(0, 0.95, 4.0)),

    // 国产旗舰档：上面几条都是中档，重活（长文书起草、复杂检索推理）需要够强的落点
    KIMI_K3("moonshotai/kimi-k3", "Kimi K3",
            Vendor.MOONSHOT, Region.GLOBAL, 1_048_576,
            tier(0, 3.0, 15.0)),

    QWEN_3_8_MAX("qwen/qwen3.8-max", "通义千问 3.8 Max",
            Vendor.ALIBABA, Region.GLOBAL, 1_000_000,
            tier(0, 2.0, 6.0)),

    // 极便宜且 1M 上下文，是 ai.aux-model（子 Agent / 起标题 / 上下文摘要）的默认值
    QWEN_3_7_FLASH("qwen/qwen3.7-flash", "通义千问 3.7 Flash",
            Vendor.ALIBABA, Region.GLOBAL, 1_000_000,
            tier(0, 0.03, 0.13), tier(32_000, 0.1, 0.4), tier(256_000, 0.2, 0.8)),

    SEED_2_0_LITE("bytedance-seed/seed-2.0-lite", "豆包 Seed 2.0 Lite",
            Vendor.BYTEDANCE, Region.GLOBAL, 262_144,
            tier(0, 0.25, 2.0), tier(128_000, 0.5, 4.0)),

    MINIMAX_M3("minimax/minimax-m3", "MiniMax M3",
            Vendor.MINIMAX, Region.GLOBAL, 1_048_576,
            tier(0, 0.3, 1.2)),

    // ==================== 国际（境内网络会被 403 region 拒绝）====================

    CLAUDE_SONNET_5("anthropic/claude-sonnet-5", "Claude Sonnet 5",
            Vendor.ANTHROPIC, Region.INTERNATIONAL, 1_000_000,
            tier(0, 2.0, 10.0)),

    CLAUDE_HAIKU_4_5("anthropic/claude-haiku-4.5", "Claude Haiku 4.5",
            Vendor.ANTHROPIC, Region.INTERNATIONAL, 200_000,
            tier(0, 1.0, 5.0)),

    GEMINI_3_6_FLASH("google/gemini-3.6-flash", "Gemini 3.6 Flash",
            Vendor.GOOGLE, Region.INTERNATIONAL, 1_048_576,
            tier(0, 1.5, 7.5)),

    GPT_5_6_TERRA("openai/gpt-5.6-terra", "GPT-5.6 Terra",
            Vendor.OPENAI, Region.INTERNATIONAL, 1_050_000,
            tier(0, 1.0, 6.0), tier(272_000, 2.0, 9.0)),

    GROK_4_5("x-ai/grok-4.5", "Grok 4.5",
            Vendor.XAI, Region.INTERNATIONAL, 500_000,
            tier(0, 2.0, 6.0), tier(200_000, 4.0, 12.0));

    /** 厂商。displayName 用于前端按厂商分组。 */
    public enum Vendor {
        DEEPSEEK("DeepSeek"),
        ZHIPU("智谱"),
        MOONSHOT("月之暗面"),
        ALIBABA("阿里云"),
        BYTEDANCE("字节跳动"),
        MINIMAX("MiniMax"),
        ANTHROPIC("Anthropic"),
        GOOGLE("Google"),
        OPENAI("OpenAI"),
        XAI("xAI");

        private final String displayName;

        Vendor(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * 网络区域可用性。
     * GLOBAL = 境内外均实测可用；INTERNATIONAL = 需国际网络，境内会 403 region。
     */
    public enum Region {
        GLOBAL,
        INTERNATIONAL
    }

    /**
     * 一档价格。{@code minPromptTokens} 是本档生效的输入 token 下限（含），单价单位 $/1M tokens。
     */
    public record PriceTier(int minPromptTokens, double inputPricePerM, double outputPricePerM) {
    }

    private static PriceTier tier(int minPromptTokens, double inputPricePerM, double outputPricePerM) {
        return new PriceTier(minPromptTokens, inputPricePerM, outputPricePerM);
    }

    private final String modelId;
    private final String displayName;
    private final Vendor vendor;
    private final Region region;
    private final int contextLength;
    /** 按 minPromptTokens 升序；首档的 minPromptTokens 恒为 0。 */
    private final List<PriceTier> priceTiers;

    AllowedModels(String modelId, String displayName, Vendor vendor, Region region,
                  int contextLength, PriceTier... priceTiers) {
        this.modelId = modelId;
        this.displayName = displayName;
        this.vendor = vendor;
        this.region = region;
        this.contextLength = contextLength;
        this.priceTiers = List.of(priceTiers);
    }

    public String getModelId() {
        return modelId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Vendor getVendor() {
        return vendor;
    }

    public Region getRegion() {
        return region;
    }

    public int getContextLength() {
        return contextLength;
    }

    public List<PriceTier> getPriceTiers() {
        return priceTiers;
    }

    /**
     * 取适用于该输入长度的价格档：按 minPromptTokens 升序找最后一个满足的档。
     * 负数或小于首档下限时返回首档。
     */
    public PriceTier priceTierFor(int promptTokens) {
        PriceTier applicable = priceTiers.get(0);
        for (PriceTier t : priceTiers) {
            if (promptTokens >= t.minPromptTokens()) {
                applicable = t;
            }
        }
        return applicable;
    }

    public static boolean isAllowed(String modelId) {
        return fromId(modelId) != null;
    }

    public static AllowedModels fromId(String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) return null;
        String needle = modelId.trim();
        for (AllowedModels m : values()) {
            if (m.modelId.equalsIgnoreCase(needle)) return m;
        }
        return null;
    }

    /** 某区域下可用的模型：国际网络全都能用，境内只剩 GLOBAL。 */
    public static List<AllowedModels> availableIn(Region networkRegion) {
        if (networkRegion == Region.INTERNATIONAL) {
            return Arrays.asList(values());
        }
        return Arrays.stream(values())
                .filter(m -> m.region == Region.GLOBAL)
                .toList();
    }
}
