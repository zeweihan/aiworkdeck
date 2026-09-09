// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.meeting;

import com.checkba.model.dto.MeetingTranscriptionProgress;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 「转写中」卡死判定与进度提示（dev-board#532，审计遗留第 3 条）。
 *
 * <p>病灶：会议进入 TRANSCRIBING 之后，只有上游给出终态才会离开这个状态。
 * 上游任务永远不回终态（听悟侧任务丢了、网关侧任务被回收）时，会议<b>永远</b>停在转写中，
 * 而 startTranscription 对 TRANSCRIBING 是幂等返回，用户连「重试转写」都点不动。
 *
 * <p>拍板的阈值：{@code max(30 分钟, 音频时长 × 3)}。
 */
class MeetingTranscriptionTimeoutTest {

    private MeetingRecordingRepository meetingRepository;
    private SystemSettingService settingService;
    private TingwuClient tingwu;
    private ExternalProviderResolver resolver;

    /** 还在跑（ONGOING）：卡死判定不生效时，refreshIfNeeded 会照常轮询并保持 TRANSCRIBING。 */
    private static final TingwuClient.TaskInfo ONGOING =
            new TingwuClient.TaskInfo("ONGOING", null, null, null, null, null);

    @BeforeEach
    void setUp() throws Exception {
        meetingRepository = mock(MeetingRecordingRepository.class);
        settingService = mock(SystemSettingService.class);
        tingwu = mock(TingwuClient.class);
        resolver = mock(ExternalProviderResolver.class);
        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settingService.get(anyString(), anyString())).thenReturn("x");
        when(resolver.resolve(anyString())).thenReturn(ExternalServiceProvider.BYOK);
        when(tingwu.getTask(any(), eq("task-1"))).thenReturn(ONGOING);
    }

    private MeetingTranscriptionService service() {
        return new MeetingTranscriptionService(
                meetingRepository, mock(ProjectFileRepository.class), null, settingService,
                mock(MeetingAudioTranscoder.class), tingwu, mock(MeetingOssClient.class),
                resolver, mock(PlatformGatewayClient.class), mock(LocalAsrClient.class),
                mock(MeetingTranscriptionService.UrlFetcher.class),
                mock(MeetingTranscriptionService.BinaryUploader.class),
                MeetingTranscriptionService.DEFAULT_TRANSCODE_TIMEOUT,
                "", "", "", "", "");
    }

    /** 已提交到听悟、正停在「转写中」的会议。startedAgo=null 模拟存量行。 */
    private MeetingRecording transcribing(Long durationMs, Duration startedAgo) {
        MeetingRecording m = new MeetingRecording();
        m.setId(7L);
        m.setProjectId(1L);
        m.setTitle("会议 09-09 10:00");
        m.setStatus(MeetingRecording.STATUS_TRANSCRIBING);
        m.setCreatedBy(10001L);
        m.setDurationMs(durationMs);
        m.setTingwuTaskId("task-1");
        m.setCreatedAt(LocalDateTime.now().minusDays(3));
        m.setUpdatedAt(LocalDateTime.now());
        if (startedAgo != null) {
            m.setTranscribingStartedAt(LocalDateTime.now().minus(startedAgo));
        }
        when(meetingRepository.findById(7L)).thenReturn(Optional.of(m));
        return m;
    }

    // ==================== ① 阈值计算 ====================

    @Test
    @DisplayName("短音频取 30 分钟下限：10 分钟的录音转写 29 分钟不算卡死，31 分钟算")
    void shortAudioUsesThirtyMinuteFloor() {
        MeetingRecording ok = transcribing(Duration.ofMinutes(10).toMillis(), Duration.ofMinutes(29));
        assertEquals(MeetingRecording.STATUS_TRANSCRIBING,
                service().refreshIfNeeded(ok).getStatus(),
                "10 分钟音频 × 3 = 30 分钟 < 下限，阈值应取 30 分钟；29 分钟还没到");

        MeetingRecording stuck = transcribing(Duration.ofMinutes(10).toMillis(), Duration.ofMinutes(31));
        assertEquals(MeetingRecording.STATUS_FAILED,
                service().refreshIfNeeded(stuck).getStatus(),
                "超过 30 分钟下限应判为卡死");
    }

    @Test
    @DisplayName("长音频取音频时长 × 3：两小时的录音转写 5 小时不算卡死，6 小时零 1 分算")
    void longAudioUsesThreeTimesDuration() {
        MeetingRecording ok = transcribing(Duration.ofHours(2).toMillis(), Duration.ofHours(5));
        assertEquals(MeetingRecording.STATUS_TRANSCRIBING,
                service().refreshIfNeeded(ok).getStatus(),
                "两小时音频阈值 6 小时，5 小时远没到——按 30 分钟下限判就会在这里误杀");

        MeetingRecording stuck = transcribing(
                Duration.ofHours(2).toMillis(), Duration.ofHours(6).plusMinutes(1));
        assertEquals(MeetingRecording.STATUS_FAILED,
                service().refreshIfNeeded(stuck).getStatus(),
                "超过 6 小时应判为卡死");
    }

    // ==================== ② 超时置失败且原因正确 ====================

    @Test
    @DisplayName("卡死的会议置为 FAILED 并写下可读的失败原因（重试入口据 FAILED 出现）")
    void stuckMeetingFailsWithReadableReason() {
        MeetingRecording stuck = transcribing(Duration.ofMinutes(10).toMillis(), Duration.ofHours(2));
        MeetingRecording out = service().refreshIfNeeded(stuck);

        assertEquals(MeetingRecording.STATUS_FAILED, out.getStatus());
        assertNotNull(out.getError(), "失败必须带原因，否则界面只会给一句泛化的「转写失败」");
        assertTrue(out.getError().contains("超时"), "原因要说清是超时判定：" + out.getError());
        assertTrue(out.getError().contains("30"), "原因里要带上判定用的阈值：" + out.getError());
        // 原始录音完好这件事必须说，否则用户不敢重试
        assertTrue(out.getError().contains("录音"), "原因要交代录音本身还在：" + out.getError());
        verify(meetingRepository).save(argThat(m ->
                MeetingRecording.STATUS_FAILED.equals(m.getStatus())));
    }

    @Test
    @DisplayName("卡死判定不去问上游：已经判死就没必要再打一次听悟")
    void stuckMeetingSkipsUpstreamPoll() throws Exception {
        MeetingRecording stuck = transcribing(Duration.ofMinutes(10).toMillis(), Duration.ofHours(2));
        service().refreshIfNeeded(stuck);
        verify(tingwu, never()).getTask(any(), anyString());
    }

    // ==================== ③ 未超时的不动 ====================

    @Test
    @DisplayName("未超时的会议一个字段都不改，照常轮询上游")
    void healthyMeetingUntouched() throws Exception {
        MeetingRecording ok = transcribing(Duration.ofHours(1).toMillis(), Duration.ofMinutes(20));
        MeetingRecording out = service().refreshIfNeeded(ok);

        assertEquals(MeetingRecording.STATUS_TRANSCRIBING, out.getStatus());
        assertNull(out.getError());
        verify(tingwu).getTask(any(), eq("task-1"));
    }

    @Test
    @DisplayName("已完成/已失败的会议不受卡死判定影响")
    void terminalStatesUntouched() {
        MeetingRecording done = transcribing(Duration.ofMinutes(10).toMillis(), Duration.ofDays(30));
        done.setStatus(MeetingRecording.STATUS_TRANSCRIBED);
        MeetingRecording out = service().refreshIfNeeded(done);
        assertEquals(MeetingRecording.STATUS_TRANSCRIBED, out.getStatus());
        assertNull(out.getError());
    }

    // ==================== ④ 存量数据兜底 ====================

    @Test
    @DisplayName("存量行（transcribingStartedAt 为 null）第一次被看到时补盖当前时间，不当场判死")
    void legacyRowIsStampedNotKilled() {
        MeetingRecording legacy = transcribing(Duration.ofMinutes(10).toMillis(), null);
        // 存量行的 createdAt 是三天前、updatedAt 是「刚刚」（poll-on-read 每 10 秒刷一次）。
        // 拿 createdAt 顶会当场判死（可能误杀刚提交的健康任务），拿 updatedAt 顶则永远判不出来。
        MeetingRecording out = service().refreshIfNeeded(legacy);

        assertEquals(MeetingRecording.STATUS_TRANSCRIBING, out.getStatus(),
                "没有锚点就当场判死等于误杀");
        assertNotNull(out.getTranscribingStartedAt(), "应补盖时间戳，让下一个阈值窗口开始计时");
        assertTrue(out.getTranscribingStartedAt().isAfter(LocalDateTime.now().minusMinutes(1)),
                "补盖的应该是当前时间，不是 createdAt/updatedAt");
    }

    @Test
    @DisplayName("补盖之后再过一个阈值仍不动，就判为卡死")
    void legacyRowFailsAfterOneThreshold() {
        MeetingRecording legacy = transcribing(Duration.ofMinutes(10).toMillis(), null);
        service().refreshIfNeeded(legacy);
        // 补盖的时间戳往前推 31 分钟，模拟「补盖后又过了一个阈值」
        legacy.setTranscribingStartedAt(legacy.getTranscribingStartedAt().minusMinutes(31));
        assertEquals(MeetingRecording.STATUS_FAILED, service().refreshIfNeeded(legacy).getStatus());
    }

    // ==================== 提交时写入锚点 ====================

    @Test
    @DisplayName("进入转写中时写下 transcribingStartedAt")
    void startTranscriptionStampsAnchor() {
        MeetingRecording m = new MeetingRecording();
        m.setId(7L);
        m.setProjectId(1L);
        m.setTitle("会议 09-09 10:00");
        m.setStatus(MeetingRecording.STATUS_RECORDED);
        m.setCreatedBy(10001L);
        when(meetingRepository.findById(7L)).thenReturn(Optional.of(m));

        MeetingRecording out = service().startTranscription(7L);
        assertEquals(MeetingRecording.STATUS_TRANSCRIBING, out.getStatus());
        assertNotNull(out.getTranscribingStartedAt(), "没有这个锚点，卡死判定就无从算起");
        assertTrue(out.getTranscribingStartedAt().isAfter(LocalDateTime.now().minusMinutes(1)));
    }

    // ==================== 进度提示 ====================

    @Test
    @DisplayName("转写中：已用时 / 预计时长 / 阶段随实体一起出接口，估算标记为真")
    void progressAttachedWhileTranscribing() {
        MeetingRecording m = transcribing(Duration.ofMinutes(10).toMillis(), Duration.ofMinutes(5));
        service().attachProgress(List.of(m));

        MeetingTranscriptionProgress p = m.getProgress();
        assertNotNull(p, "转写中必须给进度，界面上「转写中」三个字撑不起等待");
        assertEquals(MeetingTranscriptionProgress.STAGE_UPSTREAM, p.stage(),
                "已有听悟任务号 = 上游正在转写");
        assertTrue(p.elapsedSec() >= 295 && p.elapsedSec() <= 305, "已用时约 5 分钟: " + p.elapsedSec());
        assertEquals(600L, p.estimatedSec(), "预计时长按音频时长估算");
        assertEquals(50, p.percent());
        assertTrue(p.estimated(), "上游给不出百分比，界面必须标注这是估算");
    }

    @Test
    @DisplayName("百分比封顶 99：只有状态机跳到 TRANSCRIBED 才算 100")
    void progressPercentCapped() {
        MeetingRecording m = transcribing(Duration.ofMinutes(10).toMillis(), Duration.ofMinutes(25));
        service().attachProgress(List.of(m));
        assertEquals(99, m.getProgress().percent());
    }

    @Test
    @DisplayName("音频时长未知（右键转写注册的文件）时只给已用时，不编预计值")
    void progressWithoutKnownDuration() {
        MeetingRecording m = transcribing(null, Duration.ofMinutes(5));
        service().attachProgress(List.of(m));

        MeetingTranscriptionProgress p = m.getProgress();
        assertNotNull(p);
        assertNull(p.estimatedSec(), "不知道音频多长就不给预计值");
        assertNull(p.percent(), "没有预计值就没有百分比");
    }

    @Test
    @DisplayName("本机档没有任务号，阶段是「本机转写」而不是「上传」")
    void progressStageForLocalTier() {
        when(resolver.resolve(anyString())).thenReturn(ExternalServiceProvider.LOCAL);
        MeetingRecording m = transcribing(Duration.ofMinutes(10).toMillis(), Duration.ofMinutes(1));
        m.setTingwuTaskId(null);
        service().attachProgress(List.of(m));
        assertEquals(MeetingTranscriptionProgress.STAGE_LOCAL, m.getProgress().stage(),
                "本机档一个字节都不出网，界面上不能出现「上传」");
    }

    @Test
    @DisplayName("云端档还没拿到任务号时阶段是「转码与上传」")
    void progressStageForCloudPreparing() {
        MeetingRecording m = transcribing(Duration.ofMinutes(10).toMillis(), Duration.ofMinutes(1));
        m.setTingwuTaskId(null);
        service().attachProgress(List.of(m));
        assertEquals(MeetingTranscriptionProgress.STAGE_PREPARING, m.getProgress().stage());
    }

    @Test
    @DisplayName("非转写中的会议不挂进度（既有字段与响应形态不受影响）")
    void noProgressOutsideTranscribing() {
        MeetingRecording m = transcribing(Duration.ofMinutes(10).toMillis(), Duration.ofMinutes(5));
        m.setStatus(MeetingRecording.STATUS_TRANSCRIBED);
        service().attachProgress(List.of(m));
        assertNull(m.getProgress());
    }
}
