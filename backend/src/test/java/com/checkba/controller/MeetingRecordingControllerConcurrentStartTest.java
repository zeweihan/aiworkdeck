package com.checkba.controller;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.UserSessionService;
import com.checkba.service.meeting.MeetingRecordingService;
import com.checkba.service.meeting.MeetingTranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * dev-board#74 稳定性审计：并发「开始录音」竞态。
 *
 * <p>前端 {@code isRecordingActive()}（frontend/src/utils/meetingRecorder.js）只是
 * 同一个 JS 运行时里的内存标记，管不到"同一账号桌面端 + 浏览器标签页各开一份""同一
 * 项目多个成员各自点了开始"这类跨客户端并发——这两种都是正常使用即可触发，不需要
 * 恶意操作。{@link MeetingRecordingService#create} 内部 {@code uniqueName()} 是
 * "查名字是否存在 → 建档"两步式，中间有窗口：两个并发请求都能查到"名字不存在"，
 * 各自建出一行同名 ProjectFile，物理路径只由 projectId/parentId/name 决定（不含行
 * id），两行会落到同一个物理文件上，后续两段录音的分片上传各写各的 audioFileId
 * 却写进同一个文件，互相覆盖。
 *
 * <p>本测试验证控制器层新加的按 projectId 分桶的锁，把 {@code create()} 的整次
 * 调用（含其内部 {@code @Transactional} 提交）正确串行化。
 */
class MeetingRecordingControllerConcurrentStartTest {

    @BeforeEach
    void setUp() {
        // 本类不走 Spring TestContext，AuthController 的 static 指针可能被别的测试类
        // 钉在别处——先钉一个关着 local-mode 的空实例，逼 getUserIdFromSession 落到
        // staticUserSessionService（本测试下面自己注册的 mock），不受执行顺序影响
        // （同 DeviceTokenServiceTest 的自钉写法）。
        AuthController.registerLocalIdentityService(
                new LocalIdentityService(null, null, null, null, false));
    }

    @Test
    @DisplayName("并发「开始录音」互斥：同一 projectId 第二个请求必须等第一个建档完全结束才能进")
    void createIsSerializedPerProject() throws Exception {
        MeetingRecordingService meetingService = mock(MeetingRecordingService.class);
        MeetingTranscriptionService transcriptionService = mock(MeetingTranscriptionService.class);
        ProjectMemberService projectMemberService = mock(ProjectMemberService.class);
        UserSessionService userSessionService = mock(UserSessionService.class);
        when(userSessionService.resolveUserId("sess-a")).thenReturn(7L);
        when(userSessionService.resolveUserId("sess-b")).thenReturn(8L);
        AuthController.registerUserSessionService(userSessionService);

        when(projectMemberService.hasReadPermission(eq(1L), anyLong())).thenReturn(true);

        // 可控闸门：第一次调用停在"临界区里"，直到测试主动放行——把"两个请求确实在
        // 同时进行"这件事做成确定性的，不靠 sleep 撞运气（同 VerificationCodeStoreTest 的写法）。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger();
        when(meetingService.create(eq(1L), anyLong())).thenAnswer(inv -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS), "release 门必须在超时前被打开");
            }
            MeetingRecording m = new MeetingRecording();
            m.setId((long) n);
            m.setProjectId(1L);
            return m;
        });

        MeetingRecordingController controller =
                new MeetingRecordingController(meetingService, transcriptionService, projectMemberService);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = pool.submit(() -> controller.create(1L, "sess-a"));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "第一个 create 应当已经进入 meetingService.create()");

            Future<Map<String, Object>> second = pool.submit(() -> controller.create(1L, "sess-b"));
            assertThrows(TimeoutException.class, () -> second.get(600, TimeUnit.MILLISECONDS),
                    "同一 projectId 的开始录音必须互斥：不然两个并发请求都能查到「名字不存在」，"
                            + "各建一份指向同一物理路径的 ProjectFile，两段录音互相覆盖");

            release.countDown();
            assertNotNull(first.get(5, TimeUnit.SECONDS));
            assertNotNull(second.get(5, TimeUnit.SECONDS));
            assertEquals(2, callCount.get(), "两个请求最终都要成功建档，锁只是串行化，不是拒绝第二个");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("不同 projectId 互不阻塞：锁按项目分桶，不会把无关项目的开始录音也串行化")
    void differentProjectsDoNotBlockEachOther() throws Exception {
        MeetingRecordingService meetingService = mock(MeetingRecordingService.class);
        MeetingTranscriptionService transcriptionService = mock(MeetingTranscriptionService.class);
        ProjectMemberService projectMemberService = mock(ProjectMemberService.class);
        UserSessionService userSessionService = mock(UserSessionService.class);
        when(userSessionService.resolveUserId("sess-a")).thenReturn(7L);
        when(userSessionService.resolveUserId("sess-b")).thenReturn(8L);
        AuthController.registerUserSessionService(userSessionService);
        when(projectMemberService.hasReadPermission(anyLong(), anyLong())).thenReturn(true);

        CountDownLatch project1Entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(meetingService.create(eq(1L), anyLong())).thenAnswer(inv -> {
            project1Entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            MeetingRecording m = new MeetingRecording();
            m.setId(1L);
            return m;
        });
        when(meetingService.create(eq(2L), anyLong())).thenReturn(new MeetingRecording());

        MeetingRecordingController controller =
                new MeetingRecordingController(meetingService, transcriptionService, projectMemberService);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = pool.submit(() -> controller.create(1L, "sess-a"));
            assertTrue(project1Entered.await(5, TimeUnit.SECONDS));

            // 项目 2 与项目 1 互不相干，即便项目 1 还卡在临界区里，项目 2 的请求也必须立刻通过
            Future<Map<String, Object>> other = pool.submit(() -> controller.create(2L, "sess-b"));
            assertNotNull(other.get(2, TimeUnit.SECONDS), "不同 projectId 不应该被彼此的锁挡住");

            release.countDown();
            assertNotNull(first.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
