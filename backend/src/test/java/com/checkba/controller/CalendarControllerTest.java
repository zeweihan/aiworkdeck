package com.checkba.controller;

import com.checkba.model.entity.Project;
import com.checkba.service.ProjectService;
import com.checkba.service.task.ProjectTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CalendarController(/api/calendar) 跨项目聚合（dev-board #49）：
 * - 未登录拒绝；
 * - 「可见项目」复用 ProjectService.getUserProjects（与 /api/projects/my 同一套判定），
 *   不自造过滤逻辑；
 * - 聚合结果按 projectId 补上 projectName（服务层不知道项目名，controller 层补）。
 */
@ExtendWith(MockitoExtension.class)
class CalendarControllerTest {

    @Mock private ProjectService projectService;
    @Mock private ProjectTaskService taskService;

    @InjectMocks private CalendarController controller;

    private Project project(Long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        return p;
    }

    @Test
    void rejectsAnonymous() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.list(null, null, null));
            assertEquals("未登录", e.getMessage());
            verify(taskService, never()).listAcrossProjects(any(), any(), any());
        }
    }

    @Test
    void noVisibleProjectsReturnsEmptyTasks() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectService.getUserProjects(1L)).thenReturn(List.of());
            when(taskService.listAcrossProjects(List.of(), null, null)).thenReturn(List.of());

            Map<String, Object> resp = controller.list(null, null, "sess").getBody();
            assertNotNull(resp);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            assertTrue(((List<?>) data.get("tasks")).isEmpty());
        }
    }

    @Test
    void aggregatesTasksAcrossVisibleProjectsAndFillsProjectName() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectService.getUserProjects(1L)).thenReturn(List.of(
                    project(1L, "金冠纾困"), project(2L, "另一个项目")));

            Map<String, Object> t1 = new LinkedHashMap<>();
            t1.put("id", 10L);
            t1.put("projectId", 1L);
            t1.put("title", "开庭");
            t1.put("dueDate", LocalDate.of(2026, 9, 1));

            Map<String, Object> t2 = new LinkedHashMap<>();
            t2.put("id", 11L);
            t2.put("projectId", 2L);
            t2.put("title", "答辩");
            t2.put("dueDate", LocalDate.of(2026, 9, 2));

            ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
            when(taskService.listAcrossProjects(idsCaptor.capture(), any(), any()))
                    .thenReturn(List.of(t1, t2));

            Map<String, Object> resp = controller.list(null, null, "sess").getBody();
            assertNotNull(resp);
            assertEquals(0, resp.get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) data.get("tasks");
            assertEquals(2, tasks.size());
            assertTrue(tasks.stream().anyMatch(t -> "金冠纾困".equals(t.get("projectName")) && t.get("id").equals(10L)));
            assertTrue(tasks.stream().anyMatch(t -> "另一个项目".equals(t.get("projectName")) && t.get("id").equals(11L)));
            assertTrue(idsCaptor.getValue().containsAll(List.of(1L, 2L)));
        }
    }
}
