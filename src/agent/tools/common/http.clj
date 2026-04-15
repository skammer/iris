(ns agent.tools.common.http
  "Rewritten HTTP tool."
  (:require
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]))

(def ^:private allowed-methods
  #{:get :post :put :patch :delete :head})

(defn- normalize-method [method]
  (cond
    (keyword? method) method
    (string? method) (keyword (str/lower-case method))
    :else nil))

(defn- parse-response-body [body]
  (try
    (json/parse-string body true)
    (catch Exception _
      body)))

(defn- validate-input [input]
  (let [url (:url input)
        method (normalize-method (or (:method input) :get))]
    (when-not (and (string? url) (not (str/blank? url)))
      (throw (tools/validation-error "url must be a non-blank string" {:input input})))
    (when-not (allowed-methods method)
      (throw (tools/validation-error "method must be a supported HTTP verb" {:method (:method input)})))
    (-> input
        (assoc :url url)
        (assoc :method method))))

(defn create-http-tool
  [opts]
  (let [config (merge {:default-headers {"User-Agent" "clj-agent/0.1"}
                       :timeout-ms 30000}
                      opts)]
    (tools/create-tool
     {:description
      (tools/create-tool-description
       :http
       "HTTP client tool"
       :category :api
       :timeout-ms (:timeout-ms config)
       :required-permissions #{:http-request}
       :input-schema {:required [:url]
                      :optional [:method :headers :params :body :timeout-ms]})
      :validate-fn validate-input
      :health-fn (fn []
                   {:healthy true
                    :details {:timeout-ms (:timeout-ms config)}})
      :execute-fn
      (fn [input _context]
        (let [timeout-ms (or (:timeout-ms input) (:timeout-ms config))
              request-opts {:method (:method input)
                            :url (:url input)
                            :headers (merge (:default-headers config) (:headers input))
                            :query-params (:params input)
                            :socket-timeout timeout-ms
                            :conn-timeout timeout-ms
                            :throw-exceptions false}
              request-opts (cond-> request-opts
                             (contains? input :body)
                             (assoc :body (json/generate-string (:body input))
                                    :content-type :json
                                    :accept :json))
              response (http/request request-opts)
              status (:status response)
              parsed-body (some-> (:body response) parse-response-body)]
          (if (<= 200 status 299)
            {:status status
             :headers (:headers response)
             :body parsed-body}
            (throw (tools/tool-error
                    :http-error
                    (str "HTTP request failed: " status)
                    {:status status
                     :body parsed-body
                     :url (:url input)
                     :method (:method input)})))))})))
