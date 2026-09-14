// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 协作事件的唯一写入口与读入口（spec 2026-09-14 §2.3）。
 *
 * <p><b>永不抛</b>：{@link #record} 整段包死。事件是旁白，记不上最多是历史里少一行
 * 「谁交了稿」，而调用点是 push 的 post-receive 钩子、加人、建项目——为一行旁白
 * 让这些真正的动作失败，是拿主流程给日志陪葬（同「版本记录失败绝不阻断主流程」纪律）。
 */
@Service
public class CollabEventService {

    private static final Logger log = LoggerFactory.getLogger(CollabEventService.class);

    /** 一页上限，与本机历史端点同一量级。 */
    public static final int MAX_LIMIT = 500;
    public static final int DEFAULT_LIMIT = 100;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CollabEventRepository repository;

    public CollabEventService(CollabEventRepository repository) {
        this.repository = repository;
    }

    /** 记一条。失败只 log，绝不外抛。 */
    public void record(CollabEvent.Kind kind, long projectId, Long actorUserId, Long tokenId,
                       String fromSha, String toSha, Integer commitCount, Long targetUserId,
                       Map<String, Object> detail) {
        try {
            if (kind == null || actorUserId == null) return;
            CollabEvent e = new CollabEvent();
            e.setProjectId(projectId);
            e.setKind(kind.name());
            e.setActorUserId(actorUserId);
            e.setTokenId(tokenId);
            e.setFromSha(fromSha);
            e.setToSha(toSha);
            e.setCommitCount(commitCount);
            e.setTargetUserId(targetUserId);
            e.setDetail(detail == null || detail.isEmpty() ? null : MAPPER.writeValueAsString(detail));
            e.setCreatedAt(LocalDateTime.now());
            repository.save(e);
        } catch (Exception ex) {
            log.warn("协作事件记录失败（已吞）: kind={}, project={}", kind, projectId, ex);
        }
    }

    /** 简写：没有 sha / 目标人的事件（SHARED、CHECKOUT）。 */
    public void record(CollabEvent.Kind kind, long projectId, Long actorUserId, Long tokenId,
                       Map<String, Object> detail) {
        record(kind, projectId, actorUserId, tokenId, null, null, null, null, detail);
    }

    /**
     * 某台设备第一次来取这个仓库才记一条 CHECKOUT——每一次 fetch 都是 upload-pack，
     * 不去重的话日常同步会把事件表刷成一条「签出」的洪流。
     *
     * <p>{@code tokenId} 为空（升级前签发的老令牌、或自建服务器的口令登录）一律不记：
     * 没有设备身份就没法去重，记下来只会一次一条。
     */
    public void recordCheckoutOnce(long projectId, Long actorUserId, Long tokenId) {
        if (actorUserId == null || tokenId == null) return;
        try {
            if (repository.existsByProjectIdAndKindAndTokenId(
                    projectId, CollabEvent.Kind.CHECKOUT.name(), tokenId)) {
                return;
            }
        } catch (Exception e) {
            log.warn("签出事件查重失败（跳过这次记录）: project={}", projectId, e);
            return;
        }
        record(CollabEvent.Kind.CHECKOUT, projectId, actorUserId, tokenId, null);
    }

    /** 倒序一页；{@code before} 非空时取 id 更小的下一页。 */
    public List<CollabEvent> list(long projectId, int limit, Long before) {
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        PageRequest page = PageRequest.of(0, size);
        return before == null
                ? repository.findByProjectIdOrderByCreatedAtDescIdDesc(projectId, page)
                : repository.findByProjectIdAndIdLessThanOrderByCreatedAtDescIdDesc(projectId, before, page);
    }

    /** detail 文本 → Map。解析不出回 null（一行坏数据不该挡住整张列表）。 */
    public static Map<String, Object> parseDetail(String detail) {
        if (detail == null || detail.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = MAPPER.readValue(detail, Map.class);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 这次 push 推进了几版：{@code old..new} 的提交数。首推（old 是全零）时
     * 整段历史都是新的，数到根为止。数不出来回 0——「几版」是旁白里的一个数字，
     * 不值得为它让 post-receive 钩子出错。
     */
    public static int countCommits(Repository repo, ObjectId oldId, ObjectId newId) {
        if (repo == null || newId == null || ObjectId.zeroId().equals(newId)) return 0;
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(newId));
            if (oldId != null && !ObjectId.zeroId().equals(oldId)) {
                walk.markUninteresting(walk.parseCommit(oldId));
            }
            int n = 0;
            while (walk.next() != null) n++;
            return n;
        } catch (Exception e) {
            log.warn("统计推进版本数失败（按 0 处理）", e);
            return 0;
        }
    }
}
