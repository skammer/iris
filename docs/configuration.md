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

Declare native input capabilities per model, for example:

```clojure
{:llm {:providers
       {:deepseek {:models
                   {"deepseek-v4-flash-vision-exp"
                    {:input-modalities #{:text :image}}}}}}}
```

OpenAI-compatible and OpenRouter models default to text input. Per-model
`:supports-vision`, `:supports-audio`, `:supports-video`, and `:supports-files`
flags also work and override the corresponding modality. Enable only inputs the
specific model and endpoint accept; image support does not imply PDF support.

Unsupported attachments become text references with a filename and local path
(or the original URL). Telegram's existing local copy is reused. Older inline
attachments are saved unchanged under the Iris data directory's `attachments/`
folder. Iris does not extract text, run OCR, or otherwise interpret these files;
the model chooses tools when it needs to read them. The original rich history is
preserved, and capabilities are checked separately for each fallback candidate.

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

### Reload boundaries

| Component / setting | Soft reload | Full reload |
|---|---|---|
| Providers, model registry, MAGI tools | Rebuilt using shared startup factories | Rebuilt |
| Tools, filesystem policy, skills including WASM bundle skills | Rebuilt | Rebuilt |
| Memory, user profile, prompts | Rebuilt/refreshed | Rebuilt |
| Observer, trace and event sink | Rebuilt together | Rebuilt |
| Cron and memory background services | Recreated; running services restarted | Recreated and started with API |
| Telegram | Replaced only when adapter config changes | Replaced |
| Chat queue, active turns, executor capacity | Retained | Replaced; old turns may be interrupted |
| Chat turn policy / model settings | New turns use refreshed config | Refreshed |
| SQLite store, broker, telemetry collector, UI state | Retained | Replaced |
| HTTP listener address/port | Retained | Rebound |
| Logging | Reconfigured | Reconfigured |

Changes to `:chat` executor/queue limits (`:max-concurrent-turns`,
`:max-active-sessions`, `:max-queued-per-session`, `:max-pending-turns`), storage,
HTTP binding or telemetry collector settings require full reload. An active turn
can retain references captured before a soft reload; refreshing configuration
is not an instruction to restart that turn.

Construction and partial startup close acquired resources in reverse order on
failure. Soft reload restores the old system reference and background services
if replacement startup fails. Full reload keeps the old chat queue available
until the new API/runtime starts. If startup fails, the new runtime is closed
and the old API/services are restarted. If that recovery also fails, both sets
of resources are closed; external restart is required. The reload state reports
failure, not successful replacement. A committed full replacement is not rolled back just
because the subsequent audit-event write fails.

See [development and lifecycle](development.md) and [event contracts](events.md)
before changing these boundaries.

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


### LLM retries and fallback chain

Set `:llm :fallbacks` to an ordered vector of explicit provider/model pairs.
All entries must reference configured providers. An empty vector disables fallback.
The same chain applies to chat, Cron, MAGI and memory extraction, including calls
that override the primary provider. Each invocation starts with its primary;
duplicate identical provider/model pairs are removed.

```clojure
{:llm
 {:fallbacks [{:provider :deepseek :model "deepseek-v4-flash-vision-exp"}]}}
```

Choose fallback models that support the tools, structured output and input
modalities required by your workloads. Each fallback uses its own provider API,
credentials and model settings. Embeddings never switch providers, since doing
so would mix incompatible vector spaces.

Provider options:
- `:connect-timeout-ms`: TCP connection timeout; defaults to `:timeout-ms`
  when supplied, otherwise 10000 ms. Set explicitly for a short connection
  deadline independent of generation time.
- `:timeout-ms`: socket/read timeout.
- `:max-retries`: retries after the initial HTTP attempt (default 3).
- `:initial-delay` / `:max-delay`: backoff milliseconds (1000 / 60000).
  Retry-After is capped at max-delay.

Connection failures, DNS failures, socket failures/timeouts and HTTP
408/429/500/502/503/504 are retried. After retries are exhausted, generation
tries the next fallback. Authentication, invalid requests, TLS errors and
cancellation do not trigger fallback. The HTTP client's implicit retries are
disabled so this budget is authoritative.

Fallback never replays a conversation turn or tool execution; it resends only
the current LLM request with the existing history. Once content or thinking
has reached a streaming callback, failure is returned without switching.
An unanswered request can still incur provider charges.

Successful turns and telemetry include actual `:provider`, `:model` and
`:llm-attempts`; exhausted chains carry attempts in exception data.
Health checks describe the primary provider, not fallback readiness.
