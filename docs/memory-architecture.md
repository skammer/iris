# Memory architecture

## Execution graph

```text
chat messages + selected session events
  -> idle extractor (chat sessions only, ordered watermarks)
  -> create candidate note / propose approved-note update
  -> MAGI or user decision
  -> rejected | approved physical folder
  -> Markdown parser
  -> durable body only (Evidence excluded)
  -> SQLite FTS5/BM25 derived index
  -> global ranking + per-note diversity + total top-k
  -> bounded valid JSON memory context
```

Markdown remains source of truth. SQLite rows are disposable indexes and audit
state. Physical folders define the durable tree: `preferences`, `decisions`,
`projects`, `runbooks`, `sessions`, `references`, and `archive`. Generated
`index.md` files provide navigation; they are not durable facts.

## Lifecycle

- Creates enter `inbox` as `candidate` notes.
- Updates remain pending in `memory_note_updates`; approved files are unchanged.
- MAGI/user approval promotes candidates into deterministic folders or applies
  update revisions atomically.
- Negative decisions become `rejected`, preventing repeated review.
- Evidence is capped at 800 characters, retained for review only, and never
  indexed or recalled.
- Origins are compact ranges, de-duplicated and capped at eight entries.
- Embeddings remain disabled until cleanup quality is accepted.

## Cleanup

`agent.memory.core/cleanup-vault!` is deliberately conservative. It first makes
a timestamped sibling backup, then removes rejected drafts, strips legacy evidence from approved
notes, caps origins, moves approved inbox notes, and regenerates indexes. Exact
title duplicates are reported for review, not merged automatically.

Run dry first:

```clojure
(agent.memory.core/cleanup-vault! (:memory-service system) {:apply? false})
```

Then run with `{:apply? true}`; returned `backup-paths` identifies the snapshot.

## Skill creation

`/distill` is the supported skill-authoring workflow. It must produce a review
candidate first; a skill is registered only after the candidate is approved.
No direct writes to an active skills directory are allowed during drafting.

Automatic maintenance uses persistent cron jobs with the restricted
`cron-memory` profile: daily `/dream` grooming and weekly `/distill`. Both jobs
may read chat history and create proposals, but cannot write files or execute
shell commands directly.
