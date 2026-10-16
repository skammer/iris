(ns agent.api.ui-state
  "UI stream ownership belongs to a system instance, not the JVM."
  (:require [agent.api.streaming :as streaming]))

(defn create-state []
  {:session-streams (atom {}) :chat-posts (atom {})})

(defn close! [state]
  (when state
    (let [streams (vals (first (swap-vals! (:session-streams state) (constantly {}))))]
      (reset! (:chat-posts state) {})
      (doseq [ctx streams] (streaming/close! ctx)))))
