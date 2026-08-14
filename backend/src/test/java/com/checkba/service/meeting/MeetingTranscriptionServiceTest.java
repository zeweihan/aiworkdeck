package com.checkba.service.meeting;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.repository.MeetingRecordingRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

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
        return new MeetingTranscriptionService(
                meetingRepository, mock(ProjectFileRepository.class), null, settingService,
                mock(MeetingAudioTranscoder.class), tingwu, oss, fetcher,
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
    @DisplayName("COMPLETED 但转写结果为空 → FAILED（不能让空稿冒充成功）")
    void refreshEmptyTranscriptFails() throws Exception {
        MeetingRecording m = meeting(MeetingRecording.STATUS_TRANSCRIBING);
        m.setTingwuTaskId("task-1");
        when(tingwu.getTask(any(), eq("task-1"))).thenReturn(new TingwuClient.TaskInfo(
                "COMPLETED", null, "http://r/trans", null, null, null));
        when(fetcher.fetch("http://r/trans")).thenReturn("{\"Transcription\":{\"Paragraphs\":[]}}");

        MeetingRecording out = service(true).refreshIfNeeded(m);

        assertEquals(MeetingRecording.STATUS_FAILED, out.getStatus());
    }
}
