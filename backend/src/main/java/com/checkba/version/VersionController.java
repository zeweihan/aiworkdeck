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
            data.put("onDraft", sessionService.activeDraftOnBranch(projectId)
                    .map(this::draftRef).orElse(null));
            data.put("adoptConflict", adoptConflictStatus(projectId));
        } else {
            data.put("working", false);
            data.put("changedCount", 0);
            data.put("pendingRecovery", false);
            data.put("onDraft", null);
            data.put("adoptConflict", null);
        }
        return ok(data);
    }

    /** {id, name} 形状——onDraft 与 drafts 列表共用。 */
    private Map<String, Object> draftRef(WorkSession draft) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", draft.getId());
        m.put("name", draft.getTitle());
        return m;
    }

    /**
     * 冲突态（MERGING）反查：仓库不在合并中时返回 null。在合并中时反查
     * {@code MERGE_HEAD} 对应哪一稿——正常路径下一定能找到（只有 ACTIVE 的稿能进入
     * 采纳流程）；查不到时是异常残局（比如稿在裁决过程中被并发放弃/数据被改动），
     * 仍然要给出 adoptConflict（draftId/draftName 为 null），前端据此至少能提供
     * 「先不采纳」这道逃生门，不能因为反查失败就对律师隐瞒"仓库停在合并中"这件事。
     * conflictingPaths 一律过滤 {@code .awd/}——律师不可见铁律。
     *
     * mainlineTip/draftTip：给前端「对比」按钮用的两个 ref（Task 7 配套）——合并未提交时
     * HEAD 仍停在合并前的主线 tip，MERGE_HEAD 就是稿的 tip，两者都已经在本方法里查过，
     * 顺手带出即可，不必再多查一次。
     */
    private Map<String, Object> adoptConflictStatus(long projectId) {
        if (!repoService.repositoryMerging(projectId)) return null;
        String mergeHeadSha = repoService.mergeHeadRef(projectId);
        WorkSession matched = mergeHeadSha == null ? null : sessionService.listDrafts(projectId).stream()
                .filter(d -> mergeHeadSha.equals(repoService.resolveRef(projectId, d.getBranchName())))
                .findFirst()
                .orElse(null);
        List<String> conflicts = repoService.conflictingPaths(projectId).stream()
                .filter(p -> !p.startsWith(".awd/"))
                .toList();
        Map<String, Object> m = new HashMap<>();
        m.put("draftId", matched == null ? null : matched.getId());
        m.put("draftName", matched == null ? null : matched.getTitle());
        m.put("conflictingPaths", conflicts);
        m.put("mainlineTip", repoService.resolveRef(projectId, "HEAD"));
        m.put("draftTip", mergeHeadSha);
        return m;
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
                // 拒绝消息不带 fileId：越权探测者不该从错误文案里拿到内部 id 的存在性回执。
                throw new IllegalArgumentException("无权访问该文件");
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
        WorkSessionService.SessionEndResult r =
                sessionService.endSession(projectId, userId, userName(userId), title);
        // notice 非空 = 结束成功但没生成版本（空工作段）。仍然是成功（code=0），
        // 前端凭它决定要不要多 toast 一句，不能走异常分支——见 SessionEndResult 注释。
        return ok(Map.of(
                "sha", r.sha() == null ? "" : r.sha(),
                "notice", r.notice() == null ? "" : r.notice()));
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
        WorkSessionService.RevertResult result = sessionService.revertTo(
                projectId, body.get("ref"), userId, userName(userId));
        return ok(Map.of(
                "sha", result.sha() == null ? "" : result.sha(),
                "affectedFileIds", result.affectedFileIds()));
    }

    // ---- 稿：创建、双向切线、采纳/裁决/中止/放弃（spec 第 3 期 Task 5） --------

    @PostMapping("/draft")
    public ResponseEntity<Map<String, Object>> createDraft(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        String ref = body == null ? null : body.get("ref");
        String name = body == null ? null : body.get("name");
        WorkSessionService.DraftCreateResult result =
                sessionService.createDraft(projectId, ref, name, userId, userName(userId));
        return ok(Map.of(
                "draftId", result.draft().getId(),
                "branch", result.lineSwitch().branch(),
                "affectedFileIds", result.lineSwitch().affectedFileIds()));
    }

    @GetMapping("/drafts")
    public ResponseEntity<Map<String, Object>> listDrafts(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        List<Map<String, Object>> drafts = sessionService.listDrafts(projectId).stream()
                .map(d -> {
                    Map<String, Object> m = draftRef(d);
                    m.put("startedAt", d.getStartedAt());
                    return m;
                })
                .toList();
        return ok(Map.of("drafts", drafts));
    }

    @PostMapping("/draft/{id}/switch")
    public ResponseEntity<Map<String, Object>> switchToDraft(
            @PathVariable Long projectId, @PathVariable("id") Long draftId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        WorkSessionService.LineSwitchResult result =
                sessionService.switchToDraft(projectId, draftId, userId, userName(userId));
        return ok(Map.of("affectedFileIds", result.affectedFileIds()));
    }

    @PostMapping("/switch-mainline")
    public ResponseEntity<Map<String, Object>> switchToMainline(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        WorkSessionService.LineSwitchResult result =
                sessionService.switchToMainline(projectId, userId, userName(userId));
        return ok(Map.of("affectedFileIds", result.affectedFileIds()));
    }

    @PostMapping("/draft/{id}/adopt")
    public ResponseEntity<Map<String, Object>> adoptDraft(
            @PathVariable Long projectId, @PathVariable("id") Long draftId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        WorkSessionService.AdoptOutcome outcome =
                sessionService.adoptDraft(projectId, draftId, userId, userName(userId));
        return ok(adoptOutcomeData(outcome));
    }

    @PostMapping("/draft/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolveAdopt(
            @PathVariable Long projectId, @PathVariable("id") Long draftId,
            @RequestBody(required = false) Map<String, Map<String, String>> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        Map<String, WorkSessionService.Resolution> resolutions = parseResolutions(body);
        WorkSessionService.AdoptOutcome outcome =
                sessionService.resolveAdopt(projectId, draftId, resolutions, userId, userName(userId));
        return ok(adoptOutcomeData(outcome));
    }

    @PostMapping("/draft/{id}/abort-adopt")
    public ResponseEntity<Map<String, Object>> abortAdopt(
            @PathVariable Long projectId, @PathVariable("id") Long draftId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        sessionService.abortAdopt(projectId);
        return okWithMessage(Map.of("aborted", true), WorkSessionService.ADOPT_ABORTED_NOTICE);
    }

    @PostMapping("/draft/{id}/abandon")
    public ResponseEntity<Map<String, Object>> abandonDraft(
            @PathVariable Long projectId, @PathVariable("id") Long draftId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        WorkSessionService.LineSwitchResult result =
                sessionService.abandonDraft(projectId, draftId, userId, userName(userId));
        return ok(Map.of("affectedFileIds", result.affectedFileIds()));
    }

    /**
     * 请求体里的字符串三选一解析成枚举。非法值（枚举名之外的任何字符串，含大小写不符）
     * 一律 userFacing「无效的选择」——不把 IllegalArgumentException 的枚举名列表带给前端。
     */
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

    /** adopt/resolve 共用的响应形状；notice 非空时才放进 data（见 AdoptOutcome 注释）。 */
    private Map<String, Object> adoptOutcomeData(WorkSessionService.AdoptOutcome outcome) {
        Map<String, Object> data = new HashMap<>();
        data.put("success", outcome.success());
        data.put("sha", outcome.sha() == null ? "" : outcome.sha());
        data.put("conflictingPaths", outcome.conflictingPaths() == null ? List.of() : outcome.conflictingPaths());
        data.put("affectedFileIds", outcome.affectedFileIds() == null ? List.of() : outcome.affectedFileIds());
        if (outcome.notice() != null && !outcome.notice().isBlank()) {
            data.put("notice", outcome.notice());
        }
        return data;
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

    /** 成功响应附一句展示给律师的话（目前只有 abort-adopt 用得到，见 ADOPT_ABORTED_NOTICE）。 */
    private ResponseEntity<Map<String, Object>> okWithMessage(Map<String, Object> data, String message) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data, "message", message));
    }
}
