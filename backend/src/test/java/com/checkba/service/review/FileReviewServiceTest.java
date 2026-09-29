// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.review;

import com.checkba.model.entity.ProjectFileReview;
import com.checkba.model.entity.ProjectFileReviewComment;
import com.checkba.repository.ProjectFileReviewCommentRepository;
import com.checkba.repository.ProjectFileReviewRepository;
import com.checkba.service.ProjectFileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 计划审阅记录（dev-board#1022）：幂等创建、批注增删、submit、discard 写回基线。 */
@ExtendWith(MockitoExtension.class)
class FileReviewServiceTest {
    @Mock ProjectFileReviewRepository reviews;
    @Mock ProjectFileReviewCommentRepository comments;
    @Mock ProjectFileService projectFileService;
    @InjectMocks FileReviewService svc;

    private static ProjectFileReview openReview() {
        ProjectFileReview r = new ProjectFileReview();
        r.setId(9L); r.setFileId(77L); r.setStatus("open"); r.setBaselineText("基线正文");
        return r;
    }

    @Test @DisplayName("同一文件已有 open 记录时 open() 幂等返回它，不覆盖基线")
    void openIsIdempotent() {
        ProjectFileReview existing = new ProjectFileReview();
        existing.setId(9L); existing.setFileId(77L); existing.setStatus("open"); existing.setBaselineText("原文");
        when(reviews.findFirstByFileIdAndStatus(77L, "open")).thenReturn(Optional.of(existing));
        ProjectFileReview r = svc.open(1L, 77L, "conv", "art", "新的基线", 5L);
        assertSame(existing, r);
        assertEquals("原文", r.getBaselineText());
        verify(reviews, never()).save(any());
    }

    @Test @DisplayName("没有 open 记录时 open() 新建一条 open 记录")
    void openCreatesWhenAbsent() {
        when(reviews.findFirstByFileIdAndStatus(77L, "open")).thenReturn(Optional.empty());
        when(reviews.save(any())).thenAnswer(a -> a.getArgument(0));
        ProjectFileReview r = svc.open(1L, 77L, "conv", "art", "基线", 5L);
        assertEquals("open", r.getStatus());
        assertEquals(1L, r.getProjectId());
        assertEquals(77L, r.getFileId());
        assertEquals("conv", r.getConversationId());
        assertEquals("art", r.getArtifactId());
        assertEquals("基线", r.getBaselineText());
        assertEquals(5L, r.getCreatedBy());
        assertNotNull(r.getCreatedAt());
        assertNotNull(r.getUpdatedAt());
    }

    @Test @DisplayName("submit 把 open 记录置 submitted")
    void submitMarksSubmitted() {
        ProjectFileReview r = new ProjectFileReview(); r.setId(9L); r.setFileId(77L); r.setStatus("open");
        when(reviews.findFirstByFileIdAndStatus(77L, "open")).thenReturn(Optional.of(r));
        when(reviews.save(any())).thenAnswer(a -> a.getArgument(0));
        assertEquals("submitted", svc.submit(77L).getStatus());
    }

    @Test @DisplayName("没有 open 记录时 submit / discard 抛 IllegalStateException")
    void submitDiscardRequireOpen() {
        when(reviews.findFirstByFileIdAndStatus(77L, "open")).thenReturn(Optional.empty());
        assertThrows(FileReviewService.NoOpenReviewException.class, () -> svc.submit(77L));
        assertThrows(FileReviewService.NoOpenReviewException.class, () -> svc.discard(1L, 77L, 5L));
        verifyNoInteractions(projectFileService);
    }

    @Test @DisplayName("discard 把文件字节写回基线并置 discarded")
    void discardRestoresBaseline() {
        ProjectFileReview r = openReview();
        when(reviews.findFirstByFileIdAndStatus(77L, "open")).thenReturn(Optional.of(r));
        when(reviews.save(any())).thenAnswer(a -> a.getArgument(0));
        svc.discard(1L, 77L, 5L);
        verify(projectFileService).overwriteTextContent(1L, 77L, "基线正文", 5L);
        assertEquals("discarded", r.getStatus());
    }

