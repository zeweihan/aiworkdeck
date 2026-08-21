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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 锁定创建/移动时的父目录校验：parentId 必须是当前项目内未删除的文件夹。
 * 缺这道闸时接口返回 200，但新节点挂在已删除（或他人项目的）父目录下，
 * getFileTree/getFilesByParent 永远拼不出它的路径，节点在所有树视图里凭空消失。
 */
@ExtendWith(MockitoExtension.class)
class ProjectFileControllerParentValidationTest {

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

    private void allowWrite() {
        when(projectMemberService.hasReadPermission(1L, 1L)).thenReturn(true);
        when(projectMemberService.isClient(1L, 1L)).thenReturn(false);
        when(projectMemberService.hasWritePermission(1L, 1L)).thenReturn(true);
    }

    private ProjectFile folder(Long id, Long projectId, boolean deleted) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(projectId);
        f.setIsFolder(true);
        f.setIsDeleted(deleted);
        return f;
    }

    /** 对话框打开后父目录被另一端软删除：这是真实可达的触发路径 */
    @Test
    void createFolderRejectsSoftDeletedParent() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            allowWrite();
            when(projectFileService.getFile(100L)).thenReturn(folder(100L, 1L, true));

            ProjectFileController.CreateFolderRequest req = new ProjectFileController.CreateFolderRequest();
            req.setParentId(100L);
            req.setName("x");

            assertThrows(IllegalArgumentException.class, () -> controller.createFolder(1L, req, "sess"));
            verify(projectFileService, never()).createFolder(anyLong(), any(), anyString(), anyLong());
        }
    }

    /** 父目录属于别的项目：新节点在本项目树里永远拼不出路径 */
    @Test
    void createFolderRejectsParentFromAnotherProject() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            allowWrite();
            when(projectFileService.getFile(200L)).thenReturn(folder(200L, 999L, false));

            ProjectFileController.CreateFolderRequest req = new ProjectFileController.CreateFolderRequest();
            req.setParentId(200L);
            req.setName("x");

            assertThrows(IllegalArgumentException.class, () -> controller.createFolder(1L, req, "sess"));
            verify(projectFileService, never()).createFolder(anyLong(), any(), anyString(), anyLong());
        }
    }

    @Test
    void createFileRejectsSoftDeletedParent() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            allowWrite();
            when(projectFileService.getFile(100L)).thenReturn(folder(100L, 1L, true));

            ProjectFileController.CreateFileRequest req = new ProjectFileController.CreateFileRequest();
            req.setParentId(100L);
            req.setName("a.docx");

            assertThrows(IllegalArgumentException.class, () -> controller.createFile(1L, req, "sess"));
            verify(projectFileService, never()).createFile(
                    anyLong(), any(), anyString(), any(), any(), any(), any(), anyLong());
        }
    }

    /** 拖拽目标在提交前被删除 */
    @Test
    void moveRejectsSoftDeletedParent() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            allowWrite();
            ProjectFile self = new ProjectFile();
            self.setId(50L);
            self.setProjectId(1L);
            self.setIsFolder(false);
            when(projectFileService.getFile(50L)).thenReturn(self);
            when(projectFileService.getFile(100L)).thenReturn(folder(100L, 1L, true));

            ProjectFileController.MoveRequest req = new ProjectFileController.MoveRequest();
            req.setParentId(100L);

            assertThrows(IllegalArgumentException.class, () -> controller.move(1L, 50L, req, "sess"));
            verify(projectFileService, never()).move(anyLong(), any(), any(), anyLong());
        }
    }

    /** 父目录是个文件而不是文件夹，同样拼不出路径 */
    @Test
    void createFolderRejectsNonFolderParent() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            allowWrite();
            ProjectFile notFolder = folder(300L, 1L, false);
            notFolder.setIsFolder(false);
            when(projectFileService.getFile(300L)).thenReturn(notFolder);

            ProjectFileController.CreateFolderRequest req = new ProjectFileController.CreateFolderRequest();
            req.setParentId(300L);
            req.setName("x");

            assertThrows(IllegalArgumentException.class, () -> controller.createFolder(1L, req, "sess"));
            verify(projectFileService, never()).createFolder(anyLong(), any(), anyString(), anyLong());
        }
    }

    /** 根目录（parentId 为 null）与正常父目录不受影响 */
    @Test
    void createFolderAllowsRootAndLiveParent() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            allowWrite();
            when(projectFileService.getFile(100L)).thenReturn(folder(100L, 1L, false));

            ProjectFileController.CreateFolderRequest root = new ProjectFileController.CreateFolderRequest();
            root.setName("root-child");
            controller.createFolder(1L, root, "sess");
            verify(projectFileService).createFolder(1L, null, "root-child", 1L);

            ProjectFileController.CreateFolderRequest nested = new ProjectFileController.CreateFolderRequest();
            nested.setParentId(100L);
            nested.setName("nested");
            controller.createFolder(1L, nested, "sess");
            verify(projectFileService).createFolder(1L, 100L, "nested", 1L);
        }
    }
}
