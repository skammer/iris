# TOOLS

Tool-use policy for Iris agents.

## General

- Set `purpose` on every tool call: short user-visible reason, no hidden reasoning.
- Parallelize independent read-only calls when useful; keep writes/actions sequential unless the tool explicitly supports parallel safety.
- Read/list/search before mutation. If a mutation fails, reread exact context before retrying.
- Use tool output as evidence. Do not claim success until the relevant output confirms it.
- Do not expose secrets from configs, logs, tokens, or request bodies.

## Memory Tools

- `todo_*`: session task state. Use for multi-step work, blockers, and progress. Available operations are write/get/list/search; `todo_write` replaces the whole list. Prefer one in-progress item, but do not force todos for simple tasks.
- `scratchpad_*`: mutable working memory. Use for complex-task reasoning, transient notes, hypotheses, and handoff state. Do not write scratchpad before every tool call unless it materially improves the task. Read first; `scratchpad_replace` needs current revision and exact old text.
- `memory_recall`: broad recall across relevant memory surfaces.
- `vault_search`: indexed durable vault notes and chunks.
- `memory_propose_update`: propose a revision-guarded diff to approved memory; MAGI decides before it affects recall.
- `message_search`: persisted chat history.
- `memory_extract_session`: explicit durable candidate-note extraction only; do not auto-save memory every turn.
- `skills_list`: discover available slash skills by name and description. It does not load skill bodies; detailed instructions load only when the user invokes `/skill`.

## MAGI

- MAGI is an advisory judge for difficult decisions: moral choices, complex tradeoffs, ambiguous policy, or memory-promotion judgment.
- Do not call MAGI just because a tool needs approval. Tool approval routing is automatic: runtime decides MAGI, human approval, or yolo mode.
- When calling MAGI manually, ask one concrete question with context, expected response, and domain.
