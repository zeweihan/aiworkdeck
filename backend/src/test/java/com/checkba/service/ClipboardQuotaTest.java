package com.checkba.service;

import com.checkba.model.dto.ClipboardListResult;
import com.checkba.model.entity.ClipboardItem;
import com.checkba.repository.ClipboardItemRepository;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.service.entitlement.FeatureCatalog;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 剪贴板免费额度（Spec §5）。锁定的是这几件事：
 * <ul>
 *   <li>额度是<b>查询侧过滤</b>——任何路径都不得调用 delete；</li>
 *   <li>条数上限 20 与保留 3 天<b>同时</b>生效，取更严者；</li>
 *   <li>hiddenCount 只算「因额度看不见」的，不含仅被分页 limit 挡住的；</li>
 *   <li>拥有 clipboard.unlimited 时完全不过滤，之前被隐藏的记录原样回来。</li>
 * </ul>
 */
class ClipboardQuotaTest {

    private static final Long USER = 7L;

    private ClipboardItemRepository repository;
    private EntitlementService entitlementService;
    private ClipboardService service;

    @BeforeEach
    void setUp() {
        repository = mock(ClipboardItemRepository.class);
        entitlementService = mock(EntitlementService.class);
        service = new ClipboardService(repository, mock(StorageServiceFactory.class), entitlementService);
        when(entitlementService.isEnabled(anyString())).thenReturn(false);
    }

