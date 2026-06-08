(ns agent.streaming.metrics
  "Neutral SSE metrics store.")

(def initial-metrics
  {:opened 0
   :closed 0
   :completed 0
   :errors 0
   :send-errors 0
   :dropped-events 0
   :unsubscribed 0
   :cleanup-errors 0})

(defn create-store []
  (atom initial-metrics))

(defn snapshot [store]
  (or (some-> store deref) initial-metrics))

(defn record! [store k]
  (when store
    (swap! store update k (fnil inc 0))))

(defn add-count! [store k n]
  (when (pos? (long (or n 0)))
    (when store
      (swap! store update k (fnil + 0) n))))
