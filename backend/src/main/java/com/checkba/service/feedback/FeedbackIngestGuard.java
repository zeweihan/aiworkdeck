package com.checkba.service.feedback;

import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.LangText;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 云端收件箱的准入闸。
 *
 * <p><b>为什么 ingest 不要求登录：</b>最该被听见的正是那些没注册、没付费、刚装上就撞墙的用户。
 * 要求账号等于把他们静音。代价是这条端点对公网开着，所以闸门全压在配额与体积上：
 * 单安装每天多少条、全站每天多少条、单个附件多大、一次几个附件。
 *
 * <p>限流的口径是**按天计数已入库的行**而不是内存令牌桶：进程重启不该把配额清零，
 * 而反馈本来就是低频动作，查一次 count 的代价可以忽略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackIngestGuard {

    private final UserFeedbackRepository feedbackRepository;

    @Value("${feedback.ingest.enabled:false}")
    private boolean enabled;

    /** 单个安装每天最多传几条：一个人一天认真写 20 条反馈已经是极限 */
    @Value("${feedback.ingest.per-install-daily:20}")
    private int perInstallDaily;

    /** 全站每天上限：兜住"某个版本刚发出去，所有人同时报同一个崩溃"之外的异常量 */
    @Value("${feedback.ingest.global-daily:2000}")
    private int globalDaily;

    /** ingest 的单附件上限比本地严：截图几百 KB，几 MB 已经不正常 */
    @Value("${feedback.ingest.max-attachment-bytes:5242880}")
    private long maxAttachmentBytes;

    @Value("${feedback.ingest.max-attachments:4}")
    private int maxAttachments;

    public boolean isEnabled() {
        return enabled;
    }

    /** 配额到顶（可重试）与请求不合法（重试也没用）分开抛，控制器据此给 429 / 400。 */
    public static class QuotaExceededException extends IllegalArgumentException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }

    /** 不通过就抛：配额类抛 {@link QuotaExceededException}，其余抛 IllegalArgumentException。 */
    public void check(String installId, List<MultipartFile> files) {
        if (!enabled) throw new IllegalStateException(LangText.of("本实例未开启反馈收件箱", "The feedback inbox is not enabled on this instance"));
        if (installId == null || installId.isBlank() || installId.length() > 64) {
            throw new IllegalArgumentException(LangText.of("缺少或非法的 installId", "Missing or invalid installId"));
        }
        List<MultipartFile> safe = files == null ? List.of() : files;
        if (safe.size() > maxAttachments) {
            throw new IllegalArgumentException(LangText.of("附件过多", "Too many attachments"));
        }
        for (MultipartFile f : safe) {
            if (f != null && f.getSize() > maxAttachmentBytes) {
                throw new IllegalArgumentException(LangText.of("附件过大", "Attachment is too large"));
            }
        }
        LocalDateTime since = LocalDateTime.now().minusDays(1);
        if (feedbackRepository.countByInstallIdAndCreatedAtAfter(installId, since) >= perInstallDaily) {
            throw new QuotaExceededException(LangText.of("该安装今日提交已达上限", "This installation has reached today's submission limit"));
        }
        if (feedbackRepository.countByCreatedAtAfter(since) >= globalDaily) {
            log.warn("反馈收件箱触到全站日上限 {}，本条被拒", globalDaily);
            throw new QuotaExceededException(LangText.of("今日收件已满，请稍后再试", "Today's inbox is full, please try again later"));
        }
    }

    /** 供状态展示：收件箱是否在收、今天收了多少。 */
    public long todayCount() {
        return feedbackRepository.countByCreatedAtAfter(LocalDateTime.now().minusDays(1));
    }

    public long pendingCount() {
        return feedbackRepository.countByStatus(UserFeedback.STATUS_NEW);
    }
}
