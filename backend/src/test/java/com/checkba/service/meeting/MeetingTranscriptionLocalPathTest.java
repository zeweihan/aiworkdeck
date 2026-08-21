package com.checkba.service.meeting;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.MeetingRecordingRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.SystemSettingService;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.ExternalServiceProvider;
import com.checkba.service.platform.PlatformGatewayClient;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * local 档（本机 asr-service）的转写编排。
 *
 * <p>四件事必须钉死：
 * <ol>
 *   <li><b>一次都不碰云端的三个协作者</b>（OSS / 听悟 / 网关）。碰了就意味着
 *       用户特意保护的录音仍然出了本机——正好背叛他打开这个开关的目的。
 *   <li><b>未就绪时不许开跑</b>，并且要分清「服务没起」与「模型没下」：两者的下一步完全不同。
 *   <li><b>失败绝不回落云端</b>，只落一条说清下一步的 error。
 *   <li><b>被关机打断的转写要能自愈</b>：local 档全程没有 taskId，
 *       不区分「正跑着」与「被打断了」的话，会议会永远停在转写中且重试按钮点不动。
 * </ol>
 */
class MeetingTranscriptionLocalPathTest {

    /** asr-service 的响应：时间戳是秒，没有说话人。 */
    private static final String LOCAL_JSON = """
            {"task":"transcribe","language":"zh","duration":8.4,
             "text":"本案争议焦点在于付款时间。",
             "segments":[{"id":0,"start":0.0,"end":4.2,"text":"本案争议焦点"},
                         {"id":1,"start":4.2,"end":8.4,"text":"在于付款时间。"}]}""";

    private MeetingRecordingRepository meetingRepository;
    private ProjectFileRepository projectFileRepository;
    private ProjectStorageResolver storageResolver;
    private SystemSettingService settingService;
    private TingwuClient tingwu;
    private MeetingOssClient oss;
    private PlatformGatewayClient gateway;
    private MeetingAudioTranscoder transcoder;
    private LocalAsrClient localAsr;
    private ExternalProviderResolver resolver;
    private File audioFile;

    @BeforeEach
    void setUp() throws Exception {
        meetingRepository = mock(MeetingRecordingRepository.class);
        projectFileRepository = mock(ProjectFileRepository.class);
        storageResolver = mock(ProjectStorageResolver.class);
        settingService = mock(SystemSettingService.class);
        tingwu = mock(TingwuClient.class);
        oss = mock(MeetingOssClient.class);
        gateway = mock(PlatformGatewayClient.class);
        transcoder = mock(MeetingAudioTranscoder.class);
        localAsr = mock(LocalAsrClient.class);
        resolver = mock(ExternalProviderResolver.class);

        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settingService.get(anyString(), anyString())).thenReturn("");
        when(resolver.resolve(ExternalServiceProvider.ASR)).thenReturn(ExternalServiceProvider.LOCAL);
        ready();

        Path tempDir = Files.createTempDirectory("awd-meeting-local-");
        audioFile = tempDir.resolve("audio.mp3").toFile();
        Files.write(audioFile.toPath(), new byte[360_000]);

