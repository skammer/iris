(ns agent.api.handlers.cron
  (:require
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.cron.service :as cron]))

(defn- service [system] (:cron-service system))
(defn- body [request]
  (let [value (h/read-json-body request)]
    (cond-> value
      (contains? value :tool_profile) (assoc :tool-profile (some-> (:tool_profile value) keyword))
      (contains? value :max_occurrences) (assoc :max-occurrences (:max_occurrences value)))))
(defn- path-id [request] (get-in request [:path-params :id]))
(defn- query [request] (or (:query-params request) {}))
(defn- job! [system id]
  (or (cron/get-job (service system) id)
      (throw (ex-info "cron job not found" {:type :not-found :id id}))))
(defn- require-run! [system id]
  (or (cron/get-run (service system) id)
      (throw (ex-info "cron run not found" {:type :not-found :id id}))))

(defn list-jobs [system request]
  (responses/json-response 200
    {:data (cron/list-jobs (service system)
                           (cond-> {}
                             (get (query request) "status")
                             (assoc :status (keyword (get (query request) "status")))))}))

(defn create-job [system request]
  (responses/json-response 201
    {:data (cron/create-job! (service system) (body request) {:created-by "api"})}))

(defn get-job [system request]
  (responses/json-response 200 {:data (job! system (path-id request))}))

(defn update-job [system request]
  (let [input (body request)]
    (responses/json-response 200
      {:data (cron/update-job! (service system) (path-id request) (:revision input)
                               (dissoc input :revision :tool_profile :max_occurrences))})))

(defn set-status [system request status]
  (responses/json-response 200
    {:data (cron/set-status! (service system) (path-id request) status (:revision (body request)))}))

(defn delete-job [system request]
  (set-status system request :deleted))

(defn run-job [system request]
  (responses/json-response 202 {:data (cron/run-now! (service system) (path-id request))}))

(defn list-runs [system request]
  (let [job (job! system (path-id request))
        limit (some-> (get (query request) "limit") parse-long)]
    (responses/json-response 200
      {:data (cron/list-runs (service system) (:id job) (or limit 50))})))

(defn get-run [system request]
  (responses/json-response 200 {:data (require-run! system (path-id request))}))

(defn status [system _request]
  (responses/json-response 200 {:data (cron/health-check (service system))}))

(defn preview [system request]
  (responses/json-response 200 {:data (cron/preview (service system) (body request))}))
