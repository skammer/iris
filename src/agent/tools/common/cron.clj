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

(def ^:private schedule-schema
  [:or
   [:map {:closed true :title "Five-field cron schedule"}
    [:kind {:description "Schedule type."} [:enum :cron "cron"]]
    [:expression
     {:description "Five-field UNIX cron expression: minute hour day-of-month month day-of-week. Example: 0 9 * * 1-5."}
     [:string {:min 1}]]]
   [:map {:closed true :title "One-shot schedule"}
    [:kind {:description "Schedule type."} [:enum :at "at"]]
    [:at
     {:description "ISO-8601 UTC instant or relative value such as 'in 15m'."}
     [:string {:min 1}]]]
   [:map {:closed true :title "Interval schedule"}
    [:kind {:description "Schedule type."} [:enum :interval "interval"]]
    [:every-seconds
     {:description "Interval in seconds; minimum 60."}
     [:int {:min 60}]]
    [:anchor-at
     {:description "ISO-8601 UTC instant anchoring the interval."}
     [:string {:min 1}]]]])

(def ^:private notification-schema
  [:map {:closed true}
   [:policy
    {:description "never saves locally; always sends the final answer; agent sends only content staged with cron_notify."}
    [:or [:enum :never :always :agent] [:enum "never" "always" "agent"]]]
   [:target
    {:optional true
     :description "Use kind=origin to reply to the Telegram chat creating the job, or kind=channel with an explicit adapter and recipient."}
    [:maybe
     [:or
      [:map {:closed true}
       [:kind [:or [:enum :origin] [:enum "origin"]]]]
      [:map {:closed true}
       [:kind [:or [:enum :channel] [:enum "channel"]]]
       [:adapter [:or :keyword :string]]
       [:recipient [:or :int :string]]]]]]
   [:notify-on-error {:optional true} :boolean]])

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
     (str "Create, inspect, update, pause, resume, run, or delete persistent scheduled agent jobs. "
          "Cron schedules use schedule.expression, for example "
          "{\"kind\":\"cron\",\"expression\":\"0 9 * * 1-5\"}; never use schedule.cron or schedule.expr. "
          "Omit provider, model, and tool-profile to inherit configured cron defaults; set them only for a per-job override. "
          "For Telegram delivery from a Telegram chat, use notification "
          "{\"policy\":\"always\",\"target\":{\"kind\":\"origin\"}}; policy=never never sends a message.")
     :category :system
     :required-permissions #{:cron-read}
     :input-schema [:map {:closed true}
                    [:action [:or [:enum :list :get :history :create :update :pause :resume :run :delete :preview]
                              [:enum "list" "get" "history" "create" "update" "pause" "resume" "run" "delete" "preview"]]]
                    [:id {:optional true} [:maybe :string]]
                    [:name {:optional true} [:maybe :string]]
                    [:prompt {:optional true} [:maybe :string]]
                    [:schedule
                     {:optional true
                      :description "Typed schedule. Required for preview/create; include only fields for the selected kind."}
                     [:maybe schedule-schema]]
                    [:timezone {:optional true} [:maybe :string]]
                    [:notification {:optional true} [:maybe notification-schema]]
                    [:provider
                     {:optional true
                      :description "Per-job provider override. Omit together with model to inherit cron/global defaults."}
                     [:maybe :string]]
                    [:model
                     {:optional true
                      :description "Per-job model ID, without provider prefix. Set together with provider; omit both to inherit defaults."}
                     [:maybe :string]]
                    [:tool-profile
                     {:optional true
                      :description "Per-job tool profile override. Omit to inherit the configured cron tool profile."}
                     [:maybe :string]]
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
