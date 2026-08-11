package com.checkba.service;

import com.checkba.model.entity.AgentRunRecord;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectMember;
import com.checkba.repository.AgentRunRecordRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 概览页统计条的三条口径：系统目录整棵子树剔除、成员去重、后台任务取表且封顶 5 条。
 */
@ExtendWith(MockitoExtension.class)
class ProjectOverviewServiceTest {

    private static final Long PROJECT = 7L;

    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private AgentRunRecordRepository agentRunRecordRepository;
    @Mock private ProjectStorageResolver storageResolver;

    @InjectMocks private ProjectOverviewService service;

    private Object[] node(long id, Long parentId, boolean folder, String name) {
        return new Object[]{id, parentId, folder, name};
    }

    private ProjectMember member(Long userId) {
        ProjectMember m = new ProjectMember();
        m.setUserId(userId);
        return m;
    }

    private AgentRunRecord run(String conversationId, String status, LocalDateTime updatedAt) {
        AgentRunRecord r = new AgentRunRecord();
        r.setConversationId(conversationId);
        r.setStatus(status);
        r.setUpdatedAt(updatedAt);
        return r;
    }

    /** 五个依赖都要有桩，stats() 每次都会全部走到（严格桩下不会有 UnnecessaryStubbing）。 */
    private void stub(List<Object[]> tree, boolean localRoot,
                      List<ProjectMember> members, Long ownerUserId,
                      List<AgentRunRecord> runs) {
        when(projectFileRepository.findTreeSkeletonByProjectId(PROJECT)).thenReturn(tree);
        when(storageResolver.hasLocalRoot(PROJECT)).thenReturn(localRoot);
        when(projectMemberRepository.findByProjectId(PROJECT)).thenReturn(members);
        Project p = new Project();
        p.setId(PROJECT);
        p.setUserId(ownerUserId);
        when(projectRepository.findById(PROJECT)).thenReturn(Optional.of(p));
        when(agentRunRecordRepository.findTop5ByProjectIdOrderByUpdatedAtDesc(PROJECT)).thenReturn(runs);
    }

    @Test
    void excludesSystemFolderSubtreesFromBothCounts() {
        stub(List.of(
                node(1L, null, true, "合同"),
                node(2L, 1L, false, "框架协议.docx"),
                node(3L, null, true, "__staging_area__"),
                node(4L, 3L, false, "临时.pdf"),
                node(5L, 3L, true, "拖进来的整个文件夹"),
                node(6L, 5L, false, "深层.pdf"),
                node(7L, null, true, "AI Assistant Files"),
                node(8L, 7L, false, "纪要.md")
        ), false, List.of(), 1L, List.of());

        Map<String, Object> data = service.stats(PROJECT);

        assertEquals(1L, data.get("fileCount"));
        assertEquals(1L, data.get("folderCount"));
        assertEquals(false, data.get("isLocalRoot"));
        assertEquals(List.of(), data.get("backgroundRuns"));
    }

    @Test
    void memberCountDeduplicatesOwnerThatAlsoHasAMemberRow() {
        stub(List.of(), false, List.of(member(1L), member(2L), member(2L)), 1L, List.of());

        assertEquals(2, service.stats(PROJECT).get("memberCount"));
    }

    @Test
    void memberCountCountsOwnerWithoutMemberRow() {
        stub(List.of(), false, List.of(), 1L, List.of());

        assertEquals(1, service.stats(PROJECT).get("memberCount"));
    }

    @Test
    void localRootProjectIsFlagged() {
        stub(List.of(), true, List.of(), 1L, List.of());

        assertEquals(true, service.stats(PROJECT).get("isLocalRoot"));
    }

    @Test
    void backgroundRunsCarryStatusAndIsoTime() {
        // 封顶 5 条现在是仓储层 findTop5By... 的职责（SQL 里直接 LIMIT，见
        // AgentRunRecordRepositoryProjectScopeTest.cappedAtFiveEvenWithMoreRowsInTable），
        // 服务层不再自己 break——这里只验证服务把仓储给的结果原样透传成响应形状。
        LocalDateTime base = LocalDateTime.of(2026, 8, 8, 10, 11, 12);
        List<AgentRunRecord> runs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            runs.add(run("c-" + i, "RUNNING", base.minusMinutes(i)));
        }
        stub(List.of(), false, List.of(), 1L, runs);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out =
                (List<Map<String, Object>>) service.stats(PROJECT).get("backgroundRuns");

        assertEquals(5, out.size());
        assertEquals("c-0", out.get(0).get("conversationId"));
        assertEquals("RUNNING", out.get(0).get("status"));
        assertEquals("2026-08-08T10:11:12", out.get(0).get("updatedAt"));
    }

    @Test
    void backgroundRunWithoutTimestampKeepsNullInsteadOfBlowingUp() {
        stub(List.of(), false, List.of(), 1L, List.of(run("c-x", "INTERRUPTED", null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> out =
                (List<Map<String, Object>>) service.stats(PROJECT).get("backgroundRuns");

        assertEquals(1, out.size());
        assertTrue(out.get(0).containsKey("updatedAt"));
        assertEquals(null, out.get(0).get("updatedAt"));
    }
}
