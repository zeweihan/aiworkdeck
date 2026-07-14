package com.checkba.service.ai;

import com.checkba.config.AiModelProperties;
import com.checkba.service.SystemSettingService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ChatModelFactory 供应商路由测试。
 *
 * 回归背景：管理后台把供应商写入 system_setting 的 ai.activeProvider，
 * 但工厂此前只读 application.yml 的静态配置；且 provider=OPENROUTER 时
 * 空 modelId / 非白名单 modelId 会静默回退到本地 Ollama，导致配好
 * OpenRouter key 后仍然连不上 AI（Connection refused :11434）。
 */
class ChatModelFactoryTest {

    private AiModelProperties properties;
    private SystemSettingService systemSettingService;
    private ChatModelFactory factory;

    @BeforeEach
    void setUp() {
        properties = new AiModelProperties();
        properties.getOpenRouter().setApiKey("sk-or-static-key");
        systemSettingService = mock(SystemSettingService.class);
        // 默认：DB 无该配置时返回调用方给的默认值
        when(systemSettingService.get(anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        factory = new ChatModelFactory(properties, systemSettingService);
    }

    private void setDbProvider(String provider) {
        when(systemSettingService.get(eq("ai.activeProvider"), any())).thenReturn(provider);
    }

    @Test
    @DisplayName("DB 供应商=OPENROUTER 且 modelId 为空：应走 OpenRouter 默认模型而非 Ollama")
    void openRouterProviderWithNullModelUsesOpenRouter() {
        properties.setProvider(AiModelProperties.Provider.OLLAMA); // 静态配置故意留 OLLAMA
        setDbProvider("OPENROUTER");

        ChatLanguageModel model = factory.getChatModel(null);
        assertInstanceOf(OpenAiChatModel.class, model,
                "供应商切到 OPENROUTER 后空 modelId 不应回退本地 Ollama");

        StreamingChatLanguageModel streaming = factory.getStreamingChatModel(null);
        assertInstanceOf(OpenAiStreamingChatModel.class, streaming);
    }

    @Test
    @DisplayName("OPENROUTER 供应商 + 非白名单 modelId：回退 OpenRouter 默认模型而非 Ollama")
    void openRouterProviderWithUnknownModelFallsBackToOpenRouterDefault() {
        properties.setProvider(AiModelProperties.Provider.OPENROUTER);

        ChatLanguageModel model = factory.getChatModel("vendor/some-unlisted-model");
        assertInstanceOf(OpenAiChatModel.class, model);

        StreamingChatLanguageModel streaming = factory.getStreamingChatModel("vendor/some-unlisted-model");
        assertInstanceOf(OpenAiStreamingChatModel.class, streaming);
    }

    @Test
    @DisplayName("白名单模型：无论静态供应商是什么都走 OpenRouter")
    void allowedModelAlwaysUsesOpenRouter() {
        properties.setProvider(AiModelProperties.Provider.OLLAMA);
        ChatLanguageModel model = factory.getChatModel("openai/gpt-5.2");
        assertInstanceOf(OpenAiChatModel.class, model,
                "前端下拉提供的 openai/gpt-5.2 必须在白名单内并走 OpenRouter");
    }

    @Test
    @DisplayName("供应商=OLLAMA（DB 未配置）：空 modelId 仍走本地 Ollama（回归保护）")
    void ollamaProviderStillUsesOllama() {
        properties.setProvider(AiModelProperties.Provider.OLLAMA);

        assertInstanceOf(OllamaChatModel.class, factory.getChatModel(null));
        assertInstanceOf(OllamaStreamingChatModel.class, factory.getStreamingChatModel(null));
    }

    @Test
    @DisplayName("DB 供应商值非法时回退静态配置，不抛异常")
    void invalidDbProviderFallsBackToStaticConfig() {
        properties.setProvider(AiModelProperties.Provider.OLLAMA);
        setDbProvider("not-a-provider");

        assertInstanceOf(OllamaChatModel.class, factory.getChatModel(null));
    }

    @Test
    @DisplayName("DB 中 baseUrl 为空串（向导只填了 key 的场景）：应回退默认 baseUrl 而非报错")
    void blankBaseUrlInDbFallsBackToDefault() {
        properties.setProvider(AiModelProperties.Provider.OPENROUTER);
        // 向导/后台保存时未填字段会以空串入库（toSettingsUpdates 的 safe()）
        when(systemSettingService.get(eq("external.openrouter.baseUrl"), any())).thenReturn("");
        when(systemSettingService.get(eq("external.openrouter.apiKey"), any())).thenReturn("sk-or-db-key");

        // 修复前这里抛 IllegalArgumentException: baseUrl cannot be null or empty
        assertInstanceOf(OpenAiChatModel.class, factory.getChatModel(null));
    }

    @Test
    @DisplayName("clearCache：清空缓存后重建实例（保存新 key 后无需重启）")
    void clearCacheRebuildsModels() {
        properties.setProvider(AiModelProperties.Provider.OPENROUTER);

        ChatLanguageModel first = factory.getChatModel("openai/gpt-4o-mini");
        assertSame(first, factory.getChatModel("openai/gpt-4o-mini"), "命中缓存应返回同一实例");

        factory.clearCache();
        assertNotSame(first, factory.getChatModel("openai/gpt-4o-mini"),
                "clearCache 后应使用最新配置重建模型实例");
    }

    @Test
    @DisplayName("白名单应包含前端下拉与内部硬编码使用的全部模型（OpenRouter 2026-07 实测在线）")
    void allowedModelsCoverFrontendAndInternalIds() {
        // 前端 ChatInterface.vue availableModels + 标题/摘要/默认用的轻量模型
        String[] required = {
                "deepseek/deepseek-v4-flash",
                "deepseek/deepseek-v4-pro",
                "qwen/qwen3-235b-a22b-2507",
                "moonshotai/kimi-k2.6",
                "z-ai/glm-5",
                "anthropic/claude-sonnet-5",
                "google/gemini-3.1-pro-preview",
                "openai/gpt-5.2",
        };
        for (String id : required) {
            assertTrue(AllowedModels.isAllowed(id), "白名单缺失: " + id);
        }
    }
}
