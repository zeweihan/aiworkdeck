// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.entity.AgentInboxItem;
import com.checkba.repository.AgentInboxItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-inbox-tx-boundary;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AgentInboxTransactionBoundaryTest {
    @Autowired AgentInboxItemRepository repository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deleteKeepsConversationGateUntilCommitSoClaimCannotResurrectTheRow() throws Exception {
        SseEmitterService sse = mock(SseEmitterService.class);
        AgentInboxService service = new AgentInboxService(repository, sse, mock(AgentRunStateService.class));
        BlockingCommitTransactionManager blocking = new BlockingCommitTransactionManager(transactionManager);
        service.setTransactionManager(blocking);

        AgentInboxItem row = service.submit(request(), 7L);
        clearInvocations(sse);
        AtomicReference<Throwable> deleteFailure = new AtomicReference<>();
        Thread deleting = new Thread(() -> {
            try { service.delete("conv-tx", row.getId(), row.getRevision()); }
            catch (Throwable failure) { deleteFailure.set(failure); }
        });
        deleting.start();
        assertTrue(blocking.commitEntered.await(5, TimeUnit.SECONDS), "delete did not reach commit");
        verifyNoInteractions(sse);

        AtomicReference<AgentInboxItem> claimed = new AtomicReference<>();
        Thread claiming = new Thread(() -> claimed.set(service.claim(row.getId(), "late-run")));
        claiming.start();
        Thread.sleep(100);
        assertTrue(claiming.isAlive(), "claim crossed the conversation gate before delete committed");

        blocking.allowCommit.countDown();
        deleting.join(5_000);
        claiming.join(5_000);
        assertNull(deleteFailure.get());
        assertNotNull(claimed.get());
        assertEquals(AgentInboxService.DELETED, claimed.get().getState());
        assertEquals(AgentInboxService.DELETED, repository.findById(row.getId()).orElseThrow().getState());
        verify(sse, atLeastOnce()).send(eq("conv-tx"), eq("inbox_updated"), anyString());
    }

    private static AiAgentController.AgentChatRequest request() {
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(42L);
        request.setConversationId("conv-tx");
        request.setMessage("must not run");
        request.setSubmissionMode("queue");
        return request;
    }

    private static final class BlockingCommitTransactionManager implements PlatformTransactionManager {
        private final PlatformTransactionManager delegate;
        private final CountDownLatch commitEntered = new CountDownLatch(1);
        private final CountDownLatch allowCommit = new CountDownLatch(1);

        private BlockingCommitTransactionManager(PlatformTransactionManager delegate) {
            this.delegate = delegate;
        }

        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return delegate.getTransaction(definition);
        }

        @Override public void commit(TransactionStatus status) {
            commitEntered.countDown();
            try {
                if (!allowCommit.await(5, TimeUnit.SECONDS)) throw new AssertionError("commit was not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            delegate.commit(status);
        }

        @Override public void rollback(TransactionStatus status) { delegate.rollback(status); }
    }
}
