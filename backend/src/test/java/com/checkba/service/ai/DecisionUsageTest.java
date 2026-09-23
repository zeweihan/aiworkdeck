// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.model.entity.TokenUsage;
import com.checkba.repository.TokenUsageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionUsageTest {
    private final TokenUsageRepository repository = mock(TokenUsageRepository.class);
    private final ChatModelFactory factory = mock(ChatModelFactory.class);
    private final PlatformUsageAccountant accountant = mock(PlatformUsageAccountant.class);
    private final TokenUsageService service = new TokenUsageService(repository, factory, accountant);

    @Test void byokRetainsReceiptAsLocalStatisticsWithoutPlatformReconciliation() {
        BigDecimal receipt = new BigDecimal("0.000023478");
        service.recordDecisionUsage(7L, 9L, "conversation", DecisionAssistService.MODEL, 559, 31, receipt, false);
        ArgumentCaptor<TokenUsage> saved = ArgumentCaptor.forClass(TokenUsage.class);
        verify(repository).save(saved.capture());
        TokenUsage entry = saved.getValue();
        assertEquals(receipt, entry.getCost());
        assertEquals(PlatformUsageAccountant.SOURCE_ESTIMATE, entry.getCostSource());
        assertEquals(7L, entry.getProjectId());
        assertEquals(9L, entry.getUserId());
        assertEquals("conversation", entry.getConversationId());
        assertEquals(590, entry.getTotalTokens());
        verifyNoInteractions(factory, accountant);
    }

    @Test void platformDoesNotAddReceiptToCumulativeCostAndReconcilesOnlyAfterCommit() {
        when(repository.save(any())).thenAnswer(invocation -> {
            TokenUsage entry = invocation.getArgument(0);
            entry.setId(41L);
            return entry;
        });
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.recordDecisionUsage(7L, 9L, "conversation", DecisionAssistService.MODEL,
                    559, 31, new BigDecimal("0.000023478"), true);
            ArgumentCaptor<TokenUsage> saved = ArgumentCaptor.forClass(TokenUsage.class);
            verify(repository).save(saved.capture());
            assertNull(saved.getValue().getCost());
            assertEquals(PlatformUsageAccountant.SOURCE_PLATFORM, saved.getValue().getCostSource());
            verifyNoInteractions(accountant, factory);
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, callbacks.size());
            callbacks.get(0).afterCommit();
            verify(accountant, times(1)).reconcileAsync(41L, 9L);
            verifyNoMoreInteractions(accountant);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
