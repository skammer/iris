(ns agent.audit-maintenance-test
  (:require [agent.api.handlers.ui :as handler]
            [agent.api.helpers :as helpers]
            [agent.api.streaming :as streaming]
            [agent.api.ui-state :as ui-state]
            [agent.broker.core :as broker]
            [agent.broker.local :as local]
            [agent.logging :as logging]
            [agent.memory.core :as memory]
            [agent.memory.search :as search]
            [agent.persistence.sqlite :as sqlite]
            [agent.persistence.sqlite.common :as db]
            [agent.persistence.sqlite.sessions.entries :as entries]
            [agent.system.events :as events]
            [agent.ui :as ui]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- with-store [f]
  (let [file (java.io.File/createTempFile "iris-maintenance-" ".db")
        store (sqlite/create-store {:path (.getPath file)})]
    (try (f store) (finally (sqlite/close-store! store) (io/delete-file file true)))))

(deftest ancestor-query-is-scoped-and-does-not-decode-other-branches
  (with-store
    (fn [store]
      (let [sid (:id (sqlite/create-session! store "branches"))
            root (entries/append-entry! store sid :message {:role "user" :content "root"})
            selected (entries/append-entry! store sid {:parent-id (:id root) :type :message
                                                       :payload {:role "assistant" :content "chosen"}})
            _ (dotimes [_ 50] (entries/append-entry! store sid {:parent-id (:id root) :type :custom :payload {}}))
            decoded (atom 0) decode entries/row->entry]
        (with-redefs [entries/row->entry (fn [row] (swap! decoded inc) (decode row))]
          (is (= [root selected] (entries/branch-path store sid (:id selected))))
          (is (= 2 @decoded)))
        (is (= [] (entries/branch-path store "other-session" (:id selected))))
        (is (= [] (entries/branch-path store sid "missing")))))))

(deftest ancestor-cycle-fails-instead-of-looping
  (with-store
    (fn [store]
      (let [sid (:id (sqlite/create-session! store "cycle"))
            root (entries/append-entry! store sid :custom {})]
        (db/with-connection store #(db/execute! % ["update session_entries set parent_id=id where id=?" (:id root)]))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cycle" (entries/branch-path store sid (:id root))))))))

(deftest scoped-routing-preserves-one-global-delivery-and-replay
  (with-store
    (fn [store]
      (let [instance (events/create-broker store)
            all (broker/subscribe! instance (broker/all-events-subject) {:buffer-size 10})
            a (broker/subscribe! instance (broker/session-events-subject "a") {:buffer-size 1 :buffer-strategy :sliding})
            b (broker/subscribe! instance (broker/session-events-subject "b") {:buffer-size 10})]
        (try
          (doseq [sid ["a" "b" "b"]]
            (let [event (sqlite/log-event! store {:event-type :message-end :entity-type :session :entity-id sid})]
              (doseq [message (broker/event->messages event)] (broker/publish! instance message))))
          (is (= ["a" "b" "b"] (mapv #(get-in % [:payload :entity-id]) (repeatedly 3 #(async/poll! (:channel all))))))
          (is (nil? (async/poll! (:channel all))))
          (is (= "a" (get-in (async/poll! (:channel a)) [:payload :entity-id])))
          (is (zero? @(:dropped-count a)))
          (let [replay (broker/replay! instance (broker/session-events-subject "b") {:limit 10})]
            (is (= ["b" "b"] (mapv #(get-in % [:payload :entity-id]) replay)))
            (is (= [(second replay)]
                   (broker/replay! instance (broker/session-events-subject "b")
                                   {:after-id (get-in (first replay) [:payload :id]) :limit 10}))))
          (is (= 3 (count (broker/replay! instance (broker/all-events-subject)))))
          (finally (doseq [sub [all a b]] (broker/unsubscribe! instance sub))))))))

(deftest live-ui-resyncs-after-overflow-without-a-new-user-message
  (doseq [history? [false true]]
    (let [instance (local/create-broker)
          sub (broker/subscribe! instance (broker/session-events-subject "session")
                                 {:buffer-size 2 :buffer-strategy :sliding})
          state (ui-state/create-state)
          _ (when history? (reset! (:session-streams state)
                                  {"client" {:history-session (atom "session")}}))
          patches (atom [])]
      ;; Final event is evicted. Both surviving envelopes carry the same loss count.
      (doseq [type ["message-end" "unrelated-1" "unrelated-2"]]
        (broker/publish! instance {:subject (broker/session-events-subject "session")
                                  :payload {:entity-type "session" :entity-id "session" :event-type type}}))
      (async/close! (:channel sub))
      (with-redefs [sqlite/session-exists? (constantly true)
                    streaming/managed-response (fn [_ _ f] (f {}))
                    streaming/subscribe! (fn [& _] sub)
                    streaming/open? (constantly true)
                    streaming/close! (constantly nil)
                    streaming/send-datastar-patch! (fn [_ html] (swap! patches conj html) true)
                    ui/session-title-fragments (fn [& _] "title")
                    ui/session-messages-fragment (fn [& _] "final")
                    ui/session-status-fragment (fn [& _] "idle")]
        (handler/session-live-response {:ui-state state}
                                       {:parameters {:query {:session_id "session" :client_id "client"}}})
        (is (= [(if history? "titleidle" "titlefinalidle")] @patches)))
      (broker/unsubscribe! instance sub))))

(deftest gap-validation-is-shared-by-take-and-alts-consumers
  (let [ch (async/chan 1) envelope {:broker/dropped-count 2 :payload {}}]
    (async/>!! ch envelope)
    (with-redefs [streaming/open? (constantly true)]
      (is (thrown? clojure.lang.ExceptionInfo (streaming/take! :ctx ch))))
    (is (thrown? clojure.lang.ExceptionInfo (streaming/check-message! envelope)))
    (async/close! ch)))

(deftest embedding-failure-is-visible-and-lexical-results-survive
  (let [service (memory/create-memory-service {:embeddings {:enabled? true}} nil)
        logged (atom [])
        lexical [{:chunk-id "chunk" :text "lexical"}]]
    (with-redefs [sqlite/search-vault-chunks (fn [& _] lexical)
                  sqlite/list-vault-chunk-embedding-candidates (fn [& _] (throw (Exception. "secret query and credential")))
                  logging/log! (fn [event payload] (swap! logged conj [event payload]))]
      (is (= ["chunk"] (mapv :chunk-id (search/search-vault service "private query"))))
      (is (= [[:agent.memory/vector-search-degraded {:reason :embedding-search-failed}]] @logged)))
    (reset! logged [])
    (with-redefs [sqlite/search-vault-chunks (fn [& _] [])
                  sqlite/list-vault-chunk-embedding-candidates (fn [& _] [])
                  logging/log! (fn [& args] (swap! logged conj args))]
      (is (= [] (search/search-vault service "private query")))
      (is (empty? @logged)))))

(deftest post-gap-uses-authoritative-stream-without-replacing-history
  (doseq [history? [false true]]
    (let [events (async/chan 2) result (async/chan 1)
          history (atom nil) state (ui-state/create-state) patches (atom [])]
      (reset! (:session-streams state) {"client" {:history-session history}})
      (async/>!! events {:broker/dropped-count 1
                        :payload {:event-type "message-update" :entity-type "session"
                                  :entity-id "session" :payload {:delta "partial"}}})
      (async/>!! events {:broker/dropped-count 1
                        :payload {:event-type "agent-end" :entity-type "session" :entity-id "session"}})
      (async/>!! result {:result {:content "done"}})
      (try
        (with-redefs [helpers/read-form-body (constantly {:session_id "session" :client_id "client" :prompt "hi"})
                      sqlite/session-exists? (constantly true)
                      streaming/managed-response (fn [_ _ f] (f {}))
                      streaming/subscribe! (fn [& _] {:channel events})
                      streaming/run-task! (fn [& _] (reset! history (when history? "session")) result)
                      streaming/send-datastar-patch! (fn [_ html] (swap! patches conj html) true)
                      ui/session-status-fragment (fn [& _] "status")
                      ui/session-messages-fragment (fn [& _] "final")
                      ui/session-streaming-fragment (fn [& args] (if (= 2 (count args)) "authoritative" "partial"))]
          (handler/chat-action {:ui-state state} {})
          (if history?
            (is (not-any? #{"authoritative" "partial" "final"} @patches))
            (do (is (some #{"authoritative"} @patches))
                (is (some #{"final"} @patches))
                (is (not-any? #{"partial"} @patches)))))
        (finally (async/close! events) (async/close! result))))))
