// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.controller.ai.AiAgentController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按活跃文档类型裁工具（dev-board#729 ①）。
 *
 * <p>病灶：{@code isToolVisible} 把 doc_/sheet_/slide_ 当同一个 lowaOnly 桶，于是一份 3 页 docx 的
 * 会话里，27 个 sheet_* 与 22 个 slide_* 的 JSON schema 每轮都要重发一遍。实测 202 个工具
 * 59045 prompt token / 首轮 26.4s，裁到 16 个是 18854 token / 6.1s——工具 schema 占了 prompt 的约 2/3，
 * 而这部分对「改 Word 文档」这件事一个字都用不上。
 */
class ClientCapabilityDocKindTest {

    private final ClientCapabilityService caps = new ClientCapabilityService();

    private static AiAgentController.ContextItem item(String name, String fileType) {
        AiAgentController.ContextItem it = new AiAgentController.ContextItem();
        it.setId("42");
        it.setName(name);
        it.setFileType(fileType);
        return it;
    }

    @Test
    @DisplayName("docx 活跃：slide_* 与写入类 sheet_* 全部隐藏，sheet_create_file 留着")
    void writerHidesSlideAndSheetEditingTools() {
        String kind = ClientCapabilityService.DOC_KIND_WRITER;
        assertTrue(caps.isToolVisible("doc_find_replace", "conv", kind));
        assertTrue(caps.isToolVisible("doc_find_text", "conv", kind));
        assertTrue(caps.isToolVisible("doc_audit_structure", "conv", kind));

        assertFalse(caps.isToolVisible("slide_add_page", "conv", kind));
        assertFalse(caps.isToolVisible("slide_get_overview", "conv", kind));
        assertFalse(caps.isToolVisible("sheet_write_cells", "conv", kind));
        assertFalse(caps.isToolVisible("sheet_get_overview", "conv", kind));

        // 「在 Word 会话里新建一个表格文件」是合法任务，唯一保留的 sheet_* 工具
        assertTrue(caps.isToolVisible("sheet_create_file", "conv", kind));
    }

    @Test
    @DisplayName("跨类型新建流程的第一步不许被堵死：两个「新建并打开」工具任何类型下都可见")
    void newDocumentToolsStayVisibleAcrossKinds() {
        for (String kind : new String[]{ClientCapabilityService.DOC_KIND_WRITER,
                ClientCapabilityService.DOC_KIND_SHEET, ClientCapabilityService.DOC_KIND_SLIDE}) {
            // 「把合同的付款条款整理成一张表」（Word 会话里建 xlsx）
            assertTrue(caps.isToolVisible("sheet_create_file", "conv", kind), "kind=" + kind);
            // 「看着这份台账起草一份说明」（Excel 会话里建 docx）
            assertTrue(caps.isToolVisible("doc_start_stream", "conv", kind), "kind=" + kind);
        }
    }

    @Test
    @DisplayName("xlsx 活跃：doc_* 只留纯后端那几个，slide_* 全隐藏")
    void calcHidesWriterBridgeToolsButKeepsPureBackendOnes() {
        String kind = ClientCapabilityService.DOC_KIND_SHEET;
        assertTrue(caps.isToolVisible("sheet_write_cells", "conv", kind));
        assertTrue(caps.isToolVisible("sheet_create_file", "conv", kind));

        assertFalse(caps.isToolVisible("doc_find_replace", "conv", kind));
        assertFalse(caps.isToolVisible("doc_get_document_text", "conv", kind));
        assertFalse(caps.isToolVisible("doc_audit_structure", "conv", kind));
        assertFalse(caps.isToolVisible("slide_add_page", "conv", kind));

        // 这四个不经 executeEditorCommand（纯后端执行），跨文档类型都成立：
        // 列文件/打开文件/搜文件是换目标文档的唯一入口，回滚检查点是 Calc 写入即生效的安全网。
        assertTrue(caps.isToolVisible("doc_list_project_files", "conv", kind));
        assertTrue(caps.isToolVisible("doc_open_file", "conv", kind));
        assertTrue(caps.isToolVisible("doc_search_related_docs", "conv", kind));
        assertTrue(caps.isToolVisible("doc_restore_checkpoint", "conv", kind));
    }

