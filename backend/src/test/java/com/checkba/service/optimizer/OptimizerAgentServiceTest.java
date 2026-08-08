package com.checkba.service.optimizer;

import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.feedback.FeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 分流逻辑：什么情况下去改代码开 PR、什么情况下发邮件问人、什么情况下都不算处理完。
 * 这是整个闭环的判断中枢，所有分支都要有断言。
 */
class OptimizerAgentServiceTest {

    OptimizerProperties props;
    UserFeedbackRepository repo;
    FeedbackService feedbackService;
    FeedbackTriageService triageService;
    OptimizerCodeFixRunner fixRunner;
    OptimizerMailer mailer;
    OptimizerAgentService svc;

    UserFeedback row;

    @BeforeEach
    void setup() {
        props = new OptimizerProperties();
        props.setEnabled(true);
        props.setMinConfidence(0.7);
        props.setMaxAttempts(3);

        repo = mock(UserFeedbackRepository.class);
        feedbackService = mock(FeedbackService.class);
        triageService = mock(FeedbackTriageService.class);
        fixRunner = mock(OptimizerCodeFixRunner.class);
        mailer = mock(OptimizerMailer.class);

        row = new UserFeedback();
        row.setId(5L);
        row.setKind(UserFeedback.KIND_BUG);
        row.setText("点保存没反应");
        row.setStatus(UserFeedback.STATUS_NEW);

        when(repo.findByStatusAndAttemptsLessThanOrderByIdAsc(eq(UserFeedback.STATUS_NEW), anyInt(), any(Pageable.class)))
                .thenReturn(List.of(row));
        when(repo.save(any(UserFeedback.class))).thenAnswer(inv -> inv.getArgument(0));
        when(feedbackService.attachmentsOf(anyLong())).thenReturn(List.of());
        when(feedbackService.feedbackDir(anyLong())).thenReturn(Path.of("/tmp/feedback/5"));
        when(mailer.isAvailable()).thenReturn(true);
        when(mailer.unavailableReason()).thenReturn("");

        svc = new OptimizerAgentService(props, repo, feedbackService, triageService, fixRunner, mailer);
    }

    private void triageReturns(String verdict, double confidence) {
        when(triageService.triage(any(), any(), any())).thenReturn(
                new FeedbackTriageService.TriageResult(verdict, confidence, "标题", "复述", "high", "依据", "{}"));
    }

    @Test
    void disabledOptimizerDoesNothing() {
        props.setEnabled(false);
        var report = svc.runOnce();
        assertEquals(0, report.picked());
        assertTrue(report.note().contains("未启用"));
        verifyNoInteractions(triageService, fixRunner, mailer);
    }

