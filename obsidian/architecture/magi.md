# MAGI Oversight

#architecture #magi #approvals #memory

Status: implemented. Operator configuration and troubleshooting live in
`docs/magi.md`.

## Role

MAGI separates proposing an action or durable memory from reviewing it. It is a
decision layer only: it never executes tools and its explicit tool cannot alter
existing approval rows.

```text
request
  -> Filter: normalize question + classify domain/risk
  -> MELCHIOR + BALTHASAR + CASPER: independent structured votes
  -> Judge: validate deterministic aggregate
  -> existing approval/memory state transition
```

Filter output is a typed `yes-no`, `info`, or `unsupported` classification.
Participant and Judge outputs use only `yes`, `conditional`, `no`, `info`, or
`error`, plus a short reason.

## Aggregation invariants

Precedence is `error` -> `info` -> `no` -> `conditional` -> unanimous `yes`.
Judge receives only the three participant outputs. It does not receive the
original prompt, tool input, risk classification, or evidence; it must neither
reinterpret conditions nor invent missing facts.

Runtime, API, DB rows, events, and reasons use ASCII keywords. Display-only
labels may be localized without changing persisted semantics.

## Tool approvals

`agent.tools.approvals` creates the ordinary `tool_approvals` row first, then
asks MAGI only when enabled and in scope. This preserves existing API, Web UI,
Telegram, expiry, actor, and input-hash behavior.

In `assistive`, the row stays pending. In `auto-approve`, unanimous `yes` writes
an approved decision with actor `magi`; `no` writes denied; `conditional` stays
pending for human review. `info` and `error` use the configured human-or-deny
fallback. Critical risk is excluded unless explicitly enabled.

Tool category selection scopes evaluation; it never bypasses Filter,
participant review, Judge, or normal tool policy.

## Memory review

`agent.memory.magi-review` handles Vault Note candidates and pending memory or
skill update proposals. Automatic work is bounded by scope, polling interval,
cooldown, and batch size. Content revision and audit events prevent stale review
results from being treated as current.

- `auto`: worker reviews and may apply unanimous `yes`.
- `manual`: explicit Review may apply unanimous `yes`.
- `assistive`: Advice records a verdict without state mutation.
- `off`: review disabled.

Applying an accepted note moves it into its deterministic durable folder.
Accepted skill candidates are installed through the skill registry; failures
roll back the install. Explicit Review with `no` rejects the candidate/proposal.

## File evidence

For requests marked `file-review`, only the three participants may use a
bounded read-only tool loop. Tool-call count, rounds, timeout, evidence size,
and result size are capped. Filter and Judge remain tool-free.

## Configuration and providers

`resources/config/default.edn` owns the contract. Filter, Judge, and each
participant may pin separate provider/model overrides; nil inherits the active
selection. Prompts are bundled under `resources/prompts/magi/` so runtime config
contains policy and model selection, not prompt bodies.

## Audit surfaces

- `tool.approval.magi_evaluated`
- `memory.vault.magi_evaluated`
- `memory.vault.update_magi_evaluated`
- Web UI **MAGI** tab for result, votes, provider selection, latency, and reason

## Related code

- `src/agent/magi/core.clj`
- `src/agent/magi/file_review.clj`
- `src/agent/tools/common/magi.clj`
- `src/agent/tools/approvals.clj`
- `src/agent/memory/magi_review.clj`
- `resources/prompts/magi/`
