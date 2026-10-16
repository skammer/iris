(ns agent.ui.overview
  "Dashboard metrics and the operator work queue."
  (:require
   [agent.build-info :as build-info]
   [agent.channels.core :as channel-adapters]
   [agent.config :as config]
   [agent.cron.service :as cron]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.ui.render :as ui-render]
   [clojure.string :as str]))

(defn- activity-chart [days metric label]
  (let [values (mapv metric days)
        peak (apply max 1 values)
        total (reduce + 0 values)]
    [:span.overview-activity
     [:svg.overview-activity__chart
      {:viewBox "0 0 140 44" :preserveAspectRatio "none"
       :role "img" :aria-label (str label ": " total " over 7 UTC days, including today")}
      [:line.overview-activity__baseline {:x1 0 :y1 43 :x2 140 :y2 43}]
      (for [[idx day] (map-indexed vector days)
            :let [value (metric day)
                  height (* 40.0 (/ value peak))]]
        [:g
         [:title (str (:day day) " UTC: " value " " label)]
         [:rect {:x (+ 3 (* idx 20)) :y (- 43 height)
                 :width 14 :height height :rx 2}]])]
     [:span.overview-activity__caption
      [:span (str label " · 7d UTC")]
      [:strong (str total)]]]))

(defn dashboard-fragment [system]
  (let [storage (sqlite/health-check (:store system))
        build (build-info/read-build-info)
        activity (when (:store system) (sqlite/dashboard-activity (:store system)))
        llm-config (get-in system [:config :llm])
        tools-health (tools/registry-health (:tool-registry system))
        memory-health (memory/health-check (:memory-service system))
        cron-health (cron/health-check (:cron-service system))
        adapter-health (channel-adapters/registry-health (:channel-adapter-registry system))
        pending-approvals (count (tool-approvals/list-review-requests (:store system) {:limit 100}))
        reload-status (or (some-> system :reload-state deref)
                          {:status :idle})
        reload-label (str/join " · " (keep #(some-> % name)
                                           [(:status reload-status) (:mode reload-status)]))]
    (ui-render/render
     [:section#dashboard-summary.panel.overview-dashboard
      {"data-on-interval__duration.10s" "@get('/ui/dashboard')"}
      [:div.overview-intro
       [:div.overview-title-block
        [:h1 "Overview"]
        [:dl.overview-runtime-identity
         [:div [:dt "Provider"] [:dd (name (config/active-provider-key llm-config))]]
         [:div [:dt "Model"] [:dd (or (config/active-model llm-config) "-")]]]]
       [:nav.overview-action-grid {:aria-label "Primary workspaces"}
        (for [[mark label detail href metric chart-label]
              [["01" "Open chat" "Sessions and messages" "/chat" :chat "Turns"]
               ["02" "Cron jobs" (str (:active-jobs cron-health) " active · " (:running-runs cron-health) " running") "/cron" :cron "Runs"]
               ["03" "Review tools" (str pending-approvals " pending approvals") "/tools" :tools "Requests"]
               ["04" "Browse memory" (str (get-in memory-health [:vault :note-count] 0) " vault notes") "/memory" :memory "Memory events"]
               ["05" "Inspect logs" (str (get-in storage [:details :event-count] 0) " events") "/logs" :logs "Events"]]]
          [:a.overview-action-tile {:href href}
           [:span.overview-action-tile__mark {:aria-hidden "true"} mark]
           [:span.overview-action-tile__copy
            [:strong label]
            [:small detail]]
           [:span.overview-action-tile__arrow {:aria-hidden "true"} "↗"]
           (activity-chart activity metric chart-label)])]]
      [:section.runtime-card
       [:header.runtime-card__header
        [:div
         [:strong "Current deployment"]]
        [:form#system-reload-form.panel-head__form
         {:method "post"
          "data-on:submit" "@post('/ui/system/reload', {contentType: 'form', selector: '#system-reload-form'})"}
         [:input {:type "hidden" :name "mode" :value "soft"}]
         [:span.reload-status
          (cond-> {:class (str "reload-status--" (name (:status reload-status)))}
            (:message reload-status) (assoc :title (str (:message reload-status))))
          reload-label]
         [:button {:type "submit"} "Reload config"]]]
       [:div.runtime-card__body
        [:div.runtime-provider-card
         [:span.label "Active model"]
         [:strong (or (config/active-model llm-config) "-")]
         [:span.meta (name (config/active-provider-key llm-config))]]
        [:div.overview-metrics
         (for [[label value alert?] [["Sessions" (get-in storage [:details :session-count] 0) false]
                                     ["Events" (get-in storage [:details :event-count] 0) false]
                                     ["Tools" (:count tools-health) false]
                                     ["Cron" (if (:running cron-health) "RUN" "STOP") (or (not (:running cron-health)) (:last-error cron-health))]
                                     ["Adapters" (:count adapter-health) false]
                                     ["Approvals" pending-approvals (pos? pending-approvals)]]]
           [:div.overview-metric
            [:span.label label]
            [:strong {:class (when alert? "value--warn")} (str value)]])]]
       [:footer.fact-strip
        (for [[label value] [["vault notes" (get-in memory-health [:vault :note-count] 0)]
                             ["version" (:version build)]
                             ["commit" (:commit-short build)]
                             ["built" (or (ui-render/short-timestamp (:built-at build)) "-")]
                             ["schema" (get-in storage [:details :schema-version] "?")]]]
          [:span.fact
           [:span.fact__label label]
           [:span.fact__value (str value)]])]]])))

