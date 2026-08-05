package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.SystemSettingRepository;
import com.checkba.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用真机 ~/.aiworkdeck/local.mv.db 的实测形态跑一遍解析，锁死这次 bug 的现场：
 *
 * <pre>
 *   id=1 admin      1 个项目 / 0 个文件   ← PR-A 的规则会选中它（用户看到空工作区）
 *   id=2 hanzewei   6 个项目 / 21 个文件  ← 用户的全部真实数据
 *   id=3 hanzewei1  0 / 0
 *   id=4 newuser    0 / 0
 *   + 一批 qa_bot_* / claude-e2e / e2e_keepalive 测试账号（部分有数据）
 * </pre>
 *
 * 断言：解析结果是「待选定」而不是任何一个静默选择，候选排序里 hanzewei 排第一。
 *
 * 数据源约定同 WorkSessionRepositoryTest（内存 H2 + MODE=PostgreSQL + NON_KEYWORDS=VALUE）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:local-identity-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class LocalIdentityRealShapeIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectFileRepository projectFileRepository;
    @Autowired private SystemSettingRepository systemSettingRepository;

    private Long adminId;
    private Long hanzeweiId;

    private LocalIdentityService service() {
        // localMode=false：本测试只验解析规则，不想污染 AuthController 的静态注册点
        return new LocalIdentityService(userRepository, projectRepository,
                projectFileRepository, systemSettingRepository, false);
    }

    private Long createUser(String username, String displayName) {
        User u = new User();
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setPassword("x");
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(u).getId();
    }

    private void createProjectsAndFiles(Long userId, int projectCount, int fileCount) {
        Long firstProjectId = null;
        for (int i = 0; i < projectCount; i++) {
            Project p = new Project();
            p.setName("项目" + i);
            p.setProjectType("BLANK");
            p.setListedCompanyName("-");
            p.setTargetCompanyName("-");
            p.setUserId(userId);
            p.setCreatedAt(LocalDateTime.now());
            p.setUpdatedAt(LocalDateTime.now());
            Long id = projectRepository.save(p).getId();
            if (firstProjectId == null) firstProjectId = id;
        }
        for (int i = 0; i < fileCount; i++) {
            ProjectFile f = new ProjectFile();
            f.setProjectId(firstProjectId == null ? 1L : firstProjectId);
            f.setName("文件" + i + ".docx");
            f.setIsFolder(false);
            f.setSortOrder(i);
            f.setUserId(userId);
            f.setIsDeleted(false);
            f.setCreatedAt(LocalDateTime.now());
            f.setUpdatedAt(LocalDateTime.now());
            projectFileRepository.save(f);
        }
    }

    @BeforeEach
    void seedRealShape() {
        adminId = createUser("admin", "管理员");
        hanzeweiId = createUser("hanzewei", "韩泽伟");
        createUser("hanzewei1", "韩泽伟1");
        createUser("newuser", "新用户");
        createUser("qa_bot_1754300001", "QA 机器人");
        createUser("claude-e2e", "E2E");
        createUser("e2e_keepalive", "保活探针");

        createProjectsAndFiles(adminId, 1, 0);
        createProjectsAndFiles(hanzeweiId, 6, 21);
        // 测试账号也留了数据——排除逻辑必须在数据量之前生效
        createProjectsAndFiles(userRepository.findByUsername("qa_bot_1754300001").orElseThrow().getId(), 4, 12);
    }

    @Test
    void realMachineShapeResolvesToPendingSelectionInsteadOfAdmin() {
        LocalIdentityService svc = service();

        assertTrue(svc.needsSelection(),
                "admin 与 hanzewei 都有数据，必须待用户选定，不能像 PR-A 那样静默选 admin");
        assertEquals(hanzeweiId, svc.localUserId(),
                "待选定期间的临时落点应是数据量最大的 hanzewei，而不是空壳 admin");
        assertTrue(systemSettingRepository.findByKey(LocalIdentityService.SELECTED_KEY).isEmpty(),
                "待选定不得写持久化");
    }

    @Test
    void candidatesAreRankedByDataAndExcludeTestAccounts() {
        List<LocalIdentityService.Candidate> candidates = service().candidates();

        assertEquals(List.of("hanzewei", "admin", "hanzewei1", "newuser"),
                candidates.stream().map(LocalIdentityService.Candidate::username).toList(),
                "按数据量降序；qa_bot_* / claude-e2e / e2e_keepalive 即便有数据也不进候选");

        LocalIdentityService.Candidate top = candidates.get(0);
        assertEquals(6L, top.projectCount());
        assertEquals(21L, top.fileCount());

        LocalIdentityService.Candidate admin = candidates.get(1);
        assertEquals(1L, admin.projectCount());
        assertEquals(0L, admin.fileCount());
    }

    @Test
    void selectingHanzeweiPersistsAndSurvivesRestart() {
        LocalIdentityService svc = service();
        svc.select(hanzeweiId);

        assertFalse(svc.needsSelection());
        assertEquals(hanzeweiId, svc.localUserId());

        // 「重启」：换一个新实例重新解析，必须直接命中持久化的选择
        LocalIdentityService restarted = service();
        assertFalse(restarted.needsSelection(), "选过一次就不该再问");
        assertEquals(hanzeweiId, restarted.localUserId());
    }

    @Test
    void deletedFilesDoNotCountAsData() {
        // 回收站里的文件不代表「还在用这个工作区」
        ProjectFile f = new ProjectFile();
        f.setProjectId(projectRepository.findByUserIdOrderByCreatedAtDesc(adminId).get(0).getId());
        f.setName("已删除.docx");
        f.setIsFolder(false);
        f.setSortOrder(0);
        f.setUserId(userRepository.findByUsername("newuser").orElseThrow().getId());
        f.setIsDeleted(true);
        f.setCreatedAt(LocalDateTime.now());
        f.setUpdatedAt(LocalDateTime.now());
        projectFileRepository.save(f);

        LocalIdentityService.Candidate newuser = service().candidates().stream()
                .filter(c -> "newuser".equals(c.username())).findFirst().orElseThrow();
        assertEquals(0L, newuser.fileCount());
        assertFalse(newuser.hasData());
    }
}
