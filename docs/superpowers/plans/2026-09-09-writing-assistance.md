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
- [x] Project/account deletion removes only its `p:<id>` / `u:<id>` learned scope, serialized with learning by the same parent-row lock; failed unified-account propagation leaves local vocabulary untouched (#791).

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
- [x] Dismiss the Qt context menu before showing a selected-text lookup preview while preserving selection/token and ordinary context menus (#790).

## Task 5: Review, regression, release

- [x] Inspect actual diffs and perform adversarial review of cursor and permissions.
- [x] Run relevant unit suites, real LOWA chain, H5/editor builds, required application and desktop verification.
- [x] Update domain maps with the implemented interaction and vocabulary lifecycle contracts.
- [x] Add final release evidence to the development card after distribution verification.
- [x] Open and merge the validated implementation and follow-up PRs (#784, #787, #790, #791).
- [x] Release according to the project handbook, verifying artifacts and distribution before claiming online.

### Verification record

- Local backend suite: 3460 tests, 0 failures/errors, 10 configured skips. Merged branch backend/frontend CI passed.
- Frontend completion: 47 passed; project-home: 416 passed; Office add-in: 307 passed.
- Real LOWA completion/undo/stale-token/export cases and real mouse context menu + explicit preview/table insertion passed. English UNO locale and writing preferences verified.
- Real Electron project vocabulary → IME → two candidates → Tab → autosave → downloaded DOCX passed; zero external lookup requests. Isolated temporary project folder, cleanup verified.
- Full app: 133 passed (explicitly skipped live AI and unconfigured local-browser allowlist). Pre-feature LOWA status-bar assertion and existing desktop clipboard empty-format assertion remain documented baseline/environment failures.
- Release preparation includes the main branch’s four required native runtime packs before v0.38.0 distribution; tracked separately in dev-board#544.
- Follow-ups #790 and #791 are merged with passing CI. Both cloud backends now run the verified lifecycle follow-up artifact. All 4/4 runtime packs passed both-site content/signature/pointer verification before tag v0.38.0 at 761aea63. Both formal installers are built; the Mac installer passed signature/notarization and isolated startup acceptance. Release workflow and mirror synchronization passed; both website download pages and version manifests report v0.38.0.

- Formal v0.38.0 release: https://github.com/zeweihan/aiworkdeck/releases/tag/v0.38.0 (published 2026-09-09 20:02:40 +08), application tag `761aea63`. Mac: 463971508 bytes; Windows: 505423019 bytes; actual downloads match GitHub asset SHA256. Mac passes system signature/notarization checks and isolated packaged-app startup with bundled JRE/backend, four nonzero optional-component cards, Not now preference persistence, no legacy extraction and no component installation. Test state is a legacy-trial fixture, not fresh-account activation.
- The first Mac CI attempt exposed a pre-existing lifecycle-test failure path that left a fake service alive; the unchanged tag passed its rerun. Test-only cleanup and real HTTP readiness assertions were independently verified and merged in #792 (dev-board#546); product payload unchanged.

- Distribution: Windows full downloads from GitHub, Beijing storage and OSS match `ce81ad38c082f8f68326e92790e8423b70f5b3daea979b90f72ced0b7cc328f6`; actual Mac GitHub download matches `3b604d781c2f24ec7cea231342e1ec0bc267fdba709918e83955d68e95658b2a`, with matching mirror metadata/OSS HEAD. Both public download pages return 200 and the correct version; unauthenticated download actions return 401. Manual authenticated 302 clicks and Windows GUI execution were outside this verification.
- Final implementation/release record added to dev-board#538; status is 待复测. Workflow evidence: https://github.com/zeweihan/aiworkdeck/actions/runs/34345191228.
