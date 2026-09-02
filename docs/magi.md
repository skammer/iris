# MAGI

MAGI is Iris's optional independent review layer. It can review tool approvals,
Vault Note candidates, and explicit policy questions without executing the
reviewed action.

## Decision flow

1. Filter normalizes the question and classifies risk.
2. MELCHIOR, BALTHASAR, and CASPER review the same normalized context.
3. Judge receives only their structured votes and checks the deterministic
   aggregate.

Votes are `yes`, `conditional`, `no`, `info`, or `error`. Unanimous
unconditional `yes` is required for approval. `no` denies; `conditional` stays
pending for human review; `info` and `error` follow the configured fallback.

## Configuration

MAGI is disabled by default. Add only intentional overrides:

```clojure
{:magi
 {:enabled? true
  :mode :assistive
  :fallback :human
  :apply-to #{:tool-approvals}
  :tool-categories #{:all}
  :allow-critical? false
  :memory-promotion {:mode :manual
                     :scopes #{:all}}}}
```

Approval modes:

- `assistive`: evaluate and log; leave every approval pending.
- `auto-approve`: approve unanimous `yes`, deny `no`, leave `conditional`
  pending, and apply `fallback` to `info` or `error`.

Fallback is `human` or `deny`. Critical requests remain outside automatic
review unless `allow-critical?` is explicitly enabled.

Memory-promotion modes:

- `manual`: the operator's Review action may apply a unanimous `yes`.
- `auto`: periodically review eligible candidates and apply unanimous `yes`.
- `assistive`: produce advice without applying it.
- `off`: disable memory review.

`scopes`, polling interval, error cooldown, and batch size bound automatic
memory review. A Review action with `no` marks the candidate or proposal
rejected; advice-only review does not change state.

## Explicit tool

The read-only `magi` tool requires `magi-evaluate`. It evaluates a supplied
question but cannot approve or deny an existing approval row. Setting
`file-review` allows the three reviewers a bounded read-only evidence-gathering
loop; Filter and Judge never receive tools.

## Audit and troubleshooting

- Web UI: open **MAGI** for recent evaluations and participant votes.
- Tool decisions: `tool.approval.magi_evaluated`.
- Memory candidates: `memory.vault.magi_evaluated`.
- Memory updates: `memory.vault.update_magi_evaluated`.
- Prompts: `resources/prompts/magi/*.md`.
- Runtime: `src/agent/magi/`, `src/agent/tools/approvals.clj`, and
  `src/agent/memory/magi_review.clj`.

Provider/model overrides may be set independently for Filter, Judge, and each
reviewer. A nil override inherits the active provider/model.
