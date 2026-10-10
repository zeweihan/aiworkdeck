// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.mobile;
import com.checkba.model.entity.Project;
import com.checkba.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:catalog-identity;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.database-platform=org.hibernate.dialect.H2Dialect","spring.jpa.hibernate.ddl-auto=create-drop"})
class ProjectCatalogIdentityRepositoryTest {
    @Autowired ProjectRepository projects;
    @Test void backfillCompareAndSetCannotOverwriteAnotherRequestsWinningIdentity() {
        Project project=new Project();project.setName("fixture");project.setProjectType("BLANK");project.setListedCompanyName("");project.setTargetCompanyName("");project.setUserId(1L);
        projects.saveAndFlush(project);String initial=project.getUid();assertNotNull(initial);
        String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
        assertEquals(1,projects.assignCatalogUidIfUnchanged(project.getId(),initial,first));
        assertEquals(0,projects.assignCatalogUidIfUnchanged(project.getId(),initial,second));
        assertEquals(first,projects.readCatalogUid(project.getId()));
    }
}
