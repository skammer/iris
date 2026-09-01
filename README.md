# iris: Isolated Reasoning & Intelligence Substrate

Iris is a stateful Clojure agent runtime for chat, Telegram, scheduled work,
tool execution, and reviewed long-term memory. It is designed to stay useful
with both hosted frontier models and small local models.

## Core features

### 0. Telegram integration

- Telegram chats are persisted as normal Iris sessions, so they share history,
  memory, tools, approvals, cancellation, and session reset policies with the
  web/API runtime.
- Rich messages are enabled by default: streamed drafts, formatted Markdown,
  code, tables, media blocks, live thinking, collapsed final thinking, and
  automatic fallback to legacy messages when the rich API fails.
- Iris accepts text and Telegram media, can send photos/documents, asks
  questions with custom reply keyboards, and renders sensitive tool approvals
  as user-facing Approve/Deny buttons. Tool calls and results have compact
  Telegram summaries instead of raw internal payloads.
- Access is deny-by-default unless the configured user/chat allowlist or
  `allow-all?` admits the update.

### 1. Small-model reliability

The `small-local` chat profile turns a weak local model into a stricter agent:

- tool schemas are routed by task category to reduce prompt load;
- tool choice is required and final output goes through a synthetic `respond`
  tool, avoiding ambiguous bare-text/tool mixtures;
- bounded nudges repair bare text, unknown tools, malformed arguments, missing
  read-before-write prerequisites, repeated calls/errors, premature finals,
  truncated output, and failed edits;
- doom-loop detection stops both identical calls and repeated multi-step tool
  sequences;
- per-call context packing budgets system prompt, memory, recent conversation,
  tool schemas, tool results, referenced files, and output reserve. Old tool
  results are truncated/compacted, stale nudges are dropped, and old session
  history is summarized while the current user turn and active tool loop remain
  protected.

The normal profile keeps the context and loop protections but disables
small-model-specific forced routing/nudging.

### 2. MAGI

MAGI is an optional independent decision layer, not another tool executor. A
Filter normalizes a concrete yes/no question and its risk; MELCHIOR (scientific
progress), BALTHASAR (care and safety), and CASPER (human wants and agency)
review it independently; Judge verifies the deterministic aggregate. One `no`
denies after valid yes/no votes, any condition remains conditional, and
approval requires unanimous unconditional `yes`. Errors or non-binary results
remain errors/information instead of being coerced into approval.

Iris uses MAGI for configured tool-approval decisions and memory-candidate
review, where a single acting model should not approve its own risky action or
its own claim as durable truth. Unsupported, failed, or critical reviews fall
back to a human unless policy explicitly says otherwise. The triumvirate can be
given a bounded read-only file-review loop; Filter and Judge never receive
tools. Prompts live in `resources/prompts/magi/` and each participant may use a
different provider/model.

### 3. Cron jobs

- Persistent jobs support five-field Unix cron schedules, one-shot `at`
  instants, and fixed-rate intervals, all with explicit IANA timezones, DST
  handling, misfire grace, occurrence limits, pause/resume, optimistic
  revisions, run-now, and run history.
- Every occurrence creates a fresh persisted Iris session and runs through the
  normal agent loop. The run snapshots prompt, provider/model, tool profile,
  origin, and notification target, so later configuration edits cannot change
  in-flight work.
- Tool profiles restrict each job (`cron-observe`, `cron-memory`, or
  `cron-automation`) without bypassing global roots, permissions, command
  policy, or approvals. Cron cannot recursively create cron jobs.
- Results are always stored locally. Delivery policy is `never`, `always`, or
  agent-controlled via one bounded `cron_notify` call; Telegram delivery can
  target the originating chat. Jobs are managed through web UI, HTTP API, CLI,
  or the `cronjob` tool; mutations use the normal approval path.

See [`docs/cron-jobs.md`](docs/cron-jobs.md).

### 4. Memory

Memory is layered by lifetime and authority:

1. **Turn context** — bounded system/context files, recalled memory, recent
   conversation, active tool loop, and compacted summaries.
2. **Working memory** — global or session scratchpads. Exact replacement
   requires the current SHA-256 revision, preventing stale concurrent edits.
3. **Episodic memory** — persisted SQLite messages, events, tool receipts, and
   compaction records. This is searchable history and audit evidence, not
   automatically trusted long-term knowledge.
4. **Durable memory** — high-confidence user-profile facts in Iris's managed
   `USER.md` section plus scoped OKF-style Markdown vault notes. Markdown is the
   source of truth; SQLite is a rebuildable search index.

