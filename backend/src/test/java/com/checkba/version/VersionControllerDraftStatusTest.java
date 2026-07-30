package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 第 3 期 Task 5：{@code /status} 的 onDraft / adoptConflict 三态行为
 * （在稿上 / 冲突窗口——含反查命中与反查落空两种 / 平常态）。
 * 鉴权矩阵已在 {@link VersionControllerAuthTest} 覆盖，这里只测状态派生。
 */
@ExtendWith(MockitoExtension.class)
class VersionControllerDraftStatusTest {

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

    private static final long PROJECT_ID = 7L;
    private static final long USER_ID = 1L;

    @SuppressWarnings("unchecked")
    private Map<String, Object> callStatus() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(USER_ID);
            when(projectMemberService.hasReadPermission(PROJECT_ID, USER_ID)).thenReturn(true);
            when(projectMemberService.isClient(PROJECT_ID, USER_ID)).thenReturn(false);
            when(repoService.isInitialized(PROJECT_ID)).thenReturn(true);
            when(sessionService.activeSession(PROJECT_ID)).thenReturn(Optional.empty());
            when(sessionService.pendingChangesLocked(PROJECT_ID)).thenReturn(List.of());
            when(sessionService.pendingRecovery(PROJECT_ID)).thenReturn(Optional.empty());

            var response = controller.status(PROJECT_ID, "sess");
            return (Map<String, Object>) response.getBody().get("data");
        }
    }

    private WorkSession draft(long id, String name, String branch) {
        WorkSession s = new WorkSession();
        s.setId(id);
        s.setTitle(name);
        s.setBranchName(branch);
        s.setProjectId(PROJECT_ID);
        s.setSessionType(WorkSession.SessionType.DRAFT);
        s.setStatus(WorkSession.Status.ACTIVE);
        return s;
    }

    @Test
    void normalStateHasNullOnDraftAndAdoptConflict() {
        when(sessionService.activeDraftOnBranch(PROJECT_ID)).thenReturn(Optional.empty());
        when(repoService.repositoryMerging(PROJECT_ID)).thenReturn(false);

        Map<String, Object> data = callStatus();

        assertNull(data.get("onDraft"));
        assertNull(data.get("adoptConflict"));
    }

    @Test
    void onDraftReflectsActiveDraftOnBranch() {
        WorkSession d = draft(3L, "试验稿", "draft/1000");
        when(sessionService.activeDraftOnBranch(PROJECT_ID)).thenReturn(Optional.of(d));
        when(repoService.repositoryMerging(PROJECT_ID)).thenReturn(false);

        Map<String, Object> data = callStatus();

        @SuppressWarnings("unchecked")
        Map<String, Object> onDraft = (Map<String, Object>) data.get("onDraft");
        assertEquals(3L, onDraft.get("id"));
        assertEquals("试验稿", onDraft.get("name"));
        assertNull(data.get("adoptConflict"));
    }

    @Test
    void adoptConflictResolvesDraftByMergeHeadReverse() {
        when(sessionService.activeDraftOnBranch(PROJECT_ID)).thenReturn(Optional.empty());
        when(repoService.repositoryMerging(PROJECT_ID)).thenReturn(true);
        when(repoService.mergeHeadRef(PROJECT_ID)).thenReturn("sha-draft-tip");
        WorkSession d = draft(3L, "试验稿", "draft/1000");
        when(sessionService.listDrafts(PROJECT_ID)).thenReturn(List.of(d));
        when(repoService.resolveRef(PROJECT_ID, "draft/1000")).thenReturn("sha-draft-tip");
        when(repoService.conflictingPaths(PROJECT_ID))
                .thenReturn(List.of(".awd/tree.json", "合同.txt"));

        Map<String, Object> data = callStatus();

        @SuppressWarnings("unchecked")
        Map<String, Object> conflict = (Map<String, Object>) data.get("adoptConflict");
        assertEquals(3L, conflict.get("draftId"));
        assertEquals("试验稿", conflict.get("draftName"));
        assertEquals(List.of("合同.txt"), conflict.get("conflictingPaths"), "内部清单文件不得透出给律师");
    }

    /**
     * 反查落空的异常残局：MERGE_HEAD 存在但找不到对应的 ACTIVE 稿（例如数据被并发改动）。
     * 仍必须给出 adoptConflict（draftId/draftName 为空），前端才能提供「先不采纳」逃生门，
     * 不能因为反查失败就对律师隐瞒仓库停在合并中这件事。
     */
    @Test
    void adoptConflictStillReportedWhenDraftLookupMisses() {
        when(sessionService.activeDraftOnBranch(PROJECT_ID)).thenReturn(Optional.empty());
        when(repoService.repositoryMerging(PROJECT_ID)).thenReturn(true);
        when(repoService.mergeHeadRef(PROJECT_ID)).thenReturn("sha-orphan");
        when(sessionService.listDrafts(PROJECT_ID)).thenReturn(List.of());
        when(repoService.conflictingPaths(PROJECT_ID)).thenReturn(List.of("合同.txt"));

        Map<String, Object> data = callStatus();

        @SuppressWarnings("unchecked")
        Map<String, Object> conflict = (Map<String, Object>) data.get("adoptConflict");
        assertNull(conflict.get("draftId"));
        assertNull(conflict.get("draftName"));
        assertEquals(List.of("合同.txt"), conflict.get("conflictingPaths"));
    }
}
