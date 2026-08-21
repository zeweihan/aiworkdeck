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
 * 导入硬顶（dev-board#107 单元 F1）：MAX_IMPORT_ENTRIES 从 3000 提到 30000，
 * 超出仍要显式截断并报出 truncatedCount，不许静默丢文件。
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
                mock(com.checkba.service.telemetry.TelemetryService.class));

        ProjectMemberService memberService = mock(ProjectMemberService.class);
        when(memberService.hasReadPermission(anyLong(), anyLong())).thenReturn(true);

        svc = new LocalProjectService(projectRepository, projectMemberRepository,
                projectFileRepository, projectFileService, memberService, resolver,
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                transactionManager);
    }

    @Test
    void over30000EntriesImportsCapAndReportsTruncatedCount(@TempDir Path folder) throws Exception {
        int total = LocalProjectService.MAX_IMPORT_ENTRIES + 1; // 30001
        for (int i = 0; i < total; i++) {
            Files.createFile(folder.resolve("f" + i + ".txt"));
        }

        LocalProjectService.OpenLocalResult r = svc.openLocalFolder(folder.toString(), false, null, null, 1L);

        assertTrue(r.truncated(), "超过 30000 项必须报出截断");
        assertEquals(LocalProjectService.MAX_IMPORT_ENTRIES, r.importedCount(),
                "扫描/入库条目数必须封顶在 MAX_IMPORT_ENTRIES");
        List<ProjectFile> rows = projectFileRepository.findByProjectId(r.project().getId());
        assertEquals(LocalProjectService.MAX_IMPORT_ENTRIES, rows.size(),
                "入库行数必须封顶在 30000，多出的文件不许静默进库");
    }

    @Test
    void under30000EntriesAllImportedNoTruncation(@TempDir Path folder) throws Exception {
        int total = 3001; // 超过旧上限 3000，验证新上限确实生效
        for (int i = 0; i < total; i++) {
            Files.createFile(folder.resolve("f" + i + ".txt"));
        }

        LocalProjectService.OpenLocalResult r = svc.openLocalFolder(folder.toString(), false, null, null, 1L);

        assertFalse(r.truncated(), "3001 项不到新上限 30000，不该截断");
        assertEquals(0, r.truncatedCount());
        assertEquals(total, r.importedCount());
        List<ProjectFile> rows = projectFileRepository.findByProjectId(r.project().getId());
        assertEquals(total, rows.size(), "3001 项应全部入库");
    }
}
