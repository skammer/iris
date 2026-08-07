(ns agent.cron.service
  "Persistent cron scheduler, CRUD API, claims, recovery, and worker lifecycle."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.cron.notification :as notification]
   [agent.cron.runner :as runner]
   [agent.cron.schedule :as schedule]
   [agent.cron.store :as store]
   [agent.llm.registry :as llm-registry]
   [agent.llm.service :as llm-service]
   [agent.logging :as logging]
   [agent.tools.core :as tools]
   [clojure.string :as str])
  (:import
   (java.time Duration Instant)
   (java.util UUID)
   (java.util.concurrent Executors ScheduledExecutorService TimeUnit)))

(defrecord CronService [system-ref store config owner-id running? scheduler workers health])

(defn create-service [system-ref store config]
  (let [max-concurrency (long (or (:max-concurrency config) 2))]
    (->CronService system-ref store config (str (UUID/randomUUID)) (atom false)
                   (atom nil) (atom nil)
                   (atom {:last-tick nil :last-error nil :next-wake nil
                          :worker-count max-concurrency}))))

(defn running? [service] (boolean (and service @(:running? service))))

(defn- current-system [service]
  (or @(:system-ref service)
      (throw (ex-info "cron system unavailable" {:type :cron-system-unavailable}))))

(defn- event! [service type entity-id payload]
  (when-let [sink (:event-sink (current-system service))]
    (sink {:event-type type :entity-type :cron-job :entity-id entity-id :payload payload})))

(defn- now [] (Instant/now))
(defn- required-text! [field value]
  (let [value* (some-> value str str/trim)]
    (when (str/blank? value*)
      (throw (ex-info (str (name field) " must be non-blank")
                      {:type :validation-failed :field field})))
    value*))

(defn- configured-pair! [system provider model]
  (let [registry (:llm-registry system)
        provider* (if (keyword? provider) provider (keyword provider))
        configured (llm-registry/provider registry provider*)
        model-ids (set (map :model-id (:models configured)))]
    (when-not (contains? model-ids model)
      (throw (ex-info "model is not configured for provider"
                      {:type :validation-failed :provider provider* :model model})))
    {:provider provider* :model model}))

(defn- resolve-model [service job]
  (let [system (current-system service)
        cron-cfg (:config service)
        override {:provider (or (:provider job) (:provider cron-cfg))
                  :model (or (:model job) (:model cron-cfg))}
        resolved (llm-service/resolve-provider-selection (:llm (:config system)) override)]
    (configured-pair! system (:provider resolved) (:model resolved))))

