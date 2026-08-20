package com.checkba.controller;

import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectOverviewService;
import com.checkba.service.task.ProjectTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 概览统计条与任务列表两个端点的鉴权口径：必须登录 + 必须是项目成员，但不拒 CLIENT
 * （统计条只是数量、任务是交付日期，客户看得见文件树就该看得见）。
 *
 * 未登录/越权抛 IllegalArgumentException，由 GlobalExceptionHandler:69-77 统一转成
 * HTTP 200 + {code:1,message}，全站同一口径，不引 401/403。
 *
 * 注意 hasReadPermission 的参数序是 (projectId, userId)，两参数同为 Long，写反能编译通过。
 *
 * 类名带 Stats 前缀是刻意的：档案组与会话列表组会各写一个鉴权测试类往同一个控制器上挂，
 * 三个文件名必须互不相同（ProjectOverviewProfileAuthTest / ProjectOverviewConversationsAuthTest）。
 */
@ExtendWith(MockitoExtension.class)
class ProjectOverviewStatsAuthTest {

    @Mock private ProjectMemberService projectMemberService;
    @Mock private ProjectOverviewService overviewService;
    @Mock private ProjectTaskService projectTaskService;

    @InjectMocks private ProjectOverviewController controller;

    @Test
    void anonymousIsRejected() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.overviewStats(7L, null));
            assertEquals("未登录", e.getMessage());
            verify(overviewService, never()).stats(anyLong());
        }
    }

    @Test
    void nonMemberIsRejected() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(false);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.overviewStats(7L, "sess"));
            assertEquals("无权访问该项目", e.getMessage());
            verify(overviewService, never()).stats(anyLong());
        }
    }

    @Test
    void memberGetsEnvelopeAndClientIsNotBlocked() {
        Map<String, Object> stats = Map.of(
                "fileCount", 12L, "folderCount", 3L, "isLocalRoot", false,
                "memberCount", 4, "backgroundRuns", List.of());
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(true);
            when(overviewService.stats(7L)).thenReturn(stats);

            Map<String, Object> body = controller.overviewStats(7L, "sess").getBody();

            assertNotNull(body);
            assertEquals(0, body.get("code"));
            assertEquals(stats, body.get("data"));
            // 统计条不拒 CLIENT：isClient 一次都不该被问
            verify(projectMemberService, never()).isClient(anyLong(), anyLong());
        }
    }

    @Test
    void tasksRejectsNonMember() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(false);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.tasks(7L, null, null, "sess"));
            assertEquals("无权访问该项目", e.getMessage());
        }
    }

    /** 会员可读，空列表原样透传（不是 null）。B 期换真查询后路径与响应形状一行不改。 */
    @Test
    void tasksReturnsEmptyListForMember() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(true);
            when(projectTaskService.listByProject(7L, null, null)).thenReturn(List.of());

            Map<String, Object> body = controller.tasks(7L, null, null, "sess").getBody();

            assertNotNull(body);
            assertEquals(0, body.get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            assertEquals(List.of(), data.get("tasks"));
            verify(projectMemberService, never()).isClient(anyLong(), anyLong());
        }
    }
}
