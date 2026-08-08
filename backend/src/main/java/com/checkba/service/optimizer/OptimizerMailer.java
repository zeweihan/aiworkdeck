package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 「建议 / 拿不准」这条出口：给维护者发一封信。
 *
 * <p>只发不收。优化者不解析回信——收信要么轮询 IMAP 要么架 webhook，
 * 都会把一个每天跑一次的批处理变成一个常驻服务；维护者读完信直接去改代码
 * 或在库里改状态更省事。信里因此写清「回信不会被系统读取」。
 *
 * <p>邮件通道用 Spring 的 {@code spring.mail.*}：配了 host 才有 JavaMailSender bean，
 * 没配就整条出口不可用（会记 FAILED 等人配置），不静默丢反馈。
 */
@Slf4j
@Service
public class OptimizerMailer {

    private final OptimizerProperties props;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    public OptimizerMailer(OptimizerProperties props, ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.props = props;
        this.mailSenderProvider = mailSenderProvider;
    }

    /** 邮件出口是否可用：要有 JavaMailSender（配了 spring.mail.host）且填了收件人。 */
    public boolean isAvailable() {
        return props.getMail().isEnabled()
                && mailSenderProvider.getIfAvailable() != null
                && !props.getMail().getTo().isBlank();
    }

    public String unavailableReason() {
        if (!props.getMail().isEnabled()) return "optimizer.mail.enabled=false";
        if (mailSenderProvider.getIfAvailable() == null) return "未配置 spring.mail.host（没有 JavaMailSender）";
        if (props.getMail().getTo().isBlank()) return "未配置 optimizer.mail.to（收件人）";
        return "";
    }

    public void send(UserFeedback fb, FeedbackTriageService.TriageResult triage,
                     List<FeedbackAttachment> attachments, OptimizerFeedbackSource source, String extraNote) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) throw new IllegalStateException(unavailableReason());

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setTo(props.getMail().getTo().split(","));
        if (!props.getMail().getFrom().isBlank()) msg.setFrom(props.getMail().getFrom());
        msg.setSubject(subject(fb, triage));
        msg.setText(body(fb, triage, attachments, source, extraNote));
        sender.send(msg);
        log.info("[optimizer] 已发送反馈 #{} 的邮件到 {}", fb.getId(), props.getMail().getTo());
    }

    String subject(UserFeedback fb, FeedbackTriageService.TriageResult triage) {
        String head = switch (triage.verdict()) {
            case FeedbackTriageService.VERDICT_SUGGESTION -> "优化建议待定夺";
            case FeedbackTriageService.VERDICT_NOISE -> "一条疑似无效反馈";
            default -> "需要你拍板";
        };
        String title = (triage.title() == null || triage.title().isBlank())
                ? "用户反馈 #" + fb.getId() : triage.title().trim();
        return props.getMail().getSubjectPrefix() + " " + head + "：" + title;
    }

    String body(UserFeedback fb, FeedbackTriageService.TriageResult triage,
                List<FeedbackAttachment> attachments, OptimizerFeedbackSource source, String extraNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("反馈 #").append(fb.getId())
                .append("（").append(UserFeedback.KIND_IDEA.equals(fb.getKind()) ? "用户选了「建议」" : "用户选了「报障」")
                .append("，").append(fb.getCreatedAt()).append("）\n\n");

        sb.append("== 用户原话 ==\n");
        sb.append(blank(fb.getText()) ? "（没写文字）" : fb.getText().trim()).append('\n');
        if (!blank(fb.getVoiceTranscript())) {
            sb.append("\n[语音转写] ").append(fb.getVoiceTranscript().trim()).append('\n');
        }

        sb.append("\n== 优化者怎么判的 ==\n")
                .append("判定：").append(triage.verdict())
                .append("（置信度 ").append(String.format("%.2f", triage.confidence()))
                .append("，严重度 ").append(nz(triage.severity())).append("）\n")
                .append("复述：").append(nz(triage.summary())).append('\n')
                .append("依据：").append(nz(triage.reason())).append('\n');
        if (!blank(extraNote)) {
            sb.append("\n== 为什么没直接开 PR ==\n").append(extraNote.trim()).append('\n');
        }

        sb.append("\n== 现场 ==\n")
                .append("页面：").append(nz(fb.getPage())).append('\n')
                .append("版本：").append(nz(fb.getAppVersion())).append('\n')
                .append("平台：").append(nz(fb.getPlatform())).append('\n');

        if (attachments != null && !attachments.isEmpty()) {
            sb.append("\n== 附件（").append(attachments.size()).append(" 件）==\n");
            for (FeedbackAttachment a : attachments) {
                // 地址形态由来源决定：本地是磁盘路径，云端收件箱是可直接打开的 URL
                sb.append("- ").append(a.getType()).append(' ')
                        .append(source == null ? a.getStoredName() : source.attachmentRef(fb, a))
                        .append('\n');
            }
            boolean audioNoTranscript = attachments.stream()
                    .anyMatch(a -> FeedbackAttachment.TYPE_AUDIO.equals(a.getType()))
                    && blank(fb.getVoiceTranscript());
            if (audioNoTranscript) {
                sb.append("\n注意：这条反馈带语音但本机没配转写服务，内容需要你亲自听一遍。\n");
            }
        }

        sb.append("\n--\n")
                .append("这封信由 AI Workdeck 优化者自动发出。**回信不会被系统读取**——\n")
                .append("要推进就直接去改代码，或把这条反馈的 status 改掉（表 user_feedback，id=")
                .append(fb.getId()).append("）。\n");
        return sb.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
