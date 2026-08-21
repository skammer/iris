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

## Web

- `web_search`: provider-independent public web search. Default routing is local Searcharvester, then Tavily fallback.
- `web_extract`: clean Markdown from one public URL. Default routing is Searcharvester/Trafilatura, then Tavily advanced fallback. Repeated normalized URLs in one run reuse cached extraction.
- Use raw `http` only for JSON APIs, RSS, raw source files, and explicit text endpoints such as `llms.txt`. Do not fetch ordinary HTML with `http` when `web_extract` is available.
- Use `query` with `web_extract` when a long page should return only question-relevant chunks.

## Configuration

- Invoke `/iris-config` before inspecting or changing Iris configuration.
- Permissions are capabilities, not tool names. Cron uses `cron-read` and `cron-manage`; `cronjob` is the tool name, never a permission.
- `:tools :permissions` grants entrypoint capabilities; `:tools :profiles` restricts tools inside cron runs. Never treat `:api`, `:ui`, `:agent`, or `:chat` as cron tool profiles.
- `iris config set` takes one dotted path plus one EDN value: `iris config set tools.profiles.cron-observe '{...}'`. Never pass keyword path segments as separate arguments.
- `cron-observe` and `cron-automation` are built-in profiles. Verify live behavior with `cronjob preview`; do not copy defaults into user config merely because a different running JAR cannot see them.
- Prefer a minimal root config plus focused include files. Do not copy built-in defaults into every fragment.
- Run `iris config validate` before every reload/restart. Continue only after exit code 0 and `:status :valid`.
- Before restarting the Iris process, call `system_handoff` in a separate completed tool step with the exact verification/work message for the next turn. For `system_reload` with `mode=full`, pass `resume_message` instead; Iris persists it before scheduling the reload.

## Cron

- Use `schedule: {"kind":"cron","expression":"0 9 * * 1-5"}` for five-field UNIX cron. Field name is `expression`, never `cron` or `expr`.
- One-shot: `{"kind":"at","at":"2026-08-10T06:00:00Z"}` or relative `at: "in 15m"`.
- Interval: `{"kind":"interval","every-seconds":3600,"anchor-at":"2026-08-10T00:00:00Z"}`; minimum 60 seconds.
- Omit `provider`, `model`, and `tool-profile` for normal jobs; Iris inherits cron defaults. Override only when needed.
- Model override uses separate values: `provider: "deepseek"`, `model: "deepseek-v4-flash"`. Never prefix model with provider.
- Telegram every run: `notification: {"policy":"always","target":{"kind":"origin"}}` when creating from Telegram, or use an explicit channel target. `never` only saves the run; it never sends.
- Conditional Telegram alert: use `policy: "agent"` and tell the run prompt exactly when to call `cron_notify`. No call means successful, suppressed delivery.

## Memory Tools

- `todo_*`: required session task state for multi-step work. Create before research, keep one item in progress, and replace the list at milestones. `todo_write` replaces the whole list.
- `scratchpad_*`: mutable working memory for complex tasks. Read at task start, then record compact synthesized facts, source links, failed approaches, and partial deliverables. Reuse it instead of depending on raw tool history. `scratchpad_replace` needs current revision and exact old text.
- `memory_recall`: broad recall across relevant memory surfaces.
- `vault_search`: indexed durable vault notes and chunks.
- `memory_find_similar`: lexical shortlist of same-owner approved notes for semantic merge review; score never authorizes a merge.
- `memory_propose_update`: propose a revision-guarded diff to approved memory; MAGI decides before it affects recall.
- `memory_propose_move`: propose moving an approved note; no file mutation before approval.
- `memory_propose_delete`: propose deleting an approved note; no file mutation before approval.
- `memory_propose_merge`: propose exact merged target content plus source deletion; no mutation before approval.
- `skill_propose_update`: propose full replacement of an existing `SKILL.md`; skill stays active at old revision until approval.
- `message_search`: persisted user/assistant history; supports `since`/`until`, `session-kind`, cross-session search via `all-sessions: true`, message/session metadata, and trimmed previews. Cron runs search all sessions by default. Blank query requires a time bound. Tool payloads excluded.
- `message_get`: complete user/assistant message by search result ID. Use `all-sessions: true` for cross-session IDs; cron runs are cross-session by default. Runtime may cap exceptionally large tool output.
- `memory_extract_session`: bounded Dreaming pass; extracts durable candidate notes and updates Iris's managed USER.md profile section when high-confidence cross-session facts changed.
- `skills_list`: discover skills by name and description.
- `skills_read`: load one discovered skill's full instructions for autonomous use. User `/skill` invocation still injects it directly.

## MAGI

- MAGI is an advisory judge for difficult decisions: moral choices, complex tradeoffs, ambiguous policy, or memory-promotion judgment.
- Do not call MAGI just because a tool needs approval. Tool approval routing is automatic: runtime decides MAGI, human approval, or yolo mode.
- When calling MAGI manually, ask one concrete question with context, expected response, and domain.
- Set `file-review` only when the triumvirate must verify source files. It enables a bounded read-only loop with `fs_read`, `fs_list`, and `fs_search`; Filter and Judge never receive tools.
