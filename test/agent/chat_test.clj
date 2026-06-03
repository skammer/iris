(ns agent.chat-test
  (:require
   [agent.chat :as chat]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.llm.core :as llm-core]
   [agent.llm.messages :as llm-messages]
   [agent.loop :as loop]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.loop :as runtime-loop]
   [agent.runtime.compaction :as compaction]
   [agent.sessions.service :as sessions]
   [agent.system :as system]
   [agent.system.components :as components]
   [agent.system.events :as events]
   [agent.tools.service :as tool-service]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

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
          response (if (instance? clojure.lang.IDeref response)
                     @response
                     response)
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

(defrecord BlockingProvider [started response]
  llm-core/ILLMProvider
  (complete [_ _ _] "fallback-response")
  (stream [_ _ _] (async/to-chan! []))
  (embed [_ _ _] [])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-tools true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ _]
    (deliver started true)
    @response)
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defn- temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-chat-" ".db")))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "iris-chat-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- tool-call-response
  ([tool-name args] (tool-call-response (str "call_" (name tool-name)) tool-name args))
  ([id tool-name args]
   {:tool-calls [{:id id
                  :type "function"
                  :function {:name (name tool-name)
                             :arguments (json/generate-string args)}}]}))

(defn- message-text [message]
  (llm-messages/content-text message))

(defn- dialogue-texts [messages]
  (mapv message-text (filter #(contains? #{"user" "assistant"} (:role %)) messages)))

(defn- test-system [path provider config-fn]
  (let [base (system/create-system)
        store (sqlite/create-store {:path path})
        event-bus (events/create-event-bus)
        event-sink (events/create-event-sink store event-bus)
        config (config-fn (:config base))]
    (assoc base
           :llm-provider provider
           :fact-llm-provider provider
           :store store
           :event-bus event-bus
           :event-sink event-sink
           :tool-registry (tool-service/create-tool-registry (:tools config) event-sink store)
           :memory-service (memory/create-memory-service (:memory config) store)
           :config config)))

(defn- custom-tool [name f metadata]
  (tools/create-tool
   {:description (tools/create-tool-description
                  name
                  (str name)
                  :input-schema [:map [:value :int]]
                  :required-permissions #{}
                  :operation (:operation metadata)
                  :parallel-safe? (:parallel-safe? metadata)
                  :approval-sensitive? (:approval-sensitive? metadata)
                  :activates-tools? (:activates-tools? metadata))
    :execute-fn f}))

(defn- custom-registry [& tools*]
  (reduce tools/register-tool (tools/create-registry) tools*))

(defn- eventually
  [pred]
  (loop [remaining 20]
    (if (pred)
      true
      (when (pos? remaining)
        (Thread/sleep 25)
        (recur (dec remaining))))))

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

(deftest chat-loop-injects-iris-context-before-memory-test
  (let [path (temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider
                            #(assoc-in % [:iris :context] "SOUL\nAGENTS"))
        session (sessions/create-session! system "context")]
    (try
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "hello"}]})
      (let [planner-messages (get-in (first @requests) [:request :messages])]
        (is (str/includes? (message-text (first planner-messages))
                           "tool-calling loop"))
        (is (= "SOUL\nAGENTS" (message-text (second planner-messages))))
        (is (str/starts-with? (message-text (nth planner-messages 2))
                              "Relevant memory JSON: "))
        (is (= "user" (:role (nth planner-messages 3)))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-injects-session-prompt-mode-before-memory-test
  (let [path (temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:iris :context] nil))
        session (sessions/create-session! system "mode")]
    (try
      (sqlite/set-session-active-mode! (:store system) (:id session) "code")
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "hello"}]})
      (let [planner-messages (get-in (first @requests) [:request :messages])]
        (is (str/includes? (message-text (first planner-messages))
                           "tool-calling loop"))
        (is (str/includes? (message-text (second planner-messages))
                           "## Coding Mode"))
        (is (str/starts-with? (message-text (nth planner-messages 2))
                              "Relevant memory JSON: "))
        (is (= "user" (:role (nth planner-messages 3)))))
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

(deftest chat-loop-executes-safe-tool-via-native-tool-call-test
  (let [path (temp-db-path)
        responses (atom [(tool-call-response :fs_list {:path "."})
                         "listed"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "tools")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "list files"}]})
            events (sqlite/list-events (:store system) {:limit 50})
            first-request (:request (first @requests))
            native-tool-names (set (map #(get-in % [:function :name]) (:tools first-request)))]
        (is (= "listed" (:content result)))
        (is (contains? native-tool-names "fs_list"))
        (is (some #{"tool-execution-end"} (map :event-type events)))
        (is (some (fn [{:keys [request]}]
                    (some #(= "tool" (:role %)) (:messages request)))
                  (rest @requests))))
      (finally
        (io/delete-file path true)))))

(deftest chat-default-profile-leaves-bare-final-unchanged-test
  (let [path (temp-db-path)
        responses (atom ["plain"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "default-profile")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "hello"}]})]
        (is (= "plain" (:content result)))
        (is (= :completed (:stop-reason result)))
        (is (= ["hello" "plain"]
               (mapv :content (sqlite/list-messages (:store system) (:id session))))))
      (finally
        (io/delete-file path true)))))

