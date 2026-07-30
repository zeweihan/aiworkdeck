package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本记录接口。术语对齐 spec 第四节——返回给前端的一切文案都不得出现 Git 词汇。
 *
 * 权限：项目成员可见；CLIENT（客户）一律拒绝——版本历史里有律师的内部草稿。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/version")
public class VersionController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(VersionController.class);

    private final ProjectRepoService repoService;
    private final WorkSessionService sessionService;
    private final ProjectMemberService projectMemberService;
    private final UserService userService;
    private final ProjectFileService projectFileService;

    public VersionController(ProjectRepoService repoService,
                             WorkSessionService sessionService,
                             ProjectMemberService projectMemberService,
                             UserService userService,
                             ProjectFileService projectFileService) {
        this.repoService = repoService;
        this.sessionService = sessionService;
        this.projectMemberService = projectMemberService;
        this.userService = userService;
        this.projectFileService = projectFileService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        Map<String, Object> data = new HashMap<>();
        boolean enabled = repoService.isInitialized(projectId);
        data.put("enabled", enabled);
        if (enabled) {
            var active = sessionService.activeSession(projectId);
            data.put("working", active.isPresent());
            data.put("sessionTitle", active.map(WorkSession::getTitle).orElse(null));
            data.put("changedCount", sessionService.pendingChangesLocked(projectId).stream()
                    .filter(c -> !c.path().startsWith(".awd/")).count());
            data.put("pendingRecovery", sessionService.pendingRecovery(projectId).isPresent());
        } else {
            data.put("working", false);
            data.put("changedCount", 0);
            data.put("pendingRecovery", false);
        }
        return ok(data);
    }

    @PostMapping("/enable")
    public ResponseEntity<Map<String, Object>> enable(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        sessionService.enableVersionRecording(projectId, userName(userId), email(userId));
        return ok(Map.of("enabled", true));
    }

    @GetMapping("/timeline")
    public ResponseEntity<Map<String, Object>> timeline(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        List<VersionEntry> entries;
        if (fileId != null) {
            ProjectFile f = projectFileService.getFile(fileId); // 文件不存在会抛异常
            if (!projectId.equals(f.getProjectId())) {
                throw new IllegalArgumentException("文件不属于该项目: fileId=" + fileId);
            }
            String relPath = WorkSessionService.repoRelativePath(f);
            entries = repoService.logForPath(projectId, "HEAD", relPath, limit);
        } else {
            entries = repoService.log(projectId, "HEAD", limit);
        }
        return ok(Map.of("versions", entries));
    }

    @GetMapping("/versions/{sha}/changes")
    public ResponseEntity<Map<String, Object>> changes(
            @PathVariable Long projectId,
            @PathVariable String sha,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        List<FileChange> changes = repoService.diffNameStatus(projectId, sha + "^", sha)
                .stream().filter(c -> !c.path().startsWith(".awd/")).toList();
        return ok(Map.of("changes", changes));
    }

    @PostMapping("/session/end")
    public ResponseEntity<Map<String, Object>> endSession(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        String title = body == null ? null : body.get("title");
        String sha = sessionService.endSession(projectId, userId, userName(userId), title);
        return ok(Map.of("sha", sha == null ? "" : sha));
    }

    @PostMapping("/session/discard")
    public ResponseEntity<Map<String, Object>> discardSession(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        sessionService.discardSession(projectId, userId);
        return ok(Map.of("discarded", true));
    }

    @PostMapping("/session/resume")
    public ResponseEntity<Map<String, Object>> resumeSession(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        sessionService.resumeSession(projectId);
        return ok(Map.of("resumed", true));
    }

    @GetMapping("/versions/{ref}/file-bytes")
    public ResponseEntity<byte[]> fileBytesAtRef(
            @PathVariable Long projectId, @PathVariable String ref,
            @RequestParam("path") String path,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        String rel = WorkSessionService.safeRepoPath(path);
        byte[] bytes = repoService.readBlobAtCommit(projectId, ref, rel);
        if (bytes == null) {
            throw VersionException.userFacing("这一版里没有这份文件");
        }
        return ResponseEntity.ok()
                .header("Content-Type", "application/octet-stream")
                .body(bytes);
    }

    @GetMapping("/versions/{ref}/file-text")
    public ResponseEntity<Map<String, Object>> fileTextAtRef(
            @PathVariable Long projectId, @PathVariable String ref,
            @RequestParam("path") String path,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        String rel = WorkSessionService.safeRepoPath(path);
        byte[] bytes = repoService.readBlobAtCommit(projectId, ref, rel);
        if (bytes == null) {
            throw VersionException.userFacing("这一版里没有这份文件");
        }
        try (java.io.InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            org.apache.tika.Tika tika = new org.apache.tika.Tika();
            String text = tika.parseToString(in);
            return ok(Map.of("text", text == null ? "" : text));
        } catch (Exception e) {
            log.warn("版本文本抽取失败: project={}, ref={}", projectId, ref, e);
            throw new VersionException("文本抽取失败", e);
        }
    }

    @PostMapping("/versions/{sha}/milestone")
    public ResponseEntity<Map<String, Object>> markMilestone(
            @PathVariable Long projectId, @PathVariable String sha,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        String name = body == null ? null : body.get("name");
        if (name == null || name.isBlank()) {
            throw VersionException.userFacing("请给重要版本起个名字");
        }
        if (name.strip().length() > 64) {
            throw VersionException.userFacing("名字太长了，请控制在 64 字以内");
        }
        repoService.tagMilestone(projectId, sha, name.strip());
        return ok(Map.of("marked", true));
    }

    @PostMapping("/revert")
    public ResponseEntity<Map<String, Object>> revert(
            @PathVariable Long projectId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        String sha = sessionService.revertTo(
                projectId, body.get("ref"), userId, userName(userId));
        return ok(Map.of("sha", sha == null ? "" : sha));
    }

    /**
     * message 可能带 Git 术语/内部分支名（见 ProjectRepoService），一律不得原样回显给律师。
     * 只有标记为 userFacing 的业务性异常（见 WorkSessionService）才展示其 message，
     * 其余一律用通用措辞；技术细节只进日志。对齐房规：HTTP 一律 200，用 code 区分成败。
     */
    @ExceptionHandler(VersionException.class)
    public ResponseEntity<Map<String, Object>> onVersionError(VersionException e) {
        log.warn("版本记录操作失败", e);
        String message = e.isUserFacing() ? e.getMessage() : "版本记录操作失败，请重试";
        return ResponseEntity.ok(Map.of("code", 1, "message", message));
    }

    /** 校验并返回当前用户 id。非成员或 CLIENT 一律拒绝。 */
    private Long requireMember(Long projectId, String sessionId) {
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

    private String userName(Long userId) {
        try {
            var u = userService.getUserById(userId);
            if (u != null && u.getUsername() != null) return u.getUsername();
        } catch (Exception e) {
            log.warn("取用户名失败: userId={}", userId, e);
        }
        return "用户";
    }

    private String email(Long userId) {
        return "user-" + userId + "@aiworkdeck.local";
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }
}
