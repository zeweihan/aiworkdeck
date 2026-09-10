// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.entity.AgentInboxItem;
import com.checkba.repository.AgentInboxItemRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Persistent, revisioned inbox for Agent input. */
@Service
public class AgentInboxService {
    public static final String PENDING = "pending";
    public static final String APPLIED = "applied";
    public static final String INTERRUPTED = "interrupted";
    /** Hidden tombstone retained so a delayed retry cannot resurrect user-deleted input. */
    public static final String DELETED = "deleted";
    public static final String STEER = "steer";
    public static final String QUEUE = "queue";

    public record ItemView(String id, String message, String displayText, String submissionMode,
                           String state, long position, long revision, String clientRequestId,
                           String runId, Long sequence, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Snapshot(List<ItemView> items, String runId, String status) {}
    public record Receipt(String status, String messageId, String runId,
                          String submissionMode, String state) {}

    public static final class RevisionConflict extends RuntimeException {
        public RevisionConflict(String message) { super(message); }
    }

    private final AgentInboxItemRepository repository;
    private final SseEmitterService sseEmitterService;
    private final AgentRunStateService runStateService;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile TransactionTemplate transactions;
    private volatile EntityManager entityManager;

    public AgentInboxService(AgentInboxItemRepository repository,
                             SseEmitterService sseEmitterService,
                             AgentRunStateService runStateService) {
        this.repository = repository;
        this.sseEmitterService = sseEmitterService;
        this.runStateService = runStateService;
    }

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions = template;
    }

    @PersistenceContext
    void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Object conversationLock(String conversationId) {
        return locks.computeIfAbsent(conversationId, ignored -> new Object());
    }

    /** Persist before returning. A duplicate clientRequestId resolves to the original row. */
    public AgentInboxItem submit(AiAgentController.AgentChatRequest request, Long userId) {
        String conv = request.getConversationId();
        synchronized (conversationLock(conv)) {
            String key = normalizeIdempotencyKey(request.getClientRequestId());
            if (key != null) {
                Optional<AgentInboxItem> existing = repository
                        .findByConversationIdAndUserIdAndClientRequestId(conv, userId, key);
                if (existing.isPresent()) return existing.get();
            }

            AgentInboxItem row = new AgentInboxItem();
            row.setId(UUID.randomUUID().toString());
            row.setConversationId(conv);
            row.setProjectId(request.getProjectId());
            row.setUserId(userId);
            row.setClientRequestId(key);
            row.setSubmissionMode(normalizeMode(request.getSubmissionMode()));
            row.setState(PENDING);
            row.setPosition(nextPosition(conv));
            row.setRevision(1L);
            row.setMessage(request.getMessage());
            row.setDisplayText(blankToNull(request.getDisplayText()));
            row.setRequestJson(writeRequest(request));
            LocalDateTime now = LocalDateTime.now();
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            try {
                row = repository.saveAndFlush(row);
            } catch (DataIntegrityViolationException race) {
                if (key == null) throw race;
                row = repository.findByConversationIdAndUserIdAndClientRequestId(conv, userId, key)
                        .orElseThrow(() -> race);
            }
            emitSnapshot(conv);
            return row;
        }
    }

    public AgentInboxItem require(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Inbox message not found"));
    }

    public ItemView view(String id) {
        return view(require(id));
    }

    public String submissionMode(String conversationId, String id) {
        return requireOwned(conversationId, id).getSubmissionMode();
    }

