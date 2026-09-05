(require '[agent.api-test :as fixture] '[agent.persistence.sqlite :as sqlite]
         '[agent.llm.core :as llm] '[clojure.java.io :as io] '[nrepl.server :as nrepl])

(def stream-chunks (atom 120))

(defrecord UiStreamReviewProvider []
  llm/ILLMProvider
  (complete [_ _ _] "complete")
  (stream [_ _ _] (throw (ex-info "Use invoke" {})))
  (embed [_ _ _] [])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-streaming true})
  (estimate-cost [_ _ _] {:tokens 0 :cost-usd 0})
  llm/ILLMProviderInvoke
  (invoke [_ {:keys [on-content-delta] :as request}]
    (let [parts (mapv #(str "word" % " ") (range @stream-chunks))]
      (when on-content-delta
        (doseq [part parts] (on-content-delta part) (Thread/sleep 15)))
      (llm/normalize-llm-response (apply str parts) request)))
  (generate [provider messages opts] (llm/invoke provider (assoc opts :messages messages))))

(.mkdirs (io/file "target/ui-review"))
(def post-review (fixture/started-test-system "target/ui-review/post.db" 17431 identity (->UiStreamReviewProvider)))
(def post-store (get-in post-review [:system :store]))
(def post-sid (:id (or (first (sqlite/list-sessions post-store {:limit 1}))
                       (sqlite/create-session! post-store "Post stream review"))))
(when (zero? (sqlite/count-messages post-store post-sid))
  (dotimes [_ 60]
    (sqlite/append-message! post-store post-sid "user"
      (str "HISTORY_SENTINEL " (apply str (repeat 200 "history "))))))
(def post-repl (nrepl/start-server :bind "127.0.0.1" :port 17432))
(println "Stream review http://127.0.0.1:17431 — nREPL 17432")
@(promise)
