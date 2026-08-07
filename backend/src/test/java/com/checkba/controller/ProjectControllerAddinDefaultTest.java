package com.checkba.controller;

import com.checkba.model.dto.ProjectCardDTO;
import com.checkba.model.dto.ProjectCreateRequest;
import com.checkba.model.entity.Project;
import com.checkba.service.LocalProjectService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectService;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 锁定「插件临时项目」端点的幂等语义：
 * 项目是租户隔离维度不能为空，但插件用户不该被逼着先建项目——
 * 一个都没有时建一个，已有任一项目时绝不再建（否则每次开窗格都多一个空项目）。
 */
@ExtendWith(MockitoExtension.class)
class ProjectControllerAddinDefaultTest {

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
    void createsOnlyOneProjectAcrossRepeatedCalls() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);

            Project created = new Project();
            created.setId(42L);
            created.setName("插件临时项目");
            when(projectService.createProject(any(ProjectCreateRequest.class), eq(1L))).thenReturn(created);

            ProjectCardDTO card = new ProjectCardDTO();
            card.setId(42L);
            card.setName("插件临时项目");
            // 第一次调用时账号还没有项目；建好之后列表里就有了
            when(projectService.getUserProjectCardDTOs(1L)).thenReturn(List.of(), List.of(card));

            Map<String, Object> first = controller.ensureAddinDefaultProject("sess");
            assertEquals(true, first.get("created"));
            @SuppressWarnings("unchecked")
            Map<String, Object> project = (Map<String, Object>) first.get("project");
            assertEquals(42L, project.get("id"));
            assertEquals("插件临时项目", project.get("name"));

            Map<String, Object> second = controller.ensureAddinDefaultProject("sess");
            assertEquals(false, second.get("created"));
            assertNull(second.get("project"));

            // 幂等：两次调用只建了一个项目，且是空白项目
            ArgumentCaptor<ProjectCreateRequest> captor = ArgumentCaptor.forClass(ProjectCreateRequest.class);
            verify(projectService, times(1)).createProject(captor.capture(), eq(1L));
            assertEquals("BLANK", captor.getValue().getProjectType());
            assertEquals("插件临时项目", captor.getValue().getName());
        }
    }

    @Test
    void existingProjectsAreNeverTouched() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            ProjectCardDTO mine = new ProjectCardDTO();
            mine.setId(3L);
            mine.setName("某并购项目");
            when(projectService.getUserProjectCardDTOs(7L)).thenReturn(List.of(mine));

            Map<String, Object> result = controller.ensureAddinDefaultProject("sess");

            assertEquals(false, result.get("created"));
            assertNull(result.get("project"));
            verify(projectService, never()).createProject(any(), anyLong());
        }
    }

    @Test
    void requiresValidSession() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            assertThrows(IllegalArgumentException.class, () -> controller.ensureAddinDefaultProject(null));
            verify(projectService, never()).createProject(any(), anyLong());
            verify(projectService, never()).getUserProjectCardDTOs(anyLong());
        }
    }

    @Test
    void resultKeysAreStable() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            when(projectService.getUserProjectCardDTOs(9L)).thenReturn(List.of(new ProjectCardDTO()));

            Map<String, Object> result = controller.ensureAddinDefaultProject("sess");

            assertTrue(result.containsKey("created"));
            assertTrue(result.containsKey("project"));
        }
    }
}
