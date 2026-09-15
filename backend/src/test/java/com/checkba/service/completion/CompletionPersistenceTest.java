// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.completion;

import com.checkba.model.entity.CompletionEntry;
import com.checkba.model.entity.User;
import com.checkba.repository.CompletionEntryRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.completion.CompletionService.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:completion-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(CompletionService.class)
class CompletionPersistenceTest {
    @Autowired CompletionService service;
    @Autowired CompletionEntryRepository entries;
    @Autowired UserRepository users;
    @MockBean ProjectMemberService members;
    @MockBean com.checkba.service.insight.DocInsightService insightService;
    @MockBean com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private CompletionEntry row(String scope, String text, LocalDateTime used) {
        CompletionEntry row = new CompletionEntry();
        row.setScopeKey(scope); row.setText(text); row.setKind("WORD"); row.setUses(1); row.setLastUsedAt(used);
        return row;
    }

    @Test
    void uniqueConstraintCoversScopeAndTextButAllowsSeparateOwners() {
        entries.saveAndFlush(row("p:1", "同名词语", LocalDateTime.now()));
        entries.saveAndFlush(row("u:1", "同名词语", LocalDateTime.now()));
        assertEquals(2, entries.count());
        assertThrows(DataIntegrityViolationException.class, () -> entries.saveAndFlush(row("p:1", "同名词语", LocalDateTime.now())));
    }

    @Test
    void recentOrderingAndScopedClearUseRealDatabase() {
        entries.saveAndFlush(row("u:2", "旧表述", LocalDateTime.now().minusDays(1)));
        entries.saveAndFlush(row("u:2", "新表述", LocalDateTime.now()));
        entries.saveAndFlush(row("u:3", "他人表述", LocalDateTime.now()));
        assertEquals(List.of("新表述", "旧表述"), entries.findByScopeKeyOrderByLastUsedAtDescIdDesc("u:2").stream().map(CompletionEntry::getText).toList());
        assertEquals(2, entries.deleteByScopeKey("u:2"));
        entries.flush();
        assertTrue(entries.findByScopeKeyOrderByLastUsedAtDescIdDesc("u:2").isEmpty());
        assertEquals(1, entries.findByScopeKeyOrderByLastUsedAtDescIdDesc("u:3").size());
    }

    @Test
    void summaryProjectionKeepsDetailFlagWithoutLoadingBodyAndIdPageSupportsPruning() {
        CompletionEntry old = row("p:77", "旧资料", LocalDateTime.now().minusDays(1));
        old.setDetailJson("large cached body");
        entries.saveAndFlush(old);
        CompletionEntry recent = entries.saveAndFlush(row("p:77", "新词语", LocalDateTime.now()));
        var summaries = entries.findSummaries("p:77", PageRequest.of(0, 2));
        assertEquals("新词语", summaries.get(0).getText());
        assertFalse(summaries.get(0).getHasDetail());
        assertTrue(summaries.get(1).getHasDetail());
        assertEquals(List.of(old.getId()), entries.findIds("p:77", PageRequest.of(1, 1)));
        assertEquals(recent.getId(), summaries.get(0).getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentLearningCreatesOneRowAndLosesNoUses() throws Exception {
        User user = new User(); user.setUsername("completion-concurrent"); user.setDisplayName("测试用户"); user.setPassword("test-only");
        Long uid = users.saveAndFlush(user).getId();
        when(members.hasWritePermission(1L, uid)).thenReturn(true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<LearnResult>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) tasks.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return service.learn(uid, 1L, new LearnRequest(List.of(new LearnEntry("并发常用表述", "PHRASE")), "user"));
            }));
            start.countDown();
            for (Future<LearnResult> task : tasks) assertEquals(1, task.get(15, TimeUnit.SECONDS).learned());
            List<CompletionEntry> rows = entries.findByScopeKeyOrderByLastUsedAtDescIdDesc("u:" + uid);
            assertEquals(1, rows.size()); assertEquals(4, rows.get(0).getUses());
        } finally {
            pool.shutdownNow();
            entries.deleteAll(entries.findByScopeKeyOrderByLastUsedAtDescIdDesc("u:" + uid));
            users.deleteById(uid);
        }
    }
}