    @Test
    @DisplayName("pptx 活跃：slide_* 可见，doc_*/sheet_* 同 xlsx 口径")
    void impressKeepsSlideTools() {
        String kind = ClientCapabilityService.DOC_KIND_SLIDE;
        assertTrue(caps.isToolVisible("slide_add_page", "conv", kind));
        assertTrue(caps.isToolVisible("slide_table_set_cell", "conv", kind));

        assertFalse(caps.isToolVisible("doc_find_replace", "conv", kind));
        assertFalse(caps.isToolVisible("sheet_write_cells", "conv", kind));

        assertTrue(caps.isToolVisible("doc_open_file", "conv", kind));
        assertTrue(caps.isToolVisible("sheet_create_file", "conv", kind));
    }

    @Test
    @DisplayName("没有活跃文档或类型未知时保持全集（不许裁）")
    void unknownKindKeepsEverything() {
        for (String kind : new String[]{null, "", "text", "whatever"}) {
            assertTrue(caps.isToolVisible("doc_find_replace", "conv", kind), "kind=" + kind);
            assertTrue(caps.isToolVisible("sheet_write_cells", "conv", kind), "kind=" + kind);
            assertTrue(caps.isToolVisible("slide_add_page", "conv", kind), "kind=" + kind);
        }
    }

    @Test
    @DisplayName("裁剪只作用于 LOWA 会话：office/none 的既有判定一字不变")
    void docKindNeverLoosensOtherCapabilities() {
        caps.record("conv-office", "office", "WORD");
        // office 会话下 doc_*/sheet_*/slide_* 本来就全隐藏，传任何 kind 都不能把它们放回来
        assertFalse(caps.isToolVisible("doc_find_replace", "conv-office",
                ClientCapabilityService.DOC_KIND_WRITER));
        assertFalse(caps.isToolVisible("sheet_create_file", "conv-office",
                ClientCapabilityService.DOC_KIND_SHEET));
        assertTrue(caps.isToolVisible("office_replace_text", "conv-office",
                ClientCapabilityService.DOC_KIND_WRITER));

        caps.record("conv-none", "none");
        assertFalse(caps.isToolVisible("doc_open_file", "conv-none",
                ClientCapabilityService.DOC_KIND_WRITER));
    }

    @Test
    @DisplayName("纯后端工具（非 doc_/sheet_/slide_/office_ 前缀）不受任何影响")
    void plainBackendToolsAreNeverTrimmed() {
        for (String kind : new String[]{null, ClientCapabilityService.DOC_KIND_WRITER,
                ClientCapabilityService.DOC_KIND_SHEET, ClientCapabilityService.DOC_KIND_SLIDE}) {
            assertTrue(caps.isToolVisible("write_docx", "conv", kind), "kind=" + kind);
            assertTrue(caps.isToolVisible("extract_file_text", "conv", kind), "kind=" + kind);
            assertTrue(caps.isToolVisible("todo_write", "conv", kind), "kind=" + kind);
        }
    }

    @Test
    @DisplayName("单参重载 = 不裁剪（存量调用方行为一字不变）")
    void singleArgOverloadStaysFullSet() {
        assertTrue(caps.isToolVisible("doc_find_replace", "conv"));
        assertTrue(caps.isToolVisible("sheet_write_cells", "conv"));
        assertTrue(caps.isToolVisible("slide_add_page", "conv"));
    }

    @Test
    @DisplayName("文档类型判据三分：fileType 优先，缺失退回文件名后缀")
    void docKindJudgeIsSharedWithContextAssembler() {
        assertEquals("doc", ContextAssemblerService.lowaDocKind(item("合同.docx", "docx")));
        assertEquals("doc", ContextAssemblerService.lowaDocKind(item("合同.docx", null)));
        assertEquals("sheet", ContextAssemblerService.lowaDocKind(item("台账.xlsx", null)));
        assertEquals("sheet", ContextAssemblerService.lowaDocKind(item("台账.et", null)));
        assertEquals("slide", ContextAssemblerService.lowaDocKind(item("路演.pptx", null)));
        assertEquals("text", ContextAssemblerService.lowaDocKind(item("笔记.md", null)));
        assertEquals("doc", ContextAssemblerService.lowaDocKind(item("没有后缀", null)));
    }

    @Test
    @DisplayName("按文件名判类型（doc_open_file 中途切文档时编排器用的那条路）")
    void docKindByFileName() {
        assertEquals("doc", ContextAssemblerService.lowaDocKindOf(null, "协议.docx"));
        assertEquals("sheet", ContextAssemblerService.lowaDocKindOf(null, "明细.XLSX"));
        assertEquals("slide", ContextAssemblerService.lowaDocKindOf(null, "汇报.ppt"));
        assertEquals("doc", ContextAssemblerService.lowaDocKindOf("docx", "随便.xlsx"));
    }
}
