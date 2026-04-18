(ns agent.kernel
  "Pure agent/orchestrator directive contract."
  (:require
   [clojure.set :as set]))

(def ^:private directive-types
  #{:spawn-worker :send-message :await :complete :tool-call :state-patch})

(defn directive
  [type payload]
  (when-not (contains? directive-types type)
    (throw (ex-info "Unknown directive type"
                    {:type :validation-failed
                     :directive-type type})))
  {:type type
   :payload payload})

(defn step-result
  [{:keys [state directives receipts]
    :or {state {}
         directives []
         receipts []}}]
  (when-not (every? #(contains? directive-types (:type %)) directives)
    (throw (ex-info "Invalid directives"
                    {:type :validation-failed
                     :directives directives})))
  {:state state
   :directives (vec directives)
   :receipts (vec receipts)})

(defn orchestrator-spawn-worker-step
  [{:keys [task worker-name worker-role capability-bundle memory-scopes budgets system-prompt]
    :or {worker-name "Task Worker"
         worker-role "worker"
         capability-bundle {}
         memory-scopes []
         budgets {}}}]
  (step-result
   {:state {:phase :delegated
            :task task}
    :directives [(directive :spawn-worker
                            {:task task
                             :name worker-name
                             :role worker-role
                             :capability-bundle capability-bundle
                             :memory-scopes memory-scopes
                             :budgets budgets
                             :system-prompt system-prompt})
                 (directive :await
                            {:reason "worker_result"})]}))

(defn normalize-tool-access [tool-access]
  (cond
    (nil? tool-access) #{}
    (set? tool-access) tool-access
    (vector? tool-access) (set tool-access)
    :else (throw (ex-info "tool-access must be a vector or set"
                          {:type :validation-failed
                           :field :tool-access}))))

(defn allows-tool?
  [tool-access tool-name]
  (let [allowed (normalize-tool-access tool-access)]
    (or (contains? allowed :*)
        (contains? allowed tool-name)
        (contains? allowed (keyword tool-name)))))