Lifecycle:

```text
chat messages/events
  -> bounded recall for each turn
  -> idle extraction after a quiet window or explicit /dream
  -> candidate note / revision-guarded update, move, merge, or delete proposal
  -> MAGI or human review
  -> approved | rejected | superseded
  -> Markdown vault folders + regenerated index.md files
  -> heading-based chunks in SQLite FTS5/BM25
  -> optional embeddings + hybrid ranking
  -> scope-filtered, diverse, bounded recall context
```

`/dream` is the deliberate grooming pass: it extracts durable facts, updates
only the managed high-confidence portion of `USER.md`, searches for duplicates,
proposes corrections/merges/moves/deletions, prunes stale material, reindexes
and audits the vault, and may distill a repeated verified workflow into a skill.
Automatic idle extraction is lower-authority: it creates review candidates and
never silently promotes them to global memory.

Vault notes carry `iris.scope` (`global`, `project`, `session`, or `agent`),
`iris.status`, confidence, and `iris.origins`. Origins preserve compact message
and event ranges, session/project/request IDs, or source vault paths. Review
evidence is bounded and excluded from durable-body indexing and recall. Recall
admits approved global notes, approved notes for the active project, and
approved/`auto_session` notes for the active session. Embeddings are optional
and disabled by default; lexical search remains the baseline.

See [`docs/memory-architecture.md`](docs/memory-architecture.md).

### 5. Tools

Tools are registered capabilities with a stable name, JSON schema, category,
operation (`read` or `act`), permissions, routing categories, sensitivity, and
parallel-safety metadata. The runtime validates/coerces input, applies startup
allow/block/scope policy, checks entrypoint or cron-profile permissions, and
then executes independent safe reads in parallel while serializing sensitive,
approval-dependent, or tool-activating calls. Every call produces correlated
events and a bounded receipt returned to the model and UI.

Sensitive calls create persisted, expiring approvals bound to the exact tool,
input hash, permissions, and requester. They can be decided by the web UI,
Telegram buttons, MAGI policy, or explicit yolo mode; approval never grants a
different call. Built-ins cover filesystem, shell, HTTP/web search, memory,
todo, skills, cron, Telegram, Home Assistant, WASM/Endive, system reload/handoff,
and MAGI. Installed WASM bundles and remote MCP servers can add tools without
changing the agent loop. Skills add instructions only; they never grant tools
or permissions.

## Runtime layout

Canonical runtime lives under `src`:

- `agent.core`
- `agent.api`
- `agent.config`
- `agent.persistence.sqlite`
- `agent.llm.core`
- `agent.llm.providers.ollama`
- `agent.llm.providers.openai-compatible`
- `agent.chat`
- `agent.runtime.*`
- `agent.runners.*`
- `agent.telegram.*`
- `agent.cron.*`
- `agent.memory.*`
- `agent.magi.*`
- `agent.tools.*`

Default classpath is `src` + `resources`. Use `agent.runtime.*`,
`agent.runners.*`, and typed API handlers as source of truth.

Run CLI:

```bash
clojure -M -m agent.core "hello"
```

Headless CLI session flow:

```bash
clojure -M -m agent.core -p "start work"          # create persisted session
clojure -M -m agent.core -c "continue latest"     # resume latest session
clojure -M -m agent.core -r "continue selected"   # pick recent session
clojure -M -m agent.core --session <id> "resume"  # resume exact session
clojure -M -m agent.core --no-session "one shot"  # do not persist session
```

Run API:

```bash
clojure -M -m agent.core serve
```

Run API without SQLite native-access warning:

```bash
clojure -J--enable-native-access=ALL-UNNAMED -M -m agent.core serve
```

Run API with explicit config and without SQLite native-access warning:

```bash
clojure -J--enable-native-access=ALL-UNNAMED -M -m agent.core --config config/deepseek.local.edn serve
```

Configuration:

- Full operator reference: [`docs/configuration.md`](docs/configuration.md).
- Validate the complete effective config before restart with `clojure -M -m agent.core config validate` (or add `--config path/to/config.edn`).
- `~/.config/iris/config.edn` is a minimal override map using the same shape as `resources/config/default.edn`. Create missing global files with `clojure -M -m agent.core config init`.
- LLM model settings live under provider entries: `{:llm {:active-provider :ollama :providers {:ollama {:type :ollama :base-url "http://localhost:11434" :model "llama3.2:3b"} :deepseek {:type :openai-compatible :api :chat-completions :base-url "https://api.deepseek.com/v1" :api-key "..." :model "deepseek-chat"}}}}`.
- OpenAI-compatible providers default to `:api :chat-completions`; use `:api :responses` only for endpoints/models that support `/responses`.
- Legacy LLM config can be converted with `clojure -M -m agent.core config migrate path/to/config.edn`.
- Config values can be written with `clojure -M -m agent.core config set dotted.path value`, for example `clojure -M -m agent.core config set channel-adapters.telegram.rich_messages true`.
- `config set` parses values as EDN (`true`, `9090`, `:responses`, vectors/maps); bare words are written as strings. `_` in path segments matches `-`, and existing boolean keys ending in `?` are auto-resolved.
- `config set` writes the highest-precedence source file that already defines the path, including files listed in `:config/includes`. New paths go to `--config` when supplied, else existing `./.iris/config.edn`, else `~/.config/iris/config.edn`.
- If a generated file starts with `#:iris`, nested keys like `:api` read as `:iris/api`; current loader normalizes this, but prefer normal map syntax: `{:iris/config-version 1 :api {:port 9090}}`.
- Config dir resolution: `IRIS_CONFIG_DIR`, then `$XDG_CONFIG_HOME/iris`, then `~/.config/iris`.
- Project-local overlay dir: `./.iris/`.
- Global files are created from `resources/` templates by `config init`: `config.edn`, `SOUL.md`, `AGENTS.md`, `USER.md`, `TOOLS.md`, `BOOT.md`, `HEARTBEAT.md`.
- EDN merge order: global `config.edn` → local `./.iris/config.edn` → explicit `--config` file → env vars.
- Markdown context files merge by concatenating global then local in this order: `SOUL.md`, `AGENTS.md`, `USER.md`, `TOOLS.md`, `BOOT.md`, `HEARTBEAT.md`.
- `:memory {:vault {:paths ["memory"]}}` configures Markdown vault roots;
  relative paths resolve against the process working directory.
- `:skills {:dirs ["skills"]}` resolves relative dirs as config-dir first, then process cwd.

WASM / Endive:

- Standalone Clojure library lives in `export/endive-clj` and is linked from Iris via `:local/root`; public API is `endive-clj.core`: `compile-module`, `instantiate`, `invoke`, `run-wasi`, `close!`.
- Iris tool `wasm_execute` is disabled by default. Enable with `clojure -M -m agent.core config set tools.wasm.enabled true`.
- Tool execution needs `:wasm-execute` permission and accepts Base64 Wasm:

```clojure
{:wasm-base64 "AGFzbQEAAA..."
 :mode "invoke"
 :export "add"
 :args [2 40]}
```

- WASI mounts are data maps. Virtual mounts are safest: `{:guest "/workspace" :type :virtual :files {"in.txt" "hi"}}`.
- Host mounts require configured `tools.wasm.wasi.fs.allowed-roots`; read-only host mounts are copied to Jimfs, writable host mounts use the host path directly.
- Network is not WASI sockets. It is an opt-in host-function pack controlled by `tools.wasm.network`, not by tool input.

OpenTelemetry:

- Iris uses μ/log's OTLP HTTP publisher. Supported signals: `traces`, `logs`. Real OTLP `metrics` export is not implemented by the current publisher.
- Runtime trace events are exported to OTel traces when `logging.otel.enabled=true`, even if local JSONL trace mode is `none`.
- EDN example:

```clojure
{:logging {:otel {:enabled true
                  :url "http://localhost:4318/"
                  :send [:traces]
                  :max-items 5000
                  :publish-delay 5000
                  :http-opts {:conn-timeout 2000
                              :socket-timeout 2000}}}}
```

Async HTTP tasks:

- Iris exposes a minimal A2A HTTP+JSON task API for webhook-style callers.
- Discovery: `GET /.well-known/agent-card.json`.
- Submit: `POST /message:send`.
- Poll: `GET /tasks/{task-id}`.
- List: `GET /tasks?contextId={session-id}&status=TASK_STATE_WORKING`.
- Cancel: `POST /tasks/{task-id}:cancel`.
- If `:api :key` is configured, A2A task endpoints use the same `X-API-Key` / `Authorization: Bearer ...` auth as `/v1/*`.
- Idempotency uses `Idempotency-Key`; if absent, `message.messageId`.
- `contextId` maps to Iris `session_id`. If omitted, Iris creates a new session.
- Final text answer is in `task.artifacts[0].parts[0].text`.

Submit example:

```bash
TASK_ID="$(curl -sS http://127.0.0.1:8080/message:send \
  -H 'Content-Type: application/a2a+json' \
  -H 'Idempotency-Key: webhook-123' \
  -d '{"message":{"messageId":"msg-123","role":"ROLE_USER","parts":[{"text":"Run this task"}]}}' \
  | jq -r '.task.id')"
```

Poll example:

```bash
curl -sS "http://127.0.0.1:8080/tasks/${TASK_ID}?historyLength=2"
```

A2A compatibility notes:

- Implemented subset: `/.well-known/agent-card.json`, `/message:send`, `/tasks`, `/tasks/{id}`, `/tasks/{id}:cancel`.
- Not implemented: JSON-RPC, gRPC, `/message:stream`, `/tasks/{id}:subscribe`, push notification config endpoints.
- `/message:send` always returns immediately with a Task; it does not implement A2A's blocking default when `configuration.returnImmediately` is absent/false.
- Input supports text parts only. `raw`, `url`, and `data` parts return `CONTENT_TYPE_NOT_SUPPORTED`.
- `message.taskId` is accepted only to infer `contextId`; Iris creates a new task instead of mutating/continuing the existing task.
- Cancel is session-scoped because Iris chat cancellation is session-scoped; canceling one task cancels other non-terminal tasks in the same `contextId`.
- `pageToken` is ignored; task listing returns the newest matching tasks from the local store.

Run tests in isolated config/data directories:

```bash
mkdir -p target/test-iris-config target/test-iris-data
env IRIS_CONFIG_DIR=target/test-iris-config IRIS_DATA_DIR=target/test-iris-data \
  clojure -M:test -e "(require 'agent.test-runner :reload) (agent.test-runner/run-all-tests) (shutdown-agents)"
```

Build and upload the standalone JAR:

```bash
./scripts/deploy-jar.sh
```

Required: `IRIS_DEPLOY_HOST`. Optional: `IRIS_DEPLOY_USER`, `IRIS_DEPLOY_DIR`, `IRIS_DEPLOY_PORT`, `IRIS_DEPLOY_JAR`, `IRIS_DEPLOY_SSH_OPTS`.

Notes:

- Ollama, OpenRouter, and OpenAI-compatible endpoints are first-class providers.
- SQLite persists sessions, messages, completions, events, approvals, memory
  indexes, cron jobs/runs, todos, and async tasks.
- Default logs go to `logs/iris.log`.
- Logging env vars:
  - `AGENT_LOG_FILE`
  - `AGENT_LOG_ENABLED`
- API env vars:
  - `AGENT_API_HOST`
  - `AGENT_API_PORT`
- SQLite env var:
  - `AGENT_SQLITE_PATH`
  - `AGENT_SQLITE_DESTRUCTIVE_RESET_ON_DRIFT=true`: delete and rebuild SQLite files if migration metadata drift is detected. Default is false; otherwise Iris prints exact files to delete.
- SSE chat streaming is available on `/v1/chat/completions` with `{\"stream\": true}`.
- `/loop run` and CLI loop `--run` no longer execute shell validation commands. Run checks through approved shell/tool paths.

## Run Iris isolated

Build the standalone jar first:

```bash
clojure -T:uberjar uberjar
```

Or rebuild and run in one step:

```bash
scripts/iris-isolated-rebuild.sh serve
```

macOS:

```bash
mkdir -p ~/.config/iris
scripts/iris-isolated.sh serve
```

Linux:

```bash
mkdir -p ~/.config/iris
scripts/iris-isolated.sh serve
```

`scripts/iris-isolated.sh` runs the main Iris process under Seatbelt on macOS and Bubblewrap on Linux. It allows read access to host files, but write/delete access only under the launch cwd and `~/.config/iris`. Override with `IRIS_SANDBOX=seatbelt|bubblewrap`, `IRIS_JAR=target/iris.jar`, or `JAVA_CMD=/path/to/java`.

Ubuntu 24.04 may block unprivileged Bubblewrap with AppArmor. For a local test:

```bash
sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0
scripts/iris-isolated.sh serve
sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=1
```

Java warning note:

- `sqlite-jdbc` loads native code.
- On newer Java, run with `--enable-native-access=ALL-UNNAMED`.
- Alternative:

```bash
export JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED
```

Then launch normally, including `--config` if needed:

```bash
clojure -M -m agent.core --config config/deepseek.local.edn serve
```

