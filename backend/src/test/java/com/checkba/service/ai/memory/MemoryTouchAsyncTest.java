// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.memory;

import com.checkba.model.entity.MemoryEntry;
import com.checkba.repository.ConversationSummaryRepository;
import com.checkba.repository.MemoryEntryRepository;
import com.checkba.repository.ProjectMemoryRepository;
import com.checkba.repository.UserMemoryRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆检索的「读路径上的写」必须让开首 token 的关键路径（dev-board#812 K32 ②，审查 perf.missed ③）。
 *
 * <p>{@code lastAccessedAt} 纯粹是使用统计，本轮没有任何东西读它；而
 * {@code retrieveMemories} 正是 {@code ContextAssemblerService.assemble} 每轮同步调的那个。
 * 留在同步路上不只是多一次往返——它还会对命中的记忆行取写锁，
 * 与 {@code MemoryPipelineService} 的异步写侧管线抢同一批行。
 */
class MemoryTouchAsyncTest {

    private MemoryEntryRepository memoryEntryRepository;

    @SuppressWarnings("unchecked")
    private MemoryManager managerWith(ExecutorService pool) {
        memoryEntryRepository = mock(MemoryEntryRepository.class);
        MemoryEntry hit = new MemoryEntry();
        hit.setId(7L);
        hit.setProjectId(88L);
        hit.setMemoryKey("交割条件");
        hit.setMemoryValue("反垄断审批通过");
        hit.setCreatedAt(LocalDateTime.now());
        when(memoryEntryRepository.searchByKeywordAndType(anyLong(), anyString(), any(), any()))
                .thenReturn(List.of(hit));
        when(memoryEntryRepository.findTopImportantMemories(anyLong(), any()))
                .thenReturn(List.of(hit));

        MemoryManager manager = new MemoryManager(
                memoryEntryRepository,
                mock(ConversationSummaryRepository.class),
                mock(ProjectMemoryRepository.class),
                mock(UserMemoryRepository.class),
                (EmbeddingStore<TextSegment>) mock(EmbeddingStore.class),
                mock(EmbeddingModel.class));
        manager.setTouchExecutorForTest(pool);
        return manager;
    }

    @Test
    @DisplayName("检索不等 lastAccessedAt 写完：写卡住 2 秒，检索仍然立刻返回")
    void retrievalDoesNotWaitForTheUsageStatisticWrite() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        MemoryManager manager = managerWith(pool);

        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AtomicBoolean writeFinished = new AtomicBoolean(false);
        org.mockito.Mockito.doAnswer(inv -> {
            writeStarted.countDown();
            releaseWrite.await(5, TimeUnit.SECONDS);
            writeFinished.set(true);
            return null;
        }).when(memoryEntryRepository).touchLastAccessedAt(any(), any());

        try {
            long started = System.nanoTime();
            List<MemoryEntry> hits = manager.retrieveMemories(88L, "交割", null, 5);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

            assertFalse(hits.isEmpty(), "桩数据应当被检索到，否则这条用例什么都没测");
            assertTrue(writeStarted.await(2, TimeUnit.SECONDS), "统计写应当确实被投递出去了");
            assertFalse(writeFinished.get(), "检索返回时统计写还卡着——说明它没有挡在路上");
            assertTrue(elapsedMs < 1000,
                    "检索不该等统计写完成（写被卡住 5 秒），实际耗时 " + elapsedMs + "ms");
        } finally {
            releaseWrite.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("没有注入池时就地同步执行（各单测与手工 new 的实例行为一字不变）")
    void withoutAPoolTheWriteStillHappensInline() {
        MemoryManager manager = managerWith(null);
        manager.retrieveMemories(88L, "交割", null, 5);
        verify(memoryEntryRepository).touchLastAccessedAt(any(), any());
    }

    @Test
    @DisplayName("统计写失败只 log，不影响检索结果")
    void aFailedStatisticWriteNeverBreaksRetrieval() {
        MemoryManager manager = managerWith(null);
        org.mockito.Mockito.doThrow(new RuntimeException("DB 挂了"))
                .when(memoryEntryRepository).touchLastAccessedAt(any(), any());
        List<MemoryEntry> hits = manager.retrieveMemories(88L, "交割", null, 5);
        assertFalse(hits.isEmpty(), "统计写失败不该让检索返回空");
    }

    @Test
    @DisplayName("投递出去的写最终真的执行了（fire-and-forget 不等于丢掉）")
    void theQueuedWriteEventuallyRuns() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        MemoryManager manager = managerWith(pool);
        try {
            manager.retrieveMemories(88L, "交割", null, 5);
            verify(memoryEntryRepository, timeout(3000)).touchLastAccessedAt(any(), any());
        } finally {
            pool.shutdownNow();
        }
    }
}
