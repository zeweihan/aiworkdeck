package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import com.checkba.service.mail.MailRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「建议 / 拿不准」这条出口：给维护者发一封信。
 *
 * <p>只发不收。优化者不解析回信——收信要么轮询 IMAP 要么架 webhook，
 * 都会把一个每天跑一次的批处理变成一个常驻服务；维护者读完信直接去改代码
 * 或在库里改状态更省事。信里因此写清「回信不会被系统读取」。
 *
 * <p>发信走 {@link MailRouter}（{@code mail.domestic.*} / {@code mail.global.*}）：
 * 一条通道都没配就整条出口不可用（会记 FAILED 等人配置），不静默丢反馈。
 * 发件人由通道决定，本类不指定——两条通道的发信域名不同，硬写 from 会和实际发信域名对不上，
 * SPF 当场判失败。
 */
@Slf4j
@Service
public class OptimizerMailer implements OptimizerNotifier {

    private final OptimizerProperties props;
    private final MailRouter mailRouter;

    /**
     * 已经成功发信的 (feedbackId, 收件人) 记录，进程内存、不落库。
     *
     * <p>病灶：多收件人分属不同通道，某条通道故障时该收件人发送失败，异常冒泡出去，
     * 外层 notifyOrFail 把这条反馈判失败转 NEW 重试；下一轮定时任务重跑会把已经
     * 收到过的收件人再发一封，故障通道修好之前每天都重复。
     *
     * <p>不落库的理由：optimizer.mail.to 是维护者自己的邮箱，重发一次是「视觉上的
     * 不一致」而不是数据丢失（审计原话），不值得为它加表/加列。代价是进程重启会
     * 清空这份记忆，重启后最多再重发一轮——可接受，且 optimizer 本来就是每天一次的
     * 定时任务，重启不常发生在两次运行之间。
     */
    private final Set<String> mailed = ConcurrentHashMap.newKeySet();

    public OptimizerMailer(OptimizerProperties props, MailRouter mailRouter) {
        this.props = props;
        this.mailRouter = mailRouter;
    }

    @Override
    public String name() {
        return "邮件";
    }

    /** 邮件出口是否可用：至少一条发信通道配齐且填了收件人。 */
    @Override
    public boolean isAvailable() {
        return props.getMail().isEnabled()
                && mailRouter.active()
                && !props.getMail().getTo().isBlank();
    }

    @Override
    public String unavailableReason() {
        if (!props.getMail().isEnabled()) return "optimizer.mail.enabled=false";
        if (!mailRouter.active()) return "未配置发信通道（mail.domestic.* 或 mail.global.*）";
        if (props.getMail().getTo().isBlank()) return "未配置 optimizer.mail.to（收件人）";
        return "";
    }

    /** 邮件没有可点开的地址，恒返回空串。 */
    @Override
    public String notify(UserFeedback fb, FeedbackTriageService.TriageResult triage,
                         List<FeedbackAttachment> attachments, OptimizerFeedbackSource source, String extraNote) {
        send(fb, triage, attachments, source, extraNote);
        return "";
    }

    public void send(UserFeedback fb, FeedbackTriageService.TriageResult triage,
                     List<FeedbackAttachment> attachments, OptimizerFeedbackSource source, String extraNote) {
        if (!mailRouter.active()) throw new IllegalStateException(unavailableReason());

        String subject = subject(fb, triage);
        String body = body(fb, triage, attachments, source, extraNote);
        // 逐个收件人分别发：多个收件人可能分属不同通道（维护者的 Gmail 与同事的 QQ 邮箱
        // 走的不是同一条），塞进同一封信就只能挑一条通道发，另一半到达率白丢。
        // 全部收件人都要试一遍，不能因为前一个失败就不再尝试后面的——否则同一次重试
        // 永远只可能推进到"第一个故障收件人"为止，排在它后面的人永远收不到信。
        List<String> failed = new ArrayList<>();
        for (String to : props.getMail().getTo().split(",")) {
            String trimmed = to.trim();
            if (trimmed.isEmpty()) continue;
            String key = fb.getId() + ":" + trimmed;
            if (!mailed.add(key)) continue; // 这条反馈已经成功发给过这个收件人，跳过
            try {
                mailRouter.send(trimmed, subject, body);
            } catch (RuntimeException e) {
                mailed.remove(key); // 没发成功，撤销标记，留给下一轮重试
                failed.add(trimmed);
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("邮件发送失败: " + String.join(",", failed));
        }
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

        // 反馈控制台直达（有浏览器入口的来源才有；本地来源为 null，不出现这一行）
        String console = source == null ? null : source.consoleRef(fb);
        if (console != null && !console.isBlank()) {
            sb.append("\n== 在浏览器里看这条反馈 ==\n")
                    .append(console).append('\n')
                    .append("（登录 admin 后直接看截图、听语音，不用 curl）\n");
        }

        sb.append("\n--\n")
                .append("附件裸地址在浏览器里会 403（要带凭据），命令行取用取件密钥：\n")
                .append("  curl -H \"X-Optimizer-Token: <你的 token>\" '<附件地址>' -o shot.png\n")
                .append("这封信由 AI WorkDeck 优化者自动发出。**回信不会被系统读取**——\n")
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
