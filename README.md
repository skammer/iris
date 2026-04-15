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
- SSE chat streaming is available on rewritten `/v1/chat/completions` with `{\"stream\": true}`.
- `API.md`, `USAGE.md`, `PROJECT_SUMMARY.md` describe archived legacy system unless rewritten later.
