package com.checkba.controller;

import com.checkba.model.entity.ProjectTask;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.task.ProjectTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskController(/api/tasks) 鉴权与归属校验（dev-board #49）：
 * - 未登录一律拒（"未登录"，由 GlobalExceptionHandler 转 code=4010）；
 * - 写操作走写权限闸（hasWritePermission 且非 CLIENT），与 ProjectOverviewController.requireWrite 同语义；
 * - PUT/DELETE 先按任务 id 查出其 projectId 再判权限——防止用自己有权限的路径操作别人项目的任务（IDOR）。
 */
@ExtendWith(MockitoExtension.class)
class TaskControllerTest {

    @Mock private ProjectTaskService taskService;
    @Mock private ProjectMemberService projectMemberService;

    @InjectMocks private TaskController controller;

    private ProjectTask stubTask(Long id, Long projectId) {
        ProjectTask t = new ProjectTask();
        t.setId(id);
        t.setUid("uid-" + id);
        t.setProjectId(projectId);
        t.setTitle("任务");
        t.setDueDate(LocalDate.of(2026, 9, 1));
        t.setStatus("OPEN");
        t.setSource("user");
        return t;
    }

    @Test
    void createRejectsAnonymous() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);
            Map<String, Object> body = Map.of("projectId", 7, "title", "任务", "dueDate", "2026-09-01");

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.create(body, null));
            assertEquals("未登录", e.getMessage());
            verify(taskService, never()).createTask(any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void createRejectsWithoutWritePermission() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasWritePermission(7L, 1L)).thenReturn(false);
            Map<String, Object> body = Map.of("projectId", 7, "title", "任务", "dueDate", "2026-09-01");

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.create(body, "sess"));
            assertEquals("无权修改该项目", e.getMessage());
            verify(taskService, never()).createTask(any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void createRejectsClient() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasWritePermission(7L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(7L, 1L)).thenReturn(true);
            Map<String, Object> body = Map.of("projectId", 7, "title", "任务", "dueDate", "2026-09-01");

            assertThrows(IllegalArgumentException.class, () -> controller.create(body, "sess"));
            verify(taskService, never()).createTask(any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void createSucceedsAndReturnsEnvelope() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasWritePermission(7L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(7L, 1L)).thenReturn(false);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("projectId", 7);
            body.put("fileId", 42);
            body.put("title", "起诉状截止");
            body.put("dueDate", "2026-09-01");
            body.put("dueTime", "09:30");

            ProjectTask created = stubTask(100L, 7L);
            when(taskService.createTask(eq(7L), eq(42L), eq("起诉状截止"),
                    eq(LocalDate.of(2026, 9, 1)), eq(LocalTime.of(9, 30)), eq(1L)))
                    .thenReturn(created);

            Map<String, Object> resp = controller.create(body, "sess").getBody();
            assertNotNull(resp);
            assertEquals(0, resp.get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            assertEquals(100L, data.get("id"));
            assertEquals("user", data.get("source"));
            assertEquals("OPEN", data.get("status"));
        }
    }

    @Test
    void updateRejectsWhenCallerLacksWriteOnTasksProject() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            when(taskService.getTask(100L)).thenReturn(stubTask(100L, 7L));
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasWritePermission(7L, 1L)).thenReturn(false);

            Map<String, Object> body = Map.of("title", "改标题");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.update(100L, body, "sess"));
            assertEquals("无权修改该项目", e.getMessage());
            verify(taskService, never()).updateTask(anyLong(), any());
        }
    }

    @Test
    void updateSucceedsAndReturnsEnvelope() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            when(taskService.getTask(100L)).thenReturn(stubTask(100L, 7L));
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasWritePermission(7L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(7L, 1L)).thenReturn(false);

            Map<String, Object> body = Map.of("status", "DONE");
            ProjectTask updated = stubTask(100L, 7L);
            updated.setStatus("DONE");
            when(taskService.updateTask(100L, body)).thenReturn(updated);

            Map<String, Object> resp = controller.update(100L, body, "sess").getBody();
            assertEquals(0, resp.get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            assertEquals("DONE", data.get("status"));
        }
    }

    @Test
    void deleteRejectsWhenCallerLacksWriteOnTasksProject() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            when(taskService.getTask(100L)).thenReturn(stubTask(100L, 7L));
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasWritePermission(7L, 1L)).thenReturn(false);

            assertThrows(IllegalArgumentException.class, () -> controller.delete(100L, "sess"));
            verify(taskService, never()).deleteTask(anyLong());
        }
    }

    @Test
    void deleteSucceeds() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            when(taskService.getTask(100L)).thenReturn(stubTask(100L, 7L));
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasWritePermission(7L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(7L, 1L)).thenReturn(false);

            Map<String, Object> resp = controller.delete(100L, "sess").getBody();
            assertEquals(0, resp.get("code"));
            verify(taskService).deleteTask(100L);
        }
    }
}
