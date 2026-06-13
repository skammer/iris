# Iris Memory Refactoring Plan

## Current assessment

Iris memory is a useful prototype, but not yet a reliable agent-memory system.

Score:
- Dev prototype: 6/10
- Reliable agent memory: 3.5/10

Main issue: the current system mixes four different concepts under one word:
- static `MEMORY.md` prompt file
- durable vault notes
- episodic logs/messages
- writable vault files

Decision update:
- Delete `MEMORY.md` as a memory surface.
- Consolidate durable memory into vault-backed documents.
- Treat SQLite note indexes, chunks, FTS, and embeddings as derived state, not the primary source of truth.
- Keep instructions in `AGENTS.md`, skills, prompts, and checked-in docs. Do not move authoritative rules into memory.
- Prefer clean deletion over compatibility layers: no aliases, shims, ignored legacy tables, or dead fallback branches.

## Current surfaces

### Legacy prompt memory

Current behavior:
- Reads configured files from `:memory :prompt :paths`.
- Default path is `MEMORY.md`.
- File is read-only.
- Content is injected into recall JSON.

Assessment:
- This is the wrong abstraction for Iris memory.
- Read-only static prompt docs belong in instructions/docs, not memory.
- A mutable `MEMORY.md` would be worse: no structure, no provenance, no review, bad merge semantics.

Decision:
- Remove `MEMORY.md` from default memory config.
- Remove prompt-memory injection from chat recall.
- Delete `resources/MEMORY.md`; no migration needed while Iris memory is alpha.
- Keep static guidance in `AGENTS.md`, skills, prompts, or normal docs.

### Vault notes and scratchpads

Current behavior:
- SQLite table `memory_facts` stores SPO triples.
- Vault is explicit file read/write only.
- No first-class scratchpad tool.

Assessment:
- A separate canonical `memory_items` table is unnecessary once the vault is OKF/Obsidian-compatible.
- Canonical memory should be vault notes, not database rows.
- SQLite should only index vault notes/chunks/frontmatter for retrieval.
- Small models need an obvious write surface; raw vault editing is too broad.

Target:
- Replace SPO facts with vault notes as canonical memory.
- Do not create canonical `memory_items` in core v1.
- Use a parsed `vault_note_index` table only as derived index/cache.
- Add explicit scratchpad tools for simple model-facing note taking.

SPO decision:
- Do not require `subject`, `predicate`, `object`.
- Do not build a knowledge graph now.
- SPO without an ontology gives weak semantics and extra extraction overhead.
- If useful later, add SPO as an optional derived projection for analytics/search, not as canonical storage.

SPO value assessment:
- Search value is low: FTS + embeddings over vault note titles, bodies, tags, and evidence quotes should cover most retrieval.
- Filter value is better served by OKF `type`, `iris.scope`, `iris.status`, tags, origin type, and folder path.
- Dedup value is possible, but can be handled with title/body similarity plus embeddings.
- Graph value is low until there is enough corpus data to design a real ontology.
- Cost is real: extraction prompts become more brittle, UI has awkward fields, and operators must maintain artificial triples.

Vault note lifecycle:
- Lifecycle lives in note frontmatter under `iris.status`.
- `candidate`: note in inbox or marked candidate; not used for normal global recall.
- `approved`: eligible for durable recall.
- `auto_session`: session-scoped note/scratchpad entry; eligible only for that session.
- `rejected`: kept in archive or marked rejected; not recalled normally.
- `superseded`: points to replacement note; not recalled normally.

Scope and approval:
- `iris.scope: global` means durable vault note eligible outside a single session/project.
- Global scratchpad is not a global note; it is mutable working memory.
- Auto extraction may create only `candidate` notes in `memory/inbox/`.
- `iris.scope: global` + `iris.status: approved` requires manual/operator action.
- Session/project notes may be auto-session or candidate, but not silently global approved.

