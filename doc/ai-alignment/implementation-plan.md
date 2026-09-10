<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# AI Codex Interaction Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Work only in the assigned worktree and owned files. Do not spawn more subagents. Commit only your task files and report concise evidence.

**Goal:** Implement approved four-scope Markdown memory, unlimited productive agent turns, mid-run steering, durable follow-up queues, and background conversation continuity; submit PR without release.

**Architecture:** Extend existing Java/Spring and Vue contracts. A persisted inbox serializes a conversation's runs and supplies steering at tool boundaries. A permission-checked memory document service is shared by API, tools, context injection and Markdown export; existing model routing, document bridges and run isolation remain.

**Tech Stack:** Java 17-compatible Spring Boot 3/JPA, H2/PostgreSQL, LangChain4j 0.36; Vue3/uni-app, Node test runner, Puppeteer/Electron E2E.

**Spec:** `docs/superpowers/specs/2026-09-10-ai-codex-alignment-research.md` (approved 2026-09-10).

## Global Constraints

- User authorized implementation, commits, PR, all E2E and real Mac testing. Do not release, tag, publish installers, deploy or merge the PR.
- Base: origin/master 5129e9bc; leave original local-desensitize-restore checkout untouched.
- Keep model-facing `content` distinct from user-facing `displayContent`.
- Existing project/conversation ownership, mirrored-read-only, tool file guards and per-user billing apply to every new route and worker.
- Default running submission: steer. Optional queue. Enter sends, Shift+Enter newline; preserve IME composition.
- Fixed model/tool turn count caps are removed, not raised. Keep bounded request retries, cancellation, resource concurrency and real no-progress handling.
- Memory scopes user/project/team/firm are permission domains. A model never chooses an arbitrary user's or organization's identity. Default shared writes: administrators, reads: members; unavailable auth fails closed.
- Private/project memory is not automatically promoted to organization scope. Markdown stored in DB is canonical; downloaded/on-disk Markdown and search indexes derive from the same document service.
- Prefer small focused helpers, not a new agent framework. No new paid services or dependencies unless needed by the accepted behavior.
- New source and test files carry existing SPDX headers. Do not expose credentials in logs, docs or reports.
- Save test outputs to each task report; report failed or skipped checks honestly.

### Task 1: Memory document backend and AI integration

**Owned files:** backend memory document entity/repository/service/controller/tools (new files); existing `service/ai/memory/*`, `service/ai/tools/MemoryTools.java`, `ContextAssemblerService.java`, `repository/MemoryEntryRepository.java`, `version/memory/*`, related memory tests. Do not edit AgentOrchestrator, ChatInterface, useAgentStream or general API JS.

**Interfaces:** New `/api/ai/memory` API, returns `{code:200,data:...}` consistent with memory endpoints. Scope references come from the spaces response, never caller-selected owner IDs. Standard contract:

```text
GET /api/ai/memory/spaces?projectId=N
 data: [{id,scope,label,readable,writable,available,reason}]
GET /api/ai/memory/files?spaceId=S
 data: [{path,title,revision,updatedAt}]
GET /api/ai/memory/file?spaceId=S&path=remember.md
 data: {path,title,content,revision,updatedAt,writable}
PUT /api/ai/memory/file {spaceId,path,content,expectedRevision}
 data: same as GET; create expectedRevision=0
DELETE /api/ai/memory/file?spaceId=S&path=P&expectedRevision=R
GET /api/ai/memory/download?spaceId=S&path=P
 text/markdown attachment (auth checked)
```

Errors use HTTP 401/403/409/400 with readable message (front end handles both HTTP and code envelope). Listing always includes remember.md for valid spaces. Deleting root index is rejected; user can edit/reset content. Topic create/delete maintains valid index links atomically; model-authored prose preserved.