(deftest chat-blocks-normal-message-while-loop-active-test
  (let [path (temp-db-path)
        responses (atom ["unexpected"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "loop-block")]
    (try
      (loop/start! (:id session) (:config system) "work plan")
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "hello"}]})
            messages (sqlite/list-messages (:store system) (:id session))]
        (is (= "Loop active. Use /loop status or /loop stop." (:content result)))
        (is (empty? @requests))
        (is (= ["hello" "Loop active. Use /loop status or /loop stop."]
               (mapv :content messages))))
      (finally
        (loop/stop! (:id session))
        (io/delete-file path true)))))

(deftest chat-loop-slash-starts-worker-and-stops-at-max-test
  (let [path (temp-db-path)
        plan (java.io.File/createTempFile "iris-loop-chat-" ".md")
        responses (atom ["iteration done src/agent/loop.clj"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider
                            #(-> %
                                 (assoc :loop {:max-iterations 1
                                               :plan-file (.getAbsolutePath plan)
                                               :summary-max-chars 400
                                               :validation-max-chars 400})
                                 (assoc-in [:memory :facts :extractor :enabled] false)))
        session (sessions/create-session! system "loop-start")]
    (try
      (spit plan "- [ ] one")
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "/loop fix bug"}]})]
        (is (str/starts-with? (:content result) "Loop started: LOOP 0/1"))
        (is (eventually #(some (fn [message]
                                 (str/includes? (:content message) "Loop complete: LOOP 1/1"))
                               (sqlite/list-messages (:store system) (:id session)))))
        (is (= 1 (count @requests)))
        (is (some #(str/includes? (message-text %) "Loop Context")
                  (get-in (first @requests) [:request :messages]))))
      (finally
        (loop/stop! (:id session))
        (io/delete-file plan true)
        (io/delete-file path true)))))

(deftest chat-per-model-profile-enables-respond-nudge-test
  (let [path (temp-db-path)
        responses (atom ["bare"
                         (tool-call-response "call_respond" :respond {:content "final"})])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider
                            #(-> %
                                 (assoc-in [:llm :active-provider] :ollama)
                                 (assoc-in [:llm :providers :ollama :model] "llama3.2:3b")
                                 (assoc-in [:llm :providers :ollama :models "llama3.2:3b" :chat-profile]
                                           {:small-model? true
                                            :respond-tool? true
                                            :force-tool-choice? true
                                            :tool-routing? true
                                            :max-nudges 2
                                            :nudge-budgets {:bare-text 2}})))
        session (sessions/create-session! system "small-profile")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "hello"}]})
            tools-sent (set (map #(get-in % [:function :name])
                                 (get-in (first @requests) [:request :tools])))]
        (is (= "final" (:content result)))
        (is (contains? tools-sent "respond"))
        (is (= ["hello" "final"]
               (mapv :content (sqlite/list-messages (:store system) (:id session))))))
      (finally
        (io/delete-file path true)))))

