(ns agent.tools.common.cron
  "Agent-facing cron management and run-scoped notification tools."
  (:require
   [agent.cron.notification :as notification]
   [agent.cron.service :as cron]
   [agent.tools.core :as tools]
   [clojure.string :as str])
  (:import (java.time Instant)))

(def actions #{:list :get :history :create :update :pause :resume :run :delete :preview})
(def read-actions #{:list :get :history :preview})

(defn- normalize-action [value]
  (if (keyword? value) value (some-> value str/lower-case keyword)))

(defn- require-permission! [context permission]
  (when-not (contains? (:permissions context) permission)
    (throw (tools/permission-error #{permission} (:permissions context)))))

(defn- origin [context]
  (when-let [recipient (:telegram-chat-id context)]
    {:adapter :telegram :recipient recipient}))

(defn- relative-at [input]
  (let [value (get-in input [:schedule :at])]
    (if-let [[_ amount unit] (and (string? value)
                                  (re-matches #"(?i)in\s+(\d+)(s|m|h|d)" value))]
      (let [seconds (* (parse-long amount) ({"s" 1 "m" 60 "h" 3600 "d" 86400}
                                             (str/lower-case unit)))]
        (assoc-in input [:schedule :at] (str (.plusSeconds (Instant/now) seconds))))
      input)))

(defn- require-job! [service id]
  (or (cron/get-job service id)
      (throw (ex-info "cron job not found" {:type :not-found :id id}))))

(defn create-cronjob-tool [service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :cronjob
     "Create, inspect, update, pause, resume, run, or delete persistent scheduled agent jobs."
     :category :system
     :required-permissions #{:cron-read}
     :input-schema [:map {:closed true}
                    [:action [:or [:enum :list :get :history :create :update :pause :resume :run :delete :preview]
                              [:enum "list" "get" "history" "create" "update" "pause" "resume" "run" "delete" "preview"]]]
                    [:id {:optional true} [:maybe :string]]
                    [:name {:optional true} [:maybe :string]]
                    [:prompt {:optional true} [:maybe :string]]
                    [:schedule {:optional true} [:maybe :map]]
                    [:timezone {:optional true} [:maybe :string]]
                    [:notification {:optional true} [:maybe :map]]
                    [:provider {:optional true} [:maybe :string]]
                    [:model {:optional true} [:maybe :string]]
                    [:tool-profile {:optional true} [:maybe :string]]
                    [:max-occurrences {:optional true} [:maybe :int]]
                    [:revision {:optional true} [:maybe :int]]
                    [:status {:optional true} [:maybe :string]]
                    [:limit {:optional true} [:maybe :int]]]
     :operation :act
     :action-key :action
     :read-only-actions read-actions
     :parallel-safe-actions read-actions
     :approval-sensitive? true
     :sensitive (fn [input] (not (contains? read-actions (normalize-action (:action input)))))
     :source :builtin)
    :validate-fn
    (fn [input]
      (let [action (normalize-action (:action input))]
        (when-not (contains? actions action)
          (throw (tools/validation-error "unsupported cronjob action" {:action action})))
        (assoc input :action action)))
    :execute-fn
    (fn [{:keys [action id revision status limit] :as input} context]
      (require-permission! context (if (contains? read-actions action) :cron-read :cron-manage))
      (case action
        :list (cron/list-jobs service (cond-> {} status (assoc :status (keyword status))))
        :get (require-job! service id)
        :history (let [job (when id (require-job! service id))]
                   (cron/list-runs service (:id job) (or limit 50)))
        :preview (cron/preview service (relative-at input))
        :create (cron/create-job! service (dissoc (relative-at input) :action :id :revision :limit :status)
                                  {:created-by (or (:user context) "agent")
                                   :origin (origin context)})
        :update (cron/update-job! service id revision (dissoc (relative-at input) :action :id :revision :limit :status))
        :pause (cron/set-status! service id :paused revision)
        :resume (cron/set-status! service id :active revision)
        :run (cron/run-now! service id)
        :delete (cron/set-status! service id :deleted revision)))}))

(defn create-cron-notify-tool [service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :cron_notify
     "Stage one notification for the current cron run. Destination is fixed by the job."
     :category :messaging
     :required-permissions #{}
     :input-schema [:map {:closed true} [:content :string]]
     :operation :act
     :approval-sensitive? false
     :source :builtin)
    :validate-fn
    (fn [input]
      (when (str/blank? (:content input))
        (throw (tools/validation-error "content must be non-blank" {})))
      input)
    :execute-fn
    (fn [{:keys [content]} context]
      (let [run-id (:cron-run-id context)]
        (when-not run-id
          (throw (tools/tool-error :tool-blocked "cron_notify is only available inside a cron run" {})))
        (notification/stage! (or @(:system-ref service)
                                 (throw (ex-info "cron system unavailable" {:type :cron-system-unavailable})))
                             run-id content)
        {:staged true :run-id run-id}))}))
