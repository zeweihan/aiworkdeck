package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectMemberService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ---- 其余 7 个端点的鉴权测试 --------------------------------------------
    //
    // /timeline 之外的端点，逐条测试要写 21 段（7 端点 x 3 拒绝场景）几乎相同的样板代码。
    // 改用参数化：Endpoint 枚举列出端点，invoke 负责调用，verifyNeverCalled 负责断言
    // 对应的服务方法一次都没被调用——这样新增端点却漏加 requireMember（或把它挪到了
    // 服务调用之后）时，对应枚举项下的三个测试都会红。

    private static final long PROJECT_ID = 7L;
    private static final long USER_ID = 1L;

    private enum Endpoint { STATUS, ENABLE, CHANGES, SESSION_END, SESSION_DISCARD, SESSION_RESUME, REVERT, FILE_BYTES, FILE_TEXT }

    private void invoke(Endpoint endpoint, String sessionId) {
        switch (endpoint) {
            case STATUS -> controller.status(PROJECT_ID, sessionId);
            case ENABLE -> controller.enable(PROJECT_ID, sessionId);
            case CHANGES -> controller.changes(PROJECT_ID, "abc123", sessionId);
            case SESSION_END -> controller.endSession(PROJECT_ID, null, sessionId);
            case SESSION_DISCARD -> controller.discardSession(PROJECT_ID, sessionId);
            case SESSION_RESUME -> controller.resumeSession(PROJECT_ID, sessionId);
            case REVERT -> controller.revert(PROJECT_ID, Map.of("ref", "abc123"), sessionId);
            case FILE_BYTES -> controller.fileBytesAtRef(PROJECT_ID, "abc123", "a.txt", sessionId);
            case FILE_TEXT -> controller.fileTextAtRef(PROJECT_ID, "abc123", "a.txt", sessionId);
        }
    }

    private void verifyNeverCalled(Endpoint endpoint) {
        switch (endpoint) {
            case STATUS -> verify(repoService, never()).isInitialized(anyLong());
            case ENABLE -> verify(repoService, never()).init(anyLong(), anyString(), anyString());
            case CHANGES -> verify(repoService, never()).diffNameStatus(anyLong(), anyString(), anyString());
            case SESSION_END -> verify(sessionService, never()).endSession(anyLong(), any(), anyString(), any());
            case SESSION_DISCARD -> verify(sessionService, never()).discardSession(anyLong(), any());
            case SESSION_RESUME -> verify(sessionService, never()).resumeSession(anyLong());
            case REVERT -> verify(sessionService, never()).revertTo(anyLong(), anyString(), any(), anyString());
            case FILE_BYTES, FILE_TEXT ->
                    verify(repoService, never()).readBlobAtCommit(anyLong(), anyString(), anyString());
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void clientRoleCannotAccess(Endpoint endpoint) {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(true);

            assertThrows(IllegalArgumentException.class, () -> invoke(endpoint, "sess"));
            verifyNeverCalled(endpoint);
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void nonMemberCannotAccess(Endpoint endpoint) {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(false);

            assertThrows(IllegalArgumentException.class, () -> invoke(endpoint, "sess"));
            verifyNeverCalled(endpoint);
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    void anonymousCannotAccess(Endpoint endpoint) {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            assertThrows(IllegalArgumentException.class, () -> invoke(endpoint, null));
            verifyNeverCalled(endpoint);
        }
    }

    // ---- 异常处理器：技术性消息不回显，业务性消息原样回显 --------------------

    @Test
    void technicalVersionExceptionIsMaskedWithGenericMessage() {
        var e = new VersionException("合并失败: work/1690000000", new RuntimeException("boom"));

        var response = controller.onVersionError(e);

        assertEquals(1, response.getBody().get("code"));
        assertEquals("版本记录操作失败，请重试", response.getBody().get("message"));
    }

    @Test
    void userFacingVersionExceptionIsShownAsIs() {
        var e = VersionException.userFacing("当前没有进行中的工作");

        var response = controller.onVersionError(e);

        assertEquals(1, response.getBody().get("code"));
        assertEquals("当前没有进行中的工作", response.getBody().get("message"));
    }
}
