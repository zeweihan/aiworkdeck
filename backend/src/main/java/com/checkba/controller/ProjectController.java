package com.checkba.controller;

import com.checkba.model.dto.ProjectCardDTO;
import com.checkba.model.dto.ProjectCreateRequest;
import com.checkba.model.entity.Project;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectMemberService projectMemberService;

    public ProjectController(ProjectService projectService, ProjectMemberService projectMemberService) {
        this.projectService = projectService;
        this.projectMemberService = projectMemberService;
    }

    @PostMapping
    public Project createProject(
            @RequestBody ProjectCreateRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return projectService.createProject(request, userId);
    }

    @GetMapping("/{id}")
    public Project getProject(
            @PathVariable Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        // 越权校验：此前无鉴权，可枚举任意项目元数据
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        if (!projectMemberService.hasReadPermission(id, userId)) {
            throw new IllegalArgumentException("无权访问此项目");
        }
        return projectService.getProject(id);
    }

    /**
     * 重命名项目
     */
    @PutMapping("/{id}")
    public Project updateProject(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        
        Project project = projectService.getProject(id);
        if (!project.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权修改此项目");
        }

        String newName = body.get("name");
        return projectService.updateProjectName(id, newName);
    }

    /**
     * 获取当前用户的项目列表
     */
    @GetMapping("/my")
    public List<ProjectCardDTO> getMyProjects(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return projectService.getUserProjectCardDTOs(userId);
    }

    /**
     * 删除项目
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteProject(
            @PathVariable Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }

        Project project = projectService.getProject(id);
        // 检查权限：只有项目创建者可以删除
        if (!project.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权删除此项目");
        }

        projectService.deleteProject(id);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "删除成功");
        return result;
    }

    private Long getUserIdFromSession(String sessionId) {
        return AuthController.getUserIdFromSession(sessionId);
    }
}

