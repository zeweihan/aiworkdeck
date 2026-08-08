package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.service.feedback.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;

import java.util.List;

/** 读本进程自己的库（单机自用，或收件箱与优化者刻意同机时）。 */
@RequiredArgsConstructor
public class LocalFeedbackSource implements OptimizerFeedbackSource {

    private final UserFeedbackRepository repository;
    private final FeedbackService feedbackService;

    @Override
    public List<UserFeedback> pending(int limit, int maxAttempts) {
        return repository.findByStatusAndAttemptsLessThanOrderByIdAsc(
                UserFeedback.STATUS_NEW, maxAttempts, PageRequest.of(0, Math.max(1, limit)));
    }

    @Override
    public List<FeedbackAttachment> attachmentsOf(UserFeedback fb) {
        return feedbackService.attachmentsOf(fb.getId());
    }

    @Override
    public void save(UserFeedback fb) {
        repository.save(fb);
    }

    @Override
    public String attachmentRef(UserFeedback fb, FeedbackAttachment a) {
        return feedbackService.attachmentPath(fb.getId(), a.getStoredName())
                + "  (API: /api/feedback/" + fb.getId() + "/attachment/" + a.getId() + ")";
    }

    @Override
    public String describe() {
        return "本进程的反馈库";
    }
}
