package com.checkba.service.feedback;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.FeedbackAttachmentRepository;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.telemetry.InstallIdentityService;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 云端收件箱两侧：收件（幂等 + 配额）与本机上传（补传 + 不误标）。
 * 这条链断了的表现是「云端优化者天天跑、天天零条」，很难从现象反推，所以断言写细。
 */
class FeedbackCloudInboxTest {

    @TempDir
    Path root;

    UserFeedbackRepository repo;
    FeedbackAttachmentRepository attachmentRepo;
    FeedbackService service;
    List<UserFeedback> saved;
    List<FeedbackAttachment> savedAttachments;

    @BeforeEach
    void setup() {
        repo = mock(UserFeedbackRepository.class);
        attachmentRepo = mock(FeedbackAttachmentRepository.class);
        saved = new ArrayList<>();
        savedAttachments = new ArrayList<>();
        AtomicLong ids = new AtomicLong(0);

        when(repo.save(any(UserFeedback.class))).thenAnswer(inv -> {
            UserFeedback fb = inv.getArgument(0);
            if (fb.getId() == null) {
                fb.setId(ids.incrementAndGet());
                saved.add(fb);
            }
            return fb;
        });
        when(repo.findByInstallIdAndClientRef(anyString(), anyString())).thenAnswer(inv ->
                saved.stream()
                        .filter(f -> inv.getArgument(0).equals(f.getInstallId())
                                && inv.getArgument(1).equals(f.getClientRef()))
                        .findFirst());
        when(attachmentRepo.save(any(FeedbackAttachment.class))).thenAnswer(inv -> {
            FeedbackAttachment a = inv.getArgument(0);
            a.setId((long) (savedAttachments.size() + 1));
            savedAttachments.add(a);
            return a;
        });
        when(attachmentRepo.findByFeedbackIdOrderByIdAsc(any())).thenAnswer(inv ->
                savedAttachments.stream().filter(a -> a.getFeedbackId().equals(inv.getArgument(0))).toList());

        ProjectStorageResolver resolver = mock(ProjectStorageResolver.class);
        when(resolver.globalRoot()).thenReturn(root);
        service = new FeedbackService(repo, attachmentRepo, resolver,
                new FeedbackContextCollector("cloud-test", ""),
                new VoiceTranscriptionService(false, "", "", "", null));
    }

    private static FeedbackService.IngestRequest req(String text) {
        return new FeedbackService.IngestRequest("BUG", text, null,
                "pages/project-overview/project-overview", "0.13.0", "Windows 11", "{\"runtime\":{}}");
    }

    // ---------- 收件 ----------

    @Test
    void ingestKeepsUploaderVersionAndMarksSourceCloud() throws Exception {
        UserFeedback fb = service.ingest("install-a", "17", req("导出崩了"),
                List.of(new MockMultipartFile("files", "s.png", "image/png", new byte[]{1, 2, 3})));

        assertEquals(UserFeedback.SOURCE_CLOUD, fb.getSource());
        assertNull(fb.getUserId(), "云端收上来的没有本地用户");
        assertEquals("install-a", fb.getInstallId());
        assertEquals("17", fb.getClientRef());
        // 版本/平台照抄上传方——云端自己的版本号对排障没有意义
        assertEquals("0.13.0", fb.getAppVersion());
        assertEquals("Windows 11", fb.getPlatform());
        assertEquals(UserFeedback.STATUS_NEW, fb.getStatus());
        assertEquals(1, savedAttachments.size());
        assertTrue(Files.exists(root.resolve("feedback").resolve(String.valueOf(fb.getId())).resolve("img-1.png")));
    }

    @Test
    void repeatedUploadOfTheSameRowIsIdempotent() throws Exception {
        UserFeedback first = service.ingest("install-a", "17", req("导出崩了"), List.of());
        UserFeedback again = service.ingest("install-a", "17", req("导出崩了"), List.of());

        assertEquals(first.getId(), again.getId());
        assertEquals(1, saved.size(), "重传不得在云端变成两条");
    }

    @Test
    void sameClientRefFromDifferentInstallsAreDifferentFeedback() throws Exception {
        service.ingest("install-a", "1", req("A 的问题"), List.of());
        service.ingest("install-b", "1", req("B 的问题"), List.of());
        assertEquals(2, saved.size(), "行号只在各自安装内唯一，跨安装不能撞车");
    }

    // ---------- 准入闸 ----------

