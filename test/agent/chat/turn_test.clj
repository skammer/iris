(ns agent.chat.turn-test
  "Characterization tests for agent.chat.turn/run-turn! at the seam used by
   agent.chat.queue. These pin the turn contract (result shape, persistence,
   event vocabulary, subscribers, approvals, recall, fallback) so the turn
   composition can be reshaped behind them."
  (:require
   [agent.chat-test :as chat-test]
   [agent.chat.service :as chat-service]
   [agent.chat.turn :as turn]
   [agent.llm.messages :as llm-messages]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.compaction :as compaction]
   [agent.runtime.messages :as runtime-messages]
   [agent.sessions.service :as sessions]
   [agent.system.components :as components]
   [agent.test.chat-harness :as harness]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]])
  (:import
   (java.time Duration Instant)))

;; Reuse the chat-test system builder and response helpers instead of
;; duplicating them; they are private there, so go through the vars.
(def ^:private test-system @#'chat-test/test-system)
(def ^:private tool-call-response @#'chat-test/tool-call-response)
(def ^:private temp-dir @#'chat-test/temp-dir)

(defn- no-extractor [config]
  (assoc-in config [:memory :notes :extractor :enabled] false))

(defn- message-text [message]
  (llm-messages/content-text message))

(defn- session-events
  "Session events oldest-first (list-events returns newest-first)."
  [system session-id]
  (vec (reverse (sqlite/list-events (:store system)
                                    {:entity-type :session
                                     :entity-id session-id
                                     :limit 200}))))

(defn- ordered-subseq? [needle haystack]
  (loop [needle (seq needle)
         haystack (seq haystack)]
    (cond
      (nil? needle) true
      (nil? haystack) false
      (= (first needle) (first haystack)) (recur (next needle) (next haystack))
      :else (recur needle (next haystack)))))

(defn- invoke-requests [requests]
  (filterv #(= :invoke (:mode %)) requests))

(defn- first-invoke-messages [requests]
  (get-in (first (invoke-requests requests)) [:request :messages]))

(deftest run-turn-plain-completion-contract-test
  (let [path (harness/temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (chat-test/->PlannerProvider responses requests)
        system (test-system path provider no-extractor)
        session (sessions/create-session! system "turn-plain")]
    (try
      (let [result (turn/run-turn! system {:session-id (:id session)
                                           :messages [{:role "user" :content "hello"}]})
            messages (sqlite/list-messages (:store system) (:id session))
            events (session-events system (:id session))
            final-end (last (filter #(and (= "message-end" (:event-type %))
                                          (get-in % [:payload :final?]))
                                    events))]
        (is (= "done" (:content result)))
        (is (= :completed (:stop-reason result)))
        (is (string? (:request-id result)))
        (is (false? (:stream? result)))
        (is (vector? (:trace result)))
        (is (map? (:usage result)))
        (is (= {:role "assistant" :content "done"} (last (:final-messages result))))
        (is (= ["user" "assistant"] (mapv :role messages)))
        (is (= ["hello" "done"] (mapv :content messages)))
        (is (ordered-subseq? ["agent-start" "turn-start" "message-start"
                              "turn-end" "message-end" "agent-end"]
                             (mapv :event-type events)))
        (is (= "assistant" (get-in final-end [:payload :role])))
        (is (= "done" (get-in final-end [:payload :content]))))
      (finally
        (io/delete-file path true)))))

(deftest run-turn-threads-request-id-through-persistence-and-events-test
  (let [path (harness/temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (chat-test/->PlannerProvider responses requests)
        system (test-system path provider no-extractor)
        session (sessions/create-session! system "turn-request-id")]
    (try
      (let [result (turn/run-turn! system {:session-id (:id session)
                                           :messages [{:role "user" :content "hello"}]
                                           :request-id "req-fixed"})
            [user-msg assistant-msg] (sqlite/list-messages (:store system) (:id session))
            turn-events (remove #(= "session.created" (:event-type %))
                                (session-events system (:id session)))]
        (is (= "req-fixed" (:request-id result)))
        (is (= "req-fixed" (get-in user-msg [:metadata :request-id])))
        (is (= "req-fixed" (get-in assistant-msg [:metadata :request-id])))
        (is (seq turn-events))
        (is (every? #(= "req-fixed" (:request-id %)) turn-events)))
      (finally
        (io/delete-file path true)))))

(deftest run-turn-skips-user-persistence-when-message-pre-persisted-test
  (let [path (harness/temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (chat-test/->PlannerProvider responses requests)
        system (test-system path provider no-extractor)
        session (sessions/create-session! system "turn-no-persist-user")
        user-row (sqlite/append-message! (:store system) (:id session) "user" "already there")]
    (try
      (let [result (turn/run-turn! system {:session-id (:id session)
                                           :messages [{:role "user" :content "already there"}]
                                           :persist-user? false
                                           :user-message user-row})
            messages (sqlite/list-messages (:store system) (:id session))]
        (is (= "done" (:content result)))
        (is (= ["user" "assistant"] (mapv :role messages)))
        (is (= ["already there" "done"] (mapv :content messages))))
      (finally
        (io/delete-file path true)))))

(deftest run-turn-tool-call-turn-persists-protocol-and-notifies-callback-test
  (let [path (harness/temp-db-path)
        responses (atom [(tool-call-response "call_fs_1" :fs_list {:path "."})
                         "listed"])
        requests (atom [])
        provider (chat-test/->PlannerProvider responses requests)
        system (test-system path provider no-extractor)
        session (sessions/create-session! system "turn-tools")
        tool-calls (atom [])]
    (try
      (let [result (turn/run-turn! system {:session-id (:id session)
                                           :messages [{:role "user" :content "list files"}]
                                           :on-tool-call #(swap! tool-calls conj %)})
            messages (sqlite/list-messages (:store system) (:id session))
            assistant-tool-call (some #(when (seq (:tool-calls %)) %) messages)
            tool-msg (some #(when (= "tool" (:role %)) %) messages)
            tool-end (some #(when (= "tool-execution-end" (:event-type %)) %)
                           (session-events system (:id session)))]
        (is (= "listed" (:content result)))
        (is (= :completed (:stop-reason result)))
        (is (= ["user" "assistant" "tool" "assistant"] (mapv :role messages)))
        (is (= "fs_list" (:name (first (:tool-calls assistant-tool-call)))))
        (is (= "call_fs_1" (:id (first (:tool-calls assistant-tool-call)))))
        (is (= "call_fs_1" (:tool-call-id tool-msg)))
        (is (= "listed" (:content (last messages))))
        (is (= ["fs_list"]
               (mapv #(some-> (get-in % [:receipt :tool-name]) name) @tool-calls)))
        (is (= "fs_list" (get-in tool-end [:payload :receipt :tool-name]))))
      (finally
        (io/delete-file path true)))))

(deftest run-turn-approval-required-creates-pending-request-with-default-ttl-test
  (let [path (harness/temp-db-path)
        responses (atom [(tool-call-response :shell {:argv ["whoami"]})])
        requests (atom [])
        provider (chat-test/->PlannerProvider responses requests)
        system (test-system path provider
                            #(-> %
                                 no-extractor
                                 (assoc-in [:tools :permissions :chat] [:shell-exec])))
        session (sessions/create-session! system "turn-approval")
        before (Instant/now)]
    (try
      (let [result (turn/run-turn! system {:session-id (:id session)
                                           :messages [{:role "user" :content "run shell"}]})
            approvals (sqlite/list-tool-approvals (:store system) {:status "pending"})
            approval (first approvals)
            ttl-seconds (.getSeconds (Duration/between before
                                                       (Instant/parse (:expires-at approval))))
            messages (sqlite/list-messages (:store system) (:id session))]
        (is (= :approval-required (:stop-reason result)))
        (is (re-find #"approval_id=" (:content result)))
        (is (seq (:approvals result)))
        (is (= 1 (count approvals)))
        (is (= "shell" (:tool-name approval)))
        (is (= (:id session) (:requested-by approval)))
        (is (= "Agent requested shell" (:reason approval)))
        ;; default TTL is [:tools :approvals :ttl-seconds] = 900
        (is (<= 895 ttl-seconds 960))
        (is (str/starts-with? (:content (last messages))
                              "Tool approval required: shell approval_id="))
        (is (= "assistant" (:role (last messages)))))
      (finally
        (io/delete-file path true)))))

(deftest run-turn-injects-recalled-vault-notes-into-planner-request-test
  (let [path (harness/temp-db-path)
        root (temp-dir)
        note-file (io/file root "preferences/tabs.md")
        responses (atom ["done"])
        requests (atom [])
        provider (chat-test/->PlannerProvider responses requests)
        system (test-system path provider #(-> %
                                               no-extractor
                                               (assoc-in [:memory :vault :paths] [(.getAbsolutePath root)])))
        session (sessions/create-session! system "turn-recall")]
    (try
      (.mkdirs (.getParentFile note-file))
      (spit note-file
            (str "---\n"
                 "id: mem_tabs\n"
                 "type: Preference\n"
                 "title: Tabs over spaces\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "---\n\n"
                 "# Tabs over spaces\n\n"
                 "User prefers tabs over spaces.\n"))
      (memory/reindex-vault! (:memory-service system))
      (turn/run-turn! system {:session-id (:id session)
                              :messages [{:role "user" :content "tabs"}]})
      (let [planner-messages (first-invoke-messages @requests)
            memory-msg (some #(when (str/starts-with? (message-text %)
                                                      "Relevant memory JSON: ")
                                %)
                             planner-messages)
            recalled (some #(when (= "memory-recalled" (get-in % [:payload :kind])) %)
                           (session-events system (:id session)))]
        (is (some? memory-msg))
        (is (str/includes? (message-text memory-msg) "tabs over spaces"))
        (is (= "tabs" (get-in recalled [:payload :query])))
        (is (= 1 (get-in recalled [:payload :surface-counts :vault-chunks]))))
      (finally
        (io/delete-file root true)
        (io/delete-file path true)))))

(deftest run-turn-orders-context-injectors-before-history-test
  (let [path (harness/temp-db-path)
        root (temp-dir)
        skill-dir (io/file root "review")
        _ (.mkdirs skill-dir)
        _ (spit (io/file skill-dir "SKILL.md")
                "---\nname: review\ndescription: Review code\n---\n# Review\n\nUse review checklist.")
        responses (atom ["done"])
        requests (atom [])
        provider (chat-test/->PlannerProvider responses requests)
        system0 (test-system path provider
                             #(-> %
                                  no-extractor
                                  (assoc-in [:iris :context] "IRIS CONTEXT")
                                  (assoc-in [:skills :dirs] [(.getAbsolutePath root)])))
        system (assoc system0 :skills-registry (components/create-skills-registry
                                                (:skills (:config system0))))
        session (sessions/create-session! system "turn-injectors")]
    (try
      (sqlite/set-session-active-mode! (:store system) (:id session) "code")
      (turn/run-turn! system {:session-id (:id session)
                              :messages [{:role "user" :content "please /review this"}]})
      (let [planner-messages (first-invoke-messages @requests)]
        (is (str/includes? (message-text (nth planner-messages 0)) "tool-calling loop"))
        (is (= "IRIS CONTEXT" (message-text (nth planner-messages 1))))
        (is (str/includes? (message-text (nth planner-messages 2)) "## Coding Mode"))
        (is (str/includes? (message-text (nth planner-messages 3)) "Use review checklist."))
        (is (str/starts-with? (message-text (nth planner-messages 4))
                              "Relevant memory JSON: "))
        (is (= "user" (:role (nth planner-messages 5))))
        (is (= "please /review this" (message-text (nth planner-messages 5)))))
      (finally
        (sqlite/close-store! (:store system))
        (io/delete-file (io/file skill-dir "SKILL.md") true)
        (.delete skill-dir)
        (.delete root)
        (io/delete-file path true)))))

(deftest run-turn-triggers-auto-compaction-for-session-test
  (let [path (harness/temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (chat-test/->PlannerProvider responses requests)
        system (test-system path provider no-extractor)
        session (sessions/create-session! system "turn-compaction")
        calls (atom [])]
    (try
      (with-redefs [compaction/auto-compact! (fn [& args]
                                               (swap! calls conj args)
                                               nil)]
        (turn/run-turn! system {:session-id (:id session)
                                :messages [{:role "user" :content "hello"}]}))
      (is (= 1 (count @calls)))
      (let [[store session-id chat-cfg] (first @calls)]
        (is (identical? (:store system) store))
        (is (= (:id session) session-id))
        (is (= (get-in system [:config :chat]) chat-cfg)))
      (finally
        (io/delete-file path true)))))

(deftest run-turn-streams-coalesced-deltas-and-clears-streaming-state-test
  (let [path (harness/temp-db-path)
        responses (atom [{:content "Hello world"
                          :stream-chunks ["Hello" " " "world"]}])
        requests (atom [])
        provider (chat-test/->PlannerProvider responses requests)
        system (test-system path provider no-extractor)
        session (sessions/create-session! system "turn-stream")
        deltas (atom [])]
    (try
      (let [result (turn/run-turn! system {:session-id (:id session)
                                           :messages [{:role "user" :content "hi"}]
                                           :on-delta #(swap! deltas conj %)})]
        (is (= "Hello world" (:content result)))
        (is (true? (:stream? result)))
        (is (some? (get-in (first (invoke-requests @requests))
                           [:request :on-content-delta])))
        (is (= ["Hello world"] @deltas))
        (is (nil? (chat-service/streaming-state system (:id session)))))
      (finally
        (io/delete-file path true)))))

(deftest run-turn-records-streaming-callback-failure-and-completes-test
  (let [path (harness/temp-db-path)
        responses (atom [{:content "done"
                          :stream-chunks ["partial"]}])
        requests (atom [])
        provider (chat-test/->PlannerProvider responses requests)
        system (test-system path provider no-extractor)
        session (sessions/create-session! system "turn-callback-failure")]
    (try
      (let [result (turn/run-turn! system {:session-id (:id session)
                                           :messages [{:role "user" :content "hello"}]
                                           :on-delta (fn [_]
                                                       (throw (ex-info "delta callback failed"
                                                                       {:type :callback-down})))})
            failures (filter #(= "chat.operation.failed" (:event-type %))
                             (session-events system (:id session)))]
        (is (= "done" (:content result)))
        (is (= ["streaming-callback"]
               (mapv #(get-in % [:payload :operation]) failures)))
        (is (= "delta callback failed" (get-in (first failures) [:payload :message])))
        (is (= "callback-down" (get-in (first failures) [:payload :type]))))
      (finally
        (io/delete-file path true)))))

(deftest run-turn-fallback-recovers-when-planner-throws-test
  (let [path (harness/temp-db-path)
        ;; First invoke (the planner step) throws; the second invoke is the
        ;; fallback completion issued by fallback-content!.
        responses (atom [(delay (throw (ex-info "planner boom" {:type :planner-down})))
                         "recovered by fallback"])
        requests (atom [])
        provider (chat-test/->PlannerProvider responses requests)
        system (test-system path provider no-extractor)
        session (sessions/create-session! system "turn-fallback")]
    (try
      (let [result (turn/run-turn! system {:session-id (:id session)
                                           :messages [{:role "user" :content "hello"}]})
            messages (sqlite/list-messages (:store system) (:id session))
            events (session-events system (:id session))
            fallback-start (some #(when (and (= "message-start" (:event-type %))
                                             (get-in % [:payload :fallback?]))
                                    %)
                                 events)]
        (is (= "recovered by fallback" (:content result)))
        (is (true? (:fallback? result)))
        (is (= :completed (:stop-reason result)))
        (is (not (:error? result)))
        (is (= ["hello" "recovered by fallback"] (mapv :content messages)))
        (is (= "planner boom" (get-in fallback-start [:payload :reason])))
        (is (some #(and (= "agent-end" (:event-type %))
                        (true? (get-in % [:payload :fallback?]))
                        (= "completed" (get-in % [:payload :stop-reason])))
                  events)))
      (finally
        (io/delete-file path true)))))

(deftest run-turn-error-result-when-planner-and-fallback-fail-test
  (let [path (harness/temp-db-path)
        provider (chat-test/->FailingProvider)
        system (test-system path provider no-extractor)
        session (sessions/create-session! system "turn-failing")]
    (try
      (let [result (turn/run-turn! system {:session-id (:id session)
                                           :messages [{:role "user" :content "hello"}]})
            messages (sqlite/list-messages (:store system) (:id session))
            events (session-events system (:id session))]
        (is (true? (:error? result)))
        (is (= :error (:stop-reason result)))
        (is (str/includes? (:content result) "Chat failed: LLM request failed: 400"))
        (is (= "LLM request failed: 400" (:initial-error result)))
        (is (= ["user" "assistant"] (mapv :role messages)))
        (is (= (:content result) (:content (last messages))))
        (is (some #(and (= "agent-end" (:event-type %))
                        (= "error" (get-in % [:payload :stop-reason]))
                        (true? (get-in % [:payload :fallback?])))
                  events)))
      (finally
        (io/delete-file path true)))))

(deftest run-turn-max-steps-opt-overrides-config-test
  (let [path (harness/temp-db-path)
        responses (atom [(tool-call-response :fs_list {:path "."})
                         "should not be reached"])
        requests (atom [])
        provider (chat-test/->PlannerProvider responses requests)
        system (test-system path provider no-extractor)
        session (sessions/create-session! system "turn-max-steps")]
    (try
      (let [result (turn/run-turn! system {:session-id (:id session)
                                           :messages [{:role "user" :content "list files"}]
                                           :max-steps 1})
            messages (sqlite/list-messages (:store system) (:id session))]
        (is (= runtime-messages/max-steps-content (:content result)))
        (is (= :max-steps (:stop-reason result)))
        (is (= 1 (count (invoke-requests @requests))))
        (is (= runtime-messages/max-steps-content (:content (last messages))))
        (is (= "assistant" (:role (last messages)))))
      (finally
        (io/delete-file path true)))))
