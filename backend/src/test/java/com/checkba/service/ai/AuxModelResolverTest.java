package com.checkba.service.ai;

import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 辅助模型 / 子 Agent 模型的解析链：DB 优先于 yml，子 Agent 空配置回退辅助模型（省钱语义）。
 */
@DisplayName("辅助模型解析")
class AuxModelResolverTest {

    private static final String YML_AUX = "qwen/qwen3.7-flash";

    private SystemSettingService settings;

    @BeforeEach
    void setUp() {
        settings = mock(SystemSettingService.class);
        when(settings.get(any(), any())).thenReturn(null);
    }

    private AuxModelResolver resolver() {
        return new AuxModelResolver(settings, YML_AUX);
    }

    @Test
    @DisplayName("辅助模型：未配置时用 yml 默认值")
    void auxFallsBackToYml() {
        assertEquals(YML_AUX, resolver().auxModelId());
    }

    @Test
    @DisplayName("辅助模型：DB 的 ai.auxModel 优先，且空白视为未配置")
    void auxPrefersDbAndTreatsBlankAsUnset() {
        when(settings.get(eq(AuxModelResolver.SETTING_AUX_MODEL), any()))
                .thenReturn("  deepseek/deepseek-v4-flash  ");
        assertEquals("deepseek/deepseek-v4-flash", resolver().auxModelId());

        when(settings.get(eq(AuxModelResolver.SETTING_AUX_MODEL), any())).thenReturn("   ");
        assertEquals(YML_AUX, resolver().auxModelId(), "向导未填的字段会以空串落库，必须当未配置");
    }

    @Test
    @DisplayName("子 Agent 模型：DB → yml → 辅助模型；留空不再继承主会话（默认走便宜模型）")
    void subAgentChain() {
        // 三档都没配 → 辅助模型
        assertEquals(YML_AUX, resolver().subAgentModelId(""));
        assertEquals(YML_AUX, resolver().subAgentModelId(null));

        // yml 配了就用 yml
        assertEquals("z-ai/glm-5.2", resolver().subAgentModelId("z-ai/glm-5.2"));

        // DB 覆盖 yml
        when(settings.get(eq(AuxModelResolver.SETTING_SUBAGENT_MODEL), any()))
                .thenReturn("minimax/minimax-m3");
        assertEquals("minimax/minimax-m3", resolver().subAgentModelId("z-ai/glm-5.2"));
    }

    @Test
    @DisplayName("辅助模型兜底：yml 也被清空时落到白名单里最便宜那条，绝不返回空")
    void neverReturnsBlank() {
        assertEquals(AllowedModels.QWEN_3_7_FLASH.getModelId(),
                new AuxModelResolver(settings, "  ").auxModelId());
    }

    @Test
    @DisplayName("firstNonBlank：全空白返回 null")
    void firstNonBlankSemantics() {
        assertEquals("a", AuxModelResolver.firstNonBlank(null, " ", " a "));
        assertNull(AuxModelResolver.firstNonBlank(null, "", "   "));
        assertNull(AuxModelResolver.firstNonBlank());
    }
}
