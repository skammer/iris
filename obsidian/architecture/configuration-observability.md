# Configuration & Observability

#architecture #reference

## Config Files

Iris loads config overlays in this order:

1. Global `~/.config/iris/config.edn`
2. Local `./.iris/config.edn`
3. Explicit `--config path/to/config.edn`
4. Environment variables

`config init` writes `config.edn`; Iris config is EDN.

## OpenTelemetry

Iris uses μ/log's OTLP HTTP publisher for OpenTelemetry.

Supported signals:

- `traces`
- `logs`

Not supported by the current μ/log publisher:

- real OTLP `metrics`

Runtime trace events from `agent.runtime.trace/record-event!` export to OTel traces when `logging.otel.enabled=true`, even if local JSONL trace mode is `none`.

EDN:

```clojure
{:logging {:otel {:enabled true
                  :url "http://localhost:4318/"
                  :send [:traces]
                  :max-items 5000
                  :publish-delay 5000
                  :http-opts {:conn-timeout 2000
                              :socket-timeout 2000}}}}
```

Environment:

```bash
AGENT_OTEL_ENABLED=true
AGENT_OTEL_URL=http://localhost:4318/
AGENT_OTEL_SEND=traces
```
