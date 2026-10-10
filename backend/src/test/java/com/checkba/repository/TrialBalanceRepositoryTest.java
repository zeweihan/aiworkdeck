// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.repository;

import com.checkba.model.entity.TrialBalance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** trial_balance 真建表（H2 MODE=PostgreSQL）：按 userId 回读 + user_id 唯一约束兜底幂等发放。 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:trial-balance-repo-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TrialBalanceRepositoryTest {

    @Autowired
    private TrialBalanceRepository repository;

    private static TrialBalance row(long userId) {
        Instant now = Instant.parse("2026-10-09T00:00:00Z");
        TrialBalance b = new TrialBalance();
        b.setUserId(userId);
        b.setTrialStartedAt(now);
        b.setTrialEndsAt(now.plus(Duration.ofDays(14)));
        b.setCallsQuota(80);
        b.setCallsUsed(0);
        b.setStatus("active");
        b.setPolicy("min_of_days_or_calls");
        b.setRegion("cn");
        b.setCreatedAt(now);
        b.setUpdatedAt(now);
        return b;
    }

    @Test
    void saveAndFindByUserId() {
        TrialBalance saved = row(101L);
        saved.setAccountFingerprint("account-a");
        saved.setAccountSnapshot("{\"remainingCalls\":61}");
        repository.saveAndFlush(saved);
        TrialBalance got = repository.findByUserId(101L).orElseThrow();
        assertEquals("account-a", got.getAccountFingerprint());
        assertEquals("{\"remainingCalls\":61}", got.getAccountSnapshot());
        assertEquals(80, got.getCallsQuota());
        assertEquals("min_of_days_or_calls", got.getPolicy());
        assertTrue(repository.findByUserId(102L).isEmpty());
    }

    @Test
    void secondRowForSameUser_isRejectedByUniqueConstraint() {
        repository.saveAndFlush(row(201L));
        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(row(201L)));
    }
}
