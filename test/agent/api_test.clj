(ns agent.api-test
  (:require
   [agent.api :as api]
   [agent.core :as core]
   [agent.llm.core :as llm-core]
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
  llm-core/ILLMProviderWithHealth
  (health-check [_] {:healthy true})
  (get-metrics [_] {:provider :test}))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "clj-agent-api-" ".db")))

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

(defn http-post [url payload]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Content-Type" "application/json")
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

(deftest api-session-chat-flow-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        base-system (core/create-system)
        messages* (atom nil)
        store (sqlite/create-store {:path path})
        event-bus (core/create-event-bus)
        event-sink (core/create-event-sink store event-bus)
        runtime-service (core/create-runtime-service store event-sink)
        system (assoc base-system
                      :llm-provider (->TestProvider messages*)
                      :store store
                      :event-bus event-bus
                      :event-sink event-sink
                      :tool-registry (core/create-tool-registry (:tools (:config base-system)) event-sink store)
                      :memory-service (memory/create-memory-service (:memory (:config base-system)) store)
                      :runtime-service runtime-service
                      :runner-registry (core/create-runner-registry runtime-service)
                      :orchestrator (core/create-orchestrator (:orchestrator (:config base-system)) event-sink)
                      :config (assoc (:config base-system)
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
                                           :runner_options {:image "clj-agent:test"
                                                            :working-dir "."
                                                            :share-network? true}})
            created-docker-run-body (json/parse-string (:body created-docker-run) true)
            created-run (http-post (str base-url "/v1/runs")
                                   {:agent_id "runner-agent"
                                    :name "runner"
                                    :substrate "local-process"
                                    :runner_options {:command ["sh" "-lc" "sleep 30"]
                                                     :working-dir "."}
                                    :auto_launch true})
            created-run-body (json/parse-string (:body created-run) true)
            run-id (get-in created-run-body [:data :id])
            _ (core/register-run! system run-id
                                  {:capabilities [:chat]
                                   :network-identity {:logical-id "agent://runner"}
                                   :runner-metadata {:pid 100}})
            _ (core/heartbeat-run! system run-id
                                   {:sequence-no 1
                                    :status :running
                                    :metrics {:cpu 0.1}
                                    :lease-id (get-in (core/get-run system run-id) [:lease :id])})
            _ (core/checkpoint-run! system run-id
                                    {:sequence-no 1
                                     :checkpoint-type :state
                                     :state {:step "exec"}})
            command-entry (core/enqueue-run-command! system run-id
                                                     {:command-type :pause
                                                      :payload {:reason "test"}})
            _ (core/log-event! system
                               {:event-type :agent.run.output
                                :entity-type :agent_run
                                :entity-id run-id
                                :payload {:stream "stdout"
                                          :line "boot ok"}})
            fetched-run (http-get (str base-url "/v1/runs/" run-id))
            fetched-run-body (json/parse-string (:body fetched-run) true)
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
            list-runs (http-get (str base-url "/v1/runs"))
            list-runs-body (json/parse-string (:body list-runs) true)
            ui-runs (http-get (str base-url "/ui/runs"))
            ui-run-detail (http-get (str base-url "/ui/run-detail?run_id=" run-id))
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
                                          {:input {:command "printf hello"}})
            shell-approval-create (http-post (str base-url "/v1/tool-approvals")
                                             {:tool "shell"
                                              :input {:command "printf hello"}
                                              :reason "test shell"})
            shell-approval-create-body (json/parse-string (:body shell-approval-create) true)
            approval-id (get-in shell-approval-create-body [:data :id])
            shell-approval-approve (http-post (str base-url "/v1/tool-approvals/" approval-id "/approve")
                                              {:actor "tester"
                                               :reason "ok"})
            shell-approved-exec (http-post (str base-url "/v1/tools/shell/execute")
                                           {:input {:command "printf hello"}
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
                                      :role "worker"})
            created-agent-body (json/parse-string (:body created-agent) true)
            agent-id (:id created-agent-body)
            agents (http-get (str base-url "/v1/agents"))
            agents-body (json/parse-string (:body agents) true)
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
        (is (= "clj-agent:test" (get-in created-docker-run-body [:data :runner_options :image])))
        (is (= true (get-in created-docker-run-body [:data :runner_options :share-network?])))
        (is (= 201 (:status created-run)))
        (is (= "launched" (get-in created-run-body [:data :status])))
        (is (= "local-process" (get-in created-run-body [:data :substrate])))
        (is (= 200 (:status fetched-run)))
        (is (= true (get-in fetched-run-body [:data :runner_status :alive])))
        (is (number? (get-in fetched-run-body [:data :runner_status :pid])))
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
        (is (= 200 (:status list-runs)))
        (is (= #{run-id (get-in created-docker-run-body [:data :id])}
               (set (map :id (:data list-runs-body)))))
        (is (= 200 (:status ui-runs)))
        (is (str/includes? (:body ui-runs) "Create Run"))
        (is (str/includes? (:body ui-runs) "seatbelt"))
        (is (= 200 (:status ui-run-detail)))
        (is (str/includes? (:body ui-run-detail) "Latest checkpoint"))
        (is (str/includes? (:body ui-run-detail) "Recent output"))
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
        (is (= 200 (:status shell-approval-approve)))
        (is (= 200 (:status shell-approved-exec)))
        (is (= "hello" (get-in shell-approved-exec-body [:data :stdout])))
        (is (= 200 (:status skills)))
        (is (= [] (:data skills-body)))
        (is (= 200 (:status memory-surfaces)))
        (is (= ["prompt" "search" "graph"] (mapv :name (:data memory-surfaces-body))))
        (is (= 200 (:status prompt-memory)))
        (is (string? (:combined prompt-memory-body)))
        (is (= 200 (:status ui-prompt-memory)))
        (is (str/includes? (:body ui-prompt-memory) "Prompt Memory"))
        (is (= 200 (:status memory-search)))
        (is (= "hello" (:query memory-search-body)))
        (is (= 200 (:status ui-memory-search)))
        (is (str/includes? (:body ui-memory-search) "Search Results"))
        (is (= 409 (:status graph-save)))
        (is (= 200 (:status channel-adapters)))
        (is (= ["discord" "slack" "telegram"] (mapv :name (:data channel-adapters-body))))
        (is (= 201 (:status created-agent)))
        (is (= 200 (:status agents)))
        (is (= [agent-id] (mapv :id (:data agents-body))))
        (is (= 200 (:status agent-msg)))
        (is (= "test-response" (get-in agent-msg-body [:response :content])))
        (is (= 201 (:status channel)))
        (is (= 201 (:status channel-post)))
        (is (= agent-id (:sender_id channel-post-body)))
        (is (= 200 (:status channels)))
        (is (= [channel-id] (mapv :id (:data channels-body))))
        (is (= 201 (:status created)))
        (is (= 201 (:status ui-created)))
        (is (str/includes? (:body ui-created) "ui-test"))
        (is (= 200 (:status session-detail)))
        (is (str/includes? (:body session-detail) session-id))
        (is (= 200 (:status session-messages)))
        (is (str/includes? (:body session-detail) "/ui/session-messages?session_id="))
        (is (= 200 (:status completion)))
        (is (= 200 (:status ui-chat)))
        (is (str/includes? (:body ui-chat) "test-response"))
        (is (= 200 (:status streamed)))
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
        (is (= "test-response" (get-in messages-body [:data 3 :content])))
        (is (= "hello" (get-in messages-body [:data 4 :content])))
        (is (= "hello world" (get-in messages-body [:data 5 :content])))
        (is (= ["user" "assistant" "user" "assistant" "user"]
               (mapv :role @messages*)))
        (is (= ["hello" "test-response" "hello ui" "test-response" "hello"]
               (mapv :content @messages*))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest api-run-stream-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        base-system (core/create-system)
        store (sqlite/create-store {:path path})
        event-bus (core/create-event-bus)
        event-sink (core/create-event-sink store event-bus)
        runtime-service (core/create-runtime-service store event-sink)
        system (assoc base-system
                      :llm-provider (->TestProvider (atom nil))
                      :store store
                      :event-bus event-bus
                      :event-sink event-sink
                      :runtime-service runtime-service
                      :runner-registry (core/create-runner-registry runtime-service)
                      :config (assoc (:config base-system)
                                     :api {:host "127.0.0.1" :port port}
                                     :storage {:sqlite {:path path}}))
        server (api/start-server! system {:host "127.0.0.1" :port port})]
    (try
      (let [run (core/request-run! system {:agent-id "stream-agent"
                                           :name "stream-run"
                                           :substrate :local-process
                                           :requested-by "tester"})
            run-id (:id run)
            stream-lines (read-sse-data-lines
                          (str base-url "/v1/runs/" run-id "/stream")
                          4
                          #(do
                             (core/register-run! system run-id
                                                 {:capabilities [:chat]
                                                  :network-identity {:logical-id "agent://stream"}})
                             (core/heartbeat-run! system run-id
                                                  {:sequence-no 1
                                                   :status :running
                                                   :metrics {:phase "boot"}
                                                   :lease-id (get-in (core/get-run system run-id) [:lease :id])})
                             (core/log-event! system
                                              {:event-type :agent.run.output
                                               :entity-type :agent_run
                                               :entity-id run-id
                                               :payload {:stream "stdout"
                                                         :line "hello from child"}})))]
        (is (= 4 (count stream-lines)))
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
