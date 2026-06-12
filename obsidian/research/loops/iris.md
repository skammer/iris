# Iris Loop

## Shape
- Pure evented loop: `src/agent/runtime/loop.clj`.
- Wrapper/runtime orchestration: `src/agent/chat.clj`.
- Loop state tracks step, planner messages, final messages, trace, usage, doom-loop state, nudge state.
- Per step: emit start events, repair history, pack context, route tools, call planner, apply guardrails, execute tools, decide complete/recur.
- Main loop is dependency-injected: planner, context packer, tool executor, approval fn, fallback fn, observer, event sink, cancellation token.

## Halting
- Stops on `:completed`, `:approval-required`, cancellation, max steps, guardrail/tool termination, planner failure fallback.
- `stop` maps to a cancellation token and produces controlled "Stopped." behavior.
- Doom-loop and token/context warnings nudge before hard failure.

## Restore
- SQLite tree/session store rebuilds context from branch path.
- Latest compaction summary is prepended; entries before compaction cut are skipped.
- `excluded-from-context` messages stay visible/persisted but are omitted from LLM context.
- `run!` queues turns per session; queued user turns are persisted excluded until activated.

## Tools
- `src/agent/runtime/tools.clj` preflights, enforces allowed tools, permissions, sensitive checks, approvals.
- Supports sequential and parallel batches while preserving ordered tool results.
- Tool termination only terminates if all ordered results say terminate.

## User Handoff
- Approval required can suspend loop.
- Queued messages are serialized per session and activated after current turn.
- Web/UI can cancel active sessions and inspect tools/events.

## Events / UI
- `src/agent/persistence/sqlite/events.clj` stores typed events with request/session linkage.
- `chat.clj` subscribes to runtime events; message-end persists messages; stream deltas flush to UI.
- UI renders `/chat/:id`, compact tool rows, events, and details.

## Reconciliation
- History repair before planner call inserts/normalizes missing tool results.
- Context packer protects system/latest user/latest tool loop, drops stale nudges, compacts/truncates older tool results.
- Persistence wrapper reconciles branch context, compaction summaries, excluded messages, queued turns.

## Code Pattern

```clojure
;; src/agent/runtime/tools.clj
(defn- parallel-safe-preflight? [preflight opts]
  (and (not (:preflight-error preflight))
       (not (legacy-sequential? preflight opts))
       (not (approval-sensitive-call? preflight))
       (not (activates-tools-call? preflight))
       (tools/parallel-safe-call? (:description preflight) (:input preflight))))

(defn execute-batch!
  ([registry calls context] (execute-batch! registry calls context {}))
  ([registry calls context opts]
   (let [opts* (update opts :mode #(normalize-tool-name (or % default-mode)))
         _ (throw-if-cancelled! opts*)
         preflights (mapv (fn [[idx call]]
                            (preflight-or-error registry call context opts* idx))
                          (map-indexed vector calls))
         results (mapcat (fn [[mode batch]]
                           (case mode
                             :sequential (execute-sequential! registry batch opts*)
                             :parallel (execute-parallel! registry batch opts*)))
                         (batches preflights opts*))]
     (finalize-results results))))
```

Pattern: preflight decides execution policy before side effects. Approval-sensitive and tool-activating calls stay sequential; safe calls can batch.

## Decision
- Strong boundary: pure loop emits events; outer chat/runtime owns persistence, transport, queues.
- Best reusable ideas: dependency-injected loop, event sink as contract, branch-aware restore, child-run protocol.

Confidence: 0.92

Caveats: static read only; no runtime trace executed.
