package com.checkba.controller;

import com.checkba.service.ProjectMemberService;
import com.checkba.service.ProjectOverviewService;
import com.checkba.service.ProjectProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 项目概览页（pages/project-home，产品语言里的「项目概览页」，不是工作台
 * pages/project-overview）的只读端点。
 *
 * <p>类级路径与 ProjectController(/api/projects) 不相交：那边占 POST /、
 * GET|PUT|DELETE /{id}、/my、/{id}/local-path 等，这边全部多一段。</p>
 *
 * <p>响应一律信封 {code, data}，且一律返回自己组装的 Map，不下发实体
 * （GET /api/projects/{id} 现在返裸实体是遗留问题，别照抄）。</p>
 *
 * <p><b>本文件是三个后端切片共用的骨架，后来的组只追加方法与构造器参数，不要重写整文件。</b>
 * 约定：requireRead/requireWrite 的参数序恒为 (Long projectId, String sessionId)；
 * 端点返回类型恒为 ResponseEntity&lt;Map&lt;String,Object&gt;&gt;；依赖注入用显式构造器，
 * 不引 Lombok 的 @RequiredArgsConstructor。写端点用的
 * {@code private Long requireWrite(Long projectId, String sessionId)}
 * 随 PUT /profile/{fieldKey} 一起追加（没有调用方就不预先写）。</p>
 *
 * <p>AuthController.getUserIdFromSession 是 public static（AuthController.java:640），
 * 直接调，不把 AuthController 注入进来——注入一个 @RestController bean 只为调静态方法，
 * 既无必要又会给每个单测凭空加一个 @Mock。后续组也不要加这个字段。</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectOverviewController {

    private final ProjectMemberService projectMemberService;
    private final ProjectOverviewService overviewService;
    private final ProjectProfileService projectProfileService;

    public ProjectOverviewController(ProjectMemberService projectMemberService,
                                     ProjectOverviewService overviewService,
                                     ProjectProfileService projectProfileService) {
        this.projectMemberService = projectMemberService;
        this.overviewService = overviewService;
        this.projectProfileService = projectProfileService;
    }

    /**
     * 读端点的统一入口：必须登录 + 必须是项目成员。返回 userId。
     *
     * <p>不拒 CLIENT——统计条/档案/会话列表/任务都是客户该看见的那一层。
     * 地雷：hasReadPermission 的参数序是 (projectId, userId)，两个都是 Long，
     * 写反了能编译通过、运行时静默返回 false。</p>
     *
     * <p>抛 IllegalArgumentException 而不是返 401/403：GlobalExceptionHandler:69-77
     * 把它统一转成 HTTP 200 + {code:1,message}，全站 90+ 端点同一口径。</p>
     */
    private Long requireRead(Long projectId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (projectId == null || !projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException("无权访问该项目");
        }
        return userId;
    }

    /** 登录 + 写权限 + 拒 CLIENT。返回 userId。参数序恒为 (projectId, sessionId)。 */
    private Long requireWrite(Long projectId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        // hasWritePermission 已天然只放行 ADMIN/PARTICIPANT + owner，但 isClient 是三个字面量的
        // 显式 or（不是 startsWith("CLIENT")），新增 CLIENT_* 角色时会漏判——显式双判是第二道闸。
        if (projectId == null
                || !projectMemberService.hasWritePermission(projectId, userId)
                || projectMemberService.isClient(projectId, userId)) {
            throw new IllegalArgumentException("无权修改该项目");
        }
        return userId;
    }

    /** 概览页统计条：一次请求喂满整块（文件/文件夹计数、成员数、localRoot、后台 AI 任务）。 */
    @GetMapping("/overview/stats")
    public ResponseEntity<Map<String, Object>> overviewStats(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireRead(projectId, sessionId);
        return ok(overviewService.stats(projectId));
    }

    /**
     * 日程与任务。A 期恒空数组，概览页据此渲染空态；B 期接上任务系统时只换实现，
     * 路径与响应形状一行不改，前端零改动。所以这里不建 service、不建实体。
     * B 期的任务 CRUD 另起 TaskController(/api/tasks)，但这条列表端点保持在
     * /api/projects/{projectId}/tasks 不迁移。
     */
    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> tasks(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireRead(projectId, sessionId);
        return ok(Map.of("tasks", List.of()));
    }

    // ==================== 项目档案 ====================

    /** 档案读：固定五个字段全量返回，未填的也返回、值为 null。不拒 CLIENT。 */
    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> getProfile(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireRead(projectId, sessionId);
        List<Map<String, Object>> fields = projectProfileService.getProfile(projectId);
        return ok(Map.of("fields", fields));
    }

    /** 档案手填单字段（A 期唯一的写入通道）。value 为空即清空该字段。 */
    @PutMapping("/profile/{fieldKey}")
    public ResponseEntity<Map<String, Object>> saveProfileField(
            @PathVariable Long projectId,
            @PathVariable String fieldKey,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireWrite(projectId, sessionId);
        String value = body == null ? null : body.get("value");
        return ok(projectProfileService.saveUserField(projectId, fieldKey, value));
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }
}
