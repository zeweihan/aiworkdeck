# Task 3 UI implementation report

## Delivered behavior

- The chat composer remains editable while a run is active. Stop, default follow-up mode, alternate send, and send are separate controls. The default (`steer` or `queue`) is stored locally.
- Each draft gets a `clientRequestId` whose retry identity covers the conversation, project, model, mode, skills, active document, exact rich-editor HTML, files, pasted images, and submission mode. An identical draft shares one in-flight HTTP request. A failed attempt keeps its draft and attachments and reuses its request ID only for an exact retry.
- The input clears only after an accepted receipt and only if the exact rich draft, attachments, configuration, and conversation are unchanged. A late receipt from a detached conversation is returned to its caller without changing the current conversation UI.
- The pending-message panel consumes server snapshots, edits and deletes with `expectedRevision`, reorders with a zero-based visible pending index, and changes a queued item to `steer` for Send now. HTTP 409 refreshes the authoritative snapshot.
- `input_applied` is rendered once by message ID and increasing sequence for the active run. Snapshot state does not mark an event as rendered. Empty assistant placeholders are coalesced so a batch of applied steering inputs leaves one assistant segment.
- New Chat detaches the local SSE and preserves the old server run. Stop remains the explicit cancellation action.
- The live acceptance journey opens New Chat during a fixture-held run, proves the backend `runId` and `RUNNING` status are unchanged, returns through History, and requires Stop to be restored before continuing the steer/queue flow.
- The Markdown memory dialog lists the authorized personal/project/team/firm spaces, files, source path, revision and update time. It follows only safe relative links to listed Markdown files, preserves exact Markdown on save, refetches on revision conflicts, protects `remember.md` from deletion, downloads raw Markdown, and disables unauthorized writes. It is available from chat and Settings.
- The composer footer, inbox rows, and memory dialog wrap into usable layouts at the current 300–360 px chat-pane width.

## Protocol notes

- Chat POST consumes the raw HTTP 200 receipt `{status,messageId,runId,submissionMode,state}`. A `deleted` idempotency tombstone is terminal and is not re-added.
- `inbox_updated` replaces local inbox data with the GET snapshot. `input_applied` consumes `{messageId,runId,sequence,message,displayText,clientRequestId,submissionMode}`. The initial POST that starts a run is represented by the optimistic user/assistant pair and its receipt; it does not require or expect `input_applied`.
- Memory consumes `{code:200,data}`. Spaces/files are direct arrays, file/save return the direct file object, DELETE returns `{deleted:true}`, and download returns a raw UTF-8 Markdown body. Space IDs remain opaque and are only round-tripped.

## Verification

- `node --test tests/ai-alignment/*.test.mjs tests/project-home/*.test.mjs`: 470 passed, 0 failed.
- `npm run check:emits`: passed, 109 Vue files scanned.
- `npm run check:locales`: passed, 25 namespaces matched.
- `npm run check:nav`: passed.
- `npm run build:h5`: completed successfully. Existing Sass deprecation and Vite dynamic/static import warnings remain unchanged.
- `node --check tests/ai-alignment-e2e/run.mjs`: passed.
- Integrated Chromium journey against the deterministic model fixture and real Task 1/2 backend: exit 0. It exercised narrow-pane submit, steer/queue, edit/reorder, stop, Send now, memory navigation, save, and exact API readback.
- Integrated real Electron journey against the same fixture/backend: exit 0. It verified the desktop preload-injected backend before exercising the same behavior through a native macOS Electron window.
- The later New Chat detach/History reattach assertions are syntax-checked but intentionally not rerun against port 9797 before this handoff; the root integration run will exercise them against the final backend package. The model fixture now holds its first streaming response until Stop completes, so this path cannot pass because a fixed delay happened to finish at the right time.

## Integrated browser journey

Run against an isolated desktop-profile backend after the Task 1/2 commits are integrated:

```sh
cd frontend
AI_ALIGNMENT_E2E_ISOLATED=1 \
AI_ALIGNMENT_E2E_BASE=http://127.0.0.1:5174 \
AI_ALIGNMENT_E2E_BACKEND=http://127.0.0.1:9797 \
npm run test:ai-alignment-e2e
```

The journey configures that isolated backend to use its in-process deterministic OpenRouter-compatible fixture, creates a real project, drives a 360 px AI pane with real mouse/keyboard events, queues and edits/reorders messages during a held model response, stops and sends a queued item now, and round-trips exact Markdown through the real memory API. It writes:

- `/tmp/ai-alignment-e2e/queue-running-360px.png`
- `/tmp/ai-alignment-e2e/reattached-running-360px.png`
- `/tmp/ai-alignment-e2e/memory-index.png`
- `/tmp/ai-alignment-e2e/memory-editor.png`

Set `AI_ALIGNMENT_E2E_OUT` to keep evidence for a specific run. Final Chromium acceptance, including the detach/reattach extension and all review repairs, passes. Evidence:

- `/tmp/ai-alignment-delivery-browser/queue-running-360px.png`
- `/tmp/ai-alignment-delivery-browser/memory-index.png`
- `/tmp/ai-alignment-delivery-browser/memory-editor.png`

The same journey can run through the real Electron desktop host. It requires the disposable backend root used to start the isolated backend:

```sh
cd frontend
AI_ALIGNMENT_E2E_ISOLATED=1 \
AI_ALIGNMENT_E2E_DESKTOP=1 \
AI_ALIGNMENT_E2E_ROOT=/tmp/aiworkdeck-ai-alignment-hrvxyeo3 \
AI_ALIGNMENT_E2E_EDITOR_DIST=/tmp/aiworkdeck-ai-alignment-hrvxyeo3/frontend/dist/zetaoffice \
AI_ALIGNMENT_E2E_BASE=http://127.0.0.1:5174 \
AI_ALIGNMENT_E2E_BACKEND=http://127.0.0.1:9797 \
AI_ALIGNMENT_E2E_OUT=/tmp/ai-alignment-delivery-electron \
npm run test:ai-alignment-e2e
```

Final real Mac Electron acceptance, including detach/reattach and all review repairs, passes. Evidence:

- `/tmp/ai-alignment-delivery-electron/queue-running-360px.png`
- `/tmp/ai-alignment-delivery-electron/memory-index.png`
- `/tmp/ai-alignment-delivery-electron/memory-editor.png`

The runner fails closed unless `AI_ALIGNMENT_E2E_ISOLATED=1` is explicit because it temporarily changes AI provider settings. Desktop mode also validates the backend port injected by the actual preload and uses `prepareWritingIsolation` to prove the backend and Electron home/profile are disposable.

Final review repairs bind memory actions to their captured space/document and discard stale view responses; inbox HTTP replies are guarded against newer SSE events and conversation changes. Long project titles truncate so memory/history/new-chat/close controls remain reachable. Full final frontend results are in `validation-report.md`.
