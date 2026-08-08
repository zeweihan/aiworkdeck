package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 邮件出口：可用性判定的每条理由都要说得出口，正文要带够维护者直接动手的信息。 */
class OptimizerMailerTest {

    OptimizerProperties props;
    JavaMailSender sender;
    OptimizerMailer mailer;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JavaMailSender> providerOf(JavaMailSender s) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(s);
        return p;
    }

    @BeforeEach
    void setup() {
        props = new OptimizerProperties();
        props.getMail().setTo("me@example.com");
        props.getMail().setFrom("bot@example.com");
        sender = mock(JavaMailSender.class);
        mailer = new OptimizerMailer(props, providerOf(sender));
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

    @Test
    void unavailableWhenNoMailSender() {
        OptimizerMailer none = new OptimizerMailer(props, providerOf(null));
        assertFalse(none.isAvailable());
        assertTrue(none.unavailableReason().contains("spring.mail.host"));
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
                List.of(a), Path.of("/data/feedback/12"), "");

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(cap.capture());
        SimpleMailMessage msg = cap.getValue();

        assertArrayEquals(new String[]{"me@example.com"}, msg.getTo());
        assertEquals("bot@example.com", msg.getFrom());
        assertTrue(msg.getSubject().startsWith("[AI Workdeck 优化者]"));
        assertTrue(msg.getSubject().contains("优化建议待定夺"));

        String body = msg.getText();
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
                List.of(), Path.of("/data/feedback/12"), "改不出来");

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(cap.capture());
        assertTrue(cap.getValue().getSubject().contains("需要你拍板"));
        assertTrue(cap.getValue().getText().contains("为什么没直接开 PR"));
        assertTrue(cap.getValue().getText().contains("改不出来"));
    }

    @Test
    void multipleRecipientsAreSplit() {
        props.getMail().setTo("a@example.com,b@example.com");
        mailer.send(feedback(), triage(FeedbackTriageService.VERDICT_SUGGESTION), List.of(), null, "");

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(cap.capture());
        assertArrayEquals(new String[]{"a@example.com", "b@example.com"}, cap.getValue().getTo());
    }
}
