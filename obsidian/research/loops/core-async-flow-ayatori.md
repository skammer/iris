# core.async.flow / Ayatori

## Shape
- `core.async.flow` builds a directed process graph from data: `:procs` plus `:conns`.
- Public lifecycle: `create-flow`, `start`, `resume`, `pause`, `stop`, `ping`, `inject`.
- `start` returns `:report-chan` and `:error-chan`; procs start paused until `resume`.
- Docs mark flow namespace alpha. Treat API as useful but not stable enough to hide deep inside core chat semantics.
- Ayatori is a graph-based agent orchestration POC. README says "Proof of concept, under active development. Not production-ready."

Sources:
- https://clojure.github.io/core.async/clojure.core.async.flow.html
- https://github.com/clojure/core.async/blob/master/docs/clojure.core.async.flow.html
- https://github.com/serefayar/ayatori

## Ayatori Execution Model
- `make-agent` validates topology and binds node impls.
- `make-system` wires named agent deps to other caps.
- `start!` compiles every agent graph into flow processes.
- `run` injects cap input and returns a core.async channel.
- Correlation IDs emulate request/reply over flow ports.
- Output collector resolves correlation ID to caller channel.

```clojure
;; /private/tmp/ayatori/src/ayatori/graph/executor.clj
(defn- build-flow-procs
  "Builds flow/process objects from agent. Reuses conns from topology."
  [agent dep-resolvers result-registry]
  (let [{:keys [nodes edges topology]} agent
        deps (:deps topology)
        router-nodes (into #{} (keep (fn [[k v]] (when (has-conditional-routes? v) k))) edges)
        fan-out-nodes (into {} (filter (comp fan-out-node? val)) nodes)
        llm-nodes (into {} (filter (comp llm-node? val)) nodes)

        procs (reduce-kv
               (fn [acc k node]
                 (cond
                   (contains? fan-out-nodes k)
                   (assoc acc k {:proc (flow/process (fan-out-node->step k (get fan-out-nodes k)))})

                   (contains? llm-nodes k)
                   (assoc acc k {:proc (flow/process (llm-node->step k node (get edges k)))})

                   (not (fn-or-var? node))
                   acc

                   (contains? router-nodes k)
                   (assoc acc k {:proc (flow/process (router-node->step k node (get edges k)))})

                   :else
                   (assoc acc k {:proc (flow/process (pure-node->step node))})))
               {}
               nodes)

        procs (assoc procs ::output-collector
                     {:proc (flow/process (output-collector-step result-registry
                                                                 (:streaming? topology)))})]

    {:procs procs
     :conns (:conns topology)
     :entry-key (:entry-key topology)
     :deps deps}))
```

Pattern: graph topology is data; node types compile into flow processes. This is clean for known graphs.

```clojure
;; /private/tmp/ayatori/src/ayatori/graph/executor.clj
(defn create-agent-flow
  "Creates and starts a flow for an agent. Returns flow state."
  [agent opts]
  (let [{:keys [topology]} agent
        ref-resolver (:ref-resolver opts)
        wiring (:wiring opts)
        agent-key (:agent opts)
        dep-resolvers (into {}
                            (map (fn [dep-key]
                                   [dep-key (make-dep-resolver ref-resolver wiring agent-key dep-key)]))
                            (:deps topology))
        result-registry (atom {})
        flow-topology (build-flow-procs agent dep-resolvers result-registry)
        flow-graph (flow/create-flow {:procs (:procs flow-topology)
                                      :conns (:conns flow-topology)})
        {:keys [report-chan error-chan]} (flow/start flow-graph)
        error-handlers (atom [])]

    (start-report-handler report-chan)
    (start-error-handler error-chan result-registry error-handlers)
    (flow/resume flow-graph)

    {:flow flow-graph
     :topology flow-topology
     :result-registry result-registry
     :error-handlers error-handlers}))
```

Pattern: flow lifecycle is explicit and monitorable. Report/error channels are natural observability hooks.

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

Pattern: request/reply needs explicit correlation and channel registry because flow is stream-oriented.

```clojure
;; /private/tmp/ayatori/src/ayatori/graph/executor.clj
(defn- output-collector-step
  "Sink that delivers results/tokens/errors to callers via registry.
   Registry passed via closure, delivery is side-effect."
  [result-registry has-streaming?]
  (flow/map->step
   {:describe
    (fn [] {:ins (collector-ins has-streaming?)})

    :init
    (fn [_] {:registry result-registry})

    :transform
    (fn [state in-id msg]
      (let [registry (:registry state)]
        (case in-id
          :result (deliver-result! registry (:corr-id msg) (:data msg))
          :token  (forward-token! registry (:corr-id msg) (:delta msg))
          :done   (forward-result-and-close! registry (:corr-id msg) (:data msg))
          nil))
      [state {}])}))
```

Pattern: terminal collector is a sink with side effects. This is acceptable at graph boundary, not inside pure node logic.

## Fit For Iris
- Good fit: optional graph engine for bounded subflows: routing pipelines, multi-agent fan-out, evaluator pipelines, external service orchestration.
- Maybe fit: flow monitor for live graph visualization, if events are mirrored into Iris' durable event stream.
- Poor fit: primary chat loop manager. Iris loop has provider-history repair, branch restore, approval suspension, queued user turns, runtime child runs, UI persistence, and protocol reconciliation. Those are run/session concerns, not just flow topology concerns.
- Risk: Ayatori's fan-out workaround stores branch state in step state because flow lacks built-in multi-branch join. That is fine for a POC, but for Iris it should be explicit durable run state if tasks can outlive one request.

## Verdict
- `core.async.flow`: good primitive for graph-shaped internal runtimes; do not put it directly at the center of Iris chat.
- Ayatori: good exercise/prototype with useful ideas: graph-as-data, correlation IDs, output collector, lifecycle hooks, error-channel routing.
- Recommendation: steal patterns, not dependency. Revisit only after Iris core loop/guardrails stabilize.

Confidence: 0.84

Caveats: Ayatori reviewed at commit `13c35d9c7c405f4df493e9782f4f185a48f8b2f5`; `core.async.flow` API is alpha; no runtime load test executed.
