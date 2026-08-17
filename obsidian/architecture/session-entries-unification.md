# Design note: session_entries as the single source of truth for messages

#architecture #decision #future
Status: **deferred by decision (2026-06-10)** — owner chose zero data risk now; this note is the plan for when it's picked up. Context: [[refactoring-2026-06-findings]] ("Dual source of truth for message data").

## Problem

Message role/content/content-blocks/tool-calls/metadata/excluded-from-context? are persisted twice: rows in the `messages` table and inside `session_entries.payload_json`. Costs:

1. `sessions.clj message-entry-overrides` loads **all** session entries on every `list-messages` call just to re-merge entry payloads over message rows.
2. `update-message-runtime-flags!` dual-writes via two SQL statements, including a fragile `json_set` patch on `payload_json` keyed by `json_extract '$."message-id"'`.
3. Merge precedence lives in `merge-entry-overrides` and can silently drift from what `append-message!` wrote. Any writer touching only one representation corrupts reads.

## Target design

- `session_entries` owns all rich message data (content-blocks, tool-calls, metadata, excluded flag, runtime flags).
- `messages` shrinks to an FTS-indexed projection: `id, session_id, role, content_preview, created_at` — kept only because message FTS search and a few list views need it. Written from the entry append path (one writer).
- Delete: `message-entry-overrides`, `merge-entry-overrides`, the JSON-patching `update-message-entry-runtime-flags` SQL, and the dual-write in `update-message-runtime-flags!`.

## Prerequisite refactor (safe to do anytime, no migration)

Implement `append-message!` in terms of `append-entry!`'s `:message` branch (they're copy-paste duplicates today) so there is one write path *before* changing the read side.

## Migration plan

1. New schema migration `NNN-messages-projection`:
   - Backfill: for any `messages` row without a corresponding `:message` entry (pre-entries era data), synthesize a `session_entries` row from the message columns.
   - Verify counts match per session; abort on mismatch (leave DB untouched — migrations run in a transaction; check the trigger-aware splitter handles this one).
   - Drop the rich columns from `messages` (SQLite: recreate-table dance) OR keep columns but stop reading them (safer first ship; drop in a later migration).
2. Read-side switch: `list-messages` and friends read from entries; `chat/history`, UI render, and API serializers audited for every field they consume (grep `message->response`, `db-message-content`, render.clj message fns).
3. Keep `AGENT_SQLITE_DESTRUCTIVE_RESET_ON_DRIFT=false` semantics: drift detection must treat the new migration like any other (checksummed).

## Risks & mitigations

- **Live deployed DB** gets rewritten on next deploy → take a file-level backup of `~/.config/iris/data/agent.db` (+ `-wal`/`-shm`) before shipping; the backfill-verify-abort step protects against partial writes.
- Pre-entries-era sessions may lack entries entirely → covered by the backfill in step 1.
- FTS message search behavior must not change → the projection keeps the FTS table fed; add a characterization test for `search-messages` before starting.

## Acceptance

- All persistence + chat + API + UI tests green; new characterization tests for `list-messages` field coverage and `search-messages` pass unchanged before/after.
- A copy of a real production DB migrates cleanly in a rehearsal run (`clojure -M -m agent.core serve` against the copy, then spot-check sessions render).
