# Agent Loop Architecture Summary

## Decision Map

### 1. Loop Owner
- Iris: pure loop; wrapper owns persistence/transport.
- Pi: session object owns event queue/persistence/extension middleware.
- Shelley: loop owns queue/history repair; embedding owns persistence.
- ZeroStack: Rig owns loop; TUI owns session/events.
- ZeroClaw: mixed functional loop plus OO agent.
- Forge: workflow runner owns step/terminal contract.
- SmallCode: compact session loop tuned for small models.
- Coddy: ReAct loop bound to ACP/session bundle.
- Little Coder: extensions around Pi, not forked loop.

Best decision: keep core loop pure/evented, put transport/persistence outside. Iris does this best.

### 2. Halting
- Hard caps: max steps/tool iterations/turns in Iris, Coddy, Forge, SmallCode, ZeroClaw, ZeroStack.
- Graceful finalization: ZeroClaw asks no-tool summary on max iterations; SmallCode appends tool-limit warning; Iris emits stopped/fallback paths.
- Cancellation: Iris tokens, Shelley contexts, ZeroClaw CancellationToken, Forge cancel_event, Gateway abort endpoint.
- Loop detection: Iris doom-loop/nudges, ZeroClaw detector, ZeroStack permission doom-loop.

Best decision: combine max cap + cancellation + loop detector + graceful final summary.

### 3. Restore
- Strong restore: Iris SQLite branch tree; Pi JSONL tree; Coddy session bundle; ZeroClaw SQLite/JSONL.
- Medium restore: ZeroStack JSON session with compactions.
- Weak restore: Forge core runner, Shelley loop core.
- SmallCode simple JSON snapshots.

Best decision: append-only or tree storage plus derived LLM context rebuild; never treat rendered UI as source of truth.

### 4. Tools
- Native/text dual parsing: ZeroClaw, SmallCode.
- Parallel batches: Iris, ZeroClaw.
- Sequential simple: Coddy, Shelley, Forge, SmallCode.
- Approval gating: Iris, Coddy, SmallCode, ZeroStack, ZeroClaw.
- Deferred/dynamic tools: ZeroClaw MCP filtering/tool_search; Coddy MCP naming.

Best decision: ordered result model even when executing in parallel.

### 5. Subagents
- Iris: no separate durable child runtime; current model favors sessions/tools/events.
- ZeroClaw: `llm_task`, `swarm`, `sessions_*`.
- Shelley: DB-backed or CLI subagent tools.
- Little Coder/Pi: multi-session/extensions, less durable child protocol.
- Others: limited/no native subagent.

Best decision: model subagents as sessions/tasks with explicit lifecycle, not anonymous tool calls.

### 6. Long-Running Tasks
- Iris: no dedicated long-task service after simplification.
- Forge: slot worker with priority/preemption.
- ZeroClaw: daemon, cron/routines, session state, cancellation tokens.
- ZeroStack: optional iterative `/loop` over plan file.
- Shelley: context/tool timeouts.

Best decision: durable task state if task can outlive one chat turn.

### 7. User Handoff / Questions
- Iris: approval-required and queued turns.
- Coddy: ACP permission flow.
- Little Coder: extension UI requests.
- ZeroStack: TUI permission prompt.
- ZeroClaw: channel approval, ask_user, request_choice, poll, escalate.
- SmallCode: clarification loop and checkpoints.

Best decision: treat user questions as first-class loop states/events, not assistant prose only.

### 8. Events / Presentation
- Iris: typed SQLite events plus Datastar UI.
- Coddy: UI log, tool call store, SSE, replay.
- Pi: ordered session events plus extension events.
- ZeroStack: compact `AgentEvent` enum rendered by TUI.
- ZeroClaw: observer events, SSE buffer, WebSocket TurnEvents, runtime trace JSONL.
- Forge/SmallCode: callback/event bus.

Best decision: typed event stream for machines; separate compact renderer for humans.

### 9. Reconciliation
- Tool repair: Iris, Shelley, ZeroClaw.
- Client/server final sync: Coddy, ZeroClaw `chunk_reset` + done.
- Context compaction repair: Iris, Pi, ZeroClaw, ZeroStack.
- Event ordering: Pi serializes event processing.
- Queue serialization: Iris per-session queue; ZeroClaw session actor queue.

Best decision: reconcile at boundaries:
- before provider call: repair history
- after stream: authoritative final message
- before restore: rebuild context from durable log/tree
- before UI paint: replay typed events

## Reference Architecture

1. Core loop:
   - input context
   - provider call
   - parse tool calls
   - execute tools
   - append ordered results
   - stop/recur
2. Loop guards:
   - cancellation token
   - max iterations
   - context budget/compaction
   - duplicate/doom-loop detector
   - approval/user-question suspension
3. Persistence:
   - append-only event/message log
   - session tree/branch if edits/retries matter
   - compaction entries as durable derived state
4. Presentation:
   - stream tokens/tool status live
   - persist partial drafts for crashes
   - send authoritative final state
   - replay from durable state after reconnect
