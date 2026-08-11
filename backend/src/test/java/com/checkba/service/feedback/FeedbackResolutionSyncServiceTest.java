package com.checkba.service.feedback;

import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.telemetry.InstallIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 把云端收件箱的处理结果拉回本机反馈行。这条链断了的表现是「用户提交完永远看不到
 * 进度」，很难从日志反推，所以断言写细：写回哪些字段、拉不到时原行纹丝不动。
 */
class FeedbackResolutionSyncServiceTest {

    UserFeedbackRepository repo;
    InstallIdentityService identity;

    @BeforeEach
    void setup() {
        repo = mock(UserFeedbackRepository.class);
        identity = mock(InstallIdentityService.class);
        when(identity.installId()).thenReturn("install-me");
    }

    private FeedbackResolutionSyncService service(FeedbackResolutionSyncService.Transport t) {
        return new FeedbackResolutionSyncService(repo, identity, true,
                "https://cloud/api/feedback/ingest", t);
    }

    private static UserFeedback localRow(long id, String status) {
        UserFeedback fb = new UserFeedback();
        fb.setId(id);
        fb.setSource(UserFeedback.SOURCE_LOCAL);
        fb.setStatus(status);
        fb.setUploaded(true);
        return fb;
    }

    private void stubPending(UserFeedback... rows) {
        when(repo.findByUploadedTrueAndSourceAndStatusInOrderByIdAsc(
                eq(UserFeedback.SOURCE_LOCAL), any(), any(Pageable.class)))
                .thenReturn(List.of(rows));
    }

    @Test
    void statusUrlIsDerivedFromUploadUrlNotANewConfigItem() {
        StringBuilder calledUrl = new StringBuilder();
        stubPending(localRow(9L, UserFeedback.STATUS_NEW));

        service((url, body) -> {
            calledUrl.append(url);
            return new FeedbackResolutionSyncService.Response(200, "{\"code\":0,\"data\":{\"items\":[]}}");
        }).sync();

        // upload 走 .../ingest，回执查询在同一个云端上加 /status
        assertEquals("https://cloud/api/feedback/ingest/status", calledUrl.toString());
    }

    @Test
    void resolvedItemIsWrittenBackToTheLocalRow() {
        UserFeedback row = localRow(9L, UserFeedback.STATUS_NEW);
        stubPending(row);

        service((url, body) -> new FeedbackResolutionSyncService.Response(200, """
                {"code":0,"data":{"items":[
                  {"clientRef":"9","status":"PR_OPENED","triageVerdict":"BUG",
                   "prUrl":"https://github.com/a/b/pull/1","handledAt":"2026-08-09T10:00:00"}
                ]}}""")).sync();

        assertEquals(UserFeedback.STATUS_PR_OPENED, row.getStatus());
        assertEquals("BUG", row.getTriageVerdict());
        assertEquals("https://github.com/a/b/pull/1", row.getPrUrl());
        assertNotNull(row.getHandledAt());
        verify(repo).save(row);
    }

    @Test
    void rowNotYetHandledByCloudIsLeftUntouched() {
        UserFeedback row = localRow(9L, UserFeedback.STATUS_NEW);
        stubPending(row);

        // 云端还没处理这条：回执里压根没有它
        service((url, body) -> new FeedbackResolutionSyncService.Response(200,
                "{\"code\":0,\"data\":{\"items\":[]}}")).sync();

        assertEquals(UserFeedback.STATUS_NEW, row.getStatus());
        assertNull(row.getPrUrl());
        verify(repo, never()).save(any());
    }

    @Test
    void networkFailureLeavesRowUntouchedAndNeverThrows() {
        UserFeedback row = localRow(9L, UserFeedback.STATUS_FAILED);
        stubPending(row);

        FeedbackResolutionSyncService svc = service((url, body) -> {
            throw new java.net.ConnectException("断网");
        });

        assertDoesNotThrow(svc::sync);
        assertEquals(UserFeedback.STATUS_FAILED, row.getStatus());
        verify(repo, never()).save(any());
    }

    @Test
    void nonZeroResponseCodeLeavesRowUntouched() {
        UserFeedback row = localRow(9L, UserFeedback.STATUS_NEW);
        stubPending(row);

        service((url, body) -> new FeedbackResolutionSyncService.Response(403,
                "{\"code\":1,\"message\":\"installId 不合法\"}")).sync();

        assertEquals(UserFeedback.STATUS_NEW, row.getStatus());
        verify(repo, never()).save(any());
    }

    @Test
    void emptyBatchSkipsNetworkCallEntirely() {
        stubPending();

        service((url, body) -> {
            fail("没有待查的行不该发请求");
            return null;
        }).sync();
    }

    @Test
    void inertWithoutUrl() {
        FeedbackResolutionSyncService svc = new FeedbackResolutionSyncService(repo, identity, true, "",
                (url, body) -> {
                    fail("没配地址不该发请求");
                    return null;
                });
        assertFalse(svc.isConfigured());
        assertDoesNotThrow(svc::sync);
        verify(repo, never()).findByUploadedTrueAndSourceAndStatusInOrderByIdAsc(any(), any(), any());
    }

    @Test
    void disabledUploadKeepsSyncInertToo() {
        FeedbackResolutionSyncService svc = new FeedbackResolutionSyncService(repo, identity, false,
                "https://cloud/api/feedback/ingest",
                (url, body) -> {
                    fail("关掉了还发就是漏数据");
                    return null;
                });
        assertFalse(svc.isConfigured());
        assertDoesNotThrow(svc::sync);
    }
}
