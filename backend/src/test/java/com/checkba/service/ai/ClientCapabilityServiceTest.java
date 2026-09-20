// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ref_* 参考来源工具的可见性（dev-board#717）。
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
}
