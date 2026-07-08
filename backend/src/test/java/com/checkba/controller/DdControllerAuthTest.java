package com.checkba.controller;

import com.checkba.service.DdService;
import com.checkba.service.ProjectMemberService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * 锁定 DdController 越权校验：登录用户必须是尽调数据所属项目的成员才能读写删。
 */
@ExtendWith(MockitoExtension.class)
class DdControllerAuthTest {

    @Mock
    private DdService ddService;
    @Mock
    private ProjectMemberService projectMemberService;

    @InjectMocks
    private DdController controller;

    @Test
    void getRequestsRejectsNonMember() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(false);
            assertThrows(IllegalArgumentException.class, () -> controller.getRequests(42L, "sess"));
        }
    }

    @Test
    void deleteRequestRejectsNonMemberOfOwningProject() {
        when(ddService.getProjectIdByRequestId(99L)).thenReturn(42L);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(false);
            assertThrows(IllegalArgumentException.class, () -> controller.deleteRequest(99L, "sess"));
        }
    }

    @Test
    void getRequestsAllowsMember() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(true);
            when(ddService.getRequests(42L)).thenReturn(List.of());
            assertNotNull(controller.getRequests(42L, "sess"));
        }
    }
}
