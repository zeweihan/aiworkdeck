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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * import-local 的两道闸（dev-board#409）：
 * - 部署模式：让调用方指名服务器磁盘上的绝对路径，只有单机模式才成立；
 * - 归属：parentId 是全局 id，落在他人项目上必须被拒（同本控制器其余接口）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFileControllerImportLocalTest {

    @Mock private ProjectFileService projectFileService;
    @Mock private ProjectMemberService projectMemberService;
    @Mock private FileTagService fileTagService;
    @Mock private com.checkba.service.quota.StageQuotaService stageQuotaService;
    @Mock private com.checkba.service.ai.ProjectRagService projectRagService;
    @Mock private com.checkba.service.ai.AutoTaggingService autoTaggingService;

    @InjectMocks
    private ProjectFileController controller;

    private ProjectFileController.ImportLocalRequest req(String path, Long parentId) {
        ProjectFileController.ImportLocalRequest r = new ProjectFileController.ImportLocalRequest();
        r.setSourcePath(path);
        r.setParentId(parentId);
        return r;
    }

    private void allowMember() {
        when(projectMemberService.hasReadPermission(1L, 1L)).thenReturn(true);
        when(projectMemberService.isClient(1L, 1L)).thenReturn(false);
        when(projectMemberService.hasWritePermission(1L, 1L)).thenReturn(true);
    }

    @Test
    void rejectedWhenNotLocalMode() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.importLocal(1L, req("/Users/me/证据.pdf", null), "sess"));
            verify(projectFileService, never()).importLocalFile(anyLong(), any(), anyString(), anyLong());
        }
    }

    @Test
    void rejectsParentFolderFromAnotherProject() {
        ReflectionTestUtils.setField(controller, "localMode", true);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            allowMember();
            ProjectFile foreign = new ProjectFile();
            foreign.setId(777L);
            foreign.setProjectId(999L);
            foreign.setIsFolder(true);
            when(projectFileService.getFile(777L)).thenReturn(foreign);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.importLocal(1L, req("/Users/me/证据.pdf", 777L), "sess"));
            verify(projectFileService, never()).importLocalFile(anyLong(), any(), anyString(), anyLong());
        }
    }

    @Test
    void rejectsReadOnlyMember() {
        ReflectionTestUtils.setField(controller, "localMode", true);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(1L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(1L, 1L)).thenReturn(false);
            when(projectMemberService.hasWritePermission(1L, 1L)).thenReturn(false);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.importLocal(1L, req("/Users/me/证据.pdf", null), "sess"));
            verify(projectFileService, never()).importLocalFile(anyLong(), any(), anyString(), anyLong());
        }
    }

    @Test
    void localModeImportsAndReturnsRow() {
        ReflectionTestUtils.setField(controller, "localMode", true);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            allowMember();
            ProjectFile created = new ProjectFile();
            created.setId(42L);
            created.setProjectId(1L);
            created.setName("证据.pdf");
            created.setFilePath("projects/1/证据.pdf");
            when(projectFileService.importLocalFile(1L, null, "/Users/me/证据.pdf", 1L)).thenReturn(created);

            ProjectFile out = controller.importLocal(1L, req("/Users/me/证据.pdf", null), "sess");

            assertEquals(42L, out.getId());
            verify(projectFileService).importLocalFile(1L, null, "/Users/me/证据.pdf", 1L);
        }
    }
}
