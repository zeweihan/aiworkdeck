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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * 锁定 ProjectFileController 的越权(IDOR)防护：
 * 用户是 URL 中 projectId 的成员，但传入他人项目的 fileId 时必须被拒绝。
 */
@ExtendWith(MockitoExtension.class)
class ProjectFileControllerIdorTest {

    @Mock
    private ProjectFileService projectFileService;
    @Mock
    private ProjectMemberService projectMemberService;
    @Mock
    private FileTagService fileTagService;
    @Mock
    private com.checkba.service.quota.StageQuotaService stageQuotaService;

    @InjectMocks
    private ProjectFileController controller;

    /**
     * 缓存区用量端点吃的是全局 folderId，只验路径上的 projectId 是不够的：
     * 服务层从文件夹自己那一行反查 projectId 再列子项，路径参数根本不参与约束，
     * 于是 A 项目的成员能拿自己的 projectId 枚举出 B 项目任意目录的文件数与总字节。
     */
    @Test
    void stageUsageRejectsFolderFromAnotherProject() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(1L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(1L, 1L)).thenReturn(false);
            // 目标文件夹属于项目 999
            ProjectFile foreign = new ProjectFile();
            foreign.setId(777L);
            foreign.setProjectId(999L);
            when(projectFileService.getFile(777L)).thenReturn(foreign);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.stageUsage(1L, 777L, "sess"));
            verify(stageQuotaService, never()).usage(anyLong());
        }
    }

    @Test
    void stageUsageAllowsFolderWithinSameProject() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(1L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(1L, 1L)).thenReturn(false);
            ProjectFile own = new ProjectFile();
            own.setId(100L);
            own.setProjectId(1L);
            when(projectFileService.getFile(100L)).thenReturn(own);

            controller.stageUsage(1L, 100L, "sess");
            verify(stageQuotaService).usage(100L);
        }
    }

    @Test
    void renameRejectsFileFromAnotherProject() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            // 用户是项目 1 的合法成员
            when(projectMemberService.hasReadPermission(1L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(1L, 1L)).thenReturn(false);
            when(projectMemberService.hasWritePermission(1L, 1L)).thenReturn(true);
            // 但目标文件属于项目 999
            ProjectFile foreign = new ProjectFile();
            foreign.setId(50L);
            foreign.setProjectId(999L);
            when(projectFileService.getFile(50L)).thenReturn(foreign);

            ProjectFileController.RenameRequest req = new ProjectFileController.RenameRequest();
            req.setName("hacked");

            assertThrows(IllegalArgumentException.class,
                    () -> controller.rename(1L, 50L, req, "sess"));
            // 校验失败后不得真正执行重命名
            verify(projectFileService, never()).rename(anyLong(), anyString(), anyLong());
        }
    }

    @Test
    void renameAllowsFileWithinSameProject() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(1L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(1L, 1L)).thenReturn(false);
            when(projectMemberService.hasWritePermission(1L, 1L)).thenReturn(true);
            ProjectFile own = new ProjectFile();
            own.setId(50L);
            own.setProjectId(1L);
            when(projectFileService.getFile(50L)).thenReturn(own);

            ProjectFileController.RenameRequest req = new ProjectFileController.RenameRequest();
            req.setName("ok");

            controller.rename(1L, 50L, req, "sess");
            verify(projectFileService).rename(50L, "ok", 1L);
        }
    }
}
