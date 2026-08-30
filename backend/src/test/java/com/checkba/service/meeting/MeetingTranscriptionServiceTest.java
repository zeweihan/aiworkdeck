package com.checkba.service.meeting;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.repository.MeetingRecordingRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.SystemSettingService;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.ExternalServiceProvider;
import com.checkba.service.platform.PlatformGatewayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 转写编排的状态机测试：桩掉听悟/OSS/下载三件外设，盯状态流转与降级路径。
 * 提交阶段的异步执行器不在这里测（涉及文件系统），由 refreshIfNeeded 的分支覆盖主逻辑。
 */
class MeetingTranscriptionServiceTest {

    private MeetingRecordingRepository meetingRepository;
    private SystemSettingService settingService;
    private TingwuClient tingwu;
    private MeetingOssClient oss;
    private MeetingTranscriptionService.UrlFetcher fetcher;
    /** service() 内部现建的档位解析桩；并发测试需要在外面 verify 它被调了几次。 */
    private ExternalProviderResolver lastResolver;

    private static final String TRANSCRIPTION_JSON = """
            {"Transcription":{"Paragraphs":[{"SpeakerId":"1","Words":[
              {"Start":0,"End":900,"Text":"开始"}]}]}}
            """;

    @BeforeEach
    void setUp() {
        meetingRepository = mock(MeetingRecordingRepository.class);
        settingService = mock(SystemSettingService.class);
        tingwu = mock(TingwuClient.class);
        oss = mock(MeetingOssClient.class);
        fetcher = mock(MeetingTranscriptionService.UrlFetcher.class);
        // save 原样返回，模拟 JPA 行为
        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private MeetingTranscriptionService service(boolean configured) {
        when(settingService.get(anyString(), anyString()))
                .thenAnswer(inv -> configured ? "x" : "");
        // 本类整体验的是 byok 档：档位解析恒回 BYOK，网关那两个协作者一次都不该被碰
        ExternalProviderResolver resolver = mock(ExternalProviderResolver.class);
        when(resolver.resolve(anyString())).thenReturn(ExternalServiceProvider.BYOK);
        lastResolver = resolver;
        return new MeetingTranscriptionService(
                meetingRepository, mock(ProjectFileRepository.class), null, settingService,
                mock(MeetingAudioTranscoder.class), tingwu, oss,
                resolver, mock(PlatformGatewayClient.class), mock(LocalAsrClient.class),
                fetcher, mock(MeetingTranscriptionService.BinaryUploader.class),
                MeetingTranscriptionService.DEFAULT_TRANSCODE_TIMEOUT,
                "", "", "", "", "");
    }

    private MeetingRecording meeting(String status) {
        MeetingRecording m = new MeetingRecording();
        m.setId(7L);
        m.setProjectId(1L);
        m.setTitle("会议 08-14 11:00");
        m.setStatus(status);
        m.setCreatedBy(10001L);
        return m;
    }

    @Test
    @DisplayName("未配置凭证时提交转写给出可读错误（降级路径的文案入口）")
    void startWithoutCredentials() {
        MeetingRecording m = meeting(MeetingRecording.STATUS_RECORDED);
        when(meetingRepository.findById(7L)).thenReturn(Optional.of(m));
        // IllegalArgumentException 才会被 GlobalExceptionHandler 透传 message（可读降级文案的前提）
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service(false).startTranscription(7L));
        assertTrue(e.getMessage().contains("未配置转写服务凭证"));
    }

    @Test
    @DisplayName("录音中不允许提交转写")
    void startWhileRecording() {
        MeetingRecording m = meeting(MeetingRecording.STATUS_RECORDING);
        when(meetingRepository.findById(7L)).thenReturn(Optional.of(m));
        assertThrows(IllegalArgumentException.class, () -> service(true).startTranscription(7L));
    }

