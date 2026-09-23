// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.eval;

import com.checkba.service.ai.ClientCapabilityService;
import com.checkba.service.ai.PluginService;
import com.checkba.service.ai.ToolDisclosurePolicy;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具渐进披露的契约（dev-board#810 B 档）。
 *
 * <p>这套机制唯一真正危险的失败形态是<b>静默的能力丢失</b>：某个工具既不在核心集、
 * 又没落进任何类目，于是 {@code list_tools} 列不出来、模型永远找不到它，
 * 表现只是「AI 说这个做不了」——没有任何返回值会戳穿。下面前三条守的就是这个。
 */
class ToolDisclosurePolicyTest {

    private static final ToolDisclosurePolicy POLICY = new ToolDisclosurePolicy(true);

    private static RecordingToolRegistry registry() {
        RecordingToolRegistry registry =
                new RecordingToolRegistry(RealToolBeans.instantiateAll(), new PluginService());
        registry.init();
        return registry;
    }

    @Test
    @DisplayName("目录里没有黑洞：每个下发过的工具要么在核心集、要么能被某个类目展开")
    void everyToolIsReachableEitherAsCoreOrThroughACategory() {
        RecordingToolRegistry registry = registry();
        Set<String> categories = POLICY.categoryNames();
        for (ToolSpecification spec : registry.getAllSpecifications("conv", null)) {
            String category = POLICY.categoryOf(spec.name());
            assertTrue("core".equals(category) || categories.contains(category),
                    spec.name() + " 落进了一个 list_tools 列不出来的类目 '" + category
                            + "'——模型将永远找不到它，而这种失败是静默的");
        }
    }

    @Test
    @DisplayName("misc 是兜底不是垃圾桶：谁落进去要看得见")
    void theFallbackBucketStaysSmallAndNamed() {
        RecordingToolRegistry registry = registry();
        List<String> misc = registry.getAllSpecifications("conv", null).stream()
                .map(ToolSpecification::name)
                .filter(name -> ToolDisclosurePolicy.FALLBACK_CATEGORY.equals(POLICY.categoryOf(name)))
                .sorted()
                .toList();
        System.out.printf("[dev-board#810] 落进 %s 的工具（%d 个）：%s%n",
                ToolDisclosurePolicy.FALLBACK_CATEGORY, misc.size(), misc);
        // 兜底桶本身是设计的一部分（新增工具不改本类也能被 list_tools 列出来），
        // 但它涨起来就说明类目表落后于工具面了——那时该加类目，不是放宽这条。
        assertTrue(misc.size() <= 12,
                "misc 里堆了 " + misc.size() + " 个工具，类目表该补了：" + misc);
    }

    @Test
    @DisplayName("核心集里没有错字：每个名字都真的是一个注册着的工具")
    void everyCoreNameIsARealTool() {
        RecordingToolRegistry registry = registry();
        List<String> missing = POLICY.coreToolNames().stream()
                .filter(name -> registry.resolve(name).isEmpty())
                .sorted()
                .toList();
        assertEquals(List.of(), missing,
                "核心集里这些名字没有对应的工具——错字不会报错，只会让那个能力悄悄从核心集里掉出去");
    }

    @Test
    @DisplayName("目录工具自己必须在核心集里，否则整套展开机制没有入口")
    void theCatalogToolItselfIsAlwaysOffered() {
        RecordingToolRegistry registry = registry();
        List<ToolSpecification> disclosed =
                POLICY.narrow(registry.getAllSpecifications("conv", null), Set.of());
        assertTrue(disclosed.stream().anyMatch(s -> s.name().equals(ToolDisclosurePolicy.CATALOG_TOOL)),
                "list_tools 被自己的策略裁掉了：模型再也没有办法看到其余工具");
    }

    @Test
    @DisplayName("常用几条链在核心集里是完整的：不必先查目录就能读文件、改文档、查法条")
    void thecommonJourneysAreCompleteWithoutExpanding() {
        RecordingToolRegistry registry = registry();
        Set<String> docx = POLICY.narrow(
                        registry.getAllSpecifications("conv", ClientCapabilityService.DOC_KIND_WRITER), Set.of())
                .stream().map(ToolSpecification::name).collect(java.util.stream.Collectors.toSet());

        // 每一组 = 一条必须不用查目录就能走完的链。少一环模型就会停在半路说做不了。
        assertTrue(docx.containsAll(List.of("doc_list_project_files", "extract_file_text", "read_document")),
                "「找一份材料并读全文」这条链断了：" + docx);
        assertTrue(docx.containsAll(List.of("doc_get_document_text", "doc_find_text", "doc_find_replace",
                        "doc_replace_at_anchor", "doc_insert_at_cursor", "doc_undo")),
                "「通读当前文档并改两处措辞」这条链断了：" + docx);
        assertTrue(docx.containsAll(List.of("doc_audit_structure", "doc_get_clauses", "doc_add_comment")),
                "「审查合同并留批注」这条链断了：" + docx);
        assertTrue(docx.containsAll(List.of("law_search", "law_search_keyword", "get_law_article", "search_web")),
                "「查法条 / 查公网」这条链断了：" + docx);
        assertTrue(docx.containsAll(List.of("doc_start_stream", "write_docx", "doc_apply_standard_format")),
                "「起草一份新文书并排版」这条链断了：" + docx);
        assertTrue(docx.containsAll(List.of("query_memory", "todo_write", "dispatch_subtask")),
                "记忆检索与编排工具必须在核心集：" + docx);
    }

