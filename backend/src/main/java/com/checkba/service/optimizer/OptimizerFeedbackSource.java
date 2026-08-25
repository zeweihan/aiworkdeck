package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;

import java.util.List;

/**
 * 优化者的反馈来源。
 *
 * <p>存在的理由：优化者**不该**跟收件箱同机。收件箱要放在公网上收各安装的反馈，
 * 而优化者要带着仓库、GitHub 推送凭据和一个能写代码的 Agent——把后者放到生产站那台机器上，
 * 等于让用户可控的文本和生产环境做邻居。拆开之后：云端只当收件箱，优化者跑在维护者自己的机器上，
 * 经 {@link RemoteFeedbackSource} 取件与回执。
 *
 * <p>两个实现对上层完全等价，{@link OptimizerAgentService} 感知不到自己读的是本地库还是云端。
 */
public interface OptimizerFeedbackSource {

    /** 取一批待分诊的反馈。 */
    List<UserFeedback> pending(int limit, int maxAttempts);

    List<FeedbackAttachment> attachmentsOf(UserFeedback fb);

    /** 回写状态与分诊结论。 */
    void save(UserFeedback fb);

    /** 邮件里给维护者看的附件地址（本地是磁盘路径，远端是可直接打开的 URL）。 */
    String attachmentRef(UserFeedback fb, FeedbackAttachment a);

    /**
     * 这条反馈在浏览器里的直达地址（反馈控制台，登录后看图听语音），没有就返回 null。
     * 远端来源指向云端后台的 /feedback-console/；本地来源没有浏览器入口（桌面端
     * 自带 admin 看板），返回 null，通知正文里就不出现这一行。
     */
    default String consoleRef(UserFeedback fb) {
        return null;
    }

    /** 人话描述，进状态接口，方便一眼看出这台优化者在读谁。 */
    String describe();
}
