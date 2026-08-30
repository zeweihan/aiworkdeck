package com.checkba.controller;

import com.checkba.model.dto.ProjectCardDTO;
import com.checkba.model.dto.ProjectCreateRequest;
import com.checkba.model.entity.Project;
import com.checkba.service.LocalProjectService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    /** 插件懒建项目的固定名称 */
    private static final String ADDIN_DEFAULT_PROJECT_NAME_ZH = "插件临时项目";
    private static final String ADDIN_DEFAULT_PROJECT_NAME_EN = "Plugin Temporary Project";

    private final ProjectService projectService;
    private final ProjectMemberService projectMemberService;
    private final com.checkba.service.LocalProjectService localProjectService;
    private final com.checkba.storage.ProjectStorageResolver storageResolver;

    /**
     * 插件归档绑定（dev-board#297）。可选注入而非构造器参数：既有测试手工 new 本控制器时不受牵连，
     * null 即绑定功能整体关闭（/my 不过滤、ensure-addin-link 报未开放）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.checkba.service.addin.AddinProjectLinkService addinProjectLinkService;

    /**
     * 「把本机文件夹挂成项目」只在单机桌面版成立——那里的「服务器」就是用户自己的电脑，
     * 读自己的磁盘正是这个功能的意义。共享/云端部署里它等于把任意绝对路径交给任意租户当项目根
     * （/etc、别家事务所的数据目录、应用自身的配置与 plugins 目录），故默认关闭，
     * 仅 desktop profile 打开（security.admin.allow-all-users 的同类开关）。
     */
    @Value("${security.local-folder-projects.enabled:false}")
    private boolean localFolderProjectsEnabled;

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
     * Office 插件用：没有任何项目的账号，懒建一个「插件临时项目」。
     *
     * 项目是租户隔离维度（chat/history 等接口都按 projectId 判权），不能放开 null；
     * 但插件用户不该被逼着先去桌面端建项目才能说第一句话。语义：
     * - 已有任一项目 → 不建，返回 {created:false, project:null}（由用户自己选）；
     * - 一个都没有 → 建一个并返回 {created:true, project:{id,name}}。
     *
     * 幂等靠「先查后建」。同一用户从插件并发首发的窗口极小（任务窗格单实例、首启只发一次），
     * 故不引锁；真撞上了最坏结果是多出一个空项目，用户可自行删除。
     * local-mode 与 server 模式行为一致：都只要求有效会话，无特判。
     */
    @PostMapping("/ensure-addin-default")
    public Map<String, Object> ensureAddinDefaultProject(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        Map<String, Object> result = new HashMap<>();
        // 影子项目（归档绑定的容器）不算「已有项目」：只剩绑定的用户仍给一个可自由使用的临时项目
        if (!filterLinkedShadows(projectService.getUserProjectCardDTOs(userId), userId).isEmpty()) {
            result.put("created", false);
            result.put("project", null);
            return result;
        }
        ProjectCreateRequest request = new ProjectCreateRequest();
        request.setProjectType("BLANK");
        request.setName(com.checkba.service.LangText.of(ADDIN_DEFAULT_PROJECT_NAME_ZH, ADDIN_DEFAULT_PROJECT_NAME_EN));
        Project project = projectService.createProject(request, userId);
        Map<String, Object> projectInfo = new HashMap<>();
        projectInfo.put("id", project.getId());
        projectInfo.put("name", project.getName());
        result.put("created", true);
        result.put("project", projectInfo);
        return result;
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
        if (!localFolderProjectsEnabled) {
            throw new IllegalArgumentException(com.checkba.service.LangText.of("当前部署未开放「打开本机文件夹」功能", "This deployment does not support opening a local folder"));
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
        data.put("truncatedCount", r.truncatedCount());
        data.put("truncatedCountCapped", r.truncatedCountCapped());
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
            throw new IllegalArgumentException(com.checkba.service.LangText.of("无权访问此项目", "You do not have access to this project"));
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
            throw new IllegalArgumentException(com.checkba.service.LangText.of("无权访问此项目", "You do not have access to this project"));
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
            throw new IllegalArgumentException(com.checkba.service.LangText.of("无权修改此项目", "You do not have permission to modify this project"));
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
        // 归档绑定的影子项目滤掉（dev-board#297）：界面上这份工作显示为「选中了那个桌面项目」，
        // 不在「我的项目」里重复出现一个同名云项目。桌面端 link 表恒空，无副作用。
        return filterLinkedShadows(projectService.getUserProjectCardDTOs(userId), userId);
    }

    private List<ProjectCardDTO> filterLinkedShadows(List<ProjectCardDTO> cards, Long userId) {
        if (addinProjectLinkService == null || cards.isEmpty()) {
            return cards;
        }
        java.util.Set<Long> linked = addinProjectLinkService.linkedCloudProjectIds(userId);
        if (linked.isEmpty()) {
            return cards;
        }
        return cards.stream().filter(c -> !linked.contains(c.getId()))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 插件归档绑定（dev-board#297）：选中远程设备分组的桌面项目 = find-or-create 云端影子
     * 容器项目并建立映射。body {deviceId, projectKey, name}；返回该映射（含影子项目 id）。
     */
    @PostMapping("/ensure-addin-link")
    public Map<String, Object> ensureAddinLink(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        if (addinProjectLinkService == null) {
            throw new IllegalArgumentException(com.checkba.service.LangText.of(
                    "当前部署未开放插件归档绑定", "This deployment does not support add-in archive binding"));
        }
        com.checkba.model.entity.AddinProjectLink link = addinProjectLinkService.ensureLink(
                userId, body.get("deviceId"), body.get("projectKey"), body.get("name"));
        Map<String, Object> result = new HashMap<>();
        result.put("projectId", link.getCloudProjectId());
        result.put("deviceId", link.getDeviceId());
        result.put("projectKey", link.getProjectKey());
        return result;
    }

    /**
     * 当前用户的全部归档绑定（裸数组）：插件端重建本地「影子项目 ↔ 桌面项目」映射用
     * （Office 清 webview 缓存后 localStorage 会丢，映射的权威源在服务端）。
     */
    @GetMapping("/addin-links")
    public List<Map<String, Object>> listAddinLinks(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (addinProjectLinkService == null) {
            return out;
        }
        for (com.checkba.model.entity.AddinProjectLink link : addinProjectLinkService.listLinks(userId)) {
            Map<String, Object> m = new HashMap<>();
            m.put("projectId", link.getCloudProjectId());
            m.put("deviceId", link.getDeviceId());
            m.put("projectKey", link.getProjectKey());
            out.add(m);
        }
        return out;
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
            throw new IllegalArgumentException(com.checkba.service.LangText.of("无权删除此项目", "You do not have permission to delete this project"));
        }

        projectService.deleteProject(id);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", com.checkba.service.LangText.of("删除成功", "Deleted successfully"));
        return result;
    }

    private Long getUserIdFromSession(String sessionId) {
        return AuthController.getUserIdFromSession(sessionId);
    }
}

