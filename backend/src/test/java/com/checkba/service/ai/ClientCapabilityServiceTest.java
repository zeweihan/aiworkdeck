// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.ai.tools.ToolMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话能力闸的两层：工具名前缀（既有契约）与 {@code @ToolMeta.requiresHost} 声明
 *（dev-board#799）。前者原本就在，后者是叠加上去、只会收窄的第二层。
 *
 * <p>ref_* 参考来源工具的可见性（dev-board#717）。
 *
 * <p>ref_ 不属于 doc_/sheet_/slide_/office_ 任何一个前缀，按原规则会落进「纯后端工具、所有会话可见」那一档。
 * 但它们只为 Office/WPS 任务窗格服务（LOWA 会话已有项目文件工具），所以要显式收窄成只对 OFFICE 会话可见。
 */
class ClientCapabilityServiceTest {

    private ClientCapabilityService capabilities;

    @BeforeEach
    void setUp() {
        capabilities = new ClientCapabilityService();
        capabilities.record("conv-office", "office");
        capabilities.record("conv-office-excel", "office", "excel");
        capabilities.record("conv-office-ppt", "office", "powerpoint", "wps");
        capabilities.record("conv-lowa", "lowa");
        capabilities.record("conv-none", "none");
    }

    @Test
    @DisplayName("ref_* 只对 OFFICE 会话可见（三类宿主、两个家族都可见）")
    void refToolsAreVisibleOnlyToOfficeSessions() {
        for (String tool : new String[] {"ref_list", "ref_read", "ref_edit", "ref_open"}) {
            assertTrue(capabilities.isToolVisible(tool, "conv-office"), tool + " 应对 Word 会话可见");
            assertTrue(capabilities.isToolVisible(tool, "conv-office-excel"), tool + " 应对 Excel 会话可见");
            assertTrue(capabilities.isToolVisible(tool, "conv-office-ppt"), tool + " 应对 WPS 演示会话可见");
            assertFalse(capabilities.isToolVisible(tool, "conv-lowa"), tool + " 不应对 LOWA 会话可见");
            assertFalse(capabilities.isToolVisible(tool, "conv-none"), tool + " 不应对 none 会话可见");
            assertFalse(capabilities.isToolVisible(tool, "conv-never-recorded"), "未登记会话按 LOWA 兜底，" + tool + " 不可见");
            assertFalse(capabilities.isToolVisible(tool, null), "无会话语境按 LOWA 兜底，" + tool + " 不可见");
        }
    }

    @Test
    @DisplayName("收窄 ref_* 不影响其他纯后端工具与既有前缀规则")
    void otherToolsKeepTheirVisibility() {
        assertTrue(capabilities.isToolVisible("read_file", "conv-lowa"), "纯后端工具对 LOWA 仍可见");
        assertTrue(capabilities.isToolVisible("read_file", "conv-office"), "纯后端工具对 OFFICE 仍可见");
        assertTrue(capabilities.isToolVisible("doc_get_text", "conv-lowa"));
        assertFalse(capabilities.isToolVisible("doc_get_text", "conv-office"));
        assertTrue(capabilities.isToolVisible("office_get_text", "conv-office"));
        assertFalse(capabilities.isToolVisible("office_get_text", "conv-office-excel"));
        assertFalse(capabilities.isToolVisible(null, "conv-office"));
        // 前缀必须是 ref_ 本身：名字里含 ref 的其他工具不受影响
        assertTrue(capabilities.isToolVisible("refresh_index", "conv-lowa"));
    }

    // ==================== 声明层 requiresHost（dev-board#799 / 审计 A9） ====================

    /** 三档能力 × 三类 Office 宿主 × 四种活跃文档类型（含"判不出来"的 null）。 */
    private static final String[] CONVS =
            {"conv-lowa", "conv-office", "conv-office-excel", "conv-office-ppt", "conv-none"};
    private static final String[] KINDS = {null, ClientCapabilityService.DOC_KIND_WRITER,
            ClientCapabilityService.DOC_KIND_SHEET, ClientCapabilityService.DOC_KIND_SLIDE};

