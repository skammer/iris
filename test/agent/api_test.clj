(ns agent.api-test
  (:require
   [agent.api.test-support :refer [free-port gunzip-string http-get http-get-headers http-post http-post-headers started-test-system temp-db-path]]
   [agent.api :as api]
   [agent.api.errors :as api-errors]
   [agent.api.helpers :as api-helpers]
   [agent.api.middleware :as api-middleware]
   [agent.api.responses :as api-responses]
   [agent.api.routes :as api-routes]
   [agent.api.schemas :as api-schemas]
   [agent.logging :as logging]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]))

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

(deftest api-middleware-gzips-large-text-responses-test
  (let [body (apply str (repeat 1000 "warm gray interface "))
        handler (api-middleware/wrap-gzip-response
                 (constantly {:status 200
                              :headers {"Content-Type" "text/html; charset=utf-8"
                                        "Content-Length" (str (count body))}
                              :body body}))
        compressed (handler {:headers {"accept-encoding" "gzip, deflate"}})
        plain (handler {:headers {}})]
    (is (= "gzip" (get-in compressed [:headers "Content-Encoding"])))
    (is (= "Accept-Encoding" (get-in compressed [:headers "Vary"])))
    (is (= body (gunzip-string (:body compressed))))
    (is (= body (:body plain)))
    (is (nil? (get-in plain [:headers "Content-Encoding"])))))

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
  (is (nil? (#'api/assert-route-bindings! (#'api/handler-map {})
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
  (let [system {:config {:api {:host "0.0.0.0" :port (free-port) :key nil}}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Refusing to bind API to a non-loopback host without :api :key"
                          (api/start-server! system (:api (:config system)))))))