Origin and evidence:
- Origin lives in note frontmatter under `iris.origins`.
- Human-readable evidence lives in the note body under `## Evidence`.
- `origin.type`: `message`, `event`, `vault_chunk`, `manual`, `extraction`, `api`, `tool`.
- Origins should include stable ids where possible: `session_id`, `request_id`, `message_id`, `event_id`, `tool_call_id`, `vault_path`, `vault_chunk_id`.
- Durable approved notes should have at least one origin or a manual/operator marker.

Scratchpad tools:
- Add a separate scratchpad tool family so small models know where to read and edit working memory.
- `scratchpad_read`
- `scratchpad_replace`
- `scratchpad_search`
- Scopes:
  - global scratchpad: `memory/scratchpad/global.md`
  - session scratchpad: `memory/scratchpad/sessions/<session-id>.md`
- Scratchpad is for temporary working notes, user "remember this" staging, and session context.
- `scratchpad_read` returns full text plus `revision = sha256(content)`.
- `scratchpad_search` returns matching snippets plus current `revision`.
- `scratchpad_replace` is the only mutation tool: exact `old_text` -> `new_text` with required `expected_revision`.
- Delete is `scratchpad_replace(old_text, "")`.
- Append/overwrite are possible by reading current text and replacing the full text.
- No-match, ambiguous-match, or stale revision must fail and require reread.
- Scratchpad is not a note promotion surface; vault tools evolve separately.

Recall rules:
- Normal recall searches indexed vault notes/chunks.
- Candidate/rejected/superseded notes are excluded unless diagnostics request them.
- Session recall includes session-scoped vault matches, including indexed session scratchpad chunks when query matches.
- Global recall includes approved global/project notes.
- Full scratchpad content is available through `scratchpad_read`, not unconditional recall injection.

UI/API requirements:
- UI shows vault notes, scratchpads, audit/reindex report, and recall diagnostics.
- `memory_recall` returns note path, type, status, source ids, chunk ids, and evidence snippets.
- `vault_search` searches indexed vault notes/chunks.
- `scratchpad_*` tools operate on fixed scoped scratchpad files, not arbitrary paths.

### Search

Current behavior:
- FTS5 over messages, events, and current fact rows.
- Simple rank: lexical/Jaccard, exact match, confidence, surface weight.
- No embeddings.

Assessment:
- Good baseline.
- Not enough for paraphrase, multilingual recall, or conceptual queries.

Target:
- Hybrid retrieval:
  - metadata filters first
  - FTS/BM25 exact search
  - vector embeddings
  - recency/confidence/provenance scoring
  - optional rerank
  - compact source-cited recall payload

Important:
- Do not replace FTS with embeddings.
- Use FTS + embeddings + filters.

### Vault

Current behavior:
- Configured writable file roots.
- Explicit read/write tools only.
- Not indexed.
- Not automatically recalled.

Assessment:
- This should become the primary memory store.
- The vault should be an OKF-compatible, Obsidian-friendly markdown folder, not an Iris-only format.
- Raw file read/write is not enough; vault needs structure, indexing, and first-class recall.

Decision:
- Make vault the source of truth for durable memory.
- Use OKF-compatible Markdown notes with YAML frontmatter as the canonical format.
- Reference spec: https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md
- Use OKF `type` instead of Iris-specific `kind`.
- Put Iris-specific lifecycle/provenance under an `iris:` frontmatter key.
- Delete `MEMORY.md` rather than keeping a second text-memory surface.

Target:
- Turn vault into indexed knowledge notes.
- Store durable memory as small Markdown notes under configured vault roots.
- Use YAML frontmatter for machine-readable metadata: `type`, `title`, `description`, `resource`, `tags`, `timestamp`, plus `iris`.
- Support Obsidian links (`[[Note]]`, `[[Note#Heading]]`) and tags.
- Generate standard markdown links for portability; parse both standard links and Obsidian wikilinks.
- Support OKF reserved files: `index.md` for progressive disclosure and `log.md` for update history.
- Treat broken links and unknown frontmatter fields as non-fatal audit findings.
- Chunk vault docs by headings, block ids where present, and content hashes.
- Index chunks with FTS + embeddings.
- Let vault notes reference origins/evidence in frontmatter/body.
- Add explicit tools:
  - `vault_search`
