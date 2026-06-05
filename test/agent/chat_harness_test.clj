(ns agent.chat-harness-test
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.loop :as runtime-loop]
   [agent.test.chat-harness :as h]
   [agent.test.predictable :as predictable]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [cheshire.core :as json]))

(defn- eventually [pred]
  (loop [remaining 80]
    (if (pred)
      true
      (do
        (when-not (pos? remaining)
          (throw (ex-info "Timed out" {})))
        (Thread/sleep 25)
        (recur (dec remaining))))))

(defn- parse-sse-json [line]
  (when-not (= "[DONE]" line)
    (json/parse-string line true)))

(defn- chunk-content [event]
  (get-in event [:choices 0 :delta :content]))

(deftest stream-batches-tiny-deltas-and-delivers-final-before-done-test
  (let [harness (h/start!)
        session-id (h/create-session! harness "stream")
        text (apply str (repeat 200 "x"))]
    (try
      (let [lines (h/stream-chat! harness session-id (str "echo: " text))
            events (keep parse-sse-json lines)
            deltas (keep chunk-content events)
            stop-index (first (keep-indexed #(when (= "stop" (get-in %2 [:choices 0 :finish_reason])) %1)
                                            events))
            done-index (.indexOf lines "[DONE]")]
        (is (< (count deltas) 50))
        (is (= text (apply str deltas)))
        (is (number? stop-index))
        (is (= (dec (count lines)) done-index))
        (is (< stop-index done-index)))
      (finally
        (h/stop! harness)))))

(deftest harness-queues-rapid-session-messages-test
  (let [harness (h/start!)
        session-id (h/create-session! harness "queue")]
    (try
      (let [first-f (h/send-chat-async! harness session-id "delay: 0.3")
            _ (is (eventually #(true? (:working (h/session-state harness session-id)))))
            second-f (h/send-chat-async! harness session-id "echo: second")]
        (is (eventually #(= 1 (:queued_count (h/session-state harness session-id)))))
        (is (= 200 (:status @first-f)))
        (is (= 200 (:status @second-f)))
        (is (= ["delay: 0.3" "Delayed for 0.3 seconds" "echo: second" "second"]
               (mapv :content (h/list-messages harness session-id))))
        (is (= false (:working (h/session-state harness session-id))))
        (is (= 0 (:queued_count (h/session-state harness session-id)))))
      (finally
        (h/stop! harness)))))

(deftest harness-cancels-active-session-and-clears-working-state-test
  (let [harness (h/start!)
        session-id (h/create-session! harness "cancel")]
    (try
      (let [chat-f (h/send-chat-async! harness session-id "delay: 0.5")]
        (is (eventually #(true? (:working (h/session-state harness session-id)))))
        (is (= true (get-in (h/stop-session! harness session-id) [:body :data :cancelled?])))
        (is (= 200 (:status @chat-f)))
        (is (= runtime-loop/stopped-content (h/wait-response harness session-id)))
        (is (= false (:working (h/session-state harness session-id)))))
      (finally
        (h/stop! harness)))))

(deftest harness-clears-working-state-after-provider-error-test
  (let [harness (h/start!)
        session-id (h/create-session! harness "error")]
    (try
      (is (= 200 (:status (h/send-chat! harness session-id "error: boom"))))
      (is (str/includes? (h/wait-response harness session-id) "Chat failed: boom"))
      (is (= false (:working (h/session-state harness session-id))))
      (finally
        (h/stop! harness)))))

(deftest harness-persists-truncation-test
  (let [harness (h/start!)
        session-id (h/create-session! harness "truncate")]
    (try
      (is (= 200 (:status (h/send-chat! harness session-id "truncate"))))
      (let [messages (h/list-messages harness session-id)]
        (is (= runtime-loop/max-tokens-content (:content (last messages))))
        (is (= "predictable truncated output" (:content (second messages))))
        (is (true? (get-in (second messages) [:metadata :truncated])))
        (is (true? (:excluded_from_context (second messages)))))
      (finally
        (h/stop! harness)))))

(deftest harness-waits-for-tool-event-test
  (let [harness (h/start!)
        session-id (h/create-session! harness "tool")]
    (try
      (h/send-chat! harness session-id "tool: fs_list {\"path\":\".\"}")
      (let [event (h/wait-tool-event harness session-id)]
        (is (= "fs_list" (get-in event [:payload :tool-name])))
        (is (= "ok" (get-in event [:payload :status]))))
      (finally
        (h/stop! harness)))))

(deftest harness-repairs-orphan-tool-result-test
  (let [harness (h/start!)
        session-id (h/create-session! harness "orphan-tool")]
    (try
      (sqlite/append-message! (:store harness) session-id "tool" "late"
                              {:tool-call-id "orphan"})
      (h/send-chat! harness session-id "orphan-tool-result")
      (let [request (last (predictable/recent-requests (:provider harness)))
            repair-event (some #(when (= "history-repaired" (get-in % [:payload :kind])) %)
                               (sqlite/list-events (:store harness)
                                                   {:entity-type :session
                                                    :entity-id session-id
                                                    :limit 100}))]
        (is (not-any? #(= "tool" (:role %)) (:messages request)))
        (is (= 1 (get-in repair-event [:payload :repairs :removed-tool-results]))))
      (finally
        (h/stop! harness)))))
