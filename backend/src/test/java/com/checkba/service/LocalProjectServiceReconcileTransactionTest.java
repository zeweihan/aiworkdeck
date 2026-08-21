package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.version.WorkSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账事务不能被一条坏条目整体拖垮（dev-board#74 稳定性审计条目 3）。
 *
 * 全上下文（真实 @Transactional 代理）而非 @DataJpaTest 手工 new：
 * LocalProjectServiceTest 里的 svc/projectFileService 是手工 new 出来的 POJO，绕过了
 * Spring AOP 代理，@Transactional 在那份测试里其实从未真正生效过——复现"内层参与事务
 * 抛异常污染外层事务"这个 bug 必须要有真代理，所以这里改用 @SpringBootTest + @Autowired。
 *
 * 复现路径：reconcileProject / importFolder 带 @Transactional；它们调的
 * ProjectFileService.createFolder 是另一个 bean 上的 @Transactional 方法（REQUIRED 传播，
 * 参与同一事务）。磁盘上出现一个与库中"活着"的文件同名的目录时，createFolder 内部的
 * existsByProjectIdAndParentIdAndNameAndIdNot 查重不看 isFolder，判定"同名已存在"抛出
 * IllegalArgumentException。LocalProjectService 在 preVisitDirectory 里 catch 住继续扫，
 * 但 Spring 对参与型事务抛异常默认 doSetRollbackOnly，catch 撤不回这个标记——
 * reconcileProject 方法体本身正常 return，AOP 代理 commit 时发现 rollback-only，
 * 转而回滚整个事务并抛 UnexpectedRollbackException，本轮已经处理完的其它条目
 * （如同一次扫描里正常的新文件）跟着一起被吞。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reconcile-tx-poison-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "security.local-mode=false"})
@ActiveProfiles("desktop")
class LocalProjectServiceReconcileTransactionTest {

    @Autowired
    private LocalProjectService localProjectService;
    @Autowired
    private ProjectFileRepository projectFileRepository;

    @MockBean
    private WorkSessionService workSessionService;
    // 全上下文会真的挂文件系统监听（onLocalProjectOpened 监听器）；不掐掉的话，
    // 后台 watcher 会对着同一个临时目录并发触发它自己的 reconcile，跟本测试手动
    // 调用的 reconcileProject 互相打架，测试变得不确定。
    @MockBean
    private LocalRootWatchService localRootWatchService;

    @Test
    void oneBadEntryDuringReconcileDoesNotDiscardTheRestOfTheBatch(@TempDir Path folder) throws Exception {
        // "conflict" 起初是一个真文件，随项目一起导入（库里落一条"活着"的文件行）
        Files.writeString(folder.resolve("conflict"), "was a file");
        Files.writeString(folder.resolve("keep.txt"), "1");
        Long projectId = localProjectService
                .openLocalFolder(folder.toString(), false, null, null, 1L)
                .project().getId();

        // Finder 里：把 conflict 从文件换成同名目录（库里那行还是"文件"，类型冲突），
        // 另外新增一个完全正常的文件
        Files.delete(folder.resolve("conflict"));
        Files.createDirectories(folder.resolve("conflict"));
        Files.writeString(folder.resolve("new.txt"), "2");

        LocalProjectService.ReconcileResult result = assertDoesNotThrow(
                () -> localProjectService.reconcileProject(projectId),
                "同名类型冲突（磁盘目录 vs 库中的文件行）触发的内层 IllegalArgumentException "
                        + "不该把整轮对账拖成 UnexpectedRollbackException 抛给调用方");

        assertFalse(result.rootMissing());
        List<ProjectFile> rows = projectFileRepository.findByProjectId(projectId);
        assertTrue(rows.stream().anyMatch(f -> "new.txt".equals(f.getName()) && !Boolean.TRUE.equals(f.getIsDeleted())),
                "一条坏条目不能拖累同一轮扫描里其它条目的导入结果: " + rows);
        assertTrue(rows.stream().anyMatch(f -> "keep.txt".equals(f.getName()) && !Boolean.TRUE.equals(f.getIsDeleted())),
                "更早已提交的行不应受影响: " + rows);
    }

    /**
     * 同样的形状也长在 openLocalFolder 上，而且后果更重：它是 @Transactional，
     * 里面同样调 importFolder。重开一个已存在的本地文件夹项目时，若磁盘上出现
     * 与库中「活着」的文件同名的目录，内层 IllegalArgumentException 会把外层事务
     * 标成 rollback-only，提交时抛 UnexpectedRollbackException——用户看到的是
     * 「打开失败」，而且重开一次还是失败，项目从此打不开。
     */
    @Test
    void oneBadEntryDuringReopenDoesNotFailTheWholeOpen(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("conflict"), "was a file");
        Long projectId = localProjectService
                .openLocalFolder(folder.toString(), false, null, null, 1L)
                .project().getId();

        Files.delete(folder.resolve("conflict"));
        Files.createDirectories(folder.resolve("conflict"));
        Files.writeString(folder.resolve("new.txt"), "2");

        LocalProjectService.OpenLocalResult reopened = assertDoesNotThrow(
                () -> localProjectService.openLocalFolder(folder.toString(), false, null, null, 1L),
                "一条同名冲突条目不该让整个「打开本地文件夹」失败");
        assertTrue(reopened.reused());
        assertTrue(projectFileRepository.findByProjectId(projectId).stream()
                        .anyMatch(f -> "new.txt".equals(f.getName()) && !Boolean.TRUE.equals(f.getIsDeleted())),
                "同一次导入里正常的新文件仍应落库");
    }
}