- Edit vault files through the existing filesystem tools (`fs_read`, `fs_create`, `fs_replace`, `fs_write`), not a second vault write API for LLMs.
- Do not add file watchers now.
- Add explicit/periodic full-vault audit and reindex.

Suggested vault structure:

```text
memory/
  index.md
  log.md
  inbox/
  preferences/
  decisions/
  projects/
    clj-agent/
    tauri-datastar/
  runbooks/
  sessions/
  references/
  archive/
```

Keep folders practical, not ontological. OKF `type` and tags do semantic grouping.

Initial OKF producer defaults Iris should generate:
- `Preference`: user/team/project preferences.
- `Decision`: accepted decision plus rationale.
- `Runbook`: commands, procedures, debug steps.
- `ProjectNote`: project-specific facts and context.
- `Reference`: external/static material, links, excerpts, or fallback when unsure.

Type rules:
- OKF `type` values are not centrally registered.
- Producers should choose descriptive, self-explanatory values.
- Consumers must tolerate unknown types gracefully, treating them as generic concepts when needed.
- Keep types coarse and operational.
- Use `Reference` as fallback, not as the default for everything.
- Add new types only after repeated corpus evidence.

Canonical note shape:

```markdown
---
id: mem_...
type: Preference
title: Concise Russian answers
description: User prefers concise answers in Russian.
tags: [memory, preference, user]
timestamp: 2026-06-13T12:00:00Z
iris:
  scope: global
  status: approved
  confidence: 0.9
  origins:
    - type: message
      session_id: ses_...
      message_id: msg_...
---

# Concise Russian answers

User prefers concise answers in Russian.

## Evidence

> "отвечай кратко по-русски"

# Citations
```

Vault audit/reindex:
- explicit job, not watcher-driven
- scans all markdown files
- parses frontmatter
- validates schema
- chunks by headings/block ids/content hash
- rebuilds FTS/embedding indexes
- indexes body text even when frontmatter/YAML is invalid
- invalid frontmatter downgrades metadata quality but must not make content undiscoverable
- detects duplicate ids
- detects invalid YAML/frontmatter
- detects missing/broken origin links
- detects deleted notes/chunks
- marks orphan notes/chunks
- reports indexed files, changed files, parse errors, duplicate ids, broken links, orphan notes/chunks, stale embeddings

## Target architecture

### 1. Instructions

Purpose:
- Authoritative rules and required behavior.

Sources:
- `AGENTS.md`
- skills
- prompts
- checked-in docs

Rule:
- Instructions are authoritative.
- Memory is helpful recall, not policy.
- `MEMORY.md` is removed; checked-in docs can still exist, but they are not memory.

### 2. Curated vault memory

Purpose:
- Durable, scoped vault notes with provenance.

Storage:
- vault documents as source of truth
- SQLite `vault_note_index` as derived projection
- vault chunk/link/frontmatter indexes

Used for:
- stable user preferences
- recurring project conventions
- decisions
- pitfalls
- known commands
- environment observations

### 3. Episodic memory

Purpose:
- Searchable history.

Storage:
- messages
- events
- traces
- compacted summaries

Used for:
- debugging
- recent context recovery
- evidence for note promotion

### 4. Knowledge vault

Purpose:
- Primary durable memory store owned by the agent/user.

Storage:
- Obsidian-compatible Markdown files under configured roots
- indexed chunks in SQLite

Used for:
- durable project notes
- investigation notes
- runbooks
- non-authoritative knowledge docs
- evidence and human-readable memory state

