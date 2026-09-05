package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@code GET /api/projects/{projectId}/version/drafts/{draftId}/timeline}——单独一稿
 * 自己的历史（沿这一稿的分支 walk，不是主线 HEAD）。给另一个前端 agent 并行消费用，
 * 契约与 {@code /timeline} 完全一致（含 parents 数组，供前端画分叉/双亲关系）。
 */
@ExtendWith(MockitoExtension.class)
class VersionControllerDraftTimelineTest {

    @Mock private ProjectRepoService repoService;
    @Mock private WorkSessionService sessionService;
    @Mock private ProjectMemberService projectMemberService;
    @Mock private UserService userService;
    @Mock private ProjectFileService projectFileService;
    @Mock private com.checkba.service.telemetry.TelemetryService telemetryService;

    @InjectMocks private VersionController controller;

    private static final long PROJECT_ID = 7L;
    private static final long USER_ID = 1L;
    private static final long DRAFT_ID = 3L;

    private void asMember(MockedStatic<AuthController> auth) {
        auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
        when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
        when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(false);
    }

    private void asNonMember(MockedStatic<AuthController> auth) {
        auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
        when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(false);
    }

    private static WorkSession draft(long id, String name, String branch) {
        WorkSession s = new WorkSession();
        s.setId(id);
        s.setTitle(name);
        s.setBranchName(branch);
        s.setProjectId(PROJECT_ID);
        s.setSessionType(WorkSession.SessionType.DRAFT);
        s.setStatus(WorkSession.Status.ACTIVE);
        return s;
    }

    private static VersionEntry entry(String sha, List<String> parents) {
        return new VersionEntry(sha, "改了合同", "韩泽伟", Instant.now(), "auto", null, parents, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(org.springframework.http.ResponseEntity<Map<String, Object>> res) {
        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        assertEquals(0, body.get("code"));
        return (Map<String, Object>) body.get("data");
    }

    @Test
    void returnsTheDraftsOwnCommits() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            asMember(auth);
            when(repoService.isInitialized(PROJECT_ID)).thenReturn(true);
            WorkSession d = draft(DRAFT_ID, "试验稿", "draft/1000");
            when(sessionService.listDrafts(PROJECT_ID)).thenReturn(List.of(d));
            List<VersionEntry> commits = List.of(entry("sha-draft-2", List.of("sha-draft-1")));
            when(repoService.log(PROJECT_ID, "draft/1000", 50)).thenReturn(commits);

            Map<String, Object> data = body(controller.draftTimeline(PROJECT_ID, DRAFT_ID, 50, "sess"));

            assertEquals(commits, data.get("versions"));
            // 沿这一稿自己的分支 walk，不是主线 HEAD——传错 ref 会把主线历史当成稿历史返回
            verify(repoService).log(PROJECT_ID, "draft/1000", 50);
            verify(repoService, never()).log(eq(PROJECT_ID), eq("HEAD"), anyInt());
        }
    }

    @Test
    void rejectsNonMember() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            asNonMember(auth);

            assertThrows(IllegalArgumentException.class,
                    () -> controller.draftTimeline(PROJECT_ID, DRAFT_ID, 50, "sess"));
            verifyNoInteractions(repoService);
        }
    }

    @Test
    void unknownOrEndedDraftIdReturnsEmptyVersionsNotAnError() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            asMember(auth);
            when(repoService.isInitialized(PROJECT_ID)).thenReturn(true);
            // listDrafts 只返回 ACTIVE 稿——已采纳/已放弃/根本不存在的 draftId 都不会出现在这里
            when(sessionService.listDrafts(PROJECT_ID)).thenReturn(List.of());

            Map<String, Object> data = body(controller.draftTimeline(PROJECT_ID, 999L, 50, "sess"));

            assertEquals(List.of(), data.get("versions"));
            verify(repoService, never()).log(anyLong(), anyString(), anyInt());
        }
    }

    @Test
    void repositoryNotInitializedEarlyReturnsEmpty() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            asMember(auth);
            when(repoService.isInitialized(PROJECT_ID)).thenReturn(false);

            Map<String, Object> data = body(controller.draftTimeline(PROJECT_ID, DRAFT_ID, 50, "sess"));

            assertEquals(List.of(), data.get("versions"));
            verifyNoInteractions(sessionService);
        }
    }

    /**
     * 契约核验：VersionEntry 是 record，parents 必须原样出现在序列化 JSON 里
     * （前端要靠它画分叉/双亲关系）。防的是某处全局配置了字段过滤/别名策略，
     * 悄悄把这个字段吃掉却没有任何编译期信号。
     */
    @Test
    void versionEntrySerializesParentsArray() throws Exception {
        VersionEntry e = entry("sha-2", List.of("sha-1", "sha-0"));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(e);
        assertTrue(json.contains("\"parents\""), "序列化结果应带 parents 字段，实际: " + json);
        assertTrue(json.contains("sha-1") && json.contains("sha-0"), "parents 数组内容应完整，实际: " + json);
    }
}
