// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.config.AiContextProperties;
import com.checkba.model.dto.SearchRequest;
import com.checkba.model.dto.SearchResult;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectFileTextCache;
import com.checkba.repository.FileTagRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectFileTextCacheRepository;
import com.checkba.repository.TagRepository;
import com.checkba.service.file.ProjectFileTextCacheService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 全文搜索的性能根因（v0.49.0 真机 BUG-12 / C3-08）。
 *
 * <p>搜索每次请求都把项目里每个文件从头抽一遍，而且逐个串行：
 * 100 个文件一次搜索 8-15 秒，连续输入几次就排成几分钟的队。
 * DocumentTextService 里那层 32 条的 LRU 救不了它——按同一顺序扫 100 个文件，
 * LRU 在每个文件被复用之前就把它挤掉了，命中率是 0。
 *
 * <p>这里守四件事：第二次搜索不再重抽（按文件 id + 物理 mtime/size 命中）、
 * 文件改过就重抽、多个文件并行抽取且并发搜索结果正确、扫描件残渣不污染共享缓存。
 */
class ContentSearchServiceCacheTest {

    private static final long PROJECT_ID = 1L;
    /** 刻意大于 DocumentTextService 内存 LRU 的 32 条，让那一层没法替缓存兜底。 */
    private static final int FILE_COUNT = 40;

    private ProjectFileRepository projectFileRepository;
    private StorageService storageService;
    private ContentSearchService service;

    /** 文件被真正打开字节流（= 真抽取一次）的次数。 */
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile long extractDelayMs = 0;

    /** 线程安全的假 project_file_text_cache：并发用例要它自己先不坏。 */
    private final Map<Long, ProjectFileTextCache> rows = new ConcurrentHashMap<>();

    /** 带物理 mtime 的内存文件；每次 getInputStream 记一次抽取，并统计同时在抽的个数。 */
    private class DiskLikeResource extends ByteArrayResource {
        private final long mtime;

        DiskLikeResource(byte[] bytes, long mtime) {
            super(bytes);
            this.mtime = mtime;
        }

        DiskLikeResource(String text, long mtime) {
            this(text.getBytes(StandardCharsets.UTF_8), mtime);
        }

        @Override
        public long lastModified() {
            return mtime;
        }

        @Override
        public InputStream getInputStream() {
            reads.incrementAndGet();
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                if (extractDelayMs > 0) {
                    Thread.sleep(extractDelayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return new ByteArrayInputStream(getByteArray());
        }
    }

    @BeforeEach
    void setUp() {
        projectFileRepository = mock(ProjectFileRepository.class);
        FileTagRepository fileTagRepository = mock(FileTagRepository.class);
        TagRepository tagRepository = mock(TagRepository.class);
        storageService = mock(StorageService.class);
        StorageServiceFactory storageServiceFactory = mock(StorageServiceFactory.class);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);

        ProjectFileTextCacheRepository cacheRepo = mock(ProjectFileTextCacheRepository.class);
        AtomicInteger seq = new AtomicInteger();
        when(cacheRepo.findByFileId(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<Long>getArgument(0))));
        when(cacheRepo.save(any(ProjectFileTextCache.class))).thenAnswer(inv -> {
            ProjectFileTextCache row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId((long) seq.incrementAndGet());
            }
            rows.put(row.getFileId(), row);
            return row;
        });
        doAnswer(inv -> rows.remove(inv.<ProjectFileTextCache>getArgument(0).getFileId()))
                .when(cacheRepo).delete(any(ProjectFileTextCache.class));
        when(cacheRepo.count()).thenAnswer(inv -> (long) rows.size());

