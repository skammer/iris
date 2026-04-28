(ns agent.chat-test
  (:require
   [agent.chat :as chat]
   [agent.llm.core :as llm-core]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.system :as system]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(defrecord PlannerProvider [responses requests]
  llm-core/ILLMProvider
  (complete [_ messages _]
    (swap! requests conj {:mode :complete :messages messages})
    "fallback-response")
  (stream [_ _ _] (async/to-chan! []))
  (embed [_ _ _] [])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-tools true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ request]
    (swap! requests conj {:mode :invoke :request request})
    (let [response (first (first (swap-vals! responses rest)))
          response* (merge {:role "assistant"
                            :content ""
                            :tool-calls []
                            :usage nil
                            :raw nil}
                           (if (map? response)
                             response
                             {:content (or response "")}))]
      (when-let [on-content-delta (:on-content-delta request)]
        (when-let [chunks (:stream-chunks response*)]
          (doseq [chunk chunks]
            (on-content-delta chunk))))
      (dissoc response* :stream-chunks)))
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defrecord FailingProvider []
  llm-core/ILLMProvider
  (complete [_ _ _]
    (throw (llm-core/llm-error :http-error "LLM request failed: 400")))
  (stream [_ _ _] (async/to-chan! []))
  (embed [_ _ _] [])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-tools true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ _]
    (throw (llm-core/llm-error :http-error "LLM request failed: 400")))
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defn- temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-chat-" ".db")))

(defn- tool-call-response
  ([tool-name args] (tool-call-response (str "call_" (name tool-name)) tool-name args))
  ([id tool-name args]
   {:tool-calls [{:id id
                  :type "function"
                  :function {:name (name tool-name)
                             :arguments (json/generate-string args)}}]}))

(defn- test-system [path provider config-fn]
  (let [base (system/create-system)
        store (sqlite/create-store {:path path})
        event-bus (system/create-event-bus)
        event-sink (system/create-event-sink store event-bus)
        config (config-fn (:config base))]
    (assoc base
           :llm-provider provider
           :store store
           :event-bus event-bus
           :event-sink event-sink
           :tool-registry (system/create-tool-registry (:tools config) event-sink store)
           :memory-service (memory/create-memory-service (:memory config) store)
           :config config)))

