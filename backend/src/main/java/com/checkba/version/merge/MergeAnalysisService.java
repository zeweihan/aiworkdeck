// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import com.checkba.version.ProjectRepoService;
import com.checkba.version.VersionAuthorResolver;
import com.checkba.version.VersionEntry;
import com.checkba.version.WorkSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 冲突窗口里每一份文件「能不能自动合、要不要打扰律师」的判定（spec 2026-09-14 §4.3）。
 *
 * <p>只在仓库停在合并中（{@code MERGING}）时工作。三语境共用一套底层
 * （{@code MERGE_HEAD} 反查，见 version-control.md「三语境冲突判定链」），所以本服务
 * 也只认两个物理侧，不认「我 / 同事」：<b>main = 当前 HEAD 那一侧，other = MERGE_HEAD 那一侧</b>。
 * 哪一侧是谁由语境决定，翻译是前端的事。
 *
 * <p>缓存键是 {@code (projectId, HEAD sha, MERGE_HEAD sha)}：合并窗口里这两个 sha
 * 都不动，而 {@code /status} 每 120 秒轮一次、裁决面板还会再刷——每轮都把几 MB 的 docx
 * 重解一遍是白烧 CPU。仓库离开 MERGING（裁决提交或中止）之后这两个 sha 必然变或消失，
 * 条目当场作废，不会把上一次窗口的判定端给下一次。
 */
