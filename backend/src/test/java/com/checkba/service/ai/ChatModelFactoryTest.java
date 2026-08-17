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
    private PlatformAiChannel platformAiChannel;
    private PlatformUsageAccountant usageAccountant;
    private PlatformCreditsGate creditsGate;

    @BeforeEach
    void setUp() {
        properties = new AiModelProperties();
        properties.getOpenRouter().setApiKey("sk-or-static-key");
        systemSettingService = mock(SystemSettingService.class);
        // 默认：DB 无该配置时返回调用方给的默认值
        when(systemSettingService.get(anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        platformAiChannel = mock(PlatformAiChannel.class);
        usageAccountant = mock(PlatformUsageAccountant.class);
        // 余额闸默认放行：本类测的是路由，闸门自身的判据在 PlatformCreditsGateTest
        creditsGate = mock(PlatformCreditsGate.class);
        // AuxModelResolver 用真实实例（无状态的两依赖纯类）：辅助模型 ID 的解析
        // 只许有一处口径，用 mock 会让这里的断言测不到真实回退链。
        factory = new ChatModelFactory(properties, systemSettingService, platformAiChannel,
                creditsGate, usageAccountant,
                new AuxModelResolver(systemSettingService, AllowedModels.QWEN_3_7_FLASH.getModelId()),
                new com.checkba.service.telemetry.TelemetryService(
                        mock(com.checkba.repository.TelemetryEventRepository.class),
                        new com.checkba.service.telemetry.InstallIdentityService(
                                System.getProperty("java.io.tmpdir")),
                        "test"));
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
        ChatLanguageModel model = factory.getChatModel("deepseek/deepseek-v4-pro");
        assertInstanceOf(OpenAiChatModel.class, model,
                "白名单内的模型必须走 OpenRouter，而不是落到本地 Ollama");
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

        ChatLanguageModel first = factory.getChatModel("qwen/qwen3.7-flash");
        assertSame(first, factory.getChatModel("qwen/qwen3.7-flash"), "命中缓存应返回同一实例");

        factory.clearCache();
        assertNotSame(first, factory.getChatModel("qwen/qwen3.7-flash"),
                "clearCache 后应使用最新配置重建模型实例");
    }

    // 原「白名单必须包含前端硬编码的 8 个 id」断言已删除：前端不再硬编码模型清单
    // （改由 GET /api/ai/models 下发），那 8 个 id 里多数也已随本次白名单换代删掉，
    // 留着只会在每次换代时报假警。替代护栏在模型目录端点的测试里：
    // 它断言端点下发的清单等于 AllowedModels.availableIn(当前区域)，方向是双向的。

    // ==================== 默认模型与辅助模型（本次改造新增） ====================

    @Test
    @DisplayName("resolveDefaultModel：DB 未配置时回退 yml 的 open-router.default-model")
    void resolveDefaultModelFallsBackToYml() {
        assertEquals(properties.getOpenRouter().getDefaultModel(), factory.resolveDefaultModel());
    }

    @Test
    @DisplayName("resolveDefaultModel：DB 的 ai.defaultModel 优先；空白值视为未配置")
    void resolveDefaultModelPrefersDbSetting() {
        when(systemSettingService.get(eq("ai.defaultModel"), any())).thenReturn("z-ai/glm-5.2");
        assertEquals("z-ai/glm-5.2", factory.resolveDefaultModel());

        // 向导/后台未填的字段会以空串入库，必须回退而不是把默认模型置空
        when(systemSettingService.get(eq("ai.defaultModel"), any())).thenReturn("  ");
        assertEquals(properties.getOpenRouter().getDefaultModel(), factory.resolveDefaultModel());
    }

    @Test
    @DisplayName("非白名单 modelId 的回落要用 ai.defaultModel，而不是钉死在 yml 上")
    void unknownModelFallsBackToConfiguredDefaultModel() {
        properties.setProvider(AiModelProperties.Provider.OPENROUTER);
        when(systemSettingService.get(eq("ai.defaultModel"), any())).thenReturn("z-ai/glm-5.2");

        // 缓存键含模型 ID：回落命中的实例应与直接请求 glm-5.2 是同一个
        assertSame(factory.getChatModel("z-ai/glm-5.2"),
                factory.getChatModel("vendor/some-unlisted-model"),
                "回落目标应是管理员设置的默认模型");
        assertSame(factory.getStreamingChatModel("z-ai/glm-5.2"),
                factory.getStreamingChatModel("vendor/some-unlisted-model"));
    }

    @Test
    @DisplayName("getAuxChatModel：默认取 yml 的 ai.aux-model（白名单里最便宜的一条）")
    void auxModelDefaultsToConfiguredCheapModel() {
        properties.setProvider(AiModelProperties.Provider.OPENROUTER);

        ChatLanguageModel aux = factory.getAuxChatModel();
        assertInstanceOf(OpenAiChatModel.class, aux);
        assertSame(factory.getChatModel("qwen/qwen3.7-flash"), aux,
                "辅助调用必须落在 ai.aux-model 上，落到默认模型就等于没省钱");
    }

    @Test
    @DisplayName("getAuxChatModel：DB 的 ai.auxModel 优先")
    void auxModelPrefersDbSetting() {
        properties.setProvider(AiModelProperties.Provider.OPENROUTER);
        when(systemSettingService.get(eq("ai.auxModel"), any())).thenReturn("deepseek/deepseek-v4-flash");

        assertSame(factory.getChatModel("deepseek/deepseek-v4-flash"), factory.getAuxChatModel());
    }

    @Test
    @DisplayName("getAuxChatModel：非白名单一律报错，不静默回落默认模型（账单要到月底才看得出来）")
    void auxModelRejectsNonAllowlistedId() {
        properties.setProvider(AiModelProperties.Provider.OPENROUTER);
        when(systemSettingService.get(eq("ai.auxModel"), any())).thenReturn("vendor/ghost-model");

        var e = assertThrows(com.checkba.exception.FeatureNotConfiguredException.class,
                () -> factory.getAuxChatModel());
        assertEquals("ai-aux-model", e.getFeature());
        assertTrue(e.getMessage().contains("vendor/ghost-model"), e.getMessage());
        for (String forbidden : new String[]{"登录", "未授权", "请先"}) {
            assertFalse(e.getMessage().contains(forbidden),
                    "业务错误文案不得含「" + forbidden + "」：前端据此判定掉线并清会话");
        }
    }

    @Test
    @DisplayName("getAuxChatModel：平台通道下辅助调用仍走平台密钥（否则省钱省在用户自己的 key 上）")
    void auxModelStaysOnPlatformChannel() {
        setDbProvider("AWD_CLOUD");
        when(platformAiChannel.apiKey()).thenReturn("sk-or-provisioned");
        when(platformAiChannel.keyFingerprint()).thenReturn("abc123");

        assertInstanceOf(OpenAiChatModel.class, factory.getAuxChatModel());
        verify(platformAiChannel, atLeastOnce()).apiKey();
        verify(systemSettingService, never()).get(eq("external.openrouter.apiKey"), any());
    }

    // ==================== GEMINI 档下线的存量迁移 ====================

    @Test
    @DisplayName("启动期迁移：DB 里存量的 GEMINI 改写成 OLLAMA，不静默回退 yml 静态配置")
    void migratesRetiredGeminiProvider() {
        setDbProvider("gemini");

        factory.migrateRetiredGeminiProvider();
        verify(systemSettingService).set("ai.activeProvider", "OLLAMA");
    }

    @Test
    @DisplayName("启动期迁移是幂等的：值不是 GEMINI 就一个字都不写")
    void migrationIsIdempotent() {
        setDbProvider("OPENROUTER");

        factory.migrateRetiredGeminiProvider();
        factory.migrateRetiredGeminiProvider();
        verify(systemSettingService, never()).set(eq("ai.activeProvider"), anyString());
    }

    // ==================== 平台通道「AI WorkDeck 云端」（PR-B） ====================

    @Test
    @DisplayName("AWD_CLOUD：即便是白名单模型也走平台密钥，不能落到 BYOK 的 OpenRouter key")
    void platformChannelTakesPrecedenceOverAllowlistShortcut() {
        setDbProvider("AWD_CLOUD");
        when(platformAiChannel.apiKey()).thenReturn("sk-or-provisioned");
        when(platformAiChannel.keyFingerprint()).thenReturn("abc123");

        assertInstanceOf(OpenAiChatModel.class, factory.getChatModel("anthropic/claude-sonnet-5"));
        assertInstanceOf(OpenAiStreamingChatModel.class, factory.getStreamingChatModel("anthropic/claude-sonnet-5"));
        // 白名单短路分支绝不能先命中——那条路用的是 BYOK 的 key
        verify(platformAiChannel, atLeastOnce()).apiKey();
        verify(systemSettingService, never()).get(eq("external.openrouter.apiKey"), any());
    }

    @Test
    @DisplayName("AWD_CLOUD 但未连接账户：明确报错，不静默回退 BYOK 花用户自己的 key")
    void platformChannelWithoutAccountFailsLoudly() {
        setDbProvider("AWD_CLOUD");
        when(platformAiChannel.apiKey()).thenThrow(new com.checkba.service.account.AccountException(
                com.checkba.service.account.AccountException.Kind.NOT_CONNECTED,
                "「AI WorkDeck 云端」需要连接账户，请到设置页粘贴账户 Key"));

        var e = assertThrows(com.checkba.service.account.AccountException.class,
                () -> factory.getChatModel("deepseek/deepseek-v4-flash"));
        assertEquals(com.checkba.service.account.AccountException.Kind.NOT_CONNECTED, e.getKind());
        assertTrue(e.getMessage().contains("连接账户"), e.getMessage());
        // 绝不回落 BYOK：用户自己的 key 一次都不该被读到
        verify(systemSettingService, never()).get(eq("external.openrouter.apiKey"), any());
    }

    @Test
    @DisplayName("故障转移换模型不换通道：平台通道下备选模型仍走平台密钥，不碰 BYOK 的 key")
    void failoverCandidateStaysOnPlatformChannel() {
        setDbProvider("AWD_CLOUD");
        when(platformAiChannel.apiKey()).thenReturn("sk-or-provisioned");
        when(platformAiChannel.keyFingerprint()).thenReturn("abc123");

        // 编排器故障转移就是拿备选 modelId 再调一次工厂——通道由 provider 决定，与 modelId 无关
        assertInstanceOf(OpenAiStreamingChatModel.class,
                factory.getStreamingChatModel("qwen/qwen3.7-flash"));
        verify(platformAiChannel, atLeastOnce()).apiKey();
        verify(systemSettingService, never()).get(eq("external.openrouter.apiKey"), any());
    }

    @Test
    @DisplayName("平台通道用之前先建用量基线：否则重启后第一条消息永远显示「待结算」")
    void platformChannelEstablishesUsageBaselineBeforeCall() {
        setDbProvider("AWD_CLOUD");
        when(platformAiChannel.apiKey()).thenReturn("sk-or-provisioned");
        when(platformAiChannel.keyFingerprint()).thenReturn("abc123");

        factory.getStreamingChatModel("anthropic/claude-sonnet-5");
        verify(usageAccountant, atLeastOnce()).ensureBaselineAsync(any());
    }

    @Test
    @DisplayName("断开账户：activeProvider 从平台通道切到还配着 key 的 OpenRouter")
    void demoteFallsBackToConfiguredOpenRouter() {
        setDbProvider("AWD_CLOUD");
        when(systemSettingService.get(eq("external.openrouter.apiKey"), any())).thenReturn("sk-or-db-key");

        assertEquals("OPENROUTER", factory.demotePlatformProvider());
        verify(systemSettingService).set("ai.activeProvider", "OPENROUTER");
    }

    @Test
    @DisplayName("断开账户且没有任何云端 key：落到本地 Ollama（而不是留在打不通的平台通道）")
    void demoteFallsBackToOllamaWhenNothingConfigured() {
        properties.getOpenRouter().setApiKey("");
        setDbProvider("AWD_CLOUD");

        assertEquals("OLLAMA", factory.demotePlatformProvider());
        verify(systemSettingService).set("ai.activeProvider", "OLLAMA");
    }

    @Test
    @DisplayName("当前供应商不是平台通道：断开账户不动用户的选择")
    void demoteLeavesOtherProvidersAlone() {
        setDbProvider("OPENROUTER");

        assertNull(factory.demotePlatformProvider());
        verify(systemSettingService, never()).set(eq("ai.activeProvider"), anyString());
    }

    @Test
    @DisplayName("多租户：还有按用户的密钥在用时，断开机器级账户不降级（否则把租户一起打断）")
    void demoteKeepsPlatformWhenPerUserKeysExist() {
        setDbProvider("AWD_CLOUD");
        when(platformAiChannel.hasPerUserKeys()).thenReturn(true);
        when(systemSettingService.get(eq("external.openrouter.apiKey"), any())).thenReturn("sk-or-db-key");

        assertNull(factory.demotePlatformProvider());
        verify(systemSettingService, never()).set(eq("ai.activeProvider"), anyString());
    }

    // ==================== per-user 平台密钥（server 模式多租户） ====================

    @Test
    @DisplayName("按用户取密钥：身份来自作用域，两个用户拿到不同的模型实例")
    void perUserScopeYieldsSeparateModelInstances() {
        setDbProvider("AWD_CLOUD");
        when(platformAiChannel.apiKey()).thenAnswer(inv ->
                PlatformAiUserScope.current() == 1L ? "sk-or-alice" : "sk-or-bob");
        when(platformAiChannel.keyFingerprint()).thenAnswer(inv ->
                PlatformAiUserScope.current() == 1L ? "aaaaaa" : "bbbbbb");

        ChatLanguageModel alice = PlatformAiUserScope.call(1L, () -> factory.getChatModel("anthropic/claude-sonnet-5"));
        ChatLanguageModel bob = PlatformAiUserScope.call(2L, () -> factory.getChatModel("anthropic/claude-sonnet-5"));

        assertNotSame(alice, bob, "不同用户的密钥指纹不同，模型实例必须分叉，绝不能串用额度");
        assertSame(alice, PlatformAiUserScope.call(1L, () -> factory.getChatModel("anthropic/claude-sonnet-5")));
    }

    @Test
    @DisplayName("多租户下缺身份：报业务错误，绝不回落 BYOK 或机器级密钥")
    void missingScopeFailsLoudly() {
        setDbProvider("AWD_CLOUD");
        when(platformAiChannel.apiKey()).thenThrow(new com.checkba.service.account.AccountException(
                com.checkba.service.account.AccountException.Kind.CONFLICT,
                "本次 AI 调用未携带用户身份，「AI WorkDeck 云端」无法确定额度归属"));

        var e = assertThrows(com.checkba.service.account.AccountException.class,
                () -> factory.getChatModel("anthropic/claude-sonnet-5"));
        assertEquals(com.checkba.service.account.AccountException.Kind.CONFLICT, e.getKind());
        verify(systemSettingService, never()).get(eq("external.openrouter.apiKey"), any());
        for (String forbidden : new String[]{"登录", "未授权", "请先"}) {
            assertFalse(e.getMessage().contains(forbidden),
                    "业务错误文案不得含「" + forbidden + "」：前端据此判定掉线并清会话");
        }
    }
}
