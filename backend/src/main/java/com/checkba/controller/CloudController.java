package com.checkba.controller;

import com.checkba.model.entity.CloudConnection;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import com.checkba.version.CloudSyncService;
import com.checkba.version.VersionException;
import com.checkba.version.WorkSessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 云端协作接口。纯转发层——业务语义全在 CloudSyncService，这里只做鉴权+参数搬运+响应封装。
 * 响应封装/异常处理照 VersionController 抄一份：HTTP 一律 200，用 code 区分成败。
 *
 * 鉴权分两档：连接级端点（连接/断开/连接列表/远端项目列表/接入）要求登录，并且
 * 只能操作自己名下的连接——连接里存着长期设备令牌，多人共用一个后端时不按人隔离，
 * 任何账号都能借别人的令牌列/克隆对方的云端项目；项目级端点（共享/状态/上传/更新/裁决/成员代理）
 * 走 requireMemberNonClient 三连，同版本记录接口一样拒绝 CLIENT 角色；其中改写仓库或
 * 云端成员的写端点再追加 hasWritePermission（requireWriteMember，拒 READ_ONLY）。
 */
@RestController
@RequestMapping("/api/cloud")
public class CloudController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CloudController.class);

    private final CloudSyncService cloudSyncService;
    private final ProjectMemberService projectMemberService;
    private final UserService userService;

    public CloudController(CloudSyncService cloudSyncService,
                            ProjectMemberService projectMemberService,
                            UserService userService) {
        this.cloudSyncService = cloudSyncService;
        this.projectMemberService = projectMemberService;
        this.userService = userService;
    }

    // ==================== 连接级：只要求登录 ====================

    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireLogin(sessionId);
        CloudConnection conn = cloudSyncService.connect(
                body.get("serverUrl"), body.get("username"), body.get("password"), body.get("deviceName"), userId);
        Map<String, Object> data = new HashMap<>();
        data.put("connectionId", conn.getId());
        data.put("username", conn.getUsername());
        data.put("displayName", conn.getDisplayName());
        data.put("serverUrl", conn.getServerUrl());
        return ok(data);
    }

    @GetMapping("/connections")
    public ResponseEntity<Map<String, Object>> connections(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireLogin(sessionId);
        List<Map<String, Object>> list = cloudSyncService.listConnections(userId).stream()
                .map(this::connectionListItem)
                .toList();
        return ok(Map.of("connections", list));
    }

    @PostMapping("/connections/{id}/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(
            @PathVariable Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireLogin(sessionId);
        cloudSyncService.disconnect(id, userId);
        return ok(Map.of());
    }

    @GetMapping("/connections/{id}/remote-projects")
    public ResponseEntity<Map<String, Object>> remoteProjects(
            @PathVariable Long id,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireLogin(sessionId);
        return ok(Map.of("projects", cloudSyncService.listRemoteProjects(id, userId)));
    }

    @PostMapping("/accept")
    public ResponseEntity<Map<String, Object>> accept(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireLogin(sessionId);
        long connectionId = ((Number) body.get("connectionId")).longValue();
        long remoteProjectId = ((Number) body.get("remoteProjectId")).longValue();
        return ok(cloudSyncService.cloneFromCloud(connectionId, remoteProjectId, userId));
    }

    /** {id, serverUrl, username, displayName}——绝不带 deviceToken，防泄漏。 */
    private Map<String, Object> connectionListItem(CloudConnection conn) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", conn.getId());
        m.put("serverUrl", conn.getServerUrl());
        m.put("username", conn.getUsername());
        m.put("displayName", conn.getDisplayName());
        return m;
    }

    // ==================== 项目级：requireMemberNonClient 三连 ====================

    @PostMapping("/projects/{projectId}/share")
    public ResponseEntity<Map<String, Object>> share(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        long connectionId = ((Number) body.get("connectionId")).longValue();
        return ok(cloudSyncService.shareToCloud(projectId, connectionId, userId));
    }

    @GetMapping("/projects/{projectId}/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberNonClient(projectId, sessionId);
        return ok(cloudSyncService.cloudStatus(projectId));
    }

    @PostMapping("/projects/{projectId}/check")
    public ResponseEntity<Map<String, Object>> check(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberNonClient(projectId, sessionId);
        return ok(cloudSyncService.checkCloud(projectId));
    }

    @PostMapping("/projects/{projectId}/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireWriteMember(projectId, sessionId);
        CloudSyncService.UploadResult r = cloudSyncService.uploadToCloud(projectId, false);
        return ok(Map.of(
                "status", r.status().name(),
                "message", r.message() == null ? "" : r.message(),
                "affectedFileIds", r.affectedFileIds() == null ? List.of() : r.affectedFileIds()));
    }

    @PostMapping("/projects/{projectId}/update")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        CloudSyncService.UpdateResult r =
                cloudSyncService.updateFromCloud(projectId, userId, userName(userId));
        return ok(updateResultData(r));
    }

    @PostMapping("/projects/{projectId}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Map<String, String>> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        Map<String, WorkSessionService.Resolution> resolutions = parseResolutions(body);
        CloudSyncService.UpdateResult r =
                cloudSyncService.resolveCloudMerge(projectId, resolutions, userId, userName(userId));
        return ok(updateResultData(r));
    }

    @PostMapping("/projects/{projectId}/abort")
    public ResponseEntity<Map<String, Object>> abort(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireWriteMember(projectId, sessionId);
        String notice = cloudSyncService.abortCloudMerge(projectId);
        return okWithMessage(Map.of(), notice);
    }

    @GetMapping("/projects/{projectId}/members")
    public ResponseEntity<Map<String, Object>> members(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMemberNonClient(projectId, sessionId);
        return ok(Map.of("members", cloudSyncService.proxyMembers(projectId)));
    }

    @PostMapping("/projects/{projectId}/members")
    public ResponseEntity<Map<String, Object>> addMember(
            @PathVariable Long projectId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireWriteMember(projectId, sessionId);
        String role = body == null || body.get("role") == null ? "PARTICIPANT" : body.get("role");
        cloudSyncService.proxyMembers(projectId, body == null ? null : body.get("username"), role);
        return ok(Map.of());
    }

    /** update/resolve 共用的响应形状：UpdateResult 平铺。 */
    private Map<String, Object> updateResultData(CloudSyncService.UpdateResult r) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", r.status().name());
        m.put("affectedFileIds", r.affectedFileIds() == null ? List.of() : r.affectedFileIds());
        m.put("conflict", r.conflict());
        return m;
    }

    /** resolve 请求体：整个 body 就是 resolutions 的外壳，形制同 VersionController.parseResolutions。 */
    private Map<String, WorkSessionService.Resolution> parseResolutions(Map<String, Map<String, String>> body) {
        Map<String, String> raw = body == null ? null : body.get("resolutions");
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, WorkSessionService.Resolution> out = new HashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            try {
                out.put(e.getKey(), WorkSessionService.Resolution.valueOf(e.getValue()));
            } catch (Exception ex) {
                throw VersionException.userFacing("无效的选择");
            }
        }
        return out;
    }

    /**
     * message 可能带 Git 术语/内部细节，一律不得原样回显给律师。只有标记为 userFacing 的
     * 业务性异常才展示其 message，其余一律用通用措辞；技术细节只进日志。
     *
     * 兜底那句直接弹成 toast（前端每个 catch 都是 showToast(e.message)），所以它也归
     * PR-E 的术语纪律管：「云端」是已经作废的说法，律师在「交稿/取回」的流程里撞见它
     * 会一头雾水。日志侧保留原词，那是给工程看的。
     */
    @ExceptionHandler(VersionException.class)
    public ResponseEntity<Map<String, Object>> onVersionError(VersionException e) {
        log.warn("云端协作操作失败", e);
        String message = e.isUserFacing() ? e.getMessage() : "这次协作操作没能完成，请稍后重试";
        return ResponseEntity.ok(Map.of("code", 1, "message", message));
    }

    /** 只校验登录，不校验项目成员/角色——连接级端点用。 */
    private Long requireLogin(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        return userId;
    }

    /** 校验并返回当前用户 id。非成员或 CLIENT 一律拒绝——项目级端点用。 */
    private Long requireMemberNonClient(Long projectId, String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) throw new IllegalArgumentException("未登录");
        if (!projectMemberService.hasReadPermission(projectId, userId)) {
            throw new IllegalArgumentException("无权访问该项目");
        }
        if (projectMemberService.isClient(projectId, userId)) {
            throw new IllegalArgumentException("无权访问该项目");
        }
        return userId;
    }

    /**
     * 项目级写端点（共享/上传/更新/裁决/中止/加成员）在成员校验之上追加写权限
     * （v2 终审 I4）：READ_ONLY 成员可看云端状态，但不得改写仓库或云端成员。
     * 口径同 VersionController.requireWriteMember；参数序 (projectId, userId)（地雷 #3）。
     */
    private Long requireWriteMember(Long projectId, String sessionId) {
        Long userId = requireMemberNonClient(projectId, sessionId);
        if (!projectMemberService.hasWritePermission(projectId, userId)) {
            throw new IllegalArgumentException("无权修改该项目");
        }
        return userId;
    }

    private String userName(Long userId) {
        try {
            var u = userService.getUserById(userId);
            if (u != null && u.getUsername() != null) return u.getUsername();
        } catch (Exception e) {
            log.warn("取用户名失败: userId={}", userId, e);
        }
        return "用户";
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }

    private ResponseEntity<Map<String, Object>> okWithMessage(Map<String, Object> data, String message) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data, "message", message));
    }
}
