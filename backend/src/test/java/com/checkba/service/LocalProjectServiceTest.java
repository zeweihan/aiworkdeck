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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
    private ProjectFileService projectFileService;
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
        projectFileService = new ProjectFileService(
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
                mock(com.checkba.service.telemetry.TelemetryService.class));
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

    // ---- 对账（watcher 触发的 reconcileProject）----

    @Test
    void reconcileImportsNewAndSoftDeletesVanished(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("a.txt"), "1");
        Files.createDirectories(folder.resolve("sub"));
        Files.writeString(folder.resolve("sub/b.txt"), "2");
        Long projectId = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();

        // Finder 里：删掉 sub 整个目录，新增 c.txt
        Files.delete(folder.resolve("sub/b.txt"));
        Files.delete(folder.resolve("sub"));
        Files.writeString(folder.resolve("c.txt"), "3");

        LocalProjectService.ReconcileResult r = svc.reconcileProject(projectId);
        assertTrue(r.changed() > 0);
        assertFalse(r.rootMissing());

        List<ProjectFile> rows = projectFileRepository.findByProjectId(projectId);
        ProjectFile a = rows.stream().filter(f -> f.getName().equals("a.txt")).findFirst().orElseThrow();
        assertFalse(Boolean.TRUE.equals(a.getIsDeleted()));
        ProjectFile c = rows.stream().filter(f -> f.getName().equals("c.txt")).findFirst().orElseThrow();
        assertFalse(Boolean.TRUE.equals(c.getIsDeleted()));
        ProjectFile sub = rows.stream().filter(f -> f.getName().equals("sub")).findFirst().orElseThrow();
        assertTrue(Boolean.TRUE.equals(sub.getIsDeleted()), "磁盘上消失的文件夹应进回收站");
        ProjectFile b = rows.stream().filter(f -> f.getName().equals("b.txt")).findFirst().orElseThrow();
        assertTrue(Boolean.TRUE.equals(b.getIsDeleted()));
    }

    @Test
    void reconcileDoesNotResurrectRecycledRows(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("a.txt"), "1");
        Long projectId = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();
        ProjectFile a = projectFileRepository.findByProjectId(projectId).get(0);

        // 律师在应用里删除（回收站，磁盘文件保留）——对账不得把它当新文件复活
        projectFileService.delete(a.getId(), 1L);
        svc.reconcileProject(projectId);

        List<ProjectFile> rows = projectFileRepository.findByProjectId(projectId);
        assertEquals(1, rows.size(), "不得因磁盘文件仍在而新建重复行");
        assertTrue(Boolean.TRUE.equals(rows.get(0).getIsDeleted()));
    }

    @Test
    void reconcileIsNoOpWhenNothingChanged(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("a.txt"), "1");
        Long projectId = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();
        java.time.LocalDateTime before = projectFileRepository.findByProjectId(projectId).get(0).getUpdatedAt();

        LocalProjectService.ReconcileResult r = svc.reconcileProject(projectId);
        assertEquals(0, r.changed());
        assertEquals(before, projectFileRepository.findByProjectId(projectId).get(0).getUpdatedAt(),
                "无变化的对账不得翻搅数据库行（版本记录噪声与修改时间失真）");
    }

    @Test
    void reconcileAbortsWhenRootUnreachable(@TempDir Path tmp) throws Exception {
        Path folder = tmp.resolve("proj");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("a.txt"), "1");
        Long projectId = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();

        // 整个文件夹被移走（外置盘拔出）：绝不能把「暂时看不见」当「全删了」
        Files.delete(folder.resolve("a.txt"));
        Files.delete(folder);
        LocalProjectService.ReconcileResult r = svc.reconcileProject(projectId);
        assertTrue(r.rootMissing());
        ProjectFile a = projectFileRepository.findByProjectId(projectId).get(0);
        assertFalse(Boolean.TRUE.equals(a.getIsDeleted()), "根目录不可达时不得软删除任何行");
    }

    /**
     * 全链路冒烟：文件系统事件 → 防抖 → reconcileProject → 落库。
     * NOT_SUPPORTED：默认测试事务不提交，watcher 线程的新事务看不见项目行，链路必假。
     *
     * 这条用例**不赌墙上时间**：
     * - 挂载不用 sleep 等：watchAsync 把注册同步跑在调用线程上，返回即已在监听，
     *   ensureWatch 的返回值就是权威信号，挂不上直接断言失败（而不是白等一整个死线）；
     * - 死线只是兜底（正常路径约 1 秒：防抖 800ms + 落库），超时不只是报「没等到」，
     *   而是把链路每一环的状态一起打出来：监听还活着吗、对账跑了几次、对账抛了什么、
     *   文件在磁盘上吗、库里到底有哪些行。下次再红能一眼看出卡在哪一段。
     */
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void watcherPicksUpExternalChanges(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("a.txt"), "1");
        Long projectId = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();

        // 探针：记录对账真的被调用了几次、抛了什么。用来区分「事件根本没到」与「对账炸了」。
        AtomicInteger reconciles = new AtomicInteger();
        List<String> reconcileErrors = Collections.synchronizedList(new ArrayList<>());
        LocalProjectService probed = spy(svc);
        doAnswer(inv -> {
            reconciles.incrementAndGet();
            try {
                return inv.callRealMethod();
            } catch (Throwable t) {
                reconcileErrors.add(t.toString());
                throw t;
            }
        }).when(probed).reconcileProject(anyLong());

        LocalRootWatchService watch = new LocalRootWatchService(projectRepository, probed);
        try {
            assertTrue(watch.ensureWatch(projectId, folder.normalize().toString()),
                    "watcher 必须真的挂上项目文件夹（挂不上是硬失败，不是慢）");

            Files.writeString(folder.resolve("b.txt"), "2");

            long start = System.currentTimeMillis();
            long deadline = start + 30000;
            boolean found = false;
            while (System.currentTimeMillis() < deadline) {
                found = projectFileRepository.findByProjectId(projectId).stream()
                        .anyMatch(f -> "b.txt".equals(f.getName()) && !Boolean.TRUE.equals(f.getIsDeleted()));
                if (found) break;
                Thread.sleep(100);
            }
            assertTrue(found, () -> "watcher 应在外部新增文件后自动把它导入数据库"
                    + "；耗时=" + (System.currentTimeMillis() - start) + "ms"
                    + "，监听仍存活=" + watch.isWatching(projectId)
                    + "，对账执行次数=" + reconciles.get()
                    + "，对账异常=" + reconcileErrors
                    + "，b.txt 在磁盘上=" + Files.exists(folder.resolve("b.txt"))
                    + "，库内行=" + projectFileRepository.findByProjectId(projectId).stream()
                            .map(f -> f.getName() + (Boolean.TRUE.equals(f.getIsDeleted()) ? "(已删)" : ""))
                            .toList());
        } finally {
            watch.shutdown();
        }
    }

    @Test
    void rejectsRelativeAndRootPaths() {
        assertThrows(IllegalArgumentException.class, () -> svc.openLocalFolder("relative/path", false, null, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> svc.openLocalFolder("/", false, null, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> svc.openLocalFolder("  ", false, null, null, 1L));
    }
}
