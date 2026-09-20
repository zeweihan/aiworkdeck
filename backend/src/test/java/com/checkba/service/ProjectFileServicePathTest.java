// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 按「项目内相对路径」找文件与列文件（dev-board#718，H2 真库）。
 *
 * <p>桌面端参考读取的 LIST 结果用 {@link ProjectFileService#listRelativePaths} 生成路径，
 * READ 再用 {@link ProjectFileService#findByRelativePath} 把同一条路径解析回来——两者口径必须一致，
 * 否则模型拿着清单里的路径去读会得到「项目里没有这个文件」。
 * 路径只按文件树（未删除的行）走，从不碰文件系统，所以 ".." 之类只可能被拒绝、不可能逃出项目。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:project-file-path-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectFileServicePathTest {

    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectFileRepository projectFileRepository;

    private ProjectFileService service;
    private Long projectId;

    @BeforeEach
    void setUp() {
        Project p = new Project();
        p.setName("路径测试");
        p.setProjectType("BLANK");
        p.setListedCompanyName("");
        p.setTargetCompanyName("");
        p.setUserId(1L);
        projectId = projectRepository.save(p).getId();

        service = new ProjectFileService(
                projectFileRepository,
                mock(com.checkba.service.ai.ProjectRagService.class),
                mock(StorageServiceFactory.class),
                mock(com.checkba.version.WorkSessionService.class),
                mock(UserService.class),
                mock(com.checkba.service.quota.StageQuotaService.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.evidence.EvidenceLinkService.class));

        ProjectFile contracts = row(null, "合同", true, false);
        ProjectFile attachments = row(null, "附件", true, false);
        row(contracts.getId(), "主合同.docx", false, false);
        row(contracts.getId(), "旧版.docx", false, true); // 已删除：不可见
        row(attachments.getId(), "清单.xlsx", false, false);
        row(null, "备忘.txt", false, false);
    }

    private ProjectFile row(Long parentId, String name, boolean folder, boolean deleted) {
        ProjectFile f = new ProjectFile();
        f.setProjectId(projectId);
        f.setParentId(parentId);
        f.setIsFolder(folder);
        f.setName(name);
        f.setSortOrder(0);
        f.setUserId(1L);
        f.setIsDeleted(deleted);
        f.setCreatedAt(LocalDateTime.now());
        f.setUpdatedAt(LocalDateTime.now());
        return projectFileRepository.save(f);
    }

    @Test
    void findsNestedFileByPath() {
        assertThat(service.findByRelativePath(projectId, "合同/主合同.docx")).get()
                .extracting(ProjectFile::getName).isEqualTo("主合同.docx");
        assertThat(service.findByRelativePath(projectId, "备忘.txt")).get()
                .extracting(ProjectFile::getName).isEqualTo("备忘.txt");
    }

    @Test
    void rejectsTraversalEmptySegmentsAndMissing() {
        assertThat(service.findByRelativePath(projectId, "../x")).isEmpty();
        assertThat(service.findByRelativePath(projectId, "合同/../附件/清单.xlsx")).isEmpty();
        assertThat(service.findByRelativePath(projectId, "./合同/主合同.docx")).isEmpty();
        assertThat(service.findByRelativePath(projectId, "合同/不存在.docx")).isEmpty();
        assertThat(service.findByRelativePath(projectId, "合同//主合同.docx")).isEmpty();
        assertThat(service.findByRelativePath(projectId, "/合同/主合同.docx")).isEmpty();
        assertThat(service.findByRelativePath(projectId, "合同/主合同.docx/")).isEmpty();
        assertThat(service.findByRelativePath(projectId, "")).isEmpty();
        assertThat(service.findByRelativePath(projectId, null)).isEmpty();
    }

    @Test
    void deletedEntriesAndOtherProjectsAreInvisible() {
        assertThat(service.findByRelativePath(projectId, "合同/旧版.docx")).isEmpty();
        assertThat(service.findByRelativePath(projectId + 1000, "合同/主合同.docx")).isEmpty();
    }

    @Test
    void listsFilesOnlyWithPathsSortedAndFiltered() {
        assertThat(service.listRelativePaths(projectId, null, 100)).extracting(Map.Entry::getKey)
                .containsExactly("合同/主合同.docx", "备忘.txt", "附件/清单.xlsx");
        assertThat(service.listRelativePaths(projectId, "清单", 100)).extracting(Map.Entry::getKey)
                .containsExactly("附件/清单.xlsx");
        assertThat(service.listRelativePaths(projectId, "DOCX", 100)).extracting(Map.Entry::getKey)
                .containsExactly("合同/主合同.docx");
        assertThat(service.listRelativePaths(projectId, null, 1)).hasSize(1);
    }

    @Test
    void everyListedPathResolvesBackToTheSameRow() {
        for (Map.Entry<String, ProjectFile> e : service.listRelativePaths(projectId, null, 100)) {
            assertThat(service.findByRelativePath(projectId, e.getKey())).get()
                    .extracting(ProjectFile::getId).isEqualTo(e.getValue().getId());
        }
    }
}
