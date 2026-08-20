package com.checkba.service;

import com.checkba.model.entity.DdItem;
import com.checkba.repository.DdItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 尽调清单的移动必须挡住「移到自己的子孙下面」。
 *
 * <p>病灶：{@code moveItem} 只判了 {@code itemId.equals(newParentId)}（移到自己身上），
 * 旁边留着一句 {@code // TODO: check deeper loops if needed}。把 A 拖到 A 的某个子孙 B 下面，
 * 就成了环（A.parent=B，而 B 在 A 的子树里）。
 *
 * <p>成环之后凡是顺着父子关系走的代码都会打转：{@code deleteItem} 的递归删子项直接
 * StackOverflowError，前端建树也拼不出来。更糟的是这条坏数据**存进库里就再也移不回来**——
 * 每次修复操作都先撞上死循环。而「把父节点拖到自己的子节点上」是用户拖拽时最容易误操作的一种。
 */
class DdMoveItemCycleTest {

    private DdItemRepository items;
    private DdService service;
    private final Map<Long, DdItem> store = new HashMap<>();

    private DdItem item(long id, Long parentId, int level) {
        DdItem i = new DdItem();
        i.setId(id);
        i.setParentId(parentId);
        i.setLevel(level);
        store.put(id, i);
        return i;
    }

    @BeforeEach
    void setUp() {
        store.clear();
        items = Mockito.mock(DdItemRepository.class);
        when(items.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, Long.class))));
        when(items.save(any(DdItem.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new DdService(null, items, null, null, null, null);
    }

    @Test
    @DisplayName("把父节点移到自己的子节点下面：必须拒绝（原来只挡直接自指）")
    void movingOntoADirectChildIsRejected() {
        item(1L, null, 0);          // A
        item(2L, 1L, 1);            // B，A 的子节点

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.moveItem(1L, 2L));
        assertTrue(e.getMessage().contains("子项"), "实际是：" + e.getMessage());
        assertNull(store.get(1L).getParentId(), "拒绝之后不许把 parentId 改坏");
    }

    @Test
    @DisplayName("移到更深一层的子孙下面同样拒绝——TODO 说的就是这一种")
    void movingOntoADeepDescendantIsRejected() {
        item(1L, null, 0);          // A
        item(2L, 1L, 1);            // B
        item(3L, 2L, 2);            // C
        item(4L, 3L, 3);            // D，A 的曾孙

        assertThrows(IllegalArgumentException.class, () -> service.moveItem(1L, 4L));
    }

    @Test
    @DisplayName("移到自己身上：既有判断保持不变")
    void movingOntoItselfStillRejected() {
        item(1L, null, 0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.moveItem(1L, 1L));
        assertTrue(e.getMessage().contains("自己"), "实际是：" + e.getMessage());
    }

    @Test
    @DisplayName("移到无关分支：正常放行，层级跟着新父节点走")
    void movingToAnUnrelatedBranchStillWorks() {
        item(1L, null, 0);          // A
        item(2L, 1L, 1);            // B（A 的子节点）
        item(9L, null, 0);          // X，另一棵树

        DdItem moved = service.moveItem(2L, 9L);

        assertEquals(9L, moved.getParentId());
        assertEquals(1, moved.getLevel(), "层级要按新父节点重算");
    }

    @Test
    @DisplayName("移到根：正常放行")
    void movingToRootStillWorks() {
        item(1L, null, 0);
        item(2L, 1L, 1);

        DdItem moved = service.moveItem(2L, null);

        assertNull(moved.getParentId());
        assertEquals(0, moved.getLevel());
    }

    @Test
    @DisplayName("库里已有历史环数据：环检测自己不许跟着打转，要给出可读错误")
    void preExistingCycleDoesNotHangTheCheck() {
        // 历史坏数据：1 <-> 2 互为父子
        item(1L, 2L, 1);
        item(2L, 1L, 1);
        item(3L, null, 0);

        assertThrows(IllegalArgumentException.class, () -> service.moveItem(3L, 2L));
    }
}
