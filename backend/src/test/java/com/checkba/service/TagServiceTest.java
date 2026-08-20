package com.checkba.service;

import com.checkba.model.entity.Tag;
import com.checkba.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标签类型维度（dev-board #63）的服务层契约：type 白名单校验、getOrCreateTag 的
 * 复用-不改型语义与按类型默认色。TagRepository 为 mock。
 */
class TagServiceTest {

    private TagRepository tagRepository;
    private TagService tagService;

    @BeforeEach
    void setUp() {
        tagRepository = mock(TagRepository.class);
        tagService = new TagService(tagRepository);
    }

    @Test
    @DisplayName("createTag：type 为 null 或白名单内的值都放行")
    void createTagAllowsNullOrWhitelistedType() {
        when(tagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Tag t1 = tagService.createTag(1L, "标签A", "#000000", "desc", null);
        assertEquals(null, t1.getType());

        when(tagRepository.existsByProjectIdAndName(1L, "标签B")).thenReturn(false);
        Tag t2 = tagService.createTag(1L, "标签B", "#000000", "desc", "PARTY");
        assertEquals("PARTY", t2.getType());
    }

    @Test
    @DisplayName("createTag：非法 type 抛 IllegalArgumentException，不调用 repository")
    void createTagRejectsIllegalType() {
        assertThrows(IllegalArgumentException.class,
                () -> tagService.createTag(1L, "标签A", "#000000", "desc", "BOGUS"));
        verify(tagRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateTag：非法 type 抛 IllegalArgumentException，不调用 repository.findById")
    void updateTagRejectsIllegalType() {
        assertThrows(IllegalArgumentException.class,
                () -> tagService.updateTag(1L, 5L, "标签A", "#000000", "desc", "BOGUS"));
        verify(tagRepository, never()).findById(any());
    }

    @Test
    @DisplayName("updateTag：type 为 null 表示不改型，既有类型保持原样")
    void updateTagKeepsTypeWhenNull() {
        Tag existing = new Tag();
        existing.setId(5L);
        existing.setProjectId(1L);
        existing.setName("张三");
        existing.setType("PARTY");
        when(tagRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(tagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Tag result = tagService.updateTag(1L, 5L, "张三", "#000000", "desc", null);

        assertEquals("PARTY", result.getType(), "不带 type 的更新不许把标签抹回普通");
    }

    @Test
    @DisplayName("getOrCreateTag：已存在同名标签——原样返回，不改型、不调用 save")
    void getOrCreateTagReusesExistingWithoutRetyping() {
        Tag existing = new Tag();
        existing.setId(9L);
        existing.setProjectId(1L);
        existing.setName("李四");
        existing.setType("NORMAL");
        existing.setColor("#3B82F6");
        when(tagRepository.findByProjectIdAndName(1L, "李四")).thenReturn(Optional.of(existing));

        Tag result = tagService.getOrCreateTag(1L, "李四", "PARTY", null);

        assertSame(existing, result);
        assertEquals("NORMAL", result.getType(), "撞名不同型时必须复用既有标签、不改型");
        verify(tagRepository, never()).save(any());
    }

    @Test
    @DisplayName("getOrCreateTag：不存在则新建，按类型套默认色")
    void getOrCreateTagCreatesWithDefaultColorByType() {
        when(tagRepository.findByProjectIdAndName(1L, "张三")).thenReturn(Optional.empty());
        when(tagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Tag party = tagService.getOrCreateTag(1L, "张三", "PARTY", null);
        assertEquals("#B45309", party.getColor());
        assertEquals("PARTY", party.getType());
        assertFalse(party.getIsSystem());

        when(tagRepository.findByProjectIdAndName(1L, "违约责任")).thenReturn(Optional.empty());
        Tag issue = tagService.getOrCreateTag(1L, "违约责任", "ISSUE", null);
        assertEquals("#9B1C31", issue.getColor());

        when(tagRepository.findByProjectIdAndName(1L, "证据一")).thenReturn(Optional.empty());
        Tag normal = tagService.getOrCreateTag(1L, "证据一", "NORMAL", null);
        assertEquals("#3B82F6", normal.getColor());
    }

    @Test
    @DisplayName("getOrCreateTag：调用方传了 color 则用调用方的，不套默认色")
    void getOrCreateTagUsesCallerColorWhenProvided() {
        when(tagRepository.findByProjectIdAndName(1L, "张三")).thenReturn(Optional.empty());
        when(tagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Tag result = tagService.getOrCreateTag(1L, "张三", "PARTY", "#FF0000");

        assertEquals("#FF0000", result.getColor());
    }

    @Test
    @DisplayName("getOrCreateTag：非法 type 抛 IllegalArgumentException")
    void getOrCreateTagRejectsIllegalType() {
        assertThrows(IllegalArgumentException.class,
                () -> tagService.getOrCreateTag(1L, "张三", "BOGUS", null));
        verify(tagRepository, never()).save(any());
    }
}
