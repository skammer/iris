# MAGI

## TL;DR

Фича: LLM-триумвират для автоматического надзора. Заменяет human approval только там, где явно включено и где вопрос классифицирован как подходящий.

Итоговые решения:

- Human approval остаётся fallback.
- Magi пишет обычный `tool_approvals` decision: `approved` или `denied`; текущие UI/API/Telegram почти не ломаются.
- Magi также доступен как read-only tool для ручной проверки решений.
- Prompts лежат в `resources/prompts/magi/*.md`, не в config.
- Config хранит только enabled, provider/model/таймауты, scope применения.
- Ответы строго enum + optional comment. Freeform запрещён.

Confidence: 0.86

## Термины

- Filter: классифицирует запрос, нормализует его в Magi question.
- Triumvirate: MELCHIOR • 1, BALTHASAR • 2, CASPER • 3.
- Judge: агрегирует только ответы триумвирата и выдаёт финальное решение.
- Human fallback: текущая очередь approval.

## Decision Enums

### Filter Output

```edn
{:kind :yes-no              ;; :yes-no | :info | :unsupported
 :domain :tool-approval     ;; :tool-approval | :memory-promotion | :policy | :other
 :risk :low                 ;; :low | :medium | :high | :critical
 :question "...normalized..."
 :expected-response :permit ;; :permit | :classify | :opine
 :context {...}}
```

Rules:

- `:yes-no`: can be decided by Magi.
- `:info`: not yes/no; Judge returns `:info`.
- `:unsupported`: use human fallback.
- `:critical`: use human fallback unless config explicitly allows.

### Triumvirate Output

```edn
{:response :yes             ;; :yes | :conditional | :no | :info | :error
 :comment "optional"}
```

Rules:

- no raw prose as decision.
- `:conditional` means "yes, but condition must be enforced before execution".
- malformed output => `:error`.

### Judge Output

```edn
{:decision :yes             ;; :error | :info | :no | :conditional | :yes
 :reason "short"
 :agents {:melchior {...}
          :balthasar {...}
          :casper {...}}}
```

Mapping:

- `:error`: one or more agents errored.
- `:info`: Filter/Judge says not yes/no.
- `:no`: at least one agent answered no.
- `:conditional`: at least one conditional yes, no no; treated as denial until the condition is satisfied by a new attempt.
- `:yes`: all unconditional yes.

Judge input rule:

- Judge receives only the three triumvirate outputs.
- Judge does not receive original context, tool input, risk classification, or user prompt.
- Judge does not validate risk, infer missing facts, reinterpret conditions, or invent a decision.
- Judge applies deterministic aggregation over the enum responses.

Documentation-only display labels:

- `:error`: `誤 差`
- `:info`: `情 報`
- `:no`: `拒 絶`
- `:conditional`: `状 態`
- `:yes`: `合 意`

Rule: code, API responses, DB rows, events, and runtime reasons use ASCII keywords/text only. Japanese names are documentation/design copy only.

## Bundled Prompts

Files:

- `resources/prompts/magi/filter.md`
- `resources/prompts/magi/melchior.md`
- `resources/prompts/magi/balthasar.md`
- `resources/prompts/magi/casper.md`
- `resources/prompts/magi/judge.md`

Base prompts:

```text
MELCHIOR • 1
You are a scientist. Your goal is to further our understanding of the universe and advance our technological progress.

BALTHASAR • 2
You are a mother. Your goal is to protect your children and ensure their well-being.

CASPER • 3
You are a woman. Your goal is to pursue love, dreams and desires.
```

Each prompt also must require:

- output JSON only.
- use enum only.
- reject unclear, unsafe, irreversible, or under-specified action with `no` or `conditional`.
- never invent missing context.

Judge prompt additionally must require: aggregate only supplied agent responses; no independent analysis.

## Config Shape

Add to `resources/config/default.edn`:

```edn
:magi {:enabled? false
       :mode :assistive          ;; default; :assistive | :auto-approve
       :fallback :human          ;; :human | :deny
       :apply-to #{:tool-approvals}
       :tool-categories #{:all}
       :tool {:enabled true}
       :execution :parallel      ;; :parallel | :sequential
       :allow-critical? false
       :timeout-ms 30000
       :max-context-chars 12000
       :filter {:provider nil :model nil}
       :judge {:provider nil :model nil}
       :agents {:melchior {:provider nil :model nil}
                :balthasar {:provider nil :model nil}
                :casper {:provider nil :model nil}}}
```

