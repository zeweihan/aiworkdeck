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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * 彻底删除送系统废纸篓（dev-board#1051）的后端两半：
 * GET .../permanent/disk-paths 只在 local-mode 开放；DELETE .../permanent?diskHandled=true
 * 只在 local-mode 认——服务端部署没有桌面壳替它删磁盘，认了就是只删行、字节永远留在服务器上。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFileControllerTrashDiskPathsTest {

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

    private void ownRow(long id) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(1L);
        when(projectFileService.findFile(id)).thenReturn(Optional.of(f));
        when(projectFileService.getFile(id)).thenReturn(f);
    }

    @Test
    void diskHandledIsHonouredInLocalMode() {
        ReflectionTestUtils.setField(controller, "localMode", true);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            member(auth);
            ownRow(42L);
            Map<String, Object> r = controller.permDelete(1L, 42L, true, "sess");
            assertEquals(0, r.get("code"));
            verify(projectFileService).permDelete(42L, 1L, true);
        }
    }

    @Test
    void diskHandledIsIgnoredOutsideLocalMode() {
        ReflectionTestUtils.setField(controller, "localMode", false);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            member(auth);
            ownRow(42L);
            controller.permDelete(1L, 42L, true, "sess");
            verify(projectFileService).permDelete(42L, 1L, false);
        }
    }

    @Test
    void diskPathsReturnsResolvedAbsolutePathsInLocalMode() {
        ReflectionTestUtils.setField(controller, "localMode", true);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            member(auth);
            ownRow(42L);
            when(projectFileService.diskPathsForPurge(42L)).thenReturn(List.of(Path.of("/Users/x/案卷/A")));
            Map<String, Object> r = controller.permDeleteDiskPaths(1L, 42L, "sess");
            assertEquals(0, r.get("code"));
            assertEquals(Map.of("paths", List.of("/Users/x/案卷/A")), r.get("data"));
        }
    }

    @Test
    void diskPathsOfAlreadyGoneRowIsEmptyNotError() {
        ReflectionTestUtils.setField(controller, "localMode", true);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            member(auth);
            when(projectFileService.findFile(42L)).thenReturn(Optional.empty());
            Map<String, Object> r = controller.permDeleteDiskPaths(1L, 42L, "sess");
            assertEquals(Map.of("paths", List.of()), r.get("data"));
            verify(projectFileService, never()).diskPathsForPurge(anyLong());
        }
    }

    @Test
    void diskPathsIsClosedOutsideLocalMode() {
        ReflectionTestUtils.setField(controller, "localMode", false);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            member(auth);
            ownRow(42L);
            assertThrows(IllegalArgumentException.class, () -> controller.permDeleteDiskPaths(1L, 42L, "sess"));
            verify(projectFileService, never()).diskPathsForPurge(anyLong());
        }
    }

    @Test
    void diskPathsRejectsRowFromAnotherProject() {
        ReflectionTestUtils.setField(controller, "localMode", true);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            member(auth);
            ProjectFile foreign = new ProjectFile();
            foreign.setId(50L);
            foreign.setProjectId(999L);
            when(projectFileService.findFile(50L)).thenReturn(Optional.of(foreign));
            when(projectFileService.getFile(50L)).thenReturn(foreign);
            assertThrows(IllegalArgumentException.class, () -> controller.permDeleteDiskPaths(1L, 50L, "sess"));
            verify(projectFileService, never()).diskPathsForPurge(anyLong());
        }
    }
}
