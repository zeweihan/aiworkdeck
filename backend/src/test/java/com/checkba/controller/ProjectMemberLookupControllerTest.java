package com.checkba.controller;

import com.checkba.service.AuthAbuseGuard;
import com.checkba.service.ClientInvitationService;
import com.checkba.service.ProjectMemberService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 查人端点（dev-board#444）：它和加人端点合起来是一个「这个手机号注册过没有」的
 * 探测面，所以两者共用一道按管理员维度的限频，超限时回**业务错误**（code=1 + 一句
 * 人话），不是 500、也不是像掉线的措辞。
 */
@ExtendWith(MockitoExtension.class)
class ProjectMemberLookupControllerTest {

    private static final long PROJECT_ID = 7L;
    private static final long USER_ID = 1L;

    @Mock private ProjectMemberService projectMemberService;
    @Mock private ClientInvitationService clientInvitationService;
    @Mock private AuthAbuseGuard authAbuseGuard;

    @InjectMocks private ProjectMemberController controller;

    @Test
    void lookupReturnsThePersonCardAndCountsAgainstTheRateLimit() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.lookupMember(PROJECT_ID, "13800138000", USER_ID))
                    .thenReturn(new ProjectMemberService.MemberLookup(
                            true, "李思", null, "138****8000", false, null, null));

            Map<String, Object> res = controller.lookupMember(PROJECT_ID, "13800138000", "sess");

            assertEquals(0, res.get("code"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.get("data");
            assertEquals(Boolean.TRUE, data.get("found"));
            assertEquals("李思", data.get("displayName"));
            assertEquals("138****8000", data.get("maskedContact"));
            verify(authAbuseGuard).checkMemberLookupRate(USER_ID);
            verify(authAbuseGuard).recordMemberLookup(USER_ID);
        }
    }

    @Test
    void lookupOverTheRateLimitBecomesABusinessError() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            doThrow(new IllegalArgumentException("查找同事过于频繁，稍后再试"))
                    .when(authAbuseGuard).checkMemberLookupRate(USER_ID);

            Map<String, Object> res = controller.lookupMember(PROJECT_ID, "13800138000", "sess");

            assertEquals(1, res.get("code"));
            String message = String.valueOf(res.get("message"));
            assertTrue(message.contains("过于频繁"), message);
            // 掉线判定用的三个词一个都不能出现（前端据此清会话）
            assertFalse(message.contains("登录"), message);
            assertFalse(message.contains("未授权"), message);
            assertFalse(message.contains("请先"), message);
            verify(projectMemberService, never()).lookupMember(anyLong(), any(), any());
        }
    }

    /** 加人也吃同一个计数——只挂在查人上等于把额度翻倍，交替调两个端点就绕开了。 */
    @Test
    void addingAMemberCountsAgainstTheSameRateLimit() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            ProjectMemberController.AddMemberRequest req = new ProjectMemberController.AddMemberRequest();
            req.setUsername("13800138000");
            req.setRole("PARTICIPANT");

            controller.addMember(PROJECT_ID, req, "sess");

            verify(authAbuseGuard).checkMemberLookupRate(USER_ID);
            verify(authAbuseGuard).recordMemberLookup(USER_ID);
        }
    }
}
