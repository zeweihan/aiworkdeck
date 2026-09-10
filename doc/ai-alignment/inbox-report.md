<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# Durable Agent inbox, steering, and unlimited productive runs

## HTTP and SSE protocol

`AgentChatRequest` accepts optional `submissionMode` (`steer` by default, or `queue`) and an optional idempotency key `clientRequestId` (maximum 128 characters). The key is scoped to the authenticated user and conversation. The complete request JSON is stored before the response, including attachments, selected context, active document, skills, model, and client capability.

`POST /api/agent/chat` returns:

```json
{"status":"accepted","messageId":"...","runId":"...","submissionMode":"steer","state":"applied"}
```

`state` is `pending` while waiting and `applied` once assigned to a run. Authentication and validation failures use HTTP 400/401/403/409 and `{ "status": "error", "message": "..." }`. The request that starts a run is rendered from this receipt and does not emit `input_applied`.

`GET /api/agent/inbox/{conversationId}` returns:

```json
{
  "items": [{
    "id": "...", "message": "model text", "displayText": "optional UI text",
    "submissionMode": "queue", "state": "pending", "position": 0, "revision": 1,
    "clientRequestId": "...", "runId": null, "sequence": null,
    "createdAt": "2026-09-10T12:00:00", "updatedAt": "2026-09-10T12:00:00"
  }],
  "runId": "...",
  "status": "RUNNING"
}
```

Item states are lowercase `pending`, `applied`, or `interrupted`. A deletion creates an internal `deleted` tombstone so a delayed duplicate POST cannot recreate the item; tombstones are omitted from snapshots. Top-level run status preserves the existing uppercase run-state names and can be null.

`PATCH /api/agent/inbox/{conversationId}/{messageId}` accepts `message?`, `submissionMode?`, `position?`, and required `expectedRevision`. `position` is the desired zero-based index among pending items; peers are shifted and reindexed in one transaction. Editing canonical text clears the old `displayText`, which makes clients fall back to the edited text, and updates the stored request used by the model. Stale or non-pending changes return 409.

`DELETE /api/agent/inbox/{conversationId}/{messageId}?expectedRevision=R` tombstones a pending item and returns the new snapshot. All inbox routes use existing authenticated conversation access checks.

`inbox_updated` carries exactly the GET snapshot. `input_applied` carries:

```json
{
  "messageId":"...", "runId":"...", "sequence":1,
  "message":"model text", "displayText":"optional UI text",
  "clientRequestId":"...", "submissionMode":"steer"
}
```

For automatic queue drain, the new-run `inbox_updated` is emitted before `input_applied`. Existing SSE infrastructure gives both events monotonic IDs and replay buffering. Sequence is monotonic within a run.

## Execution behavior

The inbox and run registration share a per-conversation critical section. The server re-reads and claims a pending row while holding it, publishes the run guard, then releases the gate before model execution. This permits mid-run POSTs while preventing a second consumer. Successful finalization removes the guard and claims one next item under the same gate. Cancellation, error, approval wait, question wait, and no-progress pause do not drain the queue.

Steering is applied before model calls, between native or XML tool calls, after the last tool, and before interpreting a no-tool response as final. The current assistant segment is saved before the inserted USER history row, then subsequent output uses a new assistant row. Existing model history remains, so the original objective is preserved. Pending calls in the old native batch receive `ToolExecutionResultMessage` cancellation partners; XML calls receive equivalent cancellation feedback. Attachment and active-document metadata are restored from the durable request and passed through the existing context assembler before the next model request.

Restart recovery leaves pending rows untouched, marks inputs assigned to uncertain old runs `interrupted`, and never replays external writes automatically. Idempotency receipts survive user deletion.

The ordinary 30-step, pass 120-step, and subagent 6-round caps were removed. Productive main-loop continuations break the synchronous Java call stack every 64 rounds, while cancellation, request retry budgets, timeouts, compaction, token budget, and subagent concurrency remain. The no-progress detector includes the tool observation in its bounded signature: repeated polling with changing output continues, while the same call and same observation first receives a corrective nudge and then moves the run to `PAUSED` with reason `no_progress`.

ASK mode advertises and executes only `memory_list`, `memory_read`, and `memory_search`, through both native and XML paths. Memory write/edit/delete remain hidden and are rejected defensively. Those three read tools are restored after an active skill narrows the general tool list, and successful ASK reads may continue to a model answer.

## Verification

All commands used Java 21 explicitly because the machine's default JDK is incompatible with Lombok.

- Clean baseline supplied by integration worker: 3,537 tests, 0 failures, 0 errors, 14 skipped.
- Focused inbox/orchestrator run: `AgentInboxServiceTest,AgentOrchestratorInboxTest` passed, including idempotent tombstones, two-item moves, concurrent submissions, native and XML safe boundaries, attachment steering, cancellation pause, and finalize/drain ordering.
- Focused final run: `AgentInboxControllerTest,AgentInboxServiceTest,AgentOrchestratorInboxTest,AiAgentControllerTest,AgentRunRecoveryServiceTest,StuckDetectorTest,AgentOrchestratorPassDepthTest,AgentSkillUpdateEventTest,AgentTextLanguageTest,SubAgentServiceTest`: 68 tests, 0 failures, 0 errors, 0 skipped.
- The main-loop test performs 1,001 productive tool rounds and reaches a final answer without growing the synchronous stack. The subagent test performs more than 100 productive rounds.

## Integration notes

The per-conversation gate is process-local. Current deployment uses one backend consumer; a future multi-instance deployment would require a database advisory/pessimistic lock around claim and run ownership. The durable unique idempotency constraint still protects duplicate receipts across instances, but it is not a distributed run lease.

The schema is created by the project's existing Hibernate `ddl-auto: update` policy. No release, deployment, version, frontend, context assembler, or memory implementation files were changed.
