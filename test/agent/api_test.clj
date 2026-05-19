(ns agent.api-test
  (:require
   [agent.api :as api]
   [agent.system :as system]
   [agent.llm.core :as llm-core]
   [agent.llm.messages :as llm-messages]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all])
  (:import
   (java.io BufferedReader InputStreamReader)
   (java.net URI)
   (java.net URL)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defrecord TestProvider [messages*]
  llm-core/ILLMProvider
  (complete [_ messages _]
    (reset! messages* messages)
    "test-response")
  (stream [_ messages _]
    (reset! messages* messages)
    (let [ch (async/chan)]
      (async/thread
        (async/>!! ch "hello")
        (async/>!! ch " world")
        (async/close! ch))
      ch))
  (embed [_ _ _] [0.1 0.2])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-streaming false})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0}))

(extend-type TestProvider
  llm-core/ILLMProviderInvoke
  (invoke [provider {:keys [messages on-content-delta] :as request}]
    (reset! (:messages* provider) messages)
    (llm-core/normalize-llm-response
     (if on-content-delta
       (do
         (on-content-delta "hello")
         (on-content-delta " world")
         "hello world")
       "test-response")
     request))
  (generate [provider messages opts]
    (llm-core/invoke provider (assoc opts :messages messages))))

(defn- message-text [message]
  (llm-messages/content-text message))

(extend-type TestProvider
  llm-core/ILLMProviderWithHealth
  (health-check [_] {:healthy true})
  (get-metrics [_] {:provider :test}))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-api-" ".db")))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn http-client []
  (HttpClient/newHttpClient))

(defn http-get [url]
  (let [request (.build (HttpRequest/newBuilder (URI/create url)))
        response (.send (http-client) request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn add-headers [builder headers]
  (reduce-kv (fn [b k v] (.header b k v))
             builder
             headers))

(defn http-get-headers [url headers]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (add-headers headers)
                    .build)
        response (.send (http-client) request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn http-post [url payload]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString
                            (json/generate-string payload)))
                    .build)
        response (.send (http-client) request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn http-post-headers [url payload headers]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Content-Type" "application/json")
                    (add-headers headers)
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString
                            (json/generate-string payload)))
                    .build)
        response (.send (http-client) request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn http-post-legacy [url payload]
  (let [conn (.openConnection (java.net.URL. url))
        body (json/generate-string payload)]
    (.setRequestMethod conn "POST")
    (.setRequestProperty conn "Content-Type" "application/json")
    (.setDoOutput conn true)
    (with-open [writer (java.io.OutputStreamWriter. (.getOutputStream conn) "UTF-8")]
      (.write writer body))
    (let [status (.getResponseCode conn)
          stream (if (>= status 400)
                   (.getErrorStream conn)
                   (.getInputStream conn))
          response-body (if stream (slurp stream) "")]
    {:status status
     :body response-body})))

