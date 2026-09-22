// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.file;

import com.checkba.config.AiContextProperties;
import com.checkba.model.entity.ProjectFileTextCache;
import com.checkba.repository.ProjectFileTextCacheRepository;
import com.checkba.service.DocumentTextService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 抽取结果的落库缓存（dev-board#800）：命中、按 mtime/size 失效、坏了也不能拖垮抽取。
 */
class ProjectFileTextCacheTest {

    /** 一个只有一张表那么大的假仓储：够用且不必起 Spring。 */
    static class FakeRepo implements AutoCloseable {
        final List<ProjectFileTextCache> rows = new ArrayList<>();
        final ProjectFileTextCacheRepository mock = Mockito.mock(ProjectFileTextCacheRepository.class);
        long seq = 0;

        FakeRepo() {
            Mockito.when(mock.findByFileId(Mockito.anyLong())).thenAnswer(inv -> {
                Long id = inv.getArgument(0);
                return rows.stream().filter(r -> id.equals(r.getFileId())).findFirst();
            });
            Mockito.when(mock.save(Mockito.any(ProjectFileTextCache.class))).thenAnswer(inv -> {
                ProjectFileTextCache row = inv.getArgument(0);
                if (row.getId() == null) {
                    row.setId(++seq);
                    rows.add(row);
                }
                return row;
            });
            Mockito.doAnswer(inv -> rows.remove(inv.<ProjectFileTextCache>getArgument(0)))
                    .when(mock).delete(Mockito.any(ProjectFileTextCache.class));
            Mockito.doAnswer(inv -> rows.removeAll(inv.<List<ProjectFileTextCache>>getArgument(0)))
                    .when(mock).deleteAll(Mockito.anyList());
            Mockito.when(mock.count()).thenAnswer(inv -> (long) rows.size());
            Mockito.when(mock.findOldest(Mockito.any())).thenAnswer(inv -> {
                org.springframework.data.domain.Pageable p = inv.getArgument(0);
                return rows.stream()
                        .sorted(Comparator.comparing(ProjectFileTextCache::getCreatedAt)
                                .thenComparing(ProjectFileTextCache::getId))
                        .limit(p.getPageSize())
                        .toList();
            });
        }

        @Override
        public void close() {
            rows.clear();
        }
    }

    private static final DocumentTextService.FileStamp STAMP = new DocumentTextService.FileStamp(1000L, 4096L);

    @Test
    @DisplayName("写一次、读一次：同一指纹命中")
    void storeThenFind() {
        FakeRepo repo = new FakeRepo();
        ProjectFileTextCacheService svc = new ProjectFileTextCacheService(repo.mock, new AiContextProperties());

        assertNull(svc.find(7L, STAMP), "还没写过，必须未命中");
        svc.store(7L, STAMP, ProjectFileTextCache.SOURCE_OCR, "扫描件识别出来的正文");
        assertEquals("扫描件识别出来的正文", svc.find(7L, STAMP));
        assertEquals(ProjectFileTextCache.SOURCE_OCR, repo.rows.get(0).getSource());
    }

    @Test
    @DisplayName("文件改过（mtime 或 size 变）即失效，并就地删掉陈旧行")
    void staleStampInvalidates() {
        FakeRepo repo = new FakeRepo();
        ProjectFileTextCacheService svc = new ProjectFileTextCacheService(repo.mock, new AiContextProperties());
        svc.store(7L, STAMP, ProjectFileTextCache.SOURCE_TEXT, "第一版正文");

        assertNull(svc.find(7L, new DocumentTextService.FileStamp(2000L, 4096L)), "mtime 变了就不能再用");
        assertTrue(repo.rows.isEmpty(), "陈旧行要就地删掉，别占着唯一键");

        svc.store(7L, STAMP, ProjectFileTextCache.SOURCE_TEXT, "第一版正文");
        assertNull(svc.find(7L, new DocumentTextService.FileStamp(1000L, 9999L)), "size 变了同样不能用");
    }

