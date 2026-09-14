// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.ProjectFile;
import com.checkba.service.LangText;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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

    /**
     * 提交署名解析（spec 2026-09-14 §2.1）。字段注入：本类的构造器被
     * GlobalExceptionHandlerAuthCodeTest / VersionFileAccessTest 手工 new，加参数是纯 churn。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VersionAuthorResolver authorResolver;

    /**
     * 云端状态（「案件库领先几版、都是谁交的」）。同样字段注入，理由同上——
     * 本类的构造器被几个测试手工 new，且历史端点在没有云端协作时照常可用。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CloudSyncService cloudSyncService;

    /**
     * 三方合并（spec 2026-09-14 §4.3/§4.4）。同样字段注入：本类的构造器被几个
     * {@code @InjectMocks} 的单测装配，加构造器参数会让它们拿到 null 再 NPE。
     * {@code required=false} 时三个冲突对象退回 v2 的老形状（documentMerges 为空
     * = 全部整份三选一），端点自己报「这个功能现在用不了」。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.checkba.version.merge.MergeAnalysisService mergeAnalysisService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.checkba.version.merge.PendingMergeStore pendingMergeStore;

    /** 单测/跨包装配用：走字段注入，手工 new 出来的实例得有地方补上。 */
    public void setMergeAnalysisServiceForTest(com.checkba.version.merge.MergeAnalysisService service) {
        this.mergeAnalysisService = service;
    }

    /** 单测/跨包装配用：同上。 */
    public void setPendingMergeStoreForTest(com.checkba.version.merge.PendingMergeStore store) {
        this.pendingMergeStore = store;
    }

    /**
     * 逐段溯源（spec 2026-09-14 §4.7）。同样字段注入且允许缺席，理由同上——
     * 手工 new 本控制器的几个测试用不到它，缺席时 {@code /provenance} 回空 units。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.checkba.version.merge.ProvenanceService provenanceService;

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
        Long viewerId = requireMember(projectId, sessionId);
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
            Map<String, Object> sessionEndConflict = sessionEndConflictStatus(projectId, viewerId);
            Map<String, Object> cloudConflict = sessionEndConflict != null
                    ? null : cloudConflictStatus(projectId, viewerId);
            data.put("sessionEndConflict", sessionEndConflict);
            data.put("cloudConflict", cloudConflict);
            // 三者都由 MERGE_HEAD 反查，先到先得：sessionEndConflict → cloudConflict →
            // adoptConflict。命中前两者中任一个时 adoptConflict 必须为 null，防止前端
            // 同时弹出多种裁决弹窗。
            data.put("adoptConflict", (sessionEndConflict != null || cloudConflict != null)
                    ? null : adoptConflictStatus(projectId, viewerId));
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
    private Map<String, Object> adoptConflictStatus(long projectId, Long viewerId) {
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
        m.putAll(mergeExtras(projectId, viewerId));
        return m;
    }

    /**
     * 三个冲突对象共有的那三个三方合并字段（spec 2026-09-14 §4.3）：
     * {@code mergeBase} / {@code documentMerges} / {@code sides}。三语境共用同一个服务，
     * <b>不许各自拼一份</b>——前端对三个语境用的是同一套渲染代码，三份实现走散之后
     * 律师在哪个语境里看到的清单不对，是没法从代码上先看出来的。
     *
     * <p>服务缺席或算不出来时回空表：冲突弹窗退回 v2 的老形状（全部整份三选一）。
     * 这一步跑在 {@code /status} 上，为一份读不开的文件让整个冲突窗口消失，代价太大。
     */
    private Map<String, Object> mergeExtras(long projectId, Long userId) {
        if (mergeAnalysisService == null) return Map.of();
        try {
            return mergeAnalysisService.conflictExtras(projectId, userId, remoteDisplayNames(projectId));
        } catch (Exception e) {
            log.warn("拼装三方合并字段失败，这次冲突窗口按整份三选一给: project={}", projectId, e);
            return Map.of();
        }
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
    private Map<String, Object> sessionEndConflictStatus(long projectId, Long viewerId) {
        if (!repoService.repositoryMerging(projectId)) return null;
        String mergeHead = repoService.mergeHeadRef(projectId);
        if (mergeHead == null) return null;
        var active = sessionService.activeSession(projectId);
        if (active.isEmpty() || !mergeHead.equals(
                repoService.resolveRef(projectId, active.get().getBranchName()))) {
            return null;
        }
        Map<String, Object> m = sessionEndConflictData(new WorkSessionService.SessionEndConflict(
                active.get().getId(), active.get().getTitle(),
                WorkSessionService.userVisibleConflicts(repoService.conflictingPaths(projectId)),
                repoService.resolveRef(projectId, "HEAD"), mergeHead));
        m.putAll(mergeExtras(projectId, viewerId));
        return m;
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
    private Map<String, Object> cloudConflictStatus(long projectId, Long viewerId) {
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
        m.putAll(mergeExtras(projectId, viewerId));
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
        sessionService.enableVersionRecording(projectId, userName(userId), email(projectId, userId));
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
        return ok(Map.of("versions", withRemoteNames(projectId, entries)));
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

    // ==================== 统一历史与任意两版对比（spec 2026-09-14 §2.4） ====================

    /**
     * 程序员在 IDE 里那份 {@code git log --graph --all}，换成律师的词（dev-board#624）。
     * 一次给回：主线 + 各稿 + 案件库最新稿合成的一条倒序流、两边各领先几版、
     * 每一行是谁在什么时候干了什么、以及「这一版还没取回」的标记。
     *
     * <p>未开版本记录不是错误：回 {@code enabled:false} + 空列表、HTTP 仍是 200，
     * 前端据此出「开启版本记录」的引导。这与 {@code /timeline} 的早退口径一致——
     * 掉进 VersionException 的通用信封会让引导页显示成「读取失败」。
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) Long fileId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean includeAuto,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        Map<String, Object> data = new HashMap<>();
        if (!repoService.isInitialized(projectId)) {
            data.put("enabled", false);
            data.put("entries", List.of());
            return ok(data);
        }

        int capped = Math.min(Math.max(limit, 1), HISTORY_MAX_LIMIT);
        String relPath = fileId == null ? null : relPathOfFile(projectId, fileId);
        ProjectRepoService.HistoryQuery query = new ProjectRepoService.HistoryQuery(
                capped, cursor, author, relPath, q,
                parseFrom(from), parseTo(to), includeAuto);
        ProjectRepoService.HistoryPage page =
                repoService.history(projectId, historyRoots(projectId), query);

        Map<String, String> remoteNames = remoteDisplayNames(projectId);
        List<Map<String, Object>> entries = new ArrayList<>(page.rows().size());
        for (ProjectRepoService.HistoryRow row : page.rows()) {
            entries.add(entryData(projectId, userId, row, remoteNames));
        }

        data.put("enabled", true);
        data.put("head", headData(projectId));
        data.put("entries", entries);
        data.put("nextCursor", page.nextCursor());
        putSyncCounters(projectId, userId, data);
        return ok(data);
    }

    /** limit 的服务端上限：再大也只是把一次请求拖长，前端滚到底会自己翻页。 */
    private static final int HISTORY_MAX_LIMIT = 500;

    /**
     * 参与 walk 的四类引用。解析不出来的（没绑案件库时的 origin/master、一条稿都没有时）
     * 由 {@link ProjectRepoService#history} 自己跳过，这里不必先判一遍。
     */
    private List<ProjectRepoService.HistoryRoot> historyRoots(long projectId) {
        List<ProjectRepoService.HistoryRoot> roots = new ArrayList<>();
        roots.add(new ProjectRepoService.HistoryRoot(
                repoService.mainBranch(), "mainline", LangText.of("主线", "Mainline")));
        for (WorkSession d : sessionService.listDrafts(projectId)) {
            if (d.getBranchName() == null) continue;
            roots.add(new ProjectRepoService.HistoryRoot(d.getBranchName(), "draft", d.getTitle()));
        }
        roots.add(new ProjectRepoService.HistoryRoot(
                repoService.originMasterRef(), "remote", LangText.of("案件库", "Case Library")));
        roots.add(new ProjectRepoService.HistoryRoot(
                "HEAD", "local", LangText.of("本机", "This computer")));
        return roots;
    }

    /** 当前站在主线还是某一稿上。 */
    private Map<String, Object> headData(long projectId) {
        Map<String, Object> head = new HashMap<>();
        WorkSession draft = sessionService.activeDraftOnBranch(projectId).orElse(null);
        head.put("branch", draft == null ? "mainline" : "draft");
        head.put("draftName", draft == null ? null : draft.getTitle());
        return head;
    }

    /**
     * 「本机领先 N 版 · 案件库领先 M 版」。没绑案件库时两者都是 0，
     * 且不放 remoteAhead* 那三个键——前端据「有没有这几个键」决定要不要说那句话。
     * cloudStatus 是不联网的本地快照（见 CloudSyncService），放在这里不会让历史变慢。
     */
    private void putSyncCounters(long projectId, Long userId, Map<String, Object> data) {
        Map<String, Object> cloud = Map.of("linked", false);
        try {
            if (cloudSyncService != null) cloud = cloudSyncService.cloudStatus(projectId, userId);
        } catch (Exception e) {
            log.warn("读取云端状态失败，历史照常给: project={}", projectId, e);
        }
        if (!Boolean.TRUE.equals(cloud.get("linked"))) {
            data.put("ahead", 0);
            data.put("behind", 0);
            return;
        }
        int ahead = 0;
        try {
            ahead = repoService.commitsBetween(projectId, repoService.originMasterRef(),
                    repoService.mainBranch(), AHEAD_WALK_CAP).size();
        } catch (Exception e) {
            log.warn("统计本机领先版数失败: project={}", projectId, e);
        }
        data.put("ahead", ahead);
        Object behind = cloud.get("remoteAheadCount");
        data.put("behind", behind instanceof Number n ? n.intValue() : 0);
        if (cloud.containsKey("remoteAheadCount")) data.put("remoteAheadCount", cloud.get("remoteAheadCount"));
        if (cloud.containsKey("remoteAheadAuthors")) data.put("remoteAheadAuthors", cloud.get("remoteAheadAuthors"));
        if (cloud.containsKey("remoteAheadAuthorCount")) data.put("remoteAheadAuthorCount", cloud.get("remoteAheadAuthorCount"));
        if (cloud.containsKey("remoteAheadBySelf")) data.put("remoteAheadBySelf", cloud.get("remoteAheadBySelf"));
    }

    /** 与 cloudStatus 数「案件库领先几版」同一个上限，两个数字的量纲才对得上。 */
    private static final int AHEAD_WALK_CAP = 200;

    /** 一行历史的完整形状。字段与 spec §2.4 的表逐条对应，前端不再二次推导。 */
    private Map<String, Object> entryData(long projectId, Long userId,
                                          ProjectRepoService.HistoryRow row,
                                          Map<String, String> remoteNames) {
        VersionEntry e = row.entry();
        Map<String, Object> m = new HashMap<>();
        m.put("sha", e.sha());
        m.put("shortId", e.sha() == null || e.sha().length() < 7 ? e.sha() : e.sha().substring(0, 7));
        // title 是律师看的那一句：工作段有自己的名字（X-AWD-Note）就用它，否则用提交标题
        m.put("title", e.note() != null && !e.note().isBlank() ? e.note() : e.message());
        m.put("message", e.message());
        // 署名翻译成案件库账户的展示名（命中才换）——git 署名是对方那台机器的本机展示名，
        // 单机模式下人人都叫「本机用户」。self 仍然按邮箱判，不受这一步影响。
        m.put("authorName", VersionAuthorResolver.preferredAuthorName(e, remoteNames));
        m.put("authorEmail", e.authorEmail());
        m.put("self", isSelf(e, projectId, userId));
        m.put("when", e.when());
        m.put("kind", e.kind());
        m.put("type", HistoryTypeClassifier.classify(e.message(), e.kind()));
        m.put("parents", e.parents() == null ? List.of() : e.parents());
        m.put("refs", row.refs().stream()
                .map(r -> {
                    Map<String, Object> one = new HashMap<>();
                    one.put("type", r.type());
                    one.put("name", r.name());
                    return one;
                }).toList());
        m.put("milestone", e.milestone());
        m.put("resolutions", e.resolutions() == null ? List.of() : e.resolutions());
        // 三方合并（spec 2026-09-14 §4.6/§5.6）：mergeContext 是把 resolutions/merges 里裸的
        // MAIN/DRAFT、M/T 翻成「你 / 律师乙」的唯一依据（三语境里指向的物理侧不同）；
        // merges 逐文件给出这一次是自动合并的还是逐处裁决的。老提交分别是 null 与空表。
        m.put("mergeContext", e.mergeContext());
        m.put("merges", e.merges() == null ? List.of() : e.merges());
        m.put("remote", row.remote());
        m.put("autoCount", row.autoCount());
        ProjectRepoService.ChangeCounts c = row.changes();
        m.put("changes", Map.of(
                "added", c.added(), "modified", c.modified(),
                "deleted", c.deleted(), "renamed", c.renamed()));
        return m;
    }

    /**
     * 案件库那边的「账号名 → 展示名」。读列表一律不许联网（allowFetch=false）：
     * 缓存里有就用，没有就保持 git 署名——为一个名字让时间线卡在一次网络请求上不值当。
     * 缓存由云端状态轮询与参与人面板顺手喂（见 CloudSyncService.remoteDisplayNames）。
     */
    private Map<String, String> remoteDisplayNames(long projectId) {
        try {
            if (cloudSyncService == null) return Map.of();
            Map<String, String> names = cloudSyncService.remoteDisplayNames(projectId, false);
            return names == null ? Map.of() : names;
        } catch (Exception e) {
            log.warn("读取案件库展示名失败，历史按 git 署名显示: project={}", projectId, e);
            return Map.of();
        }
    }

    /** 出参侧统一把署名换成案件库账户的展示名；命中才换，未命中原样。 */
    private List<VersionEntry> withRemoteNames(long projectId, List<VersionEntry> entries) {
        Map<String, String> names = remoteDisplayNames(projectId);
        if (names.isEmpty()) return entries;
        // preferredAuthorName 未命中时原样回 authorName，所以这里不必再分支
        return entries.stream()
                .map(e -> e.withAuthorName(VersionAuthorResolver.preferredAuthorName(e, names)))
                .toList();
    }

    /** 「这一版是不是我提交的」的唯一判法（见 VersionAuthorResolver）；resolver 缺席时一律否。 */
    private boolean isSelf(VersionEntry e, long projectId, Long userId) {
        try {
            return authorResolver != null && authorResolver.isSelf(e, projectId, userId);
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 逐段溯源（dev-board#632，spec 2026-09-14 §4.7）：这份文件的每一段 / 每一格 / 每一页，
     * 最后是哪一版改的。
     *
     * <p>未开版本记录、这份文件还没进过版本、或服务端还没装上溯源，一律回**空 units + 200**，
     * 不走异常信封：编辑器顶栏那一条溯源是锦上添花，它不该有能力把正文变成一个错误提示。
     * 后端还在算（第一次对着老文件回溯几百版）时回 {@code computing:true}，前端过几秒再问一次。
     */
    @GetMapping("/provenance")
    public ResponseEntity<Map<String, Object>> provenance(
            @PathVariable Long projectId,
            @RequestParam Long fileId,
            @RequestParam(required = false) String ref,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireMember(projectId, sessionId);
        String wanted = ref == null || ref.isBlank() ? "HEAD" : ref.trim();
        // 归属校验先做：这一步不因「还没开版本记录」而跳过，越权探测在两条路径上同一个回答
        String relPath = relPathOfFile(projectId, fileId);
        if (!repoService.isInitialized(projectId) || provenanceService == null) {
            return ok(Map.of("ref", wanted,
                    "kind", com.checkba.version.merge.ThreeWayAnalyzer.kindOf(relPath)
                            .name().toLowerCase(java.util.Locale.ROOT),
                    "units", List.of(), "truncated", false, "computing", false));
        }
        return ok(provenanceService.provenance(projectId, userId, relPath, wanted));
    }

    /**
     * 任意两版之间的文件清单（「对比这两版」）。from/to 可以是任何引用或 sha。
     * 解析不出来就明说哪一头找不到——这是律师自己选的两行，不是内部错误。
     */
    @GetMapping("/compare")
    public ResponseEntity<Map<String, Object>> compare(
            @PathVariable Long projectId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        requireResolvable(projectId, from);
        requireResolvable(projectId, to);
        List<FileChange> changes = repoService.diffNameStatus(projectId, from, to)
                .stream().filter(c -> !c.path().startsWith(".awd/")).toList();
        return ok(Map.of("changes", changes));
    }

    /**
     * 两个入参必须真的指向本仓库里的某一版。用 {@code commitExists} 而不是
     * {@code resolveRef}：后者对「格式合法但库里没有」的完整 sha 会原样回一个 ObjectId，
     * 要等拿去 diff 才炸成技术档异常，律师看到的是通用的「操作失败，请重试」。
     */
    private void requireResolvable(long projectId, String ref) {
        if (!repoService.commitExists(projectId, ref)) {
            throw VersionException.userFacing(LangText.of("找不到要对比的版本", "Cannot find the version to compare"));
        }
    }

    /** fileId → 仓库内相对路径。归属校验与 /timeline 逐字一致（错误文案不带 fileId）。 */
    private String relPathOfFile(Long projectId, Long fileId) {
        ProjectFile f = projectFileService.getFile(fileId); // 文件不存在会抛异常
        if (!projectId.equals(f.getProjectId())) {
            throw new IllegalArgumentException(LangText.of("无权访问该文件", "You don't have access to this file"));
        }
        return WorkSessionService.repoRelativePath(f);
    }

    /**
     * 日期筛选的两端。只写日期（{@code 2026-09-14}）时按**本机时区**理解成那一天的
     * 起点 / 终点——律师选的是「9 月 14 日」，按 UTC 切会把当天早上八小时切到前一天去。
     * 也接受完整时刻（ISO-8601）。解析不出来当作没填，不因为一个筛选参数让整页失败。
     */
    private static java.time.Instant parseFrom(String raw) {
        return parseBoundary(raw, true);
    }

    private static java.time.Instant parseTo(String raw) {
        return parseBoundary(raw, false);
    }

    private static java.time.Instant parseBoundary(String raw, boolean start) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(s);
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            return start
                    ? d.atStartOfDay(zone).toInstant()
                    : d.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1);
        } catch (Exception ignored) {
            // 不是纯日期，往下试完整时刻
        }
        try {
            return java.time.OffsetDateTime.parse(s).toInstant();
        } catch (Exception ignored) {
            // 继续
        }
        try {
            return java.time.Instant.parse(s);
        } catch (Exception e) {
            log.warn("无法解析历史筛选的日期，按未填处理: {}", s);
            return null;
        }
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

    // ==================== 三方合并（spec 2026-09-14 §4.3–§4.5） ====================

    /**
     * 一份冲突文件的完整结构化比对结果（含 overlaps 的三栏文字、引擎重放计划、基线单元）。
     * 裁决界面按需拉，不塞进 {@code /status}——{@code plan}/{@code baseUnits} 对一份
     * 几百段的合同是几十 KB，每 120 秒推一遍纯属浪费。
     *
     * <p>响应的 {@code data} 直接就是 {@code Analysis}（不再裹一层），前端
     * {@code services/mergeDraft.js} 按这个形状读。
     */
    @GetMapping("/merge/analysis")
    public ResponseEntity<Map<String, Object>> mergeAnalysis(
            @PathVariable Long projectId,
            @RequestParam("path") String path,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireMember(projectId, sessionId);
        requireMergeServices();
        com.checkba.version.merge.Analysis analysis =
                mergeAnalysisService.analysisFor(projectId, WorkSessionService.safeRepoPath(path));
        if (analysis == null) {
            throw VersionException.userFacing(LangText.of(
                    "这份文件不在等你做选择的清单里", "This file isn't among the ones waiting on your choice"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", analysis));
    }

    /**
     * 一份文件合并好了：字节写回工作区 + 记一条待决记录（spec 2026-09-14 §4.4）。
     * 三个语境打的是同一个端点——语境由 {@code MERGE_HEAD} 反查，{@code ctx} 只用来
     * 校验客户端手里那份冲突对象还是不是当前这一个（拿着过期对象落盘 = 往另一个合并
     * 窗口里写字节）。
     *
     * <p>收尾不在这里：这一步只是「这一份我处理完了」，律师还能继续处理别的文件、
     * 也还能整个中止。真正落成版本的是三个既有 resolve 端点（值填 {@code MERGED}）。
     */
    @PostMapping("/merge/resolve-file")
    public ResponseEntity<Map<String, Object>> mergeResolveFile(
            @PathVariable Long projectId,
            @RequestParam("path") String path,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "decisions", required = false) String decisions,
            @RequestParam(value = "ctx", required = false) String ctx,
            @RequestPart(value = "file", required = false) org.springframework.web.multipart.MultipartFile file,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireWriteMember(projectId, sessionId);
        String rel = requireConflictPath(projectId, path, ctx);
        byte[] bytes = readUpload(file);
        if (bytes == null) {
            throw VersionException.userFacing(LangText.of(
                    "没有收到合并后的文件", "The merged file didn't come through"));
        }
        return ok(landMerged(projectId, rel, mode, parseDecisions(decisions), bytes));
    }

    /**
     * 表格 / 演示文稿的合并文件由后端按决定清单拼（spec 2026-09-14 §4.5）——这两类不需要
     * 引擎，所以字节不必在客户端和服务端之间走一个来回。{@code decisions} 为空即自动模式
     * （把另一侧独有的改动全部合入）。
     */
    @PostMapping("/merge/resolve-structured")
    public ResponseEntity<Map<String, Object>> mergeResolveStructured(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireWriteMember(projectId, sessionId);
        Object rawPath = body == null ? null : body.get("path");
        String rel = requireConflictPath(projectId,
                rawPath == null ? null : String.valueOf(rawPath), null);
        List<com.checkba.version.merge.Decision> decisions = decisionsFromList(
                body == null ? null : body.get("decisions"));

        com.checkba.version.merge.Analysis analysis = mergeAnalysisService.analysisFor(projectId, rel);
        if (analysis == null || (analysis.kind() != com.checkba.version.merge.MergeKind.XLSX
                && analysis.kind() != com.checkba.version.merge.MergeKind.PPTX)) {
            throw VersionException.userFacing(LangText.of(
                    "这份文件不能这样合并", "This file can't be merged this way"));
        }
        String head = repoService.resolveRef(projectId, "HEAD");
        String mergeHead = repoService.mergeHeadRef(projectId);
        byte[] mainBytes = repoService.readBlobAtCommit(projectId, head, rel);
        byte[] otherBytes = repoService.readBlobAtCommit(projectId, mergeHead, rel);
        byte[] merged = analysis.kind() == com.checkba.version.merge.MergeKind.XLSX
                ? com.checkba.version.merge.XlsxMerger.merge(mainBytes, otherBytes, analysis, decisions)
                : com.checkba.version.merge.PptxMerger.merge(mainBytes, otherBytes, analysis, decisions);
        String mode = decisions.isEmpty() ? "auto" : "manual";
        return ok(landMerged(projectId, rel, mode, decisions, merged));
    }

    /**
     * 落盘 + 记录 + 回执，两个 resolve-* 端点共用。计数只在自动模式下有意义
     * （逐处裁决的账在 {@code decisions} 里），取自分析结果而不是信客户端报上来的数。
     */
    private Map<String, Object> landMerged(long projectId, String rel, String mode,
                                           List<com.checkba.version.merge.Decision> decisions,
                                           byte[] bytes) {
        String normalized = "auto".equals(mode) ? "auto" : "manual";
        com.checkba.version.merge.Analysis analysis = mergeAnalysisService.analysisFor(projectId, rel);
        int mainCount = "auto".equals(normalized) && analysis != null ? analysis.mainChanges() : 0;
        int otherCount = "auto".equals(normalized) && analysis != null ? analysis.otherChanges() : 0;

        java.nio.file.Path target = repoService.workTree(projectId).resolve(rel);
        try {
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.write(target, bytes);
        } catch (Exception e) {
            throw new VersionException("写入合并结果失败: " + target, e);
        }
        pendingMergeStore.put(projectId, new com.checkba.version.merge.MergeRecord(
                rel, normalized, "auto".equals(normalized) ? List.of() : decisions,
                mainCount, otherCount));

        Map<String, Object> data = new HashMap<>();
        data.put("path", rel);
        data.put("state", "MERGED");
        data.put("mainCount", mainCount);
        data.put("otherCount", otherCount);
        return data;
    }

    /**
     * 路径与语境的双重校验。<b>两条都不能省</b>：
     * 路径不在本次冲突清单里 = 这个端点成了「往项目里任意写文件」的口子，而随后的
     * {@code git add .} 会把它收进律师的历史；语境对不上 = 客户端手里那份冲突对象
     * 是上一个合并窗口的，往当前窗口里写的是另一件事的字节。
     */
    private String requireConflictPath(long projectId, String path, String ctx) {
        if (!repoService.repositoryMerging(projectId)) {
            throw VersionException.userFacing(LangText.of(
                    "现在没有等你做选择的文件", "There are no files waiting on your choice right now"));
        }
        requireMergeServices();
        if (ctx != null && !ctx.isBlank() && !ctx.trim().equals(currentMergeContext(projectId))) {
            throw VersionException.userFacing(LangText.of(
                    "正在处理的是另一件事，请先把它处理完", "Something else is already in progress — please finish that first"));
        }
        String rel = WorkSessionService.safeRepoPath(path);
        List<String> conflicts = WorkSessionService.userVisibleConflicts(
                repoService.conflictingPaths(projectId));
        if (!conflicts.contains(rel)) {
            throw VersionException.userFacing(LangText.of(
                    "这份文件不在等你做选择的清单里", "This file isn't among the ones waiting on your choice"));
        }
        return rel;
    }

    /**
     * 当前这个合并窗口属于哪个语境。判定链与 {@code /status} 逐字同序
     * （sessionEnd → cloud → adopt，见 version-control.md「三语境冲突判定链」），
     * 只是不拼 payload——这里只要一个标签。
     */
    private String currentMergeContext(long projectId) {
        String mergeHead = repoService.mergeHeadRef(projectId);
        if (mergeHead == null) return null;
        var active = sessionService.activeSession(projectId);
        if (active.isPresent() && mergeHead.equals(
                repoService.resolveRef(projectId, active.get().getBranchName()))) {
            return ProjectRepoService.MERGE_CONTEXT_SESSION_END;
        }
        String originSha = repoService.originMasterSha(projectId);
        if (originSha != null && (mergeHead.equals(originSha)
                || repoService.isAncestor(projectId, mergeHead, repoService.originMasterRef()))) {
            return ProjectRepoService.MERGE_CONTEXT_CLOUD;
        }
        return ProjectRepoService.MERGE_CONTEXT_ADOPT;
    }

    private void requireMergeServices() {
        if (mergeAnalysisService == null || pendingMergeStore == null) {
            throw VersionException.userFacing(LangText.of(
                    "逐处合并现在用不了，请整份选择", "Merging piece by piece isn't available right now — please choose whole files"));
        }
    }

    private byte[] readUpload(org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new VersionException("读取上传的合并结果失败", e);
        }
    }

    /** {@code decisions} 是一段 JSON 数组文本（multipart 字段装不下结构化对象）。 */
    private List<com.checkba.version.merge.Decision> parseDecisions(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MERGE_JSON.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<com.checkba.version.merge.Decision>>() {});
        } catch (Exception e) {
            throw VersionException.userFacing(LangText.of("无效的请求", "Invalid request"));
        }
    }

    /** JSON 请求体里那一段已经解成 List&lt;Map&gt; 了，换一条路转成 Decision。 */
    private List<com.checkba.version.merge.Decision> decisionsFromList(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        try {
            return MERGE_JSON.convertValue(list,
                    new com.fasterxml.jackson.core.type.TypeReference<List<com.checkba.version.merge.Decision>>() {});
        } catch (Exception e) {
            throw VersionException.userFacing(LangText.of("无效的请求", "Invalid request"));
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MERGE_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

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
        return ok(Map.of("versions", withRemoteNames(projectId, entries)));
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

    /**
     * 版本记录里的署名（spec 2026-09-10 §4）：**展示名优先，空了才回落用户名**。
     *
     * <p>以前直接写 username，手机号注册的同事在时间线与文档修订里就是一串
     * {@code awd_upoxwcdtg}——用户名现在是内部标识，任何界面都不再当名字显示。
     * 已经写进 Git 提交对象的历史 authorName 不回填。
     */
    private String userName(Long userId) {
        try {
            String name = UserService.signatureName(userService.getUserById(userId));
            if (name != null) return name;
        } catch (Exception e) {
            log.warn("取署名失败: userId={}", userId, e);
        }
        return LangText.of("用户", "User");
    }

    /** 作者邮箱的唯一取法，规则集中在 {@link VersionAuthorResolver}（spec 2026-09-14 §2.1）。 */
    private String email(Long projectId, Long userId) {
        String name = userName(userId);
        return authorResolver != null
                ? authorResolver.email(projectId, userId, name)
                : VersionAuthorResolver.localEmail(name);
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }

    /** 成功响应附一句展示给律师的话（目前只有 abort-adopt 用得到，见 ADOPT_ABORTED_NOTICE）。 */
    private ResponseEntity<Map<String, Object>> okWithMessage(Map<String, Object> data, String message) {
        return ResponseEntity.ok(Map.of("code", 0, "data", data, "message", message));
    }
}
