// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
    @DisplayName("默认值（token 预算两项已按 dev-board#729 ④ 改过，其余仍是原硬编码常量）")
    void defaultsShouldMatchLegacyConstants() {
        AiContextProperties props = new AiContextProperties();

        assertEquals(200000, props.getMaxContextTokens());
        // dev-board#729 ④：8000 与真实固定前缀差了近一个数量级（实测 promptTokens 约 5 万，
        // 其中约 2/3 是工具 schema），「历史可用预算」被系统性高估 7 倍有余
        assertEquals(60000, props.getSystemPromptReserve());
        assertEquals(5000, props.getMemoryReserve());
        assertEquals(8000, props.getResponseReserve());
        assertEquals(2.0, props.getCharsPerToken());
        assertEquals(0.85, props.getModelBudgetHeadroom());

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
    @DisplayName("白名单外的模型标识仍吃兜底预算")
    void shouldReturnDefaultBudgetForUnknownModels() {
        AiContextProperties props = new AiContextProperties();
        assertEquals(200000, props.maxContextTokensFor("google/gemini-2.0-flash-exp:free"));
        assertEquals(200000, props.maxContextTokensFor("llama3:latest"));
        assertEquals(200000, props.maxContextTokensFor(null));
        assertEquals(200000, props.maxContextTokensFor(""));
    }

    @Test
    @DisplayName("白名单内的模型按标称上下文 × 余量派生（dev-board#729 ④）")
    void shouldDeriveBudgetFromAllowedModelContextLength() {
        AiContextProperties props = new AiContextProperties();

        // 生产默认模型：1,048,576 × 0.85，而不是那个 10 万的常数
        assertEquals((int) Math.floor(1_048_576 * 0.85),
                props.maxContextTokensFor("deepseek/deepseek-v4-flash"));
        // 白名单里上下文最小的一条也远大于旧常数——这条改动只会放宽、不会收紧
        assertEquals((int) Math.floor(200_000 * 0.85),
                props.maxContextTokensFor("anthropic/claude-haiku-4.5"));

        for (com.checkba.service.ai.AllowedModels m : com.checkba.service.ai.AllowedModels.values()) {
            assertTrue(props.maxContextTokensFor(m.getModelId()) >= 100000,
                    m.getModelId() + " 的派生预算不得低于旧的兜底值（否则会比改动前更早触发压缩）");
        }
    }

    @Test
    @DisplayName("显式 model-token-budgets 永远优先于派生")
    void explicitOverrideBeatsDerivation() {
        AiContextProperties props = new AiContextProperties();
        props.setModelTokenBudgets(Map.of("deepseek/deepseek-v4-flash", 30000));
        assertEquals(30000, props.maxContextTokensFor("deepseek/deepseek-v4-flash"));
        // 未被覆盖的仍走派生
        assertEquals((int) Math.floor(1_000_000 * 0.85),
                props.maxContextTokensFor("qwen/qwen3.7-flash"));
    }

    @Test
    @DisplayName("deepseek-v4-flash 下 79k token 的历史不再触发压缩（旧配置会触发）")
    void longHistoryNoLongerTriggersSynchronousSummarisationOnMillionTokenModels() {
        AiContextProperties props = new AiContextProperties();
        int available = props.maxContextTokensFor("deepseek/deepseek-v4-flash")
                - props.getSystemPromptReserve()
                - props.getMemoryReserve()
                - props.getResponseReserve();
        assertTrue(available > 79_000,
                "79k 历史必须装得下，否则首 token 之前会插一次同步 LLM 摘要调用；实际预算 " + available);

        // 旧配置（总预算 10 万 + 预留 8000/5000/8000）下 79k 恰好卡在边界上：
        // 这正是探查里「上下文预算基数错误提前触发同步摘要」的算术依据
        assertTrue(100000 - 8000 - 5000 - 8000 <= 79_000,
                "旧口径下 79k 历史会触发压缩，这条断言钉住改动前后的差别");
    }

    @Test
    @DisplayName("这次改动只放宽不收紧：任何模型（含解析不出的）的历史可用预算都不低于旧口径的 79000")
    void noModelEverGetsATighterHistoryBudgetThanBefore() {
        AiContextProperties props = new AiContextProperties();
        int fixedReserves = props.getSystemPromptReserve() + props.getMemoryReserve()
                + props.getResponseReserve();

        java.util.List<String> keys = new java.util.ArrayList<>(
                java.util.List.of("llama3:latest", "some/unknown-model", ""));
        for (com.checkba.service.ai.AllowedModels m : com.checkba.service.ai.AllowedModels.values()) {
            keys.add(m.getModelId());
        }
        keys.add(null);

        for (String key : keys) {
            int available = props.maxContextTokensFor(key) - fixedReserves;
            assertTrue(available >= 79_000,
                    "模型 " + key + " 的历史可用预算 " + available
                            + " 低于旧口径的 79000——system-prompt-reserve 抬到 60000 时"
                            + "兜底总预算必须跟着抬，否则会比改动前更早触发同步摘要");
        }
    }

    @Test
    @DisplayName("按模型精确匹配覆盖 token 预算")
    void shouldResolveExactModelOverride() {
        AiContextProperties props = new AiContextProperties();
        props.setModelTokenBudgets(Map.of("qwen3-vl:8b", 30000));

        assertEquals(30000, props.maxContextTokensFor("qwen3-vl:8b"));
        assertEquals(30000, props.maxContextTokensFor("QWEN3-VL:8B"));
        assertEquals(200000, props.maxContextTokensFor("other-model"));
    }

    @Test
    @DisplayName("按模型子串匹配覆盖 token 预算")
    void shouldResolveSubstringModelOverride() {
        AiContextProperties props = new AiContextProperties();
        props.setModelTokenBudgets(Map.of("gemini", 500000));

        assertEquals(500000, props.maxContextTokensFor("google/gemini-2.0-flash-exp:free"));
        assertEquals(200000, props.maxContextTokensFor("qwen3-vl:8b"));
    }
}