    @Test
    void confidentBugOpensPrAndNeverMails() {
        triageReturns(FeedbackTriageService.VERDICT_BUG, 0.92);
        when(fixRunner.run(any(), any())).thenReturn(new OptimizerCodeFixRunner.FixOutcome(
                OptimizerCodeFixRunner.Status.PR_OPENED, "optimizer/feedback-5",
                "https://github.com/a/b/pull/9", "ok"));

        var report = svc.runOnce();

        assertEquals(1, report.prOpened());
        assertEquals(0, report.emailed());
        assertEquals(UserFeedback.STATUS_PR_OPENED, row.getStatus());
        assertEquals("https://github.com/a/b/pull/9", row.getPrUrl());
        assertEquals(FeedbackTriageService.VERDICT_BUG, row.getTriageVerdict());
        assertNotNull(row.getHandledAt());
        assertNotNull(row.getTriageJson());
        verify(mailer, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void suggestionGoesToMailAndNeverTouchesCode() {
        triageReturns(FeedbackTriageService.VERDICT_SUGGESTION, 0.8);

        var report = svc.runOnce();

        assertEquals(1, report.emailed());
        assertEquals(UserFeedback.STATUS_EMAILED, row.getStatus());
        verify(mailer).send(any(), any(), any(), any(), any());
        verifyNoInteractions(fixRunner);
    }

    @Test
    void lowConfidenceBugIsDowngradedToMail() {
        triageReturns(FeedbackTriageService.VERDICT_BUG, 0.5);

        var report = svc.runOnce();

        assertEquals(1, report.emailed());
        assertEquals(UserFeedback.STATUS_EMAILED, row.getStatus());
        verifyNoInteractions(fixRunner);
        assertTrue(report.items().get(0).detail().contains("低于开 PR 的门槛"));
    }

    @Test
    void bugThatProducedNoDiffFallsBackToMail() {
        triageReturns(FeedbackTriageService.VERDICT_BUG, 0.95);
        when(fixRunner.run(any(), any())).thenReturn(new OptimizerCodeFixRunner.FixOutcome(
                OptimizerCodeFixRunner.Status.NO_CHANGES, "b", "", "Agent 什么都没改"));

        var report = svc.runOnce();

        assertEquals(1, report.emailed());
        assertEquals(UserFeedback.STATUS_EMAILED, row.getStatus());
        verify(mailer).send(any(), any(), any(), any(), contains("什么都没改"));
    }

    @Test
    void blockedDiffFallsBackToMail() {
        triageReturns(FeedbackTriageService.VERDICT_BUG, 0.95);
        when(fixRunner.run(any(), any())).thenReturn(new OptimizerCodeFixRunner.FixOutcome(
                OptimizerCodeFixRunner.Status.BLOCKED, "b", "", "碰了 .github/"));

        svc.runOnce();

        assertEquals(UserFeedback.STATUS_EMAILED, row.getStatus());
        verify(mailer).send(any(), any(), any(), any(), contains(".github/"));
    }

    @Test
    void noiseIsSkippedNotDeleted() {
        triageReturns(FeedbackTriageService.VERDICT_NOISE, 0.9);

        var report = svc.runOnce();

        assertEquals(1, report.skipped());
        assertEquals(UserFeedback.STATUS_SKIPPED, row.getStatus());
        verify(repo, never()).delete(any());
        verify(repo, never()).deleteById(any());
    }

    @Test
    void unavailableMailKeepsFeedbackForRetryInsteadOfMarkingItHandled() {
        triageReturns(FeedbackTriageService.VERDICT_SUGGESTION, 0.8);
        when(mailer.isAvailable()).thenReturn(false);
        when(mailer.unavailableReason()).thenReturn("未配置 spring.mail.host（没有 JavaMailSender）");

        var report = svc.runOnce();

        assertEquals(1, report.failed());
        // 还没到重试上限：退回 NEW 等下一轮，绝不能悄悄标成已处理
        assertEquals(UserFeedback.STATUS_NEW, row.getStatus());
        assertEquals(1, row.getAttempts());
        assertTrue(row.getLastError().contains("spring.mail.host"));
    }

    @Test
    void repeatedFailureEventuallyStopsAtFailed() {
        props.setMaxAttempts(1);
        triageReturns(FeedbackTriageService.VERDICT_SUGGESTION, 0.8);
        when(mailer.isAvailable()).thenReturn(false);

        svc.runOnce();

        assertEquals(UserFeedback.STATUS_FAILED, row.getStatus());
    }

    @Test
    void triageCrashStillCountsAsAnAttempt() {
        when(triageService.triage(any(), any(), any())).thenThrow(new IllegalStateException("模型不可用"));

        var report = svc.runOnce();

        assertEquals(1, report.failed());
        assertEquals(1, row.getAttempts(), "分诊挂了也要计数，否则永远到不了上限、每轮白烧 token");
        assertTrue(row.getLastError().contains("模型不可用"));
    }

    @Test
    void dryRunTriagesButChangesNothing() {
        props.setDryRun(true);
        triageReturns(FeedbackTriageService.VERDICT_BUG, 0.99);

        var report = svc.runOnce();

        assertEquals(1, report.picked());
        assertEquals(0, report.prOpened());
        assertEquals(0, report.emailed());
        assertEquals(UserFeedback.STATUS_NEW, row.getStatus());
        assertEquals(0, row.getAttempts());
        assertEquals(FeedbackTriageService.VERDICT_BUG, row.getTriageVerdict(), "演练也要留下分诊结论");
        verifyNoInteractions(fixRunner);
        verify(mailer, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void batchSizeIsHonoured() {
        props.setBatchSize(3);
        triageReturns(FeedbackTriageService.VERDICT_NOISE, 0.9);

        svc.runOnce();

        verify(repo).findByStatusAndAttemptsLessThanOrderByIdAsc(
                eq(UserFeedback.STATUS_NEW), eq(3), argThat(p -> p.getPageSize() == 3));
    }
}
