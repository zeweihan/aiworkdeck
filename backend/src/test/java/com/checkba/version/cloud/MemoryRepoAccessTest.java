package com.checkba.version.cloud;

import com.checkba.service.DeviceTokenService;
import com.checkba.service.ProjectMemberService;
import com.checkba.version.memory.MemoryRealm;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 记忆仓库的 Git 传输鉴权（spec Phase A 第 6 条）：
 * user-{id}-memory 仓 owner-only——本人读写皆可，任何别人（包括同项目成员）403；
 * project-{id}-memory 仓复用既有项目成员规则（authorize 路径，GitAccessServiceTest 已覆盖）。
 * 另测仓库名路由解析：数字 = 项目文档仓库，memory 键 = 记忆仓库，其余 404。
 */
class MemoryRepoAccessTest {

    private DeviceTokenService tokens;
    private ProjectMemberService members;
    private GitAccessService svc;

    @BeforeEach
    void setUp() {
        tokens = mock(DeviceTokenService.class);
        members = mock(ProjectMemberService.class);
        svc = new GitAccessService(tokens, members);
    }

    private HttpServletRequest reqWith(String user, String token) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        if (token != null) {
            String cred = Base64.getEncoder().encodeToString(
                    (user + ":" + token).getBytes(StandardCharsets.UTF_8));
            when(req.getHeader("Authorization")).thenReturn("Basic " + cred);
        }
        return req;
    }

    @Test
    void ownerCanReadAndWriteOwnUserMemoryRepo() {
        when(tokens.resolveUserId("awdt_x")).thenReturn(42L);
        assertEquals(42L, svc.authorizeUserMemory(reqWith("u", "awdt_x"), 42L, false));
        assertEquals(42L, svc.authorizeUserMemory(reqWith("u", "awdt_x"), 42L, true));
    }

    @Test
    void nonOwnerIsRejectedEvenWithValidToken() {
        when(tokens.resolveUserId("awdt_x")).thenReturn(42L);
        assertEquals(403, assertThrows(GitAccessDeniedException.class,
                () -> svc.authorizeUserMemory(reqWith("u", "awdt_x"), 7L, false)).statusCode());
        assertEquals(403, assertThrows(GitAccessDeniedException.class,
                () -> svc.authorizeUserMemory(reqWith("u", "awdt_x"), 7L, true)).statusCode());
        // owner-only 不看项目成员关系：成员服务在这条路径上不该被咨询
        verifyNoInteractions(members);
    }

    @Test
    void missingOrBadCredentialsIs401() {
        assertEquals(401, assertThrows(GitAccessDeniedException.class,
                () -> svc.authorizeUserMemory(reqWith(null, null), 42L, false)).statusCode());
        when(tokens.resolveUserId("bad")).thenReturn(null);
        assertEquals(401, assertThrows(GitAccessDeniedException.class,
                () -> svc.authorizeUserMemory(reqWith("u", "bad"), 42L, false)).statusCode());
    }

    @Test
    void repoNameRoutingSeparatesProjectDocAndMemoryRepos() {
        GitHttpController.RepoTarget project = GitHttpController.parseRepoName("12");
        assertNotNull(project);
        assertEquals(12L, project.projectId());
        assertNull(project.memoryRealm());

        GitHttpController.RepoTarget userMem = GitHttpController.parseRepoName("user-3-memory");
        assertNotNull(userMem);
        assertNull(userMem.projectId());
        assertEquals(MemoryRealm.Kind.USER, userMem.memoryRealm().kind());
        assertEquals(3L, userMem.memoryRealm().ownerId());

        GitHttpController.RepoTarget projMem = GitHttpController.parseRepoName("project-4-memory");
        assertNotNull(projMem);
        assertEquals(MemoryRealm.Kind.PROJECT, projMem.memoryRealm().kind());
        assertEquals(4L, projMem.memoryRealm().ownerId());

        assertNull(GitHttpController.parseRepoName("whatever"));
        assertNull(GitHttpController.parseRepoName("user-x-memory"));
        assertNull(GitHttpController.parseRepoName("user--memory"));
        assertNull(GitHttpController.parseRepoName(null));
    }
}
