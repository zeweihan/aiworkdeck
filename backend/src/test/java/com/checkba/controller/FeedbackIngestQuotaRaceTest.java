package com.checkba.controller;

import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.feedback.FeedbackIngestGuard;
import com.checkba.service.feedback.FeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 病灶：FeedbackIngestGuard.check() 只读 count 判定配额，真正的落库在
 * FeedbackService.ingest() 里，两者之间此前完全没有互斥——两个并发的
 * POST /api/feedback/ingest（同一 installId）都能查到"还没到每日上限"、
 * 都通过 check()，再各自落库，共同越过配额。
 *
 * <p>修法：controller 把 check() 与 ingest() 包进同一段按 installId 分条的互斥区间
 * （FeedbackIngestGuard.lockFor）。这里直接证明互斥这条结构性质——真实的「越过配额」
 * 结果依赖具体的计数时序，不如「二者互斥」这条能稳定验证。
 */
class FeedbackIngestQuotaRaceTest {

    private UserFeedbackRepository repo;
    private FeedbackIngestGuard guard;
    private FeedbackService feedbackService;
    private FeedbackIngestController controller;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(UserFeedbackRepository.class);
        guard = new FeedbackIngestGuard(repo);
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "perInstallDaily", 20);
        ReflectionTestUtils.setField(guard, "globalDaily", 2000);
        ReflectionTestUtils.setField(guard, "maxAttachments", 4);
        ReflectionTestUtils.setField(guard, "maxAttachmentBytes", 5_242_880L);
        feedbackService = mock(FeedbackService.class);
        controller = new FeedbackIngestController(feedbackService, guard, repo);

        UserFeedback saved = new UserFeedback();
        saved.setId(1L);
        when(feedbackService.ingest(anyString(), anyString(), any(), any())).thenReturn(saved);
    }

    private static String payload(String installId, String clientRef) {
        return "{\"installId\":\"" + installId + "\",\"clientRef\":\"" + clientRef + "\",\"kind\":\"BUG\"}";
    }

    @Test
    @DisplayName("ingest 端点与持有同一 installId 分条锁的另一段代码互斥")
    void ingestIsMutuallyExclusiveWithSameInstallLock() throws Exception {
        String installId = "install-race";

        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (guard.lockFor(installId)) {
                holderReady.countDown();
                try {
                    releaseHolder.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });
        holder.start();
        assertTrue(holderReady.await(2, TimeUnit.SECONDS));

        AtomicBoolean ingestDone = new AtomicBoolean(false);
        Thread requester = new Thread(() -> {
            controller.ingest(payload(installId, "1"), List.of());
            ingestDone.set(true);
        });
        requester.start();

        Thread.sleep(2000); // holder 最多能撑到 5 秒，检查点留在 2 秒，边际够宽
        assertFalse(ingestDone.get(),
                "ingest 不该在同一 installId 的锁还没让出时就跑完——说明没有和它互斥");

        releaseHolder.countDown();
        requester.join(3000);
        assertTrue(ingestDone.get(), "锁一放，ingest 应该很快跑完");
    }

    @Test
    @DisplayName("行为不变：正常单次提交仍然成功")
    void normalSubmissionStillSucceeds() {
        ResponseEntity<?> resp = controller.ingest(payload("install-normal", "1"), List.of());
        assertEquals(200, resp.getStatusCode().value());
    }
}
