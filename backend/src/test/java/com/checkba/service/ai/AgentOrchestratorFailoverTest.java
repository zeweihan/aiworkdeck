package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障转移的候选选择 + 打转干预/熔断的两段文案。
 *
 * <p>计费红线在 ChatModelFactory 一侧守（见 ChatModelFactoryTest）：这里只换 modelId，
 * 通道由 resolveProvider() 决定，与 modelId 无关，因此切模型不可能把平台通道切成 BYOK。
 */
class AgentOrchestratorFailoverTest {

    private static final List<String> CHAIN =
            List.of("deepseek/deepseek-v4-flash", "qwen/qwen3.7-flash");

    @Test
    @DisplayName("按配置顺序取第一个可用候选")
    void picksFirstCandidate() {
        assertEquals("deepseek/deepseek-v4-flash",
                AgentOrchestrator.nextFailoverModel(CHAIN, "anthropic/claude-sonnet-5", Set.of()));
    }

    @Test
    @DisplayName("跳过当前正在失败的模型——切回自己等于没切")
    void skipsCurrentModel() {
        assertEquals("qwen/qwen3.7-flash",
                AgentOrchestrator.nextFailoverModel(CHAIN, "deepseek/deepseek-v4-flash", Set.of()));
    }

    @Test
    @DisplayName("跳过已经试过并失败的模型")
    void skipsAlreadyTried() {
        assertEquals("qwen/qwen3.7-flash",
                AgentOrchestrator.nextFailoverModel(
                        CHAIN, "anthropic/claude-sonnet-5", Set.of("deepseek/deepseek-v4-flash")));

        assertNull(AgentOrchestrator.nextFailoverModel(
                        CHAIN, "anthropic/claude-sonnet-5",
                        Set.of("deepseek/deepseek-v4-flash", "qwen/qwen3.7-flash")),
                "链路耗尽必须返回 null，交给终态处置");
    }

    @Test
    @DisplayName("非白名单候选一律跳过：工厂会把它静默回落成默认模型，切了等于没切")
    void skipsNonAllowlistedCandidates() {
        assertEquals("qwen/qwen3.7-flash",
                AgentOrchestrator.nextFailoverModel(
                        List.of("vendor/not-in-allowlist", "qwen/qwen3.7-flash"),
                        "anthropic/claude-sonnet-5", Set.of()));
    }

    @Test
    @DisplayName("空配置/空白项/大小写差异都不会误选")
    void handlesEmptyAndMessyConfig() {
        assertNull(AgentOrchestrator.nextFailoverModel(null, "anthropic/claude-sonnet-5", Set.of()));
        assertNull(AgentOrchestrator.nextFailoverModel(List.of(), "anthropic/claude-sonnet-5", Set.of()));
        assertNull(AgentOrchestrator.nextFailoverModel(List.of("", "   "), "anthropic/claude-sonnet-5", Set.of()));
        assertNull(AgentOrchestrator.nextFailoverModel(
                        List.of("DeepSeek/DeepSeek-V4-Flash"), "deepseek/deepseek-v4-flash", Set.of()),
                "大小写不同仍是同一个模型");
    }

    @Test
    @DisplayName("地域拒绝：候选收窄成区域无关模型——境内切到另一个国际档只会再撞一次 403")
    void regionAgnosticFilterSkipsInternationalCandidates() {
        List<String> mixedChain = List.of(
                AllowedModels.GEMINI_3_6_FLASH.getModelId(),   // INTERNATIONAL：境内同样被拒
                AllowedModels.DEEPSEEK_V4_FLASH.getModelId()); // GLOBAL：真正能救回本轮的候选

        // 不收窄时按顺序取第一个（旧行为，非地域类错误仍走这条）
        assertEquals(AllowedModels.GEMINI_3_6_FLASH.getModelId(),
                AgentOrchestrator.nextFailoverModel(mixedChain, "anthropic/claude-sonnet-5", Set.of(), false));

        // 收窄后跳过所有 INTERNATIONAL 候选
        assertEquals(AllowedModels.DEEPSEEK_V4_FLASH.getModelId(),
                AgentOrchestrator.nextFailoverModel(mixedChain, "anthropic/claude-sonnet-5", Set.of(), true));
    }

