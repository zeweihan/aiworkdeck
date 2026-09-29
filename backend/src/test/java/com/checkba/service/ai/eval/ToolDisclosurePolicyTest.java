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
        assertTrue(docx.containsAll(List.of("doc_list_project_files", "extract_file_text", "search_project_content")),
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
        System.out.printf("[dev-board#810] 披露与类目裁剪都关着的 docx：%d 个工具 / %d 字符 / %d 上线路字节%n",
                docx.size(), ToolSchemaBudgetTest.weight(docx), ToolSchemaBudgetTest.wireBytes(docx));
        assertFalse(new ToolDisclosurePolicy(false).isEnabled());
    }

    @Test
    @DisplayName("移入回收站是核心集常驻、无需展开；永久删除 delete_file 披露开关两态都不下发（dev-board#1044）")
    void trashIsOfferedButPermanentDeleteNeverIs() {
        RecordingToolRegistry registry = registry();
        Set<String> narrowed = names(POLICY.narrow(registry.getAllSpecifications("conv", null), Set.of()));
        assertTrue(narrowed.contains("move_to_trash"), "move_to_trash 应与 move_files_batch 同档常驻核心集：" + narrowed);
        assertFalse(narrowed.contains("delete_file"), "delete_file 不许下发");

        RecordingToolRegistry off =
                new RecordingToolRegistry(RealToolBeans.instantiateAll(false), new PluginService());
        off.init();
        Set<String> all = names(off.getAllSpecifications("conv", null));
        assertTrue(all.contains("move_to_trash"), "披露关着时（生产默认）模型也要看得见 move_to_trash");
        assertFalse(all.contains("delete_file"), "披露关着时 delete_file 同样不下发");
    }

    // ==================== 类目归属（dev-board#1065 T-19 / T-20）====================

    @Test
    @DisplayName("类目归属：edit 从 doc_ 通配里拆出来、tag 独立、记忆/证据各归其位，导出 PDF 不被 format 吃掉")
    void categoryAssignmentsAfterTheSplit() {
        // edit：定位 / 删改 / 按段落取改。核心集里的几个照旧是 core（categoryOf 先判核心集）
        for (String name : List.of("doc_goto", "doc_collapse_cursor", "doc_delete_selection", "doc_redo",
                "doc_modify_paragraph", "doc_replace_nth_match", "doc_delete_match", "doc_delete_text",
                "doc_set_selection", "doc_get_selection", "doc_insert_under_heading")) {
            assertEquals("edit", POLICY.categoryOf(name), name);
        }
        for (String name : List.of("doc_select_anchor", "doc_select_paragraph", "doc_get_paragraph", "doc_get_outline")) {
            assertEquals("core", POLICY.categoryOf(name), name + " 仍在核心集");
        }
        // format 剩下的才是真·版式
        for (String name : List.of("doc_format_selection", "doc_set_paragraph_format", "doc_insert_toc",
                "doc_set_page_setup", "doc_insert_image")) {
            assertEquals("format", POLICY.categoryOf(name), name);
        }
        assertEquals("files", POLICY.categoryOf("doc_export_pdf"), "排在 format 通配之前才不会被吃掉");
        assertEquals("revision", POLICY.categoryOf("doc_restore_checkpoint"), "最后手段，退出核心集");
        assertEquals("core", POLICY.categoryOf("doc_undo"), "常规纠错留在核心集");
        for (String name : List.of("tag_list", "tag_file", "tag_remove_from_file")) {
            assertEquals("tag", POLICY.categoryOf(name), name + " 不是事项");
        }
        assertEquals("task", POLICY.categoryOf("task_create"));
        assertEquals("memory", POLICY.categoryOf("update_project_info"));
        assertEquals("evidence", POLICY.categoryOf("dd_export"));
        assertEquals("evidence", POLICY.categoryOf("web_verify_import"));
        assertEquals("enterprise-data", POLICY.categoryOf("qichacha_query"));
    }

    @Test
    @DisplayName("核心集收窄（T-14/T-15/T-16/T-19）：不下发或有更好路径的四个工具退出核心集")
    void retiredOrDemotedToolsLeftTheCore() {
        for (String name : List.of("doc_delete_text", "doc_get_selection", "doc_insert_under_heading",
                "doc_restore_checkpoint")) {
            assertFalse(POLICY.coreToolNames().contains(name), name + " 不该再在核心集里");
        }
        // 替代路径仍在核心集：删除走锚点替换/查找替换传空串、看选区走 cursor_context、锚点插入一步完成
        assertTrue(POLICY.coreToolNames().containsAll(List.of("doc_replace_at_anchor", "doc_find_replace",
                "doc_get_cursor_context", "doc_insert_at_cursor", "doc_undo")));
    }

    @Test
    @DisplayName("运行期插件工具单独成 plugin-tools 类目，不再落 misc；没人交来源时视为没有插件工具")
    void pluginToolsGetTheirOwnCategory() {
        ToolDisclosurePolicy policy = new ToolDisclosurePolicy(true);
        assertEquals(ToolDisclosurePolicy.FALLBACK_CATEGORY, policy.categoryOf("acme_contract_scan"),
                "没有交来源时认不出是插件工具，照旧落 misc");
        policy.setPluginToolNames(() -> Set.of("acme_contract_scan", "doc_acme_helper"));
        assertEquals(ToolDisclosurePolicy.PLUGIN_TOOLS_CATEGORY, policy.categoryOf("acme_contract_scan"));
        assertEquals(ToolDisclosurePolicy.PLUGIN_TOOLS_CATEGORY, policy.categoryOf("doc_acme_helper"),
                "插件工具判定先于前缀规则：一个叫 doc_ 的插件工具不该被当成版式工具");
        assertEquals("core", policy.categoryOf("doc_find_replace"), "核心集优先于一切");
        assertTrue(policy.categoryNames().contains(ToolDisclosurePolicy.PLUGIN_TOOLS_CATEGORY));
        assertEquals(Set.of(ToolDisclosurePolicy.PLUGIN_TOOLS_CATEGORY), policy.parseCategories("plugin-tools"));

        // 接线：ToolRegistry 初始化时把插件工具名的活视图交给策略（插件热加载后自动跟上）
        ToolDisclosurePolicy wired = new ToolDisclosurePolicy(true);
        PluginService plugins = new PluginService();
        RecordingToolRegistry registry = new RecordingToolRegistry(RealToolBeans.instantiateAll(), plugins);
        org.springframework.test.util.ReflectionTestUtils.setField(registry, "disclosurePolicy", wired);
        registry.init();
        plugins.getPluginTools().put("acme_hot_loaded", new Object());
        assertEquals(ToolDisclosurePolicy.PLUGIN_TOOLS_CATEGORY, wired.categoryOf("acme_hot_loaded"),
                "注册表初始化之后才加载的插件工具也要认得出（活视图）");
        assertEquals("core", wired.categoryOf("doc_find_replace"));

        policy.setPluginToolNames(() -> { throw new IllegalStateException("boom"); });
        assertEquals(ToolDisclosurePolicy.FALLBACK_CATEGORY, policy.categoryOf("acme_contract_scan"),
                "来源抛异常时判不准就落回普通规则，目录不能整个挂掉");
    }

    // ==================== 活跃文档类目裁剪（dev-board#1064）====================

    private static final ToolDisclosurePolicy TRIM = new ToolDisclosurePolicy(false, true);

    @Test
    @DisplayName("类目裁剪：每种文档类型藏哪些类目是钉死的；判不准的类型一个都不藏")
    void hiddenCategoriesPerDocKindArePinned() {
        Set<String> docAndSheet = Set.of("pdf", "litigation", "slides", "enterprise-data", "plugin", "meeting", "python");
        assertEquals(docAndSheet, TRIM.hiddenCategoriesFor(ClientCapabilityService.DOC_KIND_WRITER));
        assertEquals(docAndSheet, TRIM.hiddenCategoriesFor(ClientCapabilityService.DOC_KIND_SHEET));
        assertEquals(Set.of("pdf", "litigation", "spreadsheet", "enterprise-data", "plugin", "meeting", "python"),
                TRIM.hiddenCategoriesFor(ClientCapabilityService.DOC_KIND_SLIDE));
        assertEquals(Set.of(), TRIM.hiddenCategoriesFor(null));
        assertEquals(Set.of(), TRIM.hiddenCategoriesFor("text"));
        assertEquals(Set.of(), TRIM.hiddenCategoriesFor("nonsense"));
        assertEquals(Set.of(), POLICY.hiddenCategoriesFor(ClientCapabilityService.DOC_KIND_WRITER),
                "只开渐进披露、没开类目裁剪时不许藏任何类目");
        for (String kind : new String[]{"doc", "sheet", "slide"}) {
            assertTrue(TRIM.categoryNames().containsAll(TRIM.hiddenCategoriesFor(kind)),
                    kind + " 的隐藏表里有目录不认识的类目——那一类藏了就再也放不回来");
        }
        // 刻意留着的：律师改文档时的法源、网页、事项、记忆、版式、表格、修订
        for (String kept : List.of("legal", "task", "tag", "memory", "edit", "format", "table", "revision",
                "evidence", "files", "plugin-tools", "misc")) {
            assertFalse(TRIM.hiddenCategoriesFor("doc").contains(kept), kept + " 不该在 docx 会话里被藏");
        }
    }

    @Test
    @DisplayName("类目裁剪：docx 真实工具集里只摘掉隐藏类目，核心集与 list_tools 一个不少；放回的类目回来")
    void trimForDocKindOnlyDropsHiddenCategoriesAndHonoursPutBack() {
        RecordingToolRegistry registry = registry();
        List<ToolSpecification> docx = registry.getAllSpecifications("conv", ClientCapabilityService.DOC_KIND_WRITER);
        Set<String> hidden = TRIM.hiddenCategoriesFor("doc");

        List<ToolSpecification> trimmed = TRIM.trimForDocKind(docx, "doc", Set.of());
        Set<String> kept = names(trimmed);
        for (ToolSpecification spec : docx) {
            String category = TRIM.categoryOf(spec.name());
            assertEquals(!hidden.contains(category), kept.contains(spec.name()),
                    spec.name() + "（类目 " + category + "）的去留与隐藏表不一致");
        }
        assertTrue(kept.contains(ToolDisclosurePolicy.CATALOG_TOOL), "list_tools 是放回的入口，永远不许裁");
        Set<String> coreInDocx = new TreeSet<>(names(docx));
        coreInDocx.retainAll(TRIM.coreToolNames());
        assertTrue(kept.containsAll(coreInDocx), "核心集不许被类目裁剪摘掉：" + difference(coreInDocx, kept));
        assertTrue(trimmed.size() < docx.size(), "docx 会话里确实有东西被藏");
        assertFalse(kept.contains("pdf_to_word"));
        assertFalse(kept.contains("pptx_generate"), "pptx_* 不带 slide_ 前缀，只能靠这里的 slides 类目裁掉");

        Set<String> putBack = names(TRIM.trimForDocKind(docx, "doc", Set.of("pdf", "slides")));
        assertTrue(putBack.contains("pdf_to_word") && putBack.contains("pptx_generate"), "放回的类目必须回来");
        assertFalse(putBack.contains("litigation_render"), "没放回的类目仍然藏着");

        assertEquals(docx, TRIM.trimForDocKind(docx, null, Set.of()), "kind 判不准一律不裁");
        assertEquals(docx, POLICY.trimForDocKind(docx, "doc", Set.of()), "开关关着一律不裁");
    }

    @Test
    @DisplayName("类目裁剪：关键词放回——每组都认得出，中性问句一个都不提示，拉丁词按整词匹配")
    void keywordsHintTheRightCategories() {
        assertEquals(Set.of(), TRIM.categoriesHintedBy("这是什么文件？"));
        assertEquals(Set.of(), TRIM.categoriesHintedBy("把第二段的『甲方』改成『买方』"));
        assertEquals(Set.of(), TRIM.categoriesHintedBy("帮我做一版汇报用的 deck"));
        assertEquals(Set.of(), TRIM.categoriesHintedBy(null));
        assertEquals(Set.of("pdf"), TRIM.categoriesHintedBy("把项目里那份 PDF 转成 Word"));
        assertEquals(Set.of("litigation"), TRIM.categoriesHintedBy("根据这份合同画一张当事人关系图"));
        assertEquals(Set.of("litigation"), TRIM.categoriesHintedBy("draw a timeline of the dispute"));
        assertEquals(Set.of("slides"), TRIM.categoriesHintedBy("把这份合同转成幻灯片"));
        assertEquals(Set.of("slides"), TRIM.categoriesHintedBy("做一份 PPTX"));
        assertEquals(Set.of("enterprise-data"), TRIM.categoriesHintedBy("查一下甲方公司的工商信息"));
        assertEquals(Set.of("plugin"), TRIM.categoriesHintedBy("装一个插件"));
        assertEquals(Set.of("meeting"), TRIM.categoriesHintedBy("把昨天的录音整理一下"));
        assertEquals(Set.of("python"), TRIM.categoriesHintedBy("用 Python 算一下违约金"));
        assertEquals(Set.of("spreadsheet"), TRIM.categoriesHintedBy("把这些数字填进 Excel"));
        assertEquals(Set.of("task"), TRIM.categoriesHintedBy("帮我建个开庭日程"));
        // 拉丁词两端整词（SkillRouter.containsTrigger 同一口径）：别的单词的一截不算
        assertFalse(TRIM.categoriesHintedBy("pythonic 的写法").contains("python"));
        assertFalse(TRIM.categoriesHintedBy("超过 100 pdfs").contains("pdf"));
        for (String category : TRIM.keywordCategories()) {
            assertTrue(TRIM.categoryNames().contains(category), "关键词表里的类目 " + category + " 目录不认识");
        }
    }

    @Test
    @DisplayName("类目裁剪：skill 放回——白名单里点名的工具，它们所在的类目整类放回（core 不算）")
    void skillAllowedToolsMapToCategories() {
        assertEquals(Set.of("python", "litigation"),
                TRIM.categoriesCoveredBy(List.of("run_python", "extract_file_text", "litigation_render")));
        assertEquals(Set.of(), TRIM.categoriesCoveredBy(List.of("doc_get_document_text", "law_search")));
        assertEquals(Set.of(), TRIM.categoriesCoveredBy(null));
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