    public AiAgentController.AgentChatRequest requestOf(AgentInboxItem row) {
        try {
            return mapper.readValue(row.getRequestJson(), AiAgentController.AgentChatRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored inbox request is unreadable: " + row.getId(), e);
        }
    }

    public AgentInboxItem claim(String id, String runId) {
        return claim(id, runId, true);
    }

    public AgentInboxItem claim(String id, String runId, boolean emitApplied) {
        String conversationId = conversationId(id);
        synchronized (conversationLock(conversationId)) {
            boolean[] claimedNow = {false};
            AgentInboxItem row = inTransaction(() -> {
                AgentInboxItem current = require(id);
                if (!PENDING.equals(current.getState())) return current;
                claimedNow[0] = true;
                current.setState(APPLIED);
                current.setRunId(runId);
                current.setAppliedSequence(nextSequence(conversationId, runId));
                current.setRevision(current.getRevision() + 1);
                current.setUpdatedAt(LocalDateTime.now());
                AgentInboxItem saved = repository.saveAndFlush(current);
                compactPendingPositions(conversationId);
                return saved;
            });
            if (!claimedNow[0]) return row;
            // The snapshot establishes the new run id before input_applied is interpreted.
            emitSnapshot(conversationId);
            if (emitApplied) emitInputApplied(row);
            return row;
        }
    }

    public String conversationId(String id) {
        return repository.findConversationIdById(id)
                .orElseThrow(() -> new NoSuchElementException("Inbox message not found"));
    }

    /** Re-read after clearing an OSIV persistence context; caller holds the conversation monitor. */
    AgentInboxItem fresh(String id) {
        return inTransaction(() -> require(id));
    }

    public boolean hasPendingSteering(String conversationId) {
        return repository.findByConversationIdAndStateOrderByPositionAscCreatedAtAsc(conversationId, PENDING)
                .stream().anyMatch(i -> STEER.equals(i.getSubmissionMode()));
    }

    public List<AgentInboxItem> claimPendingSteering(String conversationId, String runId) {
        synchronized (conversationLock(conversationId)) {
            List<AgentInboxItem> claimed = inTransaction(() -> {
                List<AgentInboxItem> result = new ArrayList<>();
                for (AgentInboxItem row : repository
                        .findByConversationIdAndStateOrderByPositionAscCreatedAtAsc(conversationId, PENDING)) {
                    if (!STEER.equals(row.getSubmissionMode())) continue;
                    row.setState(APPLIED);
                    row.setRunId(runId);
                    row.setAppliedSequence(nextSequence(conversationId, runId));
                    row.setRevision(row.getRevision() + 1);
                    row.setUpdatedAt(LocalDateTime.now());
                    result.add(repository.saveAndFlush(row));
                }
                if (!result.isEmpty()) compactPendingPositions(conversationId);
                return result;
            });
            if (!claimed.isEmpty()) {
                emitSnapshot(conversationId);
                claimed.forEach(this::emitInputApplied);
            }
            return claimed;
        }
    }

    public Optional<AgentInboxItem> nextQueued(String conversationId) {
        return repository.findByConversationIdAndStateOrderByPositionAscCreatedAtAsc(conversationId, PENDING)
                .stream().filter(i -> QUEUE.equals(i.getSubmissionMode())).findFirst();
    }

    /** After success, every pending input is a normal follow-up; preserve durable position order. */
    public Optional<AgentInboxItem> nextPending(String conversationId) {
        return repository.findByConversationIdAndStateOrderByPositionAscCreatedAtAsc(conversationId, PENDING)
                .stream().findFirst();
    }

    public ItemView edit(String conversationId, String id, String message, String submissionMode,
                         Long position, long expectedRevision) {
        synchronized (conversationLock(conversationId)) {
            ItemView result = inTransaction(() -> {
                AgentInboxItem row = requireOwned(conversationId, id);
                assertMutable(row, expectedRevision);
                AiAgentController.AgentChatRequest storedRequest = null;
                if (message != null) {
                    if (message.isBlank()) throw new IllegalArgumentException("Message cannot be empty");
                    row.setMessage(message);
                    storedRequest = requestOf(row);
                    storedRequest.setMessage(message);
                    // displayText described the old canonical message. PATCH has no separate displayText
                    // field, so clearing it makes every renderer fall back to the edited canonical text.
                    storedRequest.setDisplayText(null);
                    row.setDisplayText(null);
                }
                if (submissionMode != null) {
                    row.setSubmissionMode(normalizeMode(submissionMode));
                    if (storedRequest == null) storedRequest = requestOf(row);
                    storedRequest.setSubmissionMode(row.getSubmissionMode());
                }
                if (storedRequest != null) row.setRequestJson(writeRequest(storedRequest));
                if (position != null) reorderPending(conversationId, row, position);
                row.setRevision(row.getRevision() + 1);
                row.setUpdatedAt(LocalDateTime.now());
                return view(repository.saveAndFlush(row));
            });
            emitSnapshot(conversationId);
            return result;
        }
    }

    public Snapshot delete(String conversationId, String id, long expectedRevision) {
        synchronized (conversationLock(conversationId)) {
            Snapshot snapshot = inTransaction(() -> {
                AgentInboxItem row = requireOwned(conversationId, id);
                assertMutable(row, expectedRevision);
                row.setState(DELETED);
                row.setRevision(row.getRevision() + 1);
                row.setUpdatedAt(LocalDateTime.now());
                repository.saveAndFlush(row);
                compactPendingPositions(conversationId);
                return snapshot(conversationId);
            });
            emitSnapshot(snapshot, conversationId);
            return snapshot;
        }
    }

    @Transactional
    public void interruptRun(String runId) {
        if (runId == null) return;
        for (AgentInboxItem row : repository.findByRunIdAndState(runId, APPLIED)) {
            row.setState(INTERRUPTED);
            row.setRevision(row.getRevision() + 1);
            row.setUpdatedAt(LocalDateTime.now());
            repository.save(row);
        }
    }

    public void interruptConversationRuns(String conversationId) {
        synchronized (conversationLock(conversationId)) {
            inTransaction(() -> {
                for (AgentInboxItem row : repository
                        .findByConversationIdOrderByPositionAscCreatedAtAsc(conversationId)) {
                    if (!APPLIED.equals(row.getState())) continue;
                    row.setState(INTERRUPTED);
                    row.setRevision(row.getRevision() + 1);
                    row.setUpdatedAt(LocalDateTime.now());
                    repository.save(row);
                }
                return null;
            });
            emitSnapshot(conversationId);
        }
    }

    public Receipt receipt(AgentInboxItem row, String activeRunId) {
        AgentInboxItem current = require(row.getId());
        return new Receipt("accepted", current.getId(),
                current.getRunId() != null ? current.getRunId() : activeRunId,
                current.getSubmissionMode(), current.getState());
    }

    public Snapshot snapshot(String conversationId) {
        List<AgentInboxItem> rows = repository.findByConversationIdOrderByPositionAscCreatedAtAsc(conversationId)
                .stream().filter(i -> !DELETED.equals(i.getState())).toList();
        String runId = rows.stream().filter(i -> i.getRunId() != null)
                .max(Comparator.comparing(AgentInboxItem::getUpdatedAt)).map(AgentInboxItem::getRunId).orElse(null);
        return new Snapshot(rows.stream().map(AgentInboxService::view).toList(), runId,
                runStateService.statusName(conversationId));
    }

    private AgentInboxItem requireOwned(String conversationId, String id) {
        AgentInboxItem row = require(id);
        if (!Objects.equals(conversationId, row.getConversationId())) {
            throw new NoSuchElementException("Inbox message not found");
        }
        return row;
    }

    private static void assertMutable(AgentInboxItem row, long expectedRevision) {
        if (!PENDING.equals(row.getState())) throw new RevisionConflict("Only pending input can be changed");
        if (row.getRevision() != expectedRevision) throw new RevisionConflict("Inbox revision is stale");
    }

    private long nextPosition(String conversationId) {
        return repository.findByConversationIdAndStateOrderByPositionAscCreatedAtAsc(conversationId, PENDING).stream()
                .map(AgentInboxItem::getPosition).filter(Objects::nonNull).max(Long::compareTo).orElse(-1L) + 1L;
    }

    private void compactPendingPositions(String conversationId) {
        List<AgentInboxItem> pending = repository
                .findByConversationIdAndStateOrderByPositionAscCreatedAtAsc(conversationId, PENDING);
        for (int i = 0; i < pending.size(); i++) {
            AgentInboxItem item = pending.get(i);
            if (Objects.equals(item.getPosition(), (long) i)) continue;
            item.setPosition((long) i);
            item.setRevision(item.getRevision() + 1);
            item.setUpdatedAt(LocalDateTime.now());
            repository.save(item);
        }
    }

    /** position is a zero-based destination index among pending items. */
    private void reorderPending(String conversationId, AgentInboxItem target, long requestedIndex) {
        List<AgentInboxItem> pending = new ArrayList<>(repository
                .findByConversationIdAndStateOrderByPositionAscCreatedAtAsc(conversationId, PENDING));
        pending.removeIf(i -> Objects.equals(i.getId(), target.getId()));
        int index = (int) Math.max(0, Math.min(requestedIndex, pending.size()));
        pending.add(index, target);
        for (int i = 0; i < pending.size(); i++) {
            AgentInboxItem item = pending.get(i);
            item.setPosition((long) i);
            if (!Objects.equals(item.getId(), target.getId())) {
                item.setRevision(item.getRevision() + 1);
                item.setUpdatedAt(LocalDateTime.now());
                repository.save(item);
            }
        }
    }

    private long nextSequence(String conversationId, String runId) {
        return repository.findByConversationIdOrderByPositionAscCreatedAtAsc(conversationId).stream()
                .filter(i -> Objects.equals(runId, i.getRunId()))
                .map(AgentInboxItem::getAppliedSequence).filter(Objects::nonNull)
                .max(Long::compareTo).orElse(0L) + 1L;
    }

    /** The transaction must commit before the caller releases the per-conversation monitor. */
    private <T> T inTransaction(Supplier<T> work) {
        TransactionTemplate template = transactions;
        if (template == null) return work.get();
        return template.execute(status -> {
            EntityManager em = entityManager;
            if (em != null) em.clear();
            return work.get();
        });
    }

    private String writeRequest(AiAgentController.AgentChatRequest request) {
        try {
            return mapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalArgumentException("Request metadata cannot be stored", e);
        }
    }

    private void emitInputApplied(AgentInboxItem row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", row.getId());
        payload.put("runId", row.getRunId());
        payload.put("sequence", row.getAppliedSequence());
        payload.put("message", row.getMessage());
        payload.put("displayText", row.getDisplayText());
        payload.put("clientRequestId", row.getClientRequestId());
        payload.put("submissionMode", row.getSubmissionMode());
        sendJson(row.getConversationId(), "input_applied", payload);
    }

    public void emitSnapshot(String conversationId) {
        emitSnapshot(snapshot(conversationId), conversationId);
    }

    private void emitSnapshot(Snapshot snapshot, String conversationId) {
        sendJson(conversationId, "inbox_updated", snapshot);
    }

    private void sendJson(String conversationId, String event, Object payload) {
        try {
            sseEmitterService.send(conversationId, event, mapper.writeValueAsString(payload));
        } catch (Exception e) {
            // The durable row is authoritative; reconnect GET restores it.
        }
    }

    private static ItemView view(AgentInboxItem i) {
        return new ItemView(i.getId(), i.getMessage(), i.getDisplayText(), i.getSubmissionMode(),
                i.getState(), i.getPosition(), i.getRevision(), i.getClientRequestId(), i.getRunId(),
                i.getAppliedSequence(), i.getCreatedAt(), i.getUpdatedAt());
    }

    public static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank() || STEER.equalsIgnoreCase(mode)) return STEER;
        if (QUEUE.equalsIgnoreCase(mode)) return QUEUE;
        throw new IllegalArgumentException("submissionMode must be steer or queue");
    }

    private static String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) return null;
        String trimmed = key.trim();
        if (trimmed.length() > 128) throw new IllegalArgumentException("clientRequestId is too long");
        return trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
