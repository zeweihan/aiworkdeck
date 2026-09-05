package com.checkba.controller;

import com.checkba.model.entity.CloudConnection;
import com.checkba.service.LangText;
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
    private final com.checkba.version.OfficialCloudService officialCloudService;
    private final ProjectMemberService projectMemberService;
    private final UserService userService;
    private final com.checkba.service.telemetry.TelemetryService telemetryService;

    public CloudController(CloudSyncService cloudSyncService,
                            com.checkba.version.OfficialCloudService officialCloudService,
                            ProjectMemberService projectMemberService,
                            UserService userService,
                            com.checkba.service.telemetry.TelemetryService telemetryService) {
        this.cloudSyncService = cloudSyncService;
        this.officialCloudService = officialCloudService;
        this.projectMemberService = projectMemberService;
        this.userService = userService;
        this.telemetryService = telemetryService;
    }

    // ==================== 连接级：只要求登录 ====================

    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireLogin(sessionId);
        CloudConnection conn = cloudSyncService.connect(
                requireText(body, "serverUrl"), requireText(body, "username"), requireText(body, "password"),
                optionalText(body, "deviceName", LangText.of("本机", "This device")), userId);
        Map<String, Object> data = new HashMap<>();
        data.put("connectionId", conn.getId());
        data.put("username", conn.getUsername());
        data.put("displayName", conn.getDisplayName());
        data.put("serverUrl", conn.getServerUrl());
        return ok(data);
    }

    /**
     * 官方团队案件库的状态：{@code {available, connected, serverUrl, username}}。
     * 界面据此决定「放进案件库」是直接可点（一键即连），还是要先让律师去连一个自建的。
     */
    @GetMapping("/official")
    public ResponseEntity<Map<String, Object>> official(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireLogin(sessionId);
        return ok(officialCloudService.status(userId));
    }

    /** 用本机的官网账户一键连上官方案件库（幂等，见 OfficialCloudService.connectOfficial）。 */
    @PostMapping("/connect-official")
    public ResponseEntity<Map<String, Object>> connectOfficial(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireLogin(sessionId);
        CloudConnection conn = officialCloudService.connectOfficial(userId);
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
        long connectionId = requireLong(body, "connectionId");
        long remoteProjectId = requireLong(body, "remoteProjectId");
        Map<String, Object> cloned = cloudSyncService.cloneFromCloud(connectionId, remoteProjectId, userId);
        telemetryService.record("project.created",
                Map.of("kind", "cloud", "reused", false, "importedCount", 0));
        return ok(cloned);
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
        // connectionId 可缺省：没指定就自动连官方案件库再共享（零配置直连，dev-board#439）。
        // 共享本身的守卫（未共享过 / 已开版本记录 / 不在合并窗口）仍在 CloudSyncService 里。
        return ok(officialCloudService.shareProject(projectId, userId, optionalLong(body, "connectionId")));
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

    /**
     * 查人（dev-board#444）：律师输手机号/邮箱，先回一张展示名 + 头像 + 打码联系方式的
     * 卡片，确认了才走加人。纯转发——解析、打码、限频都在案件库那一侧。
     */
    @GetMapping("/projects/{projectId}/members/lookup")
    public ResponseEntity<Map<String, Object>> lookupMember(
            @PathVariable Long projectId,
            @RequestParam(value = "identifier", required = false) String identifier,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireWriteMember(projectId, sessionId);
        return ok(cloudSyncService.proxyMemberLookup(projectId, identifier));
    }

    @PostMapping("/projects/{projectId}/members")
    public ResponseEntity<Map<String, Object>> addMember(
            @PathVariable Long projectId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireWriteMember(projectId, sessionId);
        String role = optionalText(body, "role", "PARTICIPANT");
        // 律师输入的是同事的手机号或邮箱（他不知道对方在案件库里的账号名——那是 awdk
        // 桥自动生成的 awd_ 前缀串）。解析在服务端 ProjectMemberService.resolveMemberUser，
        // 这里只透传。仍收 username 键是为了兼容老客户端。
        String identifier = body != null && body.get("identifier") != null
                ? requireText(body, "identifier") : requireText(body, "username");
        cloudSyncService.proxyMembers(projectId, identifier, role);
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
                throw VersionException.userFacing(LangText.of("无效的选择", "Invalid choice"));
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
        String message = e.isUserFacing() ? e.getMessage() : LangText.of("这次协作操作没能完成，请稍后重试", "This collaboration action didn't finish — please try again later");
        return ResponseEntity.ok(Map.of("code", 1, "message", message));
    }

    /** 只校验登录，不校验项目成员/角色——连接级端点用。 */
    /**
     * 请求体里的必填数字。
     *
     * <p>此前是 {@code ((Number) body.get(key)).longValue()}：缺字段就 NPE、传字符串就
     * ClassCastException，两者都被兜成「服务器内部错误」——用户与调用方都不知道
     * 真正的原因只是请求少写了一个字段。数字串也一并收下，客户端把 id 序列化成字符串很常见。
     */
    private static long requireLong(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignore) {
                // 落到下面统一报错
            }
        }
        throw new IllegalArgumentException(LangText.of(
                "参数 " + key + " 缺失或不是数字", "Parameter " + key + " is missing or not a number"));
    }

    /**
     * 请求体里的可选数字：**缺失或空白**才返回 null，写了但不是数字仍然报错。
     *
     * <p>不能把「写错了」也当成「没写」：share 的 connectionId 缺省语义是「放进官方
     * 案件库」，把一个拼错的 id 静默当成缺省，等于把案卷送去了调用方根本没指定的地方。
     */
    private static Long optionalLong(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String str) {
            if (str.isBlank()) return null;
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignore) {
                // 落到下面统一报错
            }
        }
        throw new IllegalArgumentException(LangText.of(
                "参数 " + key + " 不是数字", "Parameter " + key + " is not a number"));
    }

    /** 请求体里的必填文本。空串与纯空白按缺失处理。 */
    private static String requireText(Map<String, String> body, String key) {
        String v = body == null ? null : body.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(LangText.of(
                    "参数 " + key + " 不能为空", "Parameter " + key + " must not be empty"));
        }
        return v.trim();
    }

    /** 可选文本，缺失时用默认值（下游 Map.of 不收 null，给个默认比放行 null 安全）。 */
    private static String optionalText(Map<String, String> body, String key, String fallback) {
        String v = body == null ? null : body.get(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

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
            throw new IllegalArgumentException(LangText.of("无权访问该项目", "You don't have access to this project"));
        }
        if (projectMemberService.isClient(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权访问该项目", "You don't have access to this project"));
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
            throw new IllegalArgumentException(LangText.of("无权修改该项目", "You don't have permission to modify this project"));
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
        return LangText.of("用户", "User");
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }

    private ResponseEntity<Map<String, Object>> okWithMessage(Map<String, Object> data, String message) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data, "message", message));
    }
}
