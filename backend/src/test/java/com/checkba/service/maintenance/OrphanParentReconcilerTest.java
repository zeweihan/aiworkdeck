package com.checkba.service.maintenance;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存量 parent_id=0 孤儿行的一次性修复口径（dev-board#457，H2 真库——重复 file_path
 * 那条是 GROUP BY ... HAVING 聚合查询，mock 不出真实语义）。
 *
 * <p>这几条断言就是「这段会在每一台存量机器上自动跑一次的代码到底动了谁」的定义。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:orphan-parent-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OrphanParentReconcilerTest {

    private static final long PROJECT_ID = 235L;

    @Autowired private ProjectFileRepository repo;

    private OrphanParentReconciler reconciler;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        reconciler = new OrphanParentReconciler(repo);
    }

    private ProjectFile row(Long parentId, boolean folder, String name, String path) {
        ProjectFile f = new ProjectFile();
        f.setProjectId(PROJECT_ID);
        f.setParentId(parentId);
        f.setIsFolder(folder);
        f.setName(name);
        f.setFilePath(path);
        f.setSortOrder(0);
        f.setUserId(1L);
        f.setIsDeleted(false);
        f.setCreatedAt(LocalDateTime.now());
        f.setUpdatedAt(LocalDateTime.now());
        return repo.save(f);
    }

    private ProjectFile reload(ProjectFile f) {
        return repo.findById(f.getId()).orElseThrow();
    }

    private boolean deleted(ProjectFile f) {
        return Boolean.TRUE.equals(reload(f).getIsDeleted());
    }

    /** 快照：用来断言「再跑一遍什么都不变」。 */
    private String snapshot() {
        return repo.findByProjectId(PROJECT_ID).stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(f -> f.getId() + ":" + f.getParentId() + ":" + f.getName() + ":" + Boolean.TRUE.equals(f.getIsDeleted()))
                .collect(Collectors.joining("|"));
    }

    /** (a) 根下没有同名行时，孤儿就是一条本该待在根上的普通行——归位即可。 */
    @Test
    void orphanWithoutRootTwinIsMovedToRoot() {
        ProjectFile orphan = row(0L, true, "05-劳动人事", null);

        assertEquals(1, reconciler.reconcile());

        assertNull(reload(orphan).getParentId());
        assertFalse(deleted(orphan), "归位不等于删除，行还在，磁盘目录也在");
    }

    /**
     * (b) 事故现场的形状：孤儿文件夹 + 对账器补出来的根下同名文件夹，两边各有一份
     * 公司章程.docx（file_path 逐字相同）。并档之后资源管理器里只剩一个节点。
     */
    @Test
    void orphanFolderIsMergedIntoRootTwinAndChildrenFollow() {
        String base = "projects/" + PROJECT_ID + "/01-主体资格与章程/";
        ProjectFile orphan = row(0L, true, "01-主体资格与章程", null);
        ProjectFile orphanCharter = row(orphan.getId(), false, "公司章程.docx", base + "公司章程.docx");
        ProjectFile orphanOnly = row(orphan.getId(), false, "营业执照.pdf", base + "营业执照.pdf");
        ProjectFile rootTwin = row(null, true, "01-主体资格与章程", null);
        ProjectFile twinCharter = row(rootTwin.getId(), false, "公司章程.docx", base + "公司章程.docx");

        reconciler.reconcile();

        List<ProjectFile> liveNamed = repo.findByProjectId(PROJECT_ID).stream()
                .filter(f -> !Boolean.TRUE.equals(f.getIsDeleted()))
                .filter(f -> "01-主体资格与章程".equals(f.getName()))
                .toList();
        assertEquals(1, liveNamed.size(), "资源管理器里只能剩一个节点: " + liveNamed);
        assertEquals(rootTwin.getId(), liveNamed.get(0).getId(), "留下的应是根下那条真行");

        assertTrue(deleted(orphan), "空掉的孤儿文件夹进回收站");
        assertTrue(deleted(orphanCharter), "同名子项让位给目标文件夹里已有的那份");
        assertFalse(deleted(twinCharter), "目标文件夹里已有的那份不动");
        assertEquals(rootTwin.getId(), reload(orphanOnly).getParentId(), "不冲突的子项改挂到根下同名文件夹");
        assertFalse(deleted(orphanOnly));
        assertEquals(base + "营业执照.pdf", reload(orphanOnly).getFilePath(),
                "两个文件夹同名，物理路径不变——对账一个字节都不搬");
    }

    /** (c) 同一项目内指向同一份字节的多条存活文件行，只留 id 最小的一条。 */
    @Test
    void duplicateFilePathKeepsTheOldestRow() {
        String path = "projects/" + PROJECT_ID + "/合同.docx";
        ProjectFile first = row(null, false, "合同.docx", path);
        ProjectFile second = row(null, false, "合同.docx", path);
        ProjectFile other = row(null, false, "备忘录.docx", "projects/" + PROJECT_ID + "/备忘录.docx");

        assertEquals(1, reconciler.reconcile());

        assertFalse(deleted(first));
        assertTrue(deleted(second));
        assertFalse(deleted(other), "路径不重复的行一律不碰");
    }

    /**
     * 回收站里的孤儿：只把 0 归成 null（还原时不会又得到一条孤儿行），
     * 根下已有同名存活行时连归位也不做，更不会被"复活"。
     */
    @Test
    void deletedOrphansAreNormalizedButNeverResurrected() {
        ProjectFile lonely = row(0L, true, "07-诉讼与合规", null);
        lonely.setIsDeleted(true);
        lonely.setDeletedAt(LocalDateTime.now());
        repo.save(lonely);

        ProjectFile shadowed = row(0L, true, "02-股权与出资", null);
        shadowed.setIsDeleted(true);
        shadowed.setDeletedAt(LocalDateTime.now());
        repo.save(shadowed);
        row(null, true, "02-股权与出资", null);

        reconciler.reconcile();

        assertNull(reload(lonely).getParentId());
        assertTrue(deleted(lonely), "归位不得把回收站里的行变回存活");
        assertEquals(0L, reload(shadowed).getParentId(), "根下已有同名存活行时不动它");
        assertTrue(deleted(shadowed));
    }

    /** 健康安装零改动；跑两遍结果一致（幂等）。 */
    @Test
    void isIdempotentAndNoOpOnHealthyData() {
        row(null, true, "01-主体资格与章程", null);
        row(null, false, "合同.docx", "projects/" + PROJECT_ID + "/合同.docx");
        assertEquals(0, reconciler.reconcile(), "健康安装一行不动");

        ProjectFile orphan = row(0L, true, "05-劳动人事", null);
        assertTrue(reconciler.reconcile() > 0);
        String after = snapshot();

        assertEquals(0, reconciler.reconcile(), "第二遍应无事可做");
        assertEquals(after, snapshot(), "跑两遍结果必须一致");
        assertNull(reload(orphan).getParentId());
    }
}
