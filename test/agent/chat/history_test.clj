(ns agent.chat.history-test
  (:require
   [agent.chat.test-support :refer [->PlannerProvider message-text temp-db-path test-system tool-call-response]]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.messages :as runtime-messages]
   [agent.sessions.service :as sessions]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest chat-loop-persists-truncation-and-recovers-next-turn-test
  (let [path (temp-db-path)
        responses (atom [{:content "partial output"
                          :stop-reason "length"
                          :usage {:tokens 7}}
                         "next answer"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider
                            (fn [cfg]
                              (let [provider-key (get-in cfg [:llm :active-provider])
                                    model (get-in cfg [:llm :providers provider-key :model])]
                                (-> cfg
                                    (assoc-in [:memory :notes :extractor :enabled] false)
                                    (assoc-in [:llm :providers provider-key :models model :chat-profile]
                                              {:small-model? false})))))
        session (sessions/create-session! system "truncation")]
    (try
      (let [first-result (chat/run! system {:session-id (:id session)
                                            :messages [{:role "user" :content "too big"}]})
            first-messages (sqlite/list-messages (:store system) (:id session))
            next-result (chat/run! system {:session-id (:id session)
                                           :messages [{:role "user" :content "smaller"}]})
            next-request-messages (get-in (second @requests) [:request :messages])]
        (is (= runtime-messages/max-tokens-content (:content first-result)))
        (is (= ["too big" "partial output" runtime-messages/max-tokens-content]
               (mapv :content first-messages)))
        (is (true? (:excluded-from-context? (second first-messages))))
        (is (true? (get-in (second first-messages) [:metadata :truncated])))
        (is (= "next answer" (:content next-result)))
        (is (not-any? #(= "partial output" (message-text %)) next-request-messages))
        (is (some #(= runtime-messages/max-tokens-content (message-text %)) next-request-messages)))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-repairs-missing-tool-result-history-test
  (let [path (temp-db-path)
        responses (atom ["recovered"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:memory :notes :extractor :enabled] false))
        session (sessions/create-session! system "missing-tool-result")]
    (try
      (sqlite/append-message! (:store system) (:id session) "user" "list")
      (sqlite/append-message! (:store system) (:id session) "assistant" ""
                              {:tool-calls [{:id "call_missing"
                                             :type "function"
                                             :function {:name "fs_list"
                                                        :arguments "{\"path\":\".\"}"}}]})
      (is (= "recovered"
             (:content (chat/run! system {:session-id (:id session)
                                          :messages [{:role "user" :content "continue"}]}))))
      (let [request-messages (get-in (first @requests) [:request :messages])
            repaired-tool (some #(when (= "tool" (:role %)) %) request-messages)
            repair-event (some #(when (= "history-repaired" (get-in % [:payload :kind])) %)
                               (sqlite/list-events (:store system)
                                                   {:entity-type :session
                                                    :entity-id (:id session)
                                                    :limit 50}))]
        (is (= "call_missing" (get-in repaired-tool [:content 0 :tool-call-id])))
        (is (= 1 (get-in repair-event [:payload :repairs :inserted-tool-results]))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-drops-orphan-tool-result-history-test
  (let [path (temp-db-path)
        responses (atom ["recovered"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:memory :notes :extractor :enabled] false))
        session (sessions/create-session! system "orphan-tool-result")]
    (try
      (sqlite/append-message! (:store system) (:id session) "tool" "late"
                              {:tool-call-id "orphan"})
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "continue"}]})
      (let [request-messages (get-in (first @requests) [:request :messages])
            repair-event (some #(when (= "history-repaired" (get-in % [:payload :kind])) %)
                               (sqlite/list-events (:store system)
                                                   {:entity-type :session
                                                    :entity-id (:id session)
                                                    :limit 50}))]
        (is (not-any? #(= "tool" (:role %)) request-messages))
        (is (= 1 (get-in repair-event [:payload :repairs :removed-tool-results]))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-persists-tool-turns-to-messages-table-test
  (let [path (temp-db-path)
        responses (atom [(tool-call-response "call_fs_1" :fs_list {:path "."})
                         "listed"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "tool-history")]
    (try
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "list files"}]})
      (let [messages (sqlite/list-messages (:store system) (:id session))
            roles (mapv :role messages)
            assistant-tool-call (some #(when (seq (:tool-calls %)) %) messages)
            tool-msg (some #(when (= "tool" (:role %)) %) messages)]
        (is (= ["user" "assistant" "tool" "assistant"] roles))
        (is (= "fs_list" (:name (first (:tool-calls assistant-tool-call)))))
        (is (= "call_fs_1" (get-in (first (:tool-calls assistant-tool-call)) [:id])))
        (is (= "call_fs_1" (:tool-call-id tool-msg)))
        (is (= "listed" (:content (last messages)))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-reloads-tool-turns-as-rich-history-test
  (let [path (temp-db-path)
        responses (atom ["follow-up answer"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "tool-reload")]
    (try
      (sqlite/append-message! (:store system) (:id session) "user" "list files")
      (sqlite/append-message! (:store system) (:id session) "assistant" ""
                              {:tool-calls [{:id "call_fs_9"
                                             :type "function"
                                             :function {:name "fs_list"
                                                        :arguments "{\"path\":\".\"}"}}]})
      (sqlite/append-message! (:store system) (:id session) "tool"
                              "{\"status\":\"ok\"}"
                              {:tool-call-id "call_fs_9"})
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "anything else?"}]})
      (let [planner-messages (get-in (first @requests) [:request :messages])
            assistant-with-tools (some #(when (some (fn [block]
                                                       (= :tool-call (:type block)))
                                                     (:content %))
                                          %)
                                       planner-messages)
            tool-msg (some #(when (= "tool" (:role %)) %) planner-messages)
            tool-call (some #(when (= :tool-call (:type %)) %)
                            (:content assistant-with-tools))
            tool-result (some #(when (= :tool-result (:type %)) %)
                              (:content tool-msg))]
        (is (some? assistant-with-tools))
        (is (= "call_fs_9" (:id tool-call)))
        (is (= "call_fs_9" (:tool-call-id tool-result))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-truncates-large-tool-history-before-planner-test
  (let [path (temp-db-path)
        responses (atom ["follow-up answer"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "large-tool-history")
        large-content (apply str (repeat 9000 "x"))]
    (try
      (sqlite/append-message! (:store system) (:id session) "assistant" ""
                              {:tool-calls [{:id "call_big"
                                             :type "function"
                                             :function {:name "fs_list"
                                                        :arguments "{\"path\":\".\"}"}}]})
      (sqlite/append-message! (:store system) (:id session) "tool" large-content
                              {:tool-call-id "call_big"})
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "anything else?"}]})
      (let [planner-messages (get-in (first @requests) [:request :messages])
            tool-msg (some #(when (= "tool" (:role %)) %) planner-messages)]
        (is (< (count (message-text tool-msg)) (count large-content)))
        (is (str/includes? (message-text tool-msg) "[truncated ")))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-persists-prompt-compaction-entry-test
  (let [path (temp-db-path)
        responses (atom ["summary of old context" "fresh answer"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(-> %
                                               (assoc-in [:memory :notes :extractor :enabled] false)
                                               (assoc-in [:memory :prompt :paths] [])
                                               (assoc-in [:memory :search :max-limit] 0)
                                               (assoc-in [:chat :compaction :max-context-tokens] 10000)
                                               (assoc-in [:chat :compaction :reserve-output-tokens] 0)
                                               (assoc-in [:chat :compaction :destructive-threshold] 0.1)
                                               (assoc-in [:chat :compaction :warning-threshold] 0.05)))
        session (sessions/create-session! system "prompt-compact")
        old (apply str (repeat 2200 "old "))]
    (try
      (sqlite/append-message! (:store system) (:id session) "user" old)
      (sqlite/append-message! (:store system) (:id session) "assistant" old)
      (is (= "fresh answer"
             (:content (chat/run! system {:session-id (:id session)
                                          :messages [{:role "user" :content "latest"}]}))))
      (let [entries (sqlite/list-entries (:store system) (:id session))
            compaction-entry (some #(when (= :compaction (:type %)) %) entries)
            messages (sqlite/list-messages (:store system) (:id session))
            planner-request (some (fn [{:keys [request]}]
                                    (when (get-in request [:metadata :planner])
                                      request))
                                  @requests)]
        (is (= "summary of old context" (get-in compaction-entry [:payload :summary])))
        (is (some #(= "context-warning" (get-in % [:payload :kind]))
                  (sqlite/list-events (:store system)
                                      {:entity-type :session
                                       :entity-id (:id session)
                                       :limit 100})))
        (is (not-any? #(str/includes? (:content %) "context-warning") messages))
        (is (not-any? #(str/includes? (message-text %) "old old old")
                      (:messages planner-request)))
        (is (some #(str/includes? (message-text %) "summary of old context")
                  (:messages planner-request))))
      (finally
        (io/delete-file path true)))))

(deftest tool-output-content-truncates-large-results-test
  (let [large-result (apply str (repeat 9000 "x"))
        content (runtime-messages/tool-output-content {:status :completed
                                                       :tool-name :memory_recall
                                                       :result large-result
                                                       :input {:query "x"}})]
    (is (str/includes? content "[truncated "))
    (is (not (str/includes? content "\"result\"")))
    (is (< (count content) (count large-result)))
    (is (str/starts-with? content "xxx"))))
