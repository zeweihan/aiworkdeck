package com.checkba.service.addin;

import com.checkba.model.dto.ProjectCreateRequest;
import com.checkba.model.entity.AddinProjectLink;
import com.checkba.model.entity.Project;
import com.checkba.repository.AddinProjectLinkRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.LangText;
import com.checkba.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 插件归档绑定（dev-board#297）：桌面项目 (deviceId, projectKey) ↔ 云端影子容器项目。
 *
 * <p>影子项目是普通 Project（BLANK），只是被 link 表指着：
 * <ul>
 *   <li>会话与附件照常挂在影子项目上（对话/工具/附件链路零改动）；</li>
 *   <li>{@code GET /api/projects/my} 把被指着的项目滤掉——界面上这份工作显示为
 *       「选中了那个桌面项目」，不重复出现一个同名云项目；</li>
 *   <li>对话镜像与文档镜像按 link 把产物路由回 (deviceId, projectKey)。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AddinProjectLinkService {

    private final AddinProjectLinkRepository linkRepository;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;

    /**
     * find-or-create：已有映射且影子项目还在 → 原样返回；影子项目被删过 → 重建并更新映射；
     * 没有映射 → 建影子项目 + 建映射。并发撞唯一约束时读对方已提交的映射、删掉自己刚建的孤儿项目。
     */
    public AddinProjectLink ensureLink(Long userId, String deviceId, String projectKey, String name) {
        if (deviceId == null || deviceId.isBlank() || deviceId.length() > 64) {
            throw new IllegalArgumentException(LangText.of("设备标识不正确", "Invalid device id"));
        }
        if (projectKey == null || projectKey.isBlank() || projectKey.length() > 64) {
            throw new IllegalArgumentException(LangText.of("缺少项目标识", "Missing project key"));
        }
        Optional<AddinProjectLink> existing = linkRepository.findByUserIdAndDeviceIdAndProjectKey(userId, deviceId, projectKey);
        if (existing.isPresent()) {
            AddinProjectLink link = existing.get();
            Optional<Project> project = projectRepository.findById(link.getCloudProjectId());
            if (project.isPresent()) {
                return link;
            }
            // 影子项目已被删（极少数路径）：重建一个，映射行保留、指向新项目
            Project rebuilt = createShadowProject(userId, name);
            link.setCloudProjectId(rebuilt.getId());
            return linkRepository.save(link);
        }
        Project shadow = createShadowProject(userId, name);
        AddinProjectLink link = new AddinProjectLink();
        link.setUserId(userId);
        link.setDeviceId(deviceId);
        link.setProjectKey(projectKey.trim());
        link.setCloudProjectId(shadow.getId());
        link.setCreatedAt(LocalDateTime.now());
        try {
            return linkRepository.save(link);
        } catch (DataIntegrityViolationException e) {
            // 并发建同一映射：对方赢了，读对方的，删掉自己刚建的孤儿影子项目
            log.info("插件归档绑定并发创建，复用既有映射 user={} device={} key={}", userId, deviceId, projectKey);
            try {
                projectService.deleteProject(shadow.getId());
            } catch (Exception cleanup) {
                log.warn("孤儿影子项目 {} 清理失败（无害，仅多占一行）", shadow.getId(), cleanup);
            }
            return linkRepository.findByUserIdAndDeviceIdAndProjectKey(userId, deviceId, projectKey)
                    .orElseThrow(() -> e);
        }
    }

    private Project createShadowProject(Long userId, String name) {
        ProjectCreateRequest request = new ProjectCreateRequest();
        request.setProjectType("BLANK");
        String projectName = name == null || name.isBlank()
                ? LangText.of("插件归档项目", "Plugin archive project") : name.trim();
        request.setName(projectName.length() > 100 ? projectName.substring(0, 100) : projectName);
        return projectService.createProject(request, userId);
    }

    /** 该用户全部映射（插件端重建本地绑定映射用）。 */
    public List<AddinProjectLink> listLinks(Long userId) {
        return linkRepository.findByUserId(userId);
    }

    /** 被映射指着的影子项目 id 集合（项目列表过滤用）。 */
    public Set<Long> linkedCloudProjectIds(Long userId) {
        return linkRepository.findByUserId(userId).stream()
                .map(AddinProjectLink::getCloudProjectId)
                .collect(Collectors.toSet());
    }

    /** 某个云项目对应的映射（对话镜像 outbox 记录点用；绝大多数项目没有映射）。 */
    public Optional<AddinProjectLink> findByCloudProjectId(Long cloudProjectId) {
        if (cloudProjectId == null) return Optional.empty();
        return linkRepository.findByCloudProjectId(cloudProjectId);
    }
}
