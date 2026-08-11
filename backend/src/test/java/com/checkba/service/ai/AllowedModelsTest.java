package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型目录唯一事实来源的离线契约测试。
 *
 * <p>守两件事：① 分档计价的取档逻辑（算错就是记账错，多数模型的分档差价是 2 到 6 倍）；
 * ② 区域过滤的集合规模（境内清单里混进 INTERNATIONAL 模型，用户点了必然 403）。
 * 单价是否与 OpenRouter 一致由联网对拍测试 {@link AllowedModelsLiveContractTest} 守，
 * 这里刻意不写死具体价格——否则每次上游调价都要改两处，反而降低了对拍测试的可信度。
 */
@DisplayName("模型白名单与分档计价")
class AllowedModelsTest {

    // ==================== priceTierFor ====================

    @Test
    @DisplayName("单档模型：任何输入长度都落首档")
    void singleTierModelAlwaysReturnsFirstTier() {
        AllowedModels m = AllowedModels.DEEPSEEK_V4_FLASH;
        assertEquals(1, m.getPriceTiers().size(), "该模型应为单档，分档了就要改本测试的前提");

        AllowedModels.PriceTier first = m.getPriceTiers().get(0);
        assertSame(first, m.priceTierFor(0));
        assertSame(first, m.priceTierFor(1));
        assertSame(first, m.priceTierFor(999_999));
    }

    @Test
    @DisplayName("恰好等于档位下限时进新档（下限是闭区间）")
    void tierBoundaryIsInclusive() {
        // seed-2.0-lite：0 起首档，128000 起第二档
        AllowedModels m = AllowedModels.SEED_2_0_LITE;
        int boundary = m.getPriceTiers().get(1).minPromptTokens();

        assertEquals(m.getPriceTiers().get(0), m.priceTierFor(boundary - 1), "下限前一个 token 仍在首档");
        assertEquals(m.getPriceTiers().get(1), m.priceTierFor(boundary), "恰好等于下限就应进新档");
        assertEquals(m.getPriceTiers().get(1), m.priceTierFor(boundary + 1));
    }

    @Test
    @DisplayName("三档模型：跨两个下限时取最后一个满足的档")
    void multiTierPicksLastApplicable() {
        // qwen3.7-flash 是白名单里唯一的三档模型：0 / 32000 / 256000
        AllowedModels m = AllowedModels.QWEN_3_7_FLASH;
        assertEquals(3, m.getPriceTiers().size(), "该模型应为三档，档数变了要改本测试的前提");

        List<AllowedModels.PriceTier> tiers = m.getPriceTiers();
        assertEquals(tiers.get(0), m.priceTierFor(31_999));
        assertEquals(tiers.get(1), m.priceTierFor(32_000));
        assertEquals(tiers.get(1), m.priceTierFor(255_999));
        assertEquals(tiers.get(2), m.priceTierFor(256_000));
        assertEquals(tiers.get(2), m.priceTierFor(10_000_000), "超出上下文上限也不能越界，仍取末档");
    }

    @Test
    @DisplayName("0 与负数输入回落首档，不抛异常")
    void zeroAndNegativeFallBackToFirstTier() {
        // 负数不该出现（TokenUsage 缺字段时补 0），但记账路径抛异常会把整条流式对话带崩，
        // 所以取档必须是全域函数
        for (AllowedModels m : AllowedModels.values()) {
            AllowedModels.PriceTier first = m.getPriceTiers().get(0);
            assertEquals(first, m.priceTierFor(0), m.getModelId() + " 的 0 token 应落首档");
            assertEquals(first, m.priceTierFor(-1), m.getModelId() + " 的负数应落首档");
            assertEquals(first, m.priceTierFor(Integer.MIN_VALUE), m.getModelId() + " 的极小值应落首档");
        }
    }

    // ==================== 结构性前提 ====================

    @Test
    @DisplayName("priceTiers 首档下限为 0 且整体升序——priceTierFor 的正确性依赖这个前提")
    void priceTiersAreZeroBasedAndAscending() {
        for (AllowedModels m : AllowedModels.values()) {
            List<AllowedModels.PriceTier> tiers = m.getPriceTiers();
            assertFalse(tiers.isEmpty(), m.getModelId() + " 至少要有一档价格");
            assertEquals(0, tiers.get(0).minPromptTokens(),
                    m.getModelId() + " 的首档 minPromptTokens 必须为 0，否则短输入取不到档");
            for (int i = 1; i < tiers.size(); i++) {
                assertTrue(tiers.get(i).minPromptTokens() > tiers.get(i - 1).minPromptTokens(),
                        m.getModelId() + " 的价格档必须按 minPromptTokens 严格升序，第 " + i + " 档乱序");
            }
        }
    }

    // ==================== availableIn ====================

    @Test
    @DisplayName("境内只放行 GLOBAL 的 9 条，国际网络给全部 14 条")
    void availableInFiltersByRegion() {
        List<AllowedModels> domestic = AllowedModels.availableIn(AllowedModels.Region.GLOBAL);
        assertEquals(9, domestic.size(), "境内可用模型数变了：要么加了国内模型，要么误把国际模型标成 GLOBAL");
        assertTrue(domestic.stream().allMatch(m -> m.getRegion() == AllowedModels.Region.GLOBAL),
                "境内清单里出现 INTERNATIONAL 模型，用户点了必然 403 region");

        List<AllowedModels> international = AllowedModels.availableIn(AllowedModels.Region.INTERNATIONAL);
        assertEquals(14, international.size(), "国际网络应能用全部白名单模型");
        assertEquals(AllowedModels.values().length, international.size());
    }

    // ==================== fromId / isAllowed ====================

    @Test
    @DisplayName("fromId 大小写不敏感且容忍首尾空白")
    void fromIdIsCaseInsensitiveAndTrims() {
        assertSame(AllowedModels.DEEPSEEK_V4_FLASH, AllowedModels.fromId("deepseek/deepseek-v4-flash"));
        assertSame(AllowedModels.DEEPSEEK_V4_FLASH, AllowedModels.fromId("DeepSeek/DeepSeek-V4-Flash"));
        assertSame(AllowedModels.DEEPSEEK_V4_FLASH, AllowedModels.fromId("  deepseek/deepseek-v4-flash  "));
    }

    @Test
    @DisplayName("null、空串、纯空白、未知 id 一律返回 null")
    void fromIdRejectsBlankAndUnknown() {
        assertNull(AllowedModels.fromId(null));
        assertNull(AllowedModels.fromId(""));
        assertNull(AllowedModels.fromId("   "));
        assertNull(AllowedModels.fromId("openrouter/auto"), "动态路由别名 pricing 返 -1，静态价格表无法计价");
        // 已下线的旧 id 不能悄悄复活：yml / 前端 / 测试里残留会被工厂静默回落默认模型
        assertNull(AllowedModels.fromId("deepseek/deepseek-v3.2"));
        assertNull(AllowedModels.fromId("google/gemini-2.5-flash"));
        assertNull(AllowedModels.fromId("openai/gpt-4o-mini"));

        assertFalse(AllowedModels.isAllowed(null));
        assertFalse(AllowedModels.isAllowed("google/gemini-2.5-pro"));
        assertTrue(AllowedModels.isAllowed("anthropic/claude-sonnet-5"));
    }
}
