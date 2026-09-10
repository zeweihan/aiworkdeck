<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# AI interaction alignment validation

Status: implementation and integration in progress. No release, merge, or deployment.

## Clean-base results

Base `5129e9bc` (plan-only commit `77ce9c3a`), tested on this Mac with explicit Java 21,
isolated H2/user home, installed Chrome, and the installed LOWA engine with this branch's
rebuilt editor bridge. These results establish existing failures before feature integration.

| Check | Result |
| --- | --- |
| Backend Maven full suite | 3,537 tests; 0 failures, 0 errors, 14 conditional skips |
| Frontend all `*.test.mjs` | 1,136 tests; 1,131 pass, 5 pre-existing failures |
| Desktop Node suite | 145 tests; 143 pass, 2 conditional skips |
| Emit / locales / full navigation checks | All pass |
| Production H5 build | Pass |
| Full LOWA E2E | 547 pass, 0 failures |
| Inline review LOWA E2E | Pass |
| Writing UI LOWA E2E | Existing failure at `writing-ui.mjs:62`: Qt popup screenshot differs from dismissed state |
| Writing caret / link preview / completion / reply insertion E2E | All pass |
| Generated DOCX LOWA E2E | Existing failure at `generated-docx.mjs:125`: injected table failure returned success |
| 150-page LOWA performance E2E, three runs | 6 criteria pass; export median 25.45 s exceeds the existing 10 s threshold |
| App browser E2E | 129 steps pass, 4 baseline navigation failures; details below |
| Real Electron desktop E2E | Pass: Word toolbar/edit/save/download, drag links, BrowserView keep-alive and cleanup |
| Real Electron writing E2E | Pass: real document entity extraction, IME/Tab completion, review, links, autosave and DOCX readback |
| Real Electron meeting E2E | Pass after updating stale navigation/license setup and waiting for asynchronous notice/recording readiness; 188,630-byte audio readback |
| Real Electron feedback E2E | Pass: screenshot selection, synthetic microphone, persistence and attachment readback |

All five existing frontend failures are in `tests/revision-view/revisionView.test.mjs`:
its source-loading test helper executes an import through `new Function`, raising
`SyntaxError: Cannot use import statement outside a module`. None of these test or toolbar
files were changed by this task. The first Maven launch used the machine's default JDK 26
and failed during Lombok compilation; the full result above is the corrected Java 21 run.

The baseline app run first failed navigating `/pages/userprofile` because Chrome returned
`DOM.resolveNode: Node with given id does not belong to the document`; three subsequent
personal-section tab assertions failed after that. The loopback browser fixture was skipped
because the initial test backend lacked `SECURITY_BROWSER_PROXY_E2E_ALLOWED_HOSTS=127.0.0.1`;
the test recipe now includes it for the integrated run. The baseline deliberately used
`AI_E2E=0`; real-model interaction validation is still outstanding.

Local raw logs use `/tmp/ai-alignment-baseline-*.log`. Additional LOWA suite results are
collected in `/tmp/ai-alignment-lowa-results.json`; final integrated results will be added
below. A clean baseline passing does not substitute for testing the integrated feature.

## Paired website implementation

Website base `bf7284c`, shared-memory commit `8fc6200`. Shared storage/route acceptance,
78 existing team cases, 27 existing collaboration-directory cases, TypeScript check,
focused lint and production build pass. The complete cn/intl contract matrix (17 scripts
per site, 34 runs) passes. Tests use generated local accounts and an isolated SQLite
database. A live local production Next server also passes desktop Bearer write → internal
member read across the same canonical document. See website `doc/shared-memory-contract.md`
for transport and configuration. Website draft PR: https://github.com/zeweihan/aiworkdeck_website/pull/172.

## Baseline real provider checks

Real vision smoke: 3/3 pass. Real text/tool smoke: 2/3 pass; the create-document case
expects the first tool to be write_file/write_docx, but the live model first chose
get_project_context. The live model catalog contract reports 4 existing discrepancies:
DeepSeek Flash/Pro and GLM 5.2 price drift, and Qwen 3.8 Max absent from the upstream
catalog. These are recorded separately from the new feature acceptance cases.
Log: `/tmp/ai-alignment-live-smoke.log`.
