package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 选用哪条通知出口：{@code optimizer.notify.channel} = auto（默认）| mail | issue | both。
 *
 * <p><b>auto 的含义是「有邮件用邮件，没有就开 Issue」</b>——邮件要维护者自己去邮箱后台生成
 * 授权码才能用，而开 Issue 不需要任何新凭据。默认让它自己降级，是为了让「反馈没人管」
 * 这件事不取决于有没有腾出时间配 SMTP。
 *
 * <p>both 是两边都发；任一成功即算送达（返回可点开的地址优先取 Issue）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizerNotifyRouter {

    private final OptimizerProperties props;
    private final OptimizerMailer mailer;
    private final OptimizerIssueNotifier issueNotifier;

    /** 本轮实际会用的出口（按配置 + 可用性解析）。 */
    public List<OptimizerNotifier> active() {
        String channel = props.getNotify().getChannel() == null
                ? "auto" : props.getNotify().getChannel().trim().toLowerCase();
        List<OptimizerNotifier> out = new ArrayList<>();
        switch (channel) {
            case "mail" -> out.add(mailer);
            case "issue" -> out.add(issueNotifier);
            case "both" -> {
                if (mailer.isAvailable()) out.add(mailer);
                if (issueNotifier.isAvailable()) out.add(issueNotifier);
                if (out.isEmpty()) out.add(mailer); // 让不可用理由能被说出口
            }
            default -> out.add(mailer.isAvailable() ? mailer : issueNotifier);
        }
        return out;
    }

    public boolean isAvailable() {
        return active().stream().anyMatch(OptimizerNotifier::isAvailable);
    }

    /** 所有候选都不可用时，把各自的理由拼给维护者，别让他猜。 */
    public String unavailableReason() {
        List<String> reasons = new ArrayList<>();
        for (OptimizerNotifier n : active()) {
            if (!n.isAvailable()) reasons.add(n.name() + "：" + n.unavailableReason());
        }
        return String.join("；", reasons);
    }

    public String describe() {
        List<String> names = new ArrayList<>();
        for (OptimizerNotifier n : active()) {
            names.add(n.name() + (n.isAvailable() ? "" : "（不可用）"));
        }
        return String.join(" + ", names);
    }

    /**
     * 投递。任一出口成功即算送达；全失败抛异常（由调用方记 FAILED，绝不当成已处理）。
     *
     * @return 可点开的去向地址（Issue URL），没有就空串
     */
    public String notify(UserFeedback fb, FeedbackTriageService.TriageResult triage,
                         List<FeedbackAttachment> attachments, OptimizerFeedbackSource source, String extraNote) {
        List<String> errors = new ArrayList<>();
        String url = "";
        boolean delivered = false;
        for (OptimizerNotifier n : active()) {
            if (!n.isAvailable()) {
                errors.add(n.name() + "：" + n.unavailableReason());
                continue;
            }
            try {
                String u = n.notify(fb, triage, attachments, source, extraNote);
                delivered = true;
                if (u != null && !u.isBlank()) url = u;
            } catch (Exception e) {
                errors.add(n.name() + "：" + e.getMessage());
            }
        }
        if (!delivered) throw new IllegalStateException(String.join("；", errors));
        if (!errors.isEmpty()) log.warn("[optimizer] 部分出口失败（已由其它出口送达）：{}", String.join("；", errors));
        return url;
    }
}
