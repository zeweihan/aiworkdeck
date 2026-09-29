// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.DdComment;
import com.checkba.model.entity.DdItem;
import com.checkba.repository.DdCommentRepository;
import com.checkba.repository.DdItemRepository;
import com.checkba.repository.DdRequestRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 律师审核的状态机（dev-board#1057）：只有已上传的条目能下结论，驳回必须带理由并落成
 * 「驳回：」留言，非法流转一律 {@link DdService.IllegalTransitionException}（控制器映射成 HTTP 400），
 * 已通过的条目不再收材料。
 */
class DdItemReviewStatusTest {

    private DdItemRepository itemRepo;
    private DdCommentRepository commentRepo;
    private StorageServiceFactory storage;
    private DdService service;
    private DdItem item;
    private final List<DdComment> comments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        itemRepo = mock(DdItemRepository.class);
        commentRepo = mock(DdCommentRepository.class);
        storage = mock(StorageServiceFactory.class);
        service = new DdService(mock(DdRequestRepository.class), itemRepo, commentRepo,
                mock(ProjectFileRepository.class), mock(ProjectFileService.class), storage);
        item = new DdItem();
        item.setId(7L);
        item.setDdRequestId(1L);
        item.setTitle("营业执照");
        item.setSortOrder(0);
        when(itemRepo.findById(7L)).thenReturn(Optional.of(item));
        when(itemRepo.save(any(DdItem.class))).thenAnswer(i -> i.getArgument(0));
        when(commentRepo.save(any(DdComment.class))).thenAnswer(i -> {
            comments.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(commentRepo.findByDdItemIdOrderByCreatedAtAsc(7L)).thenAnswer(i -> new ArrayList<>(comments));
    }

    private void uploaded(String status) {
        item.setUploadedFileId(900L);
        item.setStatus(status);
    }

    private void assertIllegal(String status, String reason) {
        String before = item.getStatus();
        assertThrows(DdService.IllegalTransitionException.class,
                () -> service.updateItemStatus(7L, status, reason, 1L), before + " -> " + status);
        assertEquals(before, item.getStatus(), "非法流转不许改动状态");
    }

    @Test
    @DisplayName("没上传就通过/驳回/撤回 → 400")
    void nothingUploadedCannotBeReviewed() {
        item.setStatus("PENDING");
        assertIllegal("APPROVED", null);
        assertIllegal("REJECTED", "看不清");
        assertIllegal("UPLOADED", null);
        verify(itemRepo, never()).save(any());
    }

    @Test
    @DisplayName("通过 → 撤回通过 → 驳回（带理由写留言）→ 再通过")
    void legalTransitions() {
        uploaded("UPLOADED");
        assertEquals("APPROVED", service.updateItemStatus(7L, "APPROVED", null, 1L).getStatus());
        assertEquals("UPLOADED", service.updateItemStatus(7L, "UPLOADED", null, 1L).getStatus());
        assertEquals("REJECTED", service.updateItemStatus(7L, "REJECTED", "  扫描件不清晰  ", 1L).getStatus());
        assertEquals(1, comments.size());
        assertEquals("驳回：扫描件不清晰", comments.get(0).getContent());
        assertEquals(1L, comments.get(0).getUserId());
        assertEquals(Map.of(7L, "扫描件不清晰"), service.rejectReasons(List.of(item)));
        assertEquals("APPROVED", service.updateItemStatus(7L, "APPROVED", null, 1L).getStatus());
        assertTrue(service.rejectReasons(List.of(item)).isEmpty(), "已不是驳回状态就不回理由");
    }

    @Test
    @DisplayName("驳回不带理由、重复下同一结论、手工改回待上传、未知状态 → 400")
    void illegalTransitions() {
        uploaded("UPLOADED");
        assertIllegal("REJECTED", null);
        assertIllegal("REJECTED", "   ");
        assertIllegal("REJECTED", "x".repeat(1001));
        assertIllegal("UPLOADED", null);
        assertIllegal("PENDING", null);
        assertIllegal("WHATEVER", null);
        assertIllegal(null, null);
        uploaded("APPROVED");
        assertIllegal("APPROVED", null);
        uploaded("REJECTED");
        assertIllegal("REJECTED", "再驳一次");
        assertTrue(comments.isEmpty(), "失败的驳回不许留下留言");
    }

    @Test
    @DisplayName("已通过的条目不再收材料（在写存储之前就拦住）")
    void approvedItemRejectsUpload() {
        uploaded("APPROVED");
        MockMultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[]{1});
        assertThrows(DdService.IllegalTransitionException.class, () -> service.uploadFile(7L, file, 3L));
        verify(storage, never()).getStorageService();
    }

    @Test
    @DisplayName("英文前缀的驳回留言同样认得出理由")
    void englishPrefixRecognised() {
        uploaded("REJECTED");
        DdComment c = new DdComment();
        c.setDdItemId(7L);
        c.setUserId(1L);
        c.setContent("Rejected: blurry scan");
        comments.add(c);
        DdComment other = new DdComment();
        other.setContent("好的，我重新扫");
        comments.add(other);
        assertEquals("blurry scan", service.rejectReasons(List.of(item)).get(7L));
        verify(commentRepo, never()).save(any());
        verify(itemRepo, never()).findById(anyLong());
    }
}
