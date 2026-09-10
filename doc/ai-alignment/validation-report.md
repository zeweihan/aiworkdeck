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

All five existing frontend failures are in `tests/revision-view/revisionView.test.mjs`:
its source-loading test helper executes an import through `new Function`, raising
`SyntaxError: Cannot use import statement outside a module`. None of these test or toolbar
files were changed by this task. The first Maven launch used the machine's default JDK 26
and failed during Lombok compilation; the full result above is the corrected Java 21 run.

Local raw logs use `/tmp/ai-alignment-baseline-*.log`. Additional LOWA suite results are
collected in `/tmp/ai-alignment-lowa-results.json`; final integrated results will be added
below. A clean baseline passing does not substitute for testing the integrated feature.

## Paired website implementation

Website base `bf7284c`, shared-memory commit `8fc6200`. Shared storage/route acceptance,
78 existing team cases, 27 existing collaboration-directory cases, TypeScript check,
focused lint and production build pass. Tests use generated local accounts and an isolated
SQLite database. See website `doc/shared-memory-contract.md` for transport and configuration.
