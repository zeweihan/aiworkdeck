package com.checkba.controller;

import com.checkba.model.entity.Project;
import com.checkba.service.LocalProjectService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectService;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 锁定 open-local 的部署模式闸门：
 * - 默认（云端多人部署）：拒绝，否则任意租户能把 /etc 或他人数据目录挂成项目根；
 * - 桌面单机（security.local-folder-projects.enabled=true）：照常放行。
 */
@ExtendWith(MockitoExtension.class)
class ProjectControllerOpenLocalGateTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private ProjectMemberService projectMemberService;
    @Mock
    private LocalProjectService localProjectService;
    @Mock
    private ProjectStorageResolver storageResolver;

    @InjectMocks
    private ProjectController controller;

    @Test
    void cloudDefaultRejectsArbitraryLocalRoot() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.openLocalFolder(Map.of("localRoot", "/etc"), "sess"));
            verify(localProjectService, never())
                    .openLocalFolder(anyString(), anyBoolean(), any(), any(), anyLong());
        }
    }

    @Test
    void desktopModeAllowsOpeningLocalFolder() {
        ReflectionTestUtils.setField(controller, "localFolderProjectsEnabled", true);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            Project p = new Project();
            p.setId(7L);
            p.setName("案卷");
            when(localProjectService.openLocalFolder("/Users/me/案卷", false, null, null, 1L))
                    .thenReturn(new LocalProjectService.OpenLocalResult(p, false, null, 3, false, 0, false));

            Map<String, Object> result = controller.openLocalFolder(
                    Map.of("localRoot", "/Users/me/案卷"), "sess");

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertEquals(7L, data.get("projectId"));
        }
    }

    /**
     * truncatedCount 是前端截断提示（「本次导入已截断，N 项未纳入」）的数据来源，
     * 必须原样透传到响应体，不能只有 truncated 布尔值（dev-board#107 单元 F1 复核）。
     */
    @Test
    void truncatedImportSurfacesTruncatedCountInResponse() {
        ReflectionTestUtils.setField(controller, "localFolderProjectsEnabled", true);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            Project p = new Project();
            p.setId(9L);
            p.setName("大项目");
            when(localProjectService.openLocalFolder("/Users/me/大项目", false, null, null, 1L))
                    .thenReturn(new LocalProjectService.OpenLocalResult(p, false, null, 50, true, 7, false));

            Map<String, Object> result = controller.openLocalFolder(
                    Map.of("localRoot", "/Users/me/大项目"), "sess");

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            assertEquals(true, data.get("truncated"));
            assertEquals(7, data.get("truncatedCount"));
        }
    }
}