    @Test
    @DisplayName("TRANSCRIBING/TRANSCRIBED 幂等返回，不重复建任务")
    void startIdempotent() {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBED);
        when(meetingRepository.findById(7L)).thenReturn(Optional.of(m));
        MeetingRecording out = service(true).startTranscription(7L);
        assertEquals(MeetingRecording.STATUS_TRANSCRIBED, out.getStatus());
        verifyNoInteractions(tingwu);
    }

    /**
     * 病灶：起手的状态判定（TRANSCRIBING/TRANSCRIBED 幂等短路）与真正落库的
     * {@code meeting.setStatus(TRANSCRIBING) + meetingRepository.save(meeting)} 之间
     * 隔着一整段校验逻辑，中间完全没有互斥。两个近乎同时的请求（自动结束时触发一次 +
     * 客户端超时重试一次，或者「重新提交转写」连点两下）都会读到同一个"还没在转写"的
     * 状态、都通过校验，最终都会各自提交一次转写——BYOK 档是两次真实的听悟建任务调用
     * （真花钱），platform 档是两次网关提交（各自预扣一次 Credits，等于对同一次转写
     * 扣两次费）。
     *
     * <p>修法：整个方法体包一把按 meetingId 分条的锁（同一实例内互斥）。第二个请求
     * 拿到锁时，第一个请求早已经把状态改成 TRANSCRIBING 并落库——它会在方法最开头
     * 那个既有的幂等短路分支直接返回，不会走到校验和提交。
     */
    @Test
    @DisplayName("同一会议并发提交转写：第二个请求命中幂等短路，不会重复提交")
    void concurrentStartTranscriptionOnlySubmitsOnce() throws Exception {
        MeetingRecording m = meeting(MeetingRecording.STATUS_RECORDED);
        Thread[] threadA = new Thread[1];
        CountDownLatch aPaused = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        when(meetingRepository.findById(7L)).thenAnswer(inv -> {
            if (Thread.currentThread() == threadA[0]) {
                aPaused.countDown();
                assertTrue(releaseA.await(5, TimeUnit.SECONDS), "测试主线程应该及时放行 A");
            }
            return Optional.of(m);
        });

        MeetingTranscriptionService svc = service(true);
        // resolver.resolve(...) 只在方法开头的幂等短路之后才会被调到，且完全同步（跑在
        // 调用方线程上、方法返回前必然已经调完）——用它的调用次数判定"这个线程有没有
        // 真正走到校验+提交"，不受 executor.submit(...) 那段异步后续（转码/建任务/失败
        // 落库）时序不确定的干扰。
        Thread a = new Thread(() -> {
            threadA[0] = Thread.currentThread();
            svc.startTranscription(7L);
        });
        a.start();
        assertTrue(aPaused.await(5, TimeUnit.SECONDS), "线程 A 应该先卡在 findById 里");

        // **状态要在 B 线程里当场取快照**，不能把共享的 MeetingRecording 对象存下来、
        // 等两个线程都 join 完再读 getStatus()：A 的异步后续（executor.submit 里的
        // 转码/建任务/失败落库）跑在同一个对象上，join 之后再读，读到的可能是 A 后来
        // 写进去的 FAILED，与 B 当时短路返回时看到的值无关。
        // 2026-08-30 这条在 CI 上真的翻红过（expected TRANSCRIBING but was FAILED），
        // 与被测的「并发只提交一次」语义毫无关系，纯粹是断言取值时机写错了。
        AtomicReference<String> bStatus = new AtomicReference<>();
        Thread b = new Thread(() -> bStatus.set(svc.startTranscription(7L).getStatus()));
        b.start();

        Thread.sleep(300); // 有锁的话 B 这时候应该还卡在方法入口，等 A 彻底做完
        verify(lastResolver, never()).resolve(anyString());

        releaseA.countDown();
        a.join(5000);
        b.join(5000);

        // 只应该有一次真正走到校验+提交；B 命中的是方法开头既有的幂等短路分支
        verify(lastResolver, times(1)).resolve(anyString());
        assertEquals(MeetingRecording.STATUS_TRANSCRIBING, bStatus.get());
    }

    @Test
    @DisplayName("poll-on-read：COMPLETED 时下载解析落库并清理 OSS 中转对象")
    void refreshCompletes() throws Exception {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setTingwuTaskId("task-1");
        when(tingwu.getTask(any(), eq("task-1"))).thenReturn(new TingwuClient.TaskInfo(
                "COMPLETED", null, "http://r/trans", null, null, null));
        when(fetcher.fetch("http://r/trans")).thenReturn(TRANSCRIPTION_JSON);

        MeetingRecording out = service(true).refreshIfNeeded(m);

        assertEquals(MeetingRecording.STATUS_TRANSCRIBED, out.getStatus());
        assertNotNull(out.getTranscriptJson());
        assertTrue(out.getTranscriptJson().contains("开始"));
        verify(oss, atLeastOnce()).deleteQuietly(any(), anyString());
    }

    @Test
    @DisplayName("poll-on-read：听悟 FAILED 落 FAILED 带原因")
    void refreshFailure() throws Exception {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setTingwuTaskId("task-1");
        when(tingwu.getTask(any(), eq("task-1"))).thenReturn(new TingwuClient.TaskInfo(
                "FAILED", "音频损坏", null, null, null, null));

        MeetingRecording out = service(true).refreshIfNeeded(m);

        assertEquals(MeetingRecording.STATUS_FAILED, out.getStatus());
        assertTrue(out.getError().contains("音频损坏"));
    }

    @Test
    @DisplayName("poll-on-read：听悟 INVALID 也是终态失败（不能一路当成还在跑），并清中转对象")
    void refreshInvalidIsTerminalFailure() throws Exception {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setTingwuTaskId("task-1");
        when(tingwu.getTask(any(), eq("task-1"))).thenReturn(new TingwuClient.TaskInfo(
                "INVALID", "音频地址无法访问", null, null, null, null));

        MeetingRecording out = service(true).refreshIfNeeded(m);

        assertEquals(MeetingRecording.STATUS_FAILED, out.getStatus());
        // 文案要能看出是上游拒收，不是我们卡住了
        assertTrue(out.getError().contains("未受理"), "实际文案: " + out.getError());
        assertTrue(out.getError().contains("音频地址无法访问"));
        // 清理只挂在完成/失败两条路径上，漏判终态就会把中转音频永远留在 OSS 里
        verify(oss, atLeastOnce()).deleteQuietly(any(), anyString());
    }

    @Test
    @DisplayName("poll-on-read：查询网络异常不落 FAILED（任务还在听悟侧跑）")
    void refreshQueryErrorKeepsTranscribing() throws Exception {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setTingwuTaskId("task-1");
        when(tingwu.getTask(any(), anyString())).thenThrow(new RuntimeException("timeout"));

        MeetingRecording out = service(true).refreshIfNeeded(m);

        assertEquals(MeetingRecording.STATUS_TRANSCRIBING, out.getStatus());
        assertNull(out.getError());
    }

    @Test
    @DisplayName("poll-on-read 节流：10 秒内不重复问听悟")
    void refreshThrottled() throws Exception {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setTingwuTaskId("task-1");
        m.setLastPolledAt(LocalDateTime.now());

        service(true).refreshIfNeeded(m);

        verifyNoInteractions(tingwu);
    }

    @Test
    @DisplayName("非 TRANSCRIBING 状态 refresh 是空操作")
    void refreshNoopForOtherStates() {
        MeetingRecording m = meeting(MeetingRecording.STATUS_RECORDED);
        MeetingRecording out = service(true).refreshIfNeeded(m);
        assertEquals(MeetingRecording.STATUS_RECORDED, out.getStatus());
        verifyNoInteractions(tingwu);
    }

    @Test
    @DisplayName("COMPLETED 但转写结果为空 → EMPTY（合法终态，不是失败）")
    void refreshEmptyTranscriptFails() throws Exception {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setTingwuTaskId("task-1");
        when(tingwu.getTask(any(), eq("task-1"))).thenReturn(new TingwuClient.TaskInfo(
                "COMPLETED", null, "http://r/trans", null, null, null));
        when(fetcher.fetch("http://r/trans")).thenReturn("{\"Transcription\":{\"Paragraphs\":[]}}");

        MeetingRecording out = service(true).refreshIfNeeded(m);

        assertEquals(MeetingRecording.STATUS_EMPTY, out.getStatus());
        assertNull(out.getError());
    }

    @Test
    @DisplayName("COMPLETED 但结果 JSON 形状不对（异常信封，不是合法的 Transcription 结构）"
            + "→ 落 FAILED 带非空 error，不能和「没人说话」的合法空结果混成同一个终态")
    void refreshMalformedTranscriptResultIsFailureNotEmpty() throws Exception {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setTingwuTaskId("task-1");
        when(tingwu.getTask(any(), eq("task-1"))).thenReturn(new TingwuClient.TaskInfo(
                "COMPLETED", null, "http://r/trans", null, null, null));
        // 听悟侧下发的是一个错误信封，不是预期的 Transcription.Paragraphs 结构
        when(fetcher.fetch("http://r/trans")).thenReturn("{\"error\":\"expired\"}");

        MeetingRecording out = service(true).refreshIfNeeded(m);

        assertNotEquals(MeetingRecording.STATUS_EMPTY, out.getStatus(),
                "结果 JSON 形状不对不是「这场会议没人说话」，不能落成合法空结果终态");
        assertEquals(MeetingRecording.STATUS_FAILED, out.getStatus());
        assertNotNull(out.getError(), "既然是出错了就该留一个非空 error，不能悄悄清空");
    }

    @Test
    @DisplayName("并发 poll-on-read 按 meetingId 串行化：两个线程同时刷新同一个"
            + "lastPolledAt 为 null 的会议，上游客户端只被调用一次"
            + "（不能各自拿着一份旧快照都通过节流、各跑一遍下载+落库，platform 档下更是各触发一次结算）")
    void concurrentRefreshIsSerializedPerMeeting() throws Exception {
        MeetingRecording base = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        base.setTingwuTaskId("task-1");

        // 让 mock 仓库表现得像真库：save 写回、findById 读最新——这样"锁内重查"才有意义，
        // 否则测的只是"两次调用互不影响"这种和真实并发无关的假象
        AtomicReference<MeetingRecording> stored = new AtomicReference<>(base);
        when(meetingRepository.findById(7L)).thenAnswer(inv -> Optional.of(stored.get()));
        when(meetingRepository.save(any())).thenAnswer(inv -> {
            MeetingRecording m = inv.getArgument(0);
            stored.set(m);
            return m;
        });

        AtomicInteger upstreamCalls = new AtomicInteger();
        when(tingwu.getTask(any(), eq("task-1"))).thenAnswer(inv -> {
            upstreamCalls.incrementAndGet();
            return new TingwuClient.TaskInfo("COMPLETED", null, "http://r/trans", null, null, null);
        });
        when(fetcher.fetch("http://r/trans")).thenReturn(TRANSCRIPTION_JSON);

        MeetingTranscriptionService svc = service(true);

        // 两次并发请求各自拿到自己那份"lastPolledAt 为 null"的快照——对应两个独立 HTTP
        // 请求各自 findById 出来、互不共享的实体实例
        MeetingRecording snapshot1 = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        snapshot1.setTingwuTaskId("task-1");
        MeetingRecording snapshot2 = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        snapshot2.setTingwuTaskId("task-1");

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MeetingRecording> f1 = pool.submit(() -> {
                startGate.await();
                return svc.refreshIfNeeded(snapshot1);
            });
            Future<MeetingRecording> f2 = pool.submit(() -> {
                startGate.await();
                return svc.refreshIfNeeded(snapshot2);
            });
            startGate.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertEquals(1, upstreamCalls.get(),
                "两个并发请求应只有一次真正问上游，另一次必须被节流窗口挡下而不是各自都通过");
    }
}
