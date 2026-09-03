package com.checkba.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 整篇过卷的游标状态（dev-board#422）。按会话记，进程内存态，TTL 30 分钟。
 *
 * <p><b>刻意不落库</b>：过卷是一轮（至多几轮）对话内的推进，重启即丢、模型重新
 * 开始过卷即可——为它建表、写迁移、做清理，收益远不抵成本。寿命语义与
 * {@link InlineContentCache} / {@link ClientCapabilityService} 同款（按会话、内存态）。
 *
 * <p>TTL 是<b>滑动窗口</b>（每次推进续命），不是从建态起算的固定窗口：
 * 一份长文档过卷本来就要跑十几分钟，固定窗口会让它跑到一半自己过期，
 * 模型下一步就撞上「没有进行中的过卷」而从第 1 块重来。
 */
@Service
@Slf4j
public class OfficePassStateStore {

    /** 过卷状态存活时长（毫秒）：30 分钟，自最后一次推进起算。 */
    static final long TTL_MILLIS = 30L * 60 * 1000;

    /** 会话数上界：超过就顺手清掉已过期的条目（条目极小，不做更复杂的驱逐）。 */
    private static final int PURGE_THRESHOLD = 200;

    /**
     * 一次过卷的全部状态。
     *
     * @param chunks        块边界（段落序号区间），一次过卷内不变
     * @param cursor        下一个要交给模型的块下标（0 起）
     * @param startedAt     建态时刻
     * @param touchedAt     最后一次推进时刻（TTL 从这里算）
     * @param replacedTotal 全程成功改动处数
     * @param failedAll     全程失败条目（模型可换锚点重试，不阻塞推进）
     * @param contentHash   建态时的内联正文哈希；对不上说明文档被换了，过卷必须终止
     */
    public record PassState(List<OfficePassChunker.Chunk> chunks, int cursor, long startedAt, long touchedAt,
                            int replacedTotal, List<Map<String, Object>> failedAll, String contentHash) {
    }

    private final ConcurrentHashMap<String, PassState> byConversation = new ConcurrentHashMap<>();

    /** 时钟。生产恒为 System::currentTimeMillis，测试可替换以验 TTL。 */
    private volatile LongSupplier clock = System::currentTimeMillis;

    void setClockForTest(LongSupplier supplier) {
        this.clock = supplier;
    }

    /** 本会话进行中的过卷；没有或已过期返回 null（过期条目顺手移除）。 */
    public PassState get(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        PassState state = byConversation.get(conversationId);
        if (state == null) {
            return null;
        }
        if (clock.getAsLong() - state.touchedAt() > TTL_MILLIS) {
            byConversation.remove(conversationId, state);
            log.info("Office pass state expired for conversation {}", conversationId);
            return null;
        }
        return state;
    }

    /** 本会话过卷的总块数；没有进行中的过卷时为 0（编排器据此决定要不要抬步数预算）。 */
    public int totalChunks(String conversationId) {
        PassState state = get(conversationId);
        return state == null ? 0 : state.chunks().size();
    }

    /** 建态：游标停在第 1 块，累计计数从零起。同一会话重复建态即覆盖（重新开始过卷）。 */
    public PassState start(String conversationId, List<OfficePassChunker.Chunk> chunks, String contentHash) {
        long now = clock.getAsLong();
        PassState state = new PassState(List.copyOf(chunks), 0, now, now, 0, List.of(), contentHash);
        byConversation.put(conversationId, state);
        purgeExpired();
        return state;
    }

    /**
     * 推进一块：游标 +1，改动处数与失败条目并进全程累计，并续上 TTL。
     * 会话没有进行中的过卷时返回 null（调用方按「过卷已终止」处理）。
     */
    public PassState advance(String conversationId, int replacedDelta, List<Map<String, Object>> failures) {
        PassState state = get(conversationId);
        if (state == null) {
            return null;
        }
        List<Map<String, Object>> failed = new ArrayList<>(state.failedAll());
        if (failures != null) {
            failed.addAll(failures);
        }
        PassState next = new PassState(state.chunks(), state.cursor() + 1, state.startedAt(),
                clock.getAsLong(), state.replacedTotal() + Math.max(0, replacedDelta),
                List.copyOf(failed), state.contentHash());
        byConversation.put(conversationId, next);
        return next;
    }

    /**
     * 只把失败条目与改动处数并进累计，<b>不推进游标</b>。
     * 用于 stop=true 提前收尾那一步：清单要落笔、汇总要算上，但没有「下一块」。
     */
    public PassState record(String conversationId, int replacedDelta, List<Map<String, Object>> failures) {
        PassState state = get(conversationId);
        if (state == null) {
            return null;
        }
        List<Map<String, Object>> failed = new ArrayList<>(state.failedAll());
        if (failures != null) {
            failed.addAll(failures);
        }
        PassState next = new PassState(state.chunks(), state.cursor(), state.startedAt(),
                clock.getAsLong(), state.replacedTotal() + Math.max(0, replacedDelta),
                List.copyOf(failed), state.contentHash());
        byConversation.put(conversationId, next);
        return next;
    }

    /** 清态（收尾、提前结束、文档被换、用户取消）。 */
    public void clear(String conversationId) {
        if (conversationId == null) {
            return;
        }
        byConversation.remove(conversationId);
    }

    private void purgeExpired() {
        if (byConversation.size() <= PURGE_THRESHOLD) {
            return;
        }
        long now = clock.getAsLong();
        byConversation.entrySet().removeIf(e -> now - e.getValue().touchedAt() > TTL_MILLIS);
    }
}
