(ns agent.cron.cli
  "Parser and renderer for `iris cron` subcommands."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.cron.service :as cron]
   [clj-http.client :as http]
   [clojure.string :as str])
  (:import (java.time Instant)))

(defn- fail! [message & [data]]
  (throw (ex-info message (merge {:type :invalid-cli-args} data))))

(defn- parse-options [args]
  (loop [remaining args result {}]
    (if-not (seq remaining)
      result
      (let [flag (first remaining)]
        (when-not (str/starts-with? flag "--")
          (fail! (str "unexpected argument: " flag)))
        (let [value (second remaining)]
          (when (or (nil? value) (str/starts-with? value "--"))
            (fail! (str flag " requires a value")))
          (recur (nnext remaining)
                 (assoc result (keyword (subs flag 2)) value)))))))

(defn- duration-seconds [value]
  (let [[_ amount unit] (re-matches #"(?i)(\d+)(s|m|h|d)" (or value ""))
        multiplier ({"s" 1 "m" 60 "h" 3600 "d" 86400} (some-> unit str/lower-case))]
    (when-not multiplier (fail! "duration must look like 60s, 15m, 2h, or 1d"))
    (* (parse-long amount) multiplier)))

(defn- at-instant [value]
  (if-let [[_ duration] (re-matches #"(?i)in\s+(\d+(?:s|m|h|d))" (or value ""))]
    (str (.plusSeconds (Instant/now) (duration-seconds duration)))
    value))

(defn- target [value]
  (when value
    (let [[adapter recipient] (str/split value #":" 2)]
      (when (or (str/blank? adapter) (str/blank? recipient))
        (fail! "target must look like telegram:<chat-id>"))
      {:kind :channel :adapter (keyword adapter) :recipient recipient})))

(defn- schedule [opts]
  (let [choices (filter #(contains? opts %) [:cron :at :every])]
    (when-not (= 1 (count choices))
      (fail! "exactly one of --cron, --at, or --every is required"))
    (case (first choices)
      :cron {:kind :cron :expression (:cron opts)}
      :at {:kind :at :at (at-instant (:at opts))}
      :every {:kind :interval :every-seconds (duration-seconds (:every opts))
              :anchor-at (or (:anchor-at opts) (str (Instant/now)))})))

(defn- job-input [opts require-schedule?]
  (cond-> {}
    (:name opts) (assoc :name (:name opts))
    (:prompt opts) (assoc :prompt (:prompt opts))
    (or require-schedule? (some #(contains? opts %) [:cron :at :every])) (assoc :schedule (schedule opts))
    (:timezone opts) (assoc :timezone (:timezone opts))
    (:notify opts) (assoc :notification {:policy (keyword (:notify opts))
                                         :target (target (:target opts))
                                         :notify-on-error (not= "false" (:notify-on-error opts))})
    (:provider opts) (assoc :provider (keyword (:provider opts)))
    (:model opts) (assoc :model (:model opts))
    (:tool-profile opts) (assoc :tool-profile (keyword (:tool-profile opts)))
    (:max-occurrences opts) (assoc :max-occurrences (parse-long (:max-occurrences opts)))))

(defn- print-value! [value]
  (binding [*print-namespace-maps* false] (prn value)))

(defn- daemon-status [system]
  (let [{:keys [host port key]} (get-in system [:config :api])
        host* (if (#{"0.0.0.0" "::"} host) "127.0.0.1" host)
        headers (cond-> {} key (assoc "Authorization" (str "Bearer " key)))]
    (try
      (let [response (http/get (str "http://" host* ":" port "/v1/cron/status")
                               {:accept :json :as :json :headers headers
                                :throw-exceptions false :socket-timeout 1500 :conn-timeout 1500})]
        (if (= 200 (:status response))
          (:data (:body response))
          (assoc (cron/health-check (:cron-service system))
                 :daemon-unreachable true :http-status (:status response))))
      (catch Exception e
        (assoc (cron/health-check (:cron-service system))
               :daemon-unreachable true :error (.getMessage e))))))

(defn run! [system args]
  (let [[subcommand id & tail] args
        service (:cron-service system)]
    (case subcommand
      "list" (let [opts (parse-options (cond-> tail id (cons id)))]
               (print-value! (cron/list-jobs service (cond-> {} (:status opts) (assoc :status (keyword (:status opts)))))))
      "get" (print-value! (cron/get-job service (or id (fail! "cron get requires id or name"))))
      "create" (let [opts (parse-options (cond-> tail id (cons id)))]
                 (print-value! (cron/create-job! service (job-input opts true) {:created-by "cli"})))
      "update" (let [id* (or id (fail! "cron update requires id or name"))
                       opts (parse-options tail)]
                   (print-value! (cron/update-job! service id* (some-> (:revision opts) parse-long)
                                                   (job-input opts false))))
      "pause" (let [opts (parse-options tail)]
                  (print-value! (cron/set-status! service id :paused (some-> (:revision opts) parse-long))))
      "resume" (let [opts (parse-options tail)]
                   (print-value! (cron/set-status! service id :active (some-> (:revision opts) parse-long))))
      "delete" (let [opts (parse-options tail)]
                   (print-value! (cron/set-status! service id :deleted (some-> (:revision opts) parse-long))))
      "run" (print-value! (cron/run-now! service (or id (fail! "cron run requires id or name"))))
      "runs" (let [opts (parse-options tail)
                    job (when id (cron/get-job service id))]
                 (print-value! (cron/list-runs service (:id job) (or (some-> (:limit opts) parse-long) 50))))
      "status" (print-value! (daemon-status system))
      (fail! "cron command must be list, get, create, update, pause, resume, run, delete, runs, or status"))))
