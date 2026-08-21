package com.checkba.service.ai.memory;

import com.checkba.model.entity.ProjectMemory;
import com.checkba.repository.ConversationSummaryRepository;
import com.checkba.repository.MemoryEntryRepository;
import com.checkba.repository.ProjectMemoryRepository;
import com.checkba.repository.UserMemoryRepository;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计条目：「saveProjectMemory / saveUserMemory use a find-then-insert pattern that races
 * on the underlying unique(project_id)/unique(user_id) constraint, silently dropping the
 * loser's update」。
 *
 * 直接验证修法要保证的性质：find→决定 insert/update→save 这段必须互斥，不允许两个线程
 * 同时穿过去（那正是 unique 约束会被撞上的窗口）。用可控延时把窗口放大，
 * 没有互斥的话多个线程会同时进入这段临界区。
 */
@DisplayName("MemoryManager：saveProjectMemory 的 find-then-insert 竞态")
class MemoryManagerConcurrentSaveTest {

    private ProjectMemoryRepository projectMemoryRepository;
    private MemoryManager memoryManager;

    @BeforeEach
    void setUp() {
        MemoryEntryRepository memoryEntryRepository = mock(MemoryEntryRepository.class);
        ConversationSummaryRepository conversationSummaryRepository = mock(ConversationSummaryRepository.class);
        projectMemoryRepository = mock(ProjectMemoryRepository.class);
        UserMemoryRepository userMemoryRepository = mock(UserMemoryRepository.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        memoryManager = new MemoryManager(memoryEntryRepository, conversationSummaryRepository,
                projectMemoryRepository, userMemoryRepository, embeddingStore, embeddingModel);
    }

    @Test
    @DisplayName("并发 saveProjectMemory：find + 决定 + save 必须互斥，同一时刻只能有一个线程在临界区内")
    void findThenInsertCriticalSectionIsMutuallyExclusive() throws InterruptedException {
        AtomicInteger inCriticalSection = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        // find 与 save 之间人为插入延时，把竞态窗口放大：没有锁的话，另一个线程的 find
        // 会在这段延时内闯进来，两次都读到 Optional.empty()。
        when(projectMemoryRepository.findByProjectId(anyLong())).thenAnswer(inv -> {
            int n = inCriticalSection.incrementAndGet();
            maxConcurrent.updateAndGet(prev -> Math.max(prev, n));
            Thread.sleep(30);
            return Optional.<ProjectMemory>empty();
        });
        when(projectMemoryRepository.save(any())).thenAnswer(inv -> {
            Thread.sleep(10);
            inCriticalSection.decrementAndGet();
            return inv.getArgument(0);
        });

        int threadCount = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch done = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    memoryManager.saveProjectMemory(ProjectMemory.builder().projectId(1L).build());
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(10, TimeUnit.SECONDS), "所有并发保存都应在超时前完成");
        pool.shutdown();

        assertEquals(1, maxConcurrent.get(),
                "find-then-insert 这段必须互斥执行；同一时刻出现第二个线程说明竞态窗口仍然打开");
    }
}
