// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.FileTagService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * v0.49.0 真机 BUG-03：回收站批量彻底删除时，先删的父文件夹会级联带走子行，
 * 紧接着对子行的请求此前在 checkFileInProject 就抛「文件不存在」（全站 HTTP 恒 200 + code:1，
 * 前端等的 404 永远不来）→ 100/104 失败、重试次次失败、回收站永远清不空。
 * 记录已经不在 = 彻底删除的目标已经达成，必须回成功；越权防护对仍存在的行照旧生效。
 */
@ExtendWith(MockitoExtension.class)
class ProjectFileControllerPermDeleteTest {

    @Mock private ProjectFileService projectFileService;
    @Mock private ProjectMemberService projectMemberService;
    @Mock private FileTagService fileTagService;
    @Mock private com.checkba.service.quota.StageQuotaService stageQuotaService;

    @InjectMocks private ProjectFileController controller;

    private void member(MockedStatic<AuthController> auth) {
        auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
        when(projectMemberService.hasReadPermission(1L, 1L)).thenReturn(true);
        when(projectMemberService.isClient(1L, 1L)).thenReturn(false);
        when(projectMemberService.hasWritePermission(1L, 1L)).thenReturn(true);
    }

    @Test
    void permDeleteOfAlreadyGoneRecordReportsSuccess() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            member(auth);
            lenient().when(projectFileService.getFile(42L))
                    .thenThrow(new IllegalArgumentException("文件不存在: 42"));
            when(projectFileService.findFile(42L)).thenReturn(Optional.empty());

            Map<String, Object> r = controller.permDelete(1L, 42L, "sess");
            assertEquals(0, r.get("code"), "记录已经不在 = 彻底删除已达成，不能回失败: " + r);
        }
    }

    @Test
    void permDeleteStillRejectsFileFromAnotherProject() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            member(auth);
            ProjectFile foreign = new ProjectFile();
            foreign.setId(50L);
            foreign.setProjectId(999L);
            lenient().when(projectFileService.getFile(50L)).thenReturn(foreign);
            lenient().when(projectFileService.findFile(50L)).thenReturn(Optional.of(foreign));

            assertThrows(IllegalArgumentException.class, () -> controller.permDelete(1L, 50L, "sess"));
            verify(projectFileService, never()).permDelete(anyLong(), anyLong());
        }
    }
}
