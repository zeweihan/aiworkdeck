// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.eval;

import com.checkba.service.ai.ClientCapabilityService;
import com.checkba.service.ai.PluginService;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * 真正上线路的字节数：与 {@code OpenRouterStreamingChatModel} 同一条编组
     *（{@code InternalOpenAiHelper.toTools} + openai4j 的 {@code Json}），空白按发出去的形态剔除。
     *
     * <p>和下面那个 {@link #weight} 的分工要说清楚，不然两个数字会打架：weight 量的是
     * {@code properties()} 的 Java toString，里头有将近一半是
     * {@code JsonStringSchema {description = ...}} 这种<b>不会上线</b>的样板，
     * 所以它只能用来做同口径的前后对比（历史断言都挂在它上面，不动）；
     * 真要估 token、估钱，看这一个。
     */
    static int wireBytes(List<ToolSpecification> specs) {
        if (specs.isEmpty()) {
            return 0;
        }
        String json = dev.ai4j.openai4j.Json.toJson(
                dev.langchain4j.model.openai.InternalOpenAiHelper.toTools(specs, false));
        return json.replaceAll("[ \\t\\n\\r]", "").length();
    }

    /** 粗估一份工具规格的体量：名字 + 描述 + 每个参数的名字与说明。与真实 JSON schema 同量级。 */
    static int weight(List<ToolSpecification> specs) {
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
        System.out.printf("[dev-board#810] 上线路字节：全集 %d；docx %d（这个才是真正付 token 的那份）%n",
                wireBytes(all), wireBytes(writer));

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
            System.out.printf("[dev-board#729 ①] %s 裁剪后 %d 个工具 / %d 字符，省下 %.1f%%%n",
                    kind, trimmed.size(), weight(trimmed), saved * 100);
            assertTrue(saved > 0.15, kind + " 省下的 schema 体量不足 15%");
        }
    }

    /**
     * 任务窗格与纯对话会话的体量（dev-board#799）。
     *
     * <p>前一条量的是「同一个 LOWA 会话按活跃文档类型裁多少」，这一条量的是
     * 「换一类客户端之后还剩多少」——{@code @ToolMeta.requiresHost} 的收益全在这一档：
     * 改之前 pptx_* / pdf_* / litigation_* / text_* 既不是 doc_/sheet_/slide_ 也不是 office_，
     * 在 Office 与 none 会话里恒可见，而它们的收尾都要桌面前端配合，在那里是纯空转
     * （pptx_generate 还会回一句「已唤起 PPT 生成配置界面…等待用户操作」，模型就此停住）。
     */
    @Test
    @DisplayName("Office / none 会话：桌面专属工具按声明退出，规格体量随之下降")
    void officeAndHeadlessSessionsDropDesktopOnlyTools() {
        RecordingToolRegistry registry = registry();
        registry.capabilities().record("conv-word", "office");
        registry.capabilities().record("conv-excel", "office", "excel");
        registry.capabilities().record("conv-ppt", "office", "powerpoint");
        registry.capabilities().record("conv-none", "none");

        // 改动前在这些会话里同样下发的「桌面专属 + 永久停用」工具。它们一个前缀都不带，
        // 所以旧规则下每种会话都放行；这里按名字取回规格，算出改动前的体量做对照。
        List<String> hiddenByDeclaration = List.of(
                "pptx_open_file", "pptx_generate", "pptx_apply_format",
                "litigation_render", "litigation_timeline_render",
                "pdf_to_word", "pdf_highlight", "pdf_annotate", "pdf_redact", "pdf_replace_text",
                "text_write_file", "text_find_replace",
                "delete_file");
        List<ToolSpecification> hiddenSpecs = hiddenByDeclaration.stream()
                .map(n -> registry.resolve(n).orElseThrow(
                        () -> new AssertionError("登记必须保留（只裁 spec、不裁 resolve）：" + n)).spec())
                .toList();

        for (String conv : new String[]{"conv-word", "conv-excel", "conv-ppt", "conv-none"}) {
            List<ToolSpecification> specs = registry.getAllSpecifications(conv, null);
            System.out.printf("[dev-board#799] %s 会话：改后 %d 个工具 / %d 字符；改前 %d 个 / %d 字符%n",
                    conv, specs.size(), weight(specs),
                    specs.size() + hiddenSpecs.size(), weight(specs) + weight(hiddenSpecs));
            List<String> names = specs.stream().map(ToolSpecification::name).toList();
            // 逐名断言在 ToolDeclarationContractTest（那份清单是唯一事实来源），
            // 这里只挑三个最能说明问题的：发 UI 指令的、发配置界面的、改纯文本的。
            assertFalse(names.contains("pptx_generate"), conv + "：" + names);
            assertFalse(names.contains("litigation_render"), conv + "：" + names);
            assertFalse(names.contains("text_write_file"), conv + "：" + names);
            // 只读面必须留着：收窄的是"改"，不是"读"
            assertTrue(names.contains("pdf_inspect"), conv + " 的 PDF 读取面不该跟着消失：" + names);
        }
    }
}
