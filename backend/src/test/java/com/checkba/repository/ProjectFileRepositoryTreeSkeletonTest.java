package com.checkba.repository;

import com.checkba.model.entity.ProjectFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 内存 H2（MODE=PostgreSQL），头部配置照抄 version/WorkSessionRepositoryTest:19-28，
 * 只换 H2 库名（库名是本类 ApplicationContext 缓存键的一部分，重名会串数据）。
 * 钉死概览页统计条依赖的骨架查询：四列、只回存活行、只回本项目。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:project-file-skeleton-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectFileRepositoryTreeSkeletonTest {

    @Autowired
    private ProjectFileRepository repository;

    private ProjectFile row(Long projectId, Long parentId, boolean folder, String name, boolean deleted) {
        ProjectFile f = new ProjectFile();
        f.setProjectId(projectId);
        f.setParentId(parentId);
        f.setIsFolder(folder);
        f.setName(name);
        f.setSortOrder(0);
        f.setUserId(1L);
        f.setIsDeleted(deleted);
        f.setCreatedAt(LocalDateTime.now());
        return repository.save(f);
    }

    private void typed(ProjectFile f, String fileType) {
        f.setFileType(fileType);
        repository.save(f);
    }

    @Test
    void returnsFourColumnSkeletonOfLivingRowsOfOneProjectOnly() {
        ProjectFile folder = row(7L, null, true, "合同", false);
        row(7L, folder.getId(), false, "框架协议.docx", false);
        row(7L, null, false, "已删.docx", true);
        row(8L, null, false, "别的项目.docx", false);

        List<Object[]> rows = repository.findTreeSkeletonByProjectId(7L);

        assertEquals(2, rows.size());
        for (Object[] r : rows) {
            assertEquals(4, r.length);
        }
        Object[] child = rows.stream()
                .filter(r -> "框架协议.docx".equals(r[3]))
                .findFirst()
                .orElseThrow();
        assertEquals(folder.getId(), child[1]);
        assertEquals(Boolean.FALSE, child[2]);

        Object[] root = rows.stream()
                .filter(r -> "合同".equals(r[3]))
                .findFirst()
                .orElseThrow();
        assertEquals(null, root[1]);
        assertEquals(Boolean.TRUE, root[2]);
    }

    /**
     * MediaFileTypeReconciler（dev-board#417）靠这条派生查询捞存量脏行。派生查询的方法名
     * 是否真能被 Spring Data 解析、isFolder/isDeleted 两个布尔条件是否真的生效，
     * mock 出来的 repository 一个字都证明不了——必须对着真 H2 跑一遍。
     */
    @Test
    void findsFilesByFileTypeExcludingFoldersAndDeletedRows() {
        typed(row(9L, null, false, "现场影像-20260902-191122-D160-d16044f3.jpg", false), "image");
        typed(row(9L, null, false, "现场影像-20260817-173704.mov", false), "video");
        typed(row(9L, null, false, "已删.jpg", true), "image");
        typed(row(9L, null, true, "现场影像", false), "image");
        typed(row(9L, null, false, "正常.jpg", false), "jpg");

        List<ProjectFile> dirty = repository
                .findByFileTypeInAndIsFolderFalseAndIsDeletedFalse(List.of("image", "video", "audio"));

        assertEquals(2, dirty.size(), "只该捞到未删除的两个文件行，实际: "
                + dirty.stream().map(ProjectFile::getName).toList());
        assertEquals(List.of("现场影像-20260817-173704.mov", "现场影像-20260902-191122-D160-d16044f3.jpg"),
                dirty.stream().map(ProjectFile::getName).sorted().toList());
    }
}
