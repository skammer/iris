# Iris Configuration

Iris has built-in defaults. User files should contain only intentional
overrides. Do not copy the full default map into every file.

## Locations and precedence

Configuration directory resolution:

1. `IRIS_CONFIG_DIR`
2. `$XDG_CONFIG_HOME/iris`
3. `~/.config/iris`

Merge order, lowest to highest precedence:

1. built-in `resources/config/default.edn`
2. global `~/.config/iris/config.edn` and its includes
3. project-local `./.iris/config.edn` and its includes
4. explicit `--config PATH` and its includes
5. supported environment variables

Maps deep-merge. Scalars, vectors, lists, and sets replace the earlier value.
Later include files override earlier includes; the including file overrides all
of them.

## Recommended layout

Keep the root small:

```clojure
{:iris/config-version 1
 :config/includes ["config/provider.edn"
                   "config/chat.edn"
                   "config/tools.edn"
                   "config/cron.edn"
                   "config/secrets.edn"]}
```

Each fragment should own a coherent area and include only overrides:

```clojure
;; config/chat.edn
{:chat {:max-steps 64}}
```

```clojure
;; config/cron.edn
{:cron {:timezone "UTC"
        :tool-profile :cron-observe}}
```

```clojure
;; config/tools.edn
{:tools {:shell {:roots ["/home/me"]
                 :working-dir "/home/me"}
         :profiles {:cron-observe
                    {:permissions [:filesystem-read :http-request]
                     :allowed-tools [:fs_read :fs_list :fs_search :http]
                     :allowed-actions {:http [:get :head]}}}}}
```

EDN supports `;;` comments. Do not add synthetic `:doc` keys: unknown keys may
fail validation or accidentally become runtime data. `iris config set` rewrites
its target file and therefore does not preserve comments; keep durable
explanation here or beside manually maintained fragments.

## Providers and models

One provider is active globally. Cron and chat profiles may override it.

```clojure
{:llm {:active-provider :deepseek
       :providers
       {:deepseek {:type :openai-compatible
                   :api :responses
                   :base-url "https://api.example/v1"
                   :model "deepseek-v4-flash"
                   :api-key nil}}}}
```

Use `:api :responses` only for endpoints implementing `/responses`; otherwise
use `:chat-completions`. Keep API keys in an ignored, permission-restricted
`config/secrets.edn` or a supported environment variable. Never paste secrets
into chat, logs, prompts, or tool purposes.

## Tools, permissions, and profiles

These are different identifiers:

- tool name: callable operation, e.g. `cronjob`, `shell`, `fs_read`;
- permission: capability granted to an entrypoint, e.g. `cron-read`,
  `cron-manage`, `shell-exec`;
- tool profile: allowlist used by a bounded runtime such as cron,
  e.g. `cron-observe` or `cron-automation`.

Do not add a tool name to `:tools :permissions`. To manage cron from chat, the
chat permission set needs both `:cron-read` and `:cron-manage`. A cron run's
profile independently controls tools available inside that run.

Sensitive mutations still pass normal approval policy. MAGI auto-approval
executes immediately. Conditional/manual decisions remain pending and appear
in Web UI **Tools → Tool Approvals** and, for Telegram-originated work, as an
inline approval card.

Shell input has two forms:

```json
{"argv":["rg","TODO","src"],"purpose":"Find unfinished work"}
```

```json
{"command":"find . -name '*.md' | head","purpose":"Find Markdown files"}
```

Use exactly one top-level form. `argv` performs no shell parsing; `command`
runs with `/bin/bash -lc` and is appropriate for pipes, redirects, variables,
globs, and `&&`. Never wrap input in `arguments` or JSON-encode it yourself.

## Cron-specific configuration

Global cron defaults select scheduler behavior and the default model/profile:

```clojure
{:cron {:enabled true
        :poll-interval-seconds 15
        :max-concurrency 2
        :run-timeout-seconds 1800
        :misfire-grace-seconds 3600
        :timezone "UTC"
        :provider nil
        :model nil
        :tool-profile :cron-observe}}
```

A job prompt is always required. Mention `/skill-name` directly in the prompt;
skills add instructions but not permissions. Describe scripts in the prompt and
grant a profile containing `shell`. Every run gets a fresh persisted session.
Telegram notification targets are fixed in the job snapshot; `cron_notify`
cannot redirect them.

Full cron usage: `~/.config/iris/docs/cron-jobs.md` after deployment, or
`obsidian/guides/cron-jobs.md` in the repository.

## Commands and reloads

```bash
iris config init
iris config validate
iris config set chat.max-steps 64
iris config set llm.active-provider :deepseek
iris config set llm.providers.deepseek.api :responses
```

`config init` creates a minimal override file plus context templates. `config
set` edits the highest-precedence source already owning that path; new paths go
to explicit `--config`, then project-local config, then global config.

Always run `iris config validate` immediately before restart or reload. Proceed
only on exit code 0 and `:status :valid`; validation reads the complete effective
config, including includes, local/explicit files, and environment overrides,
without printing secrets.

The Web/API/tool reload path performs the same validation synchronously before
a full reload is scheduled. Both reload modes keep the old runtime alive when
validation fails.

Use soft reload for normal provider, model, tool, skill, cron, and prompt config
changes. It preserves the active chat queue. Use full reload only when a
component cannot be safely hot-swapped; the turn requesting a full reload may
be interrupted while the runtime is rebuilt.

## Debug checklist

1. Read root `config.edn` and its `:config/includes`; do not guess filenames.
2. Check project-local `.iris/config.edn` and explicit `--config` use.
3. Check environment overrides.
4. Distinguish tool names, permissions, and profiles.
5. Validate provider `:api`, model, URL, and model metadata as one unit.
6. Run `iris config validate`; stop on any error.
7. Soft reload, then verify `/health`, active provider/model, tool registry, and
   scheduler health.
8. For approval failures, inspect latest approval record and event sequence;
   do not assume an approved record is still pending.