    @Test @DisplayName("没有 open 记录时 addComment 抛 IllegalStateException")
    void addCommentRequiresOpenReview() {
        when(reviews.findFirstByFileIdAndStatus(77L, "open")).thenReturn(Optional.empty());
        assertThrows(FileReviewService.NoOpenReviewException.class, () -> svc.addComment(77L, 1, 2, "引用", "评论"));
    }

    @Test @DisplayName("addComment 挂在 open 记录下并落字段")
    void addCommentSaves() {
        when(reviews.findFirstByFileIdAndStatus(77L, "open")).thenReturn(Optional.of(openReview()));
        when(comments.save(any())).thenAnswer(a -> a.getArgument(0));
        ProjectFileReviewComment c = svc.addComment(77L, 3, 4, "引用", "评论");
        assertEquals(9L, c.getReviewId());
        assertEquals(3, c.getFromLine());
        assertEquals(4, c.getToLine());
        assertEquals("引用", c.getQuotedText());
        assertEquals("评论", c.getBody());
        assertNotNull(c.getCreatedAt());
    }

    @Test @DisplayName("deleteComment 只删当前 open 记录下的批注，别的记录的批注拒绝")
    void deleteCommentScopedToOpenReview() {
        when(reviews.findFirstByFileIdAndStatus(77L, "open")).thenReturn(Optional.of(openReview()));
        ProjectFileReviewComment mine = new ProjectFileReviewComment(); mine.setId(31L); mine.setReviewId(9L);
        ProjectFileReviewComment other = new ProjectFileReviewComment(); other.setId(32L); other.setReviewId(8L);
        when(comments.findById(31L)).thenReturn(Optional.of(mine));
        when(comments.findById(32L)).thenReturn(Optional.of(other));
        svc.deleteComment(77L, 31L);
        ArgumentCaptor<ProjectFileReviewComment> cap = ArgumentCaptor.forClass(ProjectFileReviewComment.class);
        verify(comments).delete(cap.capture());
        assertSame(mine, cap.getValue());
        assertThrows(IllegalArgumentException.class, () -> svc.deleteComment(77L, 32L));
        verify(comments, times(1)).delete(any());
    }

    @Test @DisplayName("ProjectFileService.overwriteTextContent：UTF-8 字节覆盖存储键、回写大小并发版本信号；跨项目拒绝")
    void overwriteTextContentWritesBytesAndSignals() throws Exception {
        com.checkba.repository.ProjectFileRepository repo = mock(com.checkba.repository.ProjectFileRepository.class);
        com.checkba.storage.StorageServiceFactory factory = mock(com.checkba.storage.StorageServiceFactory.class);
        com.checkba.storage.StorageService storage = mock(com.checkba.storage.StorageService.class);
        com.checkba.version.WorkSessionService ws = mock(com.checkba.version.WorkSessionService.class);
        ProjectFileService real = new ProjectFileService(repo, mock(com.checkba.service.ai.ProjectRagService.class),
                factory, ws, mock(com.checkba.service.UserService.class),
                mock(com.checkba.service.quota.StageQuotaService.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.evidence.EvidenceLinkService.class));
        com.checkba.model.entity.ProjectFile f = new com.checkba.model.entity.ProjectFile();
        f.setId(77L); f.setProjectId(1L); f.setIsFolder(false); f.setName("plan.md"); f.setFilePath("p/1/plan.md");
        when(repo.findById(77L)).thenReturn(Optional.of(f));
        when(repo.save(any())).thenAnswer(a -> a.getArgument(0));
        when(factory.getStorageService()).thenReturn(storage);

        real.overwriteTextContent(1L, 77L, "基线正文", 5L);

        ArgumentCaptor<java.io.InputStream> in = ArgumentCaptor.forClass(java.io.InputStream.class);
        verify(storage).save(eq("p/1/plan.md"), in.capture());
        byte[] expected = "基线正文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(expected, in.getValue().readAllBytes());
        assertEquals((long) expected.length, f.getFileSize());
        assertNotNull(f.getUpdatedAt());
        verify(ws).onChangeSignal(eq(1L), any(), any());

        assertThrows(IllegalArgumentException.class, () -> real.overwriteTextContent(2L, 77L, "x", 5L));
        verify(storage, times(1)).save(any(), any());
    }
}
