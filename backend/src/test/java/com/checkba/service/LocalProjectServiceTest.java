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
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

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
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.evidence.EvidenceLinkService.class));

        ProjectMemberService memberService = mock(ProjectMemberService.class);
        when(memberService.hasReadPermission(anyLong(), anyLong())).thenReturn(true);

        svc = new LocalProjectService(projectRepository, projectMemberRepository,
                projectFileRepository, projectFileService, memberService, resolver,
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                transactionManager);
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
     * importFolder 在扫描条目数达到 MAX_IMPORT_ENTRIES 上限时会置位 ImportStats.truncated，
     * 但 reconcileProject 此前只读 stats.changed，truncated 被整个丢弃——ReconcileResult
     * 没有字段承载它，watcher 触发的后台对账因此全静默：超出上限的文件永远不进文件树，
     * 无日志无 API 信号。openLocalFolder/OpenLocalResult 早就正确处理了同一个 stats.truncated
     * （见 reconcileImportsNewAndSoftDeletesVanished 之外的 openLocalFolder 路径），
     * 这里补上 reconcileProject 这一侧。
     */
    @Test
    void reconcileReportsTruncationWhenImportHitsTheCap(@TempDir Path folder) throws Exception {
        // 上限覆盖成一个小值：真实生产上限是 30000，为了触发截断真建这么多文件
        // 会是几十分钟、几万个 inode 的测试，不能进 CI（dev-board#107 单元 F1 复核）。
        svc.setMaxImportEntriesForTest(50);
        Long projectId = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();

        int overCap = 55;
        for (int i = 0; i < overCap; i++) {
            Files.writeString(folder.resolve("f" + i + ".txt"), "x");
        }

        LocalProjectService.ReconcileResult r = svc.reconcileProject(projectId);
        assertTrue(r.truncated(), "扫描条目数超过上限时，对账结果必须报出截断，否则超限文件永远静默不进文件树");
        assertEquals(5, r.truncatedCount(), "55 项超出上限 50，未纳入的应正好是 5 项");
    }

    /**
     * Files.walkFileTree 的 maxDepth 参数在深度封顶时是"静默"的：preVisitDirectory/visitFile
     * 对超出 MAX_IMPORT_DEPTH 的目录/文件根本不会被调用，没有任何一次遍历回调会执行到"置位
     * truncated"这行代码——与 MAX_IMPORT_ENTRIES 上限不同，那个上限的判断天然长在每次回调
     * 内部，有机会置位。深层文件因此永远静默不进文件树，无日志无 API 信号。
     */
    @Test
    void reportsTruncationWhenNestingExceedsMaxDepth(@TempDir Path folder) throws Exception {
        Path deepest = folder;
        for (int i = 1; i <= LocalProjectService.MAX_IMPORT_DEPTH + 1; i++) {
            deepest = deepest.resolve("d" + i);
        }
        Files.createDirectories(deepest);
        Files.writeString(deepest.resolve("leaf.txt"), "x");

        LocalProjectService.OpenLocalResult r = svc.openLocalFolder(folder.toString(), false, null, null, 1L);

        assertTrue(r.truncated(),
                "深度超过 MAX_IMPORT_DEPTH 时必须报出截断，否则深层文件永远静默不进文件树且无任何信号");
    }

    /**
     * macOS 默认的 APFS/HFS+ 等大小写不敏感、大小写保留的文件系统上，仅改大小写的重命名
     * （"Docs" -> "docs"）之后：importFolder 的 rowKey 按大小写敏感比对，识别不出这是同一个
     * 物理目录，会为新大小写建一个新行；而删除同步那一侧，旧行的 Files.exists(root.resolve("Docs"))
     * 在大小写不敏感文件系统上依然为 true（不敏感匹配命中了同一个物理目录），永远不会被判定
     * 为缺失——旧行从此成为再也清不掉的永久幽灵行。
     *
     * 只在真正大小写不敏感、大小写保留的文件系统上才能复现，用探测式 Assumption 而不是按
     * 操作系统名称猜测（CI 若跑在大小写敏感的文件系统上，用例据此跳过，不制造假红/假绿）。
     */
    @Test
    void caseOnlyRenameOnCaseInsensitiveFsRetiresStaleGhostRow(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("Docs"));
        Long projectId = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();

        boolean caseInsensitiveAndPreserving = Files.exists(folder.resolve("docs"));
        org.junit.jupiter.api.Assumptions.assumeTrue(caseInsensitiveAndPreserving,
                "当前文件系统大小写敏感，跳过（此缺陷只在大小写不敏感盘上出现）");

        // 大小写重命名不能直接 Files.move("Docs", "docs")：在 APFS 这类大小写不敏感盘上，
        // rename(2) 系统调用发现新旧路径解析到同一个物理目录时按 POSIX 语义直接判定"无事可做"，
        // 连显示大小写都不会更新（实测：mv 走 Finder/shell 的两步改名会真的生效，
        // 直接单步 Files.move 则是空操作）——这里用同样的"先改到临时名、再改成目标名"
        // 两步手法，制造出与真实 Finder 改名完全相同的终态：磁盘上的真实大小写已经是 "docs"。
        Path tmp = folder.resolve("Docs__awd_test_tmp__");
        Files.move(folder.resolve("Docs"), tmp);
        Files.move(tmp, folder.resolve("docs"));

        svc.reconcileProject(projectId);

        List<ProjectFile> aliveFolders = projectFileRepository.findByProjectId(projectId).stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsFolder()) && !Boolean.TRUE.equals(f.getIsDeleted()))
                .toList();
        assertEquals(1, aliveFolders.size(),
                "改大小写重命名后应只剩一个存活的文件夹行，旧大小写那行必须被对账清掉: " + aliveFolders);
        assertEquals("docs", aliveFolders.get(0).getName(), "存活的应该是新大小写那一行");
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