(deftest chat-mixed-respond-and-real-tool-executes-real-tool-test
  (let [path (temp-db-path)
        responses (atom [{:tool-calls [{:id "call_fs"
                                        :type "function"
                                        :function {:name "fs_list"
                                                   :arguments (json/generate-string {:path "."})}}
                                       {:id "call_respond"
                                        :type "function"
                                        :function {:name "respond"
                                                   :arguments (json/generate-string {:content "too soon"})}}]}
                         (tool-call-response "call_respond2" :respond {:content "listed"})])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider
                            #(-> %
                                 (assoc-in [:llm :active-provider] :ollama)
                                 (assoc-in [:llm :providers :ollama :model] "llama3.2:3b")
                                 (assoc-in [:llm :providers :ollama :models "llama3.2:3b" :chat-profile]
                                           {:small-model? true
                                            :respond-tool? true
                                            :force-tool-choice? true
                                            :tool-routing? false
                                            :max-nudges 2
                                            :nudge-budgets {}})))
        session (sessions/create-session! system "mixed-respond")
        tool-events (atom [])]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "list files"}]
                                      :on-tool-call #(swap! tool-events conj %)})]
        (is (= "listed" (:content result)))
        (is (= ["fs_list"] (mapv #(some-> (get-in % [:receipt :tool-name]) name) @tool-events))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-uses-configured-max-steps-test
  (let [path (temp-db-path)
        responses (atom [(tool-call-response :fs_list {:path "."})
                         "listed"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(-> %
                                               (assoc-in [:chat :max-steps] 1)
                                               (assoc-in [:memory :facts :extractor :enabled] false)))
        session (sessions/create-session! system "max-steps")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "list files"}]})]
        (is (= "Stopped: max chat tool steps reached." (:content result)))
        (is (= 1 (count @requests))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-can-cancel-active-session-test
  (let [path (temp-db-path)
        started (promise)
        response (promise)
        provider (->BlockingProvider started response)
        system (test-system path provider #(assoc-in % [:memory :facts :extractor :enabled] false))
        session (sessions/create-session! system "cancel")]
    (try
      (let [result-f (future
                       (chat/run! system {:session-id (:id session)
                                          :messages [{:role "user" :content "wait"}]}))]
        (is (true? (deref started 1000 false)))
        (is (:cancelled? (chat/cancel-session! system (:id session))))
        (deliver response "late answer")
        (is (= chat/stopped-content (:content (deref result-f 1000 nil))))
        (is (eventually #(false? (chat/active? system (:id session)))))
        (is (= ["wait" chat/stopped-content]
               (mapv :content (sqlite/list-messages (:store system) (:id session))))))
      (finally
        (io/delete-file path true)))))

(deftest chat-service-state-is-system-local-test
  (let [path-a (temp-db-path)
        path-b (temp-db-path)
        release (promise)
        entered (promise)
        provider-a (->PlannerProvider (atom ["unused"]) (atom []))
        provider-b (->PlannerProvider (atom ["unused"]) (atom []))
        system-a (test-system path-a provider-a #(assoc-in % [:memory :facts :extractor :enabled] false))
        system-b (test-system path-b provider-b #(assoc-in % [:memory :facts :extractor :enabled] false))
        session-a (sessions/create-session! system-a "a")]
    (try
      (with-redefs [runtime-loop/run!
                    (fn [{:keys [event-sink session-id request-id]}]
                      (event-sink {:event-type "message-update"
                                   :entity-type "session"
                                   :entity-id session-id
                                   :request-id request-id
                                   :payload {:delta "partial"}})
                      (deliver entered true)
                      @release
                      (event-sink {:event-type "message-end"
                                   :entity-type "session"
                                   :entity-id session-id
                                   :request-id request-id
                                   :payload {:role "assistant"
                                             :content "done"
                                             :final? true}})
                      {:content "done"
                       :stop-reason :completed})]
        (let [result-f (future
                         (chat/run! system-a {:session-id (:id session-a)
                                              :messages [{:role "user" :content "go"}]
                                              :stream? true}))]
          (is (true? (deref entered 1000 false)))
          (is (eventually #(chat/active? system-a (:id session-a))))
          (is (false? (chat/active? system-b (:id session-a))))
          (is (eventually #(= "partial"
                              (chat/streaming-content system-a (:id session-a)))))
          (is (nil? (chat/streaming-content system-b (:id session-a))))
          (deliver release true)
          (is (= "done" (:content (deref result-f 1000 nil))))
          (is (eventually #(false? (chat/active? system-a (:id session-a)))))
          (is (nil? (chat/streaming-content system-a (:id session-a))))))
      (finally
        (deliver release true)
        (io/delete-file path-a true)
        (io/delete-file path-b true)))))

(deftest chat-loop-queues-rapid-sequential-session-messages-test
  (let [path (temp-db-path)
        first-response (promise)
        responses (atom [first-response "second answer"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:memory :facts :extractor :enabled] false))
        session (sessions/create-session! system "queue")]
    (try
      (let [first-f (future
                      (chat/run! system {:session-id (:id session)
                                         :messages [{:role "user" :content "first"}]}))]
        (is (eventually #(= 1 (count @requests))))
        (let [second-f (future
                         (chat/run! system {:session-id (:id session)
                                            :messages [{:role "user" :content "second"}]}))]
          (is (eventually #(= 2 (count (sqlite/list-messages (:store system) (:id session))))))
          (let [queued (second (sqlite/list-messages (:store system) (:id session)))]
            (is (= "second" (:content queued)))
            (is (true? (get-in queued [:metadata :queued])))
            (is (true? (:excluded-from-context? queued))))
          (deliver first-response "first answer")
          (is (= "first answer" (:content (deref first-f 2000 nil))))
          (is (= "second answer" (:content (deref second-f 2000 nil))))
          (is (= ["first" "first answer" "second" "second answer"]
                 (mapv :content (sqlite/list-messages (:store system) (:id session)))))
          (is (not-any? :excluded-from-context?
                        (sqlite/list-messages (:store system) (:id session))))
          (let [second-request (get-in (second @requests) [:request :messages])]
            (is (= ["first" "first answer" "second"] (dialogue-texts second-request))))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-queues-during-tool-execution-test
  (let [path (temp-db-path)
        release-tool (promise)
        tool-started (promise)
        responses (atom [(tool-call-response :fs_list {:path "."})
                         "first done"
                         "second done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:memory :facts :extractor :enabled] false))
        session (sessions/create-session! system "tool-queue")]
    (try
      (with-redefs [kernel-runtime/execute-step!
                    (fn [_ _ step _]
                      (deliver tool-started true)
                      @release-tool
                      (assoc step
                             :receipts (mapv (fn [directive]
                                               (case (:type directive)
                                                 :complete {:directive :complete
                                                            :status :completed
                                                            :result (get-in directive [:payload :result])}
                                                 :tool-call {:directive :tool-call
                                                             :status :ok
                                                             :tool-name :fs_list
                                                             :tool-call-id "call_fs"
                                                             :input {:path "."}
                                                             :result "listed"}))
                                             (:directives step))))]
        (let [first-f (future
                        (chat/run! system {:session-id (:id session)
                                           :messages [{:role "user" :content "first"}]}))]
          (is (true? (deref tool-started 1000 false)))
          (let [second-f (future
                           (chat/run! system {:session-id (:id session)
                                              :messages [{:role "user" :content "second"}]}))]
            (is (eventually #(some (fn [m]
                                      (and (= "second" (:content m))
                                           (:excluded-from-context? m)))
                                    (sqlite/list-messages (:store system) (:id session)))))
            (deliver release-tool true)
            (is (= "first done" (:content (deref first-f 2000 nil))))
            (is (= "second done" (:content (deref second-f 2000 nil))))
            (let [during-tool-request (get-in (second @requests) [:request :messages])
                  queued-request (get-in (nth @requests 2) [:request :messages])]
              (is (not-any? #(= "second" (message-text %)) during-tool-request))
              (is (some #(= "second" (message-text %)) queued-request))))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-cancellation-drains-queued-turn-test
  (let [path (temp-db-path)
        first-response (promise)
        responses (atom [first-response "after cancel answer"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:memory :facts :extractor :enabled] false))
        session (sessions/create-session! system "cancel-queue")]
    (try
      (let [first-f (future
                      (chat/run! system {:session-id (:id session)
                                         :messages [{:role "user" :content "wait"}]}))]
        (is (eventually #(= 1 (count @requests))))
        (let [second-f (future
                         (chat/run! system {:session-id (:id session)
                                            :messages [{:role "user" :content "after cancel"}]}))]
          (is (eventually #(= 2 (count (sqlite/list-messages (:store system) (:id session))))))
          (is (:cancelled? (chat/cancel-session! system (:id session))))
          (deliver first-response "late answer")
          (is (= chat/stopped-content (:content (deref first-f 2000 nil))))
          (is (= "after cancel answer" (:content (deref second-f 2000 nil))))
          (is (= ["wait" chat/stopped-content "after cancel" "after cancel answer"]
                 (mapv :content (sqlite/list-messages (:store system) (:id session)))))))
      (finally
        (io/delete-file path true)))))

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
                                    (assoc-in [:memory :facts :extractor :enabled] false)
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
        (is (= runtime-loop/max-tokens-content (:content first-result)))
        (is (= ["too big" "partial output" runtime-loop/max-tokens-content]
               (mapv :content first-messages)))
        (is (true? (:excluded-from-context? (second first-messages))))
        (is (true? (get-in (second first-messages) [:metadata :truncated])))
        (is (= "next answer" (:content next-result)))
        (is (not-any? #(= "partial output" (message-text %)) next-request-messages))
        (is (some #(= runtime-loop/max-tokens-content (message-text %)) next-request-messages)))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-repairs-missing-tool-result-history-test
  (let [path (temp-db-path)
        responses (atom ["recovered"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:memory :facts :extractor :enabled] false))
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
        system (test-system path provider #(assoc-in % [:memory :facts :extractor :enabled] false))
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
                                               (assoc-in [:memory :facts :extractor :enabled] false)
                                               (assoc-in [:memory :prompt :paths] [])
                                               (assoc-in [:memory :search :max-limit] 0)
                                               (assoc-in [:memory :graph :enabled] false)
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
        content (runtime-loop/tool-output-content {:status :completed
                                                   :tool-name :memory_search
                                                   :result large-result
                                                   :input {:query "x"}})]
    (is (str/includes? content "[truncated "))
    (is (not (str/includes? content "\"result\"")))
    (is (< (count content) (count large-result)))
    (is (str/starts-with? content "xxx"))))

(deftest chat-loop-uses-chat-completions-tool-result-protocol-test
  (let [path (temp-db-path)
        responses (atom [{:tool-calls [{:id "call_fs_1"
                                        :type "function"
                                        :function {:name "fs_list"
                                                   :arguments "{\"path\":\".\"}"}}]}
                         "listed"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider identity)
        session (sessions/create-session! system "native-tools")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "list files"}]})
            request-with-tool-output (some (fn [{:keys [request]}]
                                             (when (some #(= "tool" (:role %))
                                                         (:messages request))
                                               request))
                                           @requests)
            messages (:messages request-with-tool-output)
            assistant-tool-call (some #(when (some (fn [block]
                                                     (= :tool-call (:type block)))
                                                   (:content %))
                                        %)
                                      messages)
            tool-message (some #(when (= "tool" (:role %)) %) messages)
            tool-call (some #(when (= :tool-call (:type %)) %)
                            (:content assistant-tool-call))
            tool-result (some #(when (= :tool-result (:type %)) %)
                              (:content tool-message))]
        (is (= "listed" (:content result)))
        (is (= "call_fs_1" (:id tool-call)))
        (is (= "call_fs_1" (:tool-call-id tool-result)))
        (is (not-any? #(str/starts-with? (or (:content %) "") "Tool receipts JSON: ")
                      messages)))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-persists-multiple-tool-results-in-source-order-test
  (let [path (temp-db-path)
        responses (atom [{:tool-calls [{:id "call_slow"
                                        :type "function"
                                        :function {:name "slow_read"
                                                   :arguments (json/generate-string {:value 1})}}
                                       {:id "call_fast"
                                        :type "function"
                                        :function {:name "fast_read"
                                                   :arguments (json/generate-string {:value 2})}}]}
                         "done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (assoc (test-system path provider identity)
                      :tool-registry
                      (custom-registry
                       (custom-tool :slow_read
                                    (fn [input _] (Thread/sleep 80) input)
                                    {:operation :read :parallel-safe? true})
                       (custom-tool :fast_read
                                    (fn [input _] input)
                                    {:operation :read :parallel-safe? true})))
        session (sessions/create-session! system "multi-tool-order")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "read both"}]})
            tool-messages (filter #(= "tool" (:role %))
                                  (sqlite/list-messages (:store system) (:id session)))]
        (is (= "done" (:content result)))
        (is (= ["call_slow" "call_fast"] (mapv :tool-call-id tool-messages))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-mixed-safe-unsafe-tool-calls-preserve-boundaries-test
  (let [path (temp-db-path)
        order (atom [])
        responses (atom [{:tool-calls [{:id "call_read_a"
                                        :type "function"
                                        :function {:name "read_a"
                                                   :arguments (json/generate-string {:value 1})}}
                                       {:id "call_write_b"
                                        :type "function"
                                        :function {:name "write_b"
                                                   :arguments (json/generate-string {:value 2})}}
                                       {:id "call_read_c"
                                        :type "function"
                                        :function {:name "read_c"
                                                   :arguments (json/generate-string {:value 3})}}]}
                         "done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (assoc (test-system path provider identity)
                      :tool-registry
                      (custom-registry
                       (custom-tool :read_a
                                    (fn [input _] (swap! order conj :read-a) input)
                                    {:operation :read :parallel-safe? true})
                       (custom-tool :write_b
                                    (fn [input _] (swap! order conj :write-b) input)
                                    {:operation :act})
                       (custom-tool :read_c
                                    (fn [input _] (swap! order conj :read-c) input)
                                    {:operation :read :parallel-safe? true})))
        session (sessions/create-session! system "mixed-tool-boundaries")]
    (try
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "mixed"}]})
      (is (= [:read-a :write-b :read-c] @order))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-denies-blocked-tool-and-continues-test
  (let [path (temp-db-path)
        responses (atom [(tool-call-response :fs_list {:path "."})
                         "cannot use fs"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path
                            provider
                            #(assoc-in % [:tools :policy :blocklist] [:fs_list]))
        session (sessions/create-session! system "blocked-tool")]
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
        (is (some #{"turn-end"} (map :event-type events)))
        (is (not (:fallback? result))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-creates-approval-for-sensitive-tool-test
  (let [path (temp-db-path)
        responses (atom [(tool-call-response :shell {:argv ["whoami"]})])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path
                            provider
                            #(assoc-in % [:tools :permissions :chat] [:shell-exec]))
        session (sessions/create-session! system "approval")]
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
        (is (some #(and (= "tool-execution-update" (:event-type %))
                        (= "approval-required" (name (get-in % [:payload :kind]))))
                  events)))
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
        session (sessions/create-session! system "facts")]
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
