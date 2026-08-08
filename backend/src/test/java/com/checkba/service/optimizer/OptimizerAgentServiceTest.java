package com.checkba.service.optimizer;

import com.checkba.model.entity.UserFeedback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    OptimizerFeedbackSource source;
    FeedbackTriageService triageService;
    OptimizerCodeFixRunner fixRunner;
    OptimizerNotifyRouter notifier;
    OptimizerAgentService svc;

    UserFeedback row;

    @BeforeEach
    void setup() {
        props = new OptimizerProperties();
        props.setEnabled(true);
        props.setMinConfidence(0.7);
        props.setMaxAttempts(3);

        source = mock(OptimizerFeedbackSource.class);
        triageService = mock(FeedbackTriageService.class);
        fixRunner = mock(OptimizerCodeFixRunner.class);
        notifier = mock(OptimizerNotifyRouter.class);

        row = new UserFeedback();
        row.setId(5L);
        row.setKind(UserFeedback.KIND_BUG);
        row.setText("点保存没反应");
        row.setStatus(UserFeedback.STATUS_NEW);

        when(source.pending(anyInt(), anyInt())).thenReturn(List.of(row));
        when(source.attachmentsOf(any(UserFeedback.class))).thenReturn(List.of());
        when(notifier.isAvailable()).thenReturn(true);
        when(notifier.unavailableReason()).thenReturn("");
        when(notifier.notify(any(), any(), any(), any(), any())).thenReturn("");

        svc = new OptimizerAgentService(props, source, triageService, fixRunner, notifier);
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
        verifyNoInteractions(triageService, fixRunner, notifier, source);
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
        verify(notifier, never()).notify(any(), any(), any(), any(), any());
    }

    @Test
    void notifiedFeedbackRecordsTheIssueUrlAsItsDestination() {
        triageReturns(FeedbackTriageService.VERDICT_SUGGESTION, 0.8);
        when(notifier.notify(any(), any(), any(), any(), any()))
                .thenReturn("https://github.com/a/b/issues/42");

        svc.runOnce();

        // 去向地址不区分 PR 还是 Issue：后台看板要能一键点开
        assertEquals("https://github.com/a/b/issues/42", row.getPrUrl());
        assertEquals(UserFeedback.STATUS_EMAILED, row.getStatus());
    }

    @Test
    void suggestionGoesToMailAndNeverTouchesCode() {
        triageReturns(FeedbackTriageService.VERDICT_SUGGESTION, 0.8);

        var report = svc.runOnce();

        assertEquals(1, report.emailed());
        assertEquals(UserFeedback.STATUS_EMAILED, row.getStatus());
        verify(notifier).notify(any(), any(), any(), any(), any());
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
        verify(notifier).notify(any(), any(), any(), any(), contains("什么都没改"));
    }

    @Test
    void blockedDiffFallsBackToMail() {
        triageReturns(FeedbackTriageService.VERDICT_BUG, 0.95);
        when(fixRunner.run(any(), any())).thenReturn(new OptimizerCodeFixRunner.FixOutcome(
                OptimizerCodeFixRunner.Status.BLOCKED, "b", "", "碰了 .github/"));

        svc.runOnce();

        assertEquals(UserFeedback.STATUS_EMAILED, row.getStatus());
        verify(notifier).notify(any(), any(), any(), any(), contains(".github/"));
    }

    @Test
    void noiseIsSkippedNotDeleted() {
        triageReturns(FeedbackTriageService.VERDICT_NOISE, 0.9);

        var report = svc.runOnce();

        assertEquals(1, report.skipped());
        // 无效反馈也只是改状态：来源上没有任何删除入口，行永远留着
        assertEquals(UserFeedback.STATUS_SKIPPED, row.getStatus());
        verify(source).save(row);
    }

    @Test
    void unavailableNotifierKeepsFeedbackForRetryInsteadOfMarkingItHandled() {
        triageReturns(FeedbackTriageService.VERDICT_SUGGESTION, 0.8);
        when(notifier.isAvailable()).thenReturn(false);
        when(notifier.unavailableReason()).thenReturn("邮件：未配置 spring.mail.host（没有 JavaMailSender）");

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
        when(notifier.isAvailable()).thenReturn(false);

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
        verify(notifier, never()).notify(any(), any(), any(), any(), any());
    }

    @Test
    void batchSizeIsHonoured() {
        props.setBatchSize(3);
        triageReturns(FeedbackTriageService.VERDICT_NOISE, 0.9);

        svc.runOnce();

        verify(source).pending(3, 3);
    }
}
