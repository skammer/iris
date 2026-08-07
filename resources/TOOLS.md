# TOOLS

Tool-use policy for Iris agents.

## General

- Set `purpose` on every tool call: short user-visible reason, no hidden reasoning.
- Parallelize independent read-only calls when useful; keep writes/actions sequential unless the tool explicitly supports parallel safety.
- Read/list/search before mutation. If a mutation fails, reread exact context before retrying.
- Use tool output as evidence. Do not claim success until the relevant output confirms it.
- Do not expose secrets from configs, logs, tokens, or request bodies.

## Shell

- Pass exactly one top-level input form. Use `argv` for one executable with literal arguments: `{"argv":["rg","TODO","src"]}`.
- Use `command` for shell syntax: `{"command":"find . -name '*.md' | head"}`. Iris runs it with `/bin/bash -lc`.
- Never put either form inside an `arguments` field and never JSON-encode tool input yourself.

## Configuration

- Invoke `/iris-config` before inspecting or changing Iris configuration.
- Permissions are capabilities, not tool names. Cron uses `cron-read` and `cron-manage`; `cronjob` is the tool name, never a permission.
- `:tools :permissions` grants entrypoint capabilities; `:tools :profiles` restricts tools inside cron runs. Never treat `:api`, `:ui`, `:agent`, or `:chat` as cron tool profiles.
- `iris config set` takes one dotted path plus one EDN value: `iris config set tools.profiles.cron-observe '{...}'`. Never pass keyword path segments as separate arguments.
- `cron-observe` and `cron-automation` are built-in profiles. Verify live behavior with `cronjob preview`; do not copy defaults into user config merely because a different running JAR cannot see them.
- Prefer a minimal root config plus focused include files. Do not copy built-in defaults into every fragment.
- Run `iris config validate` before every reload/restart. Continue only after exit code 0 and `:status :valid`.

## Cron

- Use `schedule: {"kind":"cron","expression":"0 9 * * 1-5"}` for five-field UNIX cron. Field name is `expression`, never `cron` or `expr`.
- One-shot: `{"kind":"at","at":"2026-08-10T06:00:00Z"}` or relative `at: "in 15m"`.
- Interval: `{"kind":"interval","every-seconds":3600,"anchor-at":"2026-08-10T00:00:00Z"}`; minimum 60 seconds.

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
- Set `file-review` only when the triumvirate must verify source files. It enables a bounded read-only loop with `fs_read`, `fs_list`, and `fs_search`; Filter and Judge never receive tools.
