package com.checkba.service.ai;

import com.checkba.model.entity.Tag;
import com.checkba.service.DocumentTextService;
import com.checkba.service.FileTagService;
import com.checkba.service.TagService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * dev-board#74 审计条目：自动打标签闸的 check-then-act 竞态——两次近乎同时的自动保存/
 * 上传事件对同一 fileId 触发 autoTagFile，hasAutoTags() 是裸 SELECT、与第一次落标签之间
 * 隔着一整趟 LLM 往返，都读到"还没打过标签"就都跑一遍 LLM、都落一遍标签，标签数翻倍。
 *
 * <p>用真实两个线程 + Mockito 手工维护的"内存标签表"复现：{@code FileTagService} 与
 * {@code TagService} 都不是数据库支撑的真实实现，而是用一个同步 List 手工模拟状态——
 * 这样才能在断言里精确数出"到底落了几条标签"，不依赖 Mockito 的调用计数。
 *
 * <p>并发构造用单向门（语义与 DdService/ShareholderMeetingService 的 ensureFolder 竞态
 * 测试同一套）：栅在 hasAutoTags() 内部调用的 fileTagService.getTagsByFileId 上。不能用
 * 双向栅栏对称等待——修复后第二个线程会卡在按 fileId 的进程内锁上，根本走不到这次调用，
 * 双向栅栏会互相等成死锁；单向门只让先到的那个等一小段超时，最坏情况多等这一段，不会死锁。
 */
class AutoTaggingConcurrentTest {

    /** 单向门：只栅头两次调用，其余（如有）直接放行。 */
    static final class ReadGate {
        private final AtomicBoolean firstArrived = new AtomicBoolean(false);
        private final CountDownLatch secondArrived = new CountDownLatch(1);

        void onRead() throws InterruptedException {
            if (firstArrived.compareAndSet(false, true)) {
                secondArrived.await(3, TimeUnit.SECONDS);
            } else {
                secondArrived.countDown();
            }
        }
    }

    @Test
    void 并发自动保存不会把同一文件的标签打两遍() throws Exception {
        long fileId = 555L;
        long projectId = 9501L;

        // 手工维护的"标签表"：list 里的每一条代表一次真实落库的 FileTag
        List<Tag> insertedTags = Collections.synchronizedList(new ArrayList<>());
        ReadGate gate = new ReadGate();

        FileTagService fileTagService = mock(FileTagService.class);
        when(fileTagService.getTagsByFileId(fileId)).thenAnswer(inv -> {
            gate.onRead();
            return new ArrayList<>(insertedTags);
        });

        AtomicLong tagIdSeq = new AtomicLong(100);
        TagService tagService = mock(TagService.class);
        when(tagService.getOrCreateSystemTag(anyLong(), anyString(), anyString())).thenAnswer(inv -> {
            Tag t = new Tag();
            t.setId(tagIdSeq.incrementAndGet());
            t.setName(inv.getArgument(1));
            t.setIsSystem(true);
            return t;
        });
        // addTagToFile 落库：往"表"里追加一行，模拟真实的 FileTag 落库
        org.mockito.Mockito.doAnswer(inv -> {
            Tag t = new Tag();
            t.setId((Long) inv.getArgument(1));
            t.setIsSystem(true);
            insertedTags.add(t);
            return null;
        }).when(fileTagService).addTagToFile(anyLong(), anyLong(), anyLong());

        DocumentTextService documentTextService = mock(DocumentTextService.class);
        when(documentTextService.extractText(any()))
                .thenReturn("这是一份股权转让协议，甲方与乙方就标的公司股权转让事宜达成如下条款……".repeat(3));

        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(any(UserMessage.class))).thenAnswer(inv -> {
            // 模拟真实 LLM 往返耗时，拉宽竞态窗口（非必须——单向门已经保证竞态一定发生，
            // 这里只是让复现更贴近真实场景的"上百毫秒到几秒"）
            Thread.sleep(50);
            return Response.from(AiMessage.from("合同,协议,担保,保证金,违约"));
        });

        ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.getAuxChatModel()).thenReturn(model);

        AutoTaggingService service = new AutoTaggingService(
                chatModelFactory, tagService, fileTagService, documentTextService,
                mock(AuxModelResolver.class), mock(TokenUsageService.class));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> service.autoTagFile(projectId, fileId, "some/path.docx", 1L));
            Future<?> f2 = pool.submit(() -> service.autoTagFile(projectId, fileId, "some/path.docx", 2L));
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(5, insertedTags.size(),
                "两次并发自动打标签必须只有一次真正落标签（5 个），不能两次都落导致标签翻倍: "
                        + insertedTags.size());
    }
}
