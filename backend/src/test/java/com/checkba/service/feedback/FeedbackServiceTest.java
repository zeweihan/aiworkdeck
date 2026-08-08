package com.checkba.service.feedback;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.FeedbackAttachmentRepository;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.storage.ProjectStorageResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 提交侧的契约：一次 multipart 落一条反馈 + N 个附件，附件名由服务端生成，
 * 非图片/语音一律拒绝，上下文里带上运行环境与日志尾巴。
 */
class FeedbackServiceTest {

    @TempDir
    Path root;

    UserFeedbackRepository feedbackRepo;
    FeedbackAttachmentRepository attachmentRepo;
    FeedbackService service;
    List<FeedbackAttachment> savedAttachments;
    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        feedbackRepo = mock(UserFeedbackRepository.class);
        attachmentRepo = mock(FeedbackAttachmentRepository.class);
        savedAttachments = new ArrayList<>();

        AtomicLong ids = new AtomicLong(100);
        when(feedbackRepo.save(any(UserFeedback.class))).thenAnswer(inv -> {
            UserFeedback fb = inv.getArgument(0);
            if (fb.getId() == null) fb.setId(42L);
            return fb;
        });
        when(attachmentRepo.save(any(FeedbackAttachment.class))).thenAnswer(inv -> {
            FeedbackAttachment a = inv.getArgument(0);
            a.setId(ids.incrementAndGet());
            savedAttachments.add(a);
            return a;
        });
        when(attachmentRepo.findByFeedbackIdOrderByIdAsc(any())).thenAnswer(inv -> savedAttachments);

        ProjectStorageResolver resolver = mock(ProjectStorageResolver.class);
        when(resolver.globalRoot()).thenReturn(root);

