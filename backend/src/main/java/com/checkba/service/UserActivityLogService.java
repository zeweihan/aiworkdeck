package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.UserActivityLog;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserActivityLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserActivityLogService {

    private final UserActivityLogRepository repository;
    private final ProjectRepository projectRepository;

    public void logActivity(Long userId, String actionType, Long targetId, String targetName, Long duration, String metaInfo, Long projectId) {
        UserActivityLog log = new UserActivityLog();
        log.setUserId(userId);
        log.setActionType(actionType);
        log.setTargetId(targetId);
        log.setTargetName(targetName);
        log.setDuration(duration);
        log.setMetaInfo(metaInfo);
        log.setProjectId(projectId);
        repository.save(log);
    }

    // 按 projectId 批量补 projectName（一次 IN 查询，不逐条查）；查不到的（项目已删除）
    // 与老数据没有 projectId 的，projectName 都留空，前端归到「未关联项目」。
    public List<UserActivityLog> getUserLogs(Long userId) {
        List<UserActivityLog> logs = repository.findTop500ByUserIdOrderByTimestampDesc(userId);

        List<Long> projectIds = logs.stream()
                .map(UserActivityLog::getProjectId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());

        if (!projectIds.isEmpty()) {
            Map<Long, String> nameById = projectRepository.findAllById(projectIds).stream()
                    .collect(Collectors.toMap(Project::getId, Project::getName));
            logs.forEach(log -> {
                if (log.getProjectId() != null) {
                    log.setProjectName(nameById.get(log.getProjectId()));
                }
            });
        }

        return logs;
    }
}