    @Test
    @DisplayName("声明 LOWA 的无前缀工具：只对 LOWA 会话可见，三类 Office 宿主与 none 一律不可见")
    void lowaDeclarationHidesTheToolFromEveryNonLowaSession() {
        // 病灶（审计 A9）：能力闸只认五个前缀，pptx_generate 这类只对桌面前端有意义的工具
        // 在 Word/Excel/PowerPoint 任务窗格会话里照样下发。它发完 ppt_config 就返回
        // 「等待用户操作...」，而那个配置界面在插件里根本不存在——模型停在那里等一个
        // 永远不会来的确认，整轮空转，最终还告诉用户「请在弹出的界面里选择」。
        capabilities.declareHostRequirement("pptx_generate", ToolMeta.Host.LOWA);

        for (String kind : KINDS) {
            assertTrue(capabilities.isToolVisible("pptx_generate", "conv-lowa", kind),
                    "LOWA 会话必须看得见（活跃文档类型=" + kind + "）");
            for (String conv : new String[]{"conv-office", "conv-office-excel", "conv-office-ppt", "conv-none"}) {
                assertFalse(capabilities.isToolVisible("pptx_generate", conv, kind),
                        conv + " 不该看见声明了 LOWA 的工具（活跃文档类型=" + kind + "）");
            }
        }
        // 未登记会话按 LOWA 兜底（存量主前端不上送 clientCapability），可见
        assertTrue(capabilities.isToolVisible("pptx_generate", "conv-never-recorded", null));
        assertTrue(capabilities.isToolVisible("pptx_generate", null, null));
    }

    @Test
    @DisplayName("声明 OFFICE 的工具反过来只对任务窗格会话可见（三类宿主都算）")
    void officeDeclarationIsTheMirrorImage() {
        capabilities.declareHostRequirement("probe_office_only", ToolMeta.Host.OFFICE);

        assertTrue(capabilities.isToolVisible("probe_office_only", "conv-office"));
        assertTrue(capabilities.isToolVisible("probe_office_only", "conv-office-excel"));
        assertTrue(capabilities.isToolVisible("probe_office_only", "conv-office-ppt"));
        assertFalse(capabilities.isToolVisible("probe_office_only", "conv-lowa"));
        assertFalse(capabilities.isToolVisible("probe_office_only", "conv-none"));
    }

    @Test
    @DisplayName("没声明过的工具行为逐字不变：三档 × 三宿主 × 四种活跃文档类型全都放行")
    void undeclaredToolsKeepTodaysBehaviourExactly() {
        for (String conv : CONVS) {
            for (String kind : KINDS) {
                assertTrue(capabilities.isToolVisible("extract_file_text", conv, kind),
                        "纯后端工具没声明宿主就该照旧可见：" + conv + "/" + kind);
                assertTrue(capabilities.isToolVisible("write_docx", conv, kind), conv + "/" + kind);
                assertTrue(capabilities.isToolVisible("todo_write", conv, kind), conv + "/" + kind);
            }
        }
        assertEquals(ToolMeta.Host.NONE, capabilities.hostRequirementOf("extract_file_text"));
        assertEquals(ToolMeta.Host.NONE, capabilities.hostRequirementOf(null));
    }

    @Test
    @DisplayName("声明层只收窄不放宽：给 office_ 工具声明 LOWA 不会让它在 LOWA 会话里冒出来")
    void theDeclarationCanOnlyNarrowNeverWiden() {
        // 前缀链是既有公开契约，声明层叠在它之上——两层都通过才可见。
        capabilities.declareHostRequirement("office_get_text", ToolMeta.Host.LOWA);
        assertFalse(capabilities.isToolVisible("office_get_text", "conv-lowa"),
                "前缀说它要 Office、声明说它要 LOWA，两个条件不可能同时满足，结论只能是不可见");
        assertFalse(capabilities.isToolVisible("office_get_text", "conv-office"));

        // 反过来给 doc_ 工具声明 OFFICE 同理
        capabilities.declareHostRequirement("doc_get_text", ToolMeta.Host.OFFICE);
        assertFalse(capabilities.isToolVisible("doc_get_text", "conv-lowa"));
        assertFalse(capabilities.isToolVisible("doc_get_text", "conv-office"));
    }

    @Test
    @DisplayName("NONE / null 声明视同没声明，不入表")
    void noneDeclarationIsANoOp() {
        capabilities.declareHostRequirement("probe_none", ToolMeta.Host.NONE);
        capabilities.declareHostRequirement("probe_null", null);
        capabilities.declareHostRequirement(null, ToolMeta.Host.LOWA);
        capabilities.declareHostRequirement("  ", ToolMeta.Host.LOWA);

        for (String conv : CONVS) {
            assertTrue(capabilities.isToolVisible("probe_none", conv));
            assertTrue(capabilities.isToolVisible("probe_null", conv));
        }
    }
}
