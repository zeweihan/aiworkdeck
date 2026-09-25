// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.ClipboardItem;
import com.checkba.repository.ClipboardItemRepository;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BUG-62：同一段文字再复制一次，剪贴板面板里出现两张一样的卡片。
 * 同文本合并到已有那一条、把时间戳顶到现在（排到最前），不新增行，也不删任何记录。
 */
class ClipboardDedupTest {

    private static final Long USER = 7L;

    private ClipboardItemRepository repository;
    private ClipboardService service;
    private final List<ClipboardItem> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(ClipboardItemRepository.class);
        service = new ClipboardService(repository, mock(StorageServiceFactory.class), mock(EntitlementService.class), true);
        when(repository.findByUserIdOrderByCreatedAtDesc(eq(USER), any(Pageable.class)))
                .thenAnswer(inv -> rows.stream()
                        .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt())).toList());
        when(repository.save(any(ClipboardItem.class))).thenAnswer(inv -> {
            ClipboardItem it = inv.getArgument(0);
            if (it.getId() == null) {
                it.setId((long) rows.size() + 1);
                rows.add(it);
            }
            return it;
        });
    }

    private static ClipboardItem row(long id, String type, String text, LocalDateTime at) {
        ClipboardItem it = new ClipboardItem();
        it.setId(id);
        it.setUserId(USER);
        it.setType(type);
        it.setText(text);
        it.setCreatedAt(at);
        return it;
    }

    @Test
    @DisplayName("同文本再次复制：合并到原记录并更新时间戳，不新增卡片")
    void sameTextMergesAndBumpsTimestamp() {
        LocalDateTime old = LocalDateTime.now().minusMinutes(5);
        rows.add(row(1, "TEXT", "QA-C8-剪贴一", old));
        rows.add(row(2, "TEXT", "QA-C8-剪贴二", old.plusMinutes(1)));

        ClipboardItem saved = service.saveText(USER, "QA-C8-剪贴一");

        assertEquals(2, rows.size(), "不该为同一段文字再建一行");
        assertEquals(1L, saved.getId());
        assertTrue(saved.getCreatedAt().isAfter(old.plusMinutes(1)), "时间戳要顶到最新，排在列表最前");
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    @DisplayName("不同文本、或同内容的图片/文件记录，照常新增")
    void differentTextStillCreates() {
        rows.add(row(1, "TEXT", "甲", LocalDateTime.now().minusMinutes(1)));
        rows.add(row(2, "IMAGE", "乙", LocalDateTime.now().minusMinutes(1)));

        service.saveText(USER, "乙");
        service.saveText(USER, "丙");

        assertEquals(4, rows.size());
    }
}
