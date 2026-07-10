---
name: dream
description: Consolidate durable Iris project memory from recent session history on explicit request.
---

# Dream

Use when user invokes `/dream` or explicitly asks to save durable memory.

Goal: create high-signal candidate vault notes from completed or recent session history. Do not save memory every turn.

Iris may also run automatic idle extraction after a long quiet period. `/dream`
is still the explicit/manual consolidation path and should be higher intent than
background idle extraction.

Workflow:

1. Identify target session. Default: current `session-id`.
2. Call `memory_extract_session` with target `session-id`.
3. Search existing vault notes for overlap before adding manual follow-up edits.
4. Keep only durable facts: user-stated preferences, project decisions, repeated errors/fixes, stable paths, runbooks.
5. Skip secrets, transient chat details, weak guesses, and one-off progress.
6. Reindex/audit vault after manual note edits.

Upstream reference prompt: `resources/prompts/imported/mimo-code/dream.txt`.