    @Test
    @DisplayName("带图的一轮：候选收窄成支持视觉的模型——切给读不了图的模型是一个必然的 400")
    void visionFilterSkipsTextOnlyCandidates() {
        // 生产默认链就是这个形状：第一条不支持视觉、第二条支持
        List<String> defaultChain = List.of(
                AllowedModels.DEEPSEEK_V4_FLASH.getModelId(), // 纯文本
                AllowedModels.QWEN_3_7_FLASH.getModelId());   // 支持视觉

        // 不收窄时按顺序取第一个（不带图的轮次仍走这条，行为不变）
        assertEquals(AllowedModels.DEEPSEEK_V4_FLASH.getModelId(),
                AgentOrchestrator.nextFailoverModel(defaultChain, "z-ai/glm-5.2", Set.of(), false, false));

        // 栈里有图时跳过纯文本候选
        assertEquals(AllowedModels.QWEN_3_7_FLASH.getModelId(),
                AgentOrchestrator.nextFailoverModel(defaultChain, "z-ai/glm-5.2", Set.of(), false, true));
    }

    @Test
    @DisplayName("带图的一轮：链里没有视觉候选时返回 null——宁可终态，也不要把图丢给瞎子模型")
    void visionFilterMayExhaustChain() {
        assertNull(AgentOrchestrator.nextFailoverModel(
                List.of(AllowedModels.DEEPSEEK_V4_FLASH.getModelId(), AllowedModels.GLM_5_2.getModelId()),
                "moonshotai/kimi-k3", Set.of(), false, true));
    }

    @Test
    @DisplayName("四参重载不做视觉收窄：既有的地域收窄调用点行为一字不变")
    void fourArgOverloadDoesNotFilterByVision() {
        assertEquals(AllowedModels.DEEPSEEK_V4_FLASH.getModelId(),
                AgentOrchestrator.nextFailoverModel(
                        List.of(AllowedModels.DEEPSEEK_V4_FLASH.getModelId()),
                        "z-ai/glm-5.2", Set.of(), true));
    }

    @Test
    @DisplayName("地域拒绝：链里全是国际档时返回 null，交给终态处置而不是白花几次请求")
    void regionAgnosticFilterMayExhaustChain() {
        assertNull(AgentOrchestrator.nextFailoverModel(
                List.of(AllowedModels.GEMINI_3_6_FLASH.getModelId(), AllowedModels.GROK_4_5.getModelId()),
                "anthropic/claude-sonnet-5", Set.of(), true));
    }

    @Test
    @DisplayName("三参重载保持旧行为：不做区域收窄")
    void threeArgOverloadKeepsLegacyBehaviour() {
        List<String> mixedChain = List.of(AllowedModels.GEMINI_3_6_FLASH.getModelId(),
                AllowedModels.DEEPSEEK_V4_FLASH.getModelId());
        assertEquals(AllowedModels.GEMINI_3_6_FLASH.getModelId(),
                AgentOrchestrator.nextFailoverModel(mixedChain, "anthropic/claude-sonnet-5", Set.of()));
    }

    @Test
    @DisplayName("打转两段式文案：首次提醒不带 Error 前缀，熔断带（前端与 success() 都按它判定失败）")
    void stuckMessagesAreDistinct() {
        String nudge = AgentOrchestrator.stuckInterventionMessage("连续 3 次以完全相同的参数调用 doc_read");
        assertTrue(nudge.startsWith("[系统提醒]"), "首次干预是末位系统提醒，不是工具失败");
        assertTrue(nudge.contains("换一种思路"));

        String halt = AgentOrchestrator.stuckCircuitBreakFeedback("连续 3 次以完全相同的参数调用 doc_read");
        assertTrue(halt.startsWith("Error:"), "熔断反馈必须带 Error 前缀");
        assertTrue(halt.contains("已被拦截"));

        // 模式描述缺失时也要能给出可读文案，不能出现 null
        assertTrue(AgentOrchestrator.stuckInterventionMessage(null).contains("重复相同的操作序列"));
        assertTrue(AgentOrchestrator.stuckCircuitBreakFeedback(null).contains("重复相同的操作序列"));
    }
}
