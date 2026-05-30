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

(defonce ^:private metrics* (atom initial-metrics))

(defn metrics []
  @metrics*)

(defn reset-metrics! []
  (reset! metrics* initial-metrics))

(defn record! [k]
  (swap! metrics* update k (fnil inc 0)))

(defn add-count! [k n]
  (when (pos? (long (or n 0)))
    (swap! metrics* update k (fnil + 0) n)))
