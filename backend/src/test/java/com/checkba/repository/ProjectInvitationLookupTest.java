package com.checkba.repository;

import com.checkba.model.entity.ProjectInvitation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 通用访问码的查找不能因为存在重复行而永久 500。
 *
 * <p>病灶：{@code inviteClient} 是先查后建（查空就新建一行 CLIENT_GENERIC），
 * 而 (project_id, type) 上没有任何唯一约束——两个并发的「生成访问码」请求会各插一行。
 * 之后每一次 findByProjectIdAndType 都走 getSingleResult，抛
 * IncorrectResultSizeDataAccessException：这个项目的邀请链接功能从此永久 500，
 * 没有重试也没有自愈，只能有人手工去库里删行。
 *
 * <p>注意 (project_id, type) 本身**不能**加唯一约束：CLIENT_NAMED 天然一个项目多行。
 * 所以修法是让查找取「最早的那一行」而不是要求唯一。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:invitation-lookup-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ProjectInvitationLookupTest {

    @Autowired
    private ProjectInvitationRepository repository;

    private ProjectInvitation row(Long projectId, String type, String code, Long relatedUserId) {
        ProjectInvitation i = new ProjectInvitation();
        i.setProjectId(projectId);
        i.setType(type);
        i.setAccessCode(code);
        i.setRelatedUserId(relatedUserId);
        return i;
    }

    @Test
    @DisplayName("同项目出现两行通用码时，查找取最早那一行而不是抛异常")
    void duplicateGenericRowsDoNotBreakLookup() {
        repository.saveAll(java.util.List.of(
                row(1L, "CLIENT_GENERIC", "codeAAAAAAAAAAAAAAAA", 11L),
                row(1L, "CLIENT_GENERIC", "codeBBBBBBBBBBBBBBBB", 12L)));
        repository.flush();

        // 今天这一句会抛 IncorrectResultSizeDataAccessException：
        // 派生查询声明成 Optional 就走 getSingleResult，多于一行直接炸
        assertDoesNotThrow(() -> repository.findByProjectIdAndType(1L, "CLIENT_GENERIC"),
                "重复行不该让这个项目的邀请链接功能永久 500");
    }

    @Test
    @DisplayName("具名邀请一个项目本来就可以有多行，不该被唯一约束挡住")
    void namedInvitationsMayCoexist() {
        assertDoesNotThrow(() -> {
            repository.saveAll(java.util.List.of(
                    row(2L, "CLIENT_NAMED", "codeCCCCCCCCCCCCCCCC", 21L),
                    row(2L, "CLIENT_NAMED", "codeDDDDDDDDDDDDDDDD", 22L)));
            repository.flush();
        });
    }
}
