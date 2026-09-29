// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.service.ai.ToolDisclosurePolicy;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * list_tools 目录页列的是「本会话有、但本轮没下发」的工具（dev-board#1064）。
 *
 * <p>只开活跃文档类目裁剪、没开渐进披露时，核心集以外的大半工具（版式、表格、修订……）其实都在
 * 模型手上。按旧口径「非核心即未下发」列，目录页会把它们也列成「你手上没有」，模型据此去
 * 展开一个本来就有的类目，白花一轮。
 */
class ToolDiscoveryToolsIndexTest {

    private static ToolSpecification spec(String name) {
        return ToolSpecification.builder().name(name).description(name).build();
    }

    private static final List<ToolSpecification> SESSION = List.of(
            spec("doc_get_document_text"), spec("doc_format_table"), spec("pdf_inspect"), spec("list_tools"));

    @AfterEach
    void clear() {
        ToolContextHolder.clear();
    }

    @Test
    @DisplayName("目录页 = 候选集 − 本轮下发集：已下发的非核心工具不再列成「没看见」")
    void indexListsOnlyToolsThatWereNotOffered() {
        ToolContextHolder.set(new ToolContext(1L, "conv", 7L, null, SESSION,
                Set.of("doc_get_document_text", "doc_format_table", "list_tools")));
        String index = new ToolDiscoveryTools(new ToolDisclosurePolicy(false, true)).list_tools(null, "conv");

        assertTrue(index.contains("pdf (1): pdf_inspect"), index);
        assertFalse(index.contains("doc_format_table"), "已经在模型手上的工具不该出现在目录页：" + index);
    }

    @Test
    @DisplayName("调用方没给本轮下发集时退回旧口径：非核心即未下发")
    void withoutOfferedSetFallsBackToNonCore() {
        ToolContextHolder.set(new ToolContext(1L, "conv", 7L, null, SESSION));
        String index = new ToolDiscoveryTools(new ToolDisclosurePolicy(true)).list_tools(null, "conv");

        assertTrue(index.contains("pdf_inspect"), index);
        assertTrue(index.contains("doc_format_table"), index);
        assertFalse(index.contains("doc_get_document_text"), "核心集永远不列：" + index);
    }

    @Test
    @DisplayName("两个开关任一开着，目录工具就进程级可用；都关着时不下发")
    void availabilityFollowsEitherSwitch() {
        assertTrue(new ToolDiscoveryTools(new ToolDisclosurePolicy(false, true)).isAvailable());
        assertTrue(new ToolDiscoveryTools(new ToolDisclosurePolicy(true, false)).isAvailable());
        assertFalse(new ToolDiscoveryTools(new ToolDisclosurePolicy(false, false)).isAvailable());
    }
}
