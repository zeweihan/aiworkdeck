<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# AI revision latency investigation — 2026-10-07

Scope: dev-board #1130, #1131, #1132. Read-only local diagnosis of installed macOS 0.53.1, followed by isolated source tests with its r5 LOWA assets. No user document or installed application was changed. Times below are Asia/Shanghai. No document text, prompt, account identifiers or credentials are included.

## Observed incident

The run began at 16:52:50. Its first checklist appeared after 160 seconds; model round 1 took 158 seconds. Round 2 took 212 seconds and only updated the checklist. The first document mutation started after 429 seconds. There was one provider requeue before first content/reasoning. The 22 issued paragraph indices did not overlap; no evidence of duplicated paragraph edits or a replayed run was found.

Editor acknowledgements then grew from 0.77/0.87 seconds to 24.89, 53.51, 72.97 and 94.45 seconds. A read-only process sample showed a busy office worker while the renderer main thread mostly waited. This separates model planning latency from editor mutation latency; the sample alone does not identify a native function or prove a permanent deadlock.

## Fix and reproduction

- Publish a short plan before detailed analysis, and put a completed-item update with the next already-determined tool in the same response. Keep clarification, source verification, final readback and truthful completion requirements. This is a prompt improvement, not a measured guarantee about real-model latency.
- Batch one paragraph's minimal tracked edits under the existing model/action lock and suspended modification listener. Preserve character-level redlines and release locks in `finally`.
- Defer display changes until asynchronous final-text edits settle, including commands starting in balloons/final/margin. Serialize overlapping final-text commands and set the correct author when a queued command actually executes.
- Deduplicate the two review refresh paths for the same completed edit.

On 60 synthetic Chinese contract paragraphs with several spaced changes per paragraph, the original worker's first six edits grew from about 13 seconds to 84 seconds. Replaying the original worker with the committed regression failed its 20-second per-paragraph bound (24.532 seconds on the second edit). The fixed worker completed 24 edits in 24.376 seconds in the first production-source run, including switching to balloons after edit 8. This is one local controlled sample, not a production percentile.

The exported DOCX contained 504 insertions and 288 deletions. Independently reconstructing every paragraph with deletions accepted or insertions rejected matched the full expected final/original arrays. Save/reopen readback at six positions and a subsequent edit also passed.

## Verification scope

- Backend: real context assembly and tool schema plus todo service, 104 tests passed. New prompt tests fail against the prior text.
- Frontend: revision views, worker units, mutation refresh and review reload, 254 tests passed; source editor bundle built. New asynchronous-view tests fail against the prior worker.
- Real asynchronous LOWA replacement: two 180-match batches, starting in all-markup and balloons respectively, switched views from a progress callback before completion. The selection waited, survived the batch and subsequent paragraph readback matched the target. Latest 24-paragraph run took 18.633 seconds.
- Native LOWA: paragraph performance, display switching and DOCX roundtrip are synthetic source-level tests, not an upgrade of the installed client. Real-model planning latency, Windows and the original customer document are not retested by this report.
- Two existing toolbar test harnesses on origin/master lacked newly imported font constants/components. Their baseline failed; fixtures now inject the real constants and assert the first bootstrap refresh clears stale view state without forbidding legitimate subsequent refreshes.

Merge is separate from release: master push workflows perform CI/cache work; desktop release requires a version tag or explicit release workflow. No release, deployment or client upgrade is requested.
