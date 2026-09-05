(ns agent.ui-performance-test
  (:require [agent.api.handlers.ui :as handler]
            [agent.api.middleware :as middleware]
            [agent.api.streaming :as streaming]
            [agent.chat :as chat]
            [agent.persistence.sqlite :as sqlite]
            [agent.persistence.sqlite.common :as db]
            [agent.persistence.sqlite.sessions :as sessions]
            [clojure.java.io :as io]
            [agent.ui :as ui]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest gzip-negotiates-quality-and-varies-both-representations
  (let [body (apply str (repeat 600 "данные"))
        response {:status 200 :headers {"Content-Type" "text/html"} :body body}
        wrapped (middleware/wrap-gzip-response (constantly response))]
    (doseq [[encoding compressed?] [["gzip;q=0.5" true] ["GZIP; q=1" true]
                                    ["gzip;q=0" false] ["gzip; q=0.000" false]
                                    ["*;q=1, gzip;q=0" false] ["*;q=0.5" true]
                                    ["xgzip" false] ["gzip;q=invalid" false]
                                    [nil false]]]
      (let [result (wrapped {:headers {"accept-encoding" encoding}})]
        (is (= compressed? (= "gzip" (get-in result [:headers "Content-Encoding"]))) encoding)
        (is (= "Accept-Encoding" (get-in result [:headers "Vary"])) encoding)))
    (doseq [headers [{"Content-Type" "text/event-stream"}
                     {"Content-Type" "text/html" "Content-Encoding" "br"}
                     {"Content-Type" "text/html" "Cache-Control" "no-transform"}
                     {"Content-Type" "text/html" "Content-Range" "bytes 0-599/900"}]]
      (is (= body (:body ((middleware/wrap-gzip-response (constantly (assoc response :headers headers)))
                          {:headers {"accept-encoding" "gzip"}})))))))

(deftest streaming-fragment-never-loads-persisted-history
  (with-redefs [chat/streaming-state (constantly {:content "Live **answer**"})
                sqlite/list-recent-messages (fn [& _] (throw (ex-info "History read during token" {})))
                sqlite/session-thread-stats (fn [& _] (throw (ex-info "Stats read during token" {})))]
    (let [html (ui/session-streaming-fragment {} "session")]
      (is (str/includes? html "id=\"streaming-message\""))
      (is (str/includes? html "<strong>answer</strong>"))
      (is (not (str/includes? html "session-messages-panel"))))))

(deftest token-bursts-coalesce-and-final-message-flushes-immediately
  (let [channel (async/chan 256)
        open? (atom true)
        patches (atom [])
        sent (promise)
        event (fn [type payload]
                {:payload {:event-type type :entity-type "session"
                           :entity-id "session" :payload payload}})]
    (with-redefs [sqlite/session-exists? (constantly true)
                  streaming/managed-response (fn [_ _ f] (f :ctx))
                  streaming/subscribe! (fn [& _] {:channel channel})
                  streaming/open? (fn [_] @open?)
                  streaming/send-datastar-patch! (fn [_ html]
                                                  (swap! patches conj html)
                                                  (when (= html "streaming") (deliver sent true))
                                                  true)
                  ui/session-streaming-fragment (fn [& _] "streaming")
                  ui/session-messages-fragment (fn [& _] "final")]
      (let [worker (future (handler/session-live-response {} {:parameters {:query {:session_id "session"}}}))]
        (try
          (dotimes [_ 100] (async/>!! channel (event "message-update" {:delta "x"})))
          (is (= true (deref sent 3000 :timeout)))
          (is (= ["streaming"] @patches))
          (async/>!! channel (event "message-end" {:final? true}))
          (async/close! channel)
          (is (not= :timeout (deref worker 3000 :timeout)))
          (is (= ["streaming" "final"] @patches))
          (finally (reset! open? false) (async/close! channel) (future-cancel worker)))))))

(deftest tool-details-are-scoped-bounded-and-preserve-rich-results
  (let [path (.getAbsolutePath (java.io.File/createTempFile "iris-detail-" ".db"))
        store (sqlite/create-store {:path path})]
    (try
      (let [sid (:id (sqlite/create-session! store "tools"))
            other (:id (sqlite/create-session! store "other"))
            call {:id "reused" :function {:name "shell" :arguments "{}"}}
            _old (sqlite/append-message! store sid "tool" "old result" {:tool-call-id "reused"})
            request (sqlite/append-message! store sid "assistant" "" {:tool-calls [call]})
            _foreign (sqlite/append-message! store other "tool" "foreign result" {:tool-call-id "reused"})
            blocks [{:type :text :text "preface"}
                    {:type :tool-result :tool-call-id "reused" :name "shell"
                     :status "done" :content "correct rich result"}]
            result (sqlite/append-message! store sid "tool" "" {:content-blocks blocks})
            _later (sqlite/append-message! store sid "tool" "later result" {:tool-call-id "reused"})
            messages (sqlite/tool-detail-messages store sid (:id request) "reused")]
        (is (= [(:id request) (:id result)] (mapv :id messages)))
        (is (= "reused" (:tool-call-id (second messages))))
        (is (= (mapv #(update % :type name) blocks) (:content-blocks (second messages))))
        (is (= [] (sqlite/tool-detail-messages store other (:id request) "reused")))
        (is (= [] (sqlite/tool-detail-messages store sid "invalid" "reused")))
        (is (= [(:id request)] (mapv :id (sqlite/tool-detail-messages store sid (:id request) "pending"))))
        (is (= [(:id result)] (mapv :id (sqlite/tool-detail-messages store sid (:id result) "reused"))))
        (with-redefs [sqlite/list-messages (fn [& _] (throw (ex-info "Unbounded read" {})))]
          (let [html (:body (handler/chat-tool-detail {:store store}
                               {:parameters {:query {:session_id sid
                                                    :message_id (str (:id request))
                                                    :tool_call_id "reused"}}}))]
            (is (str/includes? html "correct rich result"))
            (is (not (str/includes? html "foreign result")))
            (is (not (str/includes? html "old result")))))
        ;; Reproduce pre-migration data, then run the exact shipped backfill.
        (db/with-connection store
          (fn [conn]
            (db/execute! conn ["update messages set tool_call_id = null where id = ?" (:id result)])
            (doseq [sql (str/split (slurp (io/resource "agent/persistence/sqlite/migrations/014-tool-result-lookup.up.sql")) #";")
                    :when (not (str/blank? sql))]
              (db/execute-ddl! conn sql))))
        (is (= [(:id request) (:id result)]
               (mapv :id (sqlite/tool-detail-messages store sid (:id request) "reused"))))
        (let [plan (db/with-connection store
                     #(db/select-many %
                        (update (sessions/get-tool-result-sqlvec {:session_id sid :tool_call_id "reused" :after_id (:id request)})
                                0 (fn [sql] (str "explain query plan " sql))) identity))]
          (is (some #(str/includes? (:detail %) "idx_messages_tool_result") plan)))
        (let [entry (sqlite/append-entry! store sid :message
                      {:role "tool" :content-blocks [{:type :tool-result :tool-call-id "entry-call" :content "entry result"}]})
              message-id (get-in entry [:payload :message-id])]
          (is (= "entry-call" (:tool-call-id (first (sqlite/tool-detail-messages store sid message-id "entry-call")))))))
      (finally (sqlite/close-store! store) (io/delete-file path true)))))

(deftest session-pages-and-project-suggestions-are-bounded
  (let [path (.getAbsolutePath (java.io.File/createTempFile "iris-pages-" ".db"))
        store (sqlite/create-store {:path path})]
    (try
      (doseq [n (range 123)]
        (sqlite/create-session! store (str "long-session-" n)
                                {:metadata {:project-id (format "project-%03d" n)}}))
      (sqlite/create-session! store "cron" {:kind :cron})
      (let [all (sqlite/list-sessions store)
            first-page (sqlite/list-sessions store {:limit 50})
            second-page (sqlite/list-sessions store {:limit 50 :offset 50})
            last-page (sqlite/list-sessions store {:limit 50 :offset 100})
            selected (:id (first first-page))
            html (ui/sessions-fragment {:store store} selected {:offset "50"})
            doc (org.jsoup.Jsoup/parse html)]
        (is (= all (vec (concat first-page second-page last-page))))
        (is (= [50 50 23] (mapv count [first-page second-page last-page])))
        (is (= {:chat 123 :cron 1} (sqlite/session-kind-counts store)))
        (is (= 51 (.size (.select doc ".session-link"))) "50 rows plus pinned selection")
        (is (= 1 (.size (.select doc ".session-link[aria-current=page]"))))
        (is (str/includes? html "51–100 / 123"))
        (is (str/includes? html "&amp;offset=50"))
        (is (str/includes? html "Chats 123"))
        (is (= 30 (count (sqlite/session-project-ids store ""))))
        (is (= ["project-122"] (sqlite/session-project-ids store "project-122")))
        (is (= [] (sqlite/session-project-ids store "project-%")))
        (is (str/includes? (ui/session-projects-fragment {:store store} "project-122") "value=\"project-122\""))
        (is (str/includes? (ui/sessions-fragment {:store store} selected {:offset "99999"}) "101–123 / 123"))
        (is (str/includes? (ui/sessions-fragment {:store store} selected {:offset "invalid"}) "1–50 / 123")))
      (finally (sqlite/close-store! store) (io/delete-file path true)))))

(deftest title-update-does-not-replace-composer-or-paged-sidebar
  (with-redefs [sqlite/get-session (constantly {:id "session" :title "Updated title"})]
    (let [html (ui/session-title-fragments {} "session")]
      (is (str/includes? html "id=\"session-title-session\""))
      (is (str/includes? html "id=\"chat-session-title\""))
      (is (not (str/includes? html "sessions-panel")))
      (is (not (str/includes? html "session-detail-panel"))))))
