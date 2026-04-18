(ns agent.runtime.control-client
  "HTTP control-plane client used by isolated child runtimes."
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.walk :as walk]))

(defn create-client
  [{:keys [base-url token]}]
  (when-not (and (string? base-url) (not (str/blank? base-url)))
    (throw (ex-info "AGENT_CONTROL_URL missing" {:type :missing-control-url})))
  (when-not (and (string? token) (not (str/blank? token)))
    (throw (ex-info "AGENT_BOOTSTRAP_TOKEN missing" {:type :missing-control-token})))
  {:base-url (str/replace base-url #"/+$" "")
   :token token})

(defn- normalize-key [k]
  (cond
    (keyword? k) (keyword (str/replace (name k) "_" "-"))
    (string? k) (keyword (str/replace k "_" "-"))
    :else k))

(defn- normalize-response [value]
  (walk/postwalk
   (fn [node]
     (if (map? node)
       (update-keys node normalize-key)
       node))
   value))

(defn- request!
  ([client method path] (request! client method path nil))
  ([{:keys [base-url token]} method path body]
   (let [response (http/request
                   (cond-> {:method method
                            :url (str base-url path)
                            :headers {"Authorization" (str "Bearer " token)}
                            :accept :json
                            :as :json
                            :throw-exceptions false
                            :socket-timeout 30000
                            :connection-timeout 10000}
                     (some? body)
                     (assoc :content-type :json
                            :body (json/generate-string body))))
         status (:status response)]
     (when-not (<= 200 status 299)
       (throw (ex-info "control-plane request failed"
                       {:type :control-request-failed
                        :status status
                        :body (:body response)
                        :path path})))
     (normalize-response (:body response)))))

(defn register-run! [client run-id registration]
  (get (request! client :post (str "/v1/runs/" run-id "/control/register") registration) :data))

(defn heartbeat! [client run-id heartbeat]
  (get (request! client :post (str "/v1/runs/" run-id "/control/heartbeat") heartbeat) :data))

(defn checkpoint! [client run-id checkpoint]
  (get (request! client :post (str "/v1/runs/" run-id "/control/checkpoint") checkpoint) :data))

(defn pending-commands [client run-id]
  (get (request! client :get (str "/v1/runs/" run-id "/control/commands")) :data))

(defn acknowledge-command! [client run-id command-id]
  (get (request! client :post (str "/v1/runs/" run-id "/control/commands/" command-id "/ack") {}) :data))

(defn complete-command! [client run-id command-id status error]
  (get (request! client
                 :post
                 (str "/v1/runs/" run-id "/control/commands/" command-id "/complete")
                 {:status (name status)
                  :error error})
       :data))

(defn transition-run! [client run-id status & [opts]]
  (get (request! client
                 :post
                 (str "/v1/runs/" run-id "/control/transition")
                 (merge {:status (name status)} opts))
       :data))
