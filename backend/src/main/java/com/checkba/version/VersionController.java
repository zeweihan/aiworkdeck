package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.LangText;
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
    private final com.checkba.service.telemetry.TelemetryService telemetryService;
    private final VersionLifecycleService lifecycleService;

    /** 埋点：版本记录关键动作计数（op 是端点枚举名，不带任何项目/版本信息） */
    private void trackOp(String op) {
        telemetryService.record("version.op", Map.of("op", op, "ok", true));
    }

    public VersionController(ProjectRepoService repoService,
                             WorkSessionService sessionService,
                             ProjectMemberService projectMemberService,
                             UserService userService,
                             ProjectFileService projectFileService,
                             com.checkba.service.telemetry.TelemetryService telemetryService,
                             VersionLifecycleService lifecycleService) {
        this.repoService = repoService;
        this.sessionService = sessionService;
        this.projectMemberService = projectMemberService;
        this.userService = userService;
        this.projectFileService = projectFileService;
        this.telemetryService = telemetryService;
        this.lifecycleService = lifecycleService;
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
            Map<String, Object> sessionEndConflict = sessionEndConflictStatus(projectId);
            Map<String, Object> cloudConflict = sessionEndConflict != null
                    ? null : cloudConflictStatus(projectId);
            data.put("sessionEndConflict", sessionEndConflict);
            data.put("cloudConflict", cloudConflict);
            // 三者都由 MERGE_HEAD 反查，先到先得：sessionEndConflict → cloudConflict →
            // adoptConflict。命中前两者中任一个时 adoptConflict 必须为 null，防止前端
            // 同时弹出多种裁决弹窗。
            data.put("adoptConflict", (sessionEndConflict != null || cloudConflict != null)
                    ? null : adoptConflictStatus(projectId));
            // 留底占了多少磁盘。版本记录现在默认开着，律师要能随时看见它的代价，
            // 才谈得上「知情之后决定要不要关」。
            data.put("repoSizeBytes", repoService.repoSizeBytes(projectId));
        } else {
            data.put("working", false);
            data.put("changedCount", 0);
            data.put("pendingRecovery", false);
            data.put("onDraft", null);
            data.put("adoptConflict", null);
            data.put("sessionEndConflict", null);
            data.put("cloudConflict", null);
            data.put("repoSizeBytes", 0L);
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
        List<String> conflicts = WorkSessionService.userVisibleConflicts(
                repoService.conflictingPaths(projectId));
        Map<String, Object> m = new HashMap<>();
        m.put("draftId", matched == null ? null : matched.getId());
        m.put("draftName", matched == null ? null : matched.getTitle());
        m.put("conflictingPaths", conflicts);
        m.put("mainlineTip", repoService.resolveRef(projectId, "HEAD"));
        m.put("draftTip", mergeHeadSha);
        return m;
    }

    /**
     * 结束工作撞车（Task 7）的反查：{@code MERGE_HEAD} 指向当前 ACTIVE 工作段自己分支的
     * tip，即「结束工作撞上被推进的主线」这个窗口——口径同 {@link #adoptConflictStatus}
     * （都靠 MERGE_HEAD 反查、都不依赖任何应用层状态字段），区别只在于反查目标是工作段
     * 而不是稿。反查不到（工作段被并发丢弃等异常残局）时返回 null，让调用方回落到
     * {@code adoptConflictStatus}——但正常路径下不会发生：结束工作撞车时工作段仍是
     * ACTIVE，不会被别的路径动。
     *
     * 不 catch 异常：这条 null 路径只该在「确实没查到结束工作撞车现场」时走，不能借它
     * 吞掉真正的查询异常。之前这里裹了一层 blanket catch，真在 MERGING 时反查失败会把
     * sessionEndConflict 悄悄判成 null，/status 转而落到 adoptConflictStatus 的
     * draftId=null 逃生门——前端把「结束工作撞车」误当成「采纳撞车」，弹出错的裁决弹窗。
     * 查询失败必须让异常走 {@link #onVersionError} 显式报错，不能静默降级成错误的语境。
     */
    private Map<String, Object> sessionEndConflictStatus(long projectId) {
        if (!repoService.repositoryMerging(projectId)) return null;
        String mergeHead = repoService.mergeHeadRef(projectId);
        if (mergeHead == null) return null;
        var active = sessionService.activeSession(projectId);
        if (active.isEmpty() || !mergeHead.equals(
                repoService.resolveRef(projectId, active.get().getBranchName()))) {
            return null;
        }
        return sessionEndConflictData(new WorkSessionService.SessionEndConflict(
                active.get().getId(), active.get().getTitle(),
                WorkSessionService.userVisibleConflicts(repoService.conflictingPaths(projectId)),
                repoService.resolveRef(projectId, "HEAD"), mergeHead));
    }

    /**
     * 云端更新冲突态（Task 9）反查：MERGE_HEAD 等于 origin/master 当前 tip、**或是它的
     * 祖先**（v2 终审 I3：窗口开着期间同事又推了一版、随后本地 fetch 过，origin/master
     * 前移不该把窗口孤儿化——窗口语境以开窗时刻的 MERGE_HEAD 为准），即为
     * CloudSyncService.updateFromCloud/uploadToCloud 的自动整合开出的合并冲突窗口。
     * cloudTip 带回的是 MERGE_HEAD（裁决按开窗时刻的云端 tip 落地，不是 fetch 后的新 tip）。
     * 口径同 {@link #sessionEndConflictStatus}/{@link #adoptConflictStatus}——都靠
     * MERGE_HEAD 反查、不依赖任何应用层状态字段，崩溃恢复天然可用。/status 判定链里
     * 排在 sessionEndConflict 之后、adoptConflict 之前（祖先判定放在活动段 tip 精确相等
     * 之后，顺序不能颠倒），命中时后者强制 null。
     */
    private Map<String, Object> cloudConflictStatus(long projectId) {
        if (!repoService.repositoryMerging(projectId)) return null;
        String mergeHead = repoService.mergeHeadRef(projectId);
        String originSha = repoService.originMasterSha(projectId);
        if (mergeHead == null || originSha == null) return null;
        boolean cloudWindow = mergeHead.equals(originSha)
                || repoService.isAncestor(projectId, mergeHead, "refs/remotes/origin/master");
        if (!cloudWindow) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("conflictingPaths", WorkSessionService.userVisibleConflicts(
                repoService.conflictingPaths(projectId)));
        m.put("mainlineTip", repoService.resolveRef(projectId, "HEAD"));
        m.put("cloudTip", mergeHead);
        return m;
    }

    private Map<String, Object> sessionEndConflictData(WorkSessionService.SessionEndConflict c) {
        Map<String, Object> m = new HashMap<>();
        m.put("sessionId", c.sessionId());
        m.put("title", c.title());
        m.put("conflictingPaths", c.conflictingPaths());
        m.put("mainlineTip", c.mainlineTip());
        m.put("sessionTip", c.sessionTip());
        return m;
    }

    @PostMapping("/enable")
    public ResponseEntity<Map<String, Object>> enable(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        // 手动开启不受大文件夹护栏管——护栏只拦"我们替他做主"的那两个自动触发点，
        // 律师自己按下去就是他自己的决定。同时清掉 opt-out，否则下次自动触发点
        // 还会被旧标记拦住。
        lifecycleService.clearOptOut(projectId);
        sessionService.enableVersionRecording(projectId, userName(userId), email(userId));
        trackOp("enable");
        return ok(Map.of("enabled", true));
    }

    /**
     * 关闭版本记录并删除全部历史（dev-board#438）。默认自动开启之后必须有这条拒绝的路。
     *
     * <p>只有项目负责人/管理员能关：这是把整个项目的留底一次性删掉，且不可撤销。
     * 关掉之后写下 opt-out，自动开启不会再把它开回来（律师随时可以手动再开）。
     * 工作区里的文件一个都不动。
     */
    @PostMapping("/disable")
    public ResponseEntity<Map<String, Object>> disable(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireAdminMember(projectId, sessionId);
        lifecycleService.disableVersionRecording(projectId);
        trackOp("disable");
        return ok(Map.of("enabled", false));
    }

    /**
     * 让本项目可作为云端仓库：未初始化则建空仓等首推（共享方带完整历史进来）；
     * 已初始化但清单还是 v1（老项目补开的云端协作）则落一笔升级提交。
     *
     * <p>决策与动作整段都在 {@link WorkSessionService#prepareRemoteRepository} 里、
     * 跑在本项目的仓库锁内——这条路与自动开启（dev-board#438）并发建同一个仓库，
     * 拆在控制器里做「先判断、再动手」必然留出竞态窗口（详见那个方法的注释）。
     */
    @PostMapping("/prepare-remote")
    public ResponseEntity<Map<String, Object>> prepareRemote(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        boolean fresh = sessionService.prepareRemoteRepository(projectId, userId, userName(userId));
        return ok(Map.of("prepared", true, "fresh", fresh));
    }

    @GetMapping("/timeline")
    public ResponseEntity<Map<String, Object>> timeline(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long fileId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        // 未开启版本记录不是错误：新建项目十有八九没开，概览页的动态块第一天就会撞上。
        // 不早退的话这里会掉进 VersionException 的通用信封（「版本记录操作失败，请重试」），
        // 概览页只能把「还没有版本记录」显示成「读取失败」。
        if (!repoService.isInitialized(projectId)) {
            return ok(Map.of("versions", List.of()));
        }
        List<VersionEntry> entries;
        if (fileId != null) {
            ProjectFile f = projectFileService.getFile(fileId); // 文件不存在会抛异常
            if (!projectId.equals(f.getProjectId())) {
                // 拒绝消息不带 fileId：越权探测者不该从错误文案里拿到内部 id 的存在性回执。
                throw new IllegalArgumentException(LangText.of("无权访问该文件", "You don't have access to this file"));
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
        Long userId = requireWriteMember(projectId, sessionId);
        String title = body == null ? null : body.get("title");
        WorkSessionService.SessionEndResult r =
                sessionService.endSession(projectId, userId, userName(userId), title);
        // notice 非空 = 结束成功但没生成版本（空工作段）。仍然是成功（code=0），
        // 前端凭它决定要不要多 toast 一句，不能走异常分支——见 SessionEndResult 注释。
        // conflict 非空 = 主线被同事推进、撞了车，等着三选一（Task 7）——只在这种情况
        // 下才放进响应，sha/notice 两个都留空字符串（三者互斥，见 SessionEndResult 注释）。
        Map<String, Object> data = new HashMap<>();
        data.put("sha", r.sha() == null ? "" : r.sha());
        data.put("notice", r.notice() == null ? "" : r.notice());
        if (r.conflict() != null) data.put("conflict", sessionEndConflictData(r.conflict()));
        trackOp("session_end");
        return ok(data);
    }

    @PostMapping("/session/resolve-end")
    public ResponseEntity<Map<String, Object>> resolveSessionEnd(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        Object rawSessionId = body.get("sessionId");
        if (!(rawSessionId instanceof Number)) {
            throw VersionException.userFacing(LangText.of("无效的请求", "Invalid request"));
        }
        long targetSession = ((Number) rawSessionId).longValue();
        @SuppressWarnings("unchecked")
        Map<String, String> raw = (Map<String, String>) body.get("resolutions");
        WorkSessionService.SessionEndResult r = sessionService.resolveSessionEnd(
                projectId, targetSession, resolutionsFromRaw(raw), userId, userName(userId));
        return ok(Map.of("sha", r.sha() == null ? "" : r.sha()));
    }

    @PostMapping("/session/abort-end")
    public ResponseEntity<Map<String, Object>> abortSessionEnd(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireWriteMember(projectId, sessionId);
        String notice = sessionService.abortSessionEnd(projectId);
        return okWithMessage(Map.of(), notice);
    }

    @PostMapping("/session/discard")
    public ResponseEntity<Map<String, Object>> discardSession(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        // affectedFileIds：丢弃改写了磁盘，打开中的编辑器要走同一条重载链（同 revert）。
        List<Long> affectedFileIds = sessionService.discardSession(projectId, userId);
        trackOp("session_discard");
        return ok(Map.of("discarded", true, "affectedFileIds", affectedFileIds));
    }

    @PostMapping("/session/resume")
    public ResponseEntity<Map<String, Object>> resumeSession(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireWriteMember(projectId, sessionId);
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
            throw VersionException.userFacing(LangText.of("这一版里没有这份文件", "This file isn't in this version"));
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
            throw VersionException.userFacing(LangText.of("这一版里没有这份文件", "This file isn't in this version"));
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
        requireWriteMember(projectId, sessionId);
        String name = body == null ? null : body.get("name");
        if (name == null || name.isBlank()) {
            throw VersionException.userFacing(LangText.of("请给重要版本起个名字", "Please give this milestone a name"));
        }
        if (name.strip().length() > 64) {
            throw VersionException.userFacing(LangText.of("名字太长了，请控制在 64 字以内", "That name is too long — please keep it under 64 characters"));
        }
        repoService.tagMilestone(projectId, sha, name.strip());
        return ok(Map.of("marked", true));
    }

    @PostMapping("/revert")
    public ResponseEntity<Map<String, Object>> revert(
            @PathVariable Long projectId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        WorkSessionService.RevertResult result = sessionService.revertTo(
                projectId, body.get("ref"), userId, userName(userId));
        trackOp("revert");
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
        Long userId = requireWriteMember(projectId, sessionId);
        String ref = body == null ? null : body.get("ref");
        String name = body == null ? null : body.get("name");
        WorkSessionService.DraftCreateResult result =
                sessionService.createDraft(projectId, ref, name, userId, userName(userId));
        trackOp("draft_create");
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

    /**
     * 单独一稿自己的历史（沿这一稿的分支 walk，不是主线 HEAD）。
     * 响应体结构与 {@code /timeline} 完全一致（含 parents，供前端画分叉/双亲关系）。
     * draftId 不存在或已不是 ACTIVE 状态（已采纳/已放弃）：与 {@code /drafts} 同一套
     * 软降级口径——不抛异常、不用 404，直接给空列表，前端不需要为「稿突然消失」
     * 单独写一套错误处理。
     */
    @GetMapping("/drafts/{draftId}/timeline")
    public ResponseEntity<Map<String, Object>> draftTimeline(
            @PathVariable Long projectId,
            @PathVariable Long draftId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        if (!repoService.isInitialized(projectId)) {
            return ok(Map.of("versions", List.of()));
        }
        WorkSession draft = sessionService.listDrafts(projectId).stream()
                .filter(d -> d.getId().equals(draftId))
                .findFirst()
                .orElse(null);
        if (draft == null) {
            return ok(Map.of("versions", List.of()));
        }
        List<VersionEntry> entries = repoService.log(projectId, draft.getBranchName(), limit);
        return ok(Map.of("versions", entries));
    }

    @PostMapping("/draft/{id}/switch")
    public ResponseEntity<Map<String, Object>> switchToDraft(
            @PathVariable Long projectId, @PathVariable("id") Long draftId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        WorkSessionService.LineSwitchResult result =
                sessionService.switchToDraft(projectId, draftId, userId, userName(userId));
        return ok(Map.of("affectedFileIds", result.affectedFileIds()));
    }

    @PostMapping("/switch-mainline")
    public ResponseEntity<Map<String, Object>> switchToMainline(
            @PathVariable Long projectId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        WorkSessionService.LineSwitchResult result =
                sessionService.switchToMainline(projectId, userId, userName(userId));
        return ok(Map.of("affectedFileIds", result.affectedFileIds()));
    }

    @PostMapping("/draft/{id}/adopt")
    public ResponseEntity<Map<String, Object>> adoptDraft(
            @PathVariable Long projectId, @PathVariable("id") Long draftId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        WorkSessionService.AdoptOutcome outcome =
                sessionService.adoptDraft(projectId, draftId, userId, userName(userId));
        trackOp("draft_adopt");
        return ok(adoptOutcomeData(outcome));
    }

    @PostMapping("/draft/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolveAdopt(
            @PathVariable Long projectId, @PathVariable("id") Long draftId,
            @RequestBody(required = false) Map<String, Map<String, String>> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        Map<String, WorkSessionService.Resolution> resolutions = parseResolutions(body);
        WorkSessionService.AdoptOutcome outcome =
                sessionService.resolveAdopt(projectId, draftId, resolutions, userId, userName(userId));
        return ok(adoptOutcomeData(outcome));
    }

    @PostMapping("/draft/{id}/abort-adopt")
    public ResponseEntity<Map<String, Object>> abortAdopt(
            @PathVariable Long projectId, @PathVariable("id") Long draftId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireWriteMember(projectId, sessionId);
        sessionService.abortAdopt(projectId);
        return okWithMessage(Map.of("aborted", true), WorkSessionService.adoptAbortedNotice());
    }

    @PostMapping("/draft/{id}/abandon")
    public ResponseEntity<Map<String, Object>> abandonDraft(
            @PathVariable Long projectId, @PathVariable("id") Long draftId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        WorkSessionService.LineSwitchResult result =
                sessionService.abandonDraft(projectId, draftId, userId, userName(userId));
        return ok(Map.of("affectedFileIds", result.affectedFileIds()));
    }

    /** 采纳裁决（{@code /draft/{id}/resolve}）的请求体形状：整个 body 就是 resolutions 的外壳。 */
    private Map<String, WorkSessionService.Resolution> parseResolutions(Map<String, Map<String, String>> body) {
        return resolutionsFromRaw(body == null ? null : body.get("resolutions"));
    }

    /**
     * 请求体里的字符串三选一解析成枚举。非法值（枚举名之外的任何字符串，含大小写不符）
     * 一律 userFacing「无效的选择」——不把 IllegalArgumentException 的枚举名列表带给前端。
     * 采纳裁决与结束工作裁决（Task 7）共用这一份解析逻辑，只是外层 body 的形状不同
     * （前者整个 body 就是 resolutions，后者 resolutions 是 {sessionId, resolutions} 的一个字段）。
     */
    private Map<String, WorkSessionService.Resolution> resolutionsFromRaw(Map<String, String> raw) {
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
        telemetryService.record("version.op", Map.of("op", "error", "ok", false));
        String message = e.isUserFacing() ? e.getMessage() : LangText.of("版本记录操作失败，请重试", "Version history operation failed — please try again");
        return ResponseEntity.ok(Map.of("code", 1, "message", message));
    }

    /** 校验并返回当前用户 id。非成员或 CLIENT 一律拒绝。 */
    private Long requireMember(Long projectId, String sessionId) {
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
     * 改写仓库状态的端点在 requireMember 之上追加写权限（v2 终审 I4）：READ_ONLY 成员
     * 能看时间线/对比，但结束工作、退回、采纳、里程碑等都会改写仓库与文件树，必须
     * hasWritePermission——与 Git 写路径（GitAccessService push 侧）同口径。
     * 注意参数序 (projectId, userId)，两参数同为 Long，写反了能编译（地雷 #3 同款）。
     */
    private Long requireWriteMember(Long projectId, String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        if (!projectMemberService.hasWritePermission(projectId, userId)) {
            throw new IllegalArgumentException(LangText.of("无权修改该项目", "You don't have permission to modify this project"));
        }
        return userId;
    }

    /**
     * 最严的一档：在写权限之上再要求项目负责人/管理员（{@code checkAdminPermission}）。
     * 目前只有「关闭版本记录并删除历史」用得到——那是不可撤销的整项目级操作。
     * 注意参数序 (projectId, userId)，两参数同为 Long，写反了能编译（地雷 #3 同款）。
     */
    private Long requireAdminMember(Long projectId, String sessionId) {
        Long userId = requireWriteMember(projectId, sessionId);
        try {
            projectMemberService.checkAdminPermission(projectId, userId);
        } catch (RuntimeException e) {
            // checkAdminPermission 的原文没走 LangText，英文界面会看到中文；换成本地化措辞。
            throw new IllegalArgumentException(LangText.of("只有项目负责人可以执行此操作", "Only the project lead can do this"));
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