    @Test
    void guardRejectsMissingInstallIdAndOverQuota() {
        UserFeedbackRepository r = mock(UserFeedbackRepository.class);
        FeedbackIngestGuard guard = new FeedbackIngestGuard(r);
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "perInstallDaily", 2);
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "globalDaily", 100);
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "maxAttachments", 2);
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "maxAttachmentBytes", 10L);

        assertThrows(IllegalArgumentException.class, () -> guard.check("", List.of()));
        assertThrows(IllegalArgumentException.class, () -> guard.check("i", List.of(
                new MockMultipartFile("files", "a.png", "image/png", new byte[]{1}),
                new MockMultipartFile("files", "b.png", "image/png", new byte[]{1}),
                new MockMultipartFile("files", "c.png", "image/png", new byte[]{1}))));
        assertThrows(IllegalArgumentException.class, () -> guard.check("i", List.of(
                new MockMultipartFile("files", "big.png", "image/png", new byte[100]))));

        when(r.countByInstallIdAndCreatedAtAfter(anyString(), any(LocalDateTime.class))).thenReturn(2L);
        assertThrows(IllegalArgumentException.class, () -> guard.check("i", List.of()));
    }

    @Test
    void guardIsClosedUnlessExplicitlyEnabled() {
        FeedbackIngestGuard guard = new FeedbackIngestGuard(mock(UserFeedbackRepository.class));
        // 默认 enabled=false：桌面版每台机器都跑着同一份代码，收件箱绝不能默认开着
        assertFalse(guard.isEnabled());
        assertThrows(IllegalStateException.class, () -> guard.check("i", List.of()));
    }

    // ---------- 本机上传 ----------

    private FeedbackUploadService uploader(FeedbackUploadService.Transport t, boolean enabled, String url) {
        InstallIdentityService identity = mock(InstallIdentityService.class);
        when(identity.installId()).thenReturn("install-me");
        return new FeedbackUploadService(repo, service, identity, enabled, url, t);
    }

    private UserFeedback localRow(long id, String text) {
        UserFeedback fb = new UserFeedback();
        fb.setId(id);
        fb.setSource(UserFeedback.SOURCE_LOCAL);
        fb.setKind(UserFeedback.KIND_BUG);
        fb.setText(text);
        fb.setAppVersion("0.13.0");
        fb.setUploaded(false);
        return fb;
    }

    @Test
    void uploadSendsPayloadAndMarksRowUploaded() {
        UserFeedback row = localRow(9L, "点保存没反应");
        when(repo.findByUploadedFalseAndSourceOrderByIdAsc(eqLocal(), any(Pageable.class)))
                .thenReturn(List.of(row));
        StringBuilder body = new StringBuilder();
        FeedbackUploadService up = uploader((url, ct, bytes) -> {
            assertEquals("https://cloud/api/feedback/ingest", url);
            assertTrue(ct.startsWith("multipart/form-data; boundary="));
            body.append(new String(bytes, StandardCharsets.UTF_8));
            return 200;
        }, true, "https://cloud/api/feedback/ingest");

        up.flush();

        assertTrue(row.isUploaded());
        assertNotNull(row.getUploadedAt());
        assertTrue(body.toString().contains("install-me"));
        assertTrue(body.toString().contains("\"clientRef\":\"9\""), "幂等键必须带上");
        assertTrue(body.toString().contains("点保存没反应"));
    }

    @Test
    void rateLimitedUploadIsRetriedNotSwallowed() {
        UserFeedback row = localRow(9L, "x");
        when(repo.findByUploadedFalseAndSourceOrderByIdAsc(eqLocal(), any(Pageable.class)))
                .thenReturn(List.of(row));
        uploader((url, ct, bytes) -> 429, true, "https://cloud/api/feedback/ingest").flush();
        // 429 标成已上传等于这条永远消失
        assertFalse(row.isUploaded());
    }

    @Test
    void serverErrorLeavesRowForNextRound() {
        UserFeedback row = localRow(9L, "x");
        when(repo.findByUploadedFalseAndSourceOrderByIdAsc(eqLocal(), any(Pageable.class)))
                .thenReturn(List.of(row));
        uploader((url, ct, bytes) -> 500, true, "https://cloud/api/feedback/ingest").flush();
        assertFalse(row.isUploaded());
    }

    @Test
    void networkFailureNeverThrows() {
        UserFeedback row = localRow(9L, "x");
        when(repo.findByUploadedFalseAndSourceOrderByIdAsc(eqLocal(), any(Pageable.class)))
                .thenReturn(List.of(row));
        FeedbackUploadService up = uploader((url, ct, bytes) -> {
            throw new java.net.ConnectException("断网");
        }, true, "https://cloud/api/feedback/ingest");
        assertDoesNotThrow(up::flush);
        assertFalse(row.isUploaded());
    }

    @Test
    void uploadIsInertWithoutUrl() {
        FeedbackUploadService up = uploader((url, ct, bytes) -> {
            fail("没配地址不该发请求");
            return 200;
        }, true, "");
        assertFalse(up.isConfigured());
        assertDoesNotThrow(up::flush);
        verify(repo, never()).findByUploadedFalseAndSourceOrderByIdAsc(anyString(), any(Pageable.class));
    }

    @Test
    void disabledUploadStaysLocal() {
        FeedbackUploadService up = uploader((url, ct, bytes) -> {
            fail("关掉了还发就是漏数据");
            return 200;
        }, false, "https://cloud/api/feedback/ingest");
        assertFalse(up.isConfigured());
        assertDoesNotThrow(up::flush);
    }

    private static String eqLocal() {
        return org.mockito.ArgumentMatchers.eq(UserFeedback.SOURCE_LOCAL);
    }
}