    @Test
    @DisplayName("披露确实省下一大块，且展开一个类目后那些工具真的回来了")
    void disclosureShrinksTheSchemaAndExpansionBringsToolsBack() {
        RecordingToolRegistry registry = registry();
        List<ToolSpecification> docx =
                registry.getAllSpecifications("conv", ClientCapabilityService.DOC_KIND_WRITER);
        List<ToolSpecification> core = POLICY.narrow(docx, Set.of());
        List<ToolSpecification> withFormat = POLICY.narrow(docx, Set.of("format"));

        int full = ToolSchemaBudgetTest.weight(docx);
        int trimmed = ToolSchemaBudgetTest.weight(core);
        System.out.printf("[dev-board#810] docx：披露前 %d 个 / %d 字符；核心集 %d 个 / %d 字符；省下 %.1f%%%n",
                docx.size(), full, core.size(), trimmed, (1 - (double) trimmed / full) * 100);
        System.out.printf("[dev-board#810] docx 上线路字节：披露前 %d；核心集 %d；省下 %.1f%%%n",
                ToolSchemaBudgetTest.wireBytes(docx), ToolSchemaBudgetTest.wireBytes(core),
                (1 - (double) ToolSchemaBudgetTest.wireBytes(core) / ToolSchemaBudgetTest.wireBytes(docx)) * 100);
        System.out.printf("[dev-board#810] docx 目录类目：%s%n", categoryCensus(docx));

        assertTrue(core.size() < docx.size(), "核心集必须比候选集小");
        assertTrue(1 - (double) trimmed / full > 0.5,
                "核心集省下的 schema 体量不足 50%——核心集多半被塞成了全集");
        assertTrue(withFormat.size() > core.size(), "展开 format 之后排版工具必须回到可见集");
        assertTrue(withFormat.stream().anyMatch(s -> s.name().equals("doc_format_table")),
                "doc_format_table 属于 format 类目，展开后却没回来");
    }

    @Test
    @DisplayName("展开只做加法：已经下发过的工具不会因为又展开一个类目而消失")
    void expansionOnlyEverAdds() {
        RecordingToolRegistry registry = registry();
        List<ToolSpecification> docx =
                registry.getAllSpecifications("conv", ClientCapabilityService.DOC_KIND_WRITER);
        Set<String> step0 = names(POLICY.narrow(docx, Set.of()));
        Set<String> step1 = names(POLICY.narrow(docx, Set.of("format")));
        Set<String> step2 = names(POLICY.narrow(docx, Set.of("format", "table")));

        assertTrue(step1.containsAll(step0), "展开 format 之后丢了工具：" + difference(step0, step1));
        assertTrue(step2.containsAll(step1), "展开 table 之后丢了工具：" + difference(step1, step2));
        // 这条不是凑数：一轮内工具集必须不变，而「只增不减」是它与渐进披露相容的全部理由。
    }

    @Test
    @DisplayName("list_tools 的描述里把每个类目名都念到了（描述与类目表是两份，会漂）")
    void theCatalogDescriptionNamesEveryCategory() {
        RecordingToolRegistry registry = registry();
        String description = registry.resolve(ToolDisclosurePolicy.CATALOG_TOOL).orElseThrow()
                .spec().description();
        List<String> unmentioned = POLICY.categoryNames().stream()
                .filter(name -> !description.contains(name))
                .sorted()
                .toList();
        // 模型只看得见描述。类目表里加了一项而描述没跟上，那个类目就等于不存在——
        // 模型不会去猜一个没人告诉过它的类目名，而 parseCategories 又只认已知名字。
        assertEquals(List.of(), unmentioned,
                "这些类目没写进 list_tools 的描述，模型永远不会去展开它们：" + unmentioned);
    }

    @Test
    @DisplayName("未知类目被丢弃、逗号分隔一次展开多个")
    void categoryParsingIsForgivingButNeverInventsCategories() {
        assertEquals(Set.of(), POLICY.parseCategories("nonsense"));
        assertEquals(Set.of(), POLICY.parseCategories(null));
        assertEquals(Set.of("format", "table"), POLICY.parseCategories("format, table"));
        assertEquals(Set.of("format"), POLICY.parseCategories("FORMAT"));
    }

    @Test
    @DisplayName("开关关着时连目录工具自己都不下发——默认路径不为一个用不上的工具每轮付钱")
    void theCatalogToolCostsNothingWhileDisclosureIsOff() {
        RecordingToolRegistry off =
                new RecordingToolRegistry(RealToolBeans.instantiateAll(false), new PluginService());
        off.init();
        List<ToolSpecification> docx =
                off.getAllSpecifications("conv", ClientCapabilityService.DOC_KIND_WRITER);
        assertFalse(docx.stream().anyMatch(s -> s.name().equals(ToolDisclosurePolicy.CATALOG_TOOL)),
                "披露关着却照样下发 list_tools：每轮白付约一千字符，而这正是本卡要治的病");
        assertTrue(off.resolve(ToolDisclosurePolicy.CATALOG_TOOL).isPresent(),
                "只裁 spec、不裁 execute：登记必须还在");
        System.out.printf("[dev-board#810] 生产默认（披露关着）docx：%d 个工具 / %d 字符 / %d 上线路字节%n",
                docx.size(), ToolSchemaBudgetTest.weight(docx), ToolSchemaBudgetTest.wireBytes(docx));
        assertFalse(new ToolDisclosurePolicy(false).isEnabled());
    }

    private static Set<String> names(List<ToolSpecification> specs) {
        return specs.stream().map(ToolSpecification::name)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> difference(Set<String> before, Set<String> after) {
        Set<String> lost = new TreeSet<>(before);
        lost.removeAll(after);
        return lost;
    }

    private static String categoryCensus(List<ToolSpecification> specs) {
        java.util.Map<String, Integer> census = new java.util.TreeMap<>();
        for (ToolSpecification spec : specs) {
            census.merge(POLICY.categoryOf(spec.name()), 1, Integer::sum);
        }
        return census.toString();
    }
}
