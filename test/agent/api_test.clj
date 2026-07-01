(ns agent.api-test
  (:require
   [agent.api :as api]
   [agent.api.errors :as api-errors]
   [agent.api.helpers :as api-helpers]
   [agent.api.middleware :as api-middleware]
   [agent.api.responses :as api-responses]
   [agent.api.routes :as api-routes]
   [agent.api.schemas :as api-schemas]
   [agent.config :as cfg]
   [agent.logging :as logging]
   [agent.system :as system]
   [agent.llm.core :as llm-core]
   [agent.llm.messages :as llm-messages]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.system.components :as components]
   [agent.system.events :as events]
   [agent.tools.service :as tool-service]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [malli.core :as m])
  (:import
   (java.io BufferedReader ByteArrayOutputStream InputStreamReader)
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

(defrecord BlockingProvider [started response calls messages*]
  llm-core/ILLMProvider
  (complete [_ messages _]
    (reset! messages* messages)
    "fallback-response")
  (stream [_ messages _]
    (reset! messages* messages)
    (async/to-chan! []))
  (embed [_ _ _] [0.1 0.2])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-tools true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ {:keys [messages] :as request}]
    (swap! calls inc)
    (reset! messages* messages)
    (deliver started true)
    (llm-core/normalize-llm-response @response request))
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

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

(defn http-post-multipart [url parts]
  (let [boundary (str "iris-boundary-" (System/nanoTime))
        out (ByteArrayOutputStream.)]
    (doseq [{:keys [name value filename content-type bytes]} parts]
      (.write out (.getBytes (str "--" boundary "\r\n") "UTF-8"))
      (.write out (.getBytes (str "Content-Disposition: form-data; name=\"" name "\""
                                  (when filename (str "; filename=\"" filename "\""))
                                  "\r\n")
                              "UTF-8"))
      (when content-type
        (.write out (.getBytes (str "Content-Type: " content-type "\r\n") "UTF-8")))
      (.write out (.getBytes "\r\n" "UTF-8"))
      (.write out (if bytes bytes (.getBytes (str value) "UTF-8")))
      (.write out (.getBytes "\r\n" "UTF-8")))
    (.write out (.getBytes (str "--" boundary "--\r\n") "UTF-8"))
    (let [request (-> (HttpRequest/newBuilder (URI/create url))
                      (.header "Content-Type" (str "multipart/form-data; boundary=" boundary))
                      (.POST (java.net.http.HttpRequest$BodyPublishers/ofByteArray
                              (.toByteArray out)))
                      .build)
          response (.send (http-client) request (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response)
       :body (.body response)})))

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

(defn eventually [f]
  (let [deadline (+ (System/currentTimeMillis) 3000)]
    (loop []
      (let [value (f)]
        (cond
          value value
          (< (System/currentTimeMillis) deadline) (do (Thread/sleep 25) (recur))
          :else false)))))

(defn started-test-system
  ([path port config-fn]
   (started-test-system path port config-fn (->TestProvider (atom nil))))
  ([path port config-fn provider]
   (let [base-system (system/create-system)
        store (sqlite/create-store {:path path})
        event-bus (events/create-event-bus)
        event-sink (events/create-event-sink store event-bus)
        config (-> (:config base-system)
                   (assoc :llm (:llm cfg/default-config)
                          :chat (:chat cfg/default-config))
                   (assoc :api {:host "127.0.0.1" :port port}
                          :storage {:sqlite {:path path}})
                   config-fn)
        system (assoc base-system
                      :llm-provider provider
                      :store store
                      :event-bus event-bus
                      :event-sink event-sink
                      :tool-registry (tool-service/create-tool-registry {:cfg (:tools config) :event-sink event-sink :store store})
                      :skills-registry (components/create-skills-registry (:skills config))
                      :memory-service (memory/create-memory-service (:memory config) store)
                      :config config)]
    {:system system
     :server (api/start-server! system {:host "127.0.0.1" :port port})})))

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
      (is (= 401 (:status (http-get (str base-url "/tasks")))))
      (is (= 401 (:status (http-post (str base-url "/message:send")
                                      {:message {:messageId "blocked"
                                                 :role "ROLE_USER"
                                                 :parts [{:text "blocked"}]}}))))
      (is (= 200 (:status (http-get (str base-url "/.well-known/agent-card.json")))))
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

(deftest api-middleware-request-id-and-500-safety-test
  (let [events (atom [])
        errors (atom [])
        ok-handler (api-middleware/wrap-defaults
                    (fn [_] {:status 204 :headers {} :body ""})
                    nil)
        failing-handler (api-middleware/wrap-defaults
                         (fn [_] (throw (Exception. "secret database path")))
                         nil)]
    (with-redefs [logging/log! (fn [event attrs]
                                 (swap! events conj [event attrs]))
                  logging/log-error! (fn [event error attrs]
                                       (swap! errors conj [event (.getMessage error) attrs]))]
      (let [ok-response (ok-handler {:request-method :get
                                     :uri "/health"
                                     :headers {"x-request-id" "rid-ok"}})
            failed-response (failing-handler {:request-method :get
                                              :uri "/boom"
                                              :headers {"x-request-id" "rid-fail"}})
            failed-body (json/parse-string (:body failed-response) true)]
        (is (= "rid-ok" (get-in ok-response [:headers "X-Request-Id"])))
        (is (= 500 (:status failed-response)))
        (is (= "rid-fail" (get-in failed-response [:headers "X-Request-Id"])))
        (is (= {:error "internal_error"
                :message "Internal server error"
                :request_id "rid-fail"}
               failed-body))
        (is (not (str/includes? (:body failed-response) "secret database path")))
        (is (some #(and (= :agent.http/request-started (first %))
                        (= "rid-ok" (get-in % [1 :request-id])))
                  @events))
        (is (some #(and (= :agent.http/request-completed (first %))
                        (= "rid-ok" (get-in % [1 :request-id])))
                  @events))
        (is (= [[:agent.http/request-failed
                 "secret database path"
                 {:method "get" :path "/boom" :request-id "rid-fail"}]]
               @errors))))))

