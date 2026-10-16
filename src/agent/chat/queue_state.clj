(ns agent.chat.queue-state
  "Pure per-session queue transitions; callers own locking, persistence and execution.")

(defn advance [runtimes session-id request-id stopping? active-fn]
  (let [{:keys [active queue]} (get runtimes session-id)]
    (if (not= request-id (:request-id active))
      {:runtimes runtimes}
      (if (and (not stopping?) (seq queue))
        (let [item (peek queue)]
          {:runtimes (assoc runtimes session-id {:active (active-fn item) :queue (pop queue)})
           :item item})
        {:runtimes (dissoc runtimes session-id) :cleared? true}))))

(defn cancel [runtimes session-id]
  (let [{:keys [active queue]} (get runtimes session-id)]
    {:runtimes (if active
                 (assoc runtimes session-id {:active active :queue clojure.lang.PersistentQueue/EMPTY})
                 (dissoc runtimes session-id))
     :active active :queued (vec queue)}))

(defn full? [runtimes session-id {:keys [max-active-sessions max-queued-per-session max-pending-turns]}]
  (or (and (not (get-in runtimes [session-id :active]))
           (>= (count runtimes) max-active-sessions))
      (and (get-in runtimes [session-id :active])
           (>= (count (get-in runtimes [session-id :queue])) max-queued-per-session))
      (>= (reduce + 0 (map #(+ (if (:active %) 1 0) (count (:queue %))) (vals runtimes)))
          max-pending-turns)))
