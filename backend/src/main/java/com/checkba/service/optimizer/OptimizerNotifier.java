package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;

import java.util.List;

/**
 * 「建议 / 拿不准」这条出口的投递方式。
 *
 * <p>本来只有邮件，但邮件出口有个绕不开的前置：SMTP 授权码只能由维护者自己去邮箱后台生成，
 * 没配就整条出口不可用（反馈只能堆在 FAILED 里）。而**开 Issue 不需要任何新凭据**——
 * 优化者本来就要有能推分支、开 PR 的 gh 登录。所以这里抽出一层，两种投递方式等价，
 * 由 {@link OptimizerNotifyRouter} 按配置和可用性选一个，谁都没配好时明确报不可用。
 */
public interface OptimizerNotifier {

    /** 人话名字，进状态接口与日志。 */
    String name();

    boolean isAvailable();

    /** 不可用的具体原因（要能直接告诉维护者缺哪一项）。 */
    String unavailableReason();

    /**
     * 投递一条。
     *
     * @return 可点开的去向地址（Issue URL）；邮件这类没有地址的返回空串
     */
    String notify(UserFeedback fb, FeedbackTriageService.TriageResult triage,
                  List<FeedbackAttachment> attachments, OptimizerFeedbackSource source, String extraNote);
}
