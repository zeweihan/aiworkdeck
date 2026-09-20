// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ClientCapabilityService.isDocumentWritingTool} 对<b>全部</b>
 * doc_/sheet_/slide_ 工具的归类（dev-board#728）。
 *
 * <p>这个判据决定 {@code bubble_end.documentEdited}，进而决定「用到文档」那组操作出不出。
 * 错在两个方向都具体：
 * <ul>
 *   <li>把只读工具算成写入：「先读文档、再起草一条条款」那一轮的按钮被误藏，
 *       而那是最该出按钮的场景；</li>
 *   <li>把写入工具算成只读：AI 已经改完了，用户还被请去手动插一遍。</li>
 * </ul>
 *
 * <p><b>为什么要扫源码而不是写死一张名单</b>：名单会腐烂。这里从真实的工具类里把方法名
 * 捞出来，与下面这份期望逐名对拍——新增一个工具而没人归类，这条测试当场红，
 * 逼着加卡的人自己决定它算不算「改过文档」。
 */
class DocumentWritingToolClassificationTest {

    private static final Path TOOLS_DIR =
            Path.of("src/main/java/com/checkba/service/ai/tools");
    private static final Path AUDIT_TOOLS =
            Path.of("src/main/java/com/checkba/service/ai/tools/DocumentAuditTools.java");

    /** public 方法名形如 doc_xxx / sheet_xxx / slide_xxx —— 与 ToolRegistry 的取名口径一致。 */
    private static final Pattern TOOL_METHOD = Pattern.compile(
            "public\\s+(?:static\\s+)?[\\w<>\\[\\],\\s.]+?\\s+((?:doc|sheet|slide)_[a-zA-Z_0-9]+)\\s*\\(");

    /**
     * 读取 / 定位 / 打开类，逐个看名字定的。凡不在这份名单里的 doc_/sheet_/slide_ 工具
     * 都按「会改动文档」处理（拿不准算写入）。
     *
     * <p>几个刻意留在写入侧、名字容易让人误会的：
     * {@code doc_undo} / {@code doc_redo} / {@code doc_restore_checkpoint} 都会改正文；
     * {@code doc_collapse_cursor} 只动光标，但它几乎只在插入前后出现，算写入没有实际代价。
     */
    private static final Set<String> EXPECTED_READ_ONLY = new TreeSet<>(List.of(
            // —— doc_：读取 ——
            "doc_audit_structure",
            "doc_debug_revisions",
            "doc_find_text",
            "doc_get_clauses",
            "doc_get_comments",
            "doc_get_cursor_context",
            "doc_get_document_text",
            "doc_get_formatting",
            "doc_get_outline",
            "doc_get_paragraph",
            "doc_get_selection",
            "doc_list_evidence",
            "doc_list_project_files",
            "doc_list_revisions",
            "doc_search_related_docs",
            "doc_table_read",
            // —— doc_：定位 / 打开 ——
            "doc_goto",
            "doc_open_file",
            "doc_select_anchor",
            "doc_select_paragraph",
            "doc_set_selection",
            // —— sheet_ ——
            "sheet_get_comments",
            "sheet_get_overview",
            "sheet_read_range",
            "sheet_search",
            "sheet_select_range",
            // —— slide_ ——
            "slide_get_overview",
            "slide_get_page",
            "slide_goto",
            "slide_read_notes",
            "slide_table_read"));

    private static Set<String> allLowaToolNames() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(TOOLS_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = TOOL_METHOD.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        // CheckpointTools 与 DocumentAuditTools 也在 tools/ 下，上面的遍历已经覆盖；
        // 这里只是确认扫描路径没写错（目录挪走了就该红，而不是静默少扫一批工具）。
        assertTrue(Files.exists(AUDIT_TOOLS), "工具源码目录变了，扫描口径要跟着改：" + TOOLS_DIR);
        return names;
    }

