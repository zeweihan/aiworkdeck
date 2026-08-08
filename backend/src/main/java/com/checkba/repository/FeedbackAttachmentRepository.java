package com.checkba.repository;

import com.checkba.model.entity.FeedbackAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedbackAttachmentRepository extends JpaRepository<FeedbackAttachment, Long> {

    List<FeedbackAttachment> findByFeedbackIdOrderByIdAsc(Long feedbackId);
}
