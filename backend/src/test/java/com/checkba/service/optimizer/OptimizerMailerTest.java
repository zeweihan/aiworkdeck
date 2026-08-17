package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import com.checkba.service.mail.MailRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 邮件出口：可用性判定的每条理由都要说得出口，正文要带够维护者直接动手的信息。 */
class OptimizerMailerTest {

    OptimizerProperties props;
    MailRouter router;
    OptimizerMailer mailer;

    @BeforeEach
    void setup() {
        props = new OptimizerProperties();
        props.getMail().setTo("me@example.com");
        router = mock(MailRouter.class);
        when(router.active()).thenReturn(true);
        mailer = new OptimizerMailer(props, router);
    }

    /** 附件地址的形态由来源决定（本地磁盘路径 / 云端 URL），邮件只负责原样贴出来。 */
    private static OptimizerFeedbackSource sourceRef(String ref) {
        OptimizerFeedbackSource s = mock(OptimizerFeedbackSource.class);
        when(s.attachmentRef(any(), any())).thenReturn(ref);
        return s;
    }

    private static UserFeedback feedback() {
        UserFeedback fb = new UserFeedback();
        fb.setId(12L);
        fb.setKind(UserFeedback.KIND_IDEA);
        fb.setText("希望批量导出");
        fb.setPage("pages/project-overview/project-overview");
        fb.setAppVersion("0.13.0");
        fb.setPlatform("Mac OS X 15.0");
        fb.setCreatedAt(LocalDateTime.of(2026, 8, 8, 10, 0));
        return fb;
    }

    private static FeedbackTriageService.TriageResult triage(String verdict) {
        return new FeedbackTriageService.TriageResult(verdict, 0.35, "批量导出",
                "用户希望一次导出多个文件", "low", "属于新功能", "{}");
    }

    /** 抓最近一次发信的正文。 */
    private String capturedBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(router, atLeastOnce()).send(any(), any(), body.capture());
        return body.getValue();
    }

    private String capturedSubject() {
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(router, atLeastOnce()).send(any(), subject.capture(), any());
        return subject.getValue();
    }

    @Test
    @DisplayName("一条发信通道都没配时不可用，理由要指到具体配置项")
    void unavailableWhenNoMailChannel() {
        when(router.active()).thenReturn(false);
        assertFalse(mailer.isAvailable());
        assertTrue(mailer.unavailableReason().contains("mail.domestic"),
                "理由要说清缺哪一项：" + mailer.unavailableReason());
    }

    @Test
    void unavailableWhenNoRecipient() {
        props.getMail().setTo("");
        assertFalse(mailer.isAvailable());
        assertTrue(mailer.unavailableReason().contains("optimizer.mail.to"));
    }

    @Test
    void sendsMessageWithActionableBody() {
        FeedbackAttachment a = new FeedbackAttachment();
        a.setId(3L);
        a.setType(FeedbackAttachment.TYPE_AUDIO);
        a.setStoredName("voice-1.webm");

        mailer.send(feedback(), triage(FeedbackTriageService.VERDICT_SUGGESTION),
                List.of(a), sourceRef("/data/feedback/12/voice-1.webm  (API: /api/feedback/12/attachment/3)"), "");

        verify(router).send(eq("me@example.com"), any(), any());
        assertTrue(capturedSubject().startsWith("[AI WorkDeck 优化者]"));
        assertTrue(capturedSubject().contains("优化建议待定夺"));

        String body = capturedBody();
        assertTrue(body.contains("希望批量导出"));
        assertTrue(body.contains("0.13.0"));
        assertTrue(body.contains("/data/feedback/12/voice-1.webm"));
        assertTrue(body.contains("/api/feedback/12/attachment/3"));
        // 带语音又没转写：必须明说要人去听
        assertTrue(body.contains("需要你亲自听"));
        // 只发不收，别让维护者对着邮件回复
        assertTrue(body.contains("回信不会被系统读取"));
    }

    @Test
    void unclearVerdictAsksForADecision() {
        mailer.send(feedback(), triage(FeedbackTriageService.VERDICT_UNCLEAR),
                List.of(), sourceRef(""), "改不出来");

        assertTrue(capturedSubject().contains("需要你拍板"));
        assertTrue(capturedBody().contains("为什么没直接开 PR"));
        assertTrue(capturedBody().contains("改不出来"));
    }

    @Test
    @DisplayName("多收件人逐个分别发——他们可能分属不同通道，塞一封信只能挑一条")
    void multipleRecipientsAreSentIndividually() {
        props.getMail().setTo("a@qq.com, b@gmail.com");
        mailer.send(feedback(), triage(FeedbackTriageService.VERDICT_SUGGESTION), List.of(), sourceRef(""), "");

        verify(router).send(eq("a@qq.com"), any(), any());
        verify(router).send(eq("b@gmail.com"), any(), any());
        verify(router, times(2)).send(any(), any(), any());
    }

    @Test
    @DisplayName("收件人串里的空项被跳过，不会发出一封没有收件人的信")
    void skipsBlankRecipients() {
        props.getMail().setTo("a@qq.com,, ,b@gmail.com");
        mailer.send(feedback(), triage(FeedbackTriageService.VERDICT_SUGGESTION), List.of(), sourceRef(""), "");
        verify(router, times(2)).send(any(), any(), any());
    }
}
