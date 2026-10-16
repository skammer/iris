(ns agent.chat.tools-test
  (:require
   [agent.chat.test-support :refer [->PlannerProvider custom-registry custom-tool temp-db-path test-system tool-call-response]]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.sessions.service :as sessions]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

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
            approval-reason (:reason (first approvals))
            messages (sqlite/list-messages (:store system) (:id session))
            tool-message (some #(when (= "tool" (:role %)) %) messages)
            tool-payload (json/parse-string (:content tool-message) true)
            events (sqlite/list-events (:store system) {:entity-type :session
                                                        :entity-id (:id session)
                                                        :limit 20})
            approval-event (some #(when (and (= "tool-execution-update" (:event-type %))
                                             (= "approval-required" (name (get-in % [:payload :kind]))))
                                    %)
                                 events)
            event-approval (first (get-in approval-event [:payload :approvals]))
            event-receipt (first (get-in approval-event [:payload :receipts]))]
        (is (re-find #"approval_id=" (:content result)))
        (is (= 1 (count approvals)))
        (is (= "shell" (:tool-name (first approvals))))
        (is (= "Agent requested shell" approval-reason))
        (is (= approval-reason (:reason tool-payload)))
        (is (= approval-reason (:reason event-approval)))
        (is (= approval-reason (:reason event-receipt))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-blocks-tools-when-routed-allow-list-empty-test
  (let [path (temp-db-path)
        executed (atom false)
        responses (atom [(tool-call-response :secret_tool {:value 1})
                         "done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (-> (test-system
                    path
                    provider
                    (fn [cfg]
                      (let [provider-key (get-in cfg [:llm :active-provider])
                            model (get-in cfg [:llm :providers provider-key :model])]
                        (-> cfg
                            (assoc-in [:memory :notes :extractor :enabled] false)
                            (assoc-in [:llm :providers provider-key :models model :chat-profile]
                                      {:tool-routing? true
                                       :tool-categories #{}})))))
                   (assoc :tool-registry
                          (custom-registry
                           (custom-tool :secret_tool
                                        (fn [_]
                                          (reset! executed true)
                                          "secret")
                                        {:parallel-safe? true}))))
        session (sessions/create-session! system "empty-allow-list")]
    (try
      (let [result (chat/run! system {:session-id (:id session)
                                      :messages [{:role "user" :content "call secret"}]})
            events (sqlite/list-events (:store system) {:entity-type :session
                                                        :entity-id (:id session)
                                                        :limit 50})]
        (is (= "done" (:content result)))
        (is (false? @executed))
        (is (some #(and (= "tool-execution-end" (:event-type %))
                        (= "denied" (get-in % [:payload :status])))
                  events)))
      (finally
        (io/delete-file path true)))))
