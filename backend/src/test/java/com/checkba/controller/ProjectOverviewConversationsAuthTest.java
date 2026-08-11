package com.checkba.controller;

import com.checkba.service.ProjectAiMessageService;
import com.checkba.service.ProjectMemberService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 项目概览页「会话列表」端点的鉴权与信封。
 *
 * 关键差异（别照抄 /api/ai/conversations）：
 * - 无 session 必须抛「未登录」，不许静默返回空数组；
 * - 非成员必须被 hasReadPermission 挡掉；
 * - 参数序是 (projectId, userId)，写反能编译通过、运行时静默 false；
 * - 返回 ResponseEntity 包的信封 {code:0,data:...}，不是裸数组。
 *
 * 用 @InjectMocks 而不是手工 new：ProjectOverviewController 的构造器参数表会随另外
 * 两个后端组追加自己的 service 而变长，手工 new 在合并后会编译失败；@InjectMocks
 * 取最大构造器、对不上的参数注 null，而本测试从不触碰那些依赖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectOverviewConversationsAuthTest {

    @Mock
    private ProjectAiMessageService projectAiMessageService;
    @Mock
    private ProjectMemberService projectMemberService;
    // 不要加 @Mock private AuthController authController —— 控制器构造器里没有这个参数，
    // 注不进去是个死字段。会话解析走 mockStatic(AuthController.class)（下面每个用例里）。

    @InjectMocks
    private ProjectOverviewController controller;

    @Test
    void 无会话时抛未登录_不返回空数组() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> controller.listConversations(42L, 20, null, null, null));
            assertEquals("未登录", ex.getMessage());
            verify(projectAiMessageService, never())
                    .listProjectConversations(any(), any(), any(), anyInt());
        }
    }

    @Test
    void 非项目成员被拒() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            // 参数序：projectId 在前，userId 在后
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(false);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> controller.listConversations(42L, 20, null, null, "sess"));
            assertEquals("无权访问该项目", ex.getMessage());
            verify(projectAiMessageService, never())
                    .listProjectConversations(any(), any(), any(), anyInt());
        }
    }

    @Test
    void 成员放行并返回信封_两个游标参数与条数原样透传() {
        LocalDateTime cursor = LocalDateTime.of(2026, 8, 8, 10, 0, 12);
        Map<String, Object> payload = Map.of(
                "conversations", List.of(Map.of("conversationId", "c-a")),
                "nextBefore", "2026-08-08T09:00:00",
                "nextBeforeId", "c-a");

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(true);
            when(projectAiMessageService.listProjectConversations(42L, cursor, "c-b", 5))
                    .thenReturn(payload);

            ResponseEntity<Map<String, Object>> res =
                    controller.listConversations(42L, 5, cursor, "c-b", "sess");

            assertNotNull(res.getBody());
            assertEquals(Integer.valueOf(0), res.getBody().get("code"), "必须是信封，不是裸数组");
            assertSame(payload, res.getBody().get("data"));
            verify(projectAiMessageService).listProjectConversations(42L, cursor, "c-b", 5);
        }
    }

    @Test
    void 读权限不拒CLIENT_列表层按项目全员可见() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            when(projectMemberService.hasReadPermission(42L, 7L)).thenReturn(true);
            when(projectAiMessageService.listProjectConversations(any(), any(), any(), anyInt()))
                    .thenReturn(Map.of("conversations", List.of()));

            assertNotNull(controller.listConversations(42L, 20, null, null, "sess"));
            // 读端点不该去问 isClient —— 问了就说明写成写端点的口径了
            verify(projectMemberService, never()).isClient(anyLong(), anyLong());
        }
    }
}