- [ ] Add tests first: user A's document cannot appear in B's project, wrong org denied, index links resolve, path traversal rejected, stale revision fails 409, deletion cannot resurrect after sync, no-team spaces unavailable. Assert actual contents and authorization outcomes.
- [ ] Implement a minimal persisted Markdown document/revision model and unified service, using existing authentication and project membership checks. Reuse AccountService team lookup for desktop role facts; inspect actual existing team/firm payload and never infer role. Shared backend authentication must verify the requesting account. Determine compatible shared storage using current case/team architecture, and report any required paired server code rather than replacing shared storage with local-only folders.
- [ ] Migrate legacy records idempotently with stable paths and source metadata. Adapt legacy save/read paths so UI edits and AI reads converge, fixing scope-filter bypasses. Retain legacy evidence retrieval compatibility. Do not maintain independently writable copies.
- [ ] Inject concise user/project indexes each turn and authorized org indexes; use token-aware bounded context. Add list/read/search/write/edit/delete memory tools, preserving skill visibility and ASK read-only needs. Model writes immediate durable preferences; avoid unconditional duplicate regex/LLM write pipelines. Keep conversation summarization for context compression.
- [ ] Run targeted memory, context assembly, sync and permission tests; report commands/counts. Commit owned files. Write `doc/ai-alignment/memory-report.md` with final API and integration details for UI worker.

**Example behavior to test:**

```java
// Illustrative service-level acceptance sequence; use actual service API names in tests.
// user A writes user/remember.md linking preferences.md, writes preferences.md,
// starts project B conversation: preferences are included.
// user C requests A's space: forbidden, even if supplying a readable projectId.
// write with revision 1 after another writer produced revision 2: conflict, not overwrite.
```

### Task 2: Durable inbox, steering and productive unlimited runs

**Owned files:** `AgentOrchestrator.java`, `AiAgentController.java`, `AgentRunStateService.java`, `AgentRunRecoveryService.java`, `StuckDetector.java`, `subagent/*`, new inbox entity/repository/service/controller, `SseEmitterService` only if necessary, application.yml only relevant caps, related tests. Do not edit ContextAssemblerService or frontend files.

**Interfaces:** Add optional `submissionMode` (`steer` default / `queue`) and `clientRequestId` on existing AgentChatRequest. `/chat` returns receipt in JSON while keeping legacy clients compatible. Queue route contract:

```text
POST /api/agent/chat -> {status:"accepted",messageId,runId,submissionMode,state}
GET /api/agent/inbox/{conversationId} -> {items:[{id,message,displayText,submissionMode,state,position,revision,...}],runId,status}
PATCH /api/agent/inbox/{conversationId}/{messageId}
 {message?,submissionMode?,position?,expectedRevision}
DELETE /api/agent/inbox/{conversationId}/{messageId}?expectedRevision=R
SSE inbox_updated -> snapshot matching GET
SSE input_applied -> {messageId,runId,sequence,message,displayText}
```

If an alternate endpoint shape is necessary, notify controller before UI implementation. Preserve authorization/attachment metadata. Receipt states distinguish accepted/pending/applied; never label queued input executed merely because HTTP accepted it.

- [ ] Write deterministic tests before changes using blocking/scripted model/tool seams: input arrives during model generation, between tools, during last-response completion, with duplicate clientRequestId, after cancellation, with two concurrent HTTP clients. Assert one active consumer and no lost/repeated input.
- [ ] Persist inbox before acknowledging, per-conversation serialize start/finish/drain. Applied steering preserves original objective, inserts user input before next model call, and skips not-yet-started superseded tool requests with paired cancellation results. Cover native and XML tool calls. Keep history chronological using assistant segments around inserted input; emit typed events with IDs/order without breaking old renderers.
- [ ] Queue normal follow-ups until successful completion. Editing/reordering/deleting pending messages is revision checked. Cancellation/error/waiting approval or answer pauses queue; retry duplicates are no-ops. Restart restores pending input and shows interrupted state; never automatically replay uncertain external writes.
- [ ] Remove ordinary/pass/subagent fixed count stops. Replace endless guard-feedback loops with actual no-progress pause after proven repeated failure; legitimate polling with changing result is allowed. Keep cancellation, retry timeout, resource concurrency. Bound resident run output through existing persistence; preserve compaction and protected current task input.
- [ ] Run targeted orchestrator/inbox/subagent/recovery tests, including >100 productive rounds. Existing max-depth tests need behavioral replacement. Commit and write `doc/ai-alignment/inbox-report.md` with final protocol and test evidence.

**Essential concurrency check:**

