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
 * <p><b>2026-08-29 第三次对拍：14 条里 5 条漂了</b>，而且这次<b>两个方向都有</b>——
 * DeepSeek V4 Flash（我们记 0.14/0.28，线上 0.08596/0.17192）与 Gemini 3.6 Flash
 * （1.5/7.5 → 0.75/3.75）是<b>对用户超收</b>，DeepSeek V4 Pro（0.435/0.87 → 0.711312/1.422624）、
 * GLM-5.2（0.76/2.42 → 1.19/3.74）、GPT-5.6 Terra（1.0/6.0 → 2.0/12.0，272k 档 2.0/9.0 → 4.0/18.0）
 * 是低报。已全部按线上改正。**注意 GPT-5.6 Terra 的高档也变了**：对拍测试只比首档，
 * 高档得自己去看 {@code pricing.overrides}——只信测试会漏掉长上下文那一档。
 * 这次是因为「改模型相关的 PR 顺手跑一次」才发现的，距上次对拍 19 天。
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
 *
 * <p><b>{@code vision} 为什么有字段而 toolCalling 没有</b>：视觉能力**不是**恒为真
 * （2026-08-29 对拍：14 条里 11 条支持、3 条不支持——deepseek-v4-flash / deepseek-v4-pro / glm-5.2），
 * 而且有三个真实消费者：{@code GET /api/ai/models} 下发给前端在**选模型的那一刻**提示用户、
 * {@code ContextAssemblerService} 决定图片走视觉直送还是降级 OCR、
 * {@code AgentOrchestrator} 的故障转移候选收窄。所以它符合本类「有消费者才建模」的口径，
 * 不要照着上一段把它删掉。
 * <b>注意默认模型今天是纯文本的</b>（{@code ai.model.open-router.default-model} =
 * deepseek/deepseek-v4-flash），也就是说降级路径是常态而非边缘情况。
 *
 * <p><b>vision 位与单价一样是人手抄的、会在我们背后变</b>：来源是 OpenRouter
 * {@code /models} 响应的 {@code architecture.input_modalities} 是否含 {@code "image"}
 * （{@code modality} 字段是同一信息的字符串形式）。对拍在 {@code AllowedModelsLiveContractTest}，
 * 同样是 {@code RUN_LIVE_MODEL_CHECK=1} 门控、不进 CI，所以改模型相关的 PR 顺手手动跑一次。
 *
 * <p><b>刻意不建模的第三件事：图片单价</b>。OpenRouter 的 {@code pricing.image}（按张计费）
 * 在我们这 11 条视觉模型里只有 Gemini 3.6 Flash 有值，且是 $7.5e-7/张——其余 10 条的图像开销
 * 全部折进 {@code prompt_tokens}，已有的分档计价天然覆盖。为一条模型的、小到可忽略的单价
 * 新增一个会腐烂的数字，代价大于收益。
 */
public enum AllowedModels {

    // ==================== 国内直连（境内外均可用）====================

    DEEPSEEK_V4_FLASH("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash",
            Vendor.DEEPSEEK, Region.GLOBAL, 1_048_576,
            tier(0, 0.08596, 0.17192)),

    DEEPSEEK_V4_PRO("deepseek/deepseek-v4-pro", "DeepSeek V4 Pro",
            Vendor.DEEPSEEK, Region.GLOBAL, 1_048_576,
            tier(0, 0.711312, 1.422624)),

    GLM_5_2("z-ai/glm-5.2", "GLM-5.2",
            Vendor.ZHIPU, Region.GLOBAL, 1_048_576,
            tier(0, 1.19, 3.74)),

    KIMI_K2_6("moonshotai/kimi-k2.6", "Kimi K2.6",
            Vendor.MOONSHOT, Region.GLOBAL, 262_144,
            true,
            tier(0, 0.95, 4.0)),

    // 国产旗舰档：上面几条都是中档，重活（长文书起草、复杂检索推理）需要够强的落点
    KIMI_K3("moonshotai/kimi-k3", "Kimi K3",
            Vendor.MOONSHOT, Region.GLOBAL, 1_048_576,
            true,
            tier(0, 3.0, 15.0)),