(defn- board-section
  ([label items row-fn] (board-section label items row-fn nil))
  ([label items row-fn {:keys [alert?]}]
   (let [items (vec items)]
     [:div.board-section {:class (when (empty? items) "board-section--empty")}
      [:div.board-section__head
       [:strong label]
       [:span.count-badge {:class (when (and alert? (seq items)) "count-badge--alert")}
        (str (count items))]]
      (if (seq items)
        [:div.rows (map row-fn items)]
        [:div.empty-line "none"])])))

(defn operator-board-fragment [system]
  (let [approvals (tool-approvals/list-review-requests (:store system) {:limit 8})
        recent-events-pool (sqlite/list-events (:store system) {:limit 40})
        events (take 8 recent-events-pool)
        kernel-events (filter #(= "agent.kernel.step.executed" (:event-type %))
                              recent-events-pool)]
    (ui-render/render
     [:section#operator-board.panel.overview-operations
      {"data-on-interval__duration.10s" "@get('/ui/operator-board')"}
      [:div.panel-head
       [:div
        [:h2 "Operator Board"]]
       [:a.overview-text-link {:href "/logs"} "View all activity ↗"]]
      [:div.board
       (board-section "Approval queue" approvals
                      (fn [{:keys [tool-name reason created-at]}]
	                        [:div.row
	                         (ui-render/status-dot "pending")
	                         [:span.row__id (str tool-name)]
	                         [:span.row__meta (or (not-empty reason) "awaiting approval")]
	                         [:span.row__time (ui-render/short-timestamp created-at)]])
	                      {:alert? true})
       (board-section "Recent events" events
                      (fn [{:keys [event-type entity-id created-at]}]
                        [:div.row
                         [:span.row__id (str event-type)]
                         [:span.row__meta {:title entity-id}
                          (ui-render/short-id (or entity-id "-"))]
                         [:span.row__time (ui-render/short-timestamp created-at)]]))
       (board-section "Kernel receipts" (take 8 kernel-events)
                      (fn [{:keys [entity-id created-at payload]}]
                        [:div.row
                         [:span.row__id {:title entity-id}
                          (ui-render/short-id (or entity-id "-"))]
                         [:span.row__meta (str "directives " (get payload :directive-count 0)
                                               " · receipts " (get payload :receipt-count 0))]
                         [:span.row__time (ui-render/short-timestamp created-at)]]))]])))