5. Long tasks:
   - create run
   - heartbeat
   - checkpoint
   - command queue/cancel
   - replay history

## Online Best Practices

- Prefer simple, composable workflows until autonomy is needed. Anthropic separates fixed workflows from agents; agent loops fit open-ended tasks where steps cannot be known up front, but need sandboxing, guardrails, max iterations, and human checkpoints. Source: https://www.anthropic.com/engineering/building-effective-agents
- Treat the loop as a run with explicit exit conditions: final-output tool, no tool calls, error, or max turns. Source: https://openai.com/business/guides-and-resources/a-practical-guide-to-building-ai-agents/
- Layer guardrails at the right boundary: input before first agent, output after final agent, tool guardrails around each tool call. Blocking guardrails are needed before side effects. Source: https://openai.github.io/openai-agents-python/guardrails/
- Trace every run: LLM calls, tool calls, handoffs, guardrails, and custom events. Redact sensitive span data if traces leave the process. Source: https://openai.github.io/openai-agents-python/tracing/
- Make long-running loops durable: checkpoint step state, use thread/run ids, isolate side effects in idempotent tasks, replay from persisted outcomes instead of redoing work. Source: https://docs.langchain.com/oss/python/langgraph/durable-execution
- Build evals early: scoped task-specific evals, production-like cases, logs mined into regression cases, automated scoring where possible, human calibration for judge drift. Source: https://developers.openai.com/api/docs/guides/evaluation-best-practices

## Code Pattern Index

```clojure
;; src/agent/runtime/tools.clj
(defn- batches [preflights opts]
  (cond
    (<= (count preflights) 1)
    (sequential-batches preflights)

    (= :sequential (normalize-tool-name (:mode opts)))
    (sequential-batches preflights)

    (batch-forces-sequential? preflights)
    (sequential-batches preflights)

    :else
    (loop [xs preflights
           acc []]
      (if (empty? xs)
        acc
        (if (parallel-safe-preflight? (first xs) opts)
          (let [[safe* rest*] (split-with #(parallel-safe-preflight? % opts) xs)
                safe* (vec safe*)]
            (recur rest*
                   (conj acc [(if (> (count safe*) 1) :parallel :sequential) safe*])))
          (recur (rest xs) (conj acc [:sequential [(first xs)]])))))))
```

Pattern: execute independent safe tools in parallel, but force sequential mode for approval-sensitive or tool-activating calls. This is the right compromise for Iris: concurrency without breaking approval ordering or dynamic tool activation.

```clojure
;; /private/tmp/ayatori/src/ayatori/graph/executor.clj
(defn inject
  "Injects input into an agent's flow. Returns promise channel for result."
  ([flow-state entry-key input]
   (inject flow-state entry-key input false))
  ([flow-state entry-key input streaming?]
   (let [corr-id (str (random-uuid))
         result-ch (if streaming?
                     (async/chan 100)
                     (async/promise-chan))]
     (swap! (:result-registry flow-state) assoc corr-id {:ch result-ch})
     (flow/inject (:flow flow-state)
                  [entry-key :in]
                  [{:data input
                    :corr-id corr-id}])
     result-ch)))
```

Pattern: request/reply over a graph runtime needs correlation IDs plus registry-owned result channels. If Iris ever uses graph execution for subflows, keep this idea and still persist run/session events outside the graph.

## core.async.flow / Ayatori Fit

- `core.async.flow` is alpha and explicitly separates ordinary step functions from topology, execution, lifecycle, error handling, and monitoring. Good fit for known process graphs, observable pipelines, fan-out/fan-in services, and long-lived dataflow subsystems. Source: https://clojure.github.io/core.async/clojure.core.async.flow.html
- Ayatori uses `core.async.flow` seriously: graph nodes compile to `flow/process`, topology compiles to `flow/create-flow`, `start` returns report/error channels, `resume` starts processing, `inject` sends correlated work into entry ports, and an output collector resolves caller channels.
- Ayatori is not production-ready by its own README. Treat it as a strong exercise/prototype for graph orchestration, not as a drop-in Iris loop manager.
- Best Iris use: optional backend for bounded, graph-shaped subflows. Poor fit: replacing the central chat loop, because Iris needs branch restore, provider history repair, approval suspension, UI event persistence, child-run lifecycle, and exact LLM protocol reconciliation.

## Iris-Relevant Takeaways

- Keep Iris pure loop plus wrapper split.
- Borrow ZeroClaw session actor queue and partial-response persistence ideas.
- Borrow Pi steer/followUp queues as explicit message delivery modes.
- Borrow Shelley missing-tool-result repair simplicity.
- Borrow Coddy replay artifacts for richer tool-call restore.
- Borrow Forge terminal-tool contract for bounded workflows.
- Borrow SmallCode two-stage tool routing for small-model reliability.
- Borrow Ayatori's graph-as-data/correlation-id approach only for explicit subgraphs, not for Iris' main loop.

Confidence: 0.9

Caveats: mixed static/local/online review; Ayatori reviewed at `13c35d9c7c405f4df493e9782f4f185a48f8b2f5`; some repos delegate core behavior to external packages.
