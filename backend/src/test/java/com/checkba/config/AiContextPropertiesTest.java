package com.checkba.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AiContextProperties 测试：默认值与按模型覆盖 token 预算
 */
class AiContextPropertiesTest {

    @Test
    @DisplayName("默认值与原硬编码常量一致（行为保持）")
    void defaultsShouldMatchLegacyConstants() {
        AiContextProperties props = new AiContextProperties();

        assertEquals(100000, props.getMaxContextTokens());
        assertEquals(8000, props.getSystemPromptReserve());
        assertEquals(5000, props.getMemoryReserve());
        assertEquals(8000, props.getResponseReserve());
        assertEquals(2.0, props.getCharsPerToken());

        assertEquals(10, props.getCompression().getKeepRecentWithSummary());
        assertEquals(6, props.getCompression().getMinMessagesForSummarize());
        assertEquals(4, props.getCompression().getKeepRecentOnSummarize());
        assertEquals(2, props.getCompression().getKeepRecentAggressive());
        assertEquals(30, props.getCompression().getMaxHistoryMessages());
        assertEquals(2000, props.getCompression().getToolOutputMaxChars());
        assertEquals(1500, props.getCompression().getToolOutputTargetChars());

        assertEquals(10 * 1024 * 1024, props.getFiles().getMaxFileSizeBytes());
        assertEquals(10, props.getFiles().getMaxFilesPerContext());
        assertEquals(50000, props.getFiles().getMaxCharsPerFile());
        assertEquals(20000, props.getFiles().getFolderFileMaxChars());
        assertEquals(6000, props.getFiles().getChatContextMaxChars());
        assertEquals(50000, props.getFiles().getChatFolderContextMaxChars());
        assertEquals(1500, props.getFiles().getChatSelectionMaxChars());

        assertTrue(props.getOcrExtensions().containsAll(
                java.util.List.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "pdf")));
    }

    @Test
    @DisplayName("无覆盖配置时返回默认 token 预算")
    void shouldReturnDefaultBudgetWithoutOverrides() {
        AiContextProperties props = new AiContextProperties();
        assertEquals(100000, props.maxContextTokensFor("google/gemini-2.0-flash-exp:free"));
        assertEquals(100000, props.maxContextTokensFor(null));
        assertEquals(100000, props.maxContextTokensFor(""));
    }

    @Test
    @DisplayName("按模型精确匹配覆盖 token 预算")
    void shouldResolveExactModelOverride() {
        AiContextProperties props = new AiContextProperties();
        props.setModelTokenBudgets(Map.of("qwen3-vl:8b", 30000));

        assertEquals(30000, props.maxContextTokensFor("qwen3-vl:8b"));
        assertEquals(30000, props.maxContextTokensFor("QWEN3-VL:8B"));
        assertEquals(100000, props.maxContextTokensFor("other-model"));
    }

    @Test
    @DisplayName("按模型子串匹配覆盖 token 预算")
    void shouldResolveSubstringModelOverride() {
        AiContextProperties props = new AiContextProperties();
        props.setModelTokenBudgets(Map.of("gemini", 500000));

        assertEquals(500000, props.maxContextTokensFor("google/gemini-2.0-flash-exp:free"));
        assertEquals(100000, props.maxContextTokensFor("qwen3-vl:8b"));
    }
}
