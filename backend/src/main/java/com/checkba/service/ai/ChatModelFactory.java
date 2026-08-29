package com.checkba.service.ai;

import com.checkba.config.AiModelProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatModel 工厂类。
 * 负责根据前端传入的 model 参数动态构建或从缓存获取 ChatLanguageModel 实例。
 * 支持三档供应商：AWD_CLOUD（平台通道）、OPENROUTER（自备 Key）、OLLAMA（本地/实验）。
 *
 * <p>判定顺序是刻意设计并被 {@code ChatModelFactoryTest} 固化的：
 * 平台通道短路 → 白名单短路 → provider 分流。不要调整顺序——
 * 「选了 Ollama 却打到 OpenRouter」这类问题靠「前端模型集随 provider 变」解决，
 * 不靠在这里加判断（那会让平台通道的便宜模型落到用户自己的 BYOK key 上）。
 */
@Service
@RequiredArgsConstructor
public class ChatModelFactory {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatModelFactory.class);

    private final AiModelProperties aiModelProperties;
    private final com.checkba.service.SystemSettingService systemSettingService;
    private final PlatformAiChannel platformAiChannel;
    /** 平台通道的余额闸：确知 Credits 为 0 时不让这一轮跑起来。 */
    private final PlatformCreditsGate platformCreditsGate;
    private final PlatformUsageAccountant usageAccountant;
    /**
     * 辅助模型 ID 的解析器。**模型 ID 的解析只许有这一处**——记账侧
     * （{@code TokenUsageService.recordUsage} 需要模型 ID）与这里（需要模型实例）
     * 各读一遍同一个 setting 键，是本仓反复踩过的「两份口径」坑：
     * 一旦漂移就会出现「记的是 A、实际调的是 B」的错账，而且要到月底看账单才发现。
     */
    private final AuxModelResolver auxModelResolver;
    // 埋点：真实落地的 provider/model（请求传入的 modelId 可能被白名单改写，必须在 factory 记）
    // 放在末位：本仓的构造器约定是埋点参数排最后（AgentOrchestrator 同款）
    private final com.checkba.service.telemetry.TelemetryService telemetryService;

    /**
     * 存量 DB 迁移：GEMINI 档已下线，但用户库里的 ai.activeProvider 可能还存着它。
     *
     * <p>不迁移的话 {@link #resolveProvider()} 只会 warn 一句然后回退 yml 的静态配置——
     * 用户的选择被静默改变、设置页显示的又是另一回事，属于查不出来的那类问题。
     * 这里改写成 OLLAMA（三档里唯一不需要额外密钥的落点），幂等：值不是 GEMINI 就什么都不做。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void migrateRetiredGeminiProvider() {
        String active = systemSettingService.get("ai.activeProvider", null);
        if (active == null || !"GEMINI".equalsIgnoreCase(active.trim())) {
            return;
        }
        systemSettingService.set("ai.activeProvider", AiModelProperties.Provider.OLLAMA.name());
        clearCache();
        log.warn("检测到已下线的 AI 供应商 GEMINI，已迁移为 OLLAMA（本地/实验档）。"
                + "Gemini 系列模型现在由 OpenRouter 通道提供，可在设置页重新选择供应商与模型");
    }

    /** 埋点：模型实际使用（含缓存命中，反映每次调用的真实分布） */
    private void recordModelUse(String provider, String model, boolean streaming) {
        telemetryService.record("ai.model", java.util.Map.of(
                "provider", provider,
                "targetModel", model == null ? "" : model,
                "streaming", streaming));
    }

    /**
     * 缓存上限。平台通道的缓存键含密钥指纹，per-user 化之后条目数从 O(模型数) 变成
     * O(用户数×模型数)——多租户下无界 Map 就是内存泄漏，这里按访问顺序淘汰。
     */
    private static final int MODEL_CACHE_MAX = 64;

    private static <V> Map<String, V> boundedCache() {
        return java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                        return size() > MODEL_CACHE_MAX;
                    }
                });
    }

    // 缓存: key = provider + ":" + modelName（平台通道另含密钥指纹）
    private final Map<String, ChatLanguageModel> modelCache = boundedCache();

    /**
     * 解析当前生效的供应商：优先读管理后台/向导写入 system_setting 的 ai.activeProvider，
     * 未配置或值非法时回退 application.yml 的静态配置。
     */
    public AiModelProperties.Provider resolveProvider() {
        String configured = systemSettingService.get("ai.activeProvider", null);
        if (configured != null && !configured.isBlank()) {
            try {
                return AiModelProperties.Provider.valueOf(configured.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown ai.activeProvider value '{}', falling back to static config", configured);
            }
        }
        return aiModelProperties.getProvider();
    }

    /**
     * 用户是否在设置页里**显式**把供应商选成了本地 OLLAMA。
     *
     * <p>必须与 {@link #resolveProvider()} 区分开：yml 的静态默认值恰好也是 OLLAMA，
     * 而那只是「还没配过」，不代表用户要求内容留在本机——既有契约是
     * 「静态供应商是什么都不妨碍用户从模型选择器里挑云端模型」（见
     * ChatModelFactoryTest.allowedModelAlwaysUsesOpenRouter）。
     * 只有 DB 里真写着 OLLAMA，才是那句明确的「不要把内容发出去」。
     */
    private boolean isExplicitLocalProvider() {
        String configured = systemSettingService.get("ai.activeProvider", null);
        return configured != null
                && AiModelProperties.Provider.OLLAMA.name().equalsIgnoreCase(configured.trim());
    }

    /**
     * 读取系统设置，DB 里的空白值视为未配置、回退默认值。
     * 向导/管理后台保存时未填的字段会以空串写入 DB（toSettingsUpdates 的 safe()），
     * 直接用会把 baseUrl 等必填项置空导致构建模型失败。
     */
    private String getSetting(String key, String fallback) {
        String value = systemSettingService.get(key, null);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /**
     * 本地 Ollama 的 system_setting 覆盖键（写入方是 admin 页的「本地 Ollama」分区）。
     *
     * <p><b>为什么定义在路由这一侧</b>：真实路由读哪个键，连通性探测就必须读同一个键。
     * 两边各写一份字面量的话，用户在设置页换了本机模型后会出现
     * 「探测说已就绪、对话却发给 yml 里那个模型」——显示与实际不一致，
     * 正是这次改造要消灭的那类问题。{@link OllamaProbeService} 直接引用这两个常量。
     */
    public static final String SETTING_OLLAMA_BASE_URL = "ai.ollama.baseUrl";
    public static final String SETTING_OLLAMA_MODEL = "ai.ollama.modelName";

    /** 本地 Ollama 目标模型：DB 覆盖优先于 yml（空白视为未配置，与 external.openrouter.* 同口径） */
    public String resolveOllamaModelName() {
        return getSetting(SETTING_OLLAMA_MODEL, aiModelProperties.getOllama().getModelName());
    }

    /** 本地 Ollama 服务地址：口径同 {@link #resolveOllamaModelName()} */
    public String resolveOllamaBaseUrl() {
        return getSetting(SETTING_OLLAMA_BASE_URL, aiModelProperties.getOllama().getBaseUrl());
    }

    /**
     * BYOK（OpenRouter 自备 Key）凭据的 system_setting 键与解析入口。
     *
     * <p><b>为什么要收口成一份</b>：这两个键决定「哪把密钥发给哪个地址」。此前 PptxTools
     * 为了给 pptx-service 下发 model_config，自己复制了一份同语义的解析（因为 getSetting 是
     * private），于是同一件事有两处口径——本仓在密钥解析上反复踩过这个坑：改了一处、
     * 另一处继续读旧键，表现是「设置页填了 Key，某个功能仍说没配」。
     * 这里对外只暴露解析结果，不暴露键，调用方也就没机会写第二份。
     */
    public static final String SETTING_OPENROUTER_API_KEY = "external.openrouter.apiKey";
    public static final String SETTING_OPENROUTER_BASE_URL = "external.openrouter.baseUrl";

    /** BYOK 的 OpenRouter API Key：DB 覆盖优先于 yml（空白视为未配置）。**不是平台通道的 key**。 */
    public String resolveOpenRouterApiKey() {
        return getSetting(SETTING_OPENROUTER_API_KEY, aiModelProperties.getOpenRouter().getApiKey());
    }

    /** BYOK 的 OpenRouter 地址：口径同上 */
    public String resolveOpenRouterBaseUrl() {
        return getSetting(SETTING_OPENROUTER_BASE_URL, aiModelProperties.getOpenRouter().getBaseUrl());
    }

    /**
     * 清空模型实例缓存。管理后台/向导保存 API key、baseUrl 等配置后必须调用，
     * 否则旧配置构建的实例会一直用到进程重启。
     */
    public void clearCache() {
        modelCache.clear();
        streamingModelCache.clear();
        log.info("ChatModel caches cleared (config updated)");
    }

    /**
     * 解析当前生效的默认模型：优先 system_setting 的 ai.defaultModel（设置页/向导写入），
     * 空则回退 yml 的 ai.model.open-router.default-model。
     *
     * <p>本类里所有「非白名单 / 空 modelId」的回落都必须走这里，否则管理员在设置页换了默认模型，
     * 只有前端下拉的默认选项变了、后端回落路径还钉在 yml 上（两份口径的老毛病）。
     */
    public String resolveDefaultModel() {
        return getSetting("ai.defaultModel", aiModelProperties.getOpenRouter().getDefaultModel());
    }

    /**
     * 辅助模型的非流式 ChatLanguageModel：服务起标题、上下文摘要、记忆抽取、memory_search、
     * 文件自动打标签这些「用户看不见但每轮都在跑」的内部调用。
     *
     * <p>模型 ID 取 system_setting 的 ai.auxModel，空则回退 yml 的 ai.aux-model。
     *
     * <p>两个刻意的设计：
     * ① 内部直接复用 {@link #getChatModel(String)}，因此平台通道短路仍在最前面——
     * AWD_CLOUD 下辅助调用照样走平台密钥、计在平台额度里，这才是「用便宜模型省钱」的前提；
     * 自己另建 OpenRouter 客户端会把这些调用悄悄记到用户的 BYOK key 上。
     * ② 非白名单一律拒绝并抛业务异常，不静默回落默认模型：failover 链踩过同一个坑
     * （候选不在白名单 → 被回落成默认模型 → 看着切了实则原地踏步），
     * 辅助模型如果被静默换成贵模型，账单要到月底才看得出来。
     */
    public ChatLanguageModel getAuxChatModel() {
        String auxModel = auxModelResolver.auxModelId();
        if (!AllowedModels.isAllowed(auxModel)) {
            throw new com.checkba.exception.FeatureNotConfiguredException("ai-aux-model",
                    "辅助模型「" + auxModel + "」不在可用模型清单内，"
                            + "到设置页的 AI 供应商里重新选一个辅助模型即可");
        }
        return getChatModel(auxModel);
    }

    /**
     * 本轮真正会被发出去的通道与模型 id。
     *
     * <p><b>为什么要有这个方法</b>：请求里带的 modelId **不等于**实际发出去的模型。本类有三条
     * 静默改写路径（非白名单回落默认模型、显式本地档忽略云端模型、平台通道回落），每条都只
     * {@code log.warn} 一行。凡是「按模型能力做决定」的调用方（视觉判定、故障转移收窄）如果按
     * 请求里那个 id 判，就会得出与实际不符的结论——把 image 内容块发给一个读不了图的模型，
     * 换来一个英文 400。这正是三档改造要消灭的「显示与实际不一致」。
     *
     * <p><b>零漂移是硬要求</b>：{@link #getChatModel(String)} 与
     * {@link #getStreamingChatModel(String)} 都改成先调本方法再按通道分派，所以判定顺序
     * （平台通道短路 → 显式本地短路 → 白名单短路 → provider 分流）只有这一份实现。
     * 不许在别处复制一份「和工厂一样的解析」。
     *
     * @param logFallback 是否打回落警告。只有真的要建模型时才打；纯能力探测传 false，
     *                    否则同一轮会把同一条警告打两遍。
     */
    public ResolvedTarget resolveTarget(String modelId, boolean logFallback) {
        AiModelProperties.Provider provider = resolveProvider();
        String targetModel = (modelId == null || modelId.isEmpty()) ? "default" : modelId;

        // 平台通道必须先于白名单短路判定：白名单模型走的是 BYOK 的 OpenRouter key，
        // 而平台通道用的是官网 provision 的 key，两者不能混
        if (provider == AiModelProperties.Provider.AWD_CLOUD) {
            return new ResolvedTarget(AiModelProperties.Provider.AWD_CLOUD,
                    resolvePlatformModel(targetModel, logFallback));
        }

        // 显式选了本地档时必须先于白名单短路判定：那是在明确表达「内容不出这台机器」，
        // 法律文书场景下那是产品承诺、不是偏好。而白名单判定原本排在前面，只要 modelId
        // 恰好在白名单里就一律被拽去 OpenRouter；更要命的是辅助模型的默认 id
        // （qwen/qwen3.7-flash）本身就在白名单里——OLLAMA 档下每一次辅助调用都在走云端。
        if (isExplicitLocalProvider()) {
            String local = resolveOllamaModelName();
            if (logFallback && !"default".equals(targetModel) && AllowedModels.isAllowed(targetModel)) {
                log.warn("供应商为本地 OLLAMA，忽略云端模型 '{}'，改用本地模型 {}", targetModel, local);
            }
            return new ResolvedTarget(AiModelProperties.Provider.OLLAMA, local);
        }

        if (AllowedModels.isAllowed(targetModel)) {
            return new ResolvedTarget(AiModelProperties.Provider.OPENROUTER, targetModel);
        }

        // 供应商为 OPENROUTER 时，空/非白名单的 modelId 统一走 OpenRouter 默认模型，
        // 不能回退本地 Ollama（用户可能根本没装，导致 Connection refused）
        if (provider == AiModelProperties.Provider.OPENROUTER) {
            String defaultModel = resolveDefaultModel();
            if (logFallback && !"default".equals(targetModel)) {
                log.warn("Model '{}' is not in the allowed list, falling back to OpenRouter default: {}",
                        targetModel, defaultModel);
            }
            return new ResolvedTarget(AiModelProperties.Provider.OPENROUTER, defaultModel);
        }

        // 剩下只有 OLLAMA 一档（本地/实验，只支持 ASK 模式）
        return new ResolvedTarget(AiModelProperties.Provider.OLLAMA, resolveOllamaModelName());
    }

    /** 本轮真正生效的模型 id（不打回落警告）。能力判定一律用它，不要用请求里那个。 */
    public String resolveEffectiveModelId(String modelId) {
        return resolveTarget(modelId, false).modelId();
    }

    /**
     * 本轮真正生效的模型支不支持视觉输入。
     *
     * <p>本地 Ollama 档恒返回 false：它的模型 id 不在 {@link AllowedModels} 白名单里
     * （{@code llama3} 之类），而且 langchain4j-ollama 走的是另一套图片编组，与 OpenAI 兼容层
     * 不共享代码，本次不接。
     */
    public boolean effectiveModelSupportsVision(String modelId) {
        ResolvedTarget target = resolveTarget(modelId, false);
        return target.channel() != AiModelProperties.Provider.OLLAMA
                && AllowedModels.supportsVision(target.modelId());
    }

    /** 解析结果：本轮走哪个通道、用哪个模型 id。 */
    public record ResolvedTarget(AiModelProperties.Provider channel, String modelId) {
    }

    /**
     * 获取或创建 ChatLanguageModel。
     * @param modelId 前端传来的模型ID (e.g. "anthropic/claude-3.5-sonnet")。如果是 null，使用默认配置。
     * @return ChatLanguageModel 实例
     */
    public ChatLanguageModel getChatModel(String modelId) {
        ResolvedTarget target = resolveTarget(modelId, true);
        return switch (target.channel()) {
            case AWD_CLOUD -> getOrCreatePlatformModel(target.modelId());
            case OLLAMA -> getOrCreateOllamaModel(target.modelId());
            default -> getOrCreateOpenRouterModel(target.modelId());
        };
    }

    private ChatLanguageModel getOrCreateOpenRouterModel(String modelId) {
        recordModelUse("OPENROUTER", modelId, false);
        String cacheKey = "openrouter:" + modelId;
        return modelCache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new OpenRouter ChatModel instance for: {}", modelId);
            AiModelProperties.OpenRouter config = aiModelProperties.getOpenRouter();
            
            String apiKey = resolveOpenRouterApiKey();
            String baseUrl = resolveOpenRouterBaseUrl();
            
            return OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelId)
                    .timeout(config.getTimeout())
                    // logRequests 必须为 false，理由见 streamingBuilder 的 javadoc（请求体物化）
                    .logRequests(false)
                    .logResponses(true)
                    // Custom Headers for OpenRouter
                    // .defaultRequestProperties(Map.of(
                    //         "HTTP-Referer", "https://checkba.com", // Replace with actual URL
                    //         "X-Title", "Checkba AI WorkDeck"
                    // ))
                    .build();
        });
    }

    // ==================== 平台通道「AI WorkDeck 云端」 ====================

    /** 平台通道仍是 OpenRouter 后端，模型口径与 BYOK 一致：非白名单一律回落默认模型。 */
    private String resolvePlatformModel(String targetModel, boolean logFallback) {
        if (AllowedModels.isAllowed(targetModel)) return targetModel;
        String defaultModel = resolveDefaultModel();
        if (logFallback && !"default".equals(targetModel)) {
            log.warn("Model '{}' is not in the allowed list, platform channel falls back to: {}",
                    targetModel, defaultModel);
        }
        return defaultModel;
    }

    /**
     * 平台通道密钥由官网 provision，取不到时**不能**静默回退 BYOK：
     * 那会把用户自己的 key 花掉，也会掩盖「未分配额度」这类需要用户去官网处理的状态。
     * 这里原样抛出 AccountException（中文文案）。
     *
     * <p>server 模式多租户下密钥是 per-user 的，身份取自 {@link PlatformAiUserScope}；
     * 缺身份同样抛业务错误，**绝不回落机器级 key**（那等于拿别人的额度花钱）。
     */
    private String platformApiKey() {
        Long userId = PlatformAiUserScope.current();
        // 余额闸排在取 key 之前：本地已经缓存过 key 时 apiKey() 根本不联网，
        // 「没充值就不能用」不能只靠取 key 那一刻的 409（见 PlatformCreditsGate）
        platformCreditsGate.ensureCredits(userId);
        String key = platformAiChannel.apiKey();
        // 请求发出前先把用量基线建起来，否则重启后第一条消息只够建基线、cost 永远留空
        usageAccountant.ensureBaselineAsync(userId);
        return key;
    }

    /**
     * 断开账户后把 activeProvider 从平台通道摘下来，返回切换到的供应商（本来就不是平台通道时返回 null）。
     *
     * 不做这一步的话：platformAiChannel 不可用 → 每条消息都在 {@link #platformApiKey()} 抛
     * NOT_CONNECTED，而设置页仍把「AI WorkDeck 云端」渲染成正常选中（不可选标记刻意豁免当前选项），
     * 用户看不出问题出在哪。落点按「哪个还能用」挑，避免一律摔回本地 Ollama（多数人没装）。
     */
    public String demotePlatformProvider() {
        String active = systemSettingService.get("ai.activeProvider", null);
        if (active == null
                || !AiModelProperties.Provider.AWD_CLOUD.name().equalsIgnoreCase(active.trim())) {
            return null;
        }
        // server 模式多租户：ai.activeProvider 是全局设置，而平台通道的密钥是 per-user 的。
        // 管理员断开机器级账户不该把还在正常用 per-user 密钥的租户一起打断。
        if (platformAiChannel.hasPerUserKeys()) {
            log.info("机器级账户已断开，但仍有按用户的平台通道密钥在用，保持 AWD_CLOUD 不降级");
            return null;
        }
        // GEMINI 档下线后落点只剩两个：配了 key 就用 BYOK 的 OpenRouter，否则本地 Ollama
        String next;
        if (hasSetting(SETTING_OPENROUTER_API_KEY, aiModelProperties.getOpenRouter().getApiKey())) {
            next = AiModelProperties.Provider.OPENROUTER.name();
        } else {
            next = AiModelProperties.Provider.OLLAMA.name();
        }
        systemSettingService.set("ai.activeProvider", next);
        clearCache();
        log.info("账户已断开，AI 供应商由平台通道切换为 {}", next);
        return next;
    }

    private boolean hasSetting(String key, String staticFallback) {
        String value = getSetting(key, staticFallback);
        return value != null && !value.isBlank();
    }

    /** 缓存 key 带密钥指纹：官网撤销重发后指纹变化，旧实例自然作废。 */
    private ChatLanguageModel getOrCreatePlatformModel(String modelId) {
        recordModelUse("AWD_CLOUD", modelId, false);
        String apiKey = platformApiKey();
        String cacheKey = "awd_cloud:" + platformAiChannel.keyFingerprint() + ":" + modelId;
        return modelCache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new AWD Cloud ChatModel instance for: {}", modelId);
            AiModelProperties.OpenRouter config = aiModelProperties.getOpenRouter();
            return OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    // 平台通道的 baseUrl 只认 yml 配置，不读 DB：DB 那份是用户 BYOK 的自定义地址，
                    // 把 provision 出来的 key 发到用户指定的地址等于把平台凭据交出去
                    .baseUrl(config.getBaseUrl())
                    .modelName(modelId)
                    .timeout(config.getTimeout())
                    // logRequests 必须为 false，理由见 streamingBuilder 的 javadoc（请求体物化）
                    .logRequests(false)
                    .logResponses(true)
                    .build();
        });
    }

    /**
     * 两个流式通道（平台通道 / OpenRouter BYOK）共用的构建口径。
     *
     * <p><b>logResponses 必须为 false，这是可靠性契约不是调优。</b>openai4j 0.23 的
     * {@code StreamingRequestExecutor$2.onFailure} 在该开关打开时，会先对 response 调
     * {@code ResponseLoggingInterceptor.log(...)}，之后才走 errorHandler；而 okhttp-sse 的
     * {@code RealEventSource.onFailure(call, e)} 在「连接失败/被断、压根没拿到响应」这条路径上
     * 传的 response <b>恒为 null</b>，于是 log() 里的 {@code response.code()} 抛 NPE，
     * 而 onFailure 只 catch IOException —— 异常掀掉 OkHttp Dispatcher 线程，
     * <b>紧随其后的 errorHandler 那一行永远走不到</b>。
     * 表现是本轮既不 onComplete 也不 onError：传输层错误被整条吞掉，
     * 只能等 {@link AgentStreamHandler} 的看门狗兜底（AGENT 模式下用户干等三分钟）。
     * 关掉后 onFailure 直接走 errorHandler，错误正常传到编排器的分类重试（IOException → TRANSIENT）。
     *
     * <p>顺带一提这两行 DEBUG 日志本来也没人看：全仓没有任何 logging 级别配置，
     * {@code dev.ai4j.openai4j} 停在 Spring 默认的 INFO，一行都不会打印；
     * 但 slf4j 的参数是提前求值的，所以 NPE 照抛。
     *
     * <p>非流式的 {@code OpenAiChatModel} 走 SyncRequestExecutor，没有这条路径，故不受影响。
     *
     * <p><b>logRequests 也必须为 false（2026-08-29，随图片多模态一起改的）。</b>
     * openai4j 0.23 的 {@code RequestLoggingInterceptor.logDebug} 在把参数交给
     * {@code Logger.debug} <b>之前</b>先执行 {@code getBody(request)}（字节码实证：
     * {@code invokestatic getBody} 在 {@code invokeinterface Logger.debug} 之前），
     * 而这个版本的 getBody <b>不截断 base64</b>（截断是 1.x 之后才加的）。
     * 也就是说日志级别停在 INFO 一行都不打印，却每次请求都把整个请求体物化成一个 String。
     * 纯文本时代这只是浪费；接上视觉后一张 5MB 的图 base64 后约 6.7MB，
     * 一轮工具循环最多 30 轮，等于每次对话白造几百 MB 的一次性字符串垃圾。
     * 同理由三处构建口径（本方法 + 两个非流式 OpenAiChatModel）一起关掉。
     */
    static dev.langchain4j.model.openai.OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder
            streamingBuilder(String apiKey, String baseUrl, String modelId, java.time.Duration timeout) {
        return dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelId)
                .timeout(timeout)
                .logRequests(false)
                .logResponses(false);
    }

    private dev.langchain4j.model.chat.StreamingChatLanguageModel getOrCreatePlatformStreamingModel(String modelId) {
        recordModelUse("AWD_CLOUD", modelId, true);
        String apiKey = platformApiKey();
        String cacheKey = "awd_cloud_stream:" + platformAiChannel.keyFingerprint() + ":" + modelId;
        return streamingModelCache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new AWD Cloud StreamingChatModel for: {}", modelId);
            AiModelProperties.OpenRouter config = aiModelProperties.getOpenRouter();
            return streamingBuilder(apiKey, config.getBaseUrl(), modelId, config.getTimeout())
                    .build();
        });
    }

    private ChatLanguageModel getOrCreateOllamaModel(String modelName) {
        recordModelUse("OLLAMA", modelName, false);
        String cacheKey = "ollama:" + modelName;
        return modelCache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new Ollama ChatModel instance for: {}", modelName);
            AiModelProperties.Ollama config = aiModelProperties.getOllama();
            return OllamaChatModel.builder()
                    .baseUrl(resolveOllamaBaseUrl())
                    .modelName(modelName) // use param or config? use param to support multiple local models if needed
                    .temperature(config.getTemperature())
                    .timeout(config.getTimeout())
                    .build();
        });
    }

    // Streaming Cache
    private final Map<String, dev.langchain4j.model.chat.StreamingChatLanguageModel> streamingModelCache = boundedCache();

    public dev.langchain4j.model.chat.StreamingChatLanguageModel getStreamingChatModel(String modelId) {
        // 判定顺序与 getChatModel 共用同一份实现（resolveTarget），不许在这里再抄一遍：
        // 两份会漂移，而漂移的表现是「同步路和流式路发给不同的模型」。
        // OLLAMA 档的流式实现没有三参 generate，AGENT/PLAN 模式会在编排层抛异常。
        ResolvedTarget target = resolveTarget(modelId, true);
        return switch (target.channel()) {
            case AWD_CLOUD -> getOrCreatePlatformStreamingModel(target.modelId());
            case OLLAMA -> getOrCreateOllamaStreamingModel(target.modelId());
            default -> getOrCreateOpenRouterStreamingModel(target.modelId());
        };
    }

    private dev.langchain4j.model.chat.StreamingChatLanguageModel getOrCreateOpenRouterStreamingModel(String modelId) {
        recordModelUse("OPENROUTER", modelId, true);
        String cacheKey = "openrouter_stream:" + modelId;
        return streamingModelCache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new OpenRouter StreamingChatModel for: {}", modelId);
            AiModelProperties.OpenRouter config = aiModelProperties.getOpenRouter();
            
            String apiKey = resolveOpenRouterApiKey();
            String baseUrl = resolveOpenRouterBaseUrl();
            
            return streamingBuilder(apiKey, baseUrl, modelId, config.getTimeout())
                    // .defaultRequestProperties(Map.of(
                    //         "HTTP-Referer", "https://checkba.com",
                    //         "X-Title", "Checkba AI WorkDeck"
                    // ))
                    .build();
        });
    }

    private dev.langchain4j.model.chat.StreamingChatLanguageModel getOrCreateOllamaStreamingModel(String modelName) {
        recordModelUse("OLLAMA", modelName, true);
        String cacheKey = "ollama_stream:" + modelName;
        return streamingModelCache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new Ollama StreamingChatModel for: {}", modelName);
            AiModelProperties.Ollama config = aiModelProperties.getOllama();
            return dev.langchain4j.model.ollama.OllamaStreamingChatModel.builder()
                    .baseUrl(resolveOllamaBaseUrl())
                    .modelName(modelName)
                    .temperature(config.getTemperature())
                    .timeout(config.getTimeout())
                    .build();
        });
    }
}