Indexing model:
- SQLite stores only derived index state: parsed frontmatter, chunks, FTS rows, embeddings, link status.
- Recall uses the last successful index.
- Manual Obsidian edits are supported by running the audit/reindex job.
- No watcher required in alpha.

### 5. Retrieval layer

Purpose:
- One recall API used by chat, tools, API, and UI.

Output:
- compact JSON
- source ids
- surface/type
- scores
- reasons
- freshness

## Interface cleanup

Current mismatch:
- API `/v1/memory/search` searches messages/events/facts.
- Tool `memory_search` searches facts + prompt files.
- `message_search` is separate.
- Vault is separate and not indexed.
- `MEMORY.md` prompt memory is a second text-memory path.
- No dedicated scratchpad tools.

Target:
- `memory_recall`: unified recall used by chat and tools.
- `vault_search`: primary durable memory search.
- `fs_read`/`fs_create`/`fs_replace`/`fs_write`: read and edit vault Markdown files under configured vault roots.
- `scratchpad_read`: read global/session scratchpad and return revision.
- `scratchpad_replace`: exact text replacement inside global/session scratchpad with expected revision.
- `scratchpad_search`: search scratchpad text.
- `message_search`: diagnostic only.
- remove prompt-memory API/tool surface.

Clean break rule:
- Iris memory is alpha; backward compatibility is not a goal.
- Delete misleading routes/tools/UI instead of aliasing them.
- Delete obsolete code paths outright; do not leave ignored legacy branches.

## Refactoring plan

### Phase 0: Pin critical invariants — DONE

Status:
- Done in current refactor pass.
- Added coverage for removed `MEMORY.md` injection and normalized recall shape.

Goal:
- Test only the behavior worth preserving.

Work:
- Add tests for chat recall payload shape.
- Add tests for vault note scope/status filtering.
- Add tests for global/session scratchpad paths.
- Add tests for vault path policy.
- Add tests for reset behavior.
- Add tests proving `MEMORY.md` is not injected after removal.

Exit criteria:
- Preserved invariants are documented by tests.
- Removed surfaces are gone, not hidden behind aliases or fallback behavior.

### Phase 1: Remove `MEMORY.md` surface — DONE

Status:
- Done in current refactor pass.
- Removed default config/template, prompt-memory injection, prompt-memory routes/UI, tool search over prompt files, and `resources/MEMORY.md`.

Goal:
- Remove the misleading prompt-memory path.

Work:
- Remove default `:memory :prompt :paths ["MEMORY.md"]`.
- Stop injecting prompt-memory docs into chat recall.
- Remove `/v1/memory/prompt` and `/ui/memory/prompt`.
- Remove "Prompt Memory" UI.
- Delete `resources/MEMORY.md`.
- Delete prompt-memory serializers, handlers, config readers, UI code, and tests that only existed for prompt memory.

Exit criteria:
- Chat recall has no `MEMORY.md` payload.
- Tests expect vault notes/chunks, scratchpads, messages, and events only.
- Existing tests pass.
- UI communicates vault-backed memory.

### Phase 2: Introduce unified recall API — DONE

Status:
- Done for currently implemented surfaces: messages, events, approved vault chunks.
- Added `agent.memory.recall`, `/v1/memory/recall`, `memory_recall`, and chat/UI recall wiring.
- Vault chunks and scratchpads move into Phase 3/4 because those surfaces are introduced there.

Goal:
- One retrieval path for chat/API/tools.

Work:
- Add `agent.memory.recall` namespace.
- Implement `recall` over:
  - vault chunks
  - vault note frontmatter
  - scoped scratchpad
  - messages
  - events
- Return normalized result records:
  - `surface`
  - `type`
  - `id`
  - `scope`
  - `status`
  - `text`
  - `score`
  - `source`
  - `reason`
  - `tags`
- Change chat recall to use this.
- Delete old `memory_search`; expose `memory_recall`/`vault_search` directly.

