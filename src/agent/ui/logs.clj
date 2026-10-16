(ns agent.ui.logs
  "Event and runtime trace views."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as runtime-trace]
   [agent.ui.render :as ui-render]
   [cheshire.core :as json]))

(defn events-fragment [system]
  (ui-render/render
   [:section#events-panel.panel
    {"data-on-interval__duration.5s" "@get('/ui/events')"}
    [:h2 "Live Events"]
    (if-let [events (seq (sqlite/list-events (:store system) {:limit 25}))]
      [:div.event-list
       (for [{:keys [event-type entity-type entity-id created-at payload]} events]
         [:article.event-item
          [:strong event-type]
          [:div.meta (str (or entity-type "system") " / " (or entity-id "-"))]
          [:div.code (json/generate-string payload)]
          [:div.meta created-at]])]
      [:div.empty "No events yet."])]))

(defn- log-state [label class-name]
  [:span.log-state {:class class-name} label])

(defn- log-payload [payload]
  [:section.log-record__payload
   [:h3 "Payload"]
   (if (seq payload)
     [:pre.code (json/generate-string payload {:pretty true})]
     [:div.empty-line "none"])])

(defn- event-log-detail [{:keys [id event-type entity-type entity-id created-at payload]}]
  [:div.log-record__detail {:id (str "log-detail-event-" id)}
   [:dl.log-record__metadata
    [:div [:dt "Event"] [:dd event-type]]
    [:div [:dt "Entity"] [:dd (or entity-type "system")]]
    [:div [:dt "Entity ID"]
     [:dd
      (if entity-id
        [:code {:title entity-id} (ui-render/short-id entity-id)]
        "-")]]
    [:div [:dt "Created"] [:dd (or created-at "-")]]]
   (log-payload payload)])

(defn- event-log-record [{:keys [id event-type entity-type entity-id created-at]}]
  [:details.log-record
   {:id (str "log-record-event-" id)
    "data-preserve-attr" "open"}
   [:summary.log-record__summary
    {"data-on:click" (str "@get('/ui/logs/event/" id "/detail')")}
    (log-state "event" "log-state--neutral")
    [:span.log-record__event event-type]
    [:span.log-record__source (or entity-type "system")]
    [:span.log-record__context
     (cond-> {}
       entity-id (assoc :title entity-id))
     (if entity-id (ui-render/short-id entity-id) "-")]
    [:time {:datetime created-at} (ui-render/short-timestamp created-at)]
    [:span.log-record__chevron {:aria-hidden "true"} "⌄"]]
   [:div.log-record__detail {:id (str "log-detail-event-" id)}
    [:div.empty-line "Loading details…"]]])

(defn- trace-log-detail [{:keys [id event-type timestamp turn-id channel model error-message payload]}]
  [:div.log-record__detail {:id (str "log-detail-trace-" id)}
   [:dl.log-record__metadata
    [:div [:dt "Event"] [:dd event-type]]
    [:div [:dt "Turn ID"]
     [:dd
      (if turn-id
        [:code {:title turn-id} (ui-render/short-id turn-id)]
        "-")]]
    [:div [:dt "Channel"] [:dd (or channel "-")]]
    [:div [:dt "Model"] [:dd (or model "-")]]
    [:div [:dt "Timestamp"] [:dd (or timestamp "-")]]
    [:div [:dt "Error"] [:dd (or error-message "-")]]]
   (log-payload payload)])

(defn- trace-log-record [{:keys [id event-type timestamp turn-id channel model success]}]
  (let [[state-label state-class] (cond
                                    (false? success) ["failed" "log-state--failed"]
                                    (true? success) ["ok" "log-state--ok"]
                                    :else ["trace" "log-state--neutral"])]
    [:details.log-record
     {:id (str "log-record-trace-" id)
      "data-preserve-attr" "open"}
     [:summary.log-record__summary
      {"data-on:click" (str "@get('/ui/logs/trace/" id "/detail')")}
      (log-state state-label state-class)
      [:span.log-record__event event-type]
      [:span.log-record__source (or channel "runtime")]
      [:span.log-record__context {:title (or model turn-id "-")}
       (or model (some-> turn-id ui-render/short-id) "-")]
      [:time {:datetime timestamp} (ui-render/short-timestamp timestamp)]
      [:span.log-record__chevron {:aria-hidden "true"} "⌄"]]
     [:div.log-record__detail {:id (str "log-detail-trace-" id)}
      [:div.empty-line "Loading details…"]]]))

(defn log-detail-fragment [source record]
  (ui-render/render
   (cond
     (nil? record) [:div#log-detail-missing.log-record__detail
                    [:div.empty-line "Log record not found."]]
     (= source :trace) (trace-log-detail record)
     :else (event-log-detail record))))

(defn- log-table [records record-fn empty-copy]
  [:section.log-table
   [:header.log-table__head
    [:span "State"]
    [:span "Event"]
    [:span "Source"]
    [:span "Context"]
    [:span "Time"]
    [:span {:aria-hidden "true"} ""]]
   (if (seq records)
     [:div.log-table__body
      (for [record records]
        (record-fn record))]
     [:div.log-table__empty empty-copy])])

(defn logs-fragment
  ([system] (logs-fragment system {:limit 20}))
  ([system {:keys [limit] :or {limit 20}}]
   (let [events (sqlite/list-events (:store system) {:limit limit})
        trace (:trace system)
        trace-health (runtime-trace/health-check trace)
        trace-events (runtime-trace/load-events trace {:limit limit})
        trace-failures (count (filter #(false? (:success %)) trace-events))]
     (ui-render/render
      [:section#logs-panel.panel.logs-page
      [:header.logs-page__header
       [:div
        [:h1 "Logs"]]
       [:div.panel-head__form
        [:span.badge (str "Latest " limit " per source")]
        [:button {:type "button"
                  "data-on:click" (str "@get('/ui/logs?limit=" limit "')")}
         "Refresh"]]]
      [:div.log-metrics
       (for [[label value] [["Events" (count events)]
                            ["Trace entries" (count trace-events)]
                            ["Trace mode" (or (some-> (:mode trace-health) name) "none")]
                            ["Failures" trace-failures]]]
         [:div.log-metric
          [:span.label label]
          [:strong (str value)]])]
      [:section.log-source
       [:header.log-source__header
        [:div
         [:h2 "Event Log"]
         [:span "SQLite / agent_events"]]
        [:span.count-badge (count events)]]
       (log-table events event-log-record "No durable events yet.")]
      [:section.log-source
       [:header.log-source__header
        [:div
         [:h2 "Runtime Trace"]
         [:span.log-source__path {:title (str (:path trace-health))}
          (str "mode " (or (some-> (:mode trace-health) name) "none")
               " / " (or (:path trace-health) "no path"))]]
        [:span.count-badge (count trace-events)]]
       (if (:enabled trace-health)
         (log-table trace-events trace-log-record "Trace enabled; no entries yet.")
         [:div.log-trace-disabled
          [:strong "Trace disabled"]
          [:span "Enable rolling trace in local/dev configuration when diagnostic events are needed."]])]
      (when (or (= (count events) limit) (= (count trace-events) limit))
        [:button.chat-history-more
         {:type "button"
          "data-on:click" (str "@get('/ui/logs?limit=" (min 200 (+ limit 20)) "')")}
         "Load 20 older"])]))))
