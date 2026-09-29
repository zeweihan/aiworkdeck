// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.review;

import com.checkba.model.entity.ProjectFileReview;
import com.checkba.model.entity.ProjectFileReviewComment;
import com.checkba.repository.ProjectFileReviewCommentRepository;
import com.checkba.repository.ProjectFileReviewRepository;
import com.checkba.service.LangText;
import com.checkba.service.ProjectFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 计划审阅（dev-board#1022，规格 §4.2 / §4.5）：按文件的审阅记录与批注。
 *
 * <p>鉴权（项目读写权限、fileId 属于 projectId）在控制器里做，这里只管状态机：
 * open 幂等创建（已有 open 记录原样返回、不覆盖基线）→ submit / discard 二选一收尾。
 * 没有 open 记录时的写操作一律抛 {@link IllegalStateException}（控制器映射 409）。
 */
@Service
@RequiredArgsConstructor
public class FileReviewService {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_SUBMITTED = "submitted";
    public static final String STATUS_DISCARDED = "discarded";

    private final ProjectFileReviewRepository reviews;
    private final ProjectFileReviewCommentRepository comments;
    private final ProjectFileService projectFileService;

    @Transactional
    public ProjectFileReview open(Long projectId, Long fileId, String conversationId, String artifactId,
                                  String baselineText, Long userId) {
        Optional<ProjectFileReview> existing = reviews.findFirstByFileIdAndStatus(fileId, STATUS_OPEN);
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalDateTime now = LocalDateTime.now();
        ProjectFileReview r = new ProjectFileReview();
        r.setProjectId(projectId);
        r.setFileId(fileId);
        r.setConversationId(conversationId);
        r.setArtifactId(artifactId);
        r.setBaselineText(baselineText == null ? "" : baselineText);
        r.setStatus(STATUS_OPEN);
        r.setCreatedBy(userId);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return reviews.save(r);
    }

    public Optional<ProjectFileReview> current(Long fileId) {
        return reviews.findFirstByFileIdAndStatus(fileId, STATUS_OPEN);
    }

    public List<ProjectFileReviewComment> comments(Long reviewId) {
        return comments.findByReviewIdOrderByIdAsc(reviewId);
    }

    @Transactional
    public ProjectFileReviewComment addComment(Long fileId, Integer fromLine, Integer toLine,
                                               String quotedText, String body) {
        ProjectFileReview r = requireOpen(fileId);
        ProjectFileReviewComment c = new ProjectFileReviewComment();
        c.setReviewId(r.getId());
        c.setFromLine(fromLine);
        c.setToLine(toLine);
        c.setQuotedText(quotedText);
        c.setBody(body);
        c.setCreatedAt(LocalDateTime.now());
        return comments.save(c);
    }

    /** 只删当前 open 记录下的批注：按全局 commentId 删别的文件/别的记录的批注是越权。 */
    @Transactional
    public void deleteComment(Long fileId, Long commentId) {
        ProjectFileReview r = requireOpen(fileId);
        ProjectFileReviewComment c = comments.findById(commentId)
                .filter(x -> r.getId().equals(x.getReviewId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        LangText.of("批注不存在", "Comment not found")));
        comments.delete(c);
    }

    @Transactional
    public ProjectFileReview submit(Long fileId) {
        ProjectFileReview r = requireOpen(fileId);
        r.setStatus(STATUS_SUBMITTED);
        r.setUpdatedAt(LocalDateTime.now());
        return reviews.save(r);
    }

    /** 先把文件字节写回基线（写失败则整笔不变，记录仍是 open），再置 discarded。 */
    @Transactional
    public ProjectFileReview discard(Long projectId, Long fileId, Long userId) {
        ProjectFileReview r = requireOpen(fileId);
        projectFileService.overwriteTextContent(projectId, fileId, r.getBaselineText(), userId);
        r.setStatus(STATUS_DISCARDED);
        r.setUpdatedAt(LocalDateTime.now());
        return reviews.save(r);
    }

    private ProjectFileReview requireOpen(Long fileId) {
        return reviews.findFirstByFileIdAndStatus(fileId, STATUS_OPEN)
                .orElseThrow(() -> new IllegalStateException("no open review"));
    }
}
