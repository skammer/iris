(ns agent.ui.cron
  "Server-rendered Cron workspace."
  (:require
   [agent.cron.schedule :as schedule]
   [agent.cron.service :as cron]
   [agent.ui.render :as render])
  (:import
   (java.time DayOfWeek LocalDate YearMonth ZonedDateTime)
   (java.time.format DateTimeFormatter)
   (java.time.temporal TemporalAdjusters)
   (java.util Locale)))

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

(def ^:private tabs [[:stats "Stats"] [:jobs "Jobs"] [:runs "Runs"] [:new "New job"]])

(def ^:private day-label-formatter
  (DateTimeFormatter/ofPattern "EEE dd" Locale/ENGLISH))
(def ^:private month-label-formatter
  (DateTimeFormatter/ofPattern "MMMM yyyy" Locale/ENGLISH))
(def ^:private time-label-formatter (DateTimeFormatter/ofPattern "HH:mm"))
(def ^:private max-exact-daily-occurrences 24)
(def ^:private max-visible-gantt-markers 10)

(defn- display-zone [system]
  (schedule/timezone! (get-in system [:config :cron :timezone] "UTC")))

(defn- parse-anchor-date [value zone]
  (try
    (if value (LocalDate/parse value) (LocalDate/now zone))
    (catch Exception _ (LocalDate/now zone))))

(defn- start-of-week [date]
  (.with date (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY)))

(defn- day-window [date zone]
  (let [start (.toInstant (.atStartOfDay date zone))]
    [start (.toInstant (.atStartOfDay (.plusDays date 1) zone))]))

(defn- day-occurrences [job date zone]
  (let [[start end] (day-window date zone)]
    (loop [cursor (.minusNanos start 1)
           occurrences []]
      (if-let [fire (schedule/next-fire (:schedule job) (:timezone job) cursor)]
        (if (.isBefore fire end)
          (if (>= (count occurrences) max-exact-daily-occurrences)
            {:occurrences occurrences :overflow? true}
            (recur fire (conj occurrences (ZonedDateTime/ofInstant fire zone))))
          {:occurrences occurrences :overflow? false})
        {:occurrences occurrences :overflow? false}))))

(defn- occurrence-count [{:keys [occurrences overflow?]}]
  (str (count occurrences) (when overflow? "+")))

(defn- occurrence-label [{:keys [occurrences overflow?] :as summary}]
  (cond
    overflow? (str (occurrence-count summary) "×")
    (= 1 (count occurrences)) (.format time-label-formatter (first occurrences))
    :else (str (count occurrences) "×")))

(defn- gantt-marker [job occurrence]
  (let [second-of-day (.toSecondOfDay (.toLocalTime occurrence))
        left (* 100.0 (/ second-of-day 86400.0))]
    [:i.cron-gantt__marker
     {:style (String/format Locale/ROOT "left: %.4f%%" (object-array [left]))
      :title (str (:name job) " · " (.format time-label-formatter occurrence)
                  " · " (:timezone job))}]))

(defn- stats-url [view date limit]
  (str "/ui/cron?tab=stats&view=" (name view) "&date=" date "&limit=" limit))

(defn- schedule-view-controls [view anchor limit zone]
  (let [month? (= :calendar view)
        previous (if month? (.minusMonths anchor 1) (.minusWeeks anchor 1))
        next (if month? (.plusMonths anchor 1) (.plusWeeks anchor 1))]
    [:div.cron-schedule-controls
     [:div.ui-segmented {:role "group" :aria-label "Schedule view"}
      (for [[candidate label] [[:week "Gantt"] [:calendar "Calendar"]]]
        [:button {:type "button"
                  :class (when (= candidate view) "active")
                  :aria-pressed (= candidate view)
                  "data-on:click" (str "@get('" (stats-url candidate anchor limit) "')")}
         label])]
     [:div.cron-schedule-nav
      [:button.btn-small {:type "button"
                          :aria-label (if month? "Previous month" "Previous week")
                          "data-on:click" (str "@get('" (stats-url view previous limit) "')")}
       "←"]
      [:button.btn-small {:type "button"
                          "data-on:click" (str "@get('" (stats-url view (LocalDate/now zone) limit) "')")}
       "Today"]
      [:button.btn-small {:type "button"
                          :aria-label (if month? "Next month" "Next week")
                          "data-on:click" (str "@get('" (stats-url view next limit) "')")}
       "→"]]]))

