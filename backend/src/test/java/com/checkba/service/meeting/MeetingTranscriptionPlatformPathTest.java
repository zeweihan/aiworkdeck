package com.checkba.service.meeting;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.MeetingRecordingRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.SystemSettingService;
import com.checkba.service.account.AccountService;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.ExternalServiceProvider;
import com.checkba.service.platform.GatewayException;
import com.checkba.service.platform.PlatformGatewayClient;
import com.checkba.service.platform.PlatformGatewayTransport;
import com.checkba.service.site.SiteProfileService;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * platform 档的转写编排。
 *
 * <p>三件事必须钉死，错了都不会当场报错、只会在真实用户身上出问题：
 * <ol>
 *   <li><b>platform 档一次都不碰 {@link MeetingOssClient} / {@link TingwuClient}</b>。
 *       碰了就意味着两条路的凭证与失败语义纠缠在一起——用户没配阿里云凭证却在平台档
 *       看到一个阿里云的报错。
 *   <li><b>byok 档行为一字未变</b>：不走网关、不发任何出站请求。
 *   <li><b>网关失败绝不静默回落 byok</b>：回落会去花用户自己的 Key。
 * </ol>
 *
 * <p>用真的 {@link PlatformGatewayClient} + 打桩的 transport，而不是 mock 掉整个 client：
 * 幂等键带没带、超时按不按端点给、错误怎么分类，全在 client 里，mock 掉就一条都验不到。
 */
class MeetingTranscriptionPlatformPathTest {

    /** 出站桩：按请求路径回预设报文，并记录每一次调用。 */
    static class ScriptedTransport implements PlatformGatewayTransport {
        record Call(String method, String url, String idempotencyKey, String body, int timeout) {}

        final List<Call> calls = new ArrayList<>();
        final List<Reply> ticket = new ArrayList<>();
        final List<Reply> submit = new ArrayList<>();
        final List<Reply> task = new ArrayList<>();

        @Override
        public Reply send(String method, String url, String bearerKey, String idempotencyKey,
                          String jsonBody, int timeoutSeconds) {
            calls.add(new Call(method, url, idempotencyKey, jsonBody, timeoutSeconds));
            if (url.contains("/asr/ticket")) return next(ticket);
            if (url.contains("/asr/submit")) return next(submit);
            if (url.contains("/asr/task/")) return next(task);
            return new Reply(404, "{}");
        }

        private Reply next(List<Reply> queue) {
            return queue.isEmpty() ? new Reply(200, "{}") : queue.remove(0);
        }

        List<Call> to(String fragment) {
            return calls.stream().filter(c -> c.url().contains(fragment)).toList();
        }
    }

    private static final String TRANSCRIPTION_JSON = """
            {"Transcription":{"Paragraphs":[{"SpeakerId":"1","Words":[
              {"Start":0,"End":900,"Text":"开庭"}]}]}}
            """;

    private MeetingRecordingRepository meetingRepository;
    private ProjectFileRepository projectFileRepository;
    private ProjectStorageResolver storageResolver;
    private SystemSettingService settingService;
    private TingwuClient tingwu;
    private MeetingOssClient oss;
    private MeetingAudioTranscoder transcoder;
    private MeetingTranscriptionService.BinaryUploader uploader;
    private ScriptedTransport transport;
    private AccountService accountService;
    private ExternalProviderResolver resolver;
    private Path tempDir;
    private File audioFile;

    @BeforeEach
    void setUp() throws Exception {
        meetingRepository = mock(MeetingRecordingRepository.class);
        projectFileRepository = mock(ProjectFileRepository.class);
        storageResolver = mock(ProjectStorageResolver.class);
        settingService = mock(SystemSettingService.class);
        tingwu = mock(TingwuClient.class);
        oss = mock(MeetingOssClient.class);
        transcoder = mock(MeetingAudioTranscoder.class);
        uploader = mock(MeetingTranscriptionService.BinaryUploader.class);
        transport = new ScriptedTransport();
        accountService = mock(AccountService.class);
        resolver = mock(ExternalProviderResolver.class);

        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settingService.get(anyString(), anyString())).thenReturn("");
        when(accountService.currentKeyOrNull()).thenReturn("awdk_test");
        when(resolver.resolve(ExternalServiceProvider.ASR)).thenReturn(ExternalServiceProvider.PLATFORM);

