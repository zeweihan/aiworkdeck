// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.addin;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 同一账号下「当前开着的插件窗格」登记簿（dev-board#717）。
 *
 * <p>跨文档读写要回答「这个账号现在开着哪些文档、各自的窗格连在哪个会话上」，
 * 答案来自窗格每 30 秒一次的心跳：90 秒没有心跳就视为已关，窗格卸载时发告别即删。
 *
 * <p>进程内存，单区域单 JVM 前提（北京、新加坡各一个实例）；重启即清空，
 * 窗格下一次心跳就会重新登记，不需要落库。
 */
@Service
public class PaneRegistry {

    /** 心跳间隔 30 秒的 3 倍：容忍丢两次心跳。 */
    static final long EXPIRY_MS = 90_000;

    /**
     * 一个窗格的登记信息。userId 与 lastSeenMs 由服务端填写，请求体里的值不作数。
     *
     * @param host   word / excel / powerpoint
     * @param family office / wps
     */
    public record PaneInfo(String paneId, Long userId, String host, String family, String docName,
                           Long projectId, String conversationId, long lastSeenMs) {
        PaneInfo withSeen(Long uid, long ms) {
            return new PaneInfo(paneId, uid, host, family, docName, projectId, conversationId, ms);
        }
    }

    private final Map<Long, Map<String, PaneInfo>> byUser = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public PaneRegistry() {
        this(System::currentTimeMillis);
    }

    PaneRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    public void heartbeat(Long userId, PaneInfo info) {
        if (userId == null || info == null || info.paneId() == null || info.paneId().isBlank()) return;
        long now = clock.getAsLong();
        Map<String, PaneInfo> m = byUser.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        // 窗格每次载入都换一个 paneId：只在 list() 里清理的话，没来得及告别的旧窗格
        // 会在从不发起跨文档读取的账号下一直留着
        purgeExpired(m, now);
        m.put(info.paneId(), info.withSeen(userId, now));
    }

    public void bye(Long userId, String paneId) {
        if (userId == null || paneId == null) return;
        Map<String, PaneInfo> m = byUser.get(userId);
        if (m != null) m.remove(paneId);
    }

    /** 该账号仍然在线的窗格（排除 excludePaneId，通常是发起方自己），按文档名排序。 */
    public List<PaneInfo> list(Long userId, String excludePaneId) {
        if (userId == null) return List.of();
        Map<String, PaneInfo> m = byUser.get(userId);
        if (m == null) return List.of();
        purgeExpired(m, clock.getAsLong());
        List<PaneInfo> out = new ArrayList<>();
        for (PaneInfo p : m.values()) {
            if (!p.paneId().equals(excludePaneId)) out.add(p);
        }
        out.sort(Comparator.comparing(PaneInfo::docName, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    public Optional<PaneInfo> find(Long userId, String paneId) {
        if (paneId == null) return Optional.empty();
        return list(userId, null).stream().filter(p -> p.paneId().equals(paneId)).findFirst();
    }

    /**
     * 当前挂在该会话上的窗格（会话 id 随切换会话/项目而变，心跳会覆盖）。
     *
     * <p>本登记簿按 paneId 存，同一条会话上可以同时挂着多个窗格（两份未保存的新文档算出
     * 同一个会话键就是这种情形）。那时「哪一个是它」没有答案，这里宁可不给：返回 empty
     * 而不是随便挑一个——挑错了，跨文档写入的来源署名就会挂到另一份文档头上。
     * 要判断这条会话是否被多个窗格共用，用 {@link #panesOfConversation}。
     */
    public Optional<PaneInfo> paneOfConversation(Long userId, String conversationId) {
        List<PaneInfo> panes = panesOfConversation(userId, conversationId);
        return panes.size() == 1 ? Optional.of(panes.get(0)) : Optional.empty();
    }

    /** 挂在该会话上的全部窗格。多于一个 = 这条会话寻址不到某一个具体窗格。 */
    public List<PaneInfo> panesOfConversation(Long userId, String conversationId) {
        if (conversationId == null) return List.of();
        return list(userId, null).stream().filter(p -> conversationId.equals(p.conversationId())).toList();
    }

    /** 该账号名下的登记条数（含尚未被清理的过期条目），回归用例的观察口。 */
    int size(Long userId) {
        Map<String, PaneInfo> m = byUser.get(userId);
        return m == null ? 0 : m.size();
    }

    private static void purgeExpired(Map<String, PaneInfo> m, long now) {
        long cutoff = now - EXPIRY_MS;
        m.values().removeIf(p -> p.lastSeenMs() < cutoff);
    }
}
