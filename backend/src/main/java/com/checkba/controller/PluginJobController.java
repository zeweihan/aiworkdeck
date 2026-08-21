package com.checkba.controller;

import com.checkba.model.entity.PluginJob;
import com.checkba.service.LangText;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.plugin.PluginJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 插件后台任务 REST（插件规范 v2.4 §11 Jobs）。
 * 鉴权：登录 + 项目成员（读）；取消要写权限（CLIENT 角色只能看）。
 * 没有 projectId 的任务（插件在无项目上下文下起的）只有 admin 能看，这里不开放。
 */
@RestController
@RequestMapping("/api/plugin-jobs")
@RequiredArgsConstructor
public class PluginJobController {

    private final PluginJobService jobs;
    private final ProjectMemberService projectMemberService;

    private Long uid(String sessionId) {
        Long u = AuthController.getUserIdFromSession(sessionId);
        if (u == null) throw new IllegalArgumentException("请先登录");
        return u;
    }

    private void requireRead(Long projectId, Long userId) {
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权访问该资源", "You don't have permission to access this resource"));
        }
    }

    @GetMapping
    public List<PluginJob> list(@RequestParam Long projectId,
                                @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireRead(projectId, uid(sessionId));
        return jobs.listByProject(projectId);
    }

    @GetMapping("/{id}")
    public PluginJob get(@PathVariable String id,
                         @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long u = uid(sessionId);
        PluginJob j = jobs.get(id);
        if (j == null) throw new IllegalArgumentException(LangText.of("任务不存在: ", "Job not found: ") + id);
        requireRead(j.getProjectId(), u);
        return j;
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id,
                                      @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long u = uid(sessionId);
        PluginJob j = jobs.get(id);
        if (j == null) throw new IllegalArgumentException(LangText.of("任务不存在: ", "Job not found: ") + id);
        if (j.getProjectId() == null || !projectMemberService.hasWritePermission(j.getProjectId(), u)) {
            throw new IllegalArgumentException(LangText.of("无权访问该资源", "You don't have permission to access this resource"));
        }
        jobs.cancel(id);
        return Map.of("jobId", id, "status", jobs.status(id).status());
    }
}