    /** 造 n 条记录，第 i 条的 createdAt 是 i 小时前（越靠前越新）。 */
    private static List<ClipboardItem> items(int n, int hoursApart) {
        List<ClipboardItem> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ClipboardItem it = new ClipboardItem();
            it.setId((long) (i + 1));
            it.setUserId(USER);
            it.setType("TEXT");
            it.setText("t" + i);
            it.setCreatedAt(LocalDateTime.now().minusHours((long) i * hoursApart));
            list.add(it);
        }
        return list;
    }

    /** 仓库按 (userId, pageable) 返回前 pageSize 条。 */
    private void stubList(List<ClipboardItem> all) {
        when(repository.findByUserIdOrderByCreatedAtDesc(eq(USER), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable p = inv.getArgument(1);
                    return all.subList(0, Math.min(all.size(), p.getPageSize()));
                });
        when(repository.countByUserId(USER)).thenReturn((long) all.size());
        when(repository.countByUserIdAndCreatedAtAfter(eq(USER), any(LocalDateTime.class)))
                .thenAnswer(inv -> {
                    LocalDateTime cutoff = inv.getArgument(1);
                    return all.stream().filter(i -> i.getCreatedAt().isAfter(cutoff)).count();
                });
    }

    @Test
    @DisplayName("19 条（全在 3 天内）：全部可见，不提示受限条数")
    void nineteenItemsAllVisible() {
        stubList(items(19, 1));
        ClipboardListResult res = service.list(USER, null, 80);
        assertEquals(19, res.items().size());
        assertTrue(res.limited(), "免费版始终标记 limited，供前端说明额度存在");
        assertEquals(0, res.hiddenCount());
        assertEquals(20, res.maxItems());
        assertEquals(3, res.retentionDays());
    }

    @Test
    @DisplayName("正好 20 条：全部可见，hiddenCount 仍为 0（上限是闭区间）")
    void twentyItemsAllVisible() {
        stubList(items(20, 1));
        ClipboardListResult res = service.list(USER, null, 80);
        assertEquals(20, res.items().size());
        assertEquals(0, res.hiddenCount());
    }

    @Test
    @DisplayName("21 条：只回 20 条，第 21 条计入 hiddenCount 而不是被删")
    void twentyOneItemsHidesOne() {
        stubList(items(21, 1));
        ClipboardListResult res = service.list(USER, null, 80);
        assertEquals(20, res.items().size());
        assertEquals(1, res.hiddenCount());
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("3 天边界：超过 3 天的记录不可见，即使总数不到 20 条")
    void retentionCutsBeforeCountLimit() {
        // 5 条，每条隔 24 小时：第 4、5 条（72h、96h 前）落在 3 天线外
        List<ClipboardItem> all = items(5, 24);
        stubList(all);
        ClipboardListResult res = service.list(USER, null, 80);
        // 严格早于 now-3d 的被滤掉；72h 前那条正好压线，isAfter(cutoff) 为 false
        assertTrue(res.items().size() <= 3, "3 天以外的记录不应可见，实际返回 " + res.items().size());
        assertTrue(res.items().stream()
                .allMatch(i -> i.getCreatedAt().isAfter(LocalDateTime.now().minusDays(3))));
        assertEquals(5 - res.items().size(), res.hiddenCount(),
                "总数减去 3 天内可见数即为被额度挡住的条数");
    }

    @Test
    @DisplayName("两条额度同时生效：30 条且只有 25 条在 3 天内 → 可见 20，隐藏 10")
    void bothLimitsApplyTakingStricter() {
        List<ClipboardItem> all = new ArrayList<>(items(25, 1));       // 25 条在 3 天内
        List<ClipboardItem> old = items(5, 1);                          // 另外 5 条挪到 10 天前
        old.forEach(i -> i.setCreatedAt(LocalDateTime.now().minusDays(10)));
        all.addAll(old);
        stubList(all);

        ClipboardListResult res = service.list(USER, null, 80);
        assertEquals(20, res.items().size());
        assertEquals(10, res.hiddenCount(), "30 总数 - min(25 条 3 天内, 20 上限) = 10");
    }

    @Test
    @DisplayName("hiddenCount 不把分页 limit 挡住的算作额度隐藏")
    void pagingLimitIsNotCountedAsQuotaHidden() {
        stubList(items(25, 1));
        ClipboardListResult res = service.list(USER, null, 5); // 前端只要 5 条
        assertEquals(5, res.items().size());
        assertEquals(5, res.hiddenCount(),
                "25 - min(25, 20) = 5：另外 15 条是分页挡的，付费用户同样看不到，不能算进解锁提示");
    }

    @Test
    @DisplayName("拥有 clipboard.unlimited：不过滤、不截断、不标记受限")
    void unlimitedReturnsEverything() {
        when(entitlementService.isEnabled(FeatureCatalog.CLIPBOARD_UNLIMITED)).thenReturn(true);
        List<ClipboardItem> all = items(50, 24); // 大半在 3 天以外
        stubList(all);

        ClipboardListResult res = service.list(USER, null, 80);
        assertEquals(50, res.items().size(), "解锁后此前被隐藏的历史记录必须原样出现");
        assertFalse(res.limited());
        assertEquals(0, res.hiddenCount());
        assertNull(res.maxItems());
        assertNull(res.retentionDays());
    }

    @Test
    @DisplayName("搜索场景走 search 计数，额度同样只过滤不删除")
    void searchPathAlsoQuotaFiltered() {
        List<ClipboardItem> all = items(21, 1);
        when(repository.search(eq(USER), eq("合同"), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable p = inv.getArgument(2);
                    return all.subList(0, Math.min(all.size(), p.getPageSize()));
                });
        when(repository.countSearch(USER, "合同")).thenReturn(21L);
        when(repository.countSearchAfter(eq(USER), eq("合同"), any(LocalDateTime.class))).thenReturn(21L);

        ClipboardListResult res = service.list(USER, "合同", 80);
        assertEquals(20, res.items().size());
        assertEquals(1, res.hiddenCount());
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("空库：可见 0 条，隐藏 0 条")
    void emptyLibrary() {
        stubList(List.of());
        ClipboardListResult res = service.list(USER, null, 80);
        assertEquals(0, res.items().size());
        assertEquals(0, res.hiddenCount());
    }
}
