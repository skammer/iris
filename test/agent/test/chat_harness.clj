(ns agent.test.chat-harness
  (:require
   [agent.api :as api]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.runs.service :as runs]
   [agent.system :as system]
   [agent.system.components :as components]
   [agent.system.events :as events]
   [agent.test.predictable :as predictable]
   [agent.tools.service :as tool-service]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                  HttpResponse$BodyHandlers)))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-harness-" ".db")))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- client []
  (HttpClient/newHttpClient))

(defn- post-json [url payload]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString
                            (json/generate-string payload)))
                    .build)
        response (.send (client) request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn- get-json [url]
  (let [request (.build (HttpRequest/newBuilder (URI/create url)))
        response (.send (client) request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn start! []
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        base-system (system/create-system)
        store (sqlite/create-store {:path path})
        event-bus (events/create-event-bus)
        event-sink (events/create-event-sink store event-bus)
        runtime-service (runs/create-runtime-service store event-sink)
        provider (predictable/create-provider)
        config (-> (:config base-system)
                   (assoc :api {:host "127.0.0.1" :port port}
                          :storage {:sqlite {:path path}})
                   (assoc-in [:memory :facts :extractor :enabled] false)
                   (assoc-in [:llm :providers :predictable]
                             {:provider :predictable
                              :type :test
                              :model "predictable"})
                   (assoc-in [:llm :active-provider] :predictable))
        system (assoc base-system
                      :llm-provider provider
                      :fact-llm-provider provider
                      :store store
                      :event-bus event-bus
                      :event-sink event-sink
                      :tool-registry (tool-service/create-tool-registry (:tools config) event-sink store)
                      :memory-service (memory/create-memory-service (:memory config) store)
                      :runtime-service runtime-service
                      :runner-registry (runs/create-runner-registry runtime-service)
                      :orchestrator (components/create-orchestrator (:orchestrator config) event-sink)
                      :config config)
        server (api/start-server! system {:host "127.0.0.1" :port port})]
    {:path path
     :port port
     :base-url base-url
     :provider provider
     :store store
     :system system
     :server server}))

(defn stop! [{:keys [server store path]}]
  (when server (api/stop-server! server))
  (when store (sqlite/close-store! store))
  (when path (io/delete-file path true)))

(defn create-session! [h title]
  (let [response (post-json (str (:base-url h) "/v1/sessions") {:title title})
        body (json/parse-string (:body response) true)]
    (or (get-in body [:data :id])
        (:id body))))

(defn send-chat! [h session-id prompt]
  (let [response (post-json (str (:base-url h) "/v1/chat/completions")
                            {:session_id session-id
                             :prompt prompt})
        body (json/parse-string (:body response) true)]
    {:status (:status response) :body body}))

(defn send-chat-async! [h session-id prompt]
  (future (send-chat! h session-id prompt)))

(defn stop-session! [h session-id]
  (let [response (post-json (str (:base-url h) "/v1/chat/stop")
                            {:session_id session-id})]
    {:status (:status response)
     :body (json/parse-string (:body response) true)}))

(defn list-messages [h session-id]
  (let [response (get-json (str (:base-url h) "/v1/sessions/" session-id "/messages"))]
    (get-in (json/parse-string (:body response) true) [:data])))

(defn session-state [h session-id]
  (let [response (get-json (str (:base-url h) "/v1/sessions/" session-id))]
    (get-in (json/parse-string (:body response) true) [:data :state])))

(defn sse-data-lines [body]
  (->> (str/split-lines body)
       (filter #(str/starts-with? % "data: "))
       (map #(subs % 6))
       vec))

(defn stream-chat! [h session-id prompt]
  (let [response (post-json (str (:base-url h) "/v1/chat/completions")
                            {:session_id session-id
                             :prompt prompt
                             :stream true})]
    (sse-data-lines (:body response))))

(defn wait-response [h session-id]
  (loop [remaining 80]
    (let [assistant (last (filter #(= "assistant" (:role %))
                                  (list-messages h session-id)))]
      (if assistant
        (:content assistant)
        (do
          (when-not (pos? remaining)
            (throw (ex-info "Timed out waiting for assistant response"
                            {:session-id session-id})))
          (Thread/sleep 25)
          (recur (dec remaining)))))))

(defn wait-tool-event [h session-id]
  (loop [remaining 80]
    (let [event (some #(when (= "tool-execution-end" (:event-type %)) %)
                      (sqlite/list-events (:store h)
                                          {:entity-type :session
                                           :entity-id session-id
                                           :limit 100}))]
      (if event
        event
        (do
          (when-not (pos? remaining)
            (throw (ex-info "Timed out waiting for tool event"
                            {:session-id session-id})))
          (Thread/sleep 25)
          (recur (dec remaining)))))))
