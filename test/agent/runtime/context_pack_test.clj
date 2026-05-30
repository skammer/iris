(ns agent.runtime.context-pack-test
  (:require
   [agent.llm.messages :as llm-messages]
   [agent.runtime.context-pack :as context-pack]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(defn- text [message]
  (llm-messages/content-text message))

(defn- big [ch n]
  (apply str (repeat n ch)))

(deftest shrinks-prefix-keeps-system-memory-and-latest-user-test
  (let [pack (context-pack/pack-context
              {:messages [{:role "system" :content "Relevant memory JSON: {\"a\":1}"}
                          {:role "user" :content (big "a" 900)}
                          {:role "assistant" :content (big "b" 900)}
                          {:role "user" :content "latest request"}]
               :system-prompt "planner"
               :tools []
               :config {:max-context-tokens 180
                        :reserve-output-tokens 0
                        :destructive-threshold 1.0}
               :summarizer-fn (constantly "short summary")})
        messages (:messages pack)]
    (is (< (:tokens-after pack) (:tokens-before pack)))
    (is (= "system" (:role (first messages))))
    (is (str/includes? (text (second messages)) "short summary"))
    (is (= "latest request" (text (last messages))))
    (is (not-any? #(str/includes? (text %) (big "a" 50)) messages))))

(deftest truncates-old-tool-result-before-dropping-test
  (let [pack (context-pack/pack-context
              {:messages [{:role "assistant"
                           :content [{:type :tool-call
                                      :id "old-call"
                                      :name "fs"
                                      :arguments {:action "read"}}]}
                          {:role "tool"
                           :content [{:type :tool-result
                                      :tool-call-id "old-call"
                                      :content (big "x" 1200)}]}
                          {:role "user" :content "middle"}
                          {:role "assistant"
                           :content [{:type :tool-call
                                      :id "new-call"
                                      :name "fs"
                                      :arguments {:action "list"}}]}
                          {:role "tool"
                           :content [{:type :tool-result
                                      :tool-call-id "new-call"
                                      :content "listed"}]}
                          {:role "user" :content "latest"}]
               :tools []
               :config {:max-context-tokens 250
                        :reserve-output-tokens 0
                        :destructive-threshold 1.0
                        :tool-result-truncate-chars 80}})]
    (is (some #(= :truncate-tool-result (:action %)) (:decisions pack)))
    (is (some #(str/includes? (text %) "[context-pack truncated") (:messages pack)))))

(deftest preserves-recent-tool-call-skeleton-test
  (let [pack (context-pack/pack-context
              {:messages [{:role "user" :content (big "a" 1000)}
                          {:role "assistant" :content "old"}
                          {:role "assistant"
                           :content [{:type :tool-call
                                      :id "call_keep"
                                      :name "fs"
                                      :arguments {:action "list"}}]}
                          {:role "tool"
                           :content [{:type :tool-result
                                      :tool-call-id "call_keep"
                                      :content "listed"}]}
                          {:role "user" :content "latest"}]
               :tools []
               :config {:max-context-tokens 160
                        :reserve-output-tokens 0
                        :destructive-threshold 1.0}
               :summarizer-fn (constantly "summary")})
        assistant (some #(when (seq (llm-messages/message-tool-calls %)) %) (:messages pack))
        tool-msg (some #(when (= "tool" (:role %)) %) (:messages pack))]
    (is (= "call_keep" (:id (first (llm-messages/message-tool-calls assistant)))))
    (is (= "call_keep" (get-in tool-msg [:content 0 :tool-call-id])))
    (is (= "latest" (text (last (:messages pack)))))))

(deftest drops-stale-synthetic-nudges-before-real-context-test
  (let [pack (context-pack/pack-context
              {:messages [{:role "user" :content "first"}
                          {:role "system" :content "NUDGE (bare-text): old"}
                          {:role "tool" :content [{:type :tool-result
                                                   :tool-call-id "call_1"
                                                   :content "real tool"}]}
                          {:role "system" :content "NUDGE (bare-text): current"}]
               :tools []
               :config {:max-context-tokens 10000}})]
    (is (= ["first" "real tool" "NUDGE (bare-text): current"]
           (mapv text (:messages pack))))))

(deftest token-estimate-ignores-provider-raw-payloads-test
  (let [base-message {:role "assistant"
                      :content [{:type :tool-call
                                 :id "call_1"
                                 :name "fs"
                                 :arguments {:action "list"}}]}
        bloated-message (assoc-in base-message [:content 0 :raw]
                                  {:provider-object (big "x" 8000)})
        pack (fn [message]
               (context-pack/pack-context
                {:messages [message]
                 :tools []
                 :config {:max-context-tokens 10000
                          :reserve-output-tokens 0}}))]
    (is (= (:tokens-before (pack base-message))
           (:tokens-before (pack bloated-message))))))
