(ns agent.api.event-compat
  "Compatibility normalization for historical persisted event rows."
  (:require
   [clojure.string :as str]))

(def ^:private legacy-event-type->canonical
  {"chat.started" "agent-start"
   "chat.memory.recalled" "message-update"
   "chat.delta" "message-update"
   "chat.planner.step" "turn-end"
   "chat.tool.approval_required" "tool-execution-update"
   "chat.fallback_completion" "message-start"
   "chat.state.changed" "session-state-changed"
   "chat.queued" "turn-queued"
   "chat.completed" "agent-end"
   "chat.cancelled" "agent-end"
   "chat.failed" "agent-end"
   "chat.error" "agent-end"
   "message.appended" "message-end"
   "completion.completed" "message-end"
   "tool.execution.requested" "tool-execution-start"
   "tool.execution.blocked" "tool-execution-end"
   "tool.execution.succeeded" "tool-execution-end"
   "tool.execution.failed" "tool-execution-end"})

(defn- event-type-string [event-type]
  (cond
    (keyword? event-type) (name event-type)
    (nil? event-type) nil
    :else (str event-type)))

(defn canonical-event-type [event-type]
  (let [event-type* (event-type-string event-type)]
    (or (get legacy-event-type->canonical event-type*)
        (some-> event-type*
                (str/replace #"_" "-")))))

(defn canonicalize-event [event]
  (if-let [event-type (canonical-event-type (:event-type event))]
    (assoc event :event-type event-type)
    event))