(defn- resolve-profile [service job]
  (let [system (current-system service)
        profile-key (or (:tool-profile job) (get-in service [:config :tool-profile]))
        profile (get-in system [:config :tools :profiles profile-key])
        messaging-tools (->> (tools/list-tools (:tool-registry system))
                             (filter #(= :messaging (:category %)))
                             (map :name)
                             set)
        forbidden (into #{:cronjob :cron_notify} messaging-tools)]
    (when-not profile
      (throw (ex-info "unknown cron tool profile"
                      {:type :validation-failed :tool-profile profile-key})))
    {:tool-profile profile-key
     :permissions (vec (:permissions profile))
     :allowed-tools (->> (:allowed-tools profile)
                         (remove forbidden)
                         vec)
     :allowed-actions (:allowed-actions profile)}))

(defn- initial-next [canonical timezone]
  (some-> (schedule/next-fire canonical timezone (now)) str))

(defn- normalize-job [service input existing]
  (let [merged (merge existing input)
        name (required-text! :name (:name merged))
        prompt (required-text! :prompt (:prompt merged))
        timezone (str (schedule/timezone! (or (:timezone merged) (:timezone (:config service)))))
        canonical (schedule/normalize (:schedule merged))
        max-occurrences (if (= :at (:kind canonical))
                          1
                          (:max-occurrences merged))
        _ (when (and max-occurrences (not (pos? (long max-occurrences))))
            (throw (ex-info "max-occurrences must be positive"
                            {:type :validation-failed :field :max-occurrences})))
        provider (:provider merged)
        model (:model merged)
        _ (when (not= (boolean provider) (boolean model))
            (throw (ex-info "provider and model must be pinned together"
                            {:type :validation-failed :field :provider})))
        _ (when provider (configured-pair! (current-system service) provider model))
        _ (resolve-profile service merged)
        notification* (notification/normalize (:notification merged) (:origin merged))]
    (assoc merged
           :name name :prompt prompt :timezone timezone :schedule canonical
           :notification notification* :provider (some-> provider keyword)
           :model model :max-occurrences max-occurrences)))

(defn preview [service input]
  (let [job (normalize-job service input nil)]
    (merge (schedule/preview (:schedule job) (:timezone job) (now))
           {:notification (:notification job)
            :resolved-model (resolve-model service job)
            :resolved-tools (resolve-profile service job)})))

(defn create-job! [service input context]
  (let [job (normalize-job service (assoc input :origin (:origin context)) nil)
        next-run-at (initial-next (:schedule job) (:timezone job))
        _ (when (and (= :at (get-in job [:schedule :kind])) (nil? next-run-at))
            (throw (ex-info "at schedule must be in the future"
                            {:type :validation-failed :field :schedule.at})))
        job* (assoc job :created-by (or (:created-by context) "operator")
                        :status :active
                        :next-run-at next-run-at)
        saved (store/create-job! (:store service) job*)]
    (event! service :cron.job.created (:id saved) {:name (:name saved) :revision (:revision saved)})
    saved))

(defn list-jobs [service opts] (store/list-jobs (:store service) opts))
(defn get-job [service id-or-name] (store/get-job (:store service) id-or-name))
(defn list-runs [service job-id limit] (store/list-runs (:store service) job-id limit))
(defn get-run [service run-id] (store/get-run (:store service) run-id))

(defn- job! [service id-or-name]
  (or (get-job service id-or-name)
      (throw (ex-info "cron job not found" {:type :not-found :id id-or-name}))))

(defn update-job! [service id-or-name expected-revision changes]
  (let [existing (job! service id-or-name)
        _ (when-not expected-revision
            (throw (ex-info "revision is required" {:type :validation-failed :field :revision})))
        merged (normalize-job service changes existing)
        next-run-at (if (= :active (:status merged))
                      (initial-next (:schedule merged) (:timezone merged)) nil)
        saved (store/update-job! (:store service) (:id existing) expected-revision
                                 (assoc merged :next-run-at next-run-at))]
    (event! service :cron.job.updated (:id saved) {:revision (:revision saved)})
    saved))

(defn set-status! [service id-or-name status expected-revision]
  (let [job (job! service id-or-name)
        next-run-at (when (= :active status)
                      (initial-next (:schedule job) (:timezone job)))
        _ (when (and (= :active status) (nil? next-run-at))
            (throw (ex-info "cron job schedule has no future occurrence"
                            {:type :job-not-runnable :id (:id job)})))
        saved (store/set-job-status! (:store service) (:id job) expected-revision status next-run-at)]
    (event! service (keyword (str "cron.job." (name (if (= :active status) :resumed status))))
            (:id job) {:revision (some-> saved :revision)})
    saved))

(defn- snapshot [service job]
  (let [model (resolve-model service job)
        profile (resolve-profile service job)
        notification (:notification job)
        allowed (cond-> (:allowed-tools profile)
                  (= :agent (:policy notification)) (conj :cron_notify))]
    (merge {:job-id (:id job) :job-revision (:revision job) :name (:name job)
            :prompt (:prompt job) :schedule (:schedule job) :timezone (:timezone job)
            :notification notification}
           model profile {:allowed-tools allowed
                          :policy-hash (format "%08x" (hash profile))})))

(declare submit-run!)

(defn run-now! [service id-or-name]
  (let [job (job! service id-or-name)
        _ (when-not (contains? #{:active :paused} (:status job))
            (throw (ex-info "only active or paused cron jobs can run"
                            {:type :job-not-runnable :id (:id job) :status (:status job)})))
        owner (when (running? service) (:owner-id service))
        run (store/claim-manual! (:store service) job {:owner-id owner :snapshot (snapshot service job)})]
    (event! service :cron.run.claimed (:id job) {:run-id (:id run) :trigger :manual})
    (when owner (submit-run! service run))
    run))

(defn- next-after-occurrence [job]
  (some-> (schedule/next-fire (:schedule job) (:timezone job)
                              (Instant/parse (:next-run-at job))) str))

(defn- claim-due! [service job]
  (let [next-run (next-after-occurrence job)
        occurrence (inc (:occurrence-count job))
        exhausted? (or (and (:max-occurrences job) (>= occurrence (:max-occurrences job)))
                       (nil? next-run))
        run (store/claim-scheduled! (:store service) job
                                    {:owner-id (:owner-id service)
                                     :snapshot (snapshot service job)
                                     :next-run-at (when-not exhausted? next-run)
                                     :terminal-status (when exhausted? :completed)})]
    (when run
      (event! service :cron.run.claimed (:id job)
              {:run-id (:id run) :trigger :scheduled :scheduled-for (:scheduled-for run)})
      (when exhausted?
        (event! service :cron.job.completed (:id job) {:run-id (:id run)})))
    run))

(defn submit-run! [service run]
  (when (and (= :claimed (:status run)) @(:workers service))
    (.submit ^java.util.concurrent.ExecutorService @(:workers service)
             ^Runnable #(runner/execute-safely! (current-system service) run)))
  run)

(defn- misfired? [service run]
  (> (.getSeconds (Duration/between (Instant/parse (:scheduled-for run)) (now)))
     (long (:misfire-grace-seconds (:config service)))))

(defn tick! [service]
  (when (running? service)
    (try
      (doseq [queued (store/queued-runs (:store service) (:max-concurrency (:config service)))
              :let [adopted (store/adopt-run! (:store service) (:id queued) (:owner-id service))]
              :when adopted]
        (submit-run! service adopted))
      (let [capacity (max 0 (- (long (:max-concurrency (:config service)))
                               (:running-runs (store/counts (:store service)))))]
        (doseq [job (store/due-jobs (:store service) (str (now)) capacity)
                :let [run (claim-due! service job)]
                :when run]
          (cond
            (= :skipped (:status run))
            (event! service :cron.run.skipped (:id job) {:run-id (:id run) :reason :overlap})

            (misfired? service run)
            (do (store/finish-run! (:store service) (:id run) :skipped {:error "missed scheduler grace window"
                                                                        :notification-status :suppressed})
                (event! service :cron.run.skipped (:id job) {:run-id (:id run) :reason :misfire}))

            :else (submit-run! service run))))
      (swap! (:health service) assoc :last-tick (str (now)) :last-error nil
             :next-wake (str (.plusSeconds (now) (:poll-interval-seconds (:config service)))))
      (catch Throwable e
        (swap! (:health service) assoc :last-tick (str (now)) :last-error (.getMessage e))
        (logging/log-error! :agent.cron/tick-failed e {})))))

(defn start! [service]
  (when (and service (:enabled (:config service)) (compare-and-set! (:running? service) false true))
    (store/abandon-stale! (:store service)
                          (str (.minusSeconds (now) (long (:run-timeout-seconds (:config service))))))
    (let [workers (Executors/newFixedThreadPool (int (:max-concurrency (:config service))))
          scheduler (Executors/newSingleThreadScheduledExecutor)]
      (reset! (:workers service) workers)
      (reset! (:scheduler service) scheduler)
      (.scheduleWithFixedDelay ^ScheduledExecutorService scheduler
                               ^Runnable #(tick! service) 0
                               (long (:poll-interval-seconds (:config service))) TimeUnit/SECONDS)))
  service)

(defn stop! [service]
  (when (and service (compare-and-set! (:running? service) true false))
    (some-> @(:scheduler service) .shutdownNow)
    (some-> @(:workers service) .shutdownNow)
    (reset! (:scheduler service) nil)
    (reset! (:workers service) nil)
    (store/abandon-active! (:store service) (:owner-id service)))
  service)

(defn health-check [service]
  (let [now* (now)
        oldest (when service (store/oldest-due-at (:store service) (str now*)))]
    (merge {:enabled (boolean (some-> service :config :enabled))
            :running (running? service)
            :oldest-due-at oldest
            :oldest-due-lag-seconds (when oldest
                                      (max 0 (.getSeconds (Duration/between (Instant/parse oldest) now*))))}
           (when service (store/counts (:store service)))
           (some-> service :health deref))))
