package com.checkba.service.ai.tools;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.repository.MeetingRecordingRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.AppLanguageService;
import com.checkba.service.LangText;
import com.checkba.service.ProjectFileService;
import com.checkba.service.meeting.MeetingRecordingService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * meeting_* 工具返回文本的语言：这些串是喂给模型的脚手架（小标题、状态词、空态指引），
 * 英文会话里漏中文会让模型跨语言理解自己的输入格式，也会漏进过程卡。
 * 转写内容本身是用户数据，必须原样保留——两条一起锁。
 */
class MeetingToolsLanguageTest {

    private MeetingRecordingRepository meetingRepository;
    private MeetingTools tools;

    private static final String TRANSCRIPT_JSON = """
            [{"speaker":"1","start":61000,"end":62000,"text":"我方认为价款应分期支付"}]
            """;

    @BeforeEach
    void setUp() {
        meetingRepository = mock(MeetingRecordingRepository.class);
        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MeetingRecordingService service = new MeetingRecordingService(
                meetingRepository, mock(ProjectFileRepository.class),
                mock(ProjectFileService.class), mock(StorageServiceFactory.class));
        tools = new MeetingTools(service);
    }

    @AfterEach
    void resetLanguage() {
        LangText.reset();
    }

    private void switchToEnglish() {
        AppLanguageService en = mock(AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        LangText.register(en);
    }

    private MeetingRecording transcribed() {
        MeetingRecording m = new MeetingRecording();
        m.setId(9L);
        m.setProjectId(1L);
        m.setTitle("尽调访谈");
        m.setStatus(MeetingRecording.STATUS_TRANSCRIBED);
        m.setDurationMs(3600_000L);
        m.setTranscriptJson(TRANSCRIPT_JSON);
        when(meetingRepository.findById(9L)).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    @DisplayName("英文下转写稿工具的脚手架是英文，转写内容与会议标题原样保留")
    void transcriptToolScaffoldingIsEnglish() {
        transcribed();
        switchToEnglish();
        String out = tools.meeting_get_transcript(1L, 9L);
        assertTrue(out.startsWith("Meeting: 尽调访谈"), "标题是用户数据，不翻：" + out);
        assertTrue(out.contains("=== Transcript ([time] speaker: text) ==="), out);
        assertTrue(out.contains("Speaker 1: 我方认为价款应分期支付"), "转写内容原样：" + out);
        assertFalse(out.contains("转写稿"), "脚手架不该残留中文：" + out);
        assertFalse(out.contains("时长"), "脚手架不该残留中文：" + out);
    }

    @Test
    @DisplayName("英文下未完成转写与空列表的指引也是英文")
    void guidanceTextIsEnglish() {
        MeetingRecording m = transcribed();
        m.setStatus(MeetingRecording.STATUS_RECORDED);
        switchToEnglish();

        String notReady = tools.meeting_get_transcript(1L, 9L);
        assertTrue(notReady.contains("recorded (not transcribed)"), notReady);
        assertTrue(notReady.contains("Meeting Recording panel"), notReady);
        assertFalse(notReady.contains("请用户"), notReady);

        when(meetingRepository.findByProjectIdOrderByCreatedAtDesc(2L)).thenReturn(List.of());
        String empty = tools.meeting_list_recordings(2L);
        assertTrue(empty.startsWith("This project has no meeting recordings yet"), empty);
    }

    @Test
    @DisplayName("中文（默认/未登记语言）下这些串逐字不变")
    void chineseUnchanged() {
        transcribed();
        String out = tools.meeting_get_transcript(1L, 9L);
        assertTrue(out.startsWith("会议：尽调访谈"), out);
        assertTrue(out.contains("=== 转写稿（[时间] 说话人：内容）==="), out);
        assertTrue(out.contains("说话人1：我方认为价款应分期支付"), out);
    }
}
