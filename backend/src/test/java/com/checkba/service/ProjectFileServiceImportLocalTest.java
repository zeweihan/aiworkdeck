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

    @Test
    void rejectsDirectory(@TempDir Path srcDir) throws Exception {
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
}
