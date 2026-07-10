---
name: iris-tools
description: Detailed Iris tool-use playbook for todo, scratchpad, memory, MAGI, approvals, and vault workflows.
---

# Iris Tools

Use when the user asks how to use Iris tools, wants better tool discipline, or invokes `/iris-tools`.

## Tool Loop

1. Read/search first when current state matters.
2. Act with the smallest tool call that advances the user goal.
3. Verify with concrete output.
4. Persist only the right kind of state:
   - todo for task progress
   - scratchpad for transient working memory
   - memory vault for explicit durable knowledge

Every tool input should include `purpose`: a short reason tied to the user's visible goal.

## Todo

Use todo when work has multiple steps, blockers, or resumable state.

- `todo_write` replaces one session-scoped list.
- `todo_get` reads one list.
- `todo_list` lists current-thread lists unless `all-threads?` is true.
- `todo_search` searches current-thread lists unless `all-threads?` is true.

Keep todo items concrete and status-bearing: `pending`, `in_progress`, `completed`, or `cancelled`.

## Scratchpad

Scratchpad is mutable working memory, not durable memory.

- Use `scratchpad_read` before editing.
- Use `scratchpad_search` to find exact text.
- Use `scratchpad_replace` with current `expected-revision`.
- Delete by replacing exact old text with `""`.
- If revision is stale, reread and retry from current content.

Use session scratchpad for task-local notes. Use global scratchpad only for active cross-session working state.

## Memory

Memory has separate read and durable-write paths.

- `memory_recall`: first broad lookup for relevant prior facts.
- `vault_search`: search approved indexed vault notes.
- `message_search`: search persisted chat messages.
- `memory_extract_session`: explicit session consolidation only, when user asks to save or extract memory.

Background idle extraction may create candidate vault notes after a configured
quiet period. Treat those as review candidates, not approved memory.

Do not auto-promote chat content, scratchpad notes, or extracted candidates into approved global memory. Source durable claims with evidence or explicit operator intent.

## Vault

Vault source of truth is Markdown under configured vault roots.

1. Search with `memory_recall` or `vault_search`.
2. Read exact files before edits.
3. Edit vault Markdown with filesystem tools.
4. Reindex/audit after changes when available.
5. Report changed paths and verification.

## MAGI

MAGI is for independent judgment, not routine tool use.

Call MAGI manually only when a judge is useful:

- moral or value-sensitive choice
- complex tradeoff with no obvious best answer
- ambiguous policy interpretation
- memory-promotion judgment
- high-impact recommendation where another reasoning angle helps

Do not call MAGI just because a tool needs approval. Tool approval is automatic: runtime decides whether to use MAGI, human approval, cached approval, denial, or yolo mode.

Good MAGI questions are concrete:

- action or decision under review
- target and scope
- known facts
- risk or ambiguity
- expected response: `permit`, `classify`, or `opine`

## Failure Handling

- Repeated same error: change approach or report blocker.
- Missing prerequisite: read/list parent or target, then retry.
- Ambiguous replace: narrow context, then retry exact replacement.
- Permission/approval required: surface approval id or wait for runtime approval path.
