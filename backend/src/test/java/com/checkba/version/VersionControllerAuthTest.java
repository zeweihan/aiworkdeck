package com.checkba.version;

import com.checkba.controller.AuthController;
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
 * 锁定版本记录接口的权限：CLIENT 角色（客户）不得看到版本历史——
 * 里面有律师的内部草稿。未登录同样拒绝。
 *
 * 注：ProjectMemberService.hasReadPermission/isClient 的真实签名是
 * (projectId, userId)（见 ProjectFileController#checkFileTreeAccess 等既有用法），
 * 此处 mock 桩按该顺序传参：projectId=7L, userId=1L。
 */
@ExtendWith(MockitoExtension.class)
class VersionControllerAuthTest {

    @Mock
    private ProjectRepoService repoService;
    @Mock
    private WorkSessionService sessionService;
    @Mock
    private ProjectMemberService projectMemberService;

    @InjectMocks
    private VersionController controller;

    @Test
    void clientRoleCannotSeeTimeline() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(7L, 1L)).thenReturn(true);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.timeline(7L, 50, "sess"));
            verify(repoService, never()).log(anyLong(), anyString(), anyInt());
        }
    }

    @Test
    void nonMemberCannotSeeTimeline() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(false);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.timeline(7L, 50, "sess"));
            verify(repoService, never()).log(anyLong(), anyString(), anyInt());
        }
    }

    @Test
    void anonymousCannotSeeTimeline() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.timeline(7L, 50, null));
            verify(repoService, never()).log(anyLong(), anyString(), anyInt());
        }
    }

    @Test
    void memberCanSeeTimeline() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(7L, 1L)).thenReturn(false);
            when(repoService.log(7L, "HEAD", 50)).thenReturn(java.util.List.of());

            controller.timeline(7L, 50, "sess");

            verify(repoService).log(7L, "HEAD", 50);
        }
    }
}
