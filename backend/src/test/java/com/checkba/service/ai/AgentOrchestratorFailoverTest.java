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
            List.of("deepseek/deepseek-v4-flash", "qwen/qwen3-235b-a22b-2507");

    @Test
    @DisplayName("按配置顺序取第一个可用候选")
    void picksFirstCandidate() {
        assertEquals("deepseek/deepseek-v4-flash",
                AgentOrchestrator.nextFailoverModel(CHAIN, "openai/gpt-4o", Set.of()));
    }

    @Test
    @DisplayName("跳过当前正在失败的模型——切回自己等于没切")
    void skipsCurrentModel() {
        assertEquals("qwen/qwen3-235b-a22b-2507",
                AgentOrchestrator.nextFailoverModel(CHAIN, "deepseek/deepseek-v4-flash", Set.of()));
    }

    @Test
    @DisplayName("跳过已经试过并失败的模型")
    void skipsAlreadyTried() {
        assertEquals("qwen/qwen3-235b-a22b-2507",
                AgentOrchestrator.nextFailoverModel(
                        CHAIN, "openai/gpt-4o", Set.of("deepseek/deepseek-v4-flash")));

        assertNull(AgentOrchestrator.nextFailoverModel(
                        CHAIN, "openai/gpt-4o",
                        Set.of("deepseek/deepseek-v4-flash", "qwen/qwen3-235b-a22b-2507")),
                "链路耗尽必须返回 null，交给终态处置");
    }

    @Test
    @DisplayName("非白名单候选一律跳过：工厂会把它静默回落成默认模型，切了等于没切")
    void skipsNonAllowlistedCandidates() {
        assertEquals("qwen/qwen3-235b-a22b-2507",
                AgentOrchestrator.nextFailoverModel(
                        List.of("vendor/not-in-allowlist", "qwen/qwen3-235b-a22b-2507"),
                        "openai/gpt-4o", Set.of()));
    }

    @Test
    @DisplayName("空配置/空白项/大小写差异都不会误选")
    void handlesEmptyAndMessyConfig() {
        assertNull(AgentOrchestrator.nextFailoverModel(null, "openai/gpt-4o", Set.of()));
        assertNull(AgentOrchestrator.nextFailoverModel(List.of(), "openai/gpt-4o", Set.of()));
        assertNull(AgentOrchestrator.nextFailoverModel(List.of("", "   "), "openai/gpt-4o", Set.of()));
        assertNull(AgentOrchestrator.nextFailoverModel(
                        List.of("DeepSeek/DeepSeek-V4-Flash"), "deepseek/deepseek-v4-flash", Set.of()),
                "大小写不同仍是同一个模型");
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
