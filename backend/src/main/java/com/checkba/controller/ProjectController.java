package com.checkba.controller;

import com.checkba.model.dto.ProjectCardDTO;
import com.checkba.model.dto.ProjectCreateRequest;
import com.checkba.model.entity.Project;
import com.checkba.service.LocalProjectService;
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
    private final com.checkba.service.LocalProjectService localProjectService;
    private final com.checkba.storage.ProjectStorageResolver storageResolver;

    public ProjectController(ProjectService projectService, ProjectMemberService projectMemberService,
                             com.checkba.service.LocalProjectService localProjectService,
                             com.checkba.storage.ProjectStorageResolver storageResolver) {
        this.projectService = projectService;
        this.projectMemberService = projectMemberService;
        this.localProjectService = localProjectService;
        this.storageResolver = storageResolver;
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

    /**
     * IDE 化本地文件夹项目：打开/新建本地文件夹作为项目。
     * body: { localRoot: 绝对路径, createFolder: 是否先建目录, name: 可选项目名, openFileName: 可选，随后要打开的根级文件名 }
     * 同一 localRoot 重复打开返回既有项目（reused=true），并幂等重扫导入。
     */
    @PostMapping("/open-local")
    public Map<String, Object> openLocalFolder(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        LocalProjectService.OpenLocalResult r = localProjectService.openLocalFolder(
                (String) body.get("localRoot"),
                Boolean.TRUE.equals(body.get("createFolder")),
                (String) body.get("name"),
                (String) body.get("openFileName"),
                userId);
        Map<String, Object> data = new HashMap<>();
        data.put("projectId", r.project().getId());
        data.put("name", r.project().getName());
        data.put("reused", r.reused());
        data.put("openFileId", r.openFileId());
        data.put("importedCount", r.importedCount());
        data.put("truncated", r.truncated());
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", data);
        return result;
    }

    /**
     * 项目物理根目录（「在 Finder 中显示」用）。托管项目也有物理目录，一样可用。
     */
    @GetMapping("/{id}/local-path")
    public Map<String, Object> getLocalPath(
            @PathVariable Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        if (!projectMemberService.hasReadPermission(id, userId)) {
            throw new IllegalArgumentException("无权访问此项目");
        }
        java.nio.file.Path root = storageResolver.projectRoot(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", Map.of(
                "path", root.toString(),
                "exists", java.nio.file.Files.isDirectory(root),
                "isLocalRoot", storageResolver.hasLocalRoot(id)));
        return result;
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

