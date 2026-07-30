package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 按版本取文件字节/文本的路径校验与控制器行为。
 *
 * path 是外部（前端）传入的仓库相对路径——校验规则见 WorkSessionService.safeRepoPath，
 * 是 .awd/ 对律师不可见（领域地雷 7）在这两个新端点上的安全边界。
 */
@ExtendWith(MockitoExtension.class)
class VersionFileAccessTest {

    @Test
    void safeRepoPathAcceptsNormalNestedPath() {
        assertEquals("重要协议/股权转让协议.docx",
                WorkSessionService.safeRepoPath("重要协议/股权转让协议.docx"));
    }

    @Test
    void safeRepoPathRejectsTraversalAwdAndAbsolute() {
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("../etc/passwd"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("a/../../b"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath(".awd/tree.json"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath(".awd"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("/abs"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("a\\b"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("  "));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("a/./b"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("a//b"));
        assertThrows(VersionException.class, () -> WorkSessionService.safeRepoPath("合同.docx/"));
    }

    // ---- 控制器行为：鉴权通过后，file-bytes/file-text 的实际取值逻辑 ----------

    @Mock
    private ProjectRepoService repoService;
    @Mock
    private WorkSessionService sessionService;
    @Mock
    private ProjectMemberService projectMemberService;
    @Mock
    private UserService userService;

    private VersionController newController() {
        return new VersionController(repoService, sessionService, projectMemberService, userService);
    }

    private static final long PROJECT_ID = 7L;
    private static final long USER_ID = 1L;
    private static final String REF = "abc123";

    private void stubMember(MockedStatic<AuthController> auth) {
        auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
        lenient().when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
        lenient().when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(false);
    }

    @Test
    void fileBytesAtRefReturnsBytesWhenPresent() {
        VersionController controller = newController();
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            stubMember(auth);
            when(repoService.readBlobAtCommit(PROJECT_ID, REF, "a.txt")).thenReturn(content);

            var response = controller.fileBytesAtRef(PROJECT_ID, REF, "a.txt", "sess");

            assertArrayEquals(content, response.getBody());
            assertEquals(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM,
                    response.getHeaders().getContentType());
        }
    }

    @Test
    void fileBytesAtRefThrowsUserFacingWhenMissing() {
        VersionController controller = newController();
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            stubMember(auth);
            when(repoService.readBlobAtCommit(PROJECT_ID, REF, "missing.txt")).thenReturn(null);

            VersionException e = assertThrows(VersionException.class,
                    () -> controller.fileBytesAtRef(PROJECT_ID, REF, "missing.txt", "sess"));
            assertTrue(e.isUserFacing());
            assertEquals("这一版里没有这份文件", e.getMessage());
        }
    }

    @Test
    void fileBytesAtRefRejectsIllegalPathBeforeTouchingRepo() {
        VersionController controller = newController();
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            stubMember(auth);

            assertThrows(VersionException.class,
                    () -> controller.fileBytesAtRef(PROJECT_ID, REF, "../etc/passwd", "sess"));
            verify(repoService, never()).readBlobAtCommit(anyLong(), anyString(), anyString());
        }
    }

    @Test
    void fileTextAtRefExtractsPlainTextBytes() {
        VersionController controller = newController();
        byte[] content = "纯文本内容".getBytes(StandardCharsets.UTF_8);
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            stubMember(auth);
            when(repoService.readBlobAtCommit(PROJECT_ID, REF, "a.txt")).thenReturn(content);

            var response = controller.fileTextAtRef(PROJECT_ID, REF, "a.txt", "sess");

            @SuppressWarnings("unchecked")
            var data = (java.util.Map<String, Object>) response.getBody().get("data");
            assertEquals(0, response.getBody().get("code"));
            assertTrue(((String) data.get("text")).contains("纯文本内容"));
        }
    }

    @Test
    void fileTextAtRefThrowsUserFacingWhenMissing() {
        VersionController controller = newController();
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            stubMember(auth);
            when(repoService.readBlobAtCommit(PROJECT_ID, REF, "missing.txt")).thenReturn(null);

            VersionException e = assertThrows(VersionException.class,
                    () -> controller.fileTextAtRef(PROJECT_ID, REF, "missing.txt", "sess"));
            assertTrue(e.isUserFacing());
            assertEquals("这一版里没有这份文件", e.getMessage());
        }
    }
}