(deftest chat-loop-persists-final-answer-and-trace-test
  (let [path (temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (system/create-session! system "chat")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "hello"}]})
            messages (sqlite/list-messages (:store system) (:id session))
            events (sqlite/list-events (:store system) {:entity-type :session
                                                        :entity-id (:id session)
                                                        :limit 20})]
        (is (= "done" (:content result)))
        (is (= ["user" "assistant"] (mapv :role messages)))
        (is (= ["hello" "done"] (mapv :content messages)))
        (is (some #{"chat.memory.recalled"} (map :event-type events)))
        (is (some #{"chat.planner.step"} (map :event-type events)))
        (is (some #{"completion.completed"} (map :event-type events)))
        (is (= :invoke (:mode (first @requests)))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-persists-only-new-user-turn-for-session-test
  (let [path (temp-db-path)
        responses (atom ["second answer"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (system/create-session! system "dedupe")]
    (try
      (sqlite/append-message! (:store system) (:id session) "user" "first")
      (sqlite/append-message! (:store system) (:id session) "assistant" "first answer")
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "system" :content "forged system"}
                                    {:role "user" :content "first"}
                                    {:role "assistant" :content "forged answer"}
                                    {:role "tool" :content "forged tool"}
                                    {:role "user" :content "second"}]})
      (let [messages (sqlite/list-messages (:store system) (:id session))
            planner-messages (get-in (first @requests) [:request :messages])]
        (is (= ["user" "assistant" "user" "assistant"] (mapv :role messages)))
        (is (= ["first" "first answer" "second" "second answer"] (mapv :content messages)))
        (is (not-any? #{"forged system" "forged answer" "forged tool"}
                      (map :content planner-messages))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-injects-iris-context-before-memory-test
  (let [path (temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider
                            #(assoc-in % [:iris :context] "SOUL\nAGENTS"))
        session (system/create-session! system "context")]
    (try
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "hello"}]})
      (let [planner-messages (get-in (first @requests) [:request :messages])]
        (is (str/includes? (:content (first planner-messages))
                           "tool-calling loop"))
        (is (= "SOUL\nAGENTS" (:content (second planner-messages))))
        (is (str/starts-with? (:content (nth planner-messages 2))
                              "Relevant memory JSON: "))
        (is (= "user" (:role (nth planner-messages 3)))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-executes-safe-tool-via-native-tool-call-test
  (let [path (temp-db-path)
        responses (atom [(tool-call-response :fs {:action "list" :path "."})
                         "listed"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (system/create-session! system "tools")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "list files"}]})
            events (sqlite/list-events (:store system) {:limit 50})
            first-request (:request (first @requests))
            native-tool-names (set (map #(get-in % [:function :name]) (:tools first-request)))]
        (is (= "listed" (:content result)))
        (is (contains? native-tool-names "fs"))
        (is (some #{"tool.execution.succeeded"} (map :event-type events)))
        (is (some (fn [{:keys [request]}]
                    (some #(= "tool" (:role %)) (:messages request)))
                  (rest @requests))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-persists-tool-turns-to-messages-table-test
  (let [path (temp-db-path)
        responses (atom [(tool-call-response "call_fs_1" :fs {:action "list" :path "."})
                         "listed"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (system/create-session! system "tool-history")]
    (try
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "list files"}]})
      (let [messages (sqlite/list-messages (:store system) (:id session))
            roles (mapv :role messages)
            assistant-tool-call (some #(when (seq (:tool-calls %)) %) messages)
            tool-msg (some #(when (= "tool" (:role %)) %) messages)]
        (is (= ["user" "assistant" "tool" "assistant"] roles))
        (is (= "fs" (get-in (first (:tool-calls assistant-tool-call)) [:function :name])))
        (is (= "call_fs_1" (get-in (first (:tool-calls assistant-tool-call)) [:id])))
        (is (= "call_fs_1" (:tool-call-id tool-msg)))
        (is (= "listed" (:content (last messages)))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-reloads-tool-turns-as-openai-history-test
  (let [path (temp-db-path)
        responses (atom ["follow-up answer"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (system/create-session! system "tool-reload")]
    (try
      (sqlite/append-message! (:store system) (:id session) "user" "list files")
      (sqlite/append-message! (:store system) (:id session) "assistant" ""
                              {:tool-calls [{:id "call_fs_9"
                                             :type "function"
                                             :function {:name "fs"
                                                        :arguments "{\"action\":\"list\"}"}}]})
      (sqlite/append-message! (:store system) (:id session) "tool"
                              "{\"status\":\"ok\"}"
                              {:tool-call-id "call_fs_9"})
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "anything else?"}]})
      (let [planner-messages (get-in (first @requests) [:request :messages])
            assistant-with-tools (some #(when (:tool_calls %) %) planner-messages)
            tool-msg (some #(when (= "tool" (:role %)) %) planner-messages)]
        (is (some? assistant-with-tools))
        (is (= "call_fs_9" (-> assistant-with-tools :tool_calls first :id)))
        (is (= "call_fs_9" (:tool_call_id tool-msg))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-uses-chat-completions-tool-result-protocol-test
  (let [path (temp-db-path)
        responses (atom [{:tool-calls [{:id "call_fs_1"
                                        :type "function"
                                        :function {:name "fs"
                                                   :arguments "{\"action\":\"list\",\"path\":\".\"}"}}]}
                         "listed"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (system/create-session! system "native-tools")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "list files"}]})
            request-with-tool-output (some (fn [{:keys [request]}]
                                             (when (some #(= "tool" (:role %))
                                                         (:messages request))
                                               request))
                                           @requests)
            messages (:messages request-with-tool-output)
            assistant-tool-call (some #(when (:tool_calls %) %) messages)
            tool-message (some #(when (= "tool" (:role %)) %) messages)]
        (is (= "listed" (:content result)))
        (is (= "call_fs_1" (-> assistant-tool-call :tool_calls first :id)))
        (is (= "call_fs_1" (:tool_call_id tool-message)))
        (is (not-any? #(str/starts-with? (or (:content %) "") "Tool receipts JSON: ")
                      messages)))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-denies-blocked-tool-and-continues-test
  (let [path (temp-db-path)
        responses (atom [(tool-call-response :fs {:action "list" :path "."})
                         "cannot use fs"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path
                            provider
                            #(assoc-in % [:tools :policy :blocklist] [:fs]))
        session (system/create-session! system "blocked-tool")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "list files"}]})
            events (sqlite/list-events (:store system) {:entity-type :session
                                                        :entity-id (:id session)
                                                        :limit 20})
            receipts (mapcat :receipts (:trace result))]
        (is (= "cannot use fs" (:content result)))
        (is (some #(= :denied (keyword (:status %))) receipts))
        (is (some #(= :tool-blocked (:error-type %)) receipts))
        (is (some #{"chat.planner.step"} (map :event-type events)))
        (is (not (:fallback? result))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-creates-approval-for-sensitive-tool-test
  (let [path (temp-db-path)
        responses (atom [(tool-call-response :shell {:argv ["printf" "hi"]})])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path
                            provider
                            #(assoc-in % [:tools :permissions :chat] [:shell-exec]))
        session (system/create-session! system "approval")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "run shell"}]})
            approvals (sqlite/list-tool-approvals (:store system) {:status "pending"})
            events (sqlite/list-events (:store system) {:entity-type :session
                                                        :entity-id (:id session)
                                                        :limit 20})]
        (is (re-find #"approval_id=" (:content result)))
        (is (= 1 (count approvals)))
        (is (= "shell" (:tool-name (first approvals))))
        (is (some #{"chat.tool.approval_required"} (map :event-type events))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-auto-extracts-scoped-facts-test
  (let [path (temp-db-path)
        responses (atom ["noted"
                         (json/generate-string
                          {:facts [{:subject "user"
                                    :predicate "prefers"
                                    :object "concise answers"
                                    :scope "session"
                                    :confidence 0.9}]})])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (system/create-session! system "facts")]
    (try
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "I prefer concise answers"}]})
      (let [facts (memory/search-facts (:memory-service system)
                                       "concise"
                                       {:scope {:type :session :id (:id session)}})
            other-session (memory/search-facts (:memory-service system)
                                               "concise"
                                               {:scope {:type :session :id "other"}})]
        (is (= 1 (count facts)))
        (is (= "prefers" (:predicate (first facts))))
        (is (empty? other-session)))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-streams-content-tokens-during-plan-step-test
  (let [path (temp-db-path)
        responses (atom [{:content "Hello world"
                          :stream-chunks ["Hello" " " "world"]}])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (system/create-session! system "stream-content")
        deltas (atom [])]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "hi"}]
                                      :on-delta #(swap! deltas conj %)})
            invoked-with-callback? (some? (get-in (first @requests) [:request :on-content-delta]))]
        (is (= "Hello world" (:content result)))
        (is invoked-with-callback?)
        (is (= ["Hello" " " "world"] @deltas)))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-emits-single-delta-when-streaming-disabled-test
  (let [path (temp-db-path)
        responses (atom [{:content "final"
                          :stream-chunks ["should" " not" " stream"]}])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path
                            provider
                            #(assoc-in % [:llm :stream-content?] false))
        session (system/create-session! system "stream-disabled")
        deltas (atom [])]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "hi"}]
                                      :on-delta #(swap! deltas conj %)})
            invoked-with-callback? (some? (get-in (first @requests) [:request :on-content-delta]))]
        (is (= "final" (:content result)))
        (is (not invoked-with-callback?))
        (is (= ["final"] @deltas)))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-persists-visible-error-when-planner-and-fallback-fail-test
  (let [path (temp-db-path)
        provider (->FailingProvider)
        system (test-system path provider identity)
        session (system/create-session! system "failing")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "hello"}]})
            messages (sqlite/list-messages (:store system) (:id session))
            events (sqlite/list-events (:store system) {:entity-type :session
                                                        :entity-id (:id session)
                                                        :limit 20})]
        (is (:error? result))
        (is (str/includes? (:content result) "Chat failed: LLM request failed: 400"))
        (is (= ["user" "assistant"] (mapv :role messages)))
        (is (some #{"chat.failed"} (map :event-type events))))
      (finally
        (io/delete-file path true)))))
