// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.model.entity.ProjectTask;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.task.ProjectTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** 模拟 ProjectTaskService.toResponseMap 的字段集（真实现在 ProjectTaskServiceTest 里覆盖）。 */
    private Map<String, Object> responseMapOf(ProjectTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("uid", t.getUid());
        m.put("projectId", t.getProjectId());
        m.put("fileId", t.getFileId());
        m.put("fileName", null);
        m.put("title", t.getTitle());
        m.put("dueDate", t.getDueDate());
        m.put("dueTime", t.getDueTime());
        m.put("status", t.getStatus());
        m.put("source", t.getSource());
        return m;
    }

    @Test
    void createRejectsAnonymous() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);
            Map<String, Object> body = Map.of("projectId", 7, "title", "任务", "dueDate", "2026-09-01");

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.create(body, null));
            assertEquals("未登录", e.getMessage());
            verify(taskService, never()).createTask(any(), any(ProjectTaskService.TaskDraft.class), any());
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
            verify(taskService, never()).createTask(any(), any(ProjectTaskService.TaskDraft.class), any());
        }
    }

    /**
     * title 是裸强转 (String)。传个数字进来抛的是 ClassCastException——不是
     * IllegalArgumentException，绕过 GlobalExceptionHandler 那条能给出人话的分支，
     * 落到兜底 Exception 处理器变成「服务器内部错误」，还给一条普通的参数校验失败
     * 打了 ERROR 级堆栈。调用方看不出真正的原因只是 title 类型不对。
     */
    @Test
    void createRejectsNonStringTitleWithUserFacingError() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasWritePermission(7L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(7L, 1L)).thenReturn(false);
            Map<String, Object> body = Map.of("projectId", 7, "title", 123, "dueDate", "2026-09-01");

            assertThrows(IllegalArgumentException.class, () -> controller.create(body, "sess"));
            verify(taskService, never()).createTask(any(), any(ProjectTaskService.TaskDraft.class), any());
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
            verify(taskService, never()).createTask(any(), any(ProjectTaskService.TaskDraft.class), any());
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
            // 旧 fileId 等价于 fileIds:[fileId]；新字段缺席时 draft 里为 null（默认值由服务层落）
            when(taskService.createTask(eq(7L), eq(new ProjectTaskService.TaskDraft("起诉状截止",
                    LocalDate.of(2026, 9, 1), LocalTime.of(9, 30), null, null, null, null, null, List.of(42L))), eq(1L)))
                    .thenReturn(created);
            when(taskService.toResponseMap(created)).thenReturn(responseMapOf(created));

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
            when(taskService.toResponseMap(updated)).thenReturn(responseMapOf(updated));

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

    // ==================== dev-board#895 ====================

    private void allowWrite() {
        when(projectMemberService.hasWritePermission(7L, 1L)).thenReturn(true);
        when(projectMemberService.isClient(7L, 1L)).thenReturn(false);
    }

    @Test
    void createPassesNewFieldsAndFileIdsToService() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            allowWrite();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("projectId", 7);
            body.put("title", "开庭");
            body.put("dueDate", "2026-10-12");
            body.put("type", "HEARING");
            body.put("priority", "HIGH");
            body.put("notes", "带原件");
            body.put("assigneeId", 2);
            body.put("remindBefore", 1440);
            body.put("fileIds", List.of(2391, "2400"));
            body.put("fileId", 9); // 与 fileIds 同传时以 fileIds 为准

            ProjectTask created = stubTask(100L, 7L);
            ArgumentCaptor<ProjectTaskService.TaskDraft> captor = ArgumentCaptor.forClass(ProjectTaskService.TaskDraft.class);
            when(taskService.createTask(eq(7L), captor.capture(), eq(1L))).thenReturn(created);
            when(taskService.toResponseMap(created)).thenReturn(responseMapOf(created));

            assertEquals(0, controller.create(body, "sess").getBody().get("code"));
            ProjectTaskService.TaskDraft d = captor.getValue();
            assertEquals("HEARING", d.type());
            assertEquals("HIGH", d.priority());
            assertEquals("带原件", d.notes());
            assertEquals(2L, d.assigneeId());
            assertEquals(1440, d.remindBefore());
            assertEquals(List.of(2391L, 2400L), d.fileIds());
            assertEquals(null, d.dueTime());
        }
    }

    @Test
    void createRejectsMalformedNewFieldsBeforeService() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            allowWrite();

            Map<String, Object> notArray = new LinkedHashMap<>();
            notArray.put("projectId", 7);
            notArray.put("title", "x");
            notArray.put("dueDate", "2026-10-12");
            notArray.put("fileIds", "2391");
            assertThrows(IllegalArgumentException.class, () -> controller.create(notArray, "sess"));

            Map<String, Object> typeNotText = new LinkedHashMap<>(notArray);
            typeNotText.remove("fileIds");
            typeNotText.put("type", 3);
            assertThrows(IllegalArgumentException.class, () -> controller.create(typeNotText, "sess"));

            verify(taskService, never()).createTask(any(), any(ProjectTaskService.TaskDraft.class), any());
        }
    }

    @Test
    void createSurfacesServiceValidationAsIllegalArgument() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            allowWrite();
            when(taskService.createTask(eq(7L), any(ProjectTaskService.TaskDraft.class), eq(1L)))
                    .thenThrow(new IllegalArgumentException("负责人不是该项目成员"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("projectId", 7);
            body.put("title", "x");
            body.put("dueDate", "2026-10-12");
            body.put("assigneeId", 99);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> controller.create(body, "sess"));
            assertEquals("负责人不是该项目成员", e.getMessage());
        }
    }

    @Test
    void updatePassesNewFieldsThroughIncludingExplicitNulls() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            when(taskService.getTask(100L)).thenReturn(stubTask(100L, 7L));
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            allowWrite();

            Map<String, Object> body = new java.util.HashMap<>();
            body.put("fileIds", List.of(5, 6));
            body.put("assigneeId", null);
            body.put("type", "MEETING");
            ProjectTask updated = stubTask(100L, 7L);
            when(taskService.updateTask(100L, body)).thenReturn(updated);
            when(taskService.toResponseMap(updated)).thenReturn(responseMapOf(updated));

            assertEquals(0, controller.update(100L, body, "sess").getBody().get("code"));
            verify(taskService).updateTask(eq(100L), eq(body));
        }
    }
}