    @Test
    @DisplayName("全部 doc_/sheet_/slide_ 工具逐名归类：只读集合必须与期望逐字相同")
    void everyLowaToolIsClassifiedExplicitly() throws IOException {
        Set<String> all = allLowaToolNames();
        assertTrue(all.size() > 100, "只扫到 " + all.size() + " 个工具，扫描口径八成坏了");

        Set<String> actualReadOnly = new TreeSet<>();
        for (String name : all) {
            if (!ClientCapabilityService.isDocumentWritingTool(name)) {
                actualReadOnly.add(name);
            }
        }

        Set<String> newlyReadOnly = new LinkedHashSet<>(actualReadOnly);
        newlyReadOnly.removeAll(EXPECTED_READ_ONLY);
        Set<String> nowWriting = new LinkedHashSet<>(EXPECTED_READ_ONLY);
        nowWriting.removeAll(actualReadOnly);

        assertEquals(EXPECTED_READ_ONLY, actualReadOnly,
                "工具归类变了。新被算成只读的：" + newlyReadOnly
                        + "；本该只读却被算成写入的：" + nowWriting
                        + "。新增工具请自己判断它改不改文档，再决定是补进 EXPECTED_READ_ONLY "
                        + "还是让它落在写入侧（判据在 ClientCapabilityService.READ_ONLY_*）。");
    }

    @Test
    @DisplayName("两个真实的坑：find_ 开头的 doc_find_replace 是写入；selection 子串两侧都有")
    void theTwoNameTraps() {
        // 全仓最常用的写入原语，名字以 find_ 开头。把 find 做成词头模式就会把它判成只读，
        // 表现是「AI 改完了，按钮还在」——正好是这张卡要消灭的那一种多余。
        assertTrue(ClientCapabilityService.isDocumentWritingTool("doc_find_replace"));
        assertFalse(ClientCapabilityService.isDocumentWritingTool("doc_find_text"));

        // selection 不能做子串匹配：挪选区是只读，对选区动手是写入。
        assertFalse(ClientCapabilityService.isDocumentWritingTool("doc_set_selection"));
        assertTrue(ClientCapabilityService.isDocumentWritingTool("doc_replace_selection"));
        assertTrue(ClientCapabilityService.isDocumentWritingTool("doc_delete_selection"));
        assertTrue(ClientCapabilityService.isDocumentWritingTool("doc_format_selection"));

        // inspect 词头若松绑成前缀匹配就会咬到 insert_*
        assertTrue(ClientCapabilityService.isDocumentWritingTool("doc_insert_table"));
        assertTrue(ClientCapabilityService.isDocumentWritingTool("doc_insert_at_cursor"));
        // list 同理不能咬到 link_
        assertTrue(ClientCapabilityService.isDocumentWritingTool("doc_link_evidence"));
    }

    @Test
    @DisplayName("「先读文档再起草」的典型序列：读取工具一个都不该被算成改过文档")
    void readThenDraftIsNotAnEdit() {
        for (String readOnly : List.of("doc_open_file", "doc_get_document_text", "doc_get_outline",
                "doc_get_clauses", "doc_list_project_files", "doc_search_related_docs",
                "doc_find_text", "doc_goto", "doc_select_paragraph", "sheet_read_range")) {
            assertFalse(ClientCapabilityService.isDocumentWritingTool(readOnly),
                    readOnly + " 是读取类，算成「改过文档」会把最该出按钮那一轮的按钮藏掉");
        }
    }

    @Test
    @DisplayName("两个判据分开：工具可见性仍按宽前缀（读取工具一样需要 LOWA）")
    void visibilityKeepsTheWidePrefix() {
        // isToolVisible 问的是「需不需要 LOWA 编辑器」——Office 会话里连读都读不了，
        // 所以读取工具必须仍算 LOWA 工具。合成一个函数就会把它放行给 Office 会话。
        for (String readOnly : List.of("doc_get_document_text", "sheet_read_range", "slide_goto")) {
            assertTrue(ClientCapabilityService.isLowaTool(readOnly));
            assertFalse(ClientCapabilityService.isDocumentWritingTool(readOnly));
        }
        assertFalse(ClientCapabilityService.isLowaTool("office_replace_batch"));
        assertFalse(ClientCapabilityService.isLowaTool("search_project_files"));
        assertFalse(ClientCapabilityService.isLowaTool(null));
        assertFalse(ClientCapabilityService.isDocumentWritingTool(null));
    }
}
