(ns agent.api.handlers.ui.cron
  "Cron editor actions and live status fragments."
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.cron.service :as cron-service]
   [agent.ui :as ui]
   [agent.ui.cron :as ui-cron]
   [clojure.string :as str]
   [agent.ui.render]
   [agent.api.handlers.ui.params :as params]))

(defn cron [system request]
  (let [query (-> request :parameters :query)
        limit (params/request-ui-limit request 20 100)
        tab (some-> (:tab query) keyword)
        view (some-> (:view query) keyword)]
    (responses/html-response 200 (ui/cron-fragment system {:limit limit
                                                            :tab tab
                                                            :view view
                                                            :date (:date query)}))))

(defn cron-status [system request]
  (let [query (-> request :parameters :query)
        limit (params/request-ui-limit request 20 100)
        tab (or (some-> (:tab query) keyword) :jobs)
        view (or (some-> (:view query) keyword) :week)]
    (responses/html-response 200 (ui-cron/status-fragment system limit tab view (:date query)))))

(defn cron-job-detail [system _request job-id]
  (responses/html-response
   200
   (ui-cron/job-editor-detail-fragment
    system
    (cron-service/get-job (:cron-service system) job-id))))

(defn cron-run-detail [system _request run-id]
  (responses/html-response
   200
   (ui-cron/run-detail-fragment
    (cron-service/get-run (:cron-service system) run-id))))

(defn- cron-form-schedule [{:keys [schedule_kind cron_expression at every_seconds anchor_at]}]
  (case schedule_kind
    "at" {:kind :at :at at}
    "interval" {:kind :interval :every-seconds (parse-long every_seconds)
                 :anchor-at (or (not-empty anchor_at) (str (java.time.Instant/now)))}
    {:kind :cron :expression cron_expression}))

(defn- cron-model-pin [model-pair]
  (when-not (str/blank? model-pair)
    (let [[provider model] (str/split model-pair #"\|" 2)]
      {:provider (keyword provider) :model model})))

(defn cron-create [system request]
  (let [{:keys [name prompt timezone max_occurrences tool_profile model_pair
                notify_policy telegram_recipient] :as body} (h/read-form-body request)
        target (when-not (str/blank? telegram_recipient)
                 {:kind :channel :adapter :telegram :recipient telegram_recipient})
        input (cond-> (merge {:name name :prompt prompt :timezone timezone
                       :schedule (cron-form-schedule body)
                       :notification {:policy (keyword (or notify_policy "never")) :target target}}
                             (cron-model-pin model_pair))
                (not (str/blank? max_occurrences)) (assoc :max-occurrences (parse-long max_occurrences))
                (not (str/blank? tool_profile)) (assoc :tool-profile (keyword tool_profile)))]
    (cron-service/create-job! (:cron-service system) input {:created-by "ui"})
    (responses/html-response 200 (ui/cron-fragment system {:tab :jobs}))))

(defn cron-preview [system request]
  (let [{:keys [name prompt timezone max_occurrences tool_profile model_pair
                notify_policy telegram_recipient] :as body} (h/read-form-body request)
        target (when-not (str/blank? telegram_recipient)
                 {:kind :channel :adapter :telegram :recipient telegram_recipient})
        input (cond-> (merge {:name name :prompt prompt :timezone timezone
                       :schedule (cron-form-schedule body)
                       :notification {:policy (keyword (or notify_policy "never")) :target target}}
                             (cron-model-pin model_pair))
                (not (str/blank? max_occurrences)) (assoc :max-occurrences (parse-long max_occurrences))
                (not (str/blank? tool_profile)) (assoc :tool-profile (keyword tool_profile)))
        preview (cron-service/preview (:cron-service system) input)]
    (responses/html-response
     200
     (agent.ui.render/render
      [:div#cron-preview.cron-preview
       [:strong "Next runs"]
       [:ol (for [instant (:next-runs preview)] [:li instant])]
       [:small (str (get-in preview [:resolved-model :provider]) "/"
                    (get-in preview [:resolved-model :model]) " · "
                    (get-in preview [:resolved-tools :tool-profile]))]]))))

(defn- cron-update! [service id revision body]
  (let [{:keys [name prompt timezone max_occurrences tool_profile
                model_pair notify_policy telegram_recipient]} body
        target (when-not (str/blank? telegram_recipient)
                 {:kind :channel :adapter :telegram :recipient telegram_recipient})
        changes (merge {:name name :prompt prompt :timezone timezone
                        :schedule (cron-form-schedule body)
                        :notification {:policy (keyword (or notify_policy "never")) :target target}
                        :max-occurrences (when-not (str/blank? max_occurrences)
                                           (parse-long max_occurrences))
                        :tool-profile (when-not (str/blank? tool_profile) (keyword tool_profile))}
                       (or (cron-model-pin model_pair) {:provider nil :model nil}))]
    (cron-service/update-job! service id revision changes)))

(defn cron-action [system request]
  (let [{:keys [id revision action cron_tab] :as body} (h/read-form-body request)
        service (:cron-service system)
        revision* (parse-long revision)]
    (case action
      "pause" (cron-service/set-status! service id :paused revision*)
      "resume" (cron-service/set-status! service id :active revision*)
      "delete" (cron-service/set-status! service id :deleted revision*)
      "run" (cron-service/run-now! service id)
      "update" (cron-update! service id revision* body)
      "update-run" (let [job (cron-update! service id revision* body)]
                     (cron-service/run-now! service (:id job)))
      (throw (errors/api-error 400 "bad_request" "Unknown cron action")))
    (responses/html-response
     200
     (ui/cron-fragment system
                       {:tab (if (contains? #{"run" "update-run"} action)
                               :runs
                               (or (some-> cron_tab keyword) :jobs))}))))
