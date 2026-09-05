(ns agent.ui-performance-test
  (:require [agent.api.handlers.ui :as handler]
            [agent.api.middleware :as middleware]
            [agent.api.streaming :as streaming]
            [agent.chat :as chat]
            [agent.persistence.sqlite :as sqlite]
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
