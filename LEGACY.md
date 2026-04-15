# Legacy Runtime

Archived pre-rewrite namespaces live in `legacy_src/`.

Examples:

```bash
clojure -M:legacy -e "(require 'agent.llm)"
clojure -M:legacy:test -e "(require 'agent.integration-tests)"
```

Legacy path is not part of default runtime/build/test flow.

Canonical runtime remains:

- `agent.core`
- `agent.api`
- `agent.config`
- `agent.persistence.sqlite`
- `agent.llm.core`
- `agent.llm.providers.ollama`
- `agent.llm.providers.openai-compatible`