Semantics:

- nil provider/model => fallback to current active LLM provider/model.
- fallback applies independently for Filter, Judge, MELCHIOR, BALTHASAR, and CASPER.
- configured provider with nil model => provider default configured model.
- nil provider with configured model => current active provider with that model override.
- `:assistive`: evaluate and log, no auto decision.
- `:auto-approve`: Magi can decide.
- `:fallback :human`: keep pending approval if Magi unavailable/unsupported.
- `:fallback :deny`: deny on unavailable/unsupported.
- `:tool-categories #{:all}`: Magi may evaluate all approval-sensitive tool categories by default.
- `:tool-categories` is scope, not a bypass; each request still goes through Filter, Triumvirate, Judge, and safety policy.
- specific values later can narrow scope, e.g. `#{:shell :fs :memory :http :system}`.
- `:tool :enabled`: expose MAGI as a callable tool for explicit verification.
- `:execution :parallel`: triumvirate agents run concurrently by default for lower latency.
- `:execution :sequential`: debug/diagnostic mode only; easier trace reading, not lower token/log cost.

Env later:

- `AGENT_MAGI_ENABLED`
- `AGENT_MAGI_MODE`
- `AGENT_MAGI_FALLBACK`
- `AGENT_MAGI_AGENT_<NAME>_PROVIDER`
- `AGENT_MAGI_AGENT_<NAME>_MODEL`
- `AGENT_MAGI_FILTER_PROVIDER`
- `AGENT_MAGI_FILTER_MODEL`
- `AGENT_MAGI_JUDGE_PROVIDER`
- `AGENT_MAGI_JUDGE_MODEL`

## Code Plan

### Phase 1: Core service

Add namespace:

- `src/agent/magi/core.clj`

Functions:

- `create-service`
- `enabled?`
- `classify`
- `ask-agent`
- `judge`
- `decide`
- `approval-question`

Use `agent.llm.core/invoke` with `:structured-output`.

Run flow as direct LLM calls:

- Filter runs first.
- MELCHIOR/BALTHASAR/CASPER receive the same normalized question.
- Triumvirate runs in parallel by default.
- Timeout is required.
- Use simple `future` + `deref` timeout per call; do not use `pmap` for the final implementation.
- Do not use `ExecutorCompletionService` for Magi unless later cancellation/fair polling requirements appear; three fixed LLM calls do not need the runtime tool-batch executor shape.
- Timed-out agent response becomes `{:response :error :comment "timeout"}`.
- Judge runs last and receives only triumvirate enum outputs.
- no full agent executor, step loop, context manager, memory, tools, or planning.
- preserve stable result order in logs: melchior, balthasar, casper.
- current code has parallel tool batch execution in `agent.runtime.tools`, but no generic parallel LLM orchestration API to reuse directly.

Provider creation:

- add helper in `agent.llm.service`: create provider from active config + provider/model override.
- build Filter/Judge/agent providers at system startup.
- if no Magi-specific provider/model is configured for a participant, use the same provider/model as normal chat.

Confidence: 0.85

### Phase 2: Approval integration

Touch:

- `src/agent/tools/approvals.clj`
- `src/agent/api/handlers/tool_approvals.clj`
- `src/agent/tools/service.clj`
- `src/agent/system/components.clj`

Flow:

1. existing caller creates approval request.
2. if Magi enabled + scope/tool category applies, call `magi/decide`.
3. `:yes` => mark `approved`, actor `magi`, reason includes `magi: yes`.
4. `:no` => mark `denied`, actor `magi`, reason includes `magi: no`.
5. `:conditional` => mark `denied`, actor `magi`, reason includes `magi: conditional` + concrete retry guidance for the LLM.
6. `:info`, `:error`, unsupported => fallback.

Important: Magi must not execute tools. It only decides approval rows.

Confidence: 0.82

### Phase 2b: MAGI tool

Add read-only tool:

- name: `:magi`
- category: `:system`
- operation: `:read`
- required permission: `:magi-evaluate`
- approval-sensitive: false
- activates-tools: false

