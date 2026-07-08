package com.checkba.controller;

import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectService;
import com.checkba.service.TagService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * 锁定 E4 批次的越权校验模式：读端点必须校验调用者是项目成员。
 * 覆盖 ProjectController.getProject 与 TagController（构造器注入，手动装配）。
 */
class E4IdorAuthTest {

    @Test
    void projectGetRejectsNonMember() {
        ProjectService ps = mock(ProjectService.class);
        ProjectMemberService pms = mock(ProjectMemberService.class);
        ProjectController c = new ProjectController(ps, pms);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);
            when(pms.hasReadPermission(42L, 7L)).thenReturn(false);
            assertThrows(IllegalArgumentException.class, () -> c.getProject(42L, "s"));
        }
    }

    @Test
    void tagListRejectsNonMember() {
        TagService ts = mock(TagService.class);
        ProjectMemberService pms = mock(ProjectMemberService.class);
        TagController c = new TagController(ts, pms);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);
            when(pms.hasReadPermission(42L, 7L)).thenReturn(false);
            assertThrows(IllegalArgumentException.class, () -> c.getProjectTags(42L, "s"));
        }
    }

    @Test
    void tagListAllowsMember() {
        TagService ts = mock(TagService.class);
        ProjectMemberService pms = mock(ProjectMemberService.class);
        TagController c = new TagController(ts, pms);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);
            when(pms.hasReadPermission(42L, 7L)).thenReturn(true);
            when(ts.getProjectTags(42L)).thenReturn(List.of());
            assertNotNull(c.getProjectTags(42L, "s"));
        }
    }
}
