package com.checkba.service.ai;

import com.checkba.config.AiModelProperties;
import com.checkba.model.ai.AiAssistantConfig;
import com.checkba.service.SystemSettingService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 助手配置与实例管理服务。
 *
 * 职责：
 * - 从系统设置动态加载助手配置（ai.assistants）
 * - 按 模型 + 助手 维度构建并缓存 ProjectAssistant 实例
 *
 * 历史背景：原为 AiChatController 的 loadAssistants/getAssistant 私有方法
 * 与 assistantCache 字段，Phase 2 下沉为专职服务。
 */
@Service
public class AiAssistantService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);

    private static final String KEY_AI_ASSISTANTS = "ai.assistants";

    private final SystemSettingService systemSettingService;
    private final AiModelProperties aiModelProperties;
    private final ChatModelFactory chatModelFactory;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final Map<String, ProjectAssistant> assistantCache = new ConcurrentHashMap<>();

    public AiAssistantService(SystemSettingService systemSettingService,
                              AiModelProperties aiModelProperties,
                              ChatModelFactory chatModelFactory,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.systemSettingService = systemSettingService;
        this.aiModelProperties = aiModelProperties;
        this.chatModelFactory = chatModelFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * 从系统设置加载助手配置（保留插入顺序）。
     */
    public Map<String, AiAssistantConfig> loadAssistants() {
        Map<String, AiAssistantConfig> map = new LinkedHashMap<>(); // Preserve order
        String json = systemSettingService.get(KEY_AI_ASSISTANTS, null);
        if (json != null && !json.isBlank()) {
            try {
                List<AiAssistantConfig> list = objectMapper.readValue(json,
                        new com.fasterxml.jackson.core.type.TypeReference<List<AiAssistantConfig>>() {});
                for (AiAssistantConfig cfg : list) {
                    map.put(cfg.getId(), cfg);
                }
            } catch (Exception e) {
                log.error("Failed to parse assistants config", e);
            }
        }
        return map;
    }

    /**
     * 获取（或构建并缓存）指定模型 + 助手配置的 ProjectAssistant 实例。
     */
    public ProjectAssistant getAssistant(String modelId, AiAssistantConfig assistantConfig) {
        String key = (StringUtils.hasText(modelId) ? modelId : "default").toLowerCase();
        boolean needsTools = assistantConfig != null && assistantConfig.getTools() != null && !assistantConfig.getTools().isEmpty();

        // Use a composite key for cache: model + assistantId
        String assistantId = assistantConfig != null ? assistantConfig.getId() : "default";
        String cacheKey = key + "_" + assistantId;

        return assistantCache.computeIfAbsent(cacheKey, k -> {
            ChatLanguageModel chatModel = chatModelFactory.getChatModel(modelId);

            var builder = AiServices.builder(ProjectAssistant.class)
                    .chatLanguageModel(chatModel);

            // Tools support (only for Ollama or if Model supports it)
            // For now, only Ollama and OpenRouter (via OpenAI) support tools well in LangChain4j.
            // Gemini manual impl does NOT support tools yet.
            AiModelProperties.Provider provider = aiModelProperties.getProvider();
            boolean isGemini = key.contains("gemini") || provider == AiModelProperties.Provider.GEMINI;

            if (needsTools && !isGemini) {
                // builder.tools(fileTools); // Temporarily simplified: we need ContentRetriever to handle context
            }

            // Inject Retriever if needed (usually RAG)
            // builder.contentRetriever(dynamicContentRetriever);

            return builder.build();
        });
    }
}
