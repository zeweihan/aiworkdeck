// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.PdfEditService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.storage.ProjectStorageResolver;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PdfTools} 页级操作的接线层（dev-board#805）。
 *
 * <p>服务层用例（{@code PdfPageOpsTest}）证明 PDFBox 那一侧对，这里证明<b>工具这一侧</b>对：
 * 产物真的登记进了项目文件树、落在原件所在目录、原件没被动过、返回文案里带得回 fileId，
 * 以及最要紧的一条——<b>模型点名别的项目的 fileId 时会被围栏挡住</b>。
 * PdfTools 此前完全没有这道围栏，而 pdf_merge 一次调用就能把另一个项目的 PDF 整份搬过来。
 */
class PdfToolsPageOpsTest {

    @TempDir
    Path tempDir;

    private static final long PROJECT_ID = 7L;

    private PdfTools tools;
    private Path projectRoot;
    private final Map<Long, ProjectFile> registry = new HashMap<>();
    private final AtomicLong nextId = new AtomicLong(100);

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = tempDir.resolve("project-" + PROJECT_ID);
        Files.createDirectories(projectRoot);

        ProjectFileService fileService = mock(ProjectFileService.class);
        ProjectFileRepository repository = mock(ProjectFileRepository.class);
        ProjectStorageResolver resolver = mock(ProjectStorageResolver.class);

        when(fileService.getFile(anyLong())).thenAnswer(inv -> registry.get(inv.getArgument(0, Long.class)));
        when(resolver.projectRoot(anyLong())).thenReturn(projectRoot);
        when(resolver.resolve(any())).thenAnswer(inv -> projectRoot.resolve(inv.getArgument(0, String.class)));
        when(repository.save(any(ProjectFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fileService.createFile(anyLong(), any(), any(), any(), any(), any(), any(), anyLong(), any()))
                .thenAnswer(inv -> {
                    ProjectFile created = new ProjectFile();
                    created.setId(nextId.incrementAndGet());
                    created.setProjectId(inv.getArgument(0, Long.class));
                    created.setParentId(inv.getArgument(1, Long.class));
                    created.setName(inv.getArgument(2, String.class));
                    created.setFileType("pdf");
                    created.setFilePath(inv.getArgument(2, String.class));
                    registry.put(created.getId(), created);
                    return created;
                });

        // editorBridgeService / aiDocxExportService / pptxServiceClient / packService 全传 null：
        // 页级操作一个都不该碰它们（收尾只建文件，刷文件树由编排器按 @ToolMeta(refreshFiles) 发）。
        // 哪天有人在这些工具里加了 sendReloadFileAction，这里会当场 NPE——这是有意的绊线。
        tools = new PdfTools(new PdfEditService(), fileService, repository,
                null, null, null, resolver, null);

        ProjectContextHolder.setProjectId(String.valueOf(PROJECT_ID));
    }

    @AfterEach
    void tearDown() {
        ProjectContextHolder.clear();
    }

    /**
     * 造一份 pages 页的 PDF 并登记成项目文件，返回 fileId。
     *
     * <p>文件名用中文（要验的就是中文名的派生命名），但<b>正文只写 ASCII</b>——
     * 夹具用的是 Helvetica，往里塞中文会在造夹具这一步就抛，把真正要验的东西全遮住。
     */
    private long givenPdf(String name, int pages, Long projectId, Long parentId) throws IOException {
        Path path = projectRoot.resolve(name);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Page " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(path.toFile());
        }
        ProjectFile pf = new ProjectFile();
        pf.setId(nextId.incrementAndGet());
        pf.setProjectId(projectId);
        pf.setParentId(parentId);
        pf.setName(name);
        pf.setFileType("pdf");
        pf.setFilePath(name);
        registry.put(pf.getId(), pf);
        return pf.getId();
    }

    private long givenPdf(String name, int pages) throws IOException {
        return givenPdf(name, pages, PROJECT_ID, 42L);
    }

