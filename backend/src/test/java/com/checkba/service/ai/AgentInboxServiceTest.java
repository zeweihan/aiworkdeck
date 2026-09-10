// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.entity.AgentInboxItem;
import com.checkba.repository.AgentInboxItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentInboxServiceTest {
    private final Map<String, AgentInboxItem> table = new LinkedHashMap<>();
    private AgentInboxItemRepository repository;
    private SseEmitterService sse;
    private AgentInboxService service;

    @BeforeEach
    void setUp() {
        table.clear();
        repository = mock(AgentInboxItemRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> save(inv.getArgument(0)));
        when(repository.save(any())).thenAnswer(inv -> save(inv.getArgument(0)));
        when(repository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(0))));
        when(repository.findConversationIdById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(table.get(inv.getArgument(0))).map(AgentInboxItem::getConversationId));
        when(repository.findByConversationIdOrderByPositionAscCreatedAtAsc(anyString()))
                .thenAnswer(inv -> rows(inv.getArgument(0), null));
        when(repository.findByConversationIdAndStateOrderByPositionAscCreatedAtAsc(anyString(), anyString()))
                .thenAnswer(inv -> rows(inv.getArgument(0), inv.getArgument(1)));
        when(repository.findByConversationIdAndUserIdAndClientRequestId(anyString(), anyLong(), anyString()))
                .thenAnswer(inv -> table.values().stream().filter(i ->
                        Objects.equals(i.getConversationId(), inv.getArgument(0))
                                && Objects.equals(i.getUserId(), inv.getArgument(1))
                                && Objects.equals(i.getClientRequestId(), inv.getArgument(2))).findFirst());
        when(repository.findByRunIdAndState(anyString(), anyString()))
                .thenAnswer(inv -> table.values().stream().filter(i ->
                        Objects.equals(i.getRunId(), inv.getArgument(0))
                                && Objects.equals(i.getState(), inv.getArgument(1))).toList());
        doAnswer(inv -> { table.remove(((AgentInboxItem) inv.getArgument(0)).getId()); return null; })
                .when(repository).delete(any());

        sse = mock(SseEmitterService.class);
        AgentRunStateService states = mock(AgentRunStateService.class);
        when(states.statusName(anyString())).thenReturn("RUNNING");
        service = new AgentInboxService(repository, sse, states);
    }

    @Test
    void persistsBeforeReceiptAndDeduplicatesClientRequestId() {
        AiAgentController.AgentChatRequest request = request("first", "same-key", "queue");
        AiAgentController.ContextItem attachment = new AiAgentController.ContextItem();
        attachment.setId("88");
        attachment.setName("contract.docx");
        request.setContextItems(List.of(attachment));

        AgentInboxItem first = service.submit(request, 7L);
        AgentInboxItem duplicate = service.submit(request("retry body", "same-key", "steer"), 7L);

        assertEquals(first.getId(), duplicate.getId());
        assertEquals(1, table.size());
        assertEquals("first", duplicate.getMessage());
        assertEquals("contract.docx", service.requestOf(first).getContextItems().get(0).getName(),
                "attachment metadata must survive durable serialization");
        assertEquals(AgentInboxService.PENDING, service.receipt(first, "run-active").state());
    }

    @Test
    void claimIsIdempotentAndEmitsTypedAppliedEventOnce() {
        AgentInboxItem row = service.submit(request("steer now", "k", null), 7L);
        clearInvocations(sse);

        service.claim(row.getId(), "run-1");
        service.claim(row.getId(), "run-1");

        AgentInboxItem applied = table.get(row.getId());
        assertEquals(AgentInboxService.APPLIED, applied.getState());
        assertEquals(1L, applied.getAppliedSequence());
        verify(sse, times(1)).send(eq("conv-1"), eq("input_applied"), contains("\"messageId\""));
    }

    @Test
    void pendingEditsAndDeletesRequireExactRevision() {
        AgentInboxItem row = service.submit(request("old", null, "queue"), 7L);
        AgentInboxService.ItemView edited = service.edit("conv-1", row.getId(), "new", "steer", 10L, 1L);
        assertEquals("new", edited.message());
        assertNull(edited.displayText(), "edited canonical text must not retain stale displayText");
        assertEquals("steer", service.requestOf(table.get(row.getId())).getSubmissionMode());
        assertEquals(2L, edited.revision());
        assertThrows(AgentInboxService.RevisionConflict.class,
                () -> service.edit("conv-1", row.getId(), "stale", null, null, 1L));
        assertThrows(AgentInboxService.RevisionConflict.class,
                () -> service.delete("conv-1", row.getId(), 1L));

        service.delete("conv-1", row.getId(), 2L);
        assertTrue(service.snapshot("conv-1").items().isEmpty());
        assertEquals(AgentInboxService.DELETED, table.get(row.getId()).getState());
        AgentInboxItem retry = service.submit(request("old", null, "queue"), 7L);
        assertNotEquals(row.getId(), retry.getId(), "requests without an idempotency key are independent");
    }

    @Test
    void deletedIdempotentRequestCannotBeResurrected() {
        AgentInboxItem row = service.submit(request("later", "durable-key", "queue"), 7L);
        service.delete("conv-1", row.getId(), row.getRevision());

        AgentInboxItem delayedRetry = service.submit(request("later", "durable-key", "queue"), 7L);

        assertEquals(row.getId(), delayedRetry.getId());
        assertEquals(AgentInboxService.DELETED, delayedRetry.getState());
        assertTrue(service.snapshot("conv-1").items().isEmpty());
    }

    @Test
    void reorderUsesZeroBasedIndexAndShiftsOtherPendingItemsDeterministically() {
        AgentInboxItem a = service.submit(request("a", "a", "queue"), 7L);
        AgentInboxItem b = service.submit(request("b", "b", "queue"), 7L);
        AgentInboxItem c = service.submit(request("c", "c", "queue"), 7L);

        service.edit("conv-1", c.getId(), null, null, 0L, c.getRevision());
        assertEquals(List.of("c", "a", "b"), service.snapshot("conv-1").items().stream()
                .map(AgentInboxService.ItemView::message).toList());

        AgentInboxItem currentC = table.get(c.getId());
        service.edit("conv-1", c.getId(), null, null, 2L, currentC.getRevision());
        assertEquals(List.of("a", "b", "c"), service.snapshot("conv-1").items().stream()
                .map(AgentInboxService.ItemView::message).toList());
    }

    @Test
    void concurrentClientsReceiveDistinctOrderedRowsWithoutLoss() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<AgentInboxItem> out = new CopyOnWriteArrayList<>();
        Thread a = new Thread(() -> awaitThenSubmit(start, out, request("a", "a", "queue")));
        Thread b = new Thread(() -> awaitThenSubmit(start, out, request("b", "b", "queue")));
        a.start(); b.start(); start.countDown(); a.join(); b.join();

        assertEquals(2, out.size());
        assertEquals(2, table.size());
        List<Long> positions = table.values().stream().map(AgentInboxItem::getPosition).sorted().toList();
        assertEquals(List.of(0L, 1L), positions);
    }

    @Test
    void restartKeepsPendingAndMarksOnlyUncertainAppliedInputInterrupted() {
        AgentInboxItem pending = service.submit(request("later", "p", "queue"), 7L);
        AgentInboxItem applied = service.submit(request("running", "a", "steer"), 7L);
        service.claim(applied.getId(), "run-old");

        AgentInboxService restarted = new AgentInboxService(repository, sse, mock(AgentRunStateService.class));
        restarted.interruptConversationRuns("conv-1");

        assertEquals(AgentInboxService.PENDING, table.get(pending.getId()).getState());
        assertEquals(AgentInboxService.INTERRUPTED, table.get(applied.getId()).getState());
    }

    private void awaitThenSubmit(CountDownLatch start, List<AgentInboxItem> out,
                                 AiAgentController.AgentChatRequest request) {
        try { start.await(); out.add(service.submit(request, 7L)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private AgentInboxItem save(AgentInboxItem row) { table.put(row.getId(), row); return row; }

    private List<AgentInboxItem> rows(String conversationId, String state) {
        return table.values().stream()
                .filter(i -> Objects.equals(conversationId, i.getConversationId()))
                .filter(i -> state == null || Objects.equals(state, i.getState()))
                .sorted(Comparator.comparing(AgentInboxItem::getPosition)
                        .thenComparing(AgentInboxItem::getCreatedAt))
                .toList();
    }

    private static AiAgentController.AgentChatRequest request(String message, String key, String mode) {
        AiAgentController.AgentChatRequest request = new AiAgentController.AgentChatRequest();
        request.setProjectId(42L);
        request.setConversationId("conv-1");
        request.setMessage(message);
        request.setDisplayText("display " + message);
        request.setModel("qwen/qwen3.7-flash");
        request.setClientRequestId(key);
        request.setSubmissionMode(mode);
        return request;
    }
}