        DocumentTextService documentTextService = new DocumentTextService(storageServiceFactory);
        ProjectFileTextCacheService textCache =
                new ProjectFileTextCacheService(cacheRepo, new AiContextProperties());
        service = new ContentSearchService(projectFileRepository, fileTagRepository, tagRepository,
                documentTextService, textCache);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static ProjectFile file(long id, String name, String type) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(PROJECT_ID);
        f.setName(name);
        f.setFileType(type);
        f.setFilePath("projects/1/" + name);
        f.setIsDeleted(false);
        f.setSortOrder((int) id);
        return f;
    }

    /** 偶数号文件含「借款合同」，奇数号含「银行流水」。 */
    private List<ProjectFile> seedTxtFiles(int n) {
        List<ProjectFile> files = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            ProjectFile f = file(i, "evidence-" + i + ".txt", "txt");
            files.add(f);
            String text = (i % 2 == 0 ? "第" + i + "号证据：借款合同原件一份\n" : "第" + i + "号证据：银行流水\n")
                    + "补充说明 " + i;
            when(storageService.load(f.getFilePath())).thenReturn(new DiskLikeResource(text, 1_000L + i));
        }
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT_ID))
                .thenReturn(files);
        return files;
    }

    private static SearchRequest query(String q) {
        SearchRequest req = new SearchRequest();
        req.setQuery(q);
        return req;
    }

    @Test
    @DisplayName("第二次搜索命中抽取缓存：文件没改就一个都不重抽")
    void secondSearchDoesNotReExtract() {
        seedTxtFiles(FILE_COUNT);

        SearchResult first = service.searchContent(PROJECT_ID, query("借款合同"));
        assertEquals(FILE_COUNT / 2, first.getTotalFiles());
        assertEquals(FILE_COUNT, reads.get(), "第一次搜索每个文件抽一次");

        reads.set(0);
        SearchResult second = service.searchContent(PROJECT_ID, query("银行流水"));

        assertEquals(FILE_COUNT / 2, second.getTotalFiles(), "换了查询词，结果必须照样对");
        assertEquals(0, reads.get(), "文件一个字节都没变，第二次搜索不该再抽取任何文件");
    }

    @Test
    @DisplayName("文件改过（mtime 变）就重抽那一份，搜到的是新内容，其余照旧命中缓存")
    void changedFileIsReExtracted() {
        List<ProjectFile> files = seedTxtFiles(FILE_COUNT);
        service.searchContent(PROJECT_ID, query("借款合同"));

        ProjectFile edited = files.get(0); // id=1，原本不含「借款合同」
        when(storageService.load(edited.getFilePath()))
                .thenReturn(new DiskLikeResource("改过之后：补签借款合同", 9_999L));
        reads.set(0);

        SearchResult after = service.searchContent(PROJECT_ID, query("借款合同"));

        assertEquals(FILE_COUNT / 2 + 1, after.getTotalFiles(), "改过的文件必须按新内容命中");
        assertTrue(after.getResults().stream().anyMatch(r -> r.getFileId() == 1L));
        assertEquals(1, reads.get(), "只有改过的那一份需要重抽");
    }

    @Test
    @DisplayName("多个文件并行抽取，而不是一个接一个")
    void extractsFilesInParallel() {
        seedTxtFiles(8);
        extractDelayMs = 60;

        SearchResult result = service.searchContent(PROJECT_ID, query("借款合同"));

        assertEquals(4, result.getTotalFiles());
        assertTrue(maxInFlight.get() >= 2,
                "同一时刻至少应有两个文件在抽取，实际最大并发 " + maxInFlight.get());
    }

    @Test
    @DisplayName("结果顺序稳定：同匹配数的文件保持项目里的原有顺序")
    void resultOrderIsStable() {
        seedTxtFiles(FILE_COUNT);
        extractDelayMs = 3;

        SearchResult result = service.searchContent(PROJECT_ID, query("借款合同"));

        List<Long> ids = result.getResults().stream().map(r -> r.getFileId()).toList();
        List<Long> expected = new ArrayList<>();
        for (long i = 2; i <= FILE_COUNT; i += 2) {
            expected.add(i);
        }
        assertEquals(expected, ids, "并行之后结果顺序不能随线程调度乱跳");
    }

    @Test
    @DisplayName("并发的多次搜索（冷缓存起步）结果一致且完整，没有异常、没有丢文件")
    void concurrentSearchesAreSafe() throws Exception {
        seedTxtFiles(FILE_COUNT);
        extractDelayMs = 5;

        ExecutorService callers = Executors.newFixedThreadPool(6);
        try {
            List<Future<SearchResult>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String q = i % 2 == 0 ? "借款合同" : "银行流水";
                Callable<SearchResult> call = () -> service.searchContent(PROJECT_ID, query(q));
                futures.add(callers.submit(call));
            }
            for (Future<SearchResult> f : futures) {
                SearchResult r = f.get(30, TimeUnit.SECONDS);
                assertEquals(FILE_COUNT / 2, r.getTotalFiles(), "并发搜索不许丢文件或重复计数");
                assertEquals(FILE_COUNT / 2, r.getTotalMatches());
            }
        } finally {
            callers.shutdownNow();
        }
        assertEquals(FILE_COUNT, rows.size(), "每个文件最终都应落进缓存，且只占一行");

        reads.set(0);
        service.searchContent(PROJECT_ID, query("借款合同"));
        assertEquals(0, reads.get(), "并发写完缓存之后，再搜不该有任何重抽");
    }

    @Test
    @DisplayName("扫描件 PDF 的残留字符不写进共享缓存（否则 AI 读这份文件时会拿残渣顶替 OCR）")
    void unusablePdfTextLayerIsNotCached() throws IOException {
        ProjectFile pdf = file(99L, "scan.pdf", "pdf");
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT_ID))
                .thenReturn(List.of(pdf));
        when(storageService.load(pdf.getFilePath())).thenReturn(new DiskLikeResource(pdfWithText("p.1"), 5_000L));

        service.searchContent(PROJECT_ID, query("p.1"));

        assertFalse(rows.containsKey(99L), "文字层不可用的 PDF 不能以 text 来源写进共享缓存");
    }

    /** 一页只有几个残留字符的 PDF（扫描件常见：页码/水印）。 */
    private static byte[] pdfWithText(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
        }
        return out.toByteArray();
    }
}
