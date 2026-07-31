package com.checkba.service;

import com.checkba.model.entity.Project;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * IDE 化本地文件夹项目的落地语义（H2 真库）：
 * - 打开即导入：文件夹现有内容进数据库文件树，隐藏项（.git/.awd/.DS_Store）跳过；
 * - 幂等：重复打开同一文件夹复用同一项目，不产生重复行（含根级 parentId=null 的查重）；
 * - 围栏：拒绝软件内部数据目录、拒绝与既有项目文件夹嵌套。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:local-project-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class LocalProjectServiceTest {

    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ProjectFileRepository projectFileRepository;

    private LocalProjectService svc;
    private Path globalRoot;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        globalRoot = tmp.resolve("appdata");
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(globalRoot.toAbsolutePath().toString());
        ProjectStorageResolver resolver = new ProjectStorageResolver(props, projectRepository);

        // ProjectFileService 的非仓库协作者全部无害化：RAG/存储/版本信号与导入语义无关
        com.checkba.storage.StorageServiceFactory storageFactory = mock(com.checkba.storage.StorageServiceFactory.class);
        com.checkba.storage.StorageService storageService = mock(com.checkba.storage.StorageService.class);
        when(storageFactory.getStorageService()).thenReturn(storageService);
        ProjectFileService projectFileService = new ProjectFileService(
                projectFileRepository,
                mock(com.checkba.service.ai.ProjectRagService.class),
                storageFactory,
                mock(com.checkba.version.WorkSessionService.class),
                mock(UserService.class));

        ProjectMemberService memberService = mock(ProjectMemberService.class);
        when(memberService.hasReadPermission(anyLong(), anyLong())).thenReturn(true);

        svc = new LocalProjectService(projectRepository, projectMemberRepository,
                projectFileRepository, projectFileService, memberService, resolver);
    }

    private Path userFolder(@TempDir Path tmp) {
        return tmp;
    }

    @Test
    void importsExistingContentAndSkipsHiddenEntries(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("合同.docx"), "x");
        Files.createDirectories(folder.resolve("sub"));
        Files.writeString(folder.resolve("sub/备忘录.txt"), "y");
        Files.createDirectories(folder.resolve(".git"));
        Files.writeString(folder.resolve(".git/config"), "z");
        Files.writeString(folder.resolve(".DS_Store"), "");

        LocalProjectService.OpenLocalResult r = svc.openLocalFolder(
                folder.toString(), false, null, "合同.docx", 1L);

        assertFalse(r.reused());
        assertNotNull(r.openFileId());
        assertEquals(folder.normalize().toString(), r.project().getLocalRoot());
        assertEquals(folder.getFileName().toString(), r.project().getName());
        assertEquals("BLANK", r.project().getProjectType());

        List<ProjectFile> rows = projectFileRepository.findByProjectId(r.project().getId());
        assertEquals(3, rows.size(), "合同.docx + sub + 备忘录.txt，隐藏项不进库: " + rows);
        ProjectFile doc = rows.stream().filter(f -> f.getName().equals("合同.docx")).findFirst().orElseThrow();
        assertNull(doc.getParentId());
        assertEquals("projects/" + r.project().getId() + "/合同.docx", doc.getFilePath());
        ProjectFile sub = rows.stream().filter(f -> f.getName().equals("sub")).findFirst().orElseThrow();
        assertTrue(Boolean.TRUE.equals(sub.getIsFolder()));
        ProjectFile memo = rows.stream().filter(f -> f.getName().equals("备忘录.txt")).findFirst().orElseThrow();
        assertEquals(sub.getId(), memo.getParentId());
        assertEquals("projects/" + r.project().getId() + "/sub/备忘录.txt", memo.getFilePath());
    }

    @Test
    void reopeningSameFolderReusesProjectWithoutDuplicates(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("a.txt"), "1");
        Files.createDirectories(folder.resolve("sub"));
        Files.writeString(folder.resolve("sub/b.txt"), "2");

        LocalProjectService.OpenLocalResult first = svc.openLocalFolder(folder.toString(), false, null, null, 1L);
        LocalProjectService.OpenLocalResult second = svc.openLocalFolder(folder.toString(), false, null, null, 1L);

        assertTrue(second.reused());
        assertEquals(first.project().getId(), second.project().getId());
        List<ProjectFile> rows = projectFileRepository.findByProjectId(first.project().getId());
        assertEquals(3, rows.size(), "重复打开不得产生重复行（含根级 parentId=null 查重）: " + rows);
    }

    @Test
    void createFolderModeCreatesTheDirectory(@TempDir Path parent) {
        Path target = parent.resolve("新项目");
        LocalProjectService.OpenLocalResult r = svc.openLocalFolder(target.toString(), true, null, null, 1L);
        assertTrue(Files.isDirectory(target));
        assertEquals("新项目", r.project().getName());
    }

    @Test
    void rejectsFolderInsideAppDataRoot() throws Exception {
        Path inside = globalRoot.resolve("projects/evil");
        Files.createDirectories(inside);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.openLocalFolder(inside.toString(), false, null, null, 1L));
        assertTrue(e.getMessage().contains("内部数据目录"));
    }

    @Test
    void rejectsNestedFolders(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("outer/inner"));
        svc.openLocalFolder(folder.resolve("outer").toString(), false, null, null, 1L);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.openLocalFolder(folder.resolve("outer/inner").toString(), false, null, null, 1L));
        assertTrue(e.getMessage().contains("嵌套"));
    }

    @Test
    void rejectsRelativeAndRootPaths() {
        assertThrows(IllegalArgumentException.class, () -> svc.openLocalFolder("relative/path", false, null, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> svc.openLocalFolder("/", false, null, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> svc.openLocalFolder("  ", false, null, null, 1L));
    }
}
