(ns agent.ui.cron
  "Server-rendered Cron workspace."
  (:require
   [agent.cron.service :as cron]
   [agent.ui.render :as render]))

(defn- schedule-label [{:keys [kind expression at every-seconds]}]
  (case (some-> kind keyword)
    :cron expression
    :at (str "once · " at)
    :interval (str "every " every-seconds "s")
    "-"))

(defn- target-label [notification]
  (let [target (:target notification)]
    (if target
      (str (name (keyword (:adapter target))) ":" (:recipient target))
      "local only")))

(defn- action-form [job action label]
  [:form.cron-inline-form
   {:method "post"
    "data-on:submit" "@post('/ui/cron/action', {contentType: 'form', selector: el})"}
   [:input {:type "hidden" :name "id" :value (:id job)}]
   [:input {:type "hidden" :name "revision" :value (:revision job)}]
   [:input {:type "hidden" :name "action" :value action}]
   [:button.btn-small {:type "submit"} label]])

(defn- jobs-table [jobs]
  [:div.cron-table-wrap.scroll-fade
   [:table.ui-table.cron-table
    [:thead [:tr
             [:th "Job"] [:th "Status"] [:th "Schedule"] [:th "Next"]
             [:th "Last"] [:th "Delivery"] [:th "Actions"]]]
    [:tbody
     (if (seq jobs)
       (for [job jobs]
         [:tr {:class (when (= :active (:status job)) "cron-row--active")}
          [:td [:strong (:name job)] [:small (str "r" (:revision job) " · " (:id job))]]
          [:td [:span.status-badge {:class (str "status-badge--" (name (:status job)))}
                (name (:status job))]]
          [:td [:span (schedule-label (:schedule job))] [:small (:timezone job)]]
          [:td (or (:next-run-at job) "—")]
          [:td [:span (or (some-> (:last-run-status job) name) "—")]
           [:small (or (:last-run-at job) "never")]]
          [:td [:span (name (keyword (get-in job [:notification :policy] :never)))]
           [:small (target-label (:notification job))]]
          [:td.cron-actions
           (if (= :active (:status job))
             (action-form job "pause" "Pause")
             (action-form job "resume" "Resume"))
           (action-form job "run" "Run now")
           (action-form job "delete" "Delete")]])
       [:tr [:td {:colspan 7} "No cron jobs."]])]]])

(defn- configured-model-pairs [system]
  (for [[provider {:keys [models]}] (get-in system [:llm-registry :providers])
        model models]
    [(str (name provider) "|" (:model-id model))
     (str (name provider) "/" (:model-id model))]))

(defn- default-model-label [system]
  (let [cron-cfg (get-in system [:config :cron])
        llm-cfg (get-in system [:config :llm])
        provider (or (:provider cron-cfg) (:active-provider llm-cfg))
        model (or (:model cron-cfg) (get-in llm-cfg [:providers provider :model]))]
    (str "Cron default (" (name provider) "/" model ")")))

(defn- job-editor-detail-node [system job]
  (let [profiles (keys (get-in system [:config :tools :profiles]))
        model-pairs (configured-model-pairs system)]
    [:div.cron-job-editor__detail {:id (str "cron-job-detail-" (:id job))}
     [:form.cron-edit-form
       {:method "post"
        "data-on:submit" "@post('/ui/cron/action', {contentType: 'form', selector: el})"}
       [:input {:type "hidden" :name "action" :value "update"}]
       [:input {:type "hidden" :name "id" :value (:id job)}]
       [:input {:type "hidden" :name "revision" :value (:revision job)}]
       [:input {:type "hidden" :name "schedule_kind" :value (name (get-in job [:schedule :kind]))}]
       (when (= :interval (get-in job [:schedule :kind]))
         [:input {:type "hidden" :name "anchor_at" :value (get-in job [:schedule :anchor-at])}])
       [:div.cron-form-grid
        [:label [:span "Name"] [:input {:name "name" :value (:name job) :required true}]]
        [:label [:span "Timezone"] [:input {:name "timezone" :value (:timezone job) :required true}]]
        (case (get-in job [:schedule :kind])
          :cron [:label [:span "Cron expression"] [:input {:name "cron_expression" :value (get-in job [:schedule :expression])}]]
          :at [:label [:span "Once at"] [:input {:name "at" :value (get-in job [:schedule :at])}]]
          :interval [:label [:span "Every seconds"] [:input {:name "every_seconds" :type "number" :min "60"
                                                               :value (get-in job [:schedule :every-seconds])}]]
          nil)
        [:label [:span "Occurrence limit"]
         [:input {:name "max_occurrences" :type "number" :min "1" :value (:max-occurrences job)}]]
        [:label [:span "Tool profile"]
         [:select {:name "tool_profile"}
          [:option {:value "" :selected (nil? (:tool-profile job))} "Cron default"]
          (for [profile profiles]
            [:option {:value (name profile) :selected (= profile (:tool-profile job))} (name profile)])]]
        [:label [:span "Model"]
         [:select {:name "model_pair"}
          [:option {:value "" :selected (nil? (:provider job))} (default-model-label system)]
          (for [[value label] model-pairs]
            [:option {:value value
                      :selected (= value (str (some-> (:provider job) name) "|" (:model job)))} label])]]
        [:label [:span "Notify"]
         [:select {:name "notify_policy"}
          (for [policy [:never :always :agent]]
            [:option {:value (name policy)
                      :selected (= policy (keyword (get-in job [:notification :policy] :never)))}
             (name policy)])]]
        [:label [:span "Telegram chat ID"]
         [:input {:name "telegram_recipient" :value (get-in job [:notification :target :recipient])}]]
        [:label.cron-prompt [:span "Prompt"] [:textarea {:name "prompt" :rows 5 :required true} (:prompt job)]]]
       [:div.cron-editor__actions [:button {:type "submit"} "Save changes"]]]]))

