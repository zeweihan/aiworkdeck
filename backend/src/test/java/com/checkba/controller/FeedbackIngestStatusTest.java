package com.checkba.controller;

import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.feedback.FeedbackIngestGuard;
import com.checkba.service.feedback.FeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/feedback/ingest/status}：本机按 installId 自证身份查回执。
 * 鉴权就是 installId 本身——重点断言「查不到别人的」与「只回状态字段」。
 */
class FeedbackIngestStatusTest {

    UserFeedbackRepository repo;
    FeedbackIngestController controller;

    @BeforeEach
    void setup() {
        repo = mock(UserFeedbackRepository.class);
        FeedbackIngestGuard guard = new FeedbackIngestGuard(repo);
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "perInstallDaily", 20);
        ReflectionTestUtils.setField(guard, "globalDaily", 2000);
        ReflectionTestUtils.setField(guard, "maxAttachments", 4);
        ReflectionTestUtils.setField(guard, "maxAttachmentBytes", 5_242_880L);
        controller = new FeedbackIngestController(mock(FeedbackService.class), guard, repo);
    }

    private static UserFeedback cloudRow(String installId, String clientRef, String status, String prUrl) {
        UserFeedback fb = new UserFeedback();
        fb.setInstallId(installId);
        fb.setClientRef(clientRef);
        fb.setSource(UserFeedback.SOURCE_CLOUD);
        fb.setStatus(status);
        fb.setTriageVerdict("BUG");
        fb.setPrUrl(prUrl);
        fb.setHandledAt(LocalDateTime.of(2026, 8, 9, 10, 0));
        fb.setText("反馈正文绝不能出现在回执里");
        return fb;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<?> resp) {
        return (Map<String, Object>) ((Map<String, Object>) resp.getBody()).get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ResponseEntity<?> resp) {
        return (List<Map<String, Object>>) data(resp).get("items");
    }

    @Test
    void returnsOnlyStatusFieldsForMatchingInstall() {
        UserFeedback fb = cloudRow("install-a", "9", UserFeedback.STATUS_PR_OPENED, "https://github.com/a/b/pull/1");
        when(repo.findByInstallIdAndSourceAndClientRefIn(eq("install-a"), eq(UserFeedback.SOURCE_CLOUD), anyList()))
                .thenReturn(List.of(fb));

        ResponseEntity<?> resp = controller.ingestStatus(Map.of("installId", "install-a", "clientRefs", List.of("9")));

        assertEquals(200, resp.getStatusCode().value());
        List<Map<String, Object>> items = items(resp);
        assertEquals(1, items.size());
        Map<String, Object> item = items.get(0);
        assertEquals("9", item.get("clientRef"));
        assertEquals(UserFeedback.STATUS_PR_OPENED, item.get("status"));
        assertEquals("BUG", item.get("triageVerdict"));
        assertEquals("https://github.com/a/b/pull/1", item.get("prUrl"));
        assertNotNull(item.get("handledAt"));
        // 只回状态字段：正文/附件/上下文一律不出现，最小化返回面
        assertEquals(Set.of("clientRef", "status", "triageVerdict", "prUrl", "handledAt"), item.keySet());
    }

    @Test
    void differentInstallCannotSeeAnothersClientRef() {
        // 仓库层桩按 installId 过滤（真实 JPQL 语义同理）：换个 installId 查同一个 clientRef 查不到
        when(repo.findByInstallIdAndSourceAndClientRefIn(eq("install-b"), eq(UserFeedback.SOURCE_CLOUD), anyList()))
                .thenReturn(List.of());

        ResponseEntity<?> resp = controller.ingestStatus(Map.of("installId", "install-b", "clientRefs", List.of("9")));

        assertTrue(items(resp).isEmpty(), "installId 不匹配时不能查到别人的回执");
    }

    @Test
    void missingInstallIdIsRejectedNotSilentlyEmpty() {
        ResponseEntity<?> resp = controller.ingestStatus(Map.of("clientRefs", List.of("9")));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void clientRefsAreTruncatedAtTheLimit() {
        List<String> huge = new ArrayList<>();
        for (int i = 0; i < 500; i++) huge.add(String.valueOf(i));
        when(repo.findByInstallIdAndSourceAndClientRefIn(eq("install-a"), eq(UserFeedback.SOURCE_CLOUD), anyList()))
                .thenReturn(List.of());

        controller.ingestStatus(Map.of("installId", "install-a", "clientRefs", huge));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).findByInstallIdAndSourceAndClientRefIn(eq("install-a"), eq(UserFeedback.SOURCE_CLOUD), captor.capture());
        assertEquals(200, captor.getValue().size(), "超过上限要截断，不能被当扫描器使");
    }

    @Test
    void emptyClientRefsSkipsTheQueryEntirely() {
        ResponseEntity<?> resp = controller.ingestStatus(Map.of("installId", "install-a", "clientRefs", List.of()));
        assertTrue(items(resp).isEmpty());
        verify(repo, org.mockito.Mockito.never())
                .findByInstallIdAndSourceAndClientRefIn(eq("install-a"), eq(UserFeedback.SOURCE_CLOUD), anyList());
    }

    @Test
    void guardDisabledMeansNotFoundNotEmptyBatch() {
        FeedbackIngestGuard closedGuard = new FeedbackIngestGuard(repo); // enabled 默认 false
        FeedbackIngestController c = new FeedbackIngestController(mock(FeedbackService.class), closedGuard, repo);

        ResponseEntity<?> resp = c.ingestStatus(Map.of("installId", "install-a", "clientRefs", List.of("9")));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void overQuotaInstallGets429() {
        when(repo.countByInstallIdAndCreatedAtAfter(eq("install-a"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(999L);

        ResponseEntity<?> resp = controller.ingestStatus(Map.of("installId", "install-a", "clientRefs", List.of("9")));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
    }
}