```text
Start run R; block first tool; POST steer S with idempotency K twice.
Release tool. Next model request contains S once; old batch's pending writes do not execute.
Finish while POST queue Q races. Assert Q remains pending or starts once, never disappears.
Stop new run R2; a late completion from R cannot finish R2 or clear its inbox.
```

### Task 3: Chat steering/queue UI and memory browser

**Owned files:** frontend/src/components/ChatInterface.vue, composables/useAgentStream.js, new AgentInbox/MemoryBrowser components and small helpers, services/api.js, locale files, admin/project entry integration, relevant frontend tests and AI interaction E2E. Backend edits require controller coordination.

**Interfaces:** Consume Task 1/2 contracts above and final reports. Existing app authentication/request helpers and currentConversationId must be used. New memory browser accepts projectId and opens its own dialog, scoped to authorized spaces.

- [ ] Add state tests for accepted/pending/applied receipts, deduplication, stale run events, pending edits and order; sender preserves draft on error and clears only after receipt. Add runnable browser/Electron journey for steering and memory edits using deterministic provider fixtures plus real backend.
- [ ] Keep rich input usable during run. Separate send/stop; default steer with alternate queue action. Preserve IME, attachments, skill selections, selected model and active document. Show queue above composer with edit/delete/move/send-now controls; allow choosing default follow-up behavior persistently.
- [ ] Consume server inbox and input-applied events, preserve chronological bubbles, restore snapshots on reconnect/history switch. Do not let isStreaming reject legitimate steering; do not create duplicate optimistic messages on replay. New conversation only detaches UI and keeps old run visible in history.
- [ ] Add four-space Markdown browser accessible from chat/project and settings. List remember.md/topic files, follow relative links safely, edit/save with revision conflicts, delete topics, download Markdown, show source/version details. Disable unauthorized organization edits honestly.
- [ ] Run all frontend pure tests relevant to modified state, locale/nav/emit checks and build. Run new AI E2E against integrated backend once controller provides port. Commit owned files and write `doc/ai-alignment/ui-report.md`.

**Example UI assertions:**

```js
// Use the existing test harness helpers and real mouse events for uni @tap.
// Start task, type follow-up while streaming, click send, expect Pending -> Applied.
// Queue two messages; move second up, edit first, delete second; reload preserves order.
// Open memory, edit preferences.md, save, reopen: exact Markdown is retained.
```

### Task 4: Integration, review, full E2E and real examples

**Files:** doc/ai-alignment test evidence; integration fixes assigned back to owning worker; workflow only if missing coverage needs adding.

- [ ] Review Task 1–3 diffs independently for spec compliance and quality, then integrate commits into codex/ai-codex-alignment-559. Address findings with scoped tests and re-review.
- [ ] Run complete backend Maven suite, all frontend node test files, emit/locale/nav/SPDX checks and production frontend build. Run all configured E2E scripts enumerated by environment report, not only the new flow.
- [ ] Launch an isolated desktop backend, frontend and real dev Electron app on this Mac using test-only projects/data. Run app, desktop host, LOWA, generated DOCX/reply/big document, meeting/feedback suites and new AI tests. Record any pre-existing/environment failures separately and fix regressions.
- [ ] Exercise real-model examples with existing authorized credentials held only in place: save/reuse memory, long tool work with inserted instruction, queue task and change direction, switch conversation and return, stop/restart. Capture concrete files, logs and screenshots. A simulated model result must be labeled simulated and not substitute for real model testing.
- [ ] Perform final branch review, ensure no version/tag/release/deploy changes, create PR with concrete behavior/validation and linked #559–563. Observe CI. Update board with actual implementation and validation; no claim of all E2E passing if any required suite failed or was skipped.

## Coordination ledger

- Memory and inbox work own disjoint backend paths; UI consumes both published contracts. Each implementer commits only owned files.
- Shared risk: constructor changes affect EvalHarness and tests. Owning backend worker updates its harness call sites; integration review reconciles both changes.
- Shared risk: application.yml is owned by inbox worker; memory worker uses defaults/config class or requests a coordinated edit.
- No scope gaps intentionally deferred. Organization storage/roles and real-model tests must be verified rather than asserted from mock tests.
