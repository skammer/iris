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
- SSE chat streaming is available on rewritten `/v1/chat/completions` with `{\"stream\": true}`.
- `API.md`, `USAGE.md`, `PROJECT_SUMMARY.md` describe archived legacy system unless rewritten later.

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
