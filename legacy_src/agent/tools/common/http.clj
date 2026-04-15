(ns agent.tools.common.http
  "HTTP/REST API tool implementation.
  Provides tools for making HTTP requests to external APIs."
  (:require
   [agent.tools.core :as tool-core]
   [clj-http.client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.walk :as walk])
  (:import
   (java.net URLEncoder)
   (java.time Instant)))

;; ======================
;; HTTP Tool
;; ======================

(defrecord HTTPTool [config metrics]
  tool-core/ITool
  (execute [this input context]
    (let [{:keys [method url headers params body timeout-ms]
           :or {method :get timeout-ms 30000}} input
          request-opts {:method method
                        :url url
                        :headers (merge (:default-headers config) headers)
                        :query-params params
                        :body (when body (json/generate-string body))
                        :content-type :json
                        :accept :json
                        :socket-timeout timeout-ms
                        :conn-timeout timeout-ms
                        :throw-exceptions false}
          start-time (System/currentTimeMillis)
          response (http/request request-opts)
          execution-time (- (System/currentTimeMillis) start-time)]
      
      ;; Update metrics
      (swap! metrics update :total-requests inc)
      (swap! metrics update :total-execution-time-ms + execution-time)
      
      (if (<= 200 (:status response) 299)
        (do
          (swap! metrics update :successful-requests inc)
          {:status (:status response)
           :headers (:headers response)
           :body (try
                   (json/parse-string (:body response) true)
                   (catch Exception _
                     (:body response)))
           :execution-time-ms execution-time})
        (do
          (swap! metrics update :failed-requests inc)
          (throw (tool-core/tool-error :http-error
                                       (str "HTTP request failed: " (:status response))
                                       {:status (:status response)
                                        :body (:body response)
                                        :url url
                                        :method method}))))))
  
  (describe [this]
    (tool-core/create-tool-description
     :http-tool
     "HTTP/REST API client tool"
     "1.0.0"
     {:type "object"
      :properties {:method {:type "string"
                            :enum ["get" "post" "put" "patch" "delete" "head"]
                            :default "get"}
                   :url {:type "string"
                         :format "uri"}
                   :headers {:type "object"
                             :additionalProperties {:type "string"}}
                   :params {:type "object"
                            :additionalProperties {:type ["string" "number" "boolean"]}}
                   :body {:type ["object" "array" "string" "number" "boolean"]}
                   :timeout-ms {:type "integer"
                                :minimum 1000
                                :maximum 60000
                                :default 30000}}
      :required ["url"]}
     {:type "object"
      :properties {:status {:type "integer"}
                   :headers {:type "object"}
                   :body {:type ["object" "array" "string" "number" "boolean"]}
                   :execution-time-ms {:type "integer"}}}
     :category :api
     :permissions #{:http-request}
     :timeout-ms 60000))
  
  (validate-input [this input]
    (tool-core/validate-with-schema
     {:type "object"
      :properties {:method {:type "string"}
                   :url {:type "string"}
                   :headers {:type "object"}
                   :params {:type "object"}
                   :body {:type ["object" "array" "string" "number" "boolean"]}
                   :timeout-ms {:type "integer"}}
      :required ["url"]}
     input))

  tool-core/IToolWithPermissions
  (required-permissions [this]
    #{:http-request})
  
  (check-permissions [this context]
    (contains? (:permissions context) :http-request))

  tool-core/IToolWithMonitoring
  (get-metrics [this]
    (let [m @metrics
          total (:total-requests m 0)
          successful (:successful-requests m 0)]
      {:total-requests total
       :successful-requests successful
       :failed-requests (- total successful)
       :avg-latency-ms (if (pos? total)
                         (/ (:total-execution-time-ms m 0) total)
                         0)
       :last-updated (Instant/now)}))
  
  (health-check [this]
    {:healthy true
     :details "HTTP tool is healthy"
     :last-checked (Instant/now)})

  tool-core/IToolWithConfiguration
  (update-config [this new-config]
    (->HTTPTool (merge config new-config) metrics))
  
  (get-config [this]
    {:default-headers (:default-headers config)
     :timeout-ms (:timeout-ms config 30000)
     :max-retries (:max-retries config 3)
     :follow-redirects (:follow-redirects config true)}))

;; ======================
;; Helper Functions
;; ======================

(defn create-http-tool
  "Create an HTTP/REST API tool.
  Options:
  - :default-headers (optional, map of default headers)
  - :timeout-ms (optional, default 30000)
  - :max-retries (optional, default 3)
  - :follow-redirects (optional, default true)"
  [opts]
  (->HTTPTool (merge {:default-headers {"User-Agent" "Clojure-Agent/1.0"}
                      :timeout-ms 30000
                      :max-retries 3
                      :follow-redirects true}
                     opts)
              (atom {:total-requests 0
                     :successful-requests 0
                     :failed-requests 0
                     :total-execution-time-ms 0})))

(defn execute-with-retry
  "Execute HTTP request with retry logic."
  [tool input context & {:keys [max-retries retry-delay-ms]
                         :or {max-retries 3 retry-delay-ms 1000}}]
  (loop [attempt 1]
    (let [result (try
                   (tool-core/execute tool input context)
                   (catch Exception e
                     (if (>= attempt max-retries)
                       (throw e)
                       e)))]
      (cond
        (not (instance? Exception result)) result
        :else (do
                (Thread/sleep (* attempt retry-delay-ms))
                (recur (inc attempt))))))

(defn create-api-client
  "Create a pre-configured API client tool for a specific API."
  [base-url default-headers]
  (create-http-tool
   {:default-headers (merge {"User-Agent" "Clojure-Agent/1.0"}
                            default-headers)
    :base-url base-url}))

(defn url-encode-params
  "URL encode query parameters."
  [params]
  (str/join "&"
            (map (fn [[k v]]
                   (str (URLEncoder/encode (name k))
                        "="
                        (URLEncoder/encode (str v))))
                 params)))

;; ======================
;; Specialized API Tools
;; ======================

(defrecord GitHubAPITool [config metrics]
  tool-core/ITool
  (execute [this input context]
    (let [{:keys [endpoint method params body]
           :or {method :get endpoint "/"}} input
          url (str "https://api.github.com" endpoint)
          headers {"Authorization" (str "Bearer " (:api-token config))
                   "Accept" "application/vnd.github.v3+json"}
          http-tool (create-http-tool {:default-headers headers})]
      (tool-core/execute http-tool
                         {:method method
                          :url url
                          :params params
                          :body body}
                         context)))
  
  (describe [this]
    (tool-core/create-tool-description
     :github-api
     "GitHub API client"
     "1.0.0"
     {:type "object"
      :properties {:endpoint {:type "string"
                              :default "/"}
                   :method {:type "string"
                            :enum ["get" "post" "put" "patch" "delete"]
                            :default "get"}
                   :params {:type "object"}
                   :body {:type ["object" "array"]}}
      :required ["endpoint"]}
     {:type "object"
      :properties {:status {:type "integer"}
                   :body {:type ["object" "array"]}}}
     :category :api
     :permissions #{:github-access}
     :timeout-ms 30000))
  
  (validate-input [this input]
    (tool-core/validate-with-schema
     {:type "object"
      :properties {:endpoint {:type "string"}
                   :method {:type "string"}
                   :params {:type "object"}
                   :body {:type ["object" "array"]}}
      :required ["endpoint"]}
     input)))

(defn create-github-tool
  "Create GitHub API tool."
  [api-token]
  (->GitHubAPITool {:api-token api-token}
                   (atom {:total-requests 0})))

;; ======================
;; Example Usage
;; ======================

(comment
  ;; Create HTTP tool
  (def http-tool (create-http-tool
                  {:default-headers {"User-Agent" "Clojure-Agent/1.0"
                                     "Accept" "application/json"}}))
  
  ;; Execute HTTP request
  (def context (tool-core/create-execution-context
                "user-1"
                #{:http-request}))
  
  (tool-core/execute http-tool
                     {:method "get"
                      :url "https://api.example.com/data"
                      :params {:limit 10}
                      :timeout-ms 5000}
                     context)
  
  ;; Get tool description
  (tool-core/describe http-tool)
  
  ;; Validate input
  (tool-core/validate-input http-tool
                            {:url "https://api.example.com/data"})
  
  ;; Check permissions
  (tool-core/check-permissions http-tool
                               {:permissions #{:http-request}})
  
  ;; Get metrics
  (tool-core/get-metrics http-tool)
  
  ;; Health check
  (tool-core/health-check http-tool)
  
  ;; Update configuration
  (def updated-tool (tool-core/update-config http-tool
                                             {:timeout-ms 10000}))
  
  ;; Get configuration
  (tool-core/get-config http-tool)
  
  ;; Execute with retry
  (execute-with-retry http-tool
                      {:method "get"
                       :url "https://api.example.com/data"}
                      context
                      :max-retries 3
                      :retry-delay-ms 1000)
  
  ;; Create specialized API client
  (def github-tool (create-github-tool "github-token"))
  
  (tool-core/execute github-tool
                     {:endpoint "/user/repos"
                      :params {:per_page 10}}
                     context)
  
  ;; Error handling
  (try
    (tool-core/execute http-tool
                       {:url "https://invalid-url.example.com"}
                       context)
    (catch agent.tools.core.ToolError e
      (println "HTTP error:" (.getMessage e))))
  
  ;; Safe execution
  (tool-core/safe-execute http-tool
                          {:url "https://api.example.com/data"}
                          context)
  
  ;; Create API client for specific service
  (def weather-tool (create-api-client
                     "https://api.weather.com/v1"
                     {"X-API-Key" "weather-api-key"})))