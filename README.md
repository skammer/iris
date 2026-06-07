# iris: Isolated Reasoning & Intelligence Substrate

Current canonical runtime lives under `src`:

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

Default classpath is `src` + `resources`. `legacy_src` is available only through the `:legacy` alias. New work should use `agent.runtime.*`, `agent.runners.*`, and typed API handlers as source of truth.

Run rewritten CLI:

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

Run rewritten API:

```bash
clojure -M -m agent.core serve
```

Run rewritten API without SQLite native-access warning:

```bash
clojure -J--enable-native-access=ALL-UNNAMED -M -m agent.core serve
```

Run rewritten API with explicit config and without SQLite native-access warning:

```bash
clojure -J--enable-native-access=ALL-UNNAMED -M -m agent.core --config config/deepseek.local.edn serve
```

Configuration:

- `~/.config/iris/config.edn` uses the same shape as `resources/config/default.edn`. Create missing global files with `clojure -M -m agent.core config init`.
- LLM model settings live under provider entries: `{:llm {:active-provider :ollama :providers {:ollama {:type :ollama :base-url "http://localhost:11434" :model "llama3.2:3b"} :deepseek {:type :openai-compatible :api :chat-completions :base-url "https://api.deepseek.com/v1" :api-key "..." :model "deepseek-chat"}}}}`.
- OpenAI-compatible providers default to `:api :chat-completions`; use `:api :responses` only for endpoints/models that support `/responses`.
- Legacy LLM config can be converted with `clojure -M -m agent.core config migrate path/to/config.edn`.
- If a generated file starts with `#:iris`, nested keys like `:api` read as `:iris/api`; current loader normalizes this, but prefer normal map syntax: `{:iris/config-version 1 :api {:port 9090}}`.
- Config dir resolution: `IRIS_CONFIG_DIR`, then `$XDG_CONFIG_HOME/iris`, then `~/.config/iris`.
- Project-local overlay dir: `./.iris/`.
- Global files are created from `resources/` templates by `config init`: `config.edn`, `SOUL.md`, `AGENTS.md`, `USER.md`, `TOOLS.md`, `BOOT.md`, `HEARTBEAT.md`, `MEMORY.md`.
- EDN merge order: global `config.edn` → local `./.iris/config.edn` → explicit `--config` file → env vars.
- Markdown context files merge by concatenating global then local in this order: `SOUL.md`, `AGENTS.md`, `USER.md`, `TOOLS.md`, `BOOT.md`, `HEARTBEAT.md`.
- `:memory {:prompt {:paths ["MEMORY.md"]}}` uses paths relative to process cwd unless absolute paths are configured.
- `:skills {:dirs ["skills"]}` resolves relative dirs as config-dir first, then process cwd.

Run rewritten tests:

```bash
clojure -M:test -e "(require 'agent.test-runner) (agent.test-runner/run-all-tests)"
```

Build and upload the standalone JAR:

```bash
./scripts/deploy-jar.sh
```

Required: `IRIS_DEPLOY_HOST`. Optional: `IRIS_DEPLOY_USER`, `IRIS_DEPLOY_DIR`, `IRIS_DEPLOY_PORT`, `IRIS_DEPLOY_JAR`, `IRIS_DEPLOY_SSH_OPTS`.

Load legacy namespaces intentionally:

```bash
clojure -M:legacy
```

Notes:

- OpenRouter + Ollama are first-class providers in rewritten path.
- SQLite session/message/completion persistence is in rewritten path.
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
- Runner env var:
  - `AGENT_RUNNER_DEFAULT_SUBSTRATE=auto|seatbelt|bubblewrap|docker|podman|local-unsandboxed`. `auto` means Seatbelt on macOS, Bubblewrap on Linux.
- Orchestrator env var:
  - `AGENT_ORCHESTRATOR_ENABLED=true`: enables experimental in-memory `/v1/agents`, `/v1/channels`, and `/v1/federation` APIs. Default is false because this state is not durable.
  - Federation outbound signing: `AGENT_FEDERATION_KEY_ID`, `AGENT_FEDERATION_PRIVATE_KEY`.
  - Federation tuning: `AGENT_FEDERATION_TIMEOUT_MS`, `AGENT_FEDERATION_MAX_CLOCK_SKEW_MS`, `AGENT_FEDERATION_OUTBOX_POLL_MS`.