    QWEN_3_8_MAX("qwen/qwen3.8-max", "通义千问 3.8 Max",
            Vendor.ALIBABA, Region.GLOBAL, 1_000_000,
            true,
            tier(0, 2.0, 6.0)),

    // 极便宜且 1M 上下文，是 ai.aux-model（子 Agent / 起标题 / 上下文摘要）的默认值
    QWEN_3_7_FLASH("qwen/qwen3.7-flash", "通义千问 3.7 Flash",
            Vendor.ALIBABA, Region.GLOBAL, 1_000_000,
            true,
            tier(0, 0.03, 0.13), tier(32_000, 0.1, 0.4), tier(256_000, 0.2, 0.8)),

    SEED_2_0_LITE("bytedance-seed/seed-2.0-lite", "豆包 Seed 2.0 Lite",
            Vendor.BYTEDANCE, Region.GLOBAL, 262_144,
            true,
            tier(0, 0.25, 2.0), tier(128_000, 0.5, 4.0)),

    MINIMAX_M3("minimax/minimax-m3", "MiniMax M3",
            Vendor.MINIMAX, Region.GLOBAL, 1_048_576,
            true,
            tier(0, 0.3, 1.2)),

    // ==================== 国际（境内网络会被 403 region 拒绝）====================

    CLAUDE_SONNET_5("anthropic/claude-sonnet-5", "Claude Sonnet 5",
            Vendor.ANTHROPIC, Region.INTERNATIONAL, 1_000_000,
            true,
            tier(0, 2.0, 10.0)),

    CLAUDE_HAIKU_4_5("anthropic/claude-haiku-4.5", "Claude Haiku 4.5",
            Vendor.ANTHROPIC, Region.INTERNATIONAL, 200_000,
            true,
            tier(0, 1.0, 5.0)),

    GEMINI_3_6_FLASH("google/gemini-3.6-flash", "Gemini 3.6 Flash",
            Vendor.GOOGLE, Region.INTERNATIONAL, 1_048_576,
            true,
            tier(0, 0.75, 3.75)),

    GPT_5_6_TERRA("openai/gpt-5.6-terra", "GPT-5.6 Terra",
            Vendor.OPENAI, Region.INTERNATIONAL, 1_050_000,
            true,
            tier(0, 2.0, 12.0), tier(272_000, 4.0, 18.0)),

    GROK_4_5("x-ai/grok-4.5", "Grok 4.5",
            Vendor.XAI, Region.INTERNATIONAL, 500_000,
            true,
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
    /** 是否接受图像输入（OpenRouter {@code architecture.input_modalities} 含 image）。 */
    private final boolean vision;
    /** 按 minPromptTokens 升序；首档的 minPromptTokens 恒为 0。 */
    private final List<PriceTier> priceTiers;

    /** 纯文本模型：等价于 vision=false，让不支持视觉的那几条不必多写一个 false。 */
    AllowedModels(String modelId, String displayName, Vendor vendor, Region region,
                  int contextLength, PriceTier... priceTiers) {
        this(modelId, displayName, vendor, region, contextLength, false, priceTiers);
    }

    AllowedModels(String modelId, String displayName, Vendor vendor, Region region,
                  int contextLength, boolean vision, PriceTier... priceTiers) {
        this.modelId = modelId;
        this.displayName = displayName;
        this.vendor = vendor;
        this.region = region;
        this.contextLength = contextLength;
        this.vision = vision;
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

    /** 是否支持视觉输入（图片直送）。false 时图片附件降级走 OCR 转文本，见 ContextAssemblerService。 */
    public boolean isVision() {
        return vision;
    }

    /** 某区域下支持视觉的模型，供「当前模型看不了图」时给出可换的候选。 */
    public static List<AllowedModels> visionCapableIn(Region networkRegion) {
        return availableIn(networkRegion).stream().filter(AllowedModels::isVision).toList();
    }

    /**
     * 模型 id 是否支持视觉。**未知一律返回 false**（不在白名单里的 id 只可能是本地 Ollama 模型
     * 或已下架的旧 id，两种都不能假定能看图——猜错的代价是把 image 块发给读不了图的模型换来一个
     * 英文 400，而猜保守的代价只是走一次已经存在的 OCR）。
     */
    public static boolean supportsVision(String modelId) {
        AllowedModels m = fromId(modelId);
        return m != null && m.vision;
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
