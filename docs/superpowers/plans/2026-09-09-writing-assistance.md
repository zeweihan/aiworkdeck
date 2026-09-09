# Writing Assistance Implementation Plan

> **For agentic workers:** Use subagent-driven-development with bounded ownership. The user explicitly requested delegation and implementation through release.

**Goal:** Reliable local writing completion and actionable contextual assistance for legal documents.
**Architecture:** Guest DOM controller + authenticated local vocabulary API + atomic UNO operations. Existing insight retrieval runs only on explicit lookup.
**Tech Stack:** Vue3, browser DOM, Spring/JPA, LibreOffice UNO JavaScript bridge; no new dependencies.
**Spec:** docs/superpowers/specs/2026-09-09-writing-assistance-design.md

## Global Constraints

All new sources carry existing SPDX headers. Use npm and JDK21. Work only in editor-completion-538. No automatic third-party calls. Project data cannot become another user's personal dictionary. No engine replacement or global range offsets. Chinese composition owns its keys.

## Task 1: Local vocabulary and lookup API

Files: CompletionEntry.java, CompletionEntryRepository.java, CompletionService.java, CompletionController.java, limited additions to DocInsightEntityRepository and DocInsightService; corresponding service tests.

- [x] GET /api/projects/{pid}/completion returns bounded authorized items {id,text,kind,source,scope,entityId,uses}.
- [x] POST /learn {scope,entries} validates and increments bounded persistent vocabulary; DELETE /entries/{id}, DELETE /learned?scope allow authorized cleanup.
- [x] POST /lookup {kind,text} alone invokes existing insight retrieval under user scope; no LLM extraction.
- [x] Verify denied project/user access, bounds, upserts, and no retrieval on GET/learning.

## Task 2: Atomic document operations

Files: office_thread.js, libreofficeExecutorClient.js, tests/lowa-e2e/completion.mjs.

- [x] get_completion_context returns opaque snapshot token with actual cursor neighborhood.
- [x] accept_completion {token,prefix,text} validates same model/range/context then appends exact suffix in one undoable operation.
- [x] insert_completion_content {token,text|rows} revalidates token and inserts literal data with grouped undo.
- [x] Real engine assertions reject old token at identical text elsewhere, reject changed input/model/selection, allow undo and literal table/text content.

## Task 3: Matching and learning

Files: completionLexicon.js, tests/completion/lexicon.test.mjs.

- [x] extractCompletionEntries(text) identifies seven concrete categories without generating missing data.
- [x] matchCompletionItems(before,items) chooses longest suffix-prefix matches, sources and usage frequency, ignores already complete names.
- [x] Verify Chinese sentence suffixes, multiple candidates, statute articles, names, case numbers, deduplication, and missing segmenter.

## Task 4: Editor experience

Files: new completion controller, host adapter, API functions; narrow seams in IME overlay/editor-main/LibreOfficeEditor.

- [x] Local candidates and source/category labels; Tab/arrow/Esc/mouse while retaining IME focus.
- [x] Record own committed text separately from imported document vocabulary; persist batches, never one network call per key.
- [x] Show available next actions after accepting or finishing known entities; right-click selected text exposes explicit lookup.
- [x] Show source data before explicit content insertion; handle stale tokens and unavailable retrieval without touching document.
- [x] Include preference and learned-record management UI; translate all user-visible labels.
- [x] Verify DOM lifecycle, stale responses, keyboard conflict, and host relay integration.

## Task 5: Review, regression, release

- [x] Inspect actual diffs and perform adversarial review of cursor and permissions.
- [ ] Run relevant unit suites, real LOWA chain, H5/editor builds, required application and desktop verification.
- [ ] Update domain maps and development card with exact validation evidence.
- [ ] Open/merge validated PR and release according to project handbook, verifying artifacts and distribution before claiming online.
