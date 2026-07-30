package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
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
    @Mock
    private ProjectFileService projectFileService;

    @InjectMocks
    private VersionController controller;

    @Test
    void clientRoleCannotSeeTimeline() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(7L, 1L)).thenReturn(true);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.timeline(7L, 50, null, "sess"));
            verify(repoService, never()).log(anyLong(), anyString(), anyInt());
        }
    }

    @Test
    void nonMemberCannotSeeTimeline() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(false);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.timeline(7L, 50, null, "sess"));
            verify(repoService, never()).log(anyLong(), anyString(), anyInt());
        }
    }

    @Test
    void anonymousCannotSeeTimeline() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.timeline(7L, 50, null, null));
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

            controller.timeline(7L, 50, null, "sess");

            verify(repoService).log(7L, "HEAD", 50);
        }
    }

    /**
     * 单文件历史的越权（IDOR）防护：fileId 属于别的项目必须拒绝，口径照
     * ProjectFileControllerIdorTest——鉴权通过后，服务端仍要校验 fileId→projectId 归属。
     */
    @Test
    void timelineRejectsFileFromAnotherProject() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(7L, 1L)).thenReturn(false);
            ProjectFile foreign = new ProjectFile();
            foreign.setId(50L);
            foreign.setProjectId(999L);
            when(projectFileService.getFile(50L)).thenReturn(foreign);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.timeline(7L, 50, 50L, "sess"));
            verify(repoService, never()).logForPath(anyLong(), anyString(), anyString(), anyInt());
        }
    }

    @Test
    void timelineFiltersByFileWithinSameProject() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(1L);
            when(projectMemberService.hasReadPermission(7L, 1L)).thenReturn(true);
            when(projectMemberService.isClient(7L, 1L)).thenReturn(false);
            ProjectFile own = new ProjectFile();
            own.setId(50L);
            own.setProjectId(7L);
            own.setFilePath("projects/7/合同.txt");
            when(projectFileService.getFile(50L)).thenReturn(own);
            when(repoService.logForPath(7L, "HEAD", "合同.txt", 50)).thenReturn(java.util.List.of());

            controller.timeline(7L, 50, 50L, "sess");

            verify(repoService).logForPath(7L, "HEAD", "合同.txt", 50);
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

    private enum Endpoint {
        STATUS, ENABLE, CHANGES, SESSION_END, SESSION_DISCARD, SESSION_RESUME, REVERT, FILE_BYTES, FILE_TEXT, MILESTONE,
        DRAFT_CREATE, DRAFT_LIST, DRAFT_SWITCH, SWITCH_MAINLINE, DRAFT_ADOPT, DRAFT_RESOLVE, DRAFT_ABORT_ADOPT, DRAFT_ABANDON
    }

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
            case MILESTONE -> controller.markMilestone(PROJECT_ID, "abc123", Map.of("name", "发客户第一稿"), sessionId);
            case DRAFT_CREATE -> controller.createDraft(PROJECT_ID, Map.of("name", "试验稿"), sessionId);
            case DRAFT_LIST -> controller.listDrafts(PROJECT_ID, sessionId);
            case DRAFT_SWITCH -> controller.switchToDraft(PROJECT_ID, 3L, sessionId);
            case SWITCH_MAINLINE -> controller.switchToMainline(PROJECT_ID, sessionId);
            case DRAFT_ADOPT -> controller.adoptDraft(PROJECT_ID, 3L, sessionId);
            case DRAFT_RESOLVE -> controller.resolveAdopt(PROJECT_ID, 3L, Map.of("resolutions", Map.of("a.txt", "MAIN")), sessionId);
            case DRAFT_ABORT_ADOPT -> controller.abortAdopt(PROJECT_ID, 3L, sessionId);
            case DRAFT_ABANDON -> controller.abandonDraft(PROJECT_ID, 3L, sessionId);
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
            case MILESTONE -> verify(repoService, never()).tagMilestone(anyLong(), anyString(), anyString());
            case DRAFT_CREATE -> verify(sessionService, never())
                    .createDraft(anyLong(), any(), anyString(), any(), anyString());
            case DRAFT_LIST -> verify(sessionService, never()).listDrafts(anyLong());
            case DRAFT_SWITCH -> verify(sessionService, never())
                    .switchToDraft(anyLong(), anyLong(), any(), anyString());
            case SWITCH_MAINLINE -> verify(sessionService, never())
                    .switchToMainline(anyLong(), any(), anyString());
            case DRAFT_ADOPT -> verify(sessionService, never())
                    .adoptDraft(anyLong(), anyLong(), any(), anyString());
            case DRAFT_RESOLVE -> verify(sessionService, never())
                    .resolveAdopt(anyLong(), anyLong(), any(), any(), anyString());
            case DRAFT_ABORT_ADOPT -> verify(sessionService, never()).abortAdopt(anyLong());
            case DRAFT_ABANDON -> verify(sessionService, never())
                    .abandonDraft(anyLong(), anyLong(), any(), anyString());
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
