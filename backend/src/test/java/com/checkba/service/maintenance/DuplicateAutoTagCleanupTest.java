package com.checkba.service.maintenance;

import com.checkba.model.entity.FileTag;
import com.checkba.model.entity.Tag;
import com.checkba.repository.FileTagRepository;
import com.checkba.repository.TagRepository;
import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 一次性去重的口径守卫。这几条断言就是「这次迁移到底动了谁」的定义，
 * 改动它之前先想清楚：这是一段会在**每一台存量机器上自动跑一次**的删数据代码。
 */
class DuplicateAutoTagCleanupTest {

    private static Tag autoTag(long id) {
        Tag t = new Tag();
        t.setId(id);
        t.setName("auto-" + id);
        t.setIsSystem(true);
        return t;
    }

    private static FileTag link(long id, long fileId, long tagId, int minuteOffset) {
        FileTag ft = new FileTag();
        ft.setId(id);
        ft.setFileId(fileId);
        ft.setTagId(tagId);
        ft.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(minuteOffset));
        return ft;
    }

    /** tagIds -> links，模拟 findByTagIdIn 的「按当前存活关联查」语义 */
    private static FileTagRepository repoWith(List<FileTag> links) {
        FileTagRepository repo = mock(FileTagRepository.class);
        List<FileTag> live = new ArrayList<>(links);
        when(repo.findByTagIdIn(anyList())).thenAnswer(inv -> {
            Set<Long> ids = Set.copyOf(inv.getArgument(0));
            return live.stream().filter(l -> ids.contains(l.getTagId())).collect(Collectors.toList());
        });
        doAnswer(inv -> {
            live.removeAll((List<FileTag>) inv.getArgument(0));
            return null;
        }).when(repo).deleteAll(any());
        return repo;
    }

    @Test
    @DisplayName("被 bug 弄坏的文件：只留最早 5 条，其余全删")
    void keepsEarliestFiveAndDropsTheRest() {
        // 一个文件挂了 12 条自动标签 = 打过至少三轮
        List<Tag> tags = new ArrayList<>();
        List<FileTag> links = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            tags.add(autoTag(i));
            // 故意乱序插入，验证排序按 createdAt 而不是遍历顺序
            links.add(link(100 + i, 7L, i, (13 - i) * 10));
        }
        TagRepository tagRepo = mock(TagRepository.class);
        when(tagRepo.findByIsSystemTrue()).thenReturn(tags);
        FileTagRepository ftRepo = repoWith(links);

        var result = new DuplicateAutoTagCleanup(tagRepo, ftRepo, mock(SystemSettingService.class)).cleanup();

        assertEquals(1, result.affectedFiles());
        assertEquals(7, result.removedLinks(), "12 条留 5 条");

        ArgumentCaptor<List<FileTag>> captor = ArgumentCaptor.forClass(List.class);
        verify(ftRepo).deleteAll(captor.capture());
        // 留下的必须是时间最早的那 5 条（tagId 12..8，因为 minuteOffset 与 i 反向）
        Set<Long> deletedTagIds = captor.getValue().stream().map(FileTag::getTagId).collect(Collectors.toSet());
        assertEquals(Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), deletedTagIds);
    }

    @Test
    @DisplayName("健康安装零变化：文件的自动标签不超过 5 个就一行不动")
    void leavesHealthyInstallUntouched() {
        List<Tag> tags = new ArrayList<>();
        List<FileTag> links = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            tags.add(autoTag(i));
            links.add(link(200 + i, 3L, i, i));
        }
        TagRepository tagRepo = mock(TagRepository.class);
        when(tagRepo.findByIsSystemTrue()).thenReturn(tags);
        FileTagRepository ftRepo = repoWith(links);

        var result = new DuplicateAutoTagCleanup(tagRepo, ftRepo, mock(SystemSettingService.class)).cleanup();

        assertEquals(0, result.affectedFiles());
        assertEquals(0, result.removedLinks());
        assertEquals(0, result.removedTags());
        verify(ftRepo, never()).deleteAll(any());
        verify(tagRepo, never()).deleteAllById(any());
    }

    @Test
    @DisplayName("删完关联后变成零引用的自动标签一并清掉，还挂在别处的留着")
    void dropsOrphanTagsButKeepsSharedOnes() {
        List<Tag> tags = new ArrayList<>();
        List<FileTag> links = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            tags.add(autoTag(i));
            links.add(link(300 + i, 9L, i, i));
        }
        // tag 8 同时挂在另一个（健康的）文件上：这次它的 fileId=9 关联被删，
        // 但它在 fileId=10 上还活着，不能跟着被清掉
        links.add(link(400, 10L, 8L, 1));

        TagRepository tagRepo = mock(TagRepository.class);
        when(tagRepo.findByIsSystemTrue()).thenReturn(tags);
        FileTagRepository ftRepo = repoWith(links);

        var result = new DuplicateAutoTagCleanup(tagRepo, ftRepo, mock(SystemSettingService.class)).cleanup();

        assertEquals(3, result.removedLinks(), "fileId=9 上 8 条留 5 条");
        ArgumentCaptor<Iterable<Long>> orphans = ArgumentCaptor.forClass(Iterable.class);
        verify(tagRepo).deleteAllById(orphans.capture());
        Set<Long> orphanIds = new java.util.HashSet<>();
        orphans.getValue().forEach(orphanIds::add);
        assertEquals(Set.of(6L, 7L), orphanIds, "tag 8 还挂在 fileId=10 上，必须留着");
        assertEquals(2, result.removedTags());
    }

    @Test
    @DisplayName("库里没有自动标签：直接返回，不发一条删除")
    void noAutoTagsIsANoop() {
        TagRepository tagRepo = mock(TagRepository.class);
        when(tagRepo.findByIsSystemTrue()).thenReturn(List.of());
        FileTagRepository ftRepo = mock(FileTagRepository.class);

        var result = new DuplicateAutoTagCleanup(tagRepo, ftRepo, mock(SystemSettingService.class)).cleanup();

        assertEquals(0, result.removedLinks());
        verifyNoInteractions(ftRepo);
    }

    @Test
    @DisplayName("已经跑过的机器不再跑第二遍")
    void runsOnlyOnce() {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(DuplicateAutoTagCleanup.DONE_KEY, null)).thenReturn("2026-08-17T18:00");
        TagRepository tagRepo = mock(TagRepository.class);

        new DuplicateAutoTagCleanup(tagRepo, mock(FileTagRepository.class), settings).onStartup();

        verifyNoInteractions(tagRepo);
        verify(settings, never()).set(any(), any());
    }
}
