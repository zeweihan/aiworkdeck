package com.checkba.controller.ai;

import com.checkba.service.ai.AllowedModels;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.NetworkRegionService;
import com.checkba.controller.AuthController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/ai/models} 的响应契约。
 *
 * <p><b>为什么现在才有这个测试</b>：{@code ChatModelFactoryTest} 里有一段注释声称
 * 「替代护栏在模型目录端点的测试里：它断言端点下发的清单等于 AllowedModels.availableIn(当前区域)」——
 * 而那个测试**根本不存在**，这个端点的契约此前零护栏。别再相信那条注释。
 *
 * <p>这里守三件事：清单等于当前区域可用集合、每条都带 vision 能力位、默认模型由工厂解析。
 */
class AiModelCatalogControllerTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(AllowedModels.Region region) {
        NetworkRegionService regionService = mock(NetworkRegionService.class);
        when(regionService.effectiveRegion()).thenReturn(region);
        when(regionService.mode()).thenReturn("auto");
        when(regionService.detectionBasis()).thenReturn("测试固定值");

        ChatModelFactory factory = mock(ChatModelFactory.class);
        when(factory.resolveDefaultModel()).thenReturn(AllowedModels.DEEPSEEK_V4_FLASH.getModelId());

        // 鉴权是静态入口（AuthController.getUserIdFromSession），单测里桩掉它是本仓既有做法
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("session-ok")).thenReturn(1L);
            ResponseEntity<?> response = new AiModelCatalogController(regionService, factory)
                    .listModels("session-ok");
            assertEquals(200, response.getStatusCode().value());
            return (Map<String, Object>) response.getBody();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> models(AllowedModels.Region region) {
        return (List<Map<String, Object>>) body(region).get("models");
    }

    @Test
    @DisplayName("清单必须逐条等于 AllowedModels.availableIn(当前区域)——境内出现国际模型，用户点了必然 403")
    void catalogEqualsAvailableInCurrentRegion() {
        for (AllowedModels.Region region : AllowedModels.Region.values()) {
            List<String> expected = AllowedModels.availableIn(region).stream()
                    .map(AllowedModels::getModelId).toList();
            List<String> actual = models(region).stream()
                    .map(m -> (String) m.get("id")).toList();
            assertEquals(expected, actual, region + " 下发的清单与白名单不一致");
        }
    }

    @Test
    @DisplayName("每条都必须带 vision 能力位——前端靠它在选模型那一刻提示，缺了只能自建模型表")
    void everyModelCarriesVisionFlag() {
        for (Map<String, Object> dto : models(AllowedModels.Region.INTERNATIONAL)) {
            Object vision = dto.get("vision");
            assertNotNull(vision, dto.get("id") + " 缺 vision 字段");
            assertTrue(vision instanceof Boolean, dto.get("id") + " 的 vision 必须是布尔");
            assertEquals(AllowedModels.fromId((String) dto.get("id")).isVision(), vision,
                    dto.get("id") + " 下发的 vision 与白名单不一致");
        }
    }

    @Test
    @DisplayName("vision 位真的有区分度：既有 true 也有 false，不是一个恒为真的摆设字段")
    void visionFlagIsDiscriminating() {
        List<Map<String, Object>> all = models(AllowedModels.Region.INTERNATIONAL);
        assertTrue(all.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("vision"))),
                "一个支持视觉的模型都没有，图片直送这条路等于没做");
        assertTrue(all.stream().anyMatch(m -> Boolean.FALSE.equals(m.get("vision"))),
                "全部为 true 的话这个字段就没有消费者了——本仓的口径是没有消费者的字段不加");
    }

    @Test
    @DisplayName("defaultModel 必须由工厂解析下发，前端不许自己取清单第一条")
    void defaultModelComesFromFactory() {
        assertEquals(AllowedModels.DEEPSEEK_V4_FLASH.getModelId(),
                body(AllowedModels.Region.GLOBAL).get("defaultModel"));
    }

    @Test
    @DisplayName("未登录返回 401，不是静默给一个空清单")
    void unauthenticatedIsRejected() {
        NetworkRegionService regionService = mock(NetworkRegionService.class);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);
            ResponseEntity<?> response = new AiModelCatalogController(regionService, factory)
                    .listModels(null);
            assertEquals(401, response.getStatusCode().value());
        }
    }
}