(deftest api-malformed-json-is-controlled-bad-json-test
  (let [error (try
                (api-helpers/read-json-body {:body "{\"broken\""})
                nil
                (catch clojure.lang.ExceptionInfo e
                  e))
        response (api-responses/error-response error {:request-id "rid-json"})
        body (json/parse-string (:body response) true)]
    (is (= 400 (:status response)))
    (is (= "bad_json" (:error body)))
    (is (= "Malformed JSON body" (:message body)))))

(deftest api-route-handler-binding-check-test
  (is (nil? (#'api/assert-route-bindings! (#'api/handler-map (system/create-system))
                                          api-routes/routes)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"API route handler binding mismatch"
                        (#'api/assert-route-bindings! {:health identity}
                                                      [["/missing" {:get {:handler/id :missing}}]])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"API route handler binding mismatch"
                        (#'api/assert-route-bindings! {:extra identity} []))))

(deftest api-chat-schema-openai-compatible-messages-test
  (is (m/validate api-schemas/ChatMessage
                  {:role "assistant"
                   :tool_calls [{:id "call-1"
                                 :type "function"
                                 :function {:name "lookup"
                                            :arguments "{}"}}]}))
  (is (m/validate api-schemas/ChatMessage
                  {:role "tool"
                   :tool_call_id "call-1"
                   :name "lookup"
                   :content "ok"}))
  (is (m/validate api-schemas/ChatMessage
                  {:role "user"
                   :content [{:type "custom_payload"
                              :payload {:x 1}}]}))
  (is (not (m/validate api-schemas/ChatMessage
                       {:role "assistant"})))
  (is (not (m/validate api-schemas/ChatMessage
                       {:role "user"
                        :content [{:type "image_url"}]}))))

(deftest api-domain-error-mapping-expanded-test
  (let [cases [[:vault-read-only 403 "vault_read_only"]
               [:unknown-provider 404 "unknown_provider"]
               [:entry-not-found 404 "entry_not_found"]]]
    (doseq [[type status code] cases]
      (let [response (api-responses/error-response
                      (api-errors/domain-error->api-error
                       (ex-info "mapped" {:type type})))
            body (json/parse-string (:body response) true)]
        (is (= status (:status response)))
        (is (= code (:error body)))
        (is (= "mapped" (:message body)))))))

(deftest api-refuses-non-loopback-bind-without-key-test
  (let [base-system (system/create-system)
        system (assoc-in base-system [:config :api] {:host "0.0.0.0"
                                                     :port (free-port)
                                                     :key nil})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Refusing to bind API to a non-loopback host without :api :key"
                          (api/start-server! system (:api (:config system)))))))

(deftest session-mode-api-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system path port identity)]
    (try
      (let [created-body (-> (http-post (str base-url "/v1/sessions") {:title "mode"})
                             :body
                             (json/parse-string true))
            session-id (:id created-body)
            set-response (http-post (str base-url "/v1/sessions/" session-id "/mode")
                                    {:mode "code"})
            set-body (json/parse-string (:body set-response) true)
            clear-response (http-post (str base-url "/v1/sessions/" session-id "/mode")
                                      {:mode nil})
            clear-body (json/parse-string (:body clear-response) true)
            unknown-response (http-post (str base-url "/v1/sessions/" session-id "/mode")
                                        {:mode "missing"})
            unknown-body (json/parse-string (:body unknown-response) true)
            missing-response (http-post (str base-url "/v1/sessions/missing/mode")
                                        {:mode "code"})
            missing-body (json/parse-string (:body missing-response) true)]
        (is (= 200 (:status set-response)))
        (is (= "code" (get-in set-body [:data :active_mode])))
        (is (= "code" (get-in set-body [:data :state :active_mode])))
        (is (= 200 (:status clear-response)))
        (is (contains? (:data clear-body) :active_mode))
        (is (nil? (get-in clear-body [:data :active_mode])))
        (is (= 400 (:status unknown-response)))
        (is (= "unknown_mode" (:error unknown-body)))
        (is (some #{"code"} (get-in unknown-body [:details :available_modes])))
        (is (= 404 (:status missing-response)))
        (is (= "session_not_found" (:error missing-body))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest chat-completions-accepts-rich-media-content-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [system server]} (started-test-system
                                  path
                                  port
                                  #(assoc-in % [:memory :notes :extractor :enabled] false))]
    (try
      (let [response (http-post (str base-url "/v1/chat/completions")
                                {:messages [{:role "user"
                                             :content [{:type "text"
                                                        :text "inspect"}
                                                       {:type "image_url"
                                                        :image_url {:url "data:image/png;base64,aGVsbG8="}}
                                                       {:type "file"
                                                        :file {:file_data "AAAA"
                                                               :media-type "video/mp4"
                                                               :filename "clip.mp4"}}]}]})
            user-message (last (filter #(= "user" (:role %))
                                       @(:messages* (:llm-provider system))))]
        (is (= 200 (:status response)))
        (is (= [{:type :text :text "inspect"}
                {:type :image
                 :source {:type :base64
                          :value "aGVsbG8="
                          :media-type "image/png"}}
                {:type :video
                 :source {:type :base64
                          :value "AAAA"
                          :media-type "video/mp4"}
                 :filename "clip.mp4"}]
               (:content user-message))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest a2a-message-send-is-async-and-pollable-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        started (promise)
        response (promise)
        calls (atom 0)
        provider (->BlockingProvider started response calls (atom nil))
        {:keys [server]} (started-test-system
                           path
                           port
                           #(assoc-in % [:memory :notes :extractor :enabled] false)
                           provider)]
    (try
      (let [submitted (http-post (str base-url "/message:send")
                                 {:message {:messageId "msg-a2a-1"
                                            :role "ROLE_USER"
                                            :parts [{:text "do async"}]}})
            submitted-body (json/parse-string (:body submitted) true)
            task-id (get-in submitted-body [:task :id])
            context-id (get-in submitted-body [:task :contextId])]
        (is (= 200 (:status submitted)))
        (is (string? task-id))
        (is (string? context-id))
        (is (= "TASK_STATE_SUBMITTED" (get-in submitted-body [:task :status :state])))
        (is (true? (deref started 1000 false)))
        (is (eventually
             #(let [body (-> (http-get (str base-url "/tasks/" task-id))
                             :body
                             (json/parse-string true))]
                (= "TASK_STATE_WORKING" (get-in body [:task :status :state])))))
        (deliver response "async-final")
        (let [completed (eventually
                         #(let [body (-> (http-get (str base-url "/tasks/" task-id "?historyLength=2"))
                                         :body
                                         (json/parse-string true))]
                            (when (= "TASK_STATE_COMPLETED" (get-in body [:task :status :state]))
                              body)))]
          (is completed)
          (is (= "async-final"
                 (get-in completed [:task :artifacts 0 :parts 0 :text])))
          (is (= ["do async" "async-final"]
                 (mapv #(get-in % [:parts 0 :text])
                       (get-in completed [:task :history]))))
          (is (= 1 @calls))))
      (finally
        (deliver response "late")
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest a2a-message-send-is-idempotent-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        started (promise)
        response (promise)
        calls (atom 0)
        provider (->BlockingProvider started response calls (atom nil))
        payload {:message {:messageId "msg-a2a-idem"
                           :role "ROLE_USER"
                           :parts [{:text "same task"}]}}
        {:keys [server]} (started-test-system
                           path
                           port
                           #(assoc-in % [:memory :notes :extractor :enabled] false)
                           provider)]
    (try
      (let [first-body (-> (http-post-headers (str base-url "/message:send")
                                              payload
                                              {"Idempotency-Key" "idem-1"})
                           :body
                           (json/parse-string true))
            second-body (-> (http-post-headers (str base-url "/message:send")
                                               payload
                                               {"Idempotency-Key" "idem-1"})
                            :body
                            (json/parse-string true))]
        (is (= (get-in first-body [:task :id])
               (get-in second-body [:task :id])))
        (is (true? (deref started 1000 false)))
        (deliver response "done once")
        (is (eventually #(= 1 @calls))))
      (finally
        (deliver response "late")
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest a2a-task-cancel-marks-task-canceled-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        started (promise)
        response (promise)
        provider (->BlockingProvider started response (atom 0) (atom nil))
        {:keys [server]} (started-test-system
                           path
                           port
                           #(assoc-in % [:memory :notes :extractor :enabled] false)
                           provider)]
    (try
      (let [submitted-body (-> (http-post (str base-url "/message:send")
                                          {:message {:messageId "msg-a2a-cancel"
                                                     :role "ROLE_USER"
                                                     :parts [{:text "wait"}]}})
                               :body
                               (json/parse-string true))
            task-id (get-in submitted-body [:task :id])
            _ (is (true? (deref started 1000 false)))
            cancel-body (-> (http-post (str base-url "/tasks/" task-id ":cancel") {})
                            :body
                            (json/parse-string true))]
        (is (= "TASK_STATE_CANCELED" (get-in cancel-body [:task :status :state])))
        (is (= "Task canceled"
               (get-in cancel-body [:task :status :message :parts 0 :text]))))
      (finally
        (deliver response "late")
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest a2a-rejects-non-text-parts-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system path port identity)]
    (try
      (let [response (http-post (str base-url "/message:send")
                                {:message {:messageId "msg-a2a-file"
                                           :role "ROLE_USER"
                                           :parts [{:raw "AAAA"
                                                    :mediaType "application/octet-stream"}]}})
            body (json/parse-string (:body response) true)]
        (is (= 400 (:status response)))
        (is (= "CONTENT_TYPE_NOT_SUPPORTED"
               (get-in body [:error :details 0 :reason]))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest slash-commands-api-lists-skill-catalog-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        root (.toFile (java.nio.file.Files/createTempDirectory "iris-api-skills-" (make-array java.nio.file.attribute.FileAttribute 0)))
        skill-dir (io/file root "review")
        _ (.mkdirs skill-dir)
        _ (spit (io/file skill-dir "SKILL.md")
                "---\nname: review\ndescription: Review code\n---\n# Review\n")
        {:keys [system server]} (started-test-system path port #(assoc-in % [:skills :dirs] [(.getAbsolutePath root)]))]
    (try
      (let [response (http-get (str base-url "/v1/slash-commands?prefix=rev&page=1&page_size=10"))
            body (json/parse-string (:body response) true)]
        (is (= 200 (:status response)))
        (is (= "iris.slash_commands_page" (:object body)))
        (is (= 1 (:total body)))
        (is (= "review" (get-in body [:items 0 :name]))))
      (finally
        (api/stop-server! server)
        (sqlite/close-store! (:store system))
        (io/delete-file (io/file skill-dir "SKILL.md") true)
        (.delete skill-dir)
        (.delete root)
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
      (let [denied (http-post (str base-url "/v1/tools/fs_list/execute")
                              {:input {:path "."}
                               :permissions ["filesystem-read"]})]
        (is (= 403 (:status denied))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest ui-chat-streams-partial-patch-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system path port identity)]
    (try
      (let [created (http-post (str base-url "/v1/sessions") {:title "stream"})
            session-id (:id (json/parse-string (:body created) true))
            response (http-post-form (str base-url "/ui/chat")
                                     (str "session_id=" session-id "&prompt=hello"))
            body (:body response)
            stream-idx (str/index-of body "message--streaming")
            final-idx (str/last-index-of body "hello world")]
        (is (= 200 (:status response)))
        (is (some? stream-idx))
        (is (some? final-idx))
        (is (< stream-idx final-idx)))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest ui-chat-accepts-image-upload-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [system server]} (started-test-system
                                  path
                                  port
                                  #(assoc-in % [:memory :notes :extractor :enabled] false))]
    (try
      (let [created (http-post (str base-url "/v1/sessions") {:title "upload"})
            session-id (:id (json/parse-string (:body created) true))
            image-bytes (.getBytes "image-bytes" "UTF-8")
            response (http-post-multipart
                      (str base-url "/ui/chat")
                      [{:name "session_id" :value session-id}
                       {:name "prompt" :value "inspect"}
                       {:name "image"
                        :filename "photo.png"
                        :content-type "image/png"
                        :bytes image-bytes}])
            user-message (last (filter #(= "user" (:role %))
                                       @(:messages* (:llm-provider system))))]
        (is (= 200 (:status response)))
        (is (= [{:type :text :text "inspect"}
                {:type :image
                 :source {:type :base64
                          :media-type "image/png"
                          :value (.encodeToString (java.util.Base64/getEncoder)
                                                  image-bytes)}
                 :filename "photo.png"
                 :alt "photo.png"}]
               (:content user-message))))
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
      (let [write-denied (http-post (str base-url "/v1/tools/fs_write/execute")
                                    {:input {:path "target/api-tool-policy-test.txt"
                                             :content "blocked"}})
            write-denied-body (json/parse-string (:body write-denied) true)]
        (is (= 403 (:status write-denied)))
        (is (= "approval_required" (:error write-denied-body))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest api-tool-approval-permissions-are-validated-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system
                           path
                           port
                           #(-> %
                                (assoc-in [:tools :permissions :api] [])
                                (assoc-in [:tools :policy :blocklist] [])))]
    (try
      (let [fake-approval-read (http-post (str base-url "/v1/tools/fs_list/execute")
                                          {:input {:path "."}
                                           :approval_id "missing-approval"})
            shell-approval-create (http-post (str base-url "/v1/tool-approvals")
                                             {:tool "shell"
                                              :input {:argv ["printf" "approved-api"]}
                                              :requested_by "api"})
            shell-approval-id (get-in (json/parse-string (:body shell-approval-create) true) [:data :id])
            _shell-approval-approve (http-post (str base-url "/v1/tool-approvals/" shell-approval-id "/approve")
                                               {:actor "tester"})
            shell-approved-exec (http-post (str base-url "/v1/tools/shell/execute")
                                           {:input {:argv ["printf" "approved-api"]}
                                            :approval_id shell-approval-id})
            shell-approved-body (json/parse-string (:body shell-approved-exec) true)]
        (is (= 404 (:status fake-approval-read)))
        (is (= 200 (:status shell-approved-exec)))
        (is (= "approved-api" (get-in shell-approved-body [:data :stdout]))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest api-domain-errors-and-normalized-provider-models-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [system server]} (started-test-system
                                  path
                                  port
                                  #(-> %
                                       (assoc-in [:memory :vault :writable?] false)))]
    (try
      (let [session-id (:id (json/parse-string
                             (:body (http-post (str base-url "/v1/sessions") {:title "branches"}))
                             true))
            _ (events/log-event! system {:event-type :test.one
                                         :entity-type :test
                                         :entity-id "one"
                                         :payload {}})
            _ (events/log-event! system {:event-type :test.two
                                         :entity-type :test
                                         :entity-id "two"
                                         :payload {}})
            vault-missing (http-post (str base-url "/v1/memory/vault/read")
                                     {:path "memory/missing.md"})
            vault-read-only (http-post (str base-url "/v1/memory/vault/write")
                                       {:path "memory/blocked.md"
                                        :content "blocked"})
            unknown-provider-health (http-get (str base-url "/v1/providers/missing/health"))
            unknown-provider-models (http-get (str base-url "/v1/providers/missing/models"))
            provider-models (json/parse-string
                             (:body (http-get (str base-url "/v1/providers/ollama/models")))
                             true)
            missing-leaf (http-post (str base-url "/v1/sessions/" session-id "/leaf")
                                    {:entry_id "missing-entry"})
            limited-events (json/parse-string
                            (:body (http-get (str base-url "/v1/events?limit=1")))
                            true)]
        (is (= 404 (:status vault-missing)))
        (is (= 403 (:status vault-read-only)))
        (is (= 404 (:status unknown-provider-health)))
        (is (= 404 (:status unknown-provider-models)))
        (is (contains? (first (:data provider-models)) :model_id))
        (is (not (contains? (first (:data provider-models)) :model-id)))
        (is (= 404 (:status missing-leaf)))
        (is (= 1 (count (:data limited-events)))))
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
        event-bus (events/create-event-bus)
        event-sink (events/create-event-sink store event-bus)
        config (-> (:config base-system)
                   (assoc :llm (:llm cfg/default-config)
                          :chat (:chat cfg/default-config))
	                   (assoc-in [:memory :notes :extractor :enabled] false))
        system (assoc base-system
                      :llm-provider (->TestProvider messages*)
                      :store store
                      :event-bus event-bus
                      :event-sink event-sink
	                      :tool-registry (tool-service/create-tool-registry
	                                      {:cfg (assoc-in (:tools config) [:http :allow-private?] true)
	                                       :event-sink event-sink
	                                       :store store})
	                      :memory-service (memory/create-memory-service (:memory config) store)
                      :config (assoc config
                                     :api {:host "127.0.0.1" :port port}
                                     :storage {:sqlite {:path path}}))
        server (api/start-server! system {:host "127.0.0.1" :port port})]
    (try
      (let [bad-session (http-post (str base-url "/v1/sessions") {:title 42})
            bad-chat (http-post (str base-url "/v1/chat/completions")
                                {:messages [{:role "bogus" :content "hello"}]})
            ui-index (http-get base-url)
            ui-dashboard (http-get (str base-url "/ui/dashboard"))
            ui-operator-board (http-get (str base-url "/ui/operator-board"))
            health (http-get (str base-url "/health"))
            health-body (json/parse-string (:body health) true)
            tools (http-get (str base-url "/v1/tools"))
            tools-body (json/parse-string (:body tools) true)
            tool-exec (http-post (str base-url "/v1/tools/fs_list/execute")
                                 {:input {:path "."}
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
            ui-prompt-memory (http-get (str base-url "/ui/memory/prompt"))
            old-memory-search (http-post (str base-url "/v1/memory/search")
                                         {:query "hello"})
            memory-recall (http-post (str base-url "/v1/memory/recall")
                                     {:query "hello"})
            memory-recall-body (json/parse-string (:body memory-recall) true)
            old-fact-save (http-post (str base-url "/v1/memory/facts")
                                     {:subject "alice"
                                      :predicate "likes"
                                      :object "clojure"
                                      :scope {:type "global"}})
            old-fact-search (http-post (str base-url "/v1/memory/facts/search")
                                       {:query "alice"
                                        :scope {:type "global"}})
	            ui-memory-search (http-post-form (str base-url "/ui/memory/search")
	                                             "query=hello")
	            channel-adapters (http-get (str base-url "/v1/channel-adapters"))
            channel-adapters-body (json/parse-string (:body channel-adapters) true)
            created (http-post (str base-url "/v1/sessions") {:title "api-test"})
            created-body (json/parse-string (:body created) true)
            session-id (:id created-body)
            ui-chat-page (http-get (str base-url "/chat/" session-id))
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
        (is (= 200 (:status ui-index)))
        (is (str/includes? (:body ui-index) "datastar.js"))
        (is (= 200 (:status ui-chat-page)))
        (is (str/includes? (:body ui-chat-page) (str "/ui/shell?tab=chat&amp;session_id=" session-id)))
        (is (= 200 (:status ui-dashboard)))
        (is (str/includes? (:body ui-dashboard) "Runtime Snapshot"))
        (is (str/includes? (:body ui-dashboard) "Pending approvals"))
        (is (= 200 (:status ui-operator-board)))
        (is (str/includes? (:body ui-operator-board) "Operator Board"))
        (is (str/includes? (:body ui-operator-board) "Approval queue"))
        (is (str/includes? (:body ui-operator-board) "Kernel receipts"))
        (is (= 200 (:status health)))
        (is (= 13 (get-in health-body [:tools :count])))
        (is (= true (get-in health-body [:memory :healthy])))
        (is (= 1 (get-in health-body [:channel-adapters :count])))
        (is (map? (:health-snapshot health-body)))
        (is (= "ok" (get-in health-body [:health-snapshot :components :api :status])))
        (is (= "ok" (get-in health-body [:health-snapshot :components :sqlite :status])))
        (is (integer? (get-in health-body [:health-snapshot :components :runtime :restart-count])))
        (is (map? (get-in health-body [:sse :metrics])))
        (is (integer? (get-in health-body [:sse :metrics :opened])))
        (is (= 200 (:status tools)))
        (is (= ["fs_create" "fs_delete" "fs_list" "fs_mkdir" "fs_read" "fs_replace" "fs_write"
                "http" "shell" "todo_get" "todo_list" "todo_search" "todo_write"]
               (mapv :name (:data tools-body))))
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
        (is (every? (set (mapv :name (:data skills-body)))
                    ["memory-vault" "dream" "distill"]))
        (is (= 200 (:status memory-surfaces)))
	        (is (= ["search" "vault"] (mapv :name (:data memory-surfaces-body))))
        (is (= 404 (:status prompt-memory)))
        (is (= 404 (:status ui-prompt-memory)))
        (is (= 404 (:status old-memory-search)))
        (is (= 200 (:status memory-recall)))
        (is (= "hello" (:query memory-recall-body)))
        (is (vector? (:results memory-recall-body)))
        (is (= 404 (:status old-fact-save)))
        (is (= 404 (:status old-fact-search)))
        (is (= 200 (:status ui-memory-search)))
        (is (str/includes? (:body ui-memory-search) "Recall Results"))
        (is (= 200 (:status channel-adapters)))
        (is (= ["telegram"] (mapv :name (:data channel-adapters-body))))
        (is (= 201 (:status created)))
        (is (= 200 (:status ui-created)))
        (is (str/includes? (:body ui-created) "datastar-patch-elements"))
        (is (str/includes? (:body ui-created) "Untitled session"))
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
        (is (some #(str/includes? % "\"event_type\":\"agent-start\"") streamed-lines))
        (is (some #(str/includes? % "\"content\":\"hello world\"") streamed-lines))
        (is (= "[DONE]" (last streamed-lines)))
        (is (= 200 (:status events)))
        (is (some #{"session.created"} (map :event_type (:data events-body))))
        (is (some #{"message-end"} (map :event_type (:data events-body))))
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
