// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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

        projectFileService.setStorageResolverForTest(resolver);

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


    /**
     * Word/WPS 打开 .docx 时会在同目录落一个 `~$合同.docx` 锁文件（dev-board#463）。
     * 它不以点开头，旧的隐藏项规则拦不住，于是：进资源管理器 → Word 关闭删掉它 →
     * 对账把它软删进回收站，一次开关文档就留一个幽灵，还顺带触发一次版本记录空转。
     * 这里两半都要断：首次导入不进库，watcher 驱动的 reconcile 也不进库。
     */
    @Test
    void skipsOfficeLockFilesOnImportAndReconcile(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("合同.docx"), "x");
        Files.writeString(folder.resolve("~$合同.docx"), "lock");

        LocalProjectService.OpenLocalResult r = svc.openLocalFolder(
                folder.toString(), false, null, "合同.docx", 1L);
        Long pid = r.project().getId();

        List<ProjectFile> rows = projectFileRepository.findByProjectId(pid);
        assertTrue(rows.stream().noneMatch(f -> f.getName().startsWith("~$")),
                "Office 锁文件不得进库: " + rows);
        assertEquals(1, rows.size(), "只有 合同.docx 一行: " + rows);

        // watcher 路径：Word 再开一次文档，对账不能把新的锁文件收进来
        Files.writeString(folder.resolve("~$备忘录.docx"), "lock2");
        svc.reconcileProject(pid);

        List<ProjectFile> after = projectFileRepository.findByProjectId(pid);
        assertTrue(after.stream().noneMatch(f -> f.getName().startsWith("~$")),
                "对账同样不得把 Office 锁文件收进来: " + after);
        assertEquals(1, after.size(), "对账后仍只有 合同.docx 一行: " + after);
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
    void reconcileImportsNewAndForgetsVanished(@TempDir Path folder) throws Exception {
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
        // 回收站只收律师在应用里亲手删的东西：Finder 里删掉的，字节已经不在了，
        // 进回收站既还原不出内容、彻底删除又撞「文件不存在」（v0.49.0 BUG-02/03）——直接出索引
        assertTrue(rows.stream().noneMatch(f -> f.getName().equals("sub")), "磁盘上消失的文件夹应直接出索引: " + rows);
        assertTrue(rows.stream().noneMatch(f -> f.getName().equals("b.txt")), "消失文件夹里的文件随之出索引: " + rows);
        assertTrue(projectFileService.getRecycleBinFiles(projectId).isEmpty(), "外部删除不得进回收站");
    }

    /**
     * v0.49.0 真机 BUG-02（0.48 BUG-005 同源）：在 Finder/终端里一次删掉 100 个文件 + 一个文件夹，
     * 后台对账把它们全当成「律师在应用里删的」软删进回收站，回收站瞬间多出 100+ 条，
     * 而且那些行的字节已经不在盘上，还原不出东西、彻底删除又失败。
     */
    @Test
    void externalBulkDeletionLeavesRecycleBinEmpty(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("合同.docx"), "keep");
        Files.createDirectories(folder.resolve("perf100"));
        for (int i = 0; i < 100; i++) {
            Files.writeString(folder.resolve("perf100/qa-perf-" + i + ".txt"), "x" + i);
        }
        Files.writeString(folder.resolve("这是一个很长的文件名.txt"), "y");
        Long pid = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();
        assertEquals(103, projectFileRepository.findByProjectId(pid).size());

        // 应用里先删一份（回收站语义：字节留盘），外部清理不得牵连它
        ProjectFile keptInBin = live(projectFileRepository.findByProjectId(pid), "合同.docx");
        projectFileService.delete(keptInBin.getId(), 1L);

        for (int i = 0; i < 100; i++) {
            Files.delete(folder.resolve("perf100/qa-perf-" + i + ".txt"));
        }
        Files.delete(folder.resolve("perf100"));
        Files.delete(folder.resolve("这是一个很长的文件名.txt"));

        LocalProjectService.ReconcileResult r = svc.reconcileProject(pid);
        assertTrue(r.changed() > 0, "外部删除是一次真实变化，要触发版本信号");

        List<ProjectFile> bin = projectFileService.getRecycleBinFiles(pid);
        assertEquals(1, bin.size(), "回收站只该有律师在应用里删的那一份: " + bin);
        assertEquals(keptInBin.getId(), bin.get(0).getId());
        assertEquals(1, projectFileRepository.findByProjectId(pid).size(),
                "外部删掉的 102 行应全部出索引: " + projectFileRepository.findByProjectId(pid));
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

    /**
     * dev-board#457：Agent 的 create_folder 把「放项目根目录」写成 parentFolderId=0，
     * 库里没有 id=0 这一行。前端 normalizeParentId 把 0 当根画出来，后端的查重却把
     * 0 当成另一个父节点——文件一落进磁盘上那个目录，对账按 rowKey("root/名字") 找不到
     * 这条 parent_id=0 的行，就再建一条真正的根行，资源管理器顶部于是多出一个重复节点，
     * 刷新/重启都在（孤儿那条的物理路径解析在缺失的父节点处断链，正好落回根，
     * 目录存在 → 删除同步判它「还在」→ 永不清理）。
     */
    @Test
    void zeroParentFolderDoesNotSpawnDuplicateRootRow(@TempDir Path folder) throws Exception {
        Long projectId = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();

        projectFileService.createFolder(projectId, 0L, "01-主体资格与章程", 1L);
        Files.createDirectories(folder.resolve("01-主体资格与章程"));
        Files.writeString(folder.resolve("01-主体资格与章程/公司章程.docx"), "x");

        svc.reconcileProject(projectId);

        List<ProjectFile> live = projectFileRepository.findByProjectId(projectId).stream()
                .filter(f -> !Boolean.TRUE.equals(f.getIsDeleted()))
                .filter(f -> "01-主体资格与章程".equals(f.getName()))
                .toList();
        assertEquals(1, live.size(), "同一个文件夹在资源管理器里只能有一个节点: " + live);
        assertNull(live.get(0).getParentId(), "它就该是一条普通的根行，parent_id 必须是 null");
    }

    // ---- dev-board#885：应用里建的文件夹必须真的落到磁盘上 ----
    // 对账以磁盘为真相源：「行在库、目录不在盘」= 律师在 Finder 里删了它 → 软删除。
    // 应用里新建的文件夹此前只落库不建目录，空的那种在下一次任意磁盘变化（新建文档、
    // 保存、版本记录写 .awd/）触发的对账里就被当成「已删除」送进回收站；恢复也不建目录，
    // 于是下一轮又进去一次。有文件的文件夹不中招，只因为写文件时顺手建了父目录。

    private static ProjectFile live(List<ProjectFile> rows, String name) {
        return rows.stream().filter(f -> name.equals(f.getName()))
                .filter(f -> !Boolean.TRUE.equals(f.getIsDeleted()))
                .findFirst().orElse(null);
    }

    @Test
    void emptyFolderCreatedInAppSurvivesReconcile(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("合同.docx"), "x");
        Long pid = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();

        ProjectFile dir = projectFileService.createFolder(pid, null, "QA空目录", 1L);
        ProjectFile sub = projectFileService.createFolder(pid, dir.getId(), "子目录", 1L);
        // 任意一次磁盘变化（这里模拟「新建文档」落了一个文件）都会触发对账
        Files.writeString(folder.resolve("newdocument.docx"), "y");
        svc.reconcileProject(pid);

        List<ProjectFile> rows = projectFileRepository.findByProjectId(pid);
        assertNotNull(live(rows, "QA空目录"), "没人删过的空文件夹不得进回收站: " + rows);
        assertNotNull(live(rows, "子目录"), "嵌套的空文件夹同理: " + rows);
        assertTrue(projectFileService.getRecycleBinFiles(pid).isEmpty(), "回收站应为空");
        assertTrue(Files.isDirectory(folder.resolve("QA空目录/子目录")), "文件夹应在磁盘上真实存在");
        assertEquals(1, rows.stream().filter(f -> "QA空目录".equals(f.getName())).count(), "不得产生重复行");
        assertEquals(dir.getId(), live(rows, "子目录").getParentId());
        assertEquals(sub.getId(), live(rows, "子目录").getId());
    }

    @Test
    void restoredEmptyFolderStaysOutOfRecycleBin(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("合同.docx"), "x");
        Long pid = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();

        // 修复前落下的存量：库里有、盘上没有、已经被对账送进回收站的空文件夹
        ProjectFile ghost = new ProjectFile();
        ghost.setProjectId(pid);
        ghost.setIsFolder(true);
        ghost.setName("QA目录刷新复验");
        ghost.setSortOrder(9);
        ghost.setUserId(1L);
        ghost.setIsDeleted(true);
        ghost.setDeletedAt(java.time.LocalDateTime.now());
        ghost = projectFileRepository.save(ghost);

        projectFileService.restore(ghost.getId(), 1L);
        Files.writeString(folder.resolve("newdocument (3).docx"), "y");
        svc.reconcileProject(pid);

        assertNotNull(live(projectFileRepository.findByProjectId(pid), "QA目录刷新复验"),
                "律师亲手恢复的文件夹，下一轮对账不得再送回回收站");
        assertTrue(Files.isDirectory(folder.resolve("QA目录刷新复验")));
    }

    @Test
    void renamedFolderKeepsItsNameAndContentsAfterReconcile(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("旧名"));
        Files.writeString(folder.resolve("旧名/证据.docx"), "x");
        Long pid = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();
        ProjectFile dir = live(projectFileRepository.findByProjectId(pid), "旧名");

        projectFileService.rename(dir.getId(), "新名", 1L);
        svc.reconcileProject(pid);

        List<ProjectFile> rows = projectFileRepository.findByProjectId(pid);
        assertNotNull(live(rows, "新名"), "改名后的文件夹不得被对账送进回收站: " + rows);
        assertNull(live(rows, "旧名"), "不得从旧目录再导入一个幽灵文件夹: " + rows);
        ProjectFile doc = live(rows, "证据.docx");
        assertNotNull(doc);
        assertEquals(dir.getId(), doc.getParentId());
        assertEquals("projects/" + pid + "/新名/证据.docx", doc.getFilePath());
        assertTrue(Files.isRegularFile(folder.resolve("新名/证据.docx")));
        assertFalse(Files.exists(folder.resolve("旧名")));
        assertTrue(projectFileService.getRecycleBinFiles(pid).isEmpty());
    }

    @Test
    void movedFolderWithEmptySubfolderSurvivesReconcile(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("甲/空的"));
        Files.writeString(folder.resolve("甲/a.txt"), "1");
        Files.createDirectories(folder.resolve("乙"));
        Files.writeString(folder.resolve("乙/b.txt"), "2");
        Long pid = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();
        List<ProjectFile> before = projectFileRepository.findByProjectId(pid);

        projectFileService.move(live(before, "甲").getId(), live(before, "乙").getId(), null, 1L);
        svc.reconcileProject(pid);

        List<ProjectFile> rows = projectFileRepository.findByProjectId(pid);
        assertNotNull(live(rows, "空的"), "随父文件夹移动的空子文件夹不得进回收站: " + rows);
        assertEquals(1, rows.stream().filter(f -> "甲".equals(f.getName())).count(), "不得在原位置导入幽灵: " + rows);
        assertEquals(live(rows, "乙").getId(), live(rows, "甲").getParentId());
        assertTrue(Files.isDirectory(folder.resolve("乙/甲/空的")));
        assertEquals("projects/" + pid + "/乙/甲/a.txt", live(rows, "a.txt").getFilePath());
        assertTrue(projectFileService.getRecycleBinFiles(pid).isEmpty());
    }

    @Test
    void stagingFolderNeitherLandsOnDiskNorBouncesIntoRecycleBin(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("合同.docx"), "x");
        Long pid = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();

        projectFileService.createFolder(pid, null, "__staging_area__", 1L);
        Files.writeString(folder.resolve("newdocument.docx"), "y");
        svc.reconcileProject(pid);

        assertFalse(Files.exists(folder.resolve("__staging_area__")), "律师的文件夹里不该凭空多出一个空的缓存区目录");
        assertNotNull(live(projectFileRepository.findByProjectId(pid), "__staging_area__"),
                "缓存区文件夹不得被对账送进回收站");
    }

    // ---------- BUG-14（v0.49.0 真机 C4-03）：Finder 里改名/移动已打开的文档 ----------
    // 旧对账把改名拆成「旧行进回收站 + 新名建一条新行」：编辑器手里还是旧 fileId，
    // 下一次保存按旧行的旧路径把文件重新建出来，磁盘上一新一旧两份、回收站里挂着一个
    // 其实还在磁盘上的旧名。改名/移动必须原地改那一行（id 不变），保存才会落到新路径。

    @Test
    void externalRenameKeepsTheRowIdAndRepointsItsPath(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("C4-long80.docx"), "0123456789");
        Long pid = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();
        ProjectFile before = live(projectFileRepository.findByProjectId(pid), "C4-long80.docx");

        Files.move(folder.resolve("C4-long80.docx"), folder.resolve("C4-long80-改名.docx"));
        svc.reconcileProject(pid);

        List<ProjectFile> rows = projectFileRepository.findByProjectId(pid);
        assertEquals(1, rows.size(), "改名不得变成「删一条 + 建一条」: " + rows);
        ProjectFile after = rows.get(0);
        assertEquals(before.getId(), after.getId(), "编辑器手里的 fileId 必须继续有效");
        assertEquals("C4-long80-改名.docx", after.getName());
        assertEquals("projects/" + pid + "/C4-long80-改名.docx", after.getFilePath());
        assertFalse(Boolean.TRUE.equals(after.getIsDeleted()));
        assertTrue(projectFileService.getRecycleBinFiles(pid).isEmpty(), "回收站里不该出现旧名");
    }

    @Test
    void externalMoveIntoSubfolderKeepsTheRowId(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("合同.docx"), "0123456789");
        Files.createDirectories(folder.resolve("归档"));
        Long pid = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();
        List<ProjectFile> before = projectFileRepository.findByProjectId(pid);
        ProjectFile doc = live(before, "合同.docx");

        Files.move(folder.resolve("合同.docx"), folder.resolve("归档/合同.docx"));
        svc.reconcileProject(pid);

        List<ProjectFile> rows = projectFileRepository.findByProjectId(pid);
        ProjectFile moved = live(rows, "合同.docx");
        assertNotNull(moved);
        assertEquals(doc.getId(), moved.getId());
        assertEquals(live(rows, "归档").getId(), moved.getParentId());
        assertEquals("projects/" + pid + "/归档/合同.docx", moved.getFilePath());
        assertEquals(1, rows.stream().filter(f -> "合同.docx".equals(f.getName())).count(), "不得留下旧行: " + rows);
        assertTrue(projectFileService.getRecycleBinFiles(pid).isEmpty());
    }

    /** 认不准就不认：同目录两份同样大小的文件一起消失、只出现一份新文件，不猜是谁改的名。 */
    @Test
    void ambiguousRenameCandidatesFallBackToDeletePlusCreate(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("a.docx"), "12345");
        Files.writeString(folder.resolve("b.docx"), "abcde");
        Long pid = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();
        List<ProjectFile> before = projectFileRepository.findByProjectId(pid);

        Files.delete(folder.resolve("a.docx"));
        Files.move(folder.resolve("b.docx"), folder.resolve("c.docx"));
        svc.reconcileProject(pid);

        List<ProjectFile> rows = projectFileRepository.findByProjectId(pid);
        ProjectFile c = live(rows, "c.docx");
        assertNotNull(c);
        assertFalse(before.stream().anyMatch(f -> f.getId().equals(c.getId())), "有歧义时 c.docx 必须是新行: " + rows);
        assertEquals(2, projectFileService.getRecycleBinFiles(pid).size(), "a/b 两条旧行照旧进回收站");
    }

    /** 大小不同的「一删一增」是两件事，不能被认成改名。 */
    @Test
    void unrelatedDeleteAndCreateAreNotPairedAsRename(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("旧合同.docx"), "12345");
        Long pid = svc.openLocalFolder(folder.toString(), false, null, null, 1L).project().getId();
        ProjectFile old = live(projectFileRepository.findByProjectId(pid), "旧合同.docx");

        Files.delete(folder.resolve("旧合同.docx"));
        Files.writeString(folder.resolve("新合同.docx"), "123456789");
        svc.reconcileProject(pid);

        List<ProjectFile> rows = projectFileRepository.findByProjectId(pid);
        assertNotEquals(old.getId(), live(rows, "新合同.docx").getId());
        assertEquals(1, projectFileService.getRecycleBinFiles(pid).size());
    }

    @Test
    void rejectsRelativeAndRootPaths() {
        assertThrows(IllegalArgumentException.class, () -> svc.openLocalFolder("relative/path", false, null, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> svc.openLocalFolder("/", false, null, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> svc.openLocalFolder("  ", false, null, null, 1L));
    }
}
