package com.checkba.service;

import com.checkba.model.dto.SearchRequest;
import com.checkba.model.dto.SearchResult;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.FileTagRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.TagRepository;
import com.checkba.storage.StorageException;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 全文搜索对 PDF / docx 的抽取能力。
 *
 * 根因回归：此前 ContentSearchService 自建 {@code new Tika()} 直接解析 PDF，
 * 但项目 classpath 锁定 PDFBox 3.0.1，Tika 2.9.1 的 PDFParser 调 PDFBox 2.x
 * 已删除的 PDDocument.load 会抛 NoSuchMethodError（Error，非 Exception），
 * 穿透 searchContent 逐文件循环里的 catch(Exception)，一个 PDF 就能让整个
 * 搜索请求 500。修复后改为委托 {@link DocumentTextService}（PDF 走 PDFBox3
 * 原生 API），并把逐文件 catch 收紧为 catch(Throwable) 做防御。
 */
class ContentSearchServiceTest {

    private ProjectFileRepository projectFileRepository;
    private FileTagRepository fileTagRepository;
    private TagRepository tagRepository;
    private StorageService storageService;
    private ContentSearchService service;

    private static final long PROJECT_ID = 1L;

    @BeforeEach
    void setUp() {
        projectFileRepository = mock(ProjectFileRepository.class);
        fileTagRepository = mock(FileTagRepository.class);
        tagRepository = mock(TagRepository.class);
        storageService = mock(StorageService.class);

        StorageServiceFactory storageServiceFactory = mock(StorageServiceFactory.class);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);

        DocumentTextService documentTextService = new DocumentTextService(storageServiceFactory);
        service = new ContentSearchService(projectFileRepository, fileTagRepository, tagRepository, documentTextService);
    }

    private static ProjectFile file(long id, String name, String fileType, String filePath) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(PROJECT_ID);
        f.setName(name);
        f.setFileType(fileType);
        f.setFilePath(filePath);
        f.setIsDeleted(false);
        f.setSortOrder(0);
        return f;
    }

    private static byte[] pdfBytes(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
        }
        return out.toByteArray();
    }

    private static byte[] docxBytes(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(text);
            doc.write(out);
        }
        return out.toByteArray();
    }

    private static SearchRequest query(String q) {
        SearchRequest req = new SearchRequest();
        req.setQuery(q);
        return req;
    }

    @Test
    @DisplayName("PDF 全文命中：不再因 Tika/PDFBox3 不兼容而抛 NoSuchMethodError")
    void searchesPdfContent() throws Exception {
        ProjectFile pdf = file(10L, "contract.pdf", "pdf", "projects/1/contract.pdf");
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT_ID))
                .thenReturn(List.of(pdf));
        when(storageService.exists("projects/1/contract.pdf")).thenReturn(true);
        when(storageService.load("projects/1/contract.pdf"))
                .thenReturn(new ByteArrayResource(pdfBytes("Shareholders Meeting Notice 2026")));

        SearchResult result = service.searchContent(PROJECT_ID, query("Shareholders"));

        assertEquals(1, result.getTotalFiles(), "应命中该 PDF");
        assertEquals(1, result.getResults().get(0).getMatchCount());
    }

    @Test
    @DisplayName("docx 全文命中")
    void searchesDocxContent() throws Exception {
        ProjectFile docx = file(11L, "diligence.docx", "docx", "projects/1/diligence.docx");
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT_ID))
                .thenReturn(List.of(docx));
        when(storageService.exists("projects/1/diligence.docx")).thenReturn(true);
        when(storageService.load("projects/1/diligence.docx"))
                .thenReturn(new ByteArrayResource(docxBytes("尽职调查报告初稿")));

        SearchResult result = service.searchContent(PROJECT_ID, query("尽职调查"));

        assertEquals(1, result.getTotalFiles(), "应命中该 docx");
        assertEquals(1, result.getResults().get(0).getMatchCount());
    }

    @Test
    @DisplayName("一个文件抽取失败不影响其它文件命中，也不让整个搜索请求异常")
    void oneBrokenFileDoesNotBreakOthers() throws Exception {
        ProjectFile broken = file(12L, "broken.pdf", "pdf", "projects/1/broken.pdf");
        ProjectFile good = file(13L, "notice.pdf", "pdf", "projects/1/notice.pdf");
        when(projectFileRepository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(PROJECT_ID))
                .thenReturn(List.of(broken, good));

        when(storageService.exists("projects/1/broken.pdf")).thenReturn(true);
        when(storageService.load(eq("projects/1/broken.pdf")))
                .thenThrow(new StorageException("模拟损坏/不可读文件"));

        when(storageService.exists("projects/1/notice.pdf")).thenReturn(true);
        when(storageService.load(eq("projects/1/notice.pdf")))
                .thenReturn(new ByteArrayResource(pdfBytes("Board Resolution Notice")));

        SearchResult result = assertDoesNotThrow(() -> service.searchContent(PROJECT_ID, query("Resolution")));

        assertEquals(1, result.getTotalFiles(), "损坏文件应被跳过，只有健康文件命中");
        assertEquals("notice.pdf", result.getResults().get(0).getFileName());
    }
}