@Service
public class MergeAnalysisService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MergeAnalysisService.class);

    /**
     * 单个路径的分析上限。超时的那一份降级成「整份选择」，不拖垮 {@code /status}——
     * 三语境的冲突弹窗、协作状态条、顶栏 chip 全挂在这一条轮询上，它卡住等于整个界面装死。
     */
    static final long PER_PATH_TIMEOUT_MILLIS = 5_000L;

    /**
     * 超时只能"不等了"，不能把已经跑起来的 POI 解析真正掐停（{@code Future.cancel}
     * 对纯 CPU 的解析不起作用）。所以用一个守护线程池：跑飞的任务自己结束、
     * 不拦着 JVM 退出，也不占用调用方（HTTP 工作线程）。
     */
    private static final ExecutorService ANALYZER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "merge-analyzer");
        t.setDaemon(true);
        return t;
    });

    private final ProjectRepoService repoService;
    private final PendingMergeStore pendingStore;

    /**
     * 「这一侧是不是我提交的」。字段注入、{@code required=false}：本服务在单测里手工
     * {@code new}，那些用例不关心署名归属（同 {@code WorkSessionService.authorResolver} 的先例）。
     */
    @Autowired(required = false)
    private VersionAuthorResolver authorResolver;

    public MergeAnalysisService(ProjectRepoService repoService, PendingMergeStore pendingStore) {
        this.repoService = repoService;
        this.pendingStore = pendingStore;
    }

    /** 单测用：走字段注入，手工 new 出来的实例得有地方补上。 */
    void setAuthorResolverForTest(VersionAuthorResolver resolver) {
        this.authorResolver = resolver;
    }

    private record CacheEntry(String head, String mergeHead, Map<String, Analysis> analyses) {
    }

    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 这次合并窗口里每一份待裁决文件的结构化比对结果。不在合并中时回空表
     * （并顺手把缓存清掉——窗口已经关了）。
     */
    public Map<String, Analysis> analyzeConflicts(long projectId) {
        if (!repoService.repositoryMerging(projectId)) {
            cache.remove(projectId);
            return Map.of();
        }
        String head = repoService.resolveRef(projectId, "HEAD");
        String mergeHead = repoService.mergeHeadRef(projectId);
        if (head == null || mergeHead == null) return Map.of();

        CacheEntry hit = cache.get(projectId);
        if (hit != null && head.equals(hit.head()) && mergeHead.equals(hit.mergeHead())) {
            return hit.analyses();
        }

        String base = repoService.mergeBase(projectId, head, mergeHead);
        List<String> paths = WorkSessionService.userVisibleConflicts(
                repoService.conflictingPaths(projectId));
        Map<String, Analysis> out = new LinkedHashMap<>();
        for (String path : paths) {
            out.put(path, analyzeOne(projectId, path, base, head, mergeHead));
        }
        // LinkedHashMap 而不是 Map.copyOf：路径顺序要跟 conflictingPaths（已排序）一致，
        // 裁决总览里那几行才不会每刷一次就换个次序。
        Map<String, Analysis> frozen = java.util.Collections.unmodifiableMap(out);
        cache.put(projectId, new CacheEntry(head, mergeHead, frozen));
        return frozen;
    }

    /** 单份文件的完整分析（{@code GET /version/merge/analysis} 用）；不在冲突清单里回 null。 */
    public Analysis analysisFor(long projectId, String relPath) {
        return analyzeConflicts(projectId).get(relPath);
    }

    /**
     * {@code /status} 的冲突对象里那一份精简清单。{@code state} 来自待决记录：
     * 已经合好写回工作区的路径是 {@code MERGED}，其余是 {@code PENDING}——
     * 崩溃恢复后前端据此不重跑已经合过的文件（工作区里躺着的已是合并结果，重跑会算错）。
     */
    public List<Map<String, Object>> documentMerges(long projectId) {
        Map<String, Analysis> analyses = analyzeConflicts(projectId);
        List<Map<String, Object>> rows = new ArrayList<>(analyses.size());
        for (Map.Entry<String, Analysis> e : analyses.entrySet()) {
            Analysis a = e.getValue();
            Map<String, Object> row = new HashMap<>();
            row.put("path", e.getKey());
            row.put("kind", a.kind().name());
            row.put("decision", a.decision().name());
            row.put("reason", a.reason().name());
            row.put("mainChanges", a.mainChanges());
            row.put("otherChanges", a.otherChanges());
            row.put("overlapCount", a.overlaps() == null ? 0 : a.overlaps().size());
            var pending = pendingStore.get(projectId, e.getKey());
            row.put("state", pending.isPresent() ? "MERGED" : "PENDING");
            // 「这一侧合了几处」只有自动合并这一档才算得出（逐处裁决那一笔账在 decisions
            // 里，记录上的两个计数是 0）。把 0 当成答案端出去，界面就写成「已合并：
            // 主线改的 0 处、稿《X》改的 0 处」，而律师刚亲手裁过好几处（真机 A3 那一批）。
            // 缺席时前端退回 mainChanges/otherChanges，那才是两边各改了几个单元的真实数。
            pending.filter(r -> "auto".equals(r.mode())).ifPresent(r -> {
                row.put("mainCount", r.mainCount());
                row.put("otherCount", r.otherCount());
            });
            rows.add(row);
        }
        return rows;
    }

    /**
     * 三个冲突对象共用的那三个新字段（spec 2026-09-14 §4.3）：
     * {@code mergeBase}（共同的上一版）、{@code documentMerges}、{@code sides}。
     *
     * <p>三语境各自的 {@code *ConflictStatus} 都调这一个方法，<b>不许各自复制一份</b>——
     * 三份独立实现一定会在某一次改动里走散，而前端对三个语境用的是同一套渲染代码。
     *
     * @param remoteNames 案件库账号名 → 展示名，调用方自己取（本服务不联网、也不该
     *                    反向依赖 {@code CloudSyncService}——那边要调本服务拼云端冲突对象）
     */
    public Map<String, Object> conflictExtras(long projectId, Long userId, Map<String, String> remoteNames) {
        String head = repoService.resolveRef(projectId, "HEAD");
        String mergeHead = repoService.mergeHeadRef(projectId);
        Map<String, Object> m = new HashMap<>();
        m.put("mergeBase", head == null || mergeHead == null
                ? null : repoService.mergeBase(projectId, head, mergeHead));
        m.put("documentMerges", documentMerges(projectId));
        Map<String, Object> sides = new HashMap<>();
        sides.put("main", sideOf(projectId, userId, head, remoteNames));
        sides.put("other", sideOf(projectId, userId, mergeHead, remoteNames));
        m.put("sides", sides);
        return m;
    }

    /**
     * 一侧的「谁、什么时候、哪一版」。{@code title} 与提交历史那边同一口径：
     * 工作段有自己的名字（{@code X-AWD-Note}）就用它，否则用提交标题。
     * 署名走 {@link VersionAuthorResolver#preferredAuthorName}（命中案件库展示名才换），
     * <b>任何情况下都不回账号名</b>——界面不显示 username 是全局纪律。
     */
    private Map<String, Object> sideOf(long projectId, Long userId, String sha, Map<String, String> remoteNames) {
        if (sha == null) return null;
        VersionEntry e;
        try {
            List<VersionEntry> one = repoService.log(projectId, sha, 1);
            if (one.isEmpty()) return null;
            e = one.get(0);
        } catch (Exception ex) {
            log.warn("读取合并一侧的版本信息失败: project={} sha={}", projectId, sha, ex);
            return null;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("sha", e.sha());
        m.put("authorName", VersionAuthorResolver.preferredAuthorName(
                e, remoteNames == null ? Map.of() : remoteNames));
        m.put("self", isSelf(e, projectId, userId));
        m.put("when", e.when());
        m.put("title", e.note() != null && !e.note().isBlank() ? e.note() : e.message());
        return m;
    }

    private boolean isSelf(VersionEntry e, long projectId, Long userId) {
        try {
            return authorResolver != null && authorResolver.isSelf(e, projectId, userId);
        } catch (Exception ex) {
            log.warn("判断这一版是不是本人提交失败，按否处理: project={}", projectId, ex);
            return false;
        }
    }

    /**
     * 一个路径的三份字节 → {@link Analysis}。任何读取/解析失败都降级成「整份选择」而不是
     * 抛出去：这条链挂在 {@code /status} 上，一份读不开的文件不能让整个冲突窗口消失。
     *
     * <p>体积上限不在这里判——{@link ThreeWayAnalyzer} 自己有一道 20 MB 的闸
     * （比 {@code version.max-tracked-file-size-bytes} 的 50MB 更紧），两处判等于两个答案。
     */
    private Analysis analyzeOne(long projectId, String path, String base, String head, String mergeHead) {
        MergeKind kind = ThreeWayAnalyzer.kindOf(path);
        if (kind == MergeKind.WHOLE) {
            // pdf / 图片 / 其它二进制：没有可比对的单元，连字节都不必读出来。
            return whole(MergeKind.WHOLE, MergeReason.BINARY);
        }
        Future<Analysis> task = ANALYZER.submit(() -> {
            byte[] baseBytes = base == null ? null : repoService.readBlobAtCommit(projectId, base, path);
            byte[] mainBytes = repoService.readBlobAtCommit(projectId, head, path);
            byte[] otherBytes = repoService.readBlobAtCommit(projectId, mergeHead, path);
            return ThreeWayAnalyzer.analyze(path, baseBytes, mainBytes, otherBytes);
        });
        try {
            return task.get(PER_PATH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            task.cancel(true);
            log.warn("结构化比对超时，这一份退回整份选择: project={} path={}", projectId, path);
            return whole(kind, MergeReason.UNSUPPORTED);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return whole(kind, MergeReason.UNSUPPORTED);
        } catch (Exception e) {
            log.warn("结构化比对失败，这一份退回整份选择: project={} path={}", projectId, path, e);
            return whole(kind, MergeReason.PARSE_FAILED);
        }
    }

    private static Analysis whole(MergeKind kind, MergeReason reason) {
        return new Analysis(kind, MergeDecision.WHOLE, reason, 0, 0,
                List.of(), List.of(), List.of(), new MergePlan(List.of(), List.of()), List.of());
    }
}
