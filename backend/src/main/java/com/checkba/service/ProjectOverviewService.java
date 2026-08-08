package com.checkba.service;

import com.checkba.model.entity.AgentRunRecord;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectMember;
import com.checkba.repository.AgentRunRecordRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.quota.StageQuotaService;
import com.checkba.storage.ProjectStorageResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 项目概览页统计条的数据组装。只读，不写任何表。
 *
 * <p>刻意不提供「项目大小」与「最近修改」：编辑器保存路径不更新
 * ProjectFile.updatedAt/fileSize，那两个数是假的。要「最近修改」请取
 * /version/timeline 最新一条的 when，不要走 /version/status（它会跑两次 git add）。</p>
 */
@Service
public class ProjectOverviewService {

    /** AI 生成物固定落在项目根下这个文件夹（见 ProjectFileService.java:835）。 */
    static final String AI_ARTIFACT_FOLDER_NAME = "AI Assistant Files";

    /** 统计条最多带回几条后台任务。 */
    private static final int MAX_BACKGROUND_RUNS = 5;

    private final ProjectFileRepository projectFileRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;
    private final AgentRunRecordRepository agentRunRecordRepository;
    private final ProjectStorageResolver storageResolver;

    public ProjectOverviewService(ProjectFileRepository projectFileRepository,
                                  ProjectMemberRepository projectMemberRepository,
                                  ProjectRepository projectRepository,
                                  AgentRunRecordRepository agentRunRecordRepository,
                                  ProjectStorageResolver storageResolver) {
        this.projectFileRepository = projectFileRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectRepository = projectRepository;
        this.agentRunRecordRepository = agentRunRecordRepository;
        this.storageResolver = storageResolver;
    }

    public Map<String, Object> stats(Long projectId) {
        List<Object[]> rows = projectFileRepository.findTreeSkeletonByProjectId(projectId);
        Set<Long> excluded = systemSubtreeIds(rows);

        long fileCount = 0L;
        long folderCount = 0L;
        for (Object[] r : rows) {
            if (excluded.contains((Long) r[0])) continue;
            if (Boolean.TRUE.equals(r[2])) folderCount++;
            else fileCount++;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("fileCount", fileCount);
        data.put("folderCount", folderCount);
        data.put("isLocalRoot", storageResolver.hasLocalRoot(projectId));
        data.put("memberCount", memberCount(projectId));
        data.put("backgroundRuns", backgroundRuns(projectId));
        return data;
    }

    /**
     * 两个系统文件夹（缓存区、AI 生成物）连整棵子树一起剔除。它们都是普通
     * ProjectFile 行、isDeleted=false，任何朴素计数都会把它们和子树数进去；
     * 而律师可以把整个文件夹拖进缓存区，所以剔除必须递归。
     * 识别口径是「项目根下（parentId 为 null）的同名文件夹」——两者都建在根下。
     */
    private Set<Long> systemSubtreeIds(List<Object[]> rows) {
        Map<Long, List<Long>> childrenOf = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();
        for (Object[] r : rows) {
            Long id = (Long) r[0];
            Long parentId = (Long) r[1];
            childrenOf.computeIfAbsent(parentId, k -> new ArrayList<>()).add(id);
            if (parentId == null && Boolean.TRUE.equals(r[2]) && isSystemFolderName((String) r[3])) {
                queue.add(id);
            }
        }
        Set<Long> excluded = new HashSet<>();
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            // add 返回 false = 已经走过，脏数据里父子成环时不再下钻
            if (!excluded.add(id)) continue;
            queue.addAll(childrenOf.getOrDefault(id, List.of()));
        }
        return excluded;
    }

    private boolean isSystemFolderName(String name) {
        return StageQuotaService.STAGING_FOLDER_NAME.equals(name)
                || AI_ARTIFACT_FOLDER_NAME.equals(name);
    }

    /**
     * 成员数要去重：project_member 返回裸行，owner 可能另有一行也可能没有
     * （getMemberRole 是先判 project.userId 再查表，说明两者并存），不去重会多算。
     */
    private int memberCount(Long projectId) {
        Set<Long> userIds = new HashSet<>();
        for (ProjectMember m : projectMemberRepository.findByProjectId(projectId)) {
            if (m.getUserId() != null) userIds.add(m.getUserId());
        }
        projectRepository.findById(projectId)
                .map(Project::getUserId)
                .ifPresent(userIds::add);
        return userIds.size();
    }

    private List<Map<String, Object>> backgroundRuns(Long projectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentRunRecord r : agentRunRecordRepository.findByProjectIdOrderByUpdatedAtDesc(projectId)) {
            if (out.size() >= MAX_BACKGROUND_RUNS) break;
            // HashMap 允许 null 值；Map.of 不允许，updatedAt 可空所以不能用 Map.of
            Map<String, Object> m = new HashMap<>();
            m.put("conversationId", r.getConversationId());
            m.put("status", r.getStatus());
            m.put("updatedAt", r.getUpdatedAt() == null ? null : r.getUpdatedAt().toString());
            out.add(m);
        }
        return out;
    }
}
