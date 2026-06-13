---
name: memory-vault
description: Manage Iris memory vault notes and scratchpads using OKF-style Markdown, vault search, filesystem edits, and reindex/audit.
---

# Memory Vault

Use when the user asks to inspect, edit, create, audit, or explain Iris memory vault notes.

## Workflow

1. Search first: `memory_recall` or `vault_search`.
2. Read exact files before edits: `fs_read`.
3. Edit vault files with normal file tools:
   - prefer `fs_replace` for existing notes
   - use `fs_create` for new notes
   - use `fs_write` only for deliberate whole-file rewrites
4. Reindex/audit after edits.
5. Report changed paths and audit result.

## OKF Minimum

Vault source of truth: Markdown files under configured vault roots.

Non-reserved notes should have YAML frontmatter and body.

Required OKF field:
- `type`

Recommended fields:
- `title`
- `description`
- `resource`
- `tags`
- `timestamp`

Iris metadata under `iris`:
- `scope`
- `status`
- `confidence`
- `origins`

Reserved files:
- `index.md`: directory listing
- `log.md`: update history

Preserve unknown frontmatter keys. Treat broken links and missing optional fields as audit findings, not fatal errors.

## Scope And Status

- New durable notes usually start in `inbox/` with `iris.status: "candidate"`.
- Use `approved` only with strong evidence.
- Use `session` for session-specific facts.
- Use `global` only for stable user/project-independent memory.
- Approved global notes need origins or an explicit manual/operator marker.

## Scratchpad

- Scratchpad is working memory, not a note promotion flow.
- Use `scratchpad_read`, `scratchpad_search`, `scratchpad_replace`.
- Exact replace requires current revision.

## Avoid

- Do not create vault-specific write abstractions.
- Do not auto-promote scratchpad or extracted content to global memory.
- Do not invent unsupported facts; source claims in `iris.origins` or `## Evidence`.
