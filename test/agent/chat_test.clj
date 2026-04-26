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
    {:role "assistant"
     :content (or (first (first (swap-vals! responses rest))) "")
     :tool-calls []
     :usage nil
     :raw nil})
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
  (.getAbsolutePath (java.io.File/createTempFile "clj-agent-chat-" ".db")))

(defn- step-json [directives]
  (json/generate-string {:schema-version "agent.step.v1"
                         :state {}
                         :directives directives
                         :receipts []}))

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
        responses (atom [(step-json [{:type "complete"
                                      :payload {:result "done"}}])])
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
        responses (atom [(step-json [{:type "complete"
                                      :payload {:result "second answer"}}])])
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

(deftest chat-loop-executes-safe-tool-via-directive-runtime-test
  (let [path (temp-db-path)
        responses (atom [(step-json [{:type "tool-call"
                                      :payload {:tool-name "fs"
                                                :input {:action "list"
                                                        :path "."}}}])
                         (step-json [{:type "complete"
                                      :payload {:result "listed"}}])])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (system/create-session! system "tools")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "list files"}]})
            events (sqlite/list-events (:store system) {:limit 50})]
        (is (= "listed" (:content result)))
        (is (some #{"tool.execution.succeeded"} (map :event-type events)))
        (is (some (fn [{:keys [request]}]
                    (some #(str/starts-with? (:content %) "Tool receipts JSON: ")
                          (:messages request)))
                  (rest @requests))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-denies-blocked-tool-and-continues-test
  (let [path (temp-db-path)
        responses (atom [(step-json [{:type "tool-call"
                                      :payload {:tool-name "fs"
                                                :input {:action "list"
                                                        :path "."}}}])
                         (step-json [{:type "complete"
                                      :payload {:result "cannot use fs"}}])])
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
        responses (atom [(step-json [{:type "tool-call"
                                      :payload {:tool-name "shell"
                                                :input {:argv ["printf" "hi"]}}}])])
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
        responses (atom [(step-json [{:type "complete"
                                      :payload {:result "noted"}}])
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
