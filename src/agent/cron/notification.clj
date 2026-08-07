(ns agent.cron.notification
  "Adapter-neutral cron notification staging and delivery."
  (:require
   [agent.channels.core :as channels]
   [agent.cron.store :as store]
   [clojure.string :as str])
  (:import (java.time Instant)))

(def policies #{:never :always :agent})

(defn- emit! [system type run-id payload]
  (when-let [sink (:event-sink system)]
    (sink {:event-type type :entity-type :cron-run :entity-id run-id :request-id run-id
           :payload payload})))

(defn normalize
  ([notification] (normalize notification nil))
  ([notification origin]
   (let [policy (keyword (or (:policy notification) :never))
         target0 (:target notification)
         target (if (= :origin (some-> (:kind target0) keyword))
                  (when (and (:adapter origin) (:recipient origin))
                    {:kind :channel :adapter (:adapter origin) :recipient (:recipient origin)})
                  target0)
         target* (when target
                   {:kind :channel
                    :adapter (some-> (:adapter target) keyword)
                    :recipient (:recipient target)})]
     (when-not (contains? policies policy)
       (throw (ex-info "notification policy must be never, always, or agent"
                       {:type :validation-failed :field :notification.policy :value policy})))
     (when (and (not= :never policy)
                (or (nil? (:adapter target*)) (nil? (:recipient target*))))
       (throw (ex-info "notification target is required for this policy"
                       {:type :validation-failed :field :notification.target})))
     {:policy policy
      :target target*
      :notify-on-error (if (or (contains? notification :notify-on-error)
                               (contains? notification :notify_on_error))
                         (boolean (if (contains? notification :notify-on-error)
                                    (:notify-on-error notification)
                                    (:notify_on_error notification)))
                         (boolean target*))})))

(defn stage! [system run-id content]
  (let [content* (str/trim (str content))
        run (store/get-run (:store system) run-id)]
    (when-not run
      (throw (ex-info "cron run not found" {:type :not-found :run-id run-id})))
    (when-not (= :agent (some-> run :snapshot :notification :policy keyword))
      (throw (ex-info "cron_notify is unavailable for this run"
                      {:type :tool-blocked :run-id run-id})))
    (when-not (= :not-requested (:notification-status run))
      (throw (ex-info "cron notification can no longer be staged"
                      {:type :notification-state-conflict :run-id run-id})))
    (when (str/blank? content*)
      (throw (ex-info "notification content must be non-blank"
                      {:type :validation-failed :field :content})))
    (let [saved (store/update-notification! (:store system) run-id :staged {:content content*})]
      (emit! system :cron.notification.staged run-id {:content-chars (count content*)})
      saved)))

(defn deliver! [system run-id target content]
  (let [adapter-name (some-> (:adapter target) keyword)
        adapter (channels/get-adapter (:channel-adapter-registry system) adapter-name)]
    (when-not adapter
      (throw (ex-info "notification adapter is unavailable"
                      {:type :notification-adapter-unavailable :adapter adapter-name})))
    (store/update-notification! (:store system) run-id :pending {:content content :target target})
    (try
      (channels/send-channel-message!
       adapter
       (channels/create-send-message content (:recipient target)
                                     :metadata {:format :markdown :source :cron :run-id run-id}))
      (store/update-notification! (:store system) run-id :succeeded
                                  {:content content :target target})
      (emit! system :cron.notification.succeeded run-id {:adapter adapter-name})
      (catch Exception e
        (store/update-notification! (:store system) run-id :failed
                                    {:content content :target target :error (.getMessage e)})
        (emit! system :cron.notification.failed run-id
               {:adapter adapter-name :error (.getMessage e)})
        (throw e)))))

(defn dispatch-success! [system run output]
  (let [{:keys [policy target]} (get-in run [:snapshot :notification])
        policy* (some-> policy keyword)
        staged (some-> (store/get-run (:store system) (:id run)) :notification :content)
        content (case policy*
                  :always output
                  :agent staged
                  nil)]
    (cond
      (and content target) (deliver! system (:id run) target
                                     (str "**Cron · " (get-in run [:snapshot :name]) "**\n\n" content))
      (= :never policy*) (store/update-notification! (:store system) (:id run) :not-configured nil)
      (= :agent policy*) (do
                           (emit! system :cron.notification.suppressed (:id run) {:reason :not-requested})
                           (store/update-notification! (:store system) (:id run) :suppressed nil))
      :else (store/update-notification! (:store system) (:id run) :not-requested nil))))

(defn dispatch-error! [system run error-message]
  (let [{:keys [target notify-on-error]} (get-in run [:snapshot :notification])]
    (if (and target notify-on-error)
      (deliver! system (:id run) target
                (str "**Cron failed · " (get-in run [:snapshot :name]) "**\n\n"
                     "Run: `" (:id run) "`\n"
                     "Time: " (Instant/now) "\n"
                     "Error: " error-message))
      (do
        (emit! system :cron.notification.suppressed (:id run) {:reason :error-notification-disabled})
        (store/update-notification! (:store system) (:id run) :suppressed nil)))))
