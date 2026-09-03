package com.checkba.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 过卷游标状态（dev-board#422）：内存态、按会话、TTL 30 分钟。
 *
 * <p>进程重启即丢是刻意的——过卷是一轮对话内的推进，丢了模型重新开始即可，
 * 不值得为它落库。
 */
class OfficePassStateStoreTest {

    private OfficePassStateStore store;
    private long now;

    private static final List<OfficePassChunker.Chunk> THREE = List.of(
            new OfficePassChunker.Chunk(1, 10),
            new OfficePassChunker.Chunk(11, 20),
            new OfficePassChunker.Chunk(21, 30));

    @BeforeEach
    void setUp() {
        store = new OfficePassStateStore();
        now = 1_000_000L;
        store.setClockForTest(() -> now);
    }

    @Test
    @DisplayName("首次建态：游标停在第 1 块，累计计数从零起")
    void startsAtFirstChunk() {
        OfficePassStateStore.PassState state = store.start("conv-1", THREE, "hash-a");
        assertEquals(0, state.cursor());
        assertEquals(3, state.chunks().size());
        assertEquals(0, state.replacedTotal());
        assertEquals("hash-a", state.contentHash());
        assertNotNull(store.get("conv-1"));
        assertEquals(3, store.totalChunks("conv-1"));
    }

    @Test
    @DisplayName("推进：游标 +1，改动处数与失败条目全程累计")
    void advanceAccumulates() {
        store.start("conv-1", THREE, "hash-a");
        store.advance("conv-1", 4, List.of(Map.of("index", 2, "searchText", "甲方")));
        OfficePassStateStore.PassState state = store.advance("conv-1", 3, List.of());

        assertEquals(2, state.cursor());
        assertEquals(7, state.replacedTotal());
        assertEquals(1, state.failedAll().size());
    }

    @Test
    @DisplayName("清态：clear 之后再查为空，totalChunks 归零")
    void clearRemovesState() {
        store.start("conv-1", THREE, "hash-a");
        store.clear("conv-1");
        assertNull(store.get("conv-1"));
        assertEquals(0, store.totalChunks("conv-1"));
    }

    @Test
    @DisplayName("TTL 30 分钟：过期后取不到，且不占着条目不放")
    void expiresAfterTtl() {
        store.start("conv-1", THREE, "hash-a");
        now += OfficePassStateStore.TTL_MILLIS - 1;
        assertNotNull(store.get("conv-1"), "还没到期就不该被清掉");
        now += 2;
        assertNull(store.get("conv-1"), "超过 TTL 必须判过期");
        assertEquals(0, store.totalChunks("conv-1"));
    }

    @Test
    @DisplayName("TTL 是滑动窗口：推进一次就续命，长文档过卷不会跑到一半自己过期")
    void advanceRefreshesTtl() {
        store.start("conv-1", THREE, "hash-a");
        now += OfficePassStateStore.TTL_MILLIS - 1000;
        store.advance("conv-1", 1, List.of());
        now += OfficePassStateStore.TTL_MILLIS - 1000;
        assertNotNull(store.get("conv-1"), "推进过就该续上 TTL");
    }

    @Test
    @DisplayName("会话之间互不干扰")
    void statesAreScopedPerConversation() {
        store.start("conv-1", THREE, "hash-a");
        store.start("conv-2", List.of(new OfficePassChunker.Chunk(1, 5)), "hash-b");
        store.advance("conv-1", 2, List.of());

        assertEquals(1, store.get("conv-1").cursor());
        assertEquals(0, store.get("conv-2").cursor());
        assertEquals(1, store.totalChunks("conv-2"));
    }

    @Test
    @DisplayName("没有过卷的会话：get 为 null、totalChunks 为 0、advance 不炸")
    void unknownConversationIsInert() {
        assertNull(store.get("nope"));
        assertEquals(0, store.totalChunks("nope"));
        assertNull(store.advance("nope", 1, List.of()));
        assertEquals(0, store.totalChunks(null));
    }
}