        tempDir = Files.createTempDirectory("awd-meeting-test-");
        audioFile = tempDir.resolve("audio.mp3").toFile();
        // 一分钟 48 kbps 单声道 mp3 的量级；durationMs 有值时用不到它
        Files.write(audioFile.toPath(), new byte[360_000]);

        ProjectFile pf = new ProjectFile();
        pf.setId(11L);
        pf.setFilePath("projects/1/audio.mp3");
        when(projectFileRepository.findById(11L)).thenReturn(Optional.of(pf));
        when(storageResolver.resolve(anyString())).thenReturn(audioFile.toPath());
        when(transcoder.toMp3(any(), any())).thenReturn(audioFile);
    }

    private MeetingTranscriptionService service() {
        SiteProfileService site = mock(SiteProfileService.class);
        when(site.baseUrl()).thenReturn("https://www.aiworkdeck.com");
        PlatformGatewayClient gateway = new PlatformGatewayClient(transport, accountService, site);
        return new MeetingTranscriptionService(
                meetingRepository, projectFileRepository, storageResolver, settingService,
                transcoder, tingwu, oss, resolver, gateway, mock(LocalAsrClient.class),
                mock(MeetingTranscriptionService.UrlFetcher.class), uploader,
                "", "", "", "", "");
    }

    private MeetingRecording meeting(String status) {
        MeetingRecording m = new MeetingRecording();
        m.setId(7L);
        m.setProjectId(1L);
        m.setTitle("会议 08-17 11:00");
        m.setStatus(status);
        m.setAudioFileId(11L);
        m.setDurationMs(600_000L); // 10 分钟
        m.setCreatedBy(10001L);
        when(meetingRepository.findById(7L)).thenReturn(Optional.of(m));
        return m;
    }

    /** 提交跑在单线程后台执行器里，等它把活干到某个可观测的终点。 */
    private void awaitUntil(java.util.function.BooleanSupplier done) throws Exception {
        for (int i = 0; i < 300 && !done.getAsBoolean(); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(done.getAsBoolean(), "后台提交没有在 3 秒内跑到预期状态");
    }

    @Test
    @DisplayName("platform 档提交：ticket → 直传 → submit，全程不碰 OSS/听悟两个接口")
    void submitViaPlatformNeverTouchesByokClients() throws Exception {
        transport.ticket.add(new PlatformGatewayTransport.Reply(200, """
                {"objectKey":"asr/abc/1.mp3","uploadUrl":"https://oss/put","uploadMethod":"PUT",
                 "contentType":"audio/mpeg","expiresInSec":1800,"maxUploadBytes":22020096,
                 "estimatedCredits":100,"unit":"minute","balanceCents":10000,"needsConfirm":false}"""));
        transport.submit.add(new PlatformGatewayTransport.Reply(200,
                "{\"taskId\":\"asr_abc\",\"heldCents\":100,\"estimatedUnits\":10,\"unit\":\"minute\",\"pollAfterSec\":10}"));

        MeetingRecording out = meeting(MeetingRecording.STATUS_RECORDED);
        assertEquals(MeetingRecording.STATUS_TRANSCRIBING, service().startTranscription(7L).getStatus());
        awaitUntil(() -> out.getGatewayTaskId() != null);

        // 这条是本批最重要的一条：两条路在编排层分叉，byok 的两个实现一次都不该被调到
        verifyNoInteractions(oss);
        verifyNoInteractions(tingwu);

        verify(uploader).put(eq("https://oss/put"), any(File.class), eq("audio/mpeg"));

        List<ScriptedTransport.Call> tickets = transport.to("/asr/ticket");
        assertEquals(1, tickets.size());
        assertNull(tickets.get(0).idempotencyKey(), "ticket 不扣费，不该占幂等键");
        assertTrue(tickets.get(0).body().contains("\"durationSec\":600"), "按 durationMs 申报时长");

        List<ScriptedTransport.Call> submits = transport.to("/asr/submit");
        assertEquals(1, submits.size());
        assertNotNull(submits.get(0).idempotencyKey(), "会扣费的 POST 必须带幂等键");
        assertTrue(submits.get(0).body().contains("asr/abc/1.mp3"));
        assertEquals(30, submits.get(0).timeout(), "submit 超时按服务给（30 秒），不是账户通道的 5 秒");

        // taskId 必须落库，否则重启后没人去把那笔预扣结算掉
        assertEquals("asr_abc", out.getGatewayTaskId());
        assertNull(out.getTingwuTaskId(), "平台档不该往听悟的列里写东西");
    }

    @Test
    @DisplayName("网关失败 → 落 FAILED 带可读原因，绝不回落 BYOK 去花用户自己的 Key")
    void gatewayFailureNeverFallsBackToByok() throws Exception {
        transport.ticket.add(new PlatformGatewayTransport.Reply(409,
                "{\"error\":\"no_credits\",\"message\":\"Credits 余额不足，到官网充值后即可继续\"}"));

        MeetingRecording m = meeting(MeetingRecording.STATUS_RECORDED);
        service().startTranscription(7L);
        awaitUntil(() -> MeetingRecording.STATUS_FAILED.equals(m.getStatus()));

        assertEquals(MeetingRecording.STATUS_FAILED, m.getStatus());
        assertTrue(m.getError().contains("余额不足"), "失败原因必须可读，实际：" + m.getError());
        verifyNoInteractions(oss);
        verifyNoInteractions(tingwu);
        verifyNoInteractions(uploader);
        assertTrue(transport.to("/asr/submit").isEmpty(), "ticket 就被拒了，不该继续提交");
    }

    @Test
    @DisplayName("未连账户：不发请求，文案不含三个掉线判定子串，并指出自备 Key 这条出路")
    void notConnectedGivesReadableGuidance() {
        when(accountService.currentKeyOrNull()).thenReturn(null);
        meeting(MeetingRecording.STATUS_RECORDED);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().startTranscription(7L));
        for (String forbidden : List.of("登录", "未授权", "请先")) {
            assertFalse(e.getMessage().contains(forbidden),
                    "命中「" + forbidden + "」会被 api.js 判成掉线并清会话：" + e.getMessage());
        }
        assertTrue(e.getMessage().contains("自己的"), "要给出自备 Key 的出路：" + e.getMessage());
        assertTrue(transport.calls.isEmpty(), "未连账户时一个请求都不该发出去");
    }

    @Test
    @DisplayName("configured 按档位判：platform 档看有没有连账户，不再要求那 5 个凭证")
    void configuredIsPerProvider() {
        MeetingTranscriptionService service = service();
        assertTrue(service.isConfigured(), "已连账户 = platform 档可用（凭证是我们出的）");

        when(accountService.currentKeyOrNull()).thenReturn(null);
        assertFalse(service().isConfigured());

        // 切回 byok：判据回到那 5 个凭证
        when(resolver.resolve(ExternalServiceProvider.ASR)).thenReturn(ExternalServiceProvider.BYOK);
        when(accountService.currentKeyOrNull()).thenReturn("awdk_test");
        assertFalse(service().isConfigured(), "byok 档没填凭证就是没配好");
        when(settingService.get(anyString(), anyString())).thenReturn("x");
        assertTrue(service().isConfigured());
    }

    @Test
    @DisplayName("轮询完成：结果内联落库，不下载 URL、不删 OSS（那是网关的义务）")
    void pollCompletesFromInlinedResults() {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setGatewayTaskId("asr_abc");
        transport.task.add(new PlatformGatewayTransport.Reply(200, """
                {"status":"completed","taskId":"asr_abc",
                 "transcription":%s,"autoChapters":null,"summarization":null,"meetingAssistance":null,
                 "billing":{"service":"asr","op":"transcribe","units":42,"unit":"minute","chargedCents":420}}"""
                .formatted(quote(TRANSCRIPTION_JSON))));

        MeetingRecording out = service().refreshIfNeeded(m);

        assertEquals(MeetingRecording.STATUS_TRANSCRIBED, out.getStatus());
        assertTrue(out.getTranscriptJson().contains("开庭"));
        verifyNoInteractions(oss);
        verifyNoInteractions(tingwu);
        assertEquals(15, transport.to("/asr/task/").get(0).timeout(), "task 超时 15 秒");
    }

    @Test
    @DisplayName("轮询失败：落 FAILED 带网关给的原因，一分钱不扣由服务端保证")
    void pollFailedIsTerminal() {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setGatewayTaskId("asr_abc");
        transport.task.add(new PlatformGatewayTransport.Reply(200,
                "{\"status\":\"failed\",\"taskId\":\"asr_abc\",\"message\":\"音频损坏\",\"billing\":{\"chargedCents\":0}}"));

        MeetingRecording out = service().refreshIfNeeded(m);

        assertEquals(MeetingRecording.STATUS_FAILED, out.getStatus());
        assertTrue(out.getError().contains("音频损坏"));
    }

    @Test
    @DisplayName("网关暂时不可达不落 FAILED（任务还在跑、钱还被占着）；说不认识这个任务才落")
    void transientErrorsKeepTranscribing() {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setGatewayTaskId("asr_abc");
        transport.task.add(new PlatformGatewayTransport.Reply(
                PlatformGatewayTransport.Reply.NETWORK_FAILURE, null));
        transport.task.add(new PlatformGatewayTransport.Reply(
                PlatformGatewayTransport.Reply.NETWORK_FAILURE, null));

        assertEquals(MeetingRecording.STATUS_TRANSCRIBING, service().refreshIfNeeded(m).getStatus());
        assertNull(m.getError());

        m.setLastPolledAt(null);
        transport.task.add(new PlatformGatewayTransport.Reply(400,
                "{\"error\":\"bad_request\",\"message\":\"找不到该转写任务\"}"));
        MeetingRecording out = service().refreshIfNeeded(m);
        assertEquals(MeetingRecording.STATUS_FAILED, out.getStatus(),
                "网关说不认识这个任务，再问一万次也是同一个答案");
    }

    @Test
    @DisplayName("轮询走哪条路由由落库的 taskId 决定，不由当前档位设置决定")
    void pollRoutingFollowsStoredTaskId() throws Exception {
        // 任务是 byok 档提交的（只有 tingwuTaskId），此刻设置已经被切成 platform
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setTingwuTaskId("tingwu-1");
        when(tingwu.getTask(any(), eq("tingwu-1")))
                .thenReturn(new TingwuClient.TaskInfo("ONGOING", null, null, null, null, null));

        service().refreshIfNeeded(m);

        verify(tingwu).getTask(any(), eq("tingwu-1"));
        assertTrue(transport.calls.isEmpty(), "byok 提交的任务不该拿去问网关");
    }

    @Test
    @DisplayName("轮询节流 10 秒，与 byok 档同一口径")
    void pollThrottled() {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setGatewayTaskId("asr_abc");
        m.setLastPolledAt(java.time.LocalDateTime.now());

        service().refreshIfNeeded(m);

        assertTrue(transport.calls.isEmpty());
    }

    /** 把一段 JSON 文本变成 JSON 字符串字面量（网关是这么内联结果的）。 */
    private static String quote(String raw) {
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(raw).toString();
    }
}
