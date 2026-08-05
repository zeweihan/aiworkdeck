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
 * 支持 OpenRouter, Gemini, Ollama。
 */
@Service
@RequiredArgsConstructor
public class ChatModelFactory {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatModelFactory.class);

    private final AiModelProperties aiModelProperties;
    private final com.checkba.service.SystemSettingService systemSettingService;
    private final PlatformAiChannel platformAiChannel;

    // 缓存: key = provider + ":" + modelName
    private final Map<String, ChatLanguageModel> modelCache = new ConcurrentHashMap<>();

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
     * 读取系统设置，DB 里的空白值视为未配置、回退默认值。
     * 向导/管理后台保存时未填的字段会以空串写入 DB（toSettingsUpdates 的 safe()），
     * 直接用会把 baseUrl 等必填项置空导致构建模型失败。
     */
    private String getSetting(String key, String fallback) {
        String value = systemSettingService.get(key, null);
        return (value == null || value.isBlank()) ? fallback : value;
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
     * 获取或创建 ChatLanguageModel。
     * @param modelId 前端传来的模型ID (e.g. "anthropic/claude-3.5-sonnet")。如果是 null，使用默认配置。
     * @return ChatLanguageModel 实例
     */
    public ChatLanguageModel getChatModel(String modelId) {
        // 1. Determine Provider and Normalized Model Name
        AiModelProperties.Provider provider = resolveProvider();
        String targetModel = modelId;

        if (targetModel == null || targetModel.isEmpty()) {
            targetModel = "default";
        }

        // 平台通道必须先于白名单短路判定：白名单模型走的是 BYOK 的 OpenRouter key，
        // 而平台通道用的是官网 provision 的 key，两者不能混
        if (provider == AiModelProperties.Provider.AWD_CLOUD) {
            return getOrCreatePlatformModel(resolvePlatformModel(targetModel));
        }

        // Logic to switch provider based on modelId pattern if Provider is set to OPENROUTER or dynamic
        // For now, if modelId contains "/", we assume it's OpenRouter style (except for "google/gemini" if we treat it specially, but OpenRouter handles google too)

        // Strategy:
        // - If modelID is "default" or local-looking => use Configured Provider (Ollama/Gemini) properties.
        // - If modelID looks like "provider/model" (e.g. "anthropic/claude") => Force OpenRouter if generic, or check allowed list.

        if (AllowedModels.isAllowed(targetModel)) {
            // It's a valid OpenRouter/Cloud model
            return getOrCreateOpenRouterModel(targetModel);
        }

        // 供应商为 OPENROUTER 时，空/非白名单的 modelId 统一走 OpenRouter 默认模型，
        // 不能回退本地 Ollama（用户可能根本没装，导致 Connection refused）
        if (provider == AiModelProperties.Provider.OPENROUTER) {
            String defaultModel = aiModelProperties.getOpenRouter().getDefaultModel();
            if (!"default".equals(targetModel)) {
                log.warn("Model '{}' is not in the allowed list, falling back to OpenRouter default: {}", targetModel, defaultModel);
            }
            return getOrCreateOpenRouterModel(defaultModel);
        }

        // Fallback to configured default provider (Legacy behavior)
        // If the user wants to force specific internal models (Gemini/Ollama) via "default"
        if (provider == AiModelProperties.Provider.GEMINI || (targetModel.toLowerCase().contains("gemini") && !targetModel.contains("/"))) {
            String defaultModel = getSetting("external.google.modelName", aiModelProperties.getGemini().getModelName());
            return getOrCreateGeminiModel(defaultModel); // fallback to config model name if generic request
        }

        // Default to Ollama
        return getOrCreateOllamaModel(aiModelProperties.getOllama().getModelName());
    }

    private ChatLanguageModel getOrCreateOpenRouterModel(String modelId) {
        String cacheKey = "openrouter:" + modelId;
        return modelCache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new OpenRouter ChatModel instance for: {}", modelId);
            AiModelProperties.OpenRouter config = aiModelProperties.getOpenRouter();
            
            String apiKey = getSetting("external.openrouter.apiKey", config.getApiKey());
            String baseUrl = getSetting("external.openrouter.baseUrl", config.getBaseUrl());
            
            return OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelId)
                    .timeout(config.getTimeout())
                    .logRequests(true)
                    .logResponses(true)
                    // Custom Headers for OpenRouter
                    // .defaultRequestProperties(Map.of(
                    //         "HTTP-Referer", "https://checkba.com", // Replace with actual URL
                    //         "X-Title", "Checkba AI Workdeck"
                    // ))
                    .build();
        });
    }

    // ==================== 平台通道「AI Workdeck 云端」 ====================

    /** 平台通道仍是 OpenRouter 后端，模型口径与 BYOK 一致：非白名单一律回落默认模型。 */
    private String resolvePlatformModel(String targetModel) {
        if (AllowedModels.isAllowed(targetModel)) return targetModel;
        String defaultModel = aiModelProperties.getOpenRouter().getDefaultModel();
        if (!"default".equals(targetModel)) {
            log.warn("Model '{}' is not in the allowed list, platform channel falls back to: {}",
                    targetModel, defaultModel);
        }
        return defaultModel;
    }

    /**
     * 平台通道密钥由官网 provision，取不到时**不能**静默回退 BYOK：
     * 那会把用户自己的 key 花掉，也会掩盖「未分配额度」这类需要用户去官网处理的状态。
     * 这里原样抛出 AccountException（中文文案，如「请先在官网账户页分配 AI 额度」）。
     */
    private String platformApiKey() {
        if (!platformAiChannel.isAvailable()) {
            throw new com.checkba.service.account.AccountException(
                    com.checkba.service.account.AccountException.Kind.NOT_CONNECTED,
                    "「AI Workdeck 云端」需要先连接账户，请到设置页粘贴账户 Key");
        }
        return platformAiChannel.apiKey();
    }

    /** 缓存 key 带密钥指纹：官网撤销重发后指纹变化，旧实例自然作废。 */
    private ChatLanguageModel getOrCreatePlatformModel(String modelId) {
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
                    .logRequests(true)
                    .logResponses(true)
                    .build();
        });
    }

    private dev.langchain4j.model.chat.StreamingChatLanguageModel getOrCreatePlatformStreamingModel(String modelId) {
        String apiKey = platformApiKey();
        String cacheKey = "awd_cloud_stream:" + platformAiChannel.keyFingerprint() + ":" + modelId;
        return streamingModelCache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new AWD Cloud StreamingChatModel for: {}", modelId);
            AiModelProperties.OpenRouter config = aiModelProperties.getOpenRouter();
            return dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(config.getBaseUrl())
                    .modelName(modelId)
                    .timeout(config.getTimeout())
                    .logRequests(true)
                    .logResponses(true)
                    .build();
        });
    }

    private ChatLanguageModel getOrCreateOllamaModel(String modelName) {
        String cacheKey = "ollama:" + modelName;
        return modelCache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new Ollama ChatModel instance for: {}", modelName);
            AiModelProperties.Ollama config = aiModelProperties.getOllama();
            return OllamaChatModel.builder()
                    .baseUrl(config.getBaseUrl())
                    .modelName(modelName) // use param or config? use param to support multiple local models if needed
                    .temperature(config.getTemperature())
                    .timeout(config.getTimeout())
                    .build();
        });
    }

    private ChatLanguageModel getOrCreateGeminiModel(String modelName) {
        String cacheKey = "gemini:" + modelName;
        return modelCache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new Gemini ChatModel instance for: {}", modelName);
            AiModelProperties.Gemini config = aiModelProperties.getGemini();
            
            String apiKey = getSetting("external.google.apiKey", config.getApiKey());
            String baseUrl = getSetting("external.google.apiBaseUrl", config.getApiBaseUrl());

            return new GeminiChatLanguageModel(
                    baseUrl,
                    modelName,
                    apiKey,
                    config.getTimeout()
            );
        });
    }
    // Streaming Cache
    private final Map<String, dev.langchain4j.model.chat.StreamingChatLanguageModel> streamingModelCache = new ConcurrentHashMap<>();

    public dev.langchain4j.model.chat.StreamingChatLanguageModel getStreamingChatModel(String modelId) {
        String targetModel = (modelId == null || modelId.isEmpty()) ? "default" : modelId;
        AiModelProperties.Provider provider = resolveProvider();

        // 同 getChatModel：平台通道先于白名单短路
        if (provider == AiModelProperties.Provider.AWD_CLOUD) {
            return getOrCreatePlatformStreamingModel(resolvePlatformModel(targetModel));
        }

        if (AllowedModels.isAllowed(targetModel)) {
            return getOrCreateOpenRouterStreamingModel(targetModel);
        }

        // 同 getChatModel：OPENROUTER 供应商下不回退本地 Ollama
        if (provider == AiModelProperties.Provider.OPENROUTER) {
            String defaultModel = aiModelProperties.getOpenRouter().getDefaultModel();
            if (!"default".equals(targetModel)) {
                log.warn("Model '{}' is not in the allowed list, falling back to OpenRouter default: {}", targetModel, defaultModel);
            }
            return getOrCreateOpenRouterStreamingModel(defaultModel);
        }

        if (provider == AiModelProperties.Provider.GEMINI || (targetModel.toLowerCase().contains("gemini") && !targetModel.contains("/"))) {
            // TODO: Implement Gemini Streaming. For now, throw or fallback? 
            // Or use OpenRouter for Gemini if configured?
            // Let's return a specific failure or just try OpenRouter if key is present?
            // For now, let's assume we use Ollama fallback or implement a simple Gemini adapter later.
            // Returning null might crash Orchestrator.
            // Let's try OpenRouter logic if it looks like a model ID, otherwise Ollama.
             return getOrCreateOllamaStreamingModel(aiModelProperties.getOllama().getModelName());
        }

        return getOrCreateOllamaStreamingModel(aiModelProperties.getOllama().getModelName());
    }

    private dev.langchain4j.model.chat.StreamingChatLanguageModel getOrCreateOpenRouterStreamingModel(String modelId) {
        String cacheKey = "openrouter_stream:" + modelId;
        return streamingModelCache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new OpenRouter StreamingChatModel for: {}", modelId);
            AiModelProperties.OpenRouter config = aiModelProperties.getOpenRouter();
            
            String apiKey = getSetting("external.openrouter.apiKey", config.getApiKey());
            String baseUrl = getSetting("external.openrouter.baseUrl", config.getBaseUrl());
            
            return dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelId)
                    .timeout(config.getTimeout())
                    .logRequests(true)
                    .logResponses(true)
                    // .defaultRequestProperties(Map.of(
                    //         "HTTP-Referer", "https://checkba.com",
                    //         "X-Title", "Checkba AI Workdeck"
                    // ))
                    .build();
        });
    }

    private dev.langchain4j.model.chat.StreamingChatLanguageModel getOrCreateOllamaStreamingModel(String modelName) {
        String cacheKey = "ollama_stream:" + modelName;
        return streamingModelCache.computeIfAbsent(cacheKey, k -> {
            log.info("Creating new Ollama StreamingChatModel for: {}", modelName);
            AiModelProperties.Ollama config = aiModelProperties.getOllama();
            return dev.langchain4j.model.ollama.OllamaStreamingChatModel.builder()
                    .baseUrl(config.getBaseUrl())
                    .modelName(modelName)
                    .temperature(config.getTemperature())
                    .timeout(config.getTimeout())
                    .build();
        });
    }
}
