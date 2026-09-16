# Semantic writing assistance Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Three explicitly authorized domain workflows run in parallel with non-overlapping files; the parent owns shared contracts and integration.

**Goal:** Deliver source-grounded contextual writing assistance for litigation, contracts and diligence/opinions inside the real Writer editor.

**Architecture:** Authorized project documents plus live editor text form a bounded, versioned context. Pure domain profiles extract attributed facts and safe local suggestions; explicit sentence/paragraph requests use the existing model channel with cancellation and output limits. Sources and permissions are revalidated before the engine's synchronous cursor/text guard inserts one undoable edit.

**Tech Stack:** Java 21 / Spring, Vue and plain guest JavaScript, existing LOWA/UNO, JUnit and Node tests.

**Spec:** ../specs/2026-09-16-semantic-writing.md

## Global constraints

- All three domain profiles ship together. No automatic release or installation.
- User authorized synthetic data, expected answers and reasonable live-model cost. Private reference files remain local; committed/live-provider fixtures are fully synthetic.
- Existing model/account routes and token accounting remain authoritative. No keys in code/logs. No query per keystroke.
- Unknown source/role/condition is not a fact; no automatic paid external legal/company lookup.
- Every new source has SPDX; only scoped files are staged. Domain agents do not commit a shared Git index.

## Tasks and acceptance ledger

- [x] Shared types/profiles: WritingTypes/ WritingProfile; Source.id=active-document is current live text. Other sources are authenticated project file slices. Attributed facts carry exact source quotes.
- [x] Domain A (#708): LitigationWritingProfile + test + synthetic fixtures; role/claim/request distinction; disputed payment does not become admitted fact.
- [x] Domain B (#709): ContractWritingProfile + test + fixtures; document-scoped roles/definitions; obligation does not imply performance; conditions and arithmetic conflicts preserved.
- [x] Domain C (#710): DiligenceWritingProfile + test + fixtures; cutoff, material availability, source-vs-verification distinction; gaps/conflicts are first-class.
- [x] Context (#707): WritingContextService + tests; authenticate user/project/document, bounded selected project texts, hash sources, revalidate current files at acceptance; project-scoped stance/cutoff settings with write checks.
- [x] Generation (#711): cancellable auxiliary-model handle in ChatModelFactory/OpenRouterStreamingChatModel, WritingSuggestionService and controller, source-bound JSON validation and structured usage; tests for no source, unsupported IDs/quotes, unknown numbers, cancellation, expired requests and cross-user/project access.
- [x] Editor (#712): guest semantic-writing panel + host relay/API adapter; no preview writes, explicit sentence/paragraph/selected-text rewrite actions, project context editor, source preview; request cancellation and stale guards.
- [x] Engine: capture semantic context and accept semantic text commands; bounded whole-sentence/paragraph insertion uses existing completion token/check and one undo group; selection replacement only when explicitly previewed and expected text matches.
- [x] Local contextual fields: explicit local suggestions use role/definition facts; ordinary typing retains existing local word completion; unknown type falls back to existing word completion. Automatic ranking integration is a later milestone.
- [x] Initial evaluation: non-mirrored domain tests, context/generation auth and negative tests, real LOWA accept/undo/stale cases, live models against 18 frozen synthetic development cases (not the held-out release set). Record failures, cost and complete-path latency; no timing-constant claims.
- [ ] Docs, independent review, CI and merge; dev-board records and pending user retest. No release.

## Integration rulings

- Current request executes the approved plan without asking for datasets or per-stage approval; fixture authors and expected-answer reviewers remain independent where possible.
- Current document is Source.id=active-document. Explicit project stance/cutoff are persisted per project, never global personal facts.
- Related-edit suggestions remain a later milestone. This iteration only replaces one explicit selection or inserts at the current cursor. No related-edit list has shipped.
- Automatic AI suggestions remain opt-in and are gated by observed latency; manual generation always available when configured. Existing deterministic local completion remains functional without a model.
- Full-context extraction is bounded and performed outside the keystroke path. Source versions are re-read on accept rather than relying on cache invalidation alone.
- Unknown quantities/claims must be rejected or returned as missing information. Source quotes are necessary but not sufficient; domain constraints and live negative evaluations are additional gates.

## Remaining release gates

- The broader spec remains a staged roadmap, not a claim that this iteration has reached IDE parity.
- Complete the separate 50 positive + 20 negative held-out cases per domain; the coverage matrix is a plan, not 210 executed cases.
- Measure at least 200 end-to-end local interactions and live latency distributions before enabling automatic AI suggestions.
- Implement and validate related-edit previews and contextual ranking integration in later milestones.
- Run lawyer review on permitted deidentified real-project samples. Local private reference material has not been uploaded to the provider or public repository.
- Release/install remain outside the present merge authorization.