- Isolated child runtime env vars:
  - `AGENT_CONTROL_URL`: parent API URL used by container children.
  - `AGENT_BOOTSTRAP_TOKEN`: per-run token; injected by runner.
  - `AGENT_CHILD_SQLITE_PATH`: child-owned SQLite path; not the parent DB.
- SSE chat streaming is available on rewritten `/v1/chat/completions` with `{\"stream\": true}`.
- `/loop run` and CLI loop `--run` no longer execute shell validation commands. Run checks through approved shell/tool paths.

Runtime isolation note:

- Parent owns durable SQLite run/session state.
- Docker/Podman children do not mount the parent DB.
- Child runners communicate with parent through `/v1/runs/:run-id/control/*` using the bootstrap token. These endpoints accept only the run bootstrap token; normal API keys do not authorize run-control calls.
- API/UI run creation defaults to safe isolation: Seatbelt on macOS, Bubblewrap on Linux. `local-unsandboxed` is explicit dev mode.

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
  -e AGENT_MEMORY_PROMPT_PATHS=/app/data/MEMORY.md \
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

- `AGENT_API_KEY`: protects `/v1/*` and `/ui/*`, except `/v1/runs/:run-id/control/*`, which uses the per-run bootstrap token.
- `AGENT_API_HOST=0.0.0.0`: required inside container.
- `IRIS_DATA_DIR=~/.config/iris/data`: default host data dir. `AGENT_SQLITE_PATH` and `AGENT_MEMORY_GRAPH_PATH` override individual stores.
- `AGENT_SQLITE_PATH=/app/data/agent.db`: persisted SQLite path.
- `AGENT_SQLITE_DESTRUCTIVE_RESET_ON_DRIFT=false`: keep false in production unless data loss is acceptable; true rebuilds drifted DB files.
- `AGENT_RUNNER_DEFAULT_SUBSTRATE=auto`: Seatbelt on macOS, Bubblewrap on Linux.
- `AGENT_ORCHESTRATOR_ENABLED=false`: keep false unless testing experimental process-local agent/federation APIs.
- Federation peers use `keys [{key_id, public_key, status, valid_from, valid_until}]`; outbound federation requires `AGENT_FEDERATION_KEY_ID` + `AGENT_FEDERATION_PRIVATE_KEY`.
- `JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED`: suppresses sqlite-jdbc native access warning.
- Tool permissions/policy: `AGENT_API_TOOL_PERMISSIONS`, `AGENT_UI_TOOL_PERMISSIONS`, `AGENT_AGENT_TOOL_PERMISSIONS`, `AGENT_TOOL_ALLOWLIST`, `AGENT_TOOL_BLOCKLIST`, `AGENT_TOOL_APPROVAL_TTL_SECONDS`, `AGENT_TOOLS_YOLO`.
- LLM: `AGENT_LLM_PROVIDER`, `AGENT_LLM_MODEL`, `AGENT_LLM_API=chat-completions|responses`, plus `OLLAMA_BASE_URL`, `OPENROUTER_API_KEY`, or `OPENAI_API_KEY`.
- Telegram: env `AGENT_TELEGRAM_ENABLED`, `AGENT_TELEGRAM_BOT_TOKEN`, `AGENT_TELEGRAM_ALLOWED_USER_IDS`, `AGENT_TELEGRAM_ALLOWED_CHAT_IDS`, `AGENT_TELEGRAM_ALLOW_ALL`. Empty allowlist denies by default. Telegram chats appear in the Sessions sidebar as `Telegram: <name>`.
- Memory: `AGENT_MEMORY_PROMPT_PATHS`, `AGENT_MEMORY_SEARCH_DEFAULT_LIMIT`, `AGENT_MEMORY_SEARCH_MAX_LIMIT`, `AGENT_MEMORY_GRAPH_ENABLED`, `AGENT_MEMORY_GRAPH_PATH`.
- Fact extraction: `AGENT_FACT_EXTRACTOR_ENABLED`, `AGENT_FACT_EXTRACTOR_PROVIDER`, `AGENT_FACT_EXTRACTOR_MODEL`.
- nREPL: `AGENT_NREPL_ENABLED`, `AGENT_NREPL_BIND`, `AGENT_NREPL_PORT`, `AGENT_NREPL_PORT_FILE`. `serve` writes the selected port to `.nrepl-port`.
