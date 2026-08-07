(ns agent.cron.runner
  "Executes a claimed cron run through the normal persisted chat path."
  (:require
   [agent.chat :as chat]
   [agent.cron.notification :as notification]
   [agent.cron.store :as store]
   [agent.llm.service :as llm-service]
   [agent.logging :as logging]
   [agent.util :as util]
   [clojure.string :as str]))

(defn- emit! [system event-type run payload]
  (when-let [sink (:event-sink system)]
    (sink {:event-type event-type :entity-type :cron-run :entity-id (:id run)
           :request-id (:request-id run) :payload payload})))

(defn- bounded [value max-chars]
  (util/truncate (or value "") max-chars #(str "\n[truncated " % " chars]")))

(defn- terminal-error [result]
  (let [reason (:stop-reason result)
        approval-ids (keep :id (:approvals result))]
    (when-not (= :completed reason)
      (str "agent stopped with " (name (or reason :error))
           (when (seq approval-ids)
             (str "; approval IDs: " (str/join ", " approval-ids)))))))

(defn execute! [system run]
  (let [snapshot (:snapshot run)
        timeout-ms (* 1000 (long (get-in system [:config :cron :run-timeout-seconds] 1800)))
        cancellation (atom false)
        provider (llm-service/create-llm-provider-with-override
                  (:llm (:config system))
                  {:provider (some-> (:provider snapshot) keyword)
                   :model (:model snapshot)})
        allowed-tools (set (map #(if (keyword? %) % (keyword %)) (:allowed-tools snapshot)))
        allowed-actions (into {} (map (fn [[k v]] [(keyword k) (set (map keyword v))]))
                              (:allowed-actions snapshot))
        max-chars (long (get-in system [:config :cron :output-max-chars] 200000))]
    (store/mark-run-started! (:store system) (:id run))
    (emit! system :cron.run.started run {:job-id (:job-id run) :session-id (:session-id run)})
    (let [task (future
                 (chat/run! system
                   {:messages [{:role "user" :content (:prompt snapshot)}]
                    :session-id (:session-id run)
                    :request-id (:request-id run)
                    :cancellation-token cancellation
                    :model (:model snapshot)
                    :provider-config provider
                    :permission-profile :cron
                    :allowed-tools allowed-tools
                    :allowed-actions allowed-actions
                    :context {:cron-run-id (:id run)
                              :permissions (set (map keyword (:permissions snapshot)))
                              :allowed-tools allowed-tools
                              :allowed-actions allowed-actions}}))
          result (deref task timeout-ms ::timeout)]
      (if (= ::timeout result)
        (do
          (reset! cancellation true)
          (future-cancel task)
          (chat/cancel-session! system (:session-id run))
          (let [message (str "run timed out after " (quot timeout-ms 1000) " seconds")]
            (store/finish-run! (:store system) (:id run) :failed {:error message})
            (try
              (notification/dispatch-error! system run message)
              (catch Exception e
                (logging/log-error! :agent.cron/notification-failed e {:run-id (:id run)})))
            (emit! system :cron.run.failed run {:error message})))
        (let [output (bounded (:content result) max-chars)
              usage (:usage result)
              terminal-error* (terminal-error result)
              notification-status (if (= :never (some-> snapshot :notification :policy keyword))
                                    :not-configured
                                    (:notification-status (store/get-run (:store system) (:id run))))]
          (if terminal-error*
            (let [status (if (= :cancelled (:stop-reason result)) :cancelled :failed)]
              (store/finish-run! (:store system) (:id run) status
                                 {:output output :error terminal-error* :usage usage
                                  :notification-status notification-status})
              (try
                (notification/dispatch-error! system run terminal-error*)
                (catch Exception e
                  (logging/log-error! :agent.cron/notification-failed e {:run-id (:id run)})))
              (emit! system (if (= :cancelled status) :cron.run.cancelled :cron.run.failed)
                     run {:error terminal-error* :usage usage}))
            (do
              (store/finish-run! (:store system) (:id run) :succeeded
                                 {:output output :usage usage
                                  :notification-status notification-status})
              (try
                (notification/dispatch-success! system run output)
                (catch Exception e
                  (logging/log-error! :agent.cron/notification-failed e {:run-id (:id run)})))
              (emit! system :cron.run.succeeded run {:output-chars (count output) :usage usage})))))
      (store/get-run (:store system) (:id run)))))

(defn execute-safely! [system run]
  (try
    (execute! system run)
    (catch Throwable e
      (let [message (bounded (.getMessage e) 4000)]
        (try
          (store/finish-run! (:store system) (:id run) :failed {:error message})
          (catch Exception _))
        (try
          (notification/dispatch-error! system run message)
          (catch Exception notification-error
            (logging/log-error! :agent.cron/notification-failed notification-error {:run-id (:id run)})))
        (emit! system :cron.run.failed run {:error message})
        (logging/log-error! :agent.cron/run-failed e {:run-id (:id run) :job-id (:job-id run)})
        (store/get-run (:store system) (:id run))))))
