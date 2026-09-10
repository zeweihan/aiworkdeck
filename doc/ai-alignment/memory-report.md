# Markdown memory backend report

## Delivered behavior

The backend now stores user and project memory as revisioned Markdown documents in
`memory_document_space` and `memory_document`. Every available space has a non-deletable
`remember.md`; creating and deleting topic files updates only its managed link block and preserves
model-authored prose. Writes use `expectedRevision` and return HTTP 409 before a stale writer can
overwrite newer content. Paths must be relative `.md` paths without traversal, backslashes, absolute
segments, URI schemes, or empty segments. Content is limited to 128 KiB of UTF-8 data.
Editing `remember.md` validates local Markdown links and then rebuilds the managed topic block from
live documents in the same transaction. Renaming an existing topic refreshes its managed link label.

Space identity comes from the authenticated user and the current project. Local user spaces are
private. Project reads and writes use `ProjectMemberService` checks. Team and firm spaces are
canonical website data: desktop mode proxies with the connected `awdk_` account key; server mode
uses an `AccountBinding` derived requester ID plus the dedicated shared-memory secret. A caller can
never provide an account, team owner, or firm owner ID as an authorization fact. Missing account,
binding, or shared service configuration leaves visible `team` and `firm` rows unavailable with a
null `id`.

Legacy `MemoryEntry` saves migrate idempotently to stable Markdown paths while retaining their UID,
scope, type, source metadata, and evidence retrieval. New local Markdown documents maintain one
derived legacy row for existing Git memory sync rather than a second writable store. Sync tombstones
delete the legacy row, tombstone the document, and refresh the index in one transaction. A failed
import remains represented by the canonical Git tombstone and is retried during export, so a repeated
migration or sync cannot revive deleted content.
Project retrieval queries and vector results exclude user/global rows even when malformed historical
rows carry a project ID. Deterministic legacy file and conversation lookups also require the current
authorized project ID; a caller-provided file ID cannot cross a project boundary.

The model tools are `memory_list`, `memory_read`, `memory_search`, `memory_write`, `memory_edit`, and
`memory_delete`. Tools accept only `user`, `project`, `team`, or `firm`; they resolve the opaque space
ID from the current authenticated context. Edits require one exact occurrence and the current
revision. ASK mode exposes only list/read/search through the paired orchestrator allowlist and the
system prompt explicitly prohibits writes. Skill selection cannot remove those three ASK reads.
Context assembly injects only authorized `remember.md` indexes, bounded by
`ai.context.memory-reserve * chars-per-token` and capped at 16,000 characters; topic bodies remain
on-demand. Local index reads use short database transactions; team and firm HTTP reads run outside
them, concurrently, with one two-second total deadline per turn. A completed organization index is
retained if the other shared space reaches that deadline. The automatic conversation pipeline retains
long-conversation summarization and Git sync, while the duplicate per-turn regex and LLM memory
writers are removed in favor of immediate, explicit model tool writes.

## Desktop API

- `GET /api/ai/memory/spaces?projectId=N` returns direct `data` array entries
  `{id,scope,label,readable,writable,available,reason}`.
- `GET /api/ai/memory/files?spaceId=S` returns a direct `data` array of
  `{path,title,content:null,revision,updatedAt,writable}`.
- `GET /api/ai/memory/file?spaceId=S&path=P` returns
  `{path,title,content,revision,updatedAt,writable}` in `data`.
- `PUT /api/ai/memory/file` accepts `{spaceId,path,content,expectedRevision}` and returns the same
  file object. A create uses revision `0`.
- `DELETE /api/ai/memory/file?spaceId=S&path=P&expectedRevision=R` returns
  `{code:200,data:{deleted:true}}`.
- `GET /api/ai/memory/download?spaceId=S&path=P` returns the raw UTF-8 body as
  `text/markdown;charset=UTF-8` with attachment disposition.
- JSON successes use `{code:200,data:...}`. Failures use HTTP 400, 401, 403, or 409 and
  `{code,message}`.

## Shared organization integration

This branch expects the paired website implementation from commit `8fc6200`. Public desktop routes
are `/api/account/memory/{spaces,files,file,search,download}` with Bearer account authentication. Server
routes are `/api/internal/memory/{spaces,files,file,search,download}` with `X-Internal-Secret` and
`X-Requester-Account-Id`; the requester header is populated only from the local `AccountBinding`.
Configure server deployments with `memory.shared.base-url` and `memory.shared.secret`; the website
uses `AWD_SHARED_MEMORY_SECRET`. This secret is deliberately separate from the read-only collaboration
directory secret. No service was deployed as part of this work.

Organization IDs are `team:<id>` and `firm:<id>`. Central authorization resolves current website
role facts on every request: team members read, team OWNER/ADMIN write, firm members read, and only
the head team's OWNER/ADMIN write firm memory. The central service owns indexes, revisions,
tombstones, validation, and atomic writes.

The organization search route accepts `spaceId`, nonblank `query`, and an optional `limit` (default
10, capped at 20). It searches path, title, and content centrally and returns metadata summaries in
one request, so the desktop never downloads every topic to perform a query.

## Verification

Java 21 command:

```text
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -Dtest='MemoryDocumentServiceTest,RemoteMemoryOrganizationGatewayTest,MemoryDocumentControllerTest,MemoryDocumentToolsTest,ContextAssemblerServiceTest,MemoryEntryRepositoryIsolationTest,MemoryPipelineServiceTest,MemorySyncRoundTripTest,MemoryScopeTest,MemoryToolsScopeTest' test
```

Result after review fixes: **90 tests, 0 failures, 0 errors, 0 skipped**. Coverage includes cross-project denial,
central wrong-organization denial propagation, no-team unavailable rows, index link integrity,
path traversal, stale revisions, legacy migration, scope bypass, ASK read-only prompts, bounded
context injection, organization transaction/deadline boundaries, and retryable cross-machine deletion
tombstones.

The paired website was also exercised over real local HTTP by its implementer: desktop Bearer write
to canonical team storage followed by a server internal read as another authorized member passed.