    /** 从返回文案里挖出新文件 ID，顺便证明文案确实把 ID 报给了模型。 */
    private ProjectFile createdFrom(String output) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("文件 ID: (\\d+)").matcher(output);
        assertTrue(m.find(), "返回文案里没有新文件 ID，模型没法接着用它：" + output);
        ProjectFile created = registry.get(Long.parseLong(m.group(1)));
        assertTrue(created != null, "新文件没有登记进文件树：" + output);
        return created;
    }

    private int pagesOf(ProjectFile file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(projectRoot.resolve(file.getFilePath()).toFile())) {
            return doc.getNumberOfPages();
        }
    }

    private int pagesOfSource(String name) throws IOException {
        try (PDDocument doc = Loader.loadPDF(projectRoot.resolve(name).toFile())) {
            return doc.getNumberOfPages();
        }
    }

    @Test
    @DisplayName("合并：产出新文件、落在第一份原件所在目录、两份原件都不动")
    void mergeProducesANewFileAndLeavesSourcesAlone() throws IOException {
        long a = givenPdf("证据一.pdf", 3);
        long b = givenPdf("证据二.pdf", 2);

        String output = tools.pdf_merge("[" + a + "," + b + "]", "证据合卷.pdf");

        ProjectFile created = createdFrom(output);
        assertEquals("证据合卷.pdf", created.getName());
        assertEquals(42L, created.getParentId(), "产物要落在原件所在的文件夹里");
        assertEquals(5, pagesOf(created));
        assertEquals(3, pagesOfSource("证据一.pdf"));
        assertEquals(2, pagesOfSource("证据二.pdf"));
    }

    @Test
    @DisplayName("合并会拒绝别的项目的 fileId：否则一次调用就把他人项目的 PDF 整份搬过来")
    void mergeRefusesFileFromAnotherProject() throws IOException {
        long mine = givenPdf("我的.pdf", 2);
        long theirs = givenPdf("别人的.pdf", 2, 99L, null);

        String output = tools.pdf_merge("[" + mine + "," + theirs + "]", "x.pdf");

        assertTrue(output.startsWith("Error:"), output);
        assertTrue(output.contains(String.valueOf(theirs)), output);
        assertFalse(output.contains("文件 ID: "), "被拒绝的调用不该产出任何文件：" + output);
    }

    @Test
    @DisplayName("合并少于两份时报错，不产出半成品")
    void mergeNeedsTwoSources() throws IOException {
        long a = givenPdf("单份.pdf", 2);
        String output = tools.pdf_merge("[" + a + "]", null);
        assertTrue(output.startsWith("Error:"), output);
    }

    @Test
    @DisplayName("拆分：每段一份文件，名字带页码范围，原件不动")
    void splitProducesOneFilePerSegment() throws IOException {
        long id = givenPdf("卷宗.pdf", 10);

        String output = tools.pdf_split(id, "1-3,5,8-");

        List<String> names = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("新文件『(.+?)』").matcher(output);
        while (m.find()) names.add(m.group(1));
        assertEquals(List.of("卷宗_第1-3页.pdf", "卷宗_第5页.pdf", "卷宗_第8-10页.pdf"), names, output);
        assertEquals(10, pagesOfSource("卷宗.pdf"));
    }

    @Test
    @DisplayName("提页：默认命名 <原名>_摘取.pdf，页数与范围一致")
    void extractUsesDerivedDefaultName() throws IOException {
        long id = givenPdf("大卷宗.pdf", 20);

        String output = tools.pdf_extract_pages(id, "10-12", null);

        ProjectFile created = createdFrom(output);
        assertEquals("大卷宗_摘取.pdf", created.getName());
        assertEquals(3, pagesOf(created));
        assertEquals(20, pagesOfSource("大卷宗.pdf"));
    }

    @Test
    @DisplayName("页码范围越界时报错，且不产出任何文件")
    void outOfRangeProducesNothing() throws IOException {
        long id = givenPdf("五页.pdf", 5);

        String output = tools.pdf_extract_pages(id, "4-9", null);

        assertTrue(output.startsWith("Error:"), output);
        assertTrue(output.contains("5"), output);
        assertFalse(output.contains("文件 ID: "), output);
    }

    @Test
    @DisplayName("删页：产物少掉指定页，原件不动")
    void deleteProducesShorterCopy() throws IOException {
        long id = givenPdf("含空白页.pdf", 6);

        String output = tools.pdf_delete_pages(id, "2,5", null);

        ProjectFile created = createdFrom(output);
        assertEquals("含空白页_删页.pdf", created.getName());
        assertEquals(4, pagesOf(created));
        assertEquals(6, pagesOfSource("含空白页.pdf"));
    }

    @Test
    @DisplayName("旋转：产物里指定页角度变了，原件角度不变")
    void rotateProducesRotatedCopy() throws IOException {
        long id = givenPdf("扫描件.pdf", 3);

        String output = tools.pdf_rotate_pages(id, "2", 90);

        ProjectFile created = createdFrom(output);
        try (PDDocument doc = Loader.loadPDF(projectRoot.resolve(created.getFilePath()).toFile())) {
            assertEquals(90, doc.getPage(1).getRotation());
            assertEquals(0, doc.getPage(0).getRotation());
        }
        try (PDDocument doc = Loader.loadPDF(projectRoot.resolve("扫描件.pdf").toFile())) {
            assertEquals(0, doc.getPage(1).getRotation(), "原件不能被改");
        }
    }

    @Test
    @DisplayName("旋转角度非法时报错，不产出文件")
    void rotateRejectsBadAngle() throws IOException {
        long id = givenPdf("扫描件.pdf", 2);
        String output = tools.pdf_rotate_pages(id, "1", 45);
        assertTrue(output.startsWith("Error:"), output);
        assertFalse(output.contains("文件 ID: "), output);
    }

    @Test
    @DisplayName("编页码：贝茨模板产出新文件，原件不动")
    void pageNumbersProduceANewFile() throws IOException {
        long id = givenPdf("质证材料.pdf", 4);

        String output = tools.pdf_add_page_numbers(id, "bottom-right", 1, "AWD{n:6}");

        ProjectFile created = createdFrom(output);
        assertEquals("质证材料_页码.pdf", created.getName());
        assertEquals(4, pagesOf(created));

        try (PDDocument doc = Loader.loadPDF(projectRoot.resolve(created.getFilePath()).toFile())) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            assertTrue(stripper.getText(doc).contains("AWD000004"), "末页没盖上贝茨号");
        }
    }

    @Test
    @DisplayName("模板缺 {n} 时报错，不产出文件")
    void pageNumberTemplateMustCarryThePlaceholder() throws IOException {
        long id = givenPdf("材料.pdf", 2);
        String output = tools.pdf_add_page_numbers(id, null, null, "证据一");
        assertTrue(output.startsWith("Error:"), output);
        assertFalse(output.contains("文件 ID: "), output);
    }

    @Test
    @DisplayName("不是 PDF 的文件、不存在的 ID 也走 Error: 前缀——ToolResult.success() 只认这个前缀")
    void everyFailurePathStartsWithTheErrorPrefix() {
        ProjectFile notPdf = new ProjectFile();
        notPdf.setId(555L);
        notPdf.setProjectId(PROJECT_ID);
        notPdf.setName("合同.docx");
        notPdf.setFilePath("合同.docx");
        registry.put(555L, notPdf);

        for (String output : List.of(
                tools.pdf_extract_pages(555L, "1", null),      // 不是 PDF
                tools.pdf_inspect(999L, null, null),           // 文件不存在
                tools.pdf_merge("不是JSON", null),              // 参数坏了
                tools.pdf_split(999L, "1"))) {
            assertTrue(output.startsWith("Error:"),
                    "失败返回没有 Error: 前缀，会被判成 SUCCESS 打上绿勾：" + output);
        }
    }

    @Test
    @DisplayName("模型给的输出名带路径分隔符时抹平：文件树里不该出现名字带斜杠的条目")
    void outputNameIsSanitised() throws IOException {
        long a = givenPdf("一.pdf", 1);
        long b = givenPdf("二.pdf", 1);

        String output = tools.pdf_merge("[" + a + "," + b + "]", "证据/合卷");

        ProjectFile created = createdFrom(output);
        assertEquals("证据_合卷.pdf", created.getName(), "斜杠没被抹掉，扩展名没补上");
    }

    @Test
    @DisplayName("失败的调用不留临时文件：孤儿文件既不在文件树里、用户也删不掉")
    void failedOperationsLeaveNoTempFiles() throws IOException {
        long id = givenPdf("材料.pdf", 3);

        tools.pdf_extract_pages(id, "9", null);
        tools.pdf_rotate_pages(id, "1", 45);
        tools.pdf_add_page_numbers(id, null, null, "无占位符");

        try (var files = Files.list(projectRoot)) {
            List<String> leftovers = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(".pdfpageop-"))
                    .toList();
            assertTrue(leftovers.isEmpty(), "留下了临时文件：" + leftovers);
        }
    }

    @Test
    @DisplayName("pdf_inspect 的 offset 透传到服务层，续读位点跟着回来")
    void inspectOffsetIsWiredThrough() throws IOException {
        long id = givenPdf("长文.pdf", 1);

        String head = tools.pdf_inspect(id, 0, null);
        assertTrue(head.contains("\"page_count\":1"), head);
        assertTrue(head.contains("Page 1"), head);

        // 单页正文 "Page 1" 共 6 个字符，从第 5 个字符起续读只剩 "1"
        String tail = tools.pdf_inspect(id, 0, 5);
        assertTrue(tail.contains("\"offset\":5"), tail);
        assertTrue(tail.contains("\"text\":\"1\""), tail);
    }

    @Test
    @DisplayName("别的项目的 fileId 在每个页操作上都被挡住，不只是合并")
    void everyPageOperationIsFenced() throws IOException {
        long theirs = givenPdf("别人的.pdf", 3, 99L, null);

        for (String output : List.of(
                tools.pdf_split(theirs, "1"),
                tools.pdf_extract_pages(theirs, "1", null),
                tools.pdf_delete_pages(theirs, "1", null),
                tools.pdf_rotate_pages(theirs, "1", 90),
                tools.pdf_add_page_numbers(theirs, null, null, "{n}"),
                tools.pdf_inspect(theirs, null, null))) {
            assertTrue(output.startsWith("Error:"), output);
            assertTrue(output.contains("does not belong"), output);
        }
    }
}
