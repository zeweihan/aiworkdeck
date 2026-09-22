// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller.ai;

import com.checkba.config.AiContextProperties;
import com.checkba.config.AiModelProperties;
import com.checkba.controller.AuthController;
import com.checkba.service.ai.AllowedModels;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.NetworkRegionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 上下文上限的下发与「本地档 vision 归一」（dev-board#801 K21 ⑧⑨）。
 *
 * <p>长期原则 5「单一事实来源」：上限值只在后端配置，经 {@code GET /api/ai/config} 下发；
 * 前端复制一份就是第二处事实来源——改了配置之后界面仍按旧值拦截，两边说的话对不上。
 */
class AiConfigContextLimitsTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(AiContextProperties properties) {
        ChatModelFactory factory = mock(ChatModelFactory.class);
        when(factory.resolveProvider()).thenReturn(AiModelProperties.Provider.OPENROUTER);
        com.checkba.service.ai.PlatformAiChannel channel =
                mock(com.checkba.service.ai.PlatformAiChannel.class);
        when(channel.availableFor(1L)).thenReturn(false);

        AiChatController controller = new AiChatController(
                mock(com.checkba.service.ProjectAiMessageService.class),
                mock(com.checkba.service.ai.AiDocxExportService.class),
                factory,
                mock(com.checkba.service.ai.ConversationFileChangeService.class),
                mock(com.checkba.repository.TokenUsageRepository.class),
                mock(com.checkba.service.ai.AgentRunStateService.class),
                channel,
                properties);

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(1L);
            ResponseEntity<?> response = controller.getAiConfig("s");
            assertEquals(200, response.getStatusCode().value());
            return (Map<String, Object>) response.getBody();
        }
    }

    @Test
    @DisplayName("五项上限全部下发，且取的是后端配置的实际值")
    void everyLimitTheFrontendNeedsIsSentDown() {
        AiContextProperties properties = new AiContextProperties();
        properties.getFiles().setMaxFilesPerContext(7);
        properties.getFiles().setMaxCharsPerFile(1234);
        properties.getFiles().setMaxCharsActiveDocument(99999);
        properties.getVision().setMaxImagesPerTurn(3);
        properties.getVision().setMaxImageBytes(555L);

        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) config(properties).get("contextLimits");
        assertNotNull(limits, "不下发的话前端只能把上限写死，改了配置界面仍按旧值拦截");
        assertEquals(7, limits.get("maxFilesPerContext"));
        assertEquals(1234, limits.get("maxCharsPerFile"));
        assertEquals(99999, limits.get("maxCharsActiveDocument"));
        assertEquals(3, limits.get("maxImagesPerTurn"));
        assertEquals(555L, limits.get("maxImageBytes"));
    }

    @Test
    @DisplayName("明写「直送的图片也占文件配额」，前端才不会把两者分开算")
    void theQuotaConventionIsStated() {
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) config(new AiContextProperties()).get("contextLimits");
        assertEquals(Boolean.TRUE, limits.get("visionCountsTowardFileQuota"));
    }

    @Test
    @DisplayName("既有字段一个不少（响应形状只增不改）")
    void existingFieldsAreUntouched() {
        Map<String, Object> body = config(new AiContextProperties());
        assertEquals("OPENROUTER", body.get("activeProvider"));
        assertTrue(body.containsKey("platformAiAvailable"));
    }

    // ---------- E-15：本地 Ollama 档的 vision 位归一 ----------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> models(AiModelProperties.Provider provider) {
        NetworkRegionService regionService = mock(NetworkRegionService.class);
        when(regionService.effectiveRegion()).thenReturn(AllowedModels.Region.GLOBAL);
        when(regionService.mode()).thenReturn("auto");
        when(regionService.detectionBasis()).thenReturn("测试固定值");
        ChatModelFactory factory = mock(ChatModelFactory.class);
        when(factory.resolveProvider()).thenReturn(provider);
        when(factory.resolveDefaultModel()).thenReturn(AllowedModels.DEEPSEEK_V4_FLASH.getModelId());

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(1L);
            Map<String, Object> body = (Map<String, Object>) new AiModelCatalogController(regionService, factory)
                    .listModels("s").getBody();
            return (List<Map<String, Object>>) body.get("models");
        }
    }

    @Test
    @DisplayName("本地 Ollama 档下所有模型的 vision 位都归一为 false")
    void localOllamaNormalisesEveryVisionFlagToFalse() {
        assertTrue(models(AiModelProperties.Provider.OPENROUTER).stream()
                        .anyMatch(m -> Boolean.TRUE.equals(m.get("vision"))),
                "前提：云端档里本来有支持视觉的模型");
        assertTrue(models(AiModelProperties.Provider.OLLAMA).stream()
                        .allMatch(m -> Boolean.FALSE.equals(m.get("vision"))),
                "后端 effectiveModelSupportsVision 对 OLLAMA 恒 false；"
                        + "这里不归一，前端就会认为「能读图」而不做任何提示");
        assertFalse(models(AiModelProperties.Provider.OLLAMA).isEmpty());
    }
}
