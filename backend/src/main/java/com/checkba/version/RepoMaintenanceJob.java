package com.checkba.version;

import com.checkba.repository.ProjectRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日仓库维护。只做 GC（重打包 + 清理不可达对象），
 * 不做任何历史清理——spec 5.5。
 */
@Component
public class RepoMaintenanceJob {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RepoMaintenanceJob.class);

    private final ProjectRepository projectRepository;
    private final ProjectRepoService repoService;

    public RepoMaintenanceJob(ProjectRepository projectRepository,
                              ProjectRepoService repoService) {
        this.projectRepository = projectRepository;
        this.repoService = repoService;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void runDaily() {
        projectRepository.findAll().forEach(p -> {
            if (p.getId() == null || !repoService.isInitialized(p.getId())) return;
            try {
                repoService.gc(p.getId());
            } catch (Exception e) {
                log.warn("仓库维护失败: project={}", p.getId(), e);
            }
        });
    }
}