        ProjectFile pf = new ProjectFile();
        pf.setId(11L);
        pf.setFilePath("projects/1/audio.mp3");
        when(projectFileRepository.findById(11L)).thenReturn(Optional.of(pf));
        when(storageResolver.resolve(anyString())).thenReturn(audioFile.toPath());
        when(transcoder.toMp3(any(), any())).thenReturn(audioFile);
    }

    private void ready() {
        stubProbe(LocalAsrClient.Status.READY, "本机转写已就绪，录音不会离开这台电脑。", "本地转写没有说话人分离。");
    }

    private void stubProbe(LocalAsrClient.Status status, String message, String nextStep) {
        when(localAsr.probe()).thenReturn(new LocalAsrClient.ProbeResult(
                status, "http://127.0.0.1:8890", "Systran/faster-whisper-medium", false, message, nextStep));
    }

    private MeetingTranscriptionService service() {
        return service(MeetingTranscriptionService.DEFAULT_TRANSCODE_TIMEOUT);
    }

    private MeetingTranscriptionService service(Duration transcodeTimeout) {
        return new MeetingTranscriptionService(
                meetingRepository, projectFileRepository, storageResolver, settingService,
                transcoder, tingwu, oss, resolver, gateway, localAsr,
                mock(MeetingTranscriptionService.UrlFetcher.class),
                mock(MeetingTranscriptionService.BinaryUploader.class),
                transcodeTimeout,
                "", "", "", "", "");
    }

    private MeetingRecording meeting(String status) {
        return meeting(7L, status);
    }

    private MeetingRecording meeting(long id, String status) {
        MeetingRecording m = new MeetingRecording();
        m.setId(id);
        m.setProjectId(1L);
        m.setTitle("当事人会见 08-17");
        m.setStatus(status);
        m.setAudioFileId(11L);
        m.setDurationMs(600_000L);
        m.setCreatedBy(10001L);
        when(meetingRepository.findById(id)).thenReturn(Optional.of(m));
        return m;
    }

    private void awaitUntil(java.util.function.BooleanSupplier done) throws Exception {
        for (int i = 0; i < 300 && !done.getAsBoolean(); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(done.getAsBoolean(), "后台转写没有在 3 秒内跑到预期状态");
    }

    @Test
    @DisplayName("local 档转写：音频一个字节都不出本机，段落按秒→毫秒落库，speaker 恒为 1")
    void transcribesLocallyAndNeverLeavesTheMachine() throws Exception {
        // save 原样返回入参，startTranscription 的返回值与 out 是同一个可变实例；
        // 不卡住后台推理的话，它可能赶在下面读 status 之前就把这个实例改成 TRANSCRIBED，
        // 断言的成败就成了 runner 的调度运气。断完 TRANSCRIBING 再放行。
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        when(localAsr.transcribe(any(File.class))).thenAnswer(inv -> {
            hold.await(3, TimeUnit.SECONDS);
            return LOCAL_JSON;
        });

        MeetingRecording out = meeting(MeetingRecording.STATUS_RECORDED);
        assertEquals(MeetingRecording.STATUS_TRANSCRIBING, service().startTranscription(7L).getStatus());
        hold.countDown();
        awaitUntil(() -> MeetingRecording.STATUS_TRANSCRIBED.equals(out.getStatus()));

        // 本批最重要的一条：录音不出本机 = 云端三条出站路径一次都不该被走到
        verifyNoInteractions(oss);
        verifyNoInteractions(tingwu);
        verifyNoInteractions(gateway);

        assertTrue(out.getTranscriptJson().contains("本案争议焦点"));
        assertTrue(out.getTranscriptJson().contains("\"end\":4200"), "秒要转成毫秒：" + out.getTranscriptJson());
        assertTrue(out.getTranscriptJson().contains("\"speaker\":\"1\""), "本地档没有说话人分离");
        assertNull(out.getSummaryJson(), "章节/摘要是听悟的增值结果，本地档没有");
        assertNull(out.getGatewayTaskId());
        assertNull(out.getTingwuTaskId());
    }

    @Test
    @DisplayName("模型没下：拦在提交前，文案说清缺什么与下一步，且不含三个掉线判定子串")
    void modelMissingBlocksSubmitWithActionableText() {
        stubProbe(LocalAsrClient.Status.MODEL_MISSING,
                "本机转写服务已运行，但语音识别模型还没下载。",
                "下载模型后即可让录音完全不出本机。");
        meeting(MeetingRecording.STATUS_RECORDED);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().startTranscription(7L));
        assertTrue(e.getMessage().contains("模型"), "要说清缺的是模型：" + e.getMessage());
        assertTrue(e.getMessage().contains("下载"), "要给出下一步：" + e.getMessage());
        for (String forbidden : List.of("登录", "未授权", "请先")) {
            assertFalse(e.getMessage().contains(forbidden),
                    "命中「" + forbidden + "」会被 api.js 判成掉线并清会话：" + e.getMessage());
        }
    }

    @Test
    @DisplayName("服务没起与模型没下是两条不同的路：文案必须能分开，不能都说「不可用」")
    void serviceDownAndModelMissingAreDistinguishable() {
        stubProbe(LocalAsrClient.Status.SERVICE_DOWN, "本机转写服务没有运行。", "重启 AI WorkDeck 让它自动拉起。");
        meeting(MeetingRecording.STATUS_RECORDED);
        String down = assertThrows(IllegalArgumentException.class,
                () -> service().startTranscription(7L)).getMessage();

        stubProbe(LocalAsrClient.Status.MODEL_MISSING, "模型还没下载。", "下载模型后即可使用。");
        String missing = assertThrows(IllegalArgumentException.class,
                () -> service().startTranscription(7L)).getMessage();

        assertNotEquals(down, missing);
        assertTrue(down.contains("没有运行"));
        assertTrue(missing.contains("下载"));
    }

    @Test
    @DisplayName("本机转写失败：落 FAILED 带可读原因，绝不悄悄把音频传上云")
    void localFailureNeverFallsBackToCloud() throws Exception {
        when(localAsr.transcribe(any(File.class))).thenThrow(new IllegalStateException("本机转写服务返回 500"));

        MeetingRecording m = meeting(MeetingRecording.STATUS_RECORDED);
        service().startTranscription(7L);
        awaitUntil(() -> MeetingRecording.STATUS_FAILED.equals(m.getStatus()));

        assertTrue(m.getError().contains("500"), "失败原因要可读：" + m.getError());
        verifyNoInteractions(oss);
        verifyNoInteractions(tingwu);
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("isConfigured 按档分：local 档要求服务起着且模型已下，只起服务不算")
    void configuredRequiresBothServiceAndModel() {
        assertTrue(service().isConfigured());

        stubProbe(LocalAsrClient.Status.MODEL_MISSING, "模型还没下载。", "下载模型。");
        assertFalse(service().isConfigured(), "只起了服务、模型没下，不能算配好——那样用户会在录完之后才发现转不了");

        stubProbe(LocalAsrClient.Status.SERVICE_DOWN, "服务没起。", "重启应用。");
        assertFalse(service().isConfigured());

        // local 档一次都不该去问账户连没连（那是 platform 档的判据）
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("被关机打断的转写要能自愈：两个 taskId 都为空且本 JVM 没在跑 → 落 FAILED 让用户能重试")
    void interruptedTranscriptionRecoversInsteadOfHangingForever() {
        // 模拟「上次运行留下的」：库里停在转写中，但本进程里没有对应的后台任务
        MeetingRecording stale = meeting(MeetingRecording.STATUS_TRANSCRIBING);

        MeetingRecording out = service().refreshIfNeeded(stale);

        assertEquals(MeetingRecording.STATUS_FAILED, out.getStatus());
        assertTrue(out.getError().contains("中断"), "要说清是被打断而不是转写本身失败：" + out.getError());
        assertTrue(out.getError().contains("录音"), "要告诉用户录音还在：" + out.getError());
    }

    @Test
    @DisplayName("正在跑的本地转写不许被误判成「被打断」——那会把一个还在跑的任务当场判死")
    void inFlightLocalTranscriptionIsNotMistakenForInterrupted() throws Exception {
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        when(localAsr.transcribe(any(File.class))).thenAnswer(inv -> {
            hold.await(3, TimeUnit.SECONDS);
            return LOCAL_JSON;
        });

        MeetingRecording m = meeting(MeetingRecording.STATUS_RECORDED);
        MeetingTranscriptionService service = service();
        service.startTranscription(7L);

        // 转写还卡在本机推理里（local 档全程没有 taskId），此刻轮询不能把它判死
        assertEquals(MeetingRecording.STATUS_TRANSCRIBING, service.refreshIfNeeded(m).getStatus());
        assertNull(m.getError());

        hold.countDown();
        awaitUntil(() -> MeetingRecording.STATUS_TRANSCRIBED.equals(m.getStatus()));
    }

    @Test
    @DisplayName("转码卡死超时：该会议落 FAILED 带可见错误，且随后提交的第二个会议仍能被处理"
            + "（单线程执行器不能被一次卡死永久占住）")
    void transcodeTimeoutFailsThatMeetingWithoutBlockingTheNextOne() throws Exception {
        // 模拟 FFmpeg 卡死（截断 webm 最常见的诱因）：toMp3 永远不返回
        when(transcoder.toMp3(any(), any())).thenAnswer(inv -> {
            new CountDownLatch(1).await();
            throw new IllegalStateException("不会跑到这里");
        });

        MeetingRecording first = meeting(7L, MeetingRecording.STATUS_RECORDED);
        MeetingTranscriptionService service = service(Duration.ofMillis(200));
        service.startTranscription(7L);
        awaitUntil(() -> MeetingRecording.STATUS_FAILED.equals(first.getStatus()));
        assertTrue(first.getError().contains("超时"), "要能看出是转码卡死: " + first.getError());

        // 换回正常转码，断言单线程执行器没有被第一个卡死的任务永久占住——
        // 这正是本条修复要堵的口子：没有超时闸的话，第二个会议会跟着第一个一起无限期排队。
        // 必须用 doReturn().when()，不能用 when().thenReturn()——后者的写法要先真的调用
        // 一次 transcoder.toMp3(...) 才能拿到"你在给哪个调用打桩"的信息，而这一次调用会
        // 撞上上面刚注册的、永远不返回的旧桩，把重新打桩这个动作本身也卡死在这里。
        doReturn(audioFile).when(transcoder).toMp3(any(), any());
        when(localAsr.transcribe(any(File.class))).thenReturn(LOCAL_JSON);
        MeetingRecording second = meeting(8L, MeetingRecording.STATUS_RECORDED);
        service.startTranscription(8L);
        awaitUntil(() -> MeetingRecording.STATUS_TRANSCRIBED.equals(second.getStatus()));
    }
}
