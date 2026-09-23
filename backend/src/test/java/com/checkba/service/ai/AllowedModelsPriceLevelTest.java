// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型选择器的贵贱档位（dev-board#853）。
 *
 * <p>与 {@link AllowedModelsTest} 同一口径：<b>不写死某个模型落在哪一档</b>——上游调价后
 * 档位本来就该跟着变。守的是档位作为一种「视觉层级」还有没有意义：
 * 每个区域四档都有模型、档位随综合单价单调、边界值落点确定。
 */
@DisplayName("模型贵贱档位")
class AllowedModelsPriceLevelTest {

    private static Set<Integer> levelsIn(AllowedModels.Region region) {
        return AllowedModels.availableIn(region).stream()
                .map(AllowedModels::priceLevel)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("境内（只剩 GLOBAL）与国际两种清单里，四档都有模型——少一档，档位就失去区分度")
    void everyLevelIsPopulatedInBothRegions() {
        Set<Integer> all = IntStream.rangeClosed(1, AllowedModels.PRICE_LEVEL_COUNT).boxed().collect(Collectors.toSet());
        assertEquals(all, levelsIn(AllowedModels.Region.GLOBAL),
                "境内清单缺档：调整 PRICE_LEVEL_UPPER_BOUNDS，别让境内用户只看到三档");
        assertEquals(all, levelsIn(AllowedModels.Region.INTERNATIONAL));
    }

    @Test
    @DisplayName("档位随综合单价单调不减：贵的模型不许排到便宜模型的下一档")
    void levelIsMonotonicInBlendedPrice() {
        List<AllowedModels> sorted = List.of(AllowedModels.values()).stream()
                .sorted(Comparator.comparingDouble(AllowedModels::blendedPricePerM))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            assertTrue(sorted.get(i).priceLevel() >= sorted.get(i - 1).priceLevel(),
                    sorted.get(i).getModelId() + " 比 " + sorted.get(i - 1).getModelId() + " 贵却档位更低");
        }
        assertEquals(1, sorted.get(0).priceLevel(), "最便宜的必须是最低档");
        assertEquals(AllowedModels.PRICE_LEVEL_COUNT, sorted.get(sorted.size() - 1).priceLevel(), "最贵的必须是最高档");
    }

    @Test
    @DisplayName("综合单价 = 0.8×输入 + 0.2×输出（首档）——agent 场景输入 token 占绝大多数")
    void blendedPriceWeightsInputHeavily() {
        AllowedModels m = AllowedModels.KIMI_K3;
        AllowedModels.PriceTier first = m.getPriceTiers().get(0);
        assertEquals(0.8 * first.inputPricePerM() + 0.2 * first.outputPricePerM(), m.blendedPricePerM(), 1e-12);

        // 分档模型只看首档：长上下文涨价靠 tiered 标签单独提示，不抬档位
        AllowedModels tiered = AllowedModels.QWEN_3_7_FLASH;
        AllowedModels.PriceTier t0 = tiered.getPriceTiers().get(0);
        assertEquals(0.8 * t0.inputPricePerM() + 0.2 * t0.outputPricePerM(), tiered.blendedPricePerM(), 1e-12);
    }

    @Test
    @DisplayName("边界值：上界不含，恰好等于阈值进下一档；异常值不抛异常")
    void boundaries() {
        assertEquals(1, AllowedModels.priceLevelOf(0));
        assertEquals(1, AllowedModels.priceLevelOf(0.2999));
        assertEquals(2, AllowedModels.priceLevelOf(0.3));
        assertEquals(2, AllowedModels.priceLevelOf(0.9999));
        assertEquals(3, AllowedModels.priceLevelOf(1.0));
        assertEquals(3, AllowedModels.priceLevelOf(2.9999));
        assertEquals(4, AllowedModels.priceLevelOf(3.0));
        assertEquals(4, AllowedModels.priceLevelOf(1000));
        assertEquals(1, AllowedModels.priceLevelOf(-1));
        assertEquals(1, AllowedModels.priceLevelOf(Double.NaN));
    }
}