(defn http-post-form [url form-body]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Content-Type" "application/x-www-form-urlencoded")
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString form-body))
                    .build)
        response (.send (http-client) request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn sse-data-lines [body]
  (->> (str/split-lines body)
       (filter #(str/starts-with? % "data: "))
       (map #(subs % 6))
       vec))

(defn read-sse-data-lines
  [url line-count trigger-fn]
  (let [result (promise)
        worker (future
                 (with-open [stream (.getInputStream (.openConnection (URL. url)))
                             reader (BufferedReader. (InputStreamReader. stream))]
                   (let [lines (loop [acc []]
                                 (if (>= (count acc) line-count)
                                   acc
                                   (if-let [line (.readLine reader)]
                                     (recur (if (str/starts-with? line "data: ")
                                              (conj acc (subs line 6))
                                              acc))
                                     acc)))]
                     (deliver result lines))))]
    (Thread/sleep 250)
    (trigger-fn)
    (let [lines (deref result 10000 nil)]
      (future-cancel worker)
      lines)))

(defn started-test-system [path port config-fn]
  (let [base-system (system/create-system)
        store (sqlite/create-store {:path path})
        event-bus (system/create-event-bus)
        event-sink (system/create-event-sink store event-bus)
        runtime-service (system/create-runtime-service store event-sink)
        config (-> (:config base-system)
                   (assoc :api {:host "127.0.0.1" :port port}
                          :storage {:sqlite {:path path}})
                   config-fn)
        system (assoc base-system
                      :llm-provider (->TestProvider (atom nil))
                      :store store
                      :event-bus event-bus
                      :event-sink event-sink
                      :tool-registry (system/create-tool-registry (:tools config) event-sink store)
                      :memory-service (memory/create-memory-service (:memory config) store)
                      :runtime-service runtime-service
                      :runner-registry (system/create-runner-registry runtime-service)
                      :orchestrator (system/create-orchestrator (:orchestrator config) event-sink)
                      :config config)]
    {:system system
     :server (api/start-server! system {:host "127.0.0.1" :port port})}))

(deftest api-key-auth-protects-v1-and-ui-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system path port #(assoc-in % [:api :key] "secret"))]
    (try
      (is (= 200 (:status (http-get (str base-url "/health")))))
      (is (= 401 (:status (http-get (str base-url "/v1/tools")))))
      (is (= 401 (:status (http-post (str base-url "/v1/sessions")
                                      {:title "blocked"}))))
      (is (= 401 (:status (http-get-headers (str base-url "/v1/tools")
                                            {"X-Api-Key" "wrong"}))))
      (is (= 200 (:status (http-get-headers (str base-url "/v1/tools")
                                            {"X-Api-Key" "secret"}))))
      (is (= 201 (:status (http-post-headers (str base-url "/v1/sessions")
                                             {:title "authorized"}
                                             {"X-Api-Key" "secret"}))))
      (is (= 200 (:status (http-get-headers (str base-url "/v1/tools")
                                            {"Authorization" "Bearer secret"}))))
      (is (= 200 (:status (http-get-headers
                           (str base-url "/ui/dashboard")
                           {"Authorization"
                            (str "Basic "
                                 (.encodeToString
                                  (java.util.Base64/getEncoder)
                                  (.getBytes "operator:secret" "UTF-8")))}))))
      (is (= 401 (:status (http-get (str base-url "/ui/dashboard")))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest api-tool-permissions-come-from-config-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system
                          path
                          port
                          #(assoc-in % [:tools :permissions :api] []))]
    (try
      (let [denied (http-post (str base-url "/v1/tools/fs/execute")
                              {:input {:action "list" :path "."}
                               :permissions ["filesystem-read"]})]
        (is (= 403 (:status denied))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest api-tool-policy-exposes-approval-required-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system
                          path
                          port
                          #(-> %
                               (assoc-in [:tools :permissions :api] [:filesystem-read :filesystem-write])
                               (assoc-in [:tools :policy :blocklist] [])))]
    (try
      (let [write-denied (http-post (str base-url "/v1/tools/fs/execute")
                                    {:input {:action "write"
                                             :path "target/api-tool-policy-test.txt"
                                             :content "blocked"}})
            write-denied-body (json/parse-string (:body write-denied) true)]
        (is (= 403 (:status write-denied)))
        (is (= "approval_required" (:error write-denied-body))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest api-session-chat-flow-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        base-system (system/create-system)
        messages* (atom nil)
        store (sqlite/create-store {:path path})
        event-bus (system/create-event-bus)
        event-sink (system/create-event-sink store event-bus)
        runtime-service (system/create-runtime-service store event-sink)
        config (assoc-in (:config base-system) [:memory :facts :extractor :enabled] false)
        system (assoc base-system
                      :llm-provider (->TestProvider messages*)
                      :store store
                      :event-bus event-bus
                      :event-sink event-sink
                      :tool-registry (system/create-tool-registry (assoc-in (:tools config)
                                                                           [:http :allow-private?]
                                                                           true)
                                                                  event-sink
                                                                  store)
                      :memory-service (memory/create-memory-service (:memory config) store)
                      :runtime-service runtime-service
                      :runner-registry (system/create-runner-registry runtime-service)
                      :orchestrator (system/create-orchestrator (:orchestrator config) event-sink)
                      :config (assoc config
                                     :api {:host "127.0.0.1" :port port}
                                     :storage {:sqlite {:path path}}))
        server (api/start-server! system {:host "127.0.0.1" :port port})]
    (try
      (let [bad-session (http-post (str base-url "/v1/sessions") {:title 42})
            bad-chat (http-post (str base-url "/v1/chat/completions")
                                {:messages [{:role "bogus" :content "hello"}]})
            created-docker-run (http-post (str base-url "/v1/runs")
                                          {:agent_id "docker-agent"
                                           :name "docker-run"
                                           :substrate "docker"
                                           :runner_options {:image "iris:test"
                                                            :working-dir "."
                                                            :share-network? true}})
            created-docker-run-body (json/parse-string (:body created-docker-run) true)
            docker-run-id (get-in created-docker-run-body [:data :id])
            created-run (http-post (str base-url "/v1/runs")
                                   {:agent_id "runner-agent"
                                    :name "runner"
                                    :substrate "local-unsandboxed"
                                    :runner_options {:command ["sh" "-lc" "sleep 30"]
                                                     :working-dir "."}
                                    :auto_launch true})
            created-run-body (json/parse-string (:body created-run) true)
            run-id (get-in created-run-body [:data :id])
            _ (system/register-run! system run-id
                                  {:capabilities [:chat]
                                   :network-identity {:logical-id "agent://runner"}
                                   :runner-metadata {:pid 100}})
            _ (system/heartbeat-run! system run-id
                                   {:sequence-no 1
                                    :status :running
                                    :metrics {:cpu 0.1}
                                    :lease-id (get-in (system/get-run system run-id) [:lease :id])})
            _ (system/checkpoint-run! system run-id
                                    {:sequence-no 1
                                     :checkpoint-type :state
                                     :state {:step "exec"}})
            command-entry (system/enqueue-run-command! system run-id
                                                     {:command-type :pause
                                                      :payload {:reason "test"}})
            _ (system/log-event! system
                               {:event-type :agent.run.output
                                :entity-type :agent_run
                                :entity-id run-id
                                :payload {:stream "stdout"
                                          :line "boot ok"}})
            fetched-run (http-get (str base-url "/v1/runs/" run-id))
            fetched-run-body (json/parse-string (:body fetched-run) true)
            fetched-docker-run (http-get (str base-url "/v1/runs/" docker-run-id))
            fetched-docker-run-body (json/parse-string (:body fetched-docker-run) true)
            waited-run (http-get (str base-url "/v1/runs/" run-id "/wait?timeout_ms=5&interval_ms=1"))
            waited-run-body (json/parse-string (:body waited-run) true)
            run-heartbeats (http-get (str base-url "/v1/runs/" run-id "/heartbeats?since_sequence=1"))
            run-heartbeats-body (json/parse-string (:body run-heartbeats) true)
            run-checkpoints (http-get (str base-url "/v1/runs/" run-id "/checkpoints?since_sequence=1"))
            run-checkpoints-body (json/parse-string (:body run-checkpoints) true)
            run-commands (http-get (str base-url "/v1/runs/" run-id "/commands?status=pending"))
            run-commands-body (json/parse-string (:body run-commands) true)
            run-events (http-get (str base-url "/v1/runs/" run-id "/events?limit=20"))
            run-events-body (json/parse-string (:body run-events) true)
            signaled-run (http-post (str base-url "/v1/runs/" run-id "/signal")
                                    {:command_type "cancel"})
            recovered-run (http-post (str base-url "/v1/runs/" run-id "/recover") {})
            recovered-run-body (json/parse-string (:body recovered-run) true)
            reclaimed-runs (http-post (str base-url "/v1/runs/reclaim-stale") {})
            reclaimed-runs-body (json/parse-string (:body reclaimed-runs) true)
            list-runs (http-get (str base-url "/v1/runs"))
            list-runs-body (json/parse-string (:body list-runs) true)
            ui-runs (http-get (str base-url "/ui/runs"))
            ui-run-detail (http-get (str base-url "/ui/run-detail?run_id=" run-id))
            ui-docker-run-detail (http-get (str base-url "/ui/run-detail?run_id=" docker-run-id))
            ui-index (http-get base-url)
            ui-dashboard (http-get (str base-url "/ui/dashboard"))
            ui-operator-board (http-get (str base-url "/ui/operator-board"))
            health (http-get (str base-url "/health"))
            health-body (json/parse-string (:body health) true)
            tools (http-get (str base-url "/v1/tools"))
            tools-body (json/parse-string (:body tools) true)
            tool-exec (http-post (str base-url "/v1/tools/fs/execute")
                                 {:input {:action "list" :path "."}
                                  :permissions ["filesystem-read"]})
            tool-exec-body (json/parse-string (:body tool-exec) true)
            shell-exec-blocked (http-post (str base-url "/v1/tools/shell/execute")
                                          {:input {:argv ["printf" "hello"]}})
            shell-approval-create (http-post (str base-url "/v1/tool-approvals")
                                             {:tool "shell"
                                              :input {:argv ["printf" "hello"]}
                                              :reason "test shell"})
            shell-approval-create-body (json/parse-string (:body shell-approval-create) true)
            approval-id (get-in shell-approval-create-body [:data :id])
            shell-approval-approve (http-post (str base-url "/v1/tool-approvals/" approval-id "/approve")
                                              {:actor "tester"
                                               :reason "ok"})
            shell-approved-exec (http-post (str base-url "/v1/tools/shell/execute")
                                           {:input {:argv ["printf" "hello"]}
                                            :approval_id approval-id})
            shell-approved-exec-body (json/parse-string (:body shell-approved-exec) true)
            skills (http-get (str base-url "/v1/skills"))
            skills-body (json/parse-string (:body skills) true)
            memory-surfaces (http-get (str base-url "/v1/memory/surfaces"))
            memory-surfaces-body (json/parse-string (:body memory-surfaces) true)
            prompt-memory (http-get (str base-url "/v1/memory/prompt"))
            prompt-memory-body (json/parse-string (:body prompt-memory) true)
            ui-prompt-memory (http-get (str base-url "/ui/memory/prompt"))
            memory-search (http-post (str base-url "/v1/memory/search")
                                     {:query "hello"})
            memory-search-body (json/parse-string (:body memory-search) true)
            fact-save (http-post (str base-url "/v1/memory/facts")
                                 {:subject "alice"
                                  :predicate "likes"
                                  :object "clojure"
                                  :scope {:type "global"}})
            fact-save-body (json/parse-string (:body fact-save) true)
            fact-search (http-post (str base-url "/v1/memory/facts/search")
                                   {:query "alice"
                                    :scope {:type "global"}})
            fact-search-body (json/parse-string (:body fact-search) true)
            ui-memory-search (http-post-form (str base-url "/ui/memory/search")
                                             "query=hello")
            graph-save (http-post (str base-url "/v1/memory/graph/facts")
                                  {:subject "alice"
                                   :predicate "likes"
                                   :object "clojure"})
            channel-adapters (http-get (str base-url "/v1/channel-adapters"))
            channel-adapters-body (json/parse-string (:body channel-adapters) true)
            created-agent (http-post (str base-url "/v1/agents")
                                     {:name "Worker"
                                      :kind "worker"
                                      :role "worker"
                                      :capabilities ["execute"]
                                      :tool_access ["http" "fs"]
                                      :memory_scopes ["session"]
                                      :budgets {:max_tokens 1000}
                                      :task {:id "task-1" :prompt "collect facts"}
                                      :allow_direct true})
            created-agent-body (json/parse-string (:body created-agent) true)
            agent-id (:id created-agent-body)
            created-peer (http-post (str base-url "/v1/agents")
                                    {:name "Peer"
                                     :role "router"
                                     :capabilities ["route"]
                                     :allow_direct true})
            created-peer-body (json/parse-string (:body created-peer) true)
            peer-id (:id created-peer-body)
            created-orchestrator (http-post (str base-url "/v1/agents")
                                            {:name "Planner"
                                             :kind "orchestrator"
                                             :role "orchestrator"})
            created-orchestrator-body (json/parse-string (:body created-orchestrator) true)
            orchestrator-id (:id created-orchestrator-body)
            created-federated-peer (http-post (str base-url "/v1/federation/peers")
                                              {:id "mesh-1"
                                               :base_url base-url
                                               :capabilities ["interop"]})
            created-federated-peer-body (json/parse-string (:body created-federated-peer) true)
            federation-peers (http-get (str base-url "/v1/federation/peers"))
            federation-peers-body (json/parse-string (:body federation-peers) true)
            agents (http-get (str base-url "/v1/agents"))
            agents-body (json/parse-string (:body agents) true)
            agent-interop (http-get (str base-url "/v1/agents/" agent-id "/interop"))
            agent-interop-body (json/parse-string (:body agent-interop) true)
            interop-capabilities (http-post (str base-url "/v1/agents/" agent-id "/interop/capabilities")
                                            {:capabilities ["execute" "report"]
                                             :tool_access ["http"]
                                             :memory_scopes ["session" "agent"]
                                             :budgets {:max_tokens 500}
                                             :allow_direct true
                                             :trusted_peers [peer-id]
                                             :trust_policies {peer-id {:message_types ["delegate.request"]
                                                                       :routes ["direct"]
                                                                       :required_capabilities ["route"]}}
                                             :rate_limit_per_minute 2})
            interop-capabilities-body (json/parse-string (:body interop-capabilities) true)
            interop-message (http-post (str base-url "/v1/agents/" agent-id "/interop/messages")
                                       {:from_agent_id peer-id
                                        :message_type "delegate.request"
                                        :route "direct"
                                        :delivery_mode "at-most-once"
                                        :request_id "req-1"
                                        :content "collect data"})
            interop-message-body (json/parse-string (:body interop-message) true)
            interop-message-duplicate (http-post (str base-url "/v1/agents/" agent-id "/interop/messages")
                                                 {:from_agent_id peer-id
                                                  :message_type "delegate.request"
                                                  :route "direct"
                                                  :delivery_mode "at-most-once"
                                                  :request_id "req-1"
                                                  :content "collect data"})
            interop-message-duplicate-body (json/parse-string (:body interop-message-duplicate) true)
            interop-retry (http-post (str base-url "/v1/agents/" peer-id "/interop/messages/"
                                          (get-in interop-message-body [:data :id]) "/retry")
                                     {})
            interop-retry-body (json/parse-string (:body interop-retry) true)
            interop-list (http-get (str base-url "/v1/agents/" agent-id "/interop/messages?direction=inbound"))
            interop-list-body (json/parse-string (:body interop-list) true)
            interop-ack (http-post (str base-url "/v1/agents/" agent-id "/interop/messages/"
                                         (get-in interop-message-body [:data :id]) "/ack")
                                   {:ack_type "completed"})
            interop-ack-body (json/parse-string (:body interop-ack) true)
            federated-interop-message (http-post (str base-url "/v1/agents/" peer-id "/interop/messages")
                                                 {:from_agent_id peer-id
                                                  :message_type "delegate.request"
                                                  :route "federated"
                                                  :request_id "req-fed-1"
                                                  :content "collect remote"
                                                  :to_agent_ref (str "federation://mesh-1/" agent-id)})
            federated-interop-message-body (json/parse-string (:body federated-interop-message) true)
            interop-list-after-federation (http-get (str base-url "/v1/agents/" agent-id "/interop/messages?direction=inbound"))
            interop-list-after-federation-body (json/parse-string (:body interop-list-after-federation) true)
            agent-tool-exec (http-post (str base-url "/v1/agents/" agent-id "/tools/http/execute")
                                       {:input {:url (str base-url "/health")}
                                        :permissions ["http-request"]})
            agent-tool-exec-blocked (http-post (str base-url "/v1/agents/" agent-id "/tools/fs/execute")
                                               {:input {:action "list" :path "."}
                                                :permissions ["filesystem-read"]})
            orchestrator-spawn-worker (http-post (str base-url "/v1/agents/" orchestrator-id "/spawn-worker")
                                                 {:name "Delegated Worker"
                                                  :task {:id "task-3" :prompt "do thing"}
                                                  :capabilities ["execute"]
                                                  :tool_access ["http"]
                                                  :memory_scopes ["session"]
                                                  :budgets {:max_tokens 100}})
            orchestrator-spawn-worker-body (json/parse-string (:body orchestrator-spawn-worker) true)
            step-execute (http-post (str base-url "/v1/agents/" agent-id "/steps")
                                    {:directives [{:type "state-patch"
                                                   :payload {:patch {:phase "working"}}}
                                                  {:type "complete"
                                                   :payload {:result "done"}}]})
            step-execute-body (json/parse-string (:body step-execute) true)
            agent-msg (http-post (str base-url "/v1/agents/" agent-id "/messages")
                                 {:content "do work"})
            agent-msg-body (json/parse-string (:body agent-msg) true)
            channel (http-post (str base-url "/v1/channels")
                               {:name "ops"
                                :participants [agent-id]})
            channel-body (json/parse-string (:body channel) true)
            channel-id (:id channel-body)
            channel-post (http-post (str base-url "/v1/channels/" channel-id "/messages")
                                    {:sender_id agent-id
                                     :content "status update"})
            channel-post-body (json/parse-string (:body channel-post) true)
            channels (http-get (str base-url "/v1/channels"))
            channels-body (json/parse-string (:body channels) true)
            created (http-post (str base-url "/v1/sessions") {:title "api-test"})
            created-body (json/parse-string (:body created) true)
            session-id (:id created-body)
            ui-created (http-post-form (str base-url "/ui/sessions") "title=ui-test")
            session-detail (http-get (str base-url "/ui/session-detail?session_id=" session-id))
            session-messages (http-get (str base-url "/ui/session-messages?session_id=" session-id))
            completion (http-post (str base-url "/v1/chat/completions")
                                  {:session_id session-id
                                   :prompt "hello"})
            ui-chat (http-post-form (str base-url "/ui/chat")
                                    (str "session_id=" session-id "&prompt=hello+ui"))
            streamed (http-post-legacy (str base-url "/v1/chat/completions")
                                       {:session_id session-id
                                        :prompt "hello"
                                        :stream true})
            streamed-lines (sse-data-lines (:body streamed))
            events (http-get (str base-url "/v1/events"))
            events-body (json/parse-string (:body events) true)
            messages (http-get (str base-url "/v1/sessions/" session-id "/messages"))
            messages-body (json/parse-string (:body messages) true)]
        (is (= 400 (:status bad-session)))
        (is (= 400 (:status bad-chat)))
        (is (= 201 (:status created-docker-run)))
        (is (= "docker" (get-in created-docker-run-body [:data :substrate])))
        (is (= "iris:test" (get-in created-docker-run-body [:data :runner_options :image])))
        (is (= true (get-in created-docker-run-body [:data :runner_options :share-network?])))
        (is (= "mounted-dev" (get-in fetched-docker-run-body [:data :container_contract :image-mode])))
        (is (= 201 (:status created-run)))
        (is (= "launched" (get-in created-run-body [:data :status])))
        (is (= "local-unsandboxed" (get-in created-run-body [:data :substrate])))
        (is (= 200 (:status fetched-run)))
        (is (= true (get-in fetched-run-body [:data :runner_status :alive])))
        (is (number? (get-in fetched-run-body [:data :runner_status :pid])))
        (is (map? (get-in fetched-run-body [:data :recovery])))
        (is (= 200 (:status waited-run)))
        (is (= run-id (get-in waited-run-body [:data :id])))
        (is (= 200 (:status run-heartbeats)))
        (is (= 1 (count (:data run-heartbeats-body))))
        (is (= 1 (get-in run-heartbeats-body [:data 0 :sequence_no])))
        (is (= 200 (:status run-checkpoints)))
        (is (= 1 (count (:data run-checkpoints-body))))
        (is (= "state" (get-in run-checkpoints-body [:data 0 :checkpoint_type])))
        (is (= 200 (:status run-commands)))
        (is (= [(:id command-entry)] (mapv :id (:data run-commands-body))))
        (is (= 200 (:status run-events)))
        (is (some #{"agent.run.heartbeat"} (map :event_type (:data run-events-body))))
        (is (= 200 (:status signaled-run)))
        (is (= 202 (:status recovered-run)))
        (is (string? (get-in recovered-run-body [:data :replacement_run :id])))
        (is (= 200 (:status reclaimed-runs)))
        (is (vector? (:data reclaimed-runs-body)))
        (is (= 200 (:status list-runs)))
        (is (contains? (set (map :id (:data list-runs-body))) run-id))
        (is (contains? (set (map :id (:data list-runs-body))) docker-run-id))
        (is (= 200 (:status ui-runs)))
        (is (str/includes? (:body ui-runs) "Create Run"))
        (is (str/includes? (:body ui-runs) "seatbelt"))
        (is (= 200 (:status ui-run-detail)))
        (is (str/includes? (:body ui-run-detail) "Latest checkpoint"))
        (is (str/includes? (:body ui-run-detail) "Recovery"))
        (is (str/includes? (:body ui-run-detail) "Recent output"))
        (is (= 200 (:status ui-docker-run-detail)))
        (is (str/includes? (:body ui-docker-run-detail) "Container contract"))
        (is (str/includes? (:body ui-run-detail) "boot ok"))
        (is (str/includes? (:body ui-run-detail) "agent-run-panel"))
        (is (str/includes? (:body ui-run-detail) "data-run-output-tail"))
        (is (= 200 (:status ui-index)))
        (is (str/includes? (:body ui-index) "datastar.js"))
        (is (= 200 (:status ui-dashboard)))
        (is (str/includes? (:body ui-dashboard) "Runtime Snapshot"))
        (is (str/includes? (:body ui-dashboard) "Recent runs"))
        (is (str/includes? (:body ui-dashboard) "Pending approvals"))
        (is (str/includes? (:body ui-dashboard) "Run status"))
        (is (str/includes? (:body ui-dashboard) "Attention"))
        (is (str/includes? (:body ui-dashboard) "Stale runs"))
        (is (= 200 (:status ui-operator-board)))
        (is (str/includes? (:body ui-operator-board) "Operator Board"))
        (is (str/includes? (:body ui-operator-board) "Active runs"))
        (is (str/includes? (:body ui-operator-board) "Stale runs"))
        (is (str/includes? (:body ui-operator-board) "Approval queue"))
        (is (str/includes? (:body ui-operator-board) "Federated peers"))
        (is (str/includes? (:body ui-operator-board) "Interop policy"))
        (is (str/includes? (:body ui-operator-board) "Interop activity"))
        (is (str/includes? (:body ui-operator-board) "Kernel receipts"))
        (is (= 200 (:status health)))
        (is (= 3 (get-in health-body [:tools :count])))
        (is (= true (get-in health-body [:memory :healthy])))
        (is (= 3 (get-in health-body [:channel-adapters :count])))
        (is (= 0 (get-in health-body [:orchestrator :agent-count])))
        (is (= 200 (:status tools)))
        (is (= ["fs" "http" "shell"] (mapv :name (:data tools-body))))
        (is (= "builtin" (get-in tools-body [:data 0 :source])))
        (is (= 200 (:status tool-exec)))
        (is (vector? (get-in tool-exec-body [:data :entries])))
        (is (= 403 (:status shell-exec-blocked)))
        (is (= 201 (:status shell-approval-create)))
        (is (re-matches #"[0-9a-f]{64}" (get-in shell-approval-create-body [:data :input_hash])))
        (is (= ["shell-exec"] (get-in shell-approval-create-body [:data :requested_permissions])))
        (is (string? (get-in shell-approval-create-body [:data :expires_at])))
        (is (= 200 (:status shell-approval-approve)))
        (is (= 200 (:status shell-approved-exec)))
        (is (= "hello" (get-in shell-approved-exec-body [:data :stdout])))
        (is (= 200 (:status skills)))
        (is (= [] (:data skills-body)))
        (is (= 200 (:status memory-surfaces)))
        (is (= ["prompt" "search" "facts" "graph" "vault"] (mapv :name (:data memory-surfaces-body))))
        (is (= 200 (:status prompt-memory)))
        (is (string? (:combined prompt-memory-body)))
        (is (= 200 (:status ui-prompt-memory)))
        (is (str/includes? (:body ui-prompt-memory) "Prompt Memory"))
        (is (= 200 (:status memory-search)))
        (is (= "hello" (:query memory-search-body)))
        (is (= 201 (:status fact-save)))
        (is (= "alice" (get-in fact-save-body [:data :subject])))
        (is (= 200 (:status fact-search)))
        (is (= 1 (count (:data fact-search-body))))
        (is (= 200 (:status ui-memory-search)))
        (is (str/includes? (:body ui-memory-search) "Search Results"))
        (is (= 409 (:status graph-save)))
        (is (= 200 (:status channel-adapters)))
        (is (= ["discord" "slack" "telegram"] (mapv :name (:data channel-adapters-body))))
        (is (= 201 (:status created-agent)))
        (is (= "worker" (:kind created-agent-body)))
        (is (= ["fs" "http"] (:tool_access created-agent-body)))
        (is (= ["session"] (:memory_scopes created-agent-body)))
        (is (= {} (:state created-agent-body)))
        (is (= 201 (:status created-peer)))
        (is (= 201 (:status created-orchestrator)))
        (is (= "orchestrator" (:kind created-orchestrator-body)))
        (is (= 201 (:status created-federated-peer)))
        (is (= "mesh-1" (get-in created-federated-peer-body [:data :id])))
        (is (= 200 (:status federation-peers)))
        (is (= ["mesh-1"] (mapv :id (:data federation-peers-body))))
        (is (= 200 (:status agents)))
        (is (= #{agent-id peer-id orchestrator-id} (set (map :id (:data agents-body)))))
        (is (= 200 (:status agent-interop)))
        (is (= (str "agent://" agent-id) (get-in agent-interop-body [:data :logical-address])))
        (is (= 200 (:status interop-capabilities)))
        (is (= ["execute" "report"] (get-in interop-capabilities-body [:data :capabilities])))
        (is (= ["http"] (get-in interop-capabilities-body [:data :tool-access])))
        (is (= ["session" "agent"] (get-in interop-capabilities-body [:data :memory-scopes])))
        (is (= {:max_tokens 500} (get-in interop-capabilities-body [:data :budgets])))
        (is (= [peer-id] (get-in interop-capabilities-body [:data :trusted-peers])))
        (is (= ["delegate.request"] (get-in interop-capabilities-body [:data :trust-policies (keyword peer-id) :message-types])))
        (is (= ["direct"] (get-in interop-capabilities-body [:data :trust-policies (keyword peer-id) :routes])))
        (is (= 2 (get-in interop-capabilities-body [:data :interop-rate-limit-per-minute])))
        (is (= 201 (:status interop-message)))
        (is (= "direct" (get-in interop-message-body [:data :route])))
        (is (= 201 (:status interop-message-duplicate)))
        (is (= (get-in interop-message-body [:data :id])
               (get-in interop-message-duplicate-body [:data :id])))
        (is (= 200 (:status interop-retry)))
        (is (= 2 (get-in interop-retry-body [:data :delivery_count])))
        (is (= 200 (:status interop-list)))
        (is (= 1 (count (:data interop-list-body))))
        (is (= "delivered" (get-in interop-list-body [:data 0 :status])))
        (is (= 200 (:status interop-ack)))
        (is (= "acked" (get-in interop-ack-body [:data :status])))
        (is (= "completed" (get-in interop-ack-body [:data :ack_type])))
        (is (= 201 (:status federated-interop-message)))
        (is (= "federated" (get-in federated-interop-message-body [:data :route])))
        (is (= "forwarded" (get-in federated-interop-message-body [:data :status])))
        (is (= 200 (:status interop-list-after-federation)))
        (is (= 2 (count (:data interop-list-after-federation-body))))
        (is (= 1 (count (filter #(= "federated" (:route %)) (:data interop-list-after-federation-body)))))
        (is (= 200 (:status agent-tool-exec)))
        (is (= 403 (:status agent-tool-exec-blocked)))
        (is (= 201 (:status orchestrator-spawn-worker)))
        (is (= :ok (keyword (get-in orchestrator-spawn-worker-body [:data :receipts 0 :status]))))
        (is (= ["http"] (get-in orchestrator-spawn-worker-body [:data :worker :tool_access])))
        (is (= 200 (:status step-execute)))
        (is (= 2 (count (get-in step-execute-body [:data :receipts]))))
        (is (= :completed (keyword (get-in step-execute-body [:data :receipts 1 :status]))))
        (is (= 200 (:status agent-msg)))
        (is (= "test-response" (get-in agent-msg-body [:response :content])))
        (is (= 201 (:status channel)))
        (is (= 201 (:status channel-post)))
        (is (= agent-id (:sender_id channel-post-body)))
        (is (= 200 (:status channels)))
        (is (= [channel-id] (mapv :id (:data channels-body))))
        (is (= 201 (:status created)))
        (is (= 200 (:status ui-created)))
        (is (str/includes? (:body ui-created) "datastar-patch-elements"))
        (is (str/includes? (:body ui-created) "ui-test"))
        (is (str/includes? (:body ui-created) "session-link--active"))
        (is (str/includes? (:body ui-created) "/ui/session/live?session_id="))
        (is (= 200 (:status session-detail)))
        (is (str/includes? (:body session-detail) session-id))
        (is (= 200 (:status session-messages)))
        (is (str/includes? (:body session-detail) "/ui/session/live?session_id="))
        (is (= 200 (:status completion)))
        (is (= 200 (:status ui-chat)))
        ;; /ui/chat streams via chat/run! fallback → final patch frame contains "hello world".
        (is (str/includes? (:body ui-chat) "hello world"))
        (is (= 200 (:status streamed)))
        (is (str/includes? (first streamed-lines) "\"role\":\"assistant\""))
        (is (some #(str/includes? % "\"event_type\":\"chat.started\"") streamed-lines))
        (is (some #(str/includes? % "\"content\":\"hello\"") streamed-lines))
        (is (some #(str/includes? % "\"content\":\" world\"") streamed-lines))
        (is (= "[DONE]" (last streamed-lines)))
        (is (= 200 (:status events)))
        (is (some #{"session.created"} (map :event_type (:data events-body))))
        (is (some #{"agent.created"} (map :event_type (:data events-body))))
        (is (some #{"channel.created"} (map :event_type (:data events-body))))
        (is (some #{"completion.completed"} (map :event_type (:data events-body))))
        (is (= 6 (count (:data messages-body))))
        (is (= "test-response" (get-in messages-body [:data 1 :content])))
        (is (= "hello ui" (get-in messages-body [:data 2 :content])))
        (is (= "hello world" (get-in messages-body [:data 3 :content])))
        (is (= "hello" (get-in messages-body [:data 4 :content])))
        (is (= "hello world" (get-in messages-body [:data 5 :content])))
        (is (= ["system" "system" "system" "user" "assistant" "user" "assistant" "user"]
               (mapv :role @messages*)))
        (is (str/starts-with? (first (mapv message-text @messages*))
                              "You drive a tool-calling loop."))
        (is (str/includes? (second (mapv message-text @messages*))
                           "# SOUL"))
        (is (str/starts-with? (nth (mapv message-text @messages*) 2)
                              "Relevant memory JSON: "))
        (is (= ["hello" "test-response" "hello ui" "hello world" "hello"]
               (subvec (mapv message-text @messages*) 3))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest api-run-stream-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        base-system (system/create-system)
        store (sqlite/create-store {:path path})
        event-bus (system/create-event-bus)
        event-sink (system/create-event-sink store event-bus)
        runtime-service (system/create-runtime-service store event-sink)
        system (assoc base-system
                      :llm-provider (->TestProvider (atom nil))
                      :store store
                      :event-bus event-bus
                      :event-sink event-sink
                      :runtime-service runtime-service
                      :runner-registry (system/create-runner-registry runtime-service)
                      :config (assoc (:config base-system)
                                     :api {:host "127.0.0.1" :port port}
                                     :storage {:sqlite {:path path}}))
        server (api/start-server! system {:host "127.0.0.1" :port port})]
    (try
      (let [run (system/request-run! system {:agent-id "stream-agent"
                                           :name "stream-run"
                                           :substrate :local-unsandboxed
                                           :requested-by "tester"})
            run-id (:id run)
            stream-lines (read-sse-data-lines
                          (str base-url "/v1/runs/" run-id "/stream")
                          6
                          #(do
                             (system/register-run! system run-id
                                                 {:capabilities [:chat]
                                                  :network-identity {:logical-id "agent://stream"}})
                             (system/heartbeat-run! system run-id
                                                  {:sequence-no 1
                                                   :status :running
                                                   :metrics {:phase "boot"}
                                                   :lease-id (get-in (system/get-run system run-id) [:lease :id])})
                             (system/log-event! system
                                              {:event-type :agent.run.output
                                               :entity-type :agent_run
                                               :entity-id run-id
                                               :payload {:stream "stdout"
                                                         :line "hello from child"}})))]
        (is (<= 4 (count stream-lines)))
        (is (= "snapshot" (:type (json/parse-string (first stream-lines) true))))
        (is (= "event" (:type (json/parse-string (second stream-lines) true))))
        (is (= "agent.run.registered"
               (get-in (json/parse-string (second stream-lines) true) [:data :event_type])))
        (is (some #{"agent.run.output"}
                  (map #(get-in (json/parse-string % true) [:data :event_type])
                       (rest stream-lines)))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))
