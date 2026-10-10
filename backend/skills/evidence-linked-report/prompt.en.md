<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
## Evidence-Linked Report

This turn delivers a report or memo whose conclusions are drawn from project documents. Every conclusion must be one click away from its source; every conclusion without evidence must say in the body what is missing.

1. **Read the sources and build an evidence list first**: read the relevant project files with `search_project_content` / `extract_file_text`. For each intended conclusion, record the source file path, the **verbatim source sentence** that directly supports it, and classify it as `supports` (direct support), `partial` (supports only part, e.g. the amount but not the date) or "missing evidence". Drafts, chat statements and your own inferences are not sources.

2. **Write into a Writer document**: if the report is already open in the editor, write there; otherwise create it with `write_docx` (returns `db_id`) and open it with `doc_open_file(db_id)` - evidence links can only be created in the Writer document currently open in the editor. Write each conclusion as one complete sentence that appears only once in the document.

3. **Link every supported conclusion (immediately, not at the end, not only some)**: right after writing each `supports` / `partial` conclusion, call `doc_link_evidence`:
   - `docFileId` = the report's file ID; `anchorQuote` = that conclusion's sentence in the report (must occur exactly once; 0 or multiple hits are rejected - use a longer span or get an `anchorId` from `doc_find_text`);
   - `targetsJson` = `[{"path":"<file-tree relative path>","locator":{"type":"docx","quote":"<verbatim source excerpt>"},"relation":"supports|partial"}]`; for PDF use `{"type":"pdf","page":N,"quote":"..."}`;
   - choose a `quote` that is **unique** in the source file and does not span table cells, or jump-and-highlight will miss or land on the wrong passage;
   - if a conclusion has several sources, pass several targets in one call.
   For a `partial` conclusion, also state in the body what part is still missing.

4. **Missing evidence goes in the body only, with no link**: a conclusion without direct evidence must not be stated as settled; write it as "[TO BE SUPPLEMENTED: missing X (e.g. proof of payment)]" and say what material is needed. **Do not** call `doc_link_evidence` for it, and do not force a loosely related file in as its source. Missing evidence does not mean the fact did not happen.

5. **Self-check before delivering**: call `doc_list_evidence(docFileId=...)` and confirm that each `supports`/`partial` conclusion has a link that is not `orphan`, and that no "to be supplemented" item has a link. In the hand-off, state how many conclusions are linked, which are partial, and which are pending and what is missing. AI-created links are "unverified"; remind the user to open and check them.

For terms, fees or agreement language, write only "subject to the agreement"; do not draft legally binding wording.
