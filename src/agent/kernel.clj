(ns agent.kernel
  "Pure tool-loop directive contract."
  (:require
   [agent.kernel.schema :as schema]))

(def directive-types schema/directive-types)

(defn directive
  [type payload]
  (when-not (contains? directive-types type)
    (throw (ex-info "Unknown directive type"
                    {:type :validation-failed
                     :directive-type type})))
  (schema/validate-directive! {:type type
                               :payload payload}))

(defn step-result
  [{:keys [state directives receipts]
    :or {state {}
         directives []
         receipts []}}]
  (when-not (every? #(contains? directive-types (:type %)) directives)
    (throw (ex-info "Invalid directives"
                    {:type :validation-failed
                     :directives directives})))
  (schema/validate-step! {:state state
                          :directives (vec directives)
                          :receipts (vec receipts)}))
