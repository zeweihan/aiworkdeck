package com.checkba.service.meeting;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.repository.MeetingRecordingRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.AppLanguageService;
import com.checkba.service.LangText;
import com.checkba.service.ProjectFileService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 会议服务的纯逻辑面：说话人改名映射、转写稿渲染、kick-off prompt 契约（含英文侧）。 */
class MeetingRecordingServiceTest {

    private MeetingRecordingRepository meetingRepository;
    private MeetingRecordingService service;

    private static final String TRANSCRIPT_JSON = """
            [{"speaker":"1","start":61000,"end":62000,"text":"我方认为价款应分期支付"},
             {"speaker":"2","start":63000,"end":65000,"text":"我们接受，但要求首期不低于五成"}]
            """;

    @BeforeEach
    void setUp() {
        meetingRepository = mock(MeetingRecordingRepository.class);
        when(meetingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new MeetingRecordingService(
                meetingRepository, mock(ProjectFileRepository.class),
                mock(ProjectFileService.class), mock(StorageServiceFactory.class));
    }

    /** LangText 是静态指针，登记过就必须清掉，否则污染后面的测试类。 */
    @AfterEach
    void resetLanguage() {
        LangText.reset();
    }

    private void switchToEnglish() {
        AppLanguageService en = mock(AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        LangText.register(en);
    }

    private MeetingRecording transcribedMeeting() {
        MeetingRecording m = new MeetingRecording();
        m.setId(9L);
        m.setProjectId(1L);
        m.setTitle("尽调访谈");
        m.setStatus(MeetingRecording.STATUS_TRANSCRIBED);
        m.setDurationMs(3600_000L);
        m.setTranscriptJson(TRANSCRIPT_JSON);
        m.setCreatedBy(10001L);
        return m;
    }

    @Test
    @DisplayName("转写稿渲染：时间戳 + 说话人默认名「说话人N」")
    void renderWithDefaultSpeakerNames() {
        String text = service.renderTranscriptText(transcribedMeeting());
        assertTrue(text.contains("[01:01] 说话人1：我方认为价款应分期支付"));
        assertTrue(text.contains("[01:03] 说话人2：我们接受，但要求首期不低于五成"));
    }

    @Test
    @DisplayName("说话人改名后渲染与纪要用改后的名字")
    void renderWithRenamedSpeakers() {
        MeetingRecording m = transcribedMeeting();
        m.setSpeakerNames("{\"1\":\"张律师\",\"2\":\"对方代理人\"}");
        String text = service.renderTranscriptText(m);
        assertTrue(text.contains("张律师：我方认为价款应分期支付"));
        assertTrue(text.contains("对方代理人：我们接受"));
        assertFalse(text.contains("说话人1"));
    }

    @Test
    @DisplayName("kick-off prompt 以 skill 触发词「会议纪要」开头并带 meetingId 与工具指引")
    void minutesPromptCarriesTriggerWord() {
        String prompt = service.buildMinutesKickoffPrompt(transcribedMeeting());
        assertTrue(prompt.startsWith("会议纪要"),
                "触发词必须在 prompt 文本里（pinnedSkillId 只裁工具不注入 prompt）");
        assertTrue(prompt.contains("meetingId=9"));
        assertTrue(prompt.contains("meeting_get_transcript"));
        assertTrue(prompt.contains("说话人1、说话人2"));
    }

    @Test
    @DisplayName("finish 幂等：durationMs 为空（崩溃恢复补刀）也能收口状态")
    void finishTolerantOfNullDuration() {
        MeetingRecording m = new MeetingRecording();
        m.setId(3L);
        m.setStatus(MeetingRecording.STATUS_RECORDING);
        when(meetingRepository.findById(3L)).thenReturn(Optional.of(m));

        MeetingRecording out = service.finish(3L, null);
        assertEquals(MeetingRecording.STATUS_RECORDED, out.getStatus());
        assertNull(out.getDurationMs());

        // 已 RECORDED 再 finish 不回退
        MeetingRecording again = service.finish(3L, 5000L);
        assertEquals(MeetingRecording.STATUS_RECORDED, again.getStatus());
        assertEquals(5000L, again.getDurationMs());
    }

    @Test
    @DisplayName("说话人改名落库与读回")
    void speakerNamesRoundTrip() {
        MeetingRecording m = transcribedMeeting();
        when(meetingRepository.findById(9L)).thenReturn(Optional.of(m));
        service.updateSpeakerNames(9L, Map.of("1", "张律师"));
        Map<String, String> names = service.speakerDisplayNames(m);
        assertEquals("张律师", names.get("1"));
        assertEquals("张律师", service.displaySpeaker(names, "1"));
        assertEquals("说话人2", service.displaySpeaker(names, "2"));
    }

    // ==================== 英文应用语言 ====================
    // 这几条是「英文版会议录音真的能用」的守卫。默认名与文件夹名不落库、按当前语言取，
    // 所以只能在这里锁；漏了就会退回「面板双语了、导出与 AI 侧还是中文」。

    @Test
    @DisplayName("英文下转写稿渲染用 Speaker N 与半角冒号")
    void renderInEnglish() {
        switchToEnglish();
        String text = service.renderTranscriptText(transcribedMeeting());
        assertTrue(text.contains("[01:01] Speaker 1: 我方认为价款应分期支付"),
                "说话人默认名与分隔符要跟界面语言走，转写内容本身是用户数据不动：" + text);
        assertFalse(text.contains("说话人"));
        assertFalse(text.contains("："), "英文行里不该出现全角冒号");
    }

    @Test
    @DisplayName("英文下改过名的说话人仍用用户改的名字（改名是用户数据，不受语言影响）")
    void renamedSpeakersSurviveEnglish() {
        switchToEnglish();
        MeetingRecording m = transcribedMeeting();
        m.setSpeakerNames("{\"1\":\"张律师\"}");
        Map<String, String> names = service.speakerDisplayNames(m);
        assertEquals("张律师", service.displaySpeaker(names, "1"));
        assertEquals("Speaker 2", service.displaySpeaker(names, "2"));
    }

    /**
     * 最要紧的一条：kick-off prompt 的开头必须落在 skill.yml 的 triggers_en 里。
     * 不成立的话英文用户点「生成会议纪要」命不中 skill——没有 prompt.en.md、
     * 没有 meeting_get_transcript / write_docx 白名单，按钮形同虚设。
     * triggers_en 第一条与这里的开头是同一个契约，改一处要改两处（BuiltinSkillsTest 从 yml 侧锁）。
     */
    @Test
    @DisplayName("英文下 kick-off prompt 以英文触发词 meeting minutes 开头")
    void minutesPromptCarriesEnglishTriggerWord() {
        switchToEnglish();
        String prompt = service.buildMinutesKickoffPrompt(transcribedMeeting());
        assertTrue(prompt.toLowerCase().startsWith("meeting minutes"),
                "英文触发词必须在 prompt 开头，否则 SkillRouter 命不中：" + prompt);
        assertTrue(prompt.contains("meetingId=9"));
        assertTrue(prompt.contains("meeting_get_transcript"));
        assertTrue(prompt.contains("Speaker 1, Speaker 2"));
        assertFalse(prompt.contains("请先"), "英文 prompt 不该混中文句子");
    }

    @Test
    @DisplayName("录音文件夹名按语言二选一，两个名字都是正名")
    void folderNameFollowsLanguage() {
        assertEquals("会议录音", MeetingRecordingService.folderName());
        switchToEnglish();
        assertEquals("Meeting Recordings", MeetingRecordingService.folderName());
    }
}