(defn- gantt-view [jobs anchor zone]
  (let [week-start (start-of-week anchor)
        days (mapv #(.plusDays week-start %) (range 7))
        today (LocalDate/now zone)
        header (into
                [:div.cron-gantt {:style "--cron-gantt-days: 7"}
                 [:div.cron-gantt__corner "Job"]]
                (map (fn [date]
                       [:div.cron-gantt__day-heading
                        {:class (when (= date today) "is-today")}
                        [:strong (.format day-label-formatter date)]])
                     days))
        rows (if (seq jobs)
               (mapcat
                (fn [job]
                  (into
                   [[:div.cron-gantt__job
                     {:class (str "is-" (name (:status job))) :title (:name job)}
                     [:strong (:name job)]
                     [:span (schedule-label (:schedule job))]]]
                   (map
                    (fn [date]
                      (let [summary (day-occurrences job date zone)
                            occurrences (:occurrences summary)]
                        [:div.cron-gantt__day
                         {:class (str "is-" (name (:status job)))
                          :aria-label (str (:name job) ", " date ", "
                                           (occurrence-count summary) " scheduled runs")}
                         (for [occurrence (take max-visible-gantt-markers occurrences)]
                           (gantt-marker job occurrence))
                         (when (> (+ (count occurrences) (if (:overflow? summary) 1 0))
                                  max-visible-gantt-markers)
                           [:span.cron-gantt__count
                            (str (occurrence-count summary) "×")])]))
                    days)))
                jobs)
               [[:div.cron-schedule-empty "No schedules."]])]
    [:div.cron-gantt-wrap
     (into header rows)]))

(defn- calendar-entry [job summary]
  [:div.cron-calendar__event
   {:class (str "is-" (name (:status job)))
    :title (str (:name job) " · " (schedule-label (:schedule job)) " · " (:timezone job))}
   [:span.cron-calendar__event-time (occurrence-label summary)]
   [:span.cron-calendar__event-name (:name job)]])

(defn- calendar-view [jobs anchor zone]
  (let [month (YearMonth/from anchor)
        first-day (.atDay month 1)
        leading (dec (.getValue (.getDayOfWeek first-day)))
        day-count (.lengthOfMonth month)
        total (+ leading day-count)
        cell-count (* 7 (long (Math/ceil (/ total 7.0))))
        dates (vec (concat (repeat leading nil)
                           (map #(.atDay month %) (range 1 (inc day-count)))
                           (repeat (- cell-count total) nil)))
        today (LocalDate/now zone)]
    [:div.cron-calendar-wrap
     [:div.cron-calendar
      (for [label ["Mon" "Tue" "Wed" "Thu" "Fri" "Sat" "Sun"]]
        [:div.cron-calendar__weekday label])
      (for [date dates]
        (if-not date
          [:div.cron-calendar__day.cron-calendar__day--outside]
          (let [events (keep (fn [job]
                               (let [summary (day-occurrences job date zone)]
                                 (when (or (seq (:occurrences summary)) (:overflow? summary))
                                   [job summary])))
                             jobs)]
            [:div.cron-calendar__day {:class (when (= date today) "is-today")}
             [:time {:datetime (str date)} (.getDayOfMonth date)]
             [:div.cron-calendar__events
              (for [[job summary] events]
                (calendar-entry job summary))]])))]]))

(defn- schedule-panel [system jobs view anchor limit]
  (let [zone (display-zone system)
        view* (if (= :calendar view) :calendar :week)
        anchor* (parse-anchor-date anchor zone)
        week-start (start-of-week anchor*)
        title (if (= :calendar view*)
                (.format month-label-formatter (YearMonth/from anchor*))
                (str (.format day-label-formatter week-start) " — "
                     (.format day-label-formatter (.plusDays week-start 6))))]
    [:section.panel.cron-schedule-panel
     [:div.panel-head.cron-schedule-head
      [:div [:span.overview-kicker "Schedules"] [:h2 title]
       [:small (str "Displayed in " zone)]]
      (schedule-view-controls view* anchor* limit zone)]
     (if (= :calendar view*)
       (calendar-view jobs anchor* zone)
       (gantt-view jobs anchor* zone))]))

(defn- tab-link [tab label active-tab limit]
  [:button.cron-tab
   {:type "button"
    :class (when (= tab active-tab) "cron-tab--active")
    :role "tab"
    :aria-selected (= tab active-tab)
    "data-on:click" (str "@get('/ui/cron?tab=" (name tab) "&limit=" limit "')")}
   label])

(defn- action-form [job action label]
  [:form.cron-inline-form
   {:method "post"
    "data-on:submit" "@post('/ui/cron/action', {contentType: 'form'})"}
   [:input {:type "hidden" :name "id" :value (:id job)}]
   [:input {:type "hidden" :name "revision" :value (:revision job)}]
   [:input {:type "hidden" :name "action" :value action}]
   [:input {:type "hidden" :name "cron_tab" :value "jobs"}]
   [:button.btn-small {:type "submit"} label]])

(defn- edit-job-button [job label class]
  (let [editor-id (str "cron-job-editor-" (:id job))]
    [:button {:type "button"
              :class class
              :onclick (str "const editor=document.getElementById('" editor-id
                            "');editor.open=true;editor.scrollIntoView({behavior:'smooth',block:'start'});"
                            "return false;")
              "data-on:click" (str "@get('/ui/cron/jobs/" (:id job) "/detail')")}
     label]))

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
          [:td (edit-job-button job (:name job) "cron-job-link")
           [:small (str "r" (:revision job) " · " (:id job))]]
          [:td [:span.status-badge {:class (str "status-badge--" (name (:status job)))}
                (name (:status job))]]
          [:td [:span (schedule-label (:schedule job))] [:small (:timezone job)]]
          [:td (or (:next-run-at job) "—")]
          [:td [:span (or (some-> (:last-run-status job) name) "—")]
           [:small (or (:last-run-at job) "never")]]
          [:td [:span (name (keyword (get-in job [:notification :policy] :never)))]
           [:small (target-label (:notification job))]]
          [:td
           [:div.cron-actions
            (if (= :active (:status job))
              (action-form job "pause" "Pause")
              (action-form job "resume" "Resume"))
            (edit-job-button job "Edit" "btn-small")
            (action-form job "run" "Run now")]]])
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
        model-pairs (configured-model-pairs system)
        form-id (str "cron-edit-form-" (:id job))]
    [:div.cron-job-editor__detail {:id (str "cron-job-detail-" (:id job))}
     [:form.cron-edit-form
       {:id form-id
        :method "post"
        "data-on:submit" "@post('/ui/cron/action', {contentType: 'form'})"}
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
          (for [[policy label] [[:never "Local only — save result"]
                                [:always "Telegram — always send result"]
                                [:agent "Telegram — only when agent calls cron_notify"]]]
            [:option {:value (name policy)
                      :selected (= policy (keyword (get-in job [:notification :policy] :never)))}
             label])]]
        [:label [:span "Telegram chat ID"]
         [:input {:name "telegram_recipient"
                  :placeholder "Required for Telegram delivery"
                  :oninput "if(this.value.trim() && this.form.elements.notify_policy.value === 'never'){this.form.elements.notify_policy.value='always'}"
                  :value (get-in job [:notification :target :recipient])}]]
        [:label.cron-prompt [:span "Prompt"] [:textarea {:name "prompt" :rows 5 :required true} (:prompt job)]]]
       [:p.cron-delivery-help "Local only keeps output in Runs. Telegram modes require a saved chat ID."]
       [:div.cron-editor__actions
        [:button.cron-delete-button {:type "submit"
                                     "data-on:click" "el.form.elements.action.value='delete'"}
         "Delete"]
        [:button {:type "submit"
                  "data-on:click" "el.form.elements.action.value='update'"}
         "Save"]
        [:button {:type "submit"
                  "data-on:click" "el.form.elements.action.value='update-run'"}
         "Save & run"]]]]))

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
         (mapcat
          (fn [run]
            (let [result? (or (:error run) (:output run))]
              (cond->
               [[:tr.cron-run-row {:class (when result? "cron-run-row--has-result")}
                 [:td [:strong (subs (:id run) 0 8)] [:small (:scheduled-for run)]]
                 [:td (or (names (:job-id run)) (:job-id run))]
                 [:td (name (:trigger run))]
                 [:td [:span.status-badge
                       {:class (str "status-badge--" (name (:status run)))}
                       (name (:status run))]]
                 [:td (or (duration-label run) "—")]
                 [:td (name (:notification-status run))]
                 [:td.cron-audit-links
                  [:a {:href (str "/chat/" (:session-id run))} "Transcript"]
                  [:span "·"]
                  [:a {:href "/logs" :title (:request-id run)} "Logs"]]]]
                result?
                (conj
                 [:tr.cron-run-result-row
                  [:td {:colspan 7}
                   [:details.cron-run-result
                    {:id (str "cron-run-result-" (:id run))
                     "data-preserve-attr" "open"}
                    [:summary
                     {"data-on:click" (str "@get('/ui/cron/runs/" (:id run) "/detail')")}
                     "Result"]
                    [:div.cron-run-result__content
                     {:id (str "cron-run-detail-" (:id run))}
                     "Loading result…"]]]]))))
          runs)
         [:tr [:td {:colspan 7} "No runs yet."]])]]]))