    @Test
    @DisplayName("拿不到文件指纹时一律未命中，绝不给陈旧正文")
    void noStampMeansNoCache() {
        FakeRepo repo = new FakeRepo();
        ProjectFileTextCacheService svc = new ProjectFileTextCacheService(repo.mock, new AiContextProperties());
        svc.store(7L, STAMP, ProjectFileTextCache.SOURCE_TEXT, "正文");

        assertNull(svc.find(7L, null));
        svc.store(7L, null, ProjectFileTextCache.SOURCE_TEXT, "另一份正文");
        assertEquals(1, repo.rows.size(), "没有指纹就不写");
    }

    @Test
    @DisplayName("读回来的长度对不上（库把长文本截断了）就当没缓存，不能把半截正文当原文")
    void truncatedRowIsRejected() {
        FakeRepo repo = new FakeRepo();
        ProjectFileTextCacheService svc = new ProjectFileTextCacheService(repo.mock, new AiContextProperties());
        svc.store(7L, STAMP, ProjectFileTextCache.SOURCE_TEXT, "完整的一份合同正文，后半段很要紧");
        repo.rows.get(0).setTextContent("完整的一份合同正文");  // 模拟 MySQL TEXT 截断

        assertNull(svc.find(7L, STAMP));
        assertTrue(repo.rows.isEmpty());
    }

    @Test
    @DisplayName("超过单条上限的不缓存；总量超限按写入时间淘汰最旧的")
    void limitsAreEnforced() {
        FakeRepo repo = new FakeRepo();
        AiContextProperties props = new AiContextProperties();
        props.getTextCache().setMaxTextChars(10);
        props.getTextCache().setMaxEntries(2);
        ProjectFileTextCacheService svc = new ProjectFileTextCacheService(repo.mock, props);

        svc.store(1L, STAMP, ProjectFileTextCache.SOURCE_TEXT, "这段正文长度超过了十个字符的上限");
        assertTrue(repo.rows.isEmpty(), "超过单条上限的不入库");

        svc.store(1L, STAMP, ProjectFileTextCache.SOURCE_TEXT, "甲");
        repo.rows.get(0).setCreatedAt(LocalDateTime.now().minusDays(2));
        svc.store(2L, STAMP, ProjectFileTextCache.SOURCE_TEXT, "乙");
        svc.store(3L, STAMP, ProjectFileTextCache.SOURCE_TEXT, "丙");

        assertEquals(2, repo.rows.size());
        assertTrue(repo.rows.stream().noneMatch(r -> r.getFileId() == 1L), "最旧的那条被淘汰");
    }

    @Test
    @DisplayName("缓存层抛异常只能变慢，不能让抽取失败")
    void repositoryFailuresAreSwallowed() {
        ProjectFileTextCacheRepository broken = Mockito.mock(ProjectFileTextCacheRepository.class);
        Mockito.when(broken.findByFileId(Mockito.anyLong())).thenThrow(new RuntimeException("table missing"));
        Mockito.when(broken.save(Mockito.any())).thenThrow(new RuntimeException("data too long"));
        ProjectFileTextCacheService svc = new ProjectFileTextCacheService(broken, new AiContextProperties());

        assertNull(svc.find(7L, STAMP));
        assertDoesNotThrow(() -> svc.store(7L, STAMP, ProjectFileTextCache.SOURCE_TEXT, "正文"));
    }

    @Test
    @DisplayName("关掉开关就是回到每次重抽的旧行为")
    void disabledMeansNoCache() {
        FakeRepo repo = new FakeRepo();
        AiContextProperties props = new AiContextProperties();
        props.getTextCache().setEnabled(false);
        ProjectFileTextCacheService svc = new ProjectFileTextCacheService(repo.mock, props);

        svc.store(7L, STAMP, ProjectFileTextCache.SOURCE_TEXT, "正文");
        assertTrue(repo.rows.isEmpty());
        assertNull(svc.find(7L, STAMP));
        Mockito.verify(repo.mock, Mockito.never()).findByFileId(Mockito.anyLong());
    }

    @Test
    @DisplayName("空正文不缓存：这次没抽出来不是结论")
    void blankResultsAreNotCached() {
        FakeRepo repo = new FakeRepo();
        ProjectFileTextCacheService svc = new ProjectFileTextCacheService(repo.mock, new AiContextProperties());
        svc.store(7L, STAMP, ProjectFileTextCache.SOURCE_TEXT, "");
        assertTrue(repo.rows.isEmpty());
        assertEquals(Optional.empty(), repo.mock.findByFileId(7L));
    }
}