(defn job-editor-detail-fragment [system job]
  (render/render
   (if job
     (job-editor-detail-node system job)
     [:div#cron-job-detail-missing.cron-job-editor__detail "Cron job not found."])))

(defn- job-editors [jobs]
  [:section.cron-editors
   (for [job jobs]
     [:details.cron-job-editor
      {:id (str "cron-job-editor-" (:id job))
       "data-preserve-attr" "open"}
      [:summary
       {"data-on:click" (str "@get('/ui/cron/jobs/" (:id job) "/detail')")}
       (str "Edit · " (:name job))]
      [:div.cron-job-editor__detail {:id (str "cron-job-detail-" (:id job))}
       "Loading editor…"]])])

(defn- duration-label [run]
  (when (and (:started-at run) (:finished-at run))
    (str (.toSeconds (java.time.Duration/between
                      (java.time.Instant/parse (:started-at run))
                      (java.time.Instant/parse (:finished-at run)))) "s")))

(defn- runs-table [runs jobs]
  (let [names (into {} (map (juxt :id :name) jobs))]
    [:div.cron-table-wrap.scroll-fade
     [:table.ui-table.cron-table
      [:thead [:tr [:th "Run"] [:th "Job"] [:th "Trigger"] [:th "Status"]
               [:th "Duration"] [:th "Notification"] [:th "Audit"]]]
      [:tbody
       (if (seq runs)
         (for [run runs]
           [:tr
            [:td [:strong (subs (:id run) 0 8)] [:small (:scheduled-for run)]]
            [:td (or (names (:job-id run)) (:job-id run))]
             [:td (name (:trigger run))]
             [:td [:span.status-badge {:class (str "status-badge--" (name (:status run)))} (name (:status run))]
             (when (or (:error run) (:output run))
               [:details {:id (str "cron-run-result-" (:id run))
                          "data-preserve-attr" "open"}
                [:summary {"data-on:click" (str "@get('/ui/cron/runs/" (:id run) "/detail')")}
                 "Result"]
                [:div {:id (str "cron-run-detail-" (:id run))} "Loading result…"]])]
            [:td (or (duration-label run) "—")]
            [:td (name (:notification-status run))]
            [:td [:a {:href (str "/chat/" (:session-id run))} "Transcript"] " · "
             [:a {:href "/logs" :title (:request-id run)} "Logs"]]])
         [:tr [:td {:colspan 7} "No runs yet."]])]]]))

(defn run-detail-fragment [run]
  (render/render
   (if run
     [:div {:id (str "cron-run-detail-" (:id run))}
      (if-let [text (or (:error run) (:output run))]
        [:pre (subs text 0 (min 12000 (count text)))]
        [:span "No output."])]
     [:div#cron-run-detail-missing "Cron run not found."])))

(defn- create-form [system]
  (let [cron-cfg (get-in system [:config :cron])
        profiles (keys (get-in system [:config :tools :profiles]))
        model-pairs (configured-model-pairs system)]
    [:form#cron-create-form.cron-editor.panel
     {:method "post"
      "data-on:submit" "@post('/ui/cron/jobs', {contentType: 'form', selector: '#cron-create-form'})"
      "data-on:datastar-fetch" "evt.detail.el === el && evt.detail.type === 'finished' && el.reset()"}
     [:div.panel-head [:div [:span.overview-kicker "New schedule"] [:h2 "Create job"]]]
     [:div.cron-form-grid
      [:label [:span "Name"] [:input {:name "name" :required true :placeholder "Check production logs"}]]
      [:label [:span "Timezone"] [:input {:name "timezone" :required true :value (:timezone cron-cfg)}]]
      [:label [:span "Schedule type"]
       [:select {:name "schedule_kind"}
        [:option {:value "cron"} "Cron"]
        [:option {:value "at"} "Once"]
        [:option {:value "interval"} "Every"]]]
      [:label [:span "Common cron preset"]
       [:select {:name "cron_preset"
                 :onchange "if(this.value){this.form.elements.cron_expression.value=this.value}"}
        [:option {:value ""} "Custom"]
        [:option {:value "0 9 * * *"} "Daily · 09:00"]
        [:option {:value "0 9 * * 1-5"} "Weekdays · 09:00"]
        [:option {:value "0 9 * * 1"} "Weekly · Monday 09:00"]]]
      [:label [:span "Cron expression"] [:input {:name "cron_expression" :value "0 9 * * 1-5"}]]
      [:label [:span "Once at (UTC)"] [:input {:name "at" :placeholder "2026-08-10T06:00:00Z"}]]
      [:label [:span "Every seconds"] [:input {:name "every_seconds" :type "number" :min "60" :value "3600"}]]
      [:label [:span "Occurrence limit"] [:input {:name "max_occurrences" :type "number" :min "1"}]]
      [:label [:span "Tool profile"]
       [:select {:name "tool_profile"}
        [:option {:value ""} (str "Cron default (" (name (:tool-profile cron-cfg)) ")")]
        (for [profile profiles] [:option {:value (name profile)} (name profile)])]]
      [:label [:span "Model"]
       [:select {:name "model_pair"}
        [:option {:value ""} (default-model-label system)]
        (for [[value label] model-pairs] [:option {:value value} label])]]
      [:label [:span "Notify"]
       [:select {:name "notify_policy"}
        [:option {:value "never"} "Never"]
        [:option {:value "always"} "Always"]
        [:option {:value "agent"} "When agent calls cron_notify"]]]
      [:label [:span "Telegram chat ID"] [:input {:name "telegram_recipient"}]]
      [:label.cron-prompt [:span "Prompt"]
       [:textarea {:name "prompt" :required true :rows 7
                   :placeholder "Inspect logs. Notify only if suspicious."}]]]
     [:div.cron-editor__actions
      [:button {:type "button"
                "data-on:click" "@post('/ui/cron/preview', {contentType: 'form', selector: '#cron-create-form'})"}
       "Preview next runs"]
      [:button {:type "submit"} "Create job"]]
     [:div#cron-preview.cron-preview "Preview schedule before saving."]]))

(defn- summary-node [health limit]
  [:section#cron-summary.panel.cron-summary
   {"data-on-interval__duration.15s" (str "@get('/ui/cron/status?limit=" limit "')")}
   [:div.panel-head
    [:div [:span.overview-kicker "Scheduler"] [:h2 "Cron jobs"]]
    [:div.panel-head__form
     [:span.status-badge {:class (if (:running health) "status-badge--active" "status-badge--paused")}
      (if (:running health) "running" "stopped")]
     [:button {:type "button"
               "data-on:click" (str "@get('/ui/cron?limit=" limit "')")}
      "Refresh"]]]
   [:div.overview-metrics
    (for [[label value] [["Active jobs" (:active-jobs health)]
                         ["Running" (:running-runs health)]
                         ["Failures / 24h" (:recent-failures health)]
                         ["Workers" (:worker-count health)]]]
      [:div.overview-metric [:span.label label] [:strong (str (or value 0))]])]
   (when-let [error (:last-error health)] [:p.value--warn error])])

(defn status-fragment [system limit]
  (render/render (summary-node (cron/health-check (:cron-service system)) limit)))

(defn fragment
  ([system] (fragment system {:limit 20}))
  ([system {:keys [limit] :or {limit 20}}]
   (let [service (:cron-service system)
        health (cron/health-check service)
        jobs (cron/list-jobs service {:limit limit})
        runs (cron/list-runs service nil limit)]
     (render/render
      [:section#cron-workspace.cron-workspace
      (summary-node health limit)
      [:section.panel
       [:div.panel-head [:div [:span.overview-kicker "Persistent schedules"] [:h2 "Jobs"]]]
       (jobs-table jobs)
       (job-editors jobs)]
      [:section.panel
       [:div.panel-head [:div [:span.overview-kicker "Audit"] [:h2 "Recent runs"]]]
       (runs-table runs jobs)]
      (when (or (= (count jobs) limit) (= (count runs) limit))
        [:button.chat-history-more
         {:type "button"
          "data-on:click" (str "@get('/ui/cron?limit=" (min 100 (+ limit 20)) "')")}
         "Load 20 older"])
      (create-form system)]))))
