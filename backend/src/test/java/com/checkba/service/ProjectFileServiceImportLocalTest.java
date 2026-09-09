// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.storage.LocalFileStorageService;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「拖入 = 复制进项目目录」的落盘语义（dev-board#409，H2 真库 + 真本地存储）。
 *
 * 用真的 LocalFileStorageService 而不是 mock：这条通道的全部价值就在于
 * 「字节真的到了项目目录里、大小对得上」，mock 掉存储等于什么都没验。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:import-local-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectFileServiceImportLocalTest {

    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectFileRepository projectFileRepository;

    private ProjectFileService svc;
    private Path projectRoot;
    private Long projectId;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        projectRoot = tmp.resolve("案卷");
        Project p = new Project();
        p.setName("案卷");
        p.setProjectType("BLANK");
        p.setListedCompanyName("");
        p.setTargetCompanyName("");
        p.setUserId(1L);
        p.setLocalRoot(projectRoot.toAbsolutePath().toString());
        projectId = projectRepository.save(p).getId();

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(tmp.resolve("appdata").toAbsolutePath().toString());
        // 模板文件刻意指向不存在的路径：createFromTemplate 会退化为「建一个空文件」，
        // 正好是本用例要覆盖的形态（随后被真正的字节覆盖）
        props.getLocal().setTemplatePath(tmp.resolve("no-such-template.docx").toAbsolutePath().toString());
        ProjectStorageResolver resolver = new ProjectStorageResolver(props, projectRepository);

        StorageServiceFactory factory = mock(StorageServiceFactory.class);
        when(factory.getStorageService()).thenReturn(new LocalFileStorageService(resolver));

        svc = new ProjectFileService(
                projectFileRepository,
                mock(com.checkba.service.ai.ProjectRagService.class),
                factory,
                mock(com.checkba.version.WorkSessionService.class),
                mock(UserService.class),
                mock(com.checkba.service.quota.StageQuotaService.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.evidence.EvidenceLinkService.class));
    }

    @Test
    void copiesBytesIntoProjectFolderAndReturnsRow(@TempDir Path srcDir) throws Exception {
        Path source = srcDir.resolve("证据.pdf");
        byte[] bytes = "PDF-BYTES-证据".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, bytes);

        ProjectFile row = svc.importLocalFile(projectId, null, source.toAbsolutePath().toString(), 1L);

        assertEquals("证据.pdf", row.getName());
        assertEquals("pdf", row.getFileType());
        assertEquals(bytes.length, row.getFileSize());
        assertEquals("projects/" + projectId + "/证据.pdf", row.getFilePath());
        assertFalse(Boolean.TRUE.equals(row.getIsFolder()));
        assertNotNull(row.getWpsFileId());

        Path copied = projectRoot.resolve("证据.pdf");
        assertTrue(Files.exists(copied), "字节必须真的落在项目目录里: " + copied);
        assertArrayEquals(bytes, Files.readAllBytes(copied));
    }

    /** 单文件入口仍然只收普通文件——目录走 {@link ProjectFileService#importLocalPath}。 */
    @Test
    void singleFileEntryPointStillRejectsDirectory(@TempDir Path srcDir) throws Exception {
        Path dir = srcDir.resolve("一整个文件夹");
        Files.createDirectories(dir);
        assertThrows(IllegalArgumentException.class,
                () -> svc.importLocalFile(projectId, null, dir.toAbsolutePath().toString(), 1L));
        assertTrue(projectFileRepository.findByProjectId(projectId).isEmpty(), "不许留下任何行");
    }

    @Test
    void rejectsMissingFile(@TempDir Path srcDir) {
        Path missing = srcDir.resolve("不存在.docx");
        assertThrows(IllegalArgumentException.class,
                () -> svc.importLocalFile(projectId, null, missing.toAbsolutePath().toString(), 1L));
        assertTrue(projectFileRepository.findByProjectId(projectId).isEmpty());
    }

    @Test
    void rejectsRelativePath() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.importLocalFile(projectId, null, "证据.pdf", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> svc.importLocalFile(projectId, null, "  ", 1L));
    }

    /** 同名处置与普通上传（REST createFile）逐字一致：报错，不静默改名也不覆盖。 */
    @Test
    void duplicateNameFailsLikeANormalUpload(@TempDir Path srcDir) throws Exception {
        Path source = srcDir.resolve("合同.docx");
        Files.writeString(source, "v1");
        svc.importLocalFile(projectId, null, source.toAbsolutePath().toString(), 1L);

        Files.writeString(source, "v2");
        assertThrows(IllegalArgumentException.class,
                () -> svc.importLocalFile(projectId, null, source.toAbsolutePath().toString(), 1L));
        assertEquals("v1", Files.readString(projectRoot.resolve("合同.docx")), "原件不许被盖掉");
    }

    // ---- 目录导入（拖整个文件夹进资源管理器） ----

    @Test
    void importsNestedDirectoryTree(@TempDir Path srcDir) throws Exception {
        Path root = srcDir.resolve("卷宗");
        Path sub = root.resolve("证据");
        Files.createDirectories(sub);
        byte[] topBytes = "TOP".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] subBytes = "SUB-证据".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(root.resolve("起诉状.docx"), topBytes);
        Files.write(sub.resolve("合同.pdf"), subBytes);

        ProjectFileService.ImportLocalResult result =
                svc.importLocalPath(projectId, null, root.toAbsolutePath().toString(), 1L);

        assertTrue(Boolean.TRUE.equals(result.getRoot().getIsFolder()), "顶层返回的是文件夹行");
        assertEquals("卷宗", result.getRoot().getName());
        assertEquals(2, result.getImportedFiles().size());
        assertEquals(0, result.getSkippedCount());

        ProjectFile subFolder = projectFileRepository.findByProjectId(projectId).stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsFolder()) && "证据".equals(f.getName()))
                .findFirst().orElseThrow();
        assertEquals(result.getRoot().getId(), subFolder.getParentId());

        assertArrayEquals(topBytes, Files.readAllBytes(projectRoot.resolve("卷宗/起诉状.docx")));
        assertArrayEquals(subBytes, Files.readAllBytes(projectRoot.resolve("卷宗/证据/合同.pdf")));
        assertEquals("projects/" + projectId + "/卷宗/证据/合同.pdf",
                result.getImportedFiles().stream()
                        .filter(f -> "合同.pdf".equals(f.getName())).findFirst().orElseThrow().getFilePath());
    }

    @Test
    void emptyDirectoryImportsAsEmptyFolder(@TempDir Path srcDir) throws Exception {
        Path root = srcDir.resolve("空文件夹");
        Files.createDirectories(root);

        ProjectFileService.ImportLocalResult result =
                svc.importLocalPath(projectId, null, root.toAbsolutePath().toString(), 1L);

        assertTrue(Boolean.TRUE.equals(result.getRoot().getIsFolder()));
        assertEquals(0, result.getImportedFiles().size());
        assertEquals(0, result.getSkippedCount());
        assertEquals(1, projectFileRepository.findByProjectId(projectId).size());
    }

    /** 顶层同名与单文件同名一条规则：报错，不改名不覆盖，一个字节都不复制。 */
    @Test
    void duplicateTopLevelFolderNameFailsAndCopiesNothing(@TempDir Path srcDir) throws Exception {
        svc.createFolder(projectId, null, "卷宗", 1L);
        Path root = srcDir.resolve("卷宗");
        Files.createDirectories(root);
        Files.writeString(root.resolve("起诉状.docx"), "v1");

        assertThrows(IllegalArgumentException.class,
                () -> svc.importLocalPath(projectId, null, root.toAbsolutePath().toString(), 1L));

        assertEquals(1, projectFileRepository.findByProjectId(projectId).size(), "只剩那个先建的文件夹行");
        assertFalse(Files.exists(projectRoot.resolve("卷宗/起诉状.docx")), "不许复制任何字节");
    }

    @Test
    void skipsSymlinkInsideTree(@TempDir Path srcDir) throws Exception {
        Path root = srcDir.resolve("卷宗");
        Files.createDirectories(root);
        Path real = srcDir.resolve("外部机密.txt");
        Files.writeString(real, "SECRET");
        Files.writeString(root.resolve("正常.txt"), "OK");
        Files.createSymbolicLink(root.resolve("链接.txt"), real);

        ProjectFileService.ImportLocalResult result =
                svc.importLocalPath(projectId, null, root.toAbsolutePath().toString(), 1L);

        assertEquals(1, result.getImportedFiles().size());
        assertEquals(1, result.getSkippedCount());
        assertFalse(Files.exists(projectRoot.resolve("卷宗/链接.txt")), "符号链接不许被跟随复制进来");
        assertTrue(Files.exists(projectRoot.resolve("卷宗/正常.txt")));
    }

    /** .awd/.git 是项目自己的元数据目录，与磁盘扫描（LocalProjectService）同一条跳过规则。 */
    @Test
    void skipsMetadataDirectories(@TempDir Path srcDir) throws Exception {
        Path root = srcDir.resolve("卷宗");
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve(".awd"));
        Files.writeString(root.resolve(".git/HEAD"), "ref: x");
        Files.writeString(root.resolve(".awd/tree.json"), "{}");
        Files.writeString(root.resolve("正常.txt"), "OK");

        ProjectFileService.ImportLocalResult result =
                svc.importLocalPath(projectId, null, root.toAbsolutePath().toString(), 1L);

        assertEquals(1, result.getImportedFiles().size());
        assertFalse(Files.exists(projectRoot.resolve("卷宗/.git")));
        assertFalse(Files.exists(projectRoot.resolve("卷宗/.awd")));
    }

    /** 额度按整棵树的总字节先算再拦：拦住时一个文件都不许落盘。 */
    @Test
    void quotaExceededRejectsBeforeCopying(@TempDir Path srcDir) throws Exception {
        ProjectFile huge = new ProjectFile();
        huge.setProjectId(projectId);
        huge.setParentId(null);
        huge.setIsFolder(false);
        huge.setName("既有大文件.bin");
        huge.setFileSize(20L * 1024 * 1024 * 1024 - 1);
        huge.setIsDeleted(false);
        huge.setSortOrder(0);
        huge.setUserId(1L);
        huge.setCreatedAt(java.time.LocalDateTime.now());
        huge.setUpdatedAt(java.time.LocalDateTime.now());
        projectFileRepository.save(huge);

        Path root = srcDir.resolve("卷宗");
        Files.createDirectories(root);
        Files.writeString(root.resolve("a.txt"), "AAAA");
        Files.writeString(root.resolve("b.txt"), "BBBB");

        assertThrows(IllegalArgumentException.class,
                () -> svc.importLocalPath(projectId, null, root.toAbsolutePath().toString(), 1L));

        assertEquals(1, projectFileRepository.findByProjectId(projectId).size(), "连顶层文件夹行都不该建");
        assertFalse(Files.exists(projectRoot.resolve("卷宗")), "一个字节都不许落盘");
    }
}