        service = new FeedbackService(feedbackRepo, attachmentRepo, resolver,
                new FeedbackContextCollector("9.9.9-test", ""),
                new VoiceTranscriptionService(false, "", "", "whisper-1", null));
    }

    private static MockMultipartFile png(String name) {
        return new MockMultipartFile("files", name, "image/png", new byte[]{1, 2, 3, 4});
    }

    private static MockMultipartFile audio(String name) {
        return new MockMultipartFile("files", name, "audio/webm", new byte[]{9, 8, 7});
    }

    @Test
    void submitStoresRowAndAttachments() throws Exception {
        UserFeedback fb = service.submit(7L,
                new FeedbackService.SubmitRequest("BUG", "点保存没反应", 3L,
                        "pages/project-overview/project-overview", Map.of("page", "x")),
                List.of(png("a.png"), audio("v.webm")));

        assertEquals(42L, fb.getId());
        assertEquals(UserFeedback.STATUS_NEW, fb.getStatus());
        assertEquals("9.9.9-test", fb.getAppVersion());
        assertEquals(0, fb.getAttempts());
        assertEquals("点保存没反应", fb.getText());

        assertEquals(2, savedAttachments.size());
        assertEquals(FeedbackAttachment.TYPE_IMAGE, savedAttachments.get(0).getType());
        assertEquals(FeedbackAttachment.TYPE_AUDIO, savedAttachments.get(1).getType());
        // 落盘名由服务端生成，客户端文件名只留作展示
        assertEquals("img-1.png", savedAttachments.get(0).getStoredName());
        assertEquals("voice-2.webm", savedAttachments.get(1).getStoredName());
        assertEquals("a.png", savedAttachments.get(0).getOriginalName());
        assertTrue(Files.exists(root.resolve("feedback").resolve("42").resolve("img-1.png")));
        assertTrue(Files.exists(root.resolve("feedback").resolve("42").resolve("voice-2.webm")));

        Map<?, ?> ctx = mapper.readValue(fb.getContextJson(), Map.class);
        assertTrue(ctx.containsKey("runtime"));
        assertTrue(ctx.containsKey("client"));
        assertEquals("9.9.9-test", ((Map<?, ?>) ctx.get("runtime")).get("appVersion"));
    }

    @Test
    void clientFilenameNeverEscapesTheFeedbackDirectory() throws Exception {
        service.submit(7L, new FeedbackService.SubmitRequest("BUG", "x", null, null, null),
                List.of(new MockMultipartFile("files", "../../evil.png", "image/png", new byte[]{1})));

        assertEquals("img-1.png", savedAttachments.get(0).getStoredName());
        assertFalse(savedAttachments.get(0).getOriginalName().contains("/"));
        assertTrue(Files.exists(root.resolve("feedback").resolve("42").resolve("img-1.png")));
    }

    @Test
    void emptySubmissionIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                service.submit(7L, new FeedbackService.SubmitRequest("BUG", "   ", null, null, null), List.of()));
    }

    @Test
    void nonMediaAttachmentIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                service.submit(7L, new FeedbackService.SubmitRequest("BUG", "看这个", null, null, null),
                        List.of(new MockMultipartFile("files", "a.exe", "application/octet-stream", new byte[]{1}))));
    }

    @Test
    void tooManyImagesRejected() {
        List<MockMultipartFile> files = new ArrayList<>();
        for (int i = 0; i <= FeedbackService.MAX_IMAGES; i++) files.add(png("a" + i + ".png"));
        assertThrows(IllegalArgumentException.class, () ->
                service.submit(7L, new FeedbackService.SubmitRequest("BUG", "多图", null, null, null),
                        List.copyOf(files)));
    }

    @Test
    void secondAudioRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                service.submit(7L, new FeedbackService.SubmitRequest("BUG", "两段语音", null, null, null),
                        List.of(audio("a.webm"), audio("b.webm"))));
    }

    @Test
    void transcriptionFillsVoiceTranscriptWhenConfigured() throws Exception {
        VoiceTranscriptionService asr = new VoiceTranscriptionService(true, "http://stub/v1", "k", "whisper-1",
                (url, key, ct, body) -> {
                    assertTrue(url.endsWith("/audio/transcriptions"));
                    assertTrue(ct.startsWith("multipart/form-data; boundary="));
                    return "{\"text\":\"保存按钮点了没反应\"}";
                });
        ProjectStorageResolver resolver = mock(ProjectStorageResolver.class);
        when(resolver.globalRoot()).thenReturn(root);
        FeedbackService withAsr = new FeedbackService(feedbackRepo, attachmentRepo, resolver,
                new FeedbackContextCollector("9.9.9-test", ""), asr);

        UserFeedback fb = withAsr.submit(7L,
                new FeedbackService.SubmitRequest("BUG", "", null, null, null), List.of(audio("v.webm")));

        assertEquals("保存按钮点了没反应", fb.getVoiceTranscript());
    }

    @Test
    void transcriptionFailureNeverBlocksSubmission() throws Exception {
        VoiceTranscriptionService asr = new VoiceTranscriptionService(true, "http://stub/v1", "k", "whisper-1",
                (url, key, ct, body) -> {
                    throw new IllegalStateException("转写服务 500");
                });
        ProjectStorageResolver resolver = mock(ProjectStorageResolver.class);
        when(resolver.globalRoot()).thenReturn(root);
        FeedbackService withAsr = new FeedbackService(feedbackRepo, attachmentRepo, resolver,
                new FeedbackContextCollector("9.9.9-test", ""), asr);

        UserFeedback fb = withAsr.submit(7L,
                new FeedbackService.SubmitRequest("BUG", "有声音", null, null, null), List.of(audio("v.webm")));

        assertNull(fb.getVoiceTranscript());
        assertEquals(1, savedAttachments.size()); // 语音仍然存下来了
    }

    @Test
    void backendLogTailIsAttachedWhenLogFileExists() throws Exception {
        Path log = root.resolve("app.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) sb.append("line ").append(i).append('\n');
        Files.writeString(log, sb.toString());

        ProjectStorageResolver resolver = mock(ProjectStorageResolver.class);
        when(resolver.globalRoot()).thenReturn(root);
        FeedbackService withLog = new FeedbackService(feedbackRepo, attachmentRepo, resolver,
                new FeedbackContextCollector("9.9.9-test", log.toString()),
                new VoiceTranscriptionService(false, "", "", "", null));

        UserFeedback fb = withLog.submit(7L,
                new FeedbackService.SubmitRequest("BUG", "崩了", null, null, null), List.of());

        Map<?, ?> ctx = mapper.readValue(fb.getContextJson(), Map.class);
        String tail = String.valueOf(ctx.get("backendLogTail"));
        assertFalse(tail.isEmpty());
        assertTrue(tail.endsWith("line 2999\n"), "取的是尾部");
        assertTrue(tail.length() <= 16 * 1024, "尾巴要截断，别把整份日志塞进库");
        assertFalse(tail.startsWith("line 0\n"), "截断处的半行残句要丢掉");
    }
}
