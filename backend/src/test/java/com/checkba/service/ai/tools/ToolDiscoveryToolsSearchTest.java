// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.ToolDisclosurePolicy;
import com.checkba.service.ai.skill.SkillDefinition;
import com.checkba.service.ai.skill.SkillRouter;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * list_tools 的 query / names 两种查法与 skill 目录（dev-board#1065，对标 Claude Code 的 ToolSearch）。
 *
 * <p>重点是「展示了哪些类目」经 {@link ToolContext#disclosedCategories()} 回写给编排器：
 * 编排器只看参数推不出 query 命中了什么，回写漏了的话模型看到了全签名、下一轮却拿不到原生规格。
 */
class ToolDiscoveryToolsSearchTest {

    private static ToolSpecification spec(String name, String description) {
        return ToolSpecification.builder().name(name).description(description).build();
    }

    private static final List<ToolSpecification> SESSION = List.of(
            spec("pdf_merge", "Merge several PDF files into one new PDF."),
            spec("pdf_split", "Split a PDF into several files by page ranges."),
            spec("pdf_add_page_numbers", "Stamp page numbers or Bates numbers onto a PDF."),
            spec("doc_insert_page_number", "Insert a page number field into the Writer footer."),
            spec("litigation_render", "Render a litigation timeline diagram."),
            spec("doc_get_document_text", "Read the whole open document."),
            spec("list_tools", "catalog"));

    private final Set<String> disclosed = ConcurrentHashMap.newKeySet();

    private void bindContext(List<ToolSpecification> session, String runId) {
        ToolContextHolder.set(new ToolContext(1L, "conv", 7L, null, session,
                Set.of("doc_get_document_text", "list_tools"), runId, disclosed));
    }

    private static ToolDiscoveryTools tools() {
        return new ToolDiscoveryTools(new ToolDisclosurePolicy(false, true));
    }

    @AfterEach
    void clear() {
        ToolContextHolder.clear();
    }

    @Test
    @DisplayName("query 是 AND 语义、大小写不敏感，搜名字也搜描述")
    void queryMatchesEveryTermInNameOrDescription() {
        bindContext(SESSION, "run-1");
        String out = tools().list_tools(null, "PDF page", null, "conv");

        assertTrue(out.contains("pdf_add_page_numbers("), out);
        assertTrue(out.contains("pdf_split("), "描述里有 page 也算命中：" + out);
        assertFalse(out.contains("pdf_merge("), "只命中一个词的不算：" + out);
        assertFalse(out.contains("doc_insert_page_number("), "没有 pdf 这个词的不算：" + out);
        assertTrue(out.contains("from the NEXT turn on"), "尾句必须还在：" + out);
        assertTrue(out.contains("<tool_code>"), out);
        assertEquals(Set.of("pdf"), disclosed, "命中工具的类目要回写给编排器");
    }

    @Test
    @DisplayName("query 最多给 15 条全签名，多出来的只报个数")
    void queryIsCappedAtFifteen() {
        List<ToolSpecification> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(spec("pdf_tool_" + i, "pdf helper number " + i));
        }
        bindContext(many, "run-1");
        String out = tools().list_tools(null, "pdf helper", null, "conv");

        long shown = out.lines().filter(l -> l.startsWith("- pdf_tool_")).count();
        assertEquals(ToolDiscoveryTools.MAX_QUERY_HITS, shown, out);
        assertTrue(out.contains("5 more tools matched"), out);
        assertTrue(out.contains("These 15 tools"), out);
    }

    @Test
    @DisplayName("query 一个都没命中：不回写任何类目，也不以 Error 开头")
    void queryWithoutHitsRecordsNothing() {
        bindContext(SESSION, "run-1");
        String out = tools().list_tools(null, "spreadsheet pivot", null, "conv");

        assertTrue(out.startsWith("No tool in this session matches"), out);
        assertTrue(disclosed.isEmpty(), "没展示任何签名就不该放回任何类目：" + disclosed);
    }

    @Test
    @DisplayName("names 按确切名字给签名，查不到的单列一行，且不让整次调用变成失败")
    void namesAreExactAndUnknownNamesAreReported() {
        bindContext(SESSION, "run-1");
        String out = tools().list_tools(null, null, "pdf_merge, litigation_render,pdf_mer,nope", "conv");

        assertTrue(out.contains("- pdf_merge("), out);
        assertTrue(out.contains("- litigation_render("), out);
        assertFalse(out.contains("- pdf_split("), "names 是精确匹配，不是前缀：" + out);
        assertTrue(out.contains("not found in this session: pdf_mer, nope"), out);
        assertFalse(out.startsWith("Error"), "部分命中不算失败：" + out);
        assertEquals(Set.of("pdf", "litigation"), disclosed);
    }

    @Test
    @DisplayName("names 全部查不到：只报查不到，不回写")
    void namesAllUnknown() {
        bindContext(SESSION, "run-1");
        String out = tools().list_tools(null, null, "nope", "conv");

        assertTrue(out.startsWith("not found in this session: nope"), out);
        assertTrue(disclosed.isEmpty());
    }

    @Test
    @DisplayName("优先级 names > query > category")
    void namesWinOverQueryAndQueryOverCategory() {
        bindContext(SESSION, "run-1");
        String byNames = tools().list_tools("litigation", "merge", "pdf_split", "conv");
        assertTrue(byNames.contains("- pdf_split("), byNames);
        assertFalse(byNames.contains("pdf_merge("), byNames);
        assertFalse(byNames.contains("litigation_render("), byNames);

        disclosed.clear();
        String byQuery = tools().list_tools("litigation", "merge", null, "conv");
        assertTrue(byQuery.contains("- pdf_merge("), byQuery);
        assertFalse(byQuery.contains("litigation_render("), byQuery);
        assertEquals(Set.of("pdf"), disclosed);
    }

    @Test
    @DisplayName("category 展开也回写类目（与编排器按参数解析的结果一致）")
    void categoryExpansionIsRecordedToo() {
        bindContext(SESSION, "run-1");
        tools().list_tools("litigation", null, null, "conv");
        assertEquals(Set.of("litigation"), disclosed);
    }

    @Test
    @DisplayName("无参目录页附 skill 目录：只列本轮还没生效、模型可调用的")
    void indexListsInvocableSkillsNotActiveYet() {
        SkillDefinition verification = skill("shareholder-meeting-verification", "股东大会核查",
                "上市公司股东会法律见证：交叉核对与表决复算。\n第二行不该出现");
        SkillDefinition visual = skill("litigation-visual", "诉讼可视化", "把案件材料画成图");
        SkillRouter router = mock(SkillRouter.class);
        when(router.invocableSkills()).thenReturn(List.of(verification, visual));
        when(router.isActiveInRun(eq("run-1"), eq("litigation-visual"))).thenReturn(true);
        when(router.isActiveInRun(eq("run-1"), eq("shareholder-meeting-verification"))).thenReturn(false);
        when(router.displayName(any())).thenAnswer(inv -> ((SkillDefinition) inv.getArgument(0)).getName());

        ToolDiscoveryTools tools = tools();
        tools.setSkillRouter(router);
        bindContext(SESSION, "run-1");
        String index = tools.list_tools(null, null, null, "conv");

        assertTrue(index.contains("skills (specialised workflows; switch with use_skill"), index);
        assertTrue(index.contains("- shareholder-meeting-verification — 股东大会核查: 上市公司股东会法律见证"), index);
        assertFalse(index.contains("第二行不该出现"), "只取描述的第一行：" + index);
        assertFalse(index.contains("- litigation-visual"), "本轮已经生效的 skill 不再列：" + index);
    }

    @Test
    @DisplayName("query 顺带列出命中的 skill")
    void queryAlsoFindsSkills() {
        SkillDefinition visual = skill("litigation-visual", "诉讼可视化", "把案件材料画成时间轴、关系图");
        SkillRouter router = mock(SkillRouter.class);
        when(router.invocableSkills()).thenReturn(List.of(visual));
        when(router.displayName(any())).thenAnswer(inv -> ((SkillDefinition) inv.getArgument(0)).getName());

        ToolDiscoveryTools tools = tools();
        tools.setSkillRouter(router);
        bindContext(SESSION, "run-1");
        String out = tools.list_tools(null, "时间轴", null, "conv");

        assertTrue(out.contains("skills matching this"), out);
        assertTrue(out.contains("- litigation-visual — 诉讼可视化"), out);
    }

    private static SkillDefinition skill(String id, String name, String description) {
        SkillDefinition def = new SkillDefinition();
        def.setId(id);
        def.setName(name);
        def.setDescription(description);
        return def;
    }
}
