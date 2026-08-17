package com.checkba.service.meeting;

import com.checkba.model.entity.MeetingRecording;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.MeetingRecordingRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.AppLanguageService;
import com.checkba.service.LangText;
import com.checkba.service.ProjectFileService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 录音文件夹的跨语言复用。
 *
 * <p>「会议录音」与「Meeting Recordings」是同一个目录的两个正名：建档按界面语言取一个，
 * <b>查找必须两个都认</b>。少了跨语言那一步，中文安装切到英文后会另建一个同用途目录，
 * 旧录音留在旧目录里像丢了——这类事故不会报错、只会让用户以为文件没了，所以在这里锁死。
 */
class MeetingFolderLanguageTest {

    private ProjectFileRepository projectFileRepository;
    private ProjectFileService projectFileService;
    private MeetingRecordingRepository meetingRepository;
    private MeetingRecordingService service;

    private static final String TRANSCRIPT_JSON = """
            [{"speaker":"1","start":61000,"end":62000,"text":"我方认为价款应分期支付"}]
            """;

    @BeforeEach
    void setUp() throws Exception {
        meetingRepository = mock(MeetingRecordingRepository.class);
        projectFileRepository = mock(ProjectFileRepository.class);
        projectFileService = mock(ProjectFileService.class);
        StorageServiceFactory factory = mock(StorageServiceFactory.class);
        StorageService storage = mock(StorageService.class);
        when(factory.getStorageService()).thenReturn(storage);
        when(storage.save(anyString(), any())).thenReturn("stored");

        // 两个名字默认都查不到；具体用例再按需给其中一个塞值
        when(projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(
                anyLong(), isNull(), anyString())).thenReturn(Optional.empty());
        when(projectFileRepository.existsByProjectIdAndParentIdAndNameAndIdNot(
                anyLong(), any(), anyString(), anyLong())).thenReturn(false);
        when(projectFileService.createFolder(anyLong(), isNull(), anyString(), anyLong()))
                .thenAnswer(inv -> folder(inv.getArgument(2)));
        when(projectFileService.createFile(anyLong(), any(), anyString(), anyString(),
                anyLong(), any(), any(), anyLong()))
                .thenAnswer(inv -> file(inv.getArgument(2)));

        service = new MeetingRecordingService(
                meetingRepository, projectFileRepository, projectFileService, factory);
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

    private static ProjectFile folder(String name) {
        ProjectFile f = new ProjectFile();
        f.setId(100L);
        f.setName(name);
        f.setIsFolder(true);
        return f;
    }

    private static ProjectFile file(String name) {
        ProjectFile f = new ProjectFile();
        f.setId(200L);
        f.setName(name);
        f.setIsFolder(false);
        f.setFilePath("p/" + name);
        return f;
    }

    private void givenExistingFolder(String name) {
        when(projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(
                eq(1L), isNull(), eq(name))).thenReturn(Optional.of(folder(name)));
    }

    private MeetingRecording transcribedMeeting() {
        MeetingRecording m = new MeetingRecording();
        m.setId(9L);
        m.setProjectId(1L);
        m.setTitle("尽调访谈");
        m.setStatus(MeetingRecording.STATUS_TRANSCRIBED);
        m.setTranscriptJson(TRANSCRIPT_JSON);
        when(meetingRepository.findById(9L)).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    @DisplayName("英文界面遇到存量中文文件夹：沿用它，绝不另建一个")
    void englishReusesExistingChineseFolder() {
        transcribedMeeting();
        givenExistingFolder(MeetingRecordingService.FOLDER_NAME);
        switchToEnglish();

        MeetingRecordingService.ExportResult res = service.exportTranscript(9L, 10001L);

        assertEquals(MeetingRecordingService.FOLDER_NAME, res.folderName(),
                "回给界面的必须是实际目录名，否则「见 X 文件夹」会指错地方");
        verify(projectFileService, never()).createFolder(anyLong(), isNull(), anyString(), anyLong());
    }

    @Test
    @DisplayName("中文界面遇到英文安装留下的文件夹：同样沿用")
    void chineseReusesExistingEnglishFolder() {
        transcribedMeeting();
        givenExistingFolder(MeetingRecordingService.FOLDER_NAME_EN);

        MeetingRecordingService.ExportResult res = service.exportTranscript(9L, 10001L);

        assertEquals(MeetingRecordingService.FOLDER_NAME_EN, res.folderName());
        verify(projectFileService, never()).createFolder(anyLong(), isNull(), anyString(), anyLong());
    }

    @Test
    @DisplayName("两个名字都没有：按当前语言新建，导出文件名也跟着语言")
    void createsFolderInCurrentLanguage() {
        transcribedMeeting();
        switchToEnglish();

        MeetingRecordingService.ExportResult res = service.exportTranscript(9L, 10001L);

        assertEquals(MeetingRecordingService.FOLDER_NAME_EN, res.folderName());
        verify(projectFileService).createFolder(eq(1L), isNull(),
                eq(MeetingRecordingService.FOLDER_NAME_EN), eq(10001L));
        assertTrue(res.file().getName().startsWith("Transcript_"),
                "英文下导出文件名不该是「转写稿_」：" + res.file().getName());
    }

    @Test
    @DisplayName("中文（默认）下新建与命名逐字不变")
    void chineseDefaultUnchanged() {
        transcribedMeeting();

        MeetingRecordingService.ExportResult res = service.exportTranscript(9L, 10001L);

        assertEquals(MeetingRecordingService.FOLDER_NAME, res.folderName());
        assertTrue(res.file().getName().startsWith("转写稿_"), res.file().getName());
    }
}
