# clj-agent

Current canonical runtime is rewritten slice:

- `agent.core`
- `agent.api`
- `agent.config`
- `agent.persistence.sqlite`
- `agent.llm.core`
- `agent.llm.providers.ollama`
- `agent.llm.providers.openai-compatible`

Default path now excludes legacy runtime/modules. Legacy code was quarantined under `legacy_src/`.

Run rewritten CLI:

```bash
clojure -M -m agent.core "hello"
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

Run rewritten tests:

```bash
clojure -M:test -e "(require 'agent.test-runner) (agent.test-runner/run-all-tests)"
```

Load legacy namespaces intentionally:

```bash
clojure -M:legacy
```

Notes:

- OpenRouter + Ollama are first-class providers in rewritten path.
- SQLite session/message/completion persistence is in rewritten path.
- Default logs go to `logs/clj-agent.log`.
- Logging env vars:
  - `AGENT_LOG_FILE`
  - `AGENT_LOG_ENABLED`
- API env vars:
  - `AGENT_API_HOST`
  - `AGENT_API_PORT`
- SQLite env var:
  - `AGENT_SQLITE_PATH`
- Isolated child runtime env vars:
  - `AGENT_CONTROL_URL`: parent API URL used by container children.
  - `AGENT_BOOTSTRAP_TOKEN`: per-run token; injected by runner.
  - `AGENT_CHILD_SQLITE_PATH`: child-owned SQLite path; not the parent DB.
- SSE chat streaming is available on rewritten `/v1/chat/completions` with `{\"stream\": true}`.
- `API.md`, `USAGE.md`, `PROJECT_SUMMARY.md` describe archived legacy system unless rewritten later.

Runtime isolation note:

- Parent owns durable SQLite run/session state.
- Docker/Podman children do not mount the parent DB.
- Container children communicate with parent through `/v1/runs/:run-id/control/*` using the bootstrap token.
- Local-process children may still use direct SQLite for dev/local compatibility.

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
docker build -t clj-agent:0.1 .
```

Run:

```bash
docker run --rm \
  -p 8080:8080 \
  -v clj-agent-data:/app/data \
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
  -e AGENT_MEMORY_GRAPH_ENABLED=false \
  -e AGENT_MEMORY_GRAPH_PATH=/app/data/memory-graph \
  clj-agent:0.1
```

OpenRouter/OpenAI-compatible example:

```bash
docker run --rm \
  -p 8080:8080 \
  -v clj-agent-data:/app/data \
  -e AGENT_API_KEY=change-me \
  -e AGENT_API_HOST=0.0.0.0 \
  -e AGENT_SQLITE_PATH=/app/data/agent.db \
  -e AGENT_LLM_PROVIDER=openrouter \
  -e AGENT_LLM_MODEL=openai/gpt-4.1-mini \
  -e OPENROUTER_API_KEY="$OPENROUTER_API_KEY" \
  clj-agent:0.1
```

Health:

```bash
curl http://localhost:8080/health
```

Required/important env:

- `AGENT_API_KEY`: required for 0.1 API auth work; set now for deploy parity.
- `AGENT_API_HOST=0.0.0.0`: required inside container.
- `AGENT_SQLITE_PATH=/app/data/agent.db`: persisted SQLite path.
- `JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED`: suppresses sqlite-jdbc native access warning.
- Tool permissions/policy: `AGENT_API_TOOL_PERMISSIONS`, `AGENT_UI_TOOL_PERMISSIONS`, `AGENT_AGENT_TOOL_PERMISSIONS`, `AGENT_TOOL_ALLOWLIST`, `AGENT_TOOL_BLOCKLIST`, `AGENT_TOOL_APPROVAL_TTL_SECONDS`, `AGENT_TOOLS_YOLO`.
- LLM: `AGENT_LLM_PROVIDER`, `AGENT_LLM_MODEL`, plus `OLLAMA_BASE_URL`, `OPENROUTER_API_KEY`, or `OPENAI_API_KEY`.
- Telegram: `AGENT_TELEGRAM_ENABLED`, `AGENT_TELEGRAM_BOT_TOKEN`, `AGENT_TELEGRAM_ALLOWED_USER_IDS`, `AGENT_TELEGRAM_ALLOWED_CHAT_IDS`, `AGENT_TELEGRAM_ALLOW_ALL`. Empty allowlist denies by default; set `AGENT_TELEGRAM_ALLOW_ALL=true` only for open bots.
- Memory: `AGENT_MEMORY_PROMPT_PATHS`, `AGENT_MEMORY_SEARCH_DEFAULT_LIMIT`, `AGENT_MEMORY_GRAPH_ENABLED`, `AGENT_MEMORY_GRAPH_PATH`.
- Fact extraction: `AGENT_FACT_EXTRACTOR_ENABLED`, `AGENT_FACT_EXTRACTOR_PROVIDER`, `AGENT_FACT_EXTRACTOR_MODEL`.
