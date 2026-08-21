package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 导入硬顶（dev-board#107 单元 F1）：生产上限 DEFAULT_MAX_IMPORT_ENTRIES=30000（提升自
 * 3000），超出仍要显式截断并报出 truncatedCount，不许静默丢文件。
 *
 * 上限本身用 setMaxImportEntriesForTest 覆盖成 50 再测：真去建 30001/3001 个文件曾经
 * 跑出几十分钟，不能进 CI（复核意见）。行为验证等价——importFolder 里判的是
 * "stats.imported >= maxImportEntries"，跟这个数字具体是 30000 还是 50 无关。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:local-project-import-cap-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class LocalProjectServiceImportCapTest {

    private static final int TEST_CAP = 50;

    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ProjectFileRepository projectFileRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private LocalProjectService svc;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(tmp.resolve("appdata").toAbsolutePath().toString());
        ProjectStorageResolver resolver = new ProjectStorageResolver(props, projectRepository);

        com.checkba.storage.StorageServiceFactory storageFactory = mock(com.checkba.storage.StorageServiceFactory.class);
        com.checkba.storage.StorageService storageService = mock(com.checkba.storage.StorageService.class);
        when(storageFactory.getStorageService()).thenReturn(storageService);
        ProjectFileService projectFileService = new ProjectFileService(
                projectFileRepository,
                mock(com.checkba.service.ai.ProjectRagService.class),
                storageFactory,
                mock(com.checkba.version.WorkSessionService.class),
                mock(UserService.class),
                mock(com.checkba.service.quota.StageQuotaService.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.evidence.EvidenceLinkService.class));

        ProjectMemberService memberService = mock(ProjectMemberService.class);
        when(memberService.hasReadPermission(anyLong(), anyLong())).thenReturn(true);

        svc = new LocalProjectService(projectRepository, projectMemberRepository,
                projectFileRepository, projectFileService, memberService, resolver,
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                transactionManager);
        svc.setMaxImportEntriesForTest(TEST_CAP);
    }

    @Test
    void overCapEntriesImportsCapAndReportsTruncatedCount(@TempDir Path folder) throws Exception {
        int total = TEST_CAP + 1; // 51
        for (int i = 0; i < total; i++) {
            Files.createFile(folder.resolve("f" + i + ".txt"));
        }

        LocalProjectService.OpenLocalResult r = svc.openLocalFolder(folder.toString(), false, null, null, 1L);

        assertTrue(r.truncated(), "超过上限必须报出截断");
        assertEquals(1, r.truncatedCount(), "51 项超出上限 50，未纳入的应正好是 1 项");
        assertEquals(TEST_CAP, r.importedCount(), "扫描/入库条目数必须封顶在上限");
        List<ProjectFile> rows = projectFileRepository.findByProjectId(r.project().getId());
        assertEquals(TEST_CAP, rows.size(), "入库行数必须封顶，多出的文件不许静默进库");
    }

    /**
     * #550 复核 M1：命中上限后被跳过的子树此前只记 1，前端却据此写「N 项未纳入」。
     * 现在对被跳过的子树做有界计数。布局：50 个根文件 + zz/（10 文件 + inner/ 20 文件），
     * 共 82 项；无论文件系统遍历顺序如何（先进目录还是先扫文件），未纳入的都应是 82-50=32。
     */
    @Test
    void skippedSubtreeIsCountedNotJustOne(@TempDir Path folder) throws Exception {
        for (int i = 0; i < TEST_CAP; i++) {
            Files.createFile(folder.resolve("f" + i + ".txt"));
        }
        Path zz = Files.createDirectory(folder.resolve("zz"));
        for (int i = 0; i < 10; i++) {
            Files.createFile(zz.resolve("z" + i + ".txt"));
        }
        Path inner = Files.createDirectory(zz.resolve("inner"));
        for (int i = 0; i < 20; i++) {
            Files.createFile(inner.resolve("i" + i + ".txt"));
        }
        Files.createFile(zz.resolve(".hidden")); // 隐藏项不入库也不计数

        LocalProjectService.OpenLocalResult r = svc.openLocalFolder(folder.toString(), false, null, null, 1L);

        assertTrue(r.truncated());
        assertEquals(TEST_CAP, r.importedCount());
        assertEquals(82 - TEST_CAP, r.truncatedCount(), "被跳过的子树要按实际条目数计，不能整棵只算 1");
        assertFalse(r.truncatedCountCapped());
    }

    /** M1：计数本身也有上限；撞上就停并标 truncatedCountCapped，前端改口「超过 N 项」。 */
    @Test
    void truncatedCountStopsAtCapAndFlagsIt(@TempDir Path folder) throws Exception {
        svc.setMaxImportEntriesForTest(0);      // 第一项就越限，zz/ 整棵走「跳过并计数」
        svc.setTruncatedCountCapForTest(10);
        Path zz = Files.createDirectory(folder.resolve("zz"));
        for (int i = 0; i < 30; i++) {
            Files.createFile(zz.resolve("z" + i + ".txt"));
        }

        LocalProjectService.OpenLocalResult r = svc.openLocalFolder(folder.toString(), false, null, null, 1L);

        assertTrue(r.truncated());
        assertEquals(10, r.truncatedCount(), "数到上限即停");
        assertTrue(r.truncatedCountCapped(), "撞了计数上限必须标出来，真实数字只会更大");
    }

    @Test
    void underCapEntriesAllImportedNoTruncation(@TempDir Path folder) throws Exception {
        int total = 30; // 明显小于上限 50
        for (int i = 0; i < total; i++) {
            Files.createFile(folder.resolve("f" + i + ".txt"));
        }

        LocalProjectService.OpenLocalResult r = svc.openLocalFolder(folder.toString(), false, null, null, 1L);

        assertFalse(r.truncated(), "30 项不到上限 50，不该截断");
        assertEquals(0, r.truncatedCount());
        assertEquals(total, r.importedCount());
        List<ProjectFile> rows = projectFileRepository.findByProjectId(r.project().getId());
        assertEquals(total, rows.size(), "30 项应全部入库");
    }
}
