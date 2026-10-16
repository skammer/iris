(ns agent.chat-test
  (:require
   [agent.chat.test-support :refer [->FailingProvider ->PlannerProvider message-text temp-db-path temp-dir test-system]]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.compaction :as compaction]
   [agent.sessions.service :as sessions]
   [agent.system.components :as components]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest chat-loop-persists-final-answer-and-trace-test
  (let [path (temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "chat")]
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
        (is (some #{"message-update"} (map :event-type events)))
        (is (some #{"turn-end"} (map :event-type events)))
        (is (some #{"message-end"} (map :event-type events)))
        (is (= :invoke (:mode (first @requests)))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-persists-turn-usage-onto-assistant-message-test
  (let [path (temp-db-path)
        responses (atom [{:content "done"
                          :usage {:tokens 1234 :prompt-tokens 1000
                                  :completion-tokens 234 :cached-tokens 0}}])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "usage")]
    (try
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "hello"}]})
      (let [assistant (last (sqlite/list-messages (:store system) (:id session)))]
        (is (= "assistant" (:role assistant)))
        (is (= 1234 (get-in assistant [:metadata :usage :tokens])))
        (is (= 1000 (get-in assistant [:metadata :usage :prompt-tokens]))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-records-streaming-callback-failures-test
  (let [path (temp-db-path)
        responses (atom [{:content "done"
                          :stream-chunks ["partial"]}])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "stream-callback-failure")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "hello"}]
                                      :on-delta (fn [_]
                                                  (throw (ex-info "delta callback failed"
                                                                  {:type :callback-down})))})
            failures (filter #(= "chat.operation.failed" (:event-type %))
                             (sqlite/list-events (:store system)
                                                 {:entity-type :session
                                                  :entity-id (:id session)
                                                  :limit 20}))]
        (is (= "done" (:content result)))
        (is (= ["streaming-callback"]
               (mapv #(get-in % [:payload :operation]) failures)))
        (is (= "delta callback failed" (get-in (first failures) [:payload :message])))
        (is (= "callback-down" (get-in (first failures) [:payload :type]))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-records-auto-compaction-failures-test
  (let [path (temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "compaction-failure")]
    (try
      (with-redefs [compaction/auto-compact! (fn [& _]
                                               (throw (ex-info "compact failed"
                                                               {:type :compact-down})))]
        (let [result (chat/run! system {:session-id (:id session)
                                        :messages [{:role "user" :content "hello"}]})
              failure (first (filter #(= "chat.operation.failed" (:event-type %))
                                     (sqlite/list-events (:store system)
                                                         {:entity-type :session
                                                          :entity-id (:id session)
                                                          :limit 20})))]
          (is (= "done" (:content result)))
          (is (= "auto-compact" (get-in failure [:payload :operation])))
          (is (= "compact failed" (get-in failure [:payload :message])))
          (is (= "compact-down" (get-in failure [:payload :type])))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-preserves-rich-user-content-for-provider-test
  (let [path (temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "media")
        blocks [{:type :text :text "look"}
                {:type :image
                 :source {:type :base64
                          :media-type "image/jpeg"
                          :value "abcd"}
                 :alt "photo"}]]
    (try
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content blocks}]})
      (let [request-messages (get-in (first @requests) [:request :messages])
            user-message (last (filter #(= "user" (:role %)) request-messages))]
        (is (= [:text :image] (mapv :type (:content user-message))))
        (is (= blocks (:content user-message)))
        (is (= ["look\nphoto" "done"]
               (mapv :content (sqlite/list-messages (:store system) (:id session))))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-persists-only-new-user-turn-for-session-test
  (let [path (temp-db-path)
        responses (atom ["second answer"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "dedupe")]
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
                      (map message-text planner-messages))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-injects-slash-skill-body-and-preserves-raw-user-text-test
  (let [path (temp-db-path)
        root (temp-dir)
        skill-dir (io/file root "review")
        _ (.mkdirs skill-dir)
        _ (spit (io/file skill-dir "SKILL.md")
                "---\nname: review\ndescription: Review code\n---\n# Review\n\nUse review checklist.")
        responses (atom ["done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system0 (test-system path provider #(-> %
                                                (assoc-in [:iris :context] nil)
                                                (assoc-in [:skills :dirs] [(.getAbsolutePath root)])))
        system (assoc system0 :skills-registry (components/create-skills-registry (:skills (:config system0))))
        session (sessions/create-session! system "skill")]
    (try
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "please /review this"}]})
      (let [planner-messages (get-in (first @requests) [:request :messages])
            stored (sqlite/list-messages (:store system) (:id session))]
        (is (some #(str/includes? (message-text %) "Use review checklist.")
                  planner-messages))
        (is (= "please /review this" (:content (first stored)))))
      (finally
        (sqlite/close-store! (:store system))
        (io/delete-file (io/file skill-dir "SKILL.md") true)
        (.delete skill-dir)
        (.delete root)
        (io/delete-file path true)))))

(deftest chat-loop-streams-content-tokens-during-plan-step-test
  (let [path (temp-db-path)
        responses (atom [{:content "Hello world"
                          :stream-chunks ["Hello" " " "world"]}])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "stream-content")
        deltas (atom [])]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "hi"}]
                                      :on-delta #(swap! deltas conj %)})
            invoked-with-callback? (some? (get-in (first @requests) [:request :on-content-delta]))]
        (is (= "Hello world" (:content result)))
        (is invoked-with-callback?)
        (is (= ["Hello world"] @deltas)))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-emits-thinking-deltas-during-plan-step-test
  (let [path (temp-db-path)
        responses (atom [{:content "Hello"
                          :thinking-chunks ["think " "hard"]}])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "stream-thinking")
        thinking (atom [])]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "hi"}]
                                      :on-thinking-delta #(swap! thinking conj %)})
            invoked-with-callback? (some? (get-in (first @requests) [:request :on-thinking-delta]))
            events (sqlite/list-events (:store system) {:entity-type :session
                                                        :entity-id (:id session)
                                                        :limit 50})]
        (is (= "Hello" (:content result)))
        (is invoked-with-callback?)
        (is (= ["think " "hard"] @thinking))
        (is (= {"think hard" 1}
               (frequencies (keep #(get-in % [:payload :thinking-delta]) events)))))
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
        session (sessions/create-session! system "stream-disabled")
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
        session (sessions/create-session! system "failing")]
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
        (is (some #(and (= "agent-end" (:event-type %))
                        (= "error" (get-in % [:payload :stop-reason])))
                  events)))
      (finally
        (io/delete-file path true)))))
