package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 通知出口的选路与开 Issue。
 *
 * <p>要守的核心是「反馈没人管」这件事不取决于有没有配 SMTP：邮件不可用时 auto 必须自动
 * 落到开 Issue（那条路不需要任何新凭据），且两条都不可用时要把理由说全。
 */
class OptimizerNotifyTest {

    @TempDir
    Path repoDir;

    OptimizerProperties props;
    OptimizerMailer mailer;
    RecordingRunner runner;
    OptimizerIssueNotifier issues;
    OptimizerNotifyRouter router;

    static class RecordingRunner implements ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        Function<List<String>, Result> responder =
                cmd -> new Result(0, "https://github.com/acme/awd/issues/42\n", "");

        @Override
        public Result run(List<String> command, File workingDir, int timeoutSeconds) {
            commands.add(List.copyOf(command));
            return responder.apply(command);
        }
    }

    private static UserFeedback feedback() {
        UserFeedback fb = new UserFeedback();
        fb.setId(12L);
        fb.setKind(UserFeedback.KIND_IDEA);
        fb.setText("希望能看到我提的这条后来被怎么处理了");
        fb.setAppVersion("0.13.0");
        fb.setPlatform("macOS");
        return fb;
    }

    private static FeedbackTriageService.TriageResult triage() {
        return new FeedbackTriageService.TriageResult(FeedbackTriageService.VERDICT_SUGGESTION, 0.42,
                "反馈处理进度可见", "用户希望能查到自己那条的去向", "low", "属于新增能力", "{}");
    }

    @BeforeEach
    void setup() throws Exception {
        Files.createDirectories(repoDir.resolve(".git"));
        props = new OptimizerProperties();
        props.getRepo().setPath(repoDir.toString());
        mailer = mock(OptimizerMailer.class);
        when(mailer.name()).thenReturn("邮件");
        runner = new RecordingRunner();
        issues = new OptimizerIssueNotifier(props, runner);
        router = new OptimizerNotifyRouter(props, mailer, issues);
    }

    @Test
    void autoFallsBackToIssueWhenMailIsNotConfigured() {
        when(mailer.isAvailable()).thenReturn(false);

        String url = router.notify(feedback(), triage(), List.of(), null, "");

        assertEquals("https://github.com/acme/awd/issues/42", url);
        assertTrue(runner.commands.get(0).subList(0, 3).equals(List.of("gh", "issue", "create")));
        verify(mailer, never()).notify(any(), any(), any(), any(), any());
    }

    @Test
    void autoPrefersMailWhenItIsConfigured() {
        when(mailer.isAvailable()).thenReturn(true);
        when(mailer.notify(any(), any(), any(), any(), any())).thenReturn("");

        router.notify(feedback(), triage(), List.of(), null, "");

        verify(mailer).notify(any(), any(), any(), any(), any());
        assertTrue(runner.commands.isEmpty(), "有邮件就不该再开 Issue");
    }

    @Test
    void bothSendsToEveryAvailableChannel() {
        props.getNotify().setChannel("both");
        when(mailer.isAvailable()).thenReturn(true);
        when(mailer.notify(any(), any(), any(), any(), any())).thenReturn("");

        String url = router.notify(feedback(), triage(), List.of(), null, "");

        verify(mailer).notify(any(), any(), any(), any(), any());
        assertFalse(runner.commands.isEmpty());
        assertEquals("https://github.com/acme/awd/issues/42", url, "有地址的那条优先当去向");
    }

    @Test
    void oneChannelFailingIsStillDeliveredByTheOther() {
        props.getNotify().setChannel("both");
        when(mailer.isAvailable()).thenReturn(true);
        when(mailer.notify(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("SMTP 拒了"));

        String url = router.notify(feedback(), triage(), List.of(), null, "");

        assertEquals("https://github.com/acme/awd/issues/42", url);
    }

    @Test
    void allChannelsDownReportsEveryReason() {
        props.getNotify().setChannel("both");
        props.getRepo().setPath("");
        when(mailer.isAvailable()).thenReturn(false);
        when(mailer.unavailableReason()).thenReturn("未配置 spring.mail.host");

        assertFalse(router.isAvailable());
        String reason = router.unavailableReason();
        assertTrue(reason.contains("spring.mail.host"));
        assertThrows(IllegalStateException.class,
                () -> router.notify(feedback(), triage(), List.of(), null, ""));
    }

    @Test
    void missingLabelIsRetriedWithoutIt() {
        when(mailer.isAvailable()).thenReturn(false);
        runner.responder = cmd -> cmd.contains("--label")
                ? new ProcessRunner.Result(1, "", "could not add label: 'user-feedback' not found")
                : new ProcessRunner.Result(0, "https://github.com/acme/awd/issues/43\n", "");

        String url = router.notify(feedback(), triage(), List.of(), null, "");

        // 仓库里没建过这个 label 不该让整条出口不可用
        assertEquals("https://github.com/acme/awd/issues/43", url);
        assertEquals(2, runner.commands.size());
        assertFalse(runner.commands.get(1).contains("--label"));
    }

    @Test
    void issueBodyCarriesUserWordsAndVerdict() {
        String body = issues.body(feedback(), triage(), List.of(), null, "改不出来");
        assertTrue(body.contains("希望能看到我提的这条后来被怎么处理了"));
        assertTrue(body.contains("SUGGESTION"));
        assertTrue(body.contains("0.13.0"));
        assertTrue(body.contains("为什么没直接开 PR"));
        assertTrue(body.contains("不会再被重复处理"));
    }

    @Test
    void issueBodyTellsYouToListenWhenVoiceHasNoTranscript() {
        FeedbackAttachment a = new FeedbackAttachment();
        a.setId(3L);
        a.setType(FeedbackAttachment.TYPE_AUDIO);
        a.setStoredName("voice-1.webm");
        OptimizerFeedbackSource src = mock(OptimizerFeedbackSource.class);
        when(src.attachmentRef(any(), any())).thenReturn("https://cloud/api/feedback/12/attachment/3");

        String body = issues.body(feedback(), triage(), List.of(a), src, "");

        assertTrue(body.contains("https://cloud/api/feedback/12/attachment/3"));
        assertTrue(body.contains("需要你亲自听"));
    }

    @Test
    void issueChannelNeedsOnlyTheRepoCheckout() {
        assertTrue(issues.isAvailable(), "有仓库副本就够了——不需要任何新凭据");
        props.getRepo().setPath("");
        assertFalse(issues.isAvailable());
        assertTrue(issues.unavailableReason().contains("optimizer.repo.path"));
    }

    @Test
    void issueUrlIsPickedOutOfChattyGhOutput() {
        assertEquals("https://github.com/a/b/issues/9",
                OptimizerIssueNotifier.firstIssueUrl("Creating issue\nhttps://github.com/a/b/issues/9\n"));
        assertEquals("", OptimizerIssueNotifier.firstIssueUrl("nothing"));
    }
}