Exit criteria:
- Chat/API/tool recall agree.
- `message_search` remains available for diagnostics.

### Phase 3: Replace facts/items with vault notes

Goal:
- Remove canonical DB facts/items and make OKF vault notes the durable model.

Status:
- Done in current refactor pass.
- Added derived `vault_note_index` and `vault_chunks` schema, explicit vault reindex, approved-note filtering, `vault_search`, and vault chunk recall.
- Deleted `memory_facts` runtime path, public facts API/tools/UI, and fact-oriented tests.
- Replaced auto extraction writes with candidate OKF notes in vault `inbox/`.
- Added session-scoped vault recall for approved/auto-session notes with matching origins.
- Added UI note status/scope promotion controls backed by vault frontmatter edits and reindex.
- Added note move UX for standard vault folders.
- Added global/session scratchpad files and exact-replace tools.
- Vault reindex indexes scratchpad files with derived `Scratchpad` metadata when frontmatter is absent.

Work:
- Delete `memory_facts` schema, SQL queries, code paths, tools, and tests.
- Add derived `vault_note_index`:
  - `path`
  - `id`
  - `type`
  - `title`
  - `description`
  - `tags_json`
  - `timestamp`
  - `iris_scope`
  - `iris_status`
  - `iris_confidence`
  - `frontmatter_json`
  - `body_hash`
  - `updated_at`
- Add `vault_chunks`:
  - `chunk_id`
  - `path`
  - `heading`
  - `block_id`
  - `content_hash`
  - `text`
- Parse `iris.origins` from frontmatter instead of separate evidence rows.
- Keep human-readable evidence in note body under `## Evidence`.
- Review by editing/moving notes and changing `iris.status`.
- UI shows note detail with frontmatter, body, origins, chunks, and source links.

Exit criteria:
- Auto extraction writes candidate OKF notes into `memory/inbox/`.
- UI can edit status and move notes.
- Retrieval defaults to approved global/project notes + current session note/scratchpad matches.
- Approved global notes have `iris.origins` or explicit manual/operator marker.
- Recall/debug payloads explain which note/chunk was recalled and why.

### Phase 4: Make vault primary

Goal:
- Make vault the primary durable memory system.

Status:
- Done in current refactor pass.
- Added vault chunk indexing, `vault_search`, vault roots in filesystem tools, vault reindex job/API/UI, vault source links, default `memory-vault` skill, scratchpad read/search/exact-replace, scratchpad indexing, UI scratchpad panel/form, and full audit report.
- Audit report now covers parse errors, duplicate ids, broken links, broken origin vault paths, orphan notes/chunks, OKF type checks, reserved `index.md`/`log.md` handling, and missing/stale embedding placeholders when embeddings are enabled.
- Reindex keeps body discovery best-effort when frontmatter has errors, and keeps the last successful SQLite index if the index write itself fails.

Work:
- Add vault chunk indexing.
- Add `vault_search`.
- Let filesystem tools read/edit configured vault roots.
- Add scratchpad tools:
  - `scratchpad_read`
  - `scratchpad_replace`
  - `scratchpad_search`
- Show vault docs/chunks in UI.
- Add vault audit/reindex job.
- Add UI button: `Audit & Reindex`.
- Add API: `POST /v1/memory/vault/reindex`.
- Add REPL/system fn: `memory/reindex-vault!`.
- Add vault source links in recall results.

Exit criteria:
- Vault participates in retrieval.
- Note indexes can be rebuilt from vault-backed state.
- Scratchpad tools support read/search/exact-replace without arbitrary path access.
- Reindex report shows parse errors, duplicate ids, broken links, orphan notes/chunks, and stale/missing embeddings when embeddings are enabled.
- Audit validates OKF basics: parseable frontmatter, non-empty `type`, reserved `index.md`/`log.md` handling.
- Files with invalid frontmatter still get body-only chunks indexed.
- Recall uses last successful index if latest audit reports errors.

