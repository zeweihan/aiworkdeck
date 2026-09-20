// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.eval;

import com.checkba.service.ai.ClientCapabilityService;
import com.checkba.service.ai.PluginService;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按活跃文档类型裁工具到底省下多少（dev-board#729 ①）。
 *
 * <p>工具规格是<b>每一轮</b>都要重发一遍的：一条消息跑三五个 LLM 往返，这份 schema 就要
 * 付三五遍钱、等三五遍首字节。本机实测 202 个工具 59045 prompt token / 首轮 26.4 秒，
 * 裁到 16 个是 18854 token / 6.1 秒——schema 占了 prompt 的约 2/3。
 *
 * <p>这条测试用真实注册表量一次，并把「确实省下了可观的一块」钉成断言：
 * 裁剪若被谁改回全集，这里会红。断言用的是宽松下限（≥15%），不锁具体数字——
 * 工具数量本来就会随功能增减浮动，锁死会变成每加一个工具就要改一次的噪音。
 */
class ToolSchemaBudgetTest {

    private static RecordingToolRegistry registry() {
        PluginService pluginService = new PluginService();
        RecordingToolRegistry registry =
                new RecordingToolRegistry(RealToolBeans.instantiateAll(), pluginService);
        registry.init();
        return registry;
    }

    /** 粗估一份工具规格的体量：名字 + 描述 + 每个参数的名字与说明。与真实 JSON schema 同量级。 */
    private static int weight(List<ToolSpecification> specs) {
        int n = 0;
        for (ToolSpecification s : specs) {
            n += s.name() == null ? 0 : s.name().length();
            n += s.description() == null ? 0 : s.description().length();
            if (s.parameters() != null && s.parameters().properties() != null) {
                n += String.valueOf(s.parameters().properties()).length();
            }
        }
        return n;
    }

    @Test
    @DisplayName("docx 会话的工具 schema 比全集小一大截（slide_* 全隐藏、sheet_* 只留新建）")
    void writerSessionShipsAMateriallySmallerSchema() {
        RecordingToolRegistry registry = registry();
        List<ToolSpecification> all = registry.getAllSpecifications("conv", null);
        List<ToolSpecification> writer =
                registry.getAllSpecifications("conv", ClientCapabilityService.DOC_KIND_WRITER);

        int allWeight = weight(all);
        int writerWeight = weight(writer);
        double saved = 1.0 - (double) writerWeight / allWeight;

        System.out.printf("[dev-board#729 ①] 全集 %d 个工具 / %d 字符；docx 裁剪后 %d 个 / %d 字符；省下 %.1f%%%n",
                all.size(), allWeight, writer.size(), writerWeight, saved * 100);

        assertTrue(writer.size() < all.size(), "裁剪后工具数必须变少");
        assertTrue(saved > 0.15,
                "docx 会话省下的 schema 体量不足 15%（实际 " + String.format("%.1f%%", saved * 100)
                        + "）——裁剪多半被改回全集了");
        assertTrue(writer.stream().noneMatch(s -> s.name().startsWith("slide_")),
                "docx 会话不该下发任何 slide_* 工具");
    }

    @Test
    @DisplayName("xlsx / pptx 会话同样省下一大截")
    void calcAndImpressSessionsAlsoShrink() {
        RecordingToolRegistry registry = registry();
        int allWeight = weight(registry.getAllSpecifications("conv", null));

        for (String kind : new String[]{ClientCapabilityService.DOC_KIND_SHEET,
                ClientCapabilityService.DOC_KIND_SLIDE}) {
            List<ToolSpecification> trimmed = registry.getAllSpecifications("conv", kind);
            double saved = 1.0 - (double) weight(trimmed) / allWeight;
            System.out.printf("[dev-board#729 ①] %s 裁剪后 %d 个工具，省下 %.1f%%%n",
                    kind, trimmed.size(), saved * 100);
            assertTrue(saved > 0.15, kind + " 省下的 schema 体量不足 15%");
        }
    }
}
