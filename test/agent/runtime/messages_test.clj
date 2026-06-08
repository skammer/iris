(ns agent.runtime.messages-test
  (:require
   [agent.runtime.messages :as runtime-messages]
   [clojure.test :refer :all]))

(deftest normalize-chat-history-inserts-missing-tool-result-test
  (let [{:keys [messages repairs]}
        (runtime-messages/normalize-chat-history
         [{:role "assistant"
           :content [{:type :tool-call
                      :id "call_1"
                      :name "fs_list"
                      :arguments {:path "."}}]}
          {:role "user" :content "next"}])]
    (is (= 1 (:inserted-tool-results repairs)))
    (is (= ["assistant" "tool" "user"] (mapv :role messages)))
    (is (= "call_1" (get-in messages [1 :content 0 :tool-call-id])))))

(deftest normalize-chat-history-removes-orphan-tool-result-test
  (let [{:keys [messages repairs]}
        (runtime-messages/normalize-chat-history
         [{:role "tool"
           :content [{:type :tool-result
                      :tool-call-id "orphan"
                      :content "late"}]}
          {:role "user" :content "next"}])]
    (is (= 1 (:removed-tool-results repairs)))
    (is (= ["user"] (mapv :role messages)))))

(deftest normalize-chat-history-preserves-valid-tool-order-test
  (let [input [{:role "assistant"
                :content [{:type :tool-call
                           :id "call_1"
                           :name "fs_list"
                           :arguments {:path "."}}]}
               {:role "tool"
                :content [{:type :tool-result
                           :tool-call-id "call_1"
                           :content "ok"}]}
               {:role "user" :content [{:type :text :text "next"}]}]
        normalized (runtime-messages/normalize-chat-history input)]
    (is (= {} (:repairs normalized)))
    (is (= input (:messages normalized)))))

(deftest normalize-chat-history-adds-empty-assistant-placeholder-test
  (let [{:keys [messages repairs]}
        (runtime-messages/normalize-chat-history
         [{:role "assistant" :content []}
          {:role "user" :content "next"}])]
    (is (= 1 (:placeholder-assistant-messages repairs)))
    (is (= runtime-messages/empty-assistant-content
           (get-in messages [0 :content 0 :text])))))

(deftest normalize-chat-history-removes-internal-stop-assistant-test
  (let [{:keys [messages repairs]}
        (runtime-messages/normalize-chat-history
         [{:role "assistant" :content "Stopped: guardrail retry budget exhausted."}
          {:role "user" :content "next"}])]
    (is (= 1 (:removed-internal-stop-messages repairs)))
    (is (= ["user"] (mapv :role messages)))))