### Phase 5: Add embeddings

Goal:
- Semantic recall without losing exact search.

Work:
- Reuse existing `agent.llm.core/embed` provider capability.
- Reuse existing provider config, including `:llm :providers ... :embedding-model`.
- Add `:memory :embeddings` config only for indexing policy, not provider abstraction:
  - enabled?
  - surfaces: vault notes and vault chunks only in v1
  - batch size
  - rebuild mode
- Add tables:
  - `memory_embeddings`
  - `vault_chunk_embeddings`
- Embed:
  - approved vault notes/chunks
- Do not embed in v1:
  - raw messages
  - raw events
  - synthetic summaries
- Keep FTS for messages/events diagnostics.
- Add conversation/session summaries later only if there is a separate summary pipeline.
- Add hybrid scoring:
  - FTS score
  - vector score
  - scope score
  - recency score
  - confidence score
  - surface weight

Exit criteria:
- No second embedding provider registry/config exists.
- FTS still works offline.
- Embeddings improve paraphrase queries.
- Scope filters prevent cross-session leakage.
- Embeddings can be rebuilt from vault state.

### Phase 6: Quality controls

Goal:
- Prevent memory rot.

Work:
- Add stale note detection.
- Add conflict detection.
- Add low-confidence review queue.
- Add source inspection in UI.
- Add orphan note/chunk detection: notes with broken origins or deleted chunks.
- Add "why recalled" in chat/debug events.
- Add memory health metrics:
  - note count by type/status
  - candidate backlog
  - orphan notes/chunks
  - origins by origin type
  - stale notes
  - embedding coverage
  - recall latency

Exit criteria:
- Operator can inspect and correct memory.
- Recall payloads are explainable.

## Suggested implementation order

1. Tests for preserved invariants.
2. Remove `MEMORY.md` memory surface.
3. Unified recall API.
4. Vault note lifecycle + scratchpad tools.
5. Vault indexing/audit.
6. Embeddings.

Reason:
- Removing `MEMORY.md` cuts the worst abstraction first.
- Unified recall removes interface mismatch before adding more storage.
- Vault indexing should land before notes become long-term source material.
- Note governance should land before embeddings amplify bad data.

## Non-goals

- Do not keep `MEMORY.md` as memory.
- Do not make `MEMORY.md` mutable memory.
- Do not remove `AGENTS.md`/skills/prompts as instruction surfaces.
- Do not use embeddings as the only retrieval system.
- Do not auto-promote every scratchpad/extracted note to global memory.
- Do not create a second vault-specific filesystem API for LLM edits.
- Do not build a knowledge graph or require SPO triples before there is corpus evidence for an ontology.
- Do not add file watchers in alpha; explicit/periodic audit and reindex is enough.

## Acceptance criteria

Memory is good enough when:
- Chat recall, API recall, and tool recall share one implementation.
- Vault notes are typed, scoped, reviewable, and source-backed.
- Canonical storage is OKF Markdown notes, not SPO triples or DB memory items.
- Approved/global notes have origin/evidence and no orphan state.
- Global notes require stronger evidence than session notes/scratchpad entries.
- Scratchpad is exposed through dedicated global/session `read`, `search`, and `replace` tools.
- Search handles exact and semantic queries.
- Vault is indexed and is the primary durable memory store.
- Vault notes are OKF-compatible Markdown with Iris metadata under `iris`.
- `MEMORY.md` is gone from memory config/UI/recall.
- UI can answer: what was remembered, why, from where, and how to fix it.

## Risks

- Auto extraction can hallucinate durable notes.
- Embeddings can retrieve plausible but irrelevant context.
- Global memory can leak project-specific assumptions.
- UI note review can become tedious if candidate volume is high.
- Destructive schema changes can wipe alpha memory; acceptable if explicit, but should be visible.

## Open decisions

- None.