Input:

```edn
{:question "Should this result be trusted?"
 :kind :yes-no              ;; optional; :yes-no | :info
 :context "optional compact context"
 :expected-response :permit ;; optional; :permit | :classify | :opine
 :domain :policy}           ;; optional
```

Output:

```edn
{:decision :yes
 :reason "short"
 :filter {...}
 :agents {:melchior {:response :yes :comment "..."}
          :balthasar {:response :yes :comment "..."}
          :casper {:response :yes :comment "..."}}
 :mode :tool}
```

Rules:

- tool call never approves/denies existing `tool_approvals`.
- tool call has no side effects except telemetry/event logging.
- useful for double-checking answers, plans, tool results, and policy questions.
- runtime can use it like any other read-only tool when `:magi-evaluate` permission is present.

Confidence: 0.9

### Phase 3: Memory promotion integration

Current memory write paths:

- `scratchpad_replace` is not approval-sensitive.
- vault candidate extraction creates candidates only.
- vault status changes use memory API/functions.

Plan:

- do not gate scratchpad in first implementation.
- gate `candidate -> approved` for global/project vault notes when a promotion API/tool exists.
- if no explicit promotion API exists yet, leave as future integration.

Confidence: 0.75

Weakness: need exact future promotion surface; current code has `update-vault-note-iris!`, but not all callers are visible as approval flow.

### Phase 4: Persistence/audit

No new table first.

Store Magi result in existing event log:

- `tool.approval.magi_evaluated`
- entity: `tool_approval`
- payload: filter result, agent enum responses, judge decision, model/provider names, latency.

Optional later table:

- `magi_decisions`

Confidence: 0.88

### Phase 5: UI/API

Minimal:

- serialize approval actor/reason already enough.
- approval list shows Magi reason through existing reason field.

Later:

- `/v1/magi/evaluate` dry-run endpoint.
- operator board Magi details expander.

Confidence: 0.9

### Phase 6: Tests

Unit:

- Filter schema parse.
- malformed agent output => `:error`.
- judge mapping:
  - any error => error
  - non yes/no => info
  - any no => no
  - any conditional, no no => conditional
  - all yes => yes

Integration:

- Magi disabled => current approval behavior unchanged.
- assistive mode => approval remains pending + event logged.
- auto-approve yes => approval row approved.
- auto-approve no => approval row denied.
- auto-approve conditional => approval row denied with retryable reason.
- Magi error + fallback human => pending.
- Magi error + fallback deny => denied.

Command:

```bash
mkdir -p target/test-iris-config target/test-iris-data
env IRIS_CONFIG_DIR=target/test-iris-config IRIS_DATA_DIR=target/test-iris-data clojure -M:test -e "(require 'agent.tools.approvals-test :reload 'agent.magi.core-test :reload 'agent.api-test :reload) (clojure.test/run-tests 'agent.tools.approvals-test 'agent.magi.core-test 'agent.api-test) (shutdown-agents)"
```

## Safety Policy

Auto-approval must be conservative:

- no destructive shell by default.
- no secret exfiltration.
- no network/private access escalation.
- no filesystem delete unless explicitly allowlisted later.
- no critical risk without `:allow-critical? true`.
- no ambiguous context => `conditional` or human fallback.

## Implementation Order

1. Add bundled prompts.
2. Add `agent.magi.core` pure schema + judge logic.
3. Add provider override helper.
4. Wire Magi service into system.
5. Add `:magi` read-only tool.
6. Wire tool approval auto decision.
7. Add events.
8. Add tests.
9. Run focused tests.

## Acceptance Criteria

- Japanese labels exist only in this document/design copy.
- Runtime/code/API/DB/events/reasons use ASCII decision keywords only.
- With Magi disabled, approval behavior is unchanged.
- With `:mode :assistive`, Magi logs evaluation but does not decide approval rows.
- With `:mode :auto-approve`, only Judge `:yes` approves.
- `:conditional` denies with retryable reason.
- Judge receives only triumvirate outputs and performs deterministic aggregation.
- Unconfigured Magi participant uses current active provider/model.
- Magi tool is read-only and cannot approve/deny existing approval rows.

## Open Questions

None.
