---
name: iris-config
description: Inspect, explain, edit, reload, and verify Iris EDN configuration without guessing tool names, permissions, profiles, or file precedence.
---

# Iris Configuration

Use when the user asks about Iris config, providers/models, tool permissions,
cron profiles, reloads, or invokes `/iris-config`.

## Source of truth

Read `~/.config/iris/docs/configuration.md` first. In a source checkout, use
`docs/configuration.md`. For cron details, read the deployed
`~/.config/iris/docs/cron-jobs.md` or repository
`obsidian/guides/cron-jobs.md`.

## Workflow

1. Locate config dir from `IRIS_CONFIG_DIR`, `XDG_CONFIG_HOME`, then
   `~/.config/iris`.
2. Read root `config.edn`, every declared include, project-local
   `.iris/config.edn`, and any explicit config path. Inspect environment
   overrides when relevant.
3. Explain the effective value and the exact file/source that wins.
4. Edit the smallest owning fragment. Preserve unrelated values and secrets.
5. Run `iris config validate`. Do not reload or restart unless it exits 0 and
   reports `:status :valid`.
6. Use soft reload unless a full rebuild is specifically required.
7. Verify live health and the exact provider/model/tool/profile behavior.

## Rules

- Never infer filenames or permissions from tool names.
- `cronjob` is a tool. Its permissions are `cron-read` and `cron-manage`.
- `iris config set` accepts one dotted path, never separate EDN keyword path segments.
- `:tools :permissions` entrypoint grants and `:tools :profiles` cron restrictions are unrelated maps.
- `cron-observe` and `cron-automation` are built in; do not duplicate them in user config unless intentionally overriding fields.
- Skills add instructions, not tools or permissions.
- Use `shell.argv` for literal process arguments and `shell.command` for shell
  syntax. Never nest either inside `arguments`.
- Keep root config and fragments minimal; built-in defaults already exist.
- EDN comments are fine, but `iris config set` does not preserve them.
- Never print tokens, API keys, secret headers, or full secret files.
- If an approval is already `approved`, do not tell the user it is pending;
  inspect the execution event/result that followed it.