(defn run-detail-fragment [run]
  (render/render
   (if run
     [:div.cron-run-result__content {:id (str "cron-run-detail-" (:id run))}
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
        [:option {:value "never"} "Local only — save result"]
        [:option {:value "always"} "Telegram — always send result"]
        [:option {:value "agent"} "Telegram — only when agent calls cron_notify"]]]
      [:label [:span "Telegram chat ID"]
       [:input {:name "telegram_recipient"
                :placeholder "Required for Telegram delivery"
                :oninput "if(this.value.trim() && this.form.elements.notify_policy.value === 'never'){this.form.elements.notify_policy.value='always'}"}]]
      [:label.cron-prompt [:span "Prompt"]
       [:textarea {:name "prompt" :required true :rows 7
                   :placeholder "Inspect logs. Notify only if suspicious."}]]]
     [:p.cron-delivery-help "Every run and full transcript are saved. Delivery is separate: Always sends the final answer; Agent sends only content passed to cron_notify."]
     [:div.cron-editor__actions
      [:button {:type "button"
                "data-on:click" "@post('/ui/cron/preview', {contentType: 'form', selector: '#cron-create-form'})"}
       "Preview next runs"]
      [:button {:type "submit"} "Create job"]]
     [:div#cron-preview.cron-preview "Preview schedule before saving."]]))

(defn- summary-node
  ([health limit tab] (summary-node health limit tab :week nil))
  ([health limit tab view anchor]
  [:section#cron-summary.panel.cron-summary
   {"data-on-interval__duration.15s" (str "@get('/ui/cron/status?tab=" (name tab)
                                               "&limit=" limit
                                               (when (= :stats tab)
                                                 (str "&view=" (name (or view :week))
                                                      (when anchor (str "&date=" anchor))))
                                               "')")}
   [:div.panel-head
    [:div [:span.overview-kicker "Scheduler"] [:h2 "Cron jobs"]]
    [:div.panel-head__form
     [:span.status-badge {:class (if (:running health) "status-badge--active" "status-badge--paused")}
      (if (:running health) "running" "stopped")]
     [:button {:type "button"
               "data-on:click" (str "@get('/ui/cron?tab=" (name tab) "&limit=" limit
                                    (when (= :stats tab)
                                      (str "&view=" (name (or view :week))
                                           (when anchor (str "&date=" anchor))))
                                    "')")}
      "Refresh"]]]
   [:div.overview-metrics
    (for [[label value] [["Active jobs" (:active-jobs health)]
                         ["Running" (:running-runs health)]
                         ["Failures / 24h" (:recent-failures health)]
                         ["Workers" (:worker-count health)]]]
      [:div.overview-metric [:span.label label] [:strong (str (or value 0))]])]
   (when-let [error (:last-error health)] [:p.value--warn error])]))

(defn status-fragment
  ([system limit tab] (status-fragment system limit tab :week nil))
  ([system limit tab view anchor]
   (render/render (summary-node (cron/health-check (:cron-service system))
                                limit tab view anchor))))

(defn fragment
  ([system] (fragment system {:limit 20 :tab :jobs}))
  ([system {:keys [limit tab view date] :or {limit 20 tab :jobs view :week}}]
   (let [service (:cron-service system)
         tab* (if (contains? (set (map first tabs)) tab) tab :jobs)
         view* (if (= :calendar view) :calendar :week)
         health (when (= :stats tab*) (cron/health-check service))
         jobs (when (contains? #{:stats :jobs :runs} tab*)
                (cron/list-jobs service {:limit (if (= :stats tab*) 200 limit)}))
         runs (when (= :runs tab*) (cron/list-runs service nil limit))]
     (render/render
      [:section#cron-workspace.cron-workspace.scroll-fade
      [:nav.cron-tabs {:role "tablist" :aria-label "Cron sections"}
       (for [[tab label] tabs] (tab-link tab label tab* limit))]
      (case tab*
        :stats [:div.cron-stats
                (summary-node health limit tab* view* date)
                (schedule-panel system jobs view* date limit)]
        :runs [:section.panel.cron-tab-panel
               [:div.panel-head
                [:div [:span.overview-kicker "Audit"] [:h2 "Recent runs"]]
                [:button {:type "button"
                          "data-on:click" (str "@get('/ui/cron?tab=runs&limit=" limit "')")}
                 "Refresh"]]
               (runs-table runs jobs)]
        :new (create-form system)
        [:section.panel.cron-tab-panel
         [:div.panel-head [:div [:span.overview-kicker "Persistent schedules"] [:h2 "Jobs"]]]
         (jobs-table jobs)
         (job-editors jobs)])
      (when (or (= (count jobs) limit) (= (count runs) limit))
        [:button.chat-history-more
         {:type "button"
          "data-on:click" (str "@get('/ui/cron?tab=" (name tab*) "&limit="
                               (min 100 (+ limit 20)) "')")}
         "Load 20 older"])]))))