## Docker

0.1 deploy target: one Docker image. Kubernetes manifests were removed; Compose is only an optional local wrapper.

Build:

```bash
docker build -t iris:0.1 .
```

Run:

```bash
docker run --rm \
  -p 8080:8080 \
  -v iris-data:/app/data \
  -e AGENT_API_KEY=change-me \
  -e AGENT_API_HOST=0.0.0.0 \
  -e AGENT_SQLITE_PATH=/app/data/agent.db \
  -e JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED \
  -e AGENT_LLM_PROVIDER=ollama \
  -e AGENT_LLM_MODEL=llama3.2:3b \
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
  -e AGENT_TELEGRAM_ENABLED=false \
  -e AGENT_TELEGRAM_BOT_TOKEN= \
  -e AGENT_TELEGRAM_ALLOWED_USER_IDS= \
  -e AGENT_TELEGRAM_ALLOWED_CHAT_IDS= \
  -e AGENT_MEMORY_VAULT_PATHS=/app/data/memory \
  iris:0.1
```

OpenRouter/OpenAI-compatible example:

```bash
docker run --rm \
  -p 8080:8080 \
  -v iris-data:/app/data \
  -e AGENT_API_KEY=change-me \
  -e AGENT_API_HOST=0.0.0.0 \
  -e AGENT_SQLITE_PATH=/app/data/agent.db \
  -e AGENT_LLM_PROVIDER=openrouter \
  -e AGENT_LLM_MODEL=openai/gpt-4.1-mini \
  -e OPENROUTER_API_KEY="$OPENROUTER_API_KEY" \
  iris:0.1
```

Health:

```bash
curl http://localhost:8080/health
```

Required/important env:

- `AGENT_API_KEY`: protects `/v1/*` and `/ui/*`.
- `AGENT_API_HOST=0.0.0.0`: required inside container.
- `IRIS_DATA_DIR=~/.config/iris/data`: default host data dir.
- `AGENT_SQLITE_PATH=/app/data/agent.db`: persisted SQLite path.
- `AGENT_SQLITE_DESTRUCTIVE_RESET_ON_DRIFT=false`: keep false in production unless data loss is acceptable; true rebuilds drifted DB files.
- `JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED`: suppresses sqlite-jdbc native access warning.
- Tool permissions/policy: `AGENT_API_TOOL_PERMISSIONS`, `AGENT_UI_TOOL_PERMISSIONS`, `AGENT_AGENT_TOOL_PERMISSIONS`, `AGENT_CHAT_TOOL_PERMISSIONS`, `AGENT_TOOL_ALLOWLIST`, `AGENT_TOOL_BLOCKLIST`, `AGENT_TOOL_APPROVAL_TTL_SECONDS`, `AGENT_TOOLS_YOLO`.
- LLM: `AGENT_LLM_PROVIDER`, `AGENT_LLM_MODEL`, `AGENT_LLM_API=chat-completions|responses`, plus `OLLAMA_BASE_URL`, `OPENROUTER_API_KEY`, or `OPENAI_API_KEY`.
- Telegram: `AGENT_TELEGRAM_ENABLED`, `AGENT_TELEGRAM_BOT_TOKEN`, `AGENT_TELEGRAM_RICH_MESSAGES`, `AGENT_TELEGRAM_ALLOWED_USER_IDS`, `AGENT_TELEGRAM_ALLOWED_CHAT_IDS`, `AGENT_TELEGRAM_ALLOW_ALL`. Empty allowlist denies by default. Telegram chats appear in the Sessions sidebar as `Telegram: <name>`.
- Memory: `AGENT_MEMORY_VAULT_PATHS`, `AGENT_MEMORY_VAULT_WRITABLE`, `AGENT_MEMORY_SEARCH_DEFAULT_LIMIT`, `AGENT_MEMORY_SEARCH_MAX_LIMIT`, `AGENT_MEMORY_SEARCH_MIN_SCORE`.
- Note extraction: `AGENT_NOTE_EXTRACTOR_ENABLED`, `AGENT_NOTE_EXTRACTOR_PROVIDER`, `AGENT_NOTE_EXTRACTOR_MODEL`, `AGENT_NOTE_EXTRACTOR_FORMAT`.
- nREPL: `AGENT_NREPL_ENABLED`, `AGENT_NREPL_BIND`, `AGENT_NREPL_PORT`, `AGENT_NREPL_PORT_FILE`. `serve` writes the selected port to `.nrepl-port`.
