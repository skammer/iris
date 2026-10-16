(ns agent.ui.approvals
  "Tool approval queue, details and action forms."
  (:require
   [agent.tools.approvals :as tool-approvals]
   [agent.ui.render :as ui-render]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn tool-results-fragment [tool-name status payload]
  (ui-render/render
   [:div#tool-results-panel.tool-operation-result
    [:div
     [:strong "Execution result"]
     [:span.meta (str (name tool-name) " / status " status)]]
    [:details
     [:summary "View output"]
     [:pre.code (json/generate-string payload {:pretty true})]]]))

(defn- approval-actions [approval]
  (concat
   (when (tool-approvals/review-required? approval)
     [[:form.approval-action-form.approval-action-form--approve {:id (str "approve-" (:id approval))}
       [:input {:type "hidden" :name "actor" :value "operator"}]
       [:label
        [:span "Approval reason"]
        [:input {:type "text" :name "reason" :placeholder "Why this is safe"}]]
       [:button {:type "button"
                 "data-on:click" (str "@post('/ui/tool-approvals/" (:id approval) "/approve', {contentType: 'form', selector: '#approve-" (:id approval) "'})")}
        "Approve"]]
      [:form.approval-action-form.approval-action-form--deny {:id (str "deny-" (:id approval))}
       [:input {:type "hidden" :name "actor" :value "operator"}]
       [:label
        [:span "Denial reason"]
        [:input {:type "text" :name "reason" :placeholder "What must change"}]]
       [:button {:type "button"
                 "data-on:click" (str "@post('/ui/tool-approvals/" (:id approval) "/deny', {contentType: 'form', selector: '#deny-" (:id approval) "'})")}
        "Deny"]]])
   (when (tool-approvals/runnable? approval)
     [[:form.approval-action-form.approval-action-form--run {:id (str "run-" (:id approval))}
       [:span "Approval granted. Execute the exact reviewed input."]
       [:button {:type "button"
                 "data-on:click" (str "@post('/ui/tool-approvals/" (:id approval) "/run', {contentType: 'form', selector: '#run-" (:id approval) "'})")}
        "Run approved tool"]]])))

(defn- approval-status [status]
  [:span.approval-status {:class (str "approval-status--" status)}
   status])

(defn- approval-input-fields [input]
  [:dl.approval-input-fields
   (for [[k value] (sort-by (comp str key) input)]
     [:div
      [:dt (if (keyword? k) (name k) (str k))]
      [:dd
       (if (or (map? value) (sequential? value))
         [:pre.code (json/generate-string value {:pretty true})]
         [:code (str value)])]])])

(defn- approval-detail-node [approval]
  (let [{:keys [id requested-by actor decision-reason decided-at
                expires-at input requested-permissions]} approval
        status (tool-approvals/effective-status approval)]
    [:div.approval-record__detail {:id (str "approval-detail-" id)}
      [:div.approval-detail-grid
       [:section
        [:h3 "Request"]
        [:dl.approval-metadata
         [:div [:dt "ID"] [:dd [:code {:title id} (ui-render/short-id id)]]]
         [:div [:dt "Requested by"]
          [:dd
           (if requested-by
             [:span {:title requested-by} (ui-render/short-id requested-by)]
             "-")]]
         [:div [:dt "Expires"] [:dd (or expires-at "-")]]
         [:div [:dt "Permissions"]
          [:dd (if (seq requested-permissions)
                 (str/join ", " (sort (map name requested-permissions)))
                 "none")]]]]
       [:section
        [:h3 "Decision"]
        [:dl.approval-metadata
         [:div [:dt "Status"] [:dd status]]
         [:div [:dt "Actor"] [:dd (or actor "-")]]
         [:div [:dt "Reason"] [:dd (or decision-reason "-")]]
         [:div [:dt "Decided"] [:dd (or decided-at "-")]]]]]
      [:section.approval-input
       [:h3 "Tool input"]
       (if (seq input)
         (approval-input-fields input)
         [:div.empty-line "none"])]
      (when-let [actions (seq (approval-actions approval))]
        [:footer.approval-record__actions
         [:span "Operator action"]
         [:div actions]])]))

(defn tool-approval-detail-fragment [approval]
  (ui-render/render
   (if approval
     (approval-detail-node approval)
     [:div#approval-detail-missing.approval-record__detail
      [:div.empty-line "Approval not found."]])))

(defn- approval-record [approval]
  (let [{:keys [id tool-name requested-by reason created-at]} approval
        status (tool-approvals/effective-status approval)]
    [:details.approval-record
     {:id (str "approval-record-" id)
      :data-status status
      "data-preserve-attr" "open"}
     [:summary.approval-record__summary
      {"data-on:click" (str "@get('/ui/tool-approvals/" id "/detail')")}
      (approval-status status)
      [:span.approval-record__tool tool-name]
      [:span.approval-record__requester
       (cond-> {}
         requested-by (assoc :title requested-by))
       (if requested-by (ui-render/short-id requested-by) "-")]
      [:span.approval-record__reason (or (not-empty reason) "No reason provided")]
      [:time {:datetime created-at} (ui-render/short-timestamp created-at)]
      [:span.approval-record__chevron {:aria-hidden "true"} "⌄"]]
     [:div.approval-record__detail.approval-record__detail--loading
      {:id (str "approval-detail-" id)}
     [:div.empty-line "Loading details…"]]]))

(defn tool-approvals-status-fragment [approvals limit]
  (let [pending (count (filter #(= "pending" (tool-approvals/effective-status %)) approvals))]
    (ui-render/render
     [:div#tool-approvals-live-status.panel-head__form
      {"data-on-interval__duration.10s"
       (str "@get('/ui/tool-approvals/status?limit=" limit "')")}
      [:span.badge (str pending " pending")]
      [:button {:type "button"
                "data-on:click" (str "@get('/ui/tool-approvals?limit=" limit "')")}
       "Refresh"]])))

(defn tool-approvals-fragment
  ([approvals] (tool-approvals-fragment approvals {:limit 20}))
  ([approvals {:keys [limit] :or {limit 20}}]
   (let [status-rank {"pending" 0 "approved" 1 "denied" 2 "expired" 3}
         approvals* (->> approvals
                         (sort-by #(get status-rank (tool-approvals/effective-status %) 9))
                         vec)
         counts (frequencies (map tool-approvals/effective-status approvals*))]
     (ui-render/render
      [:section#tool-approvals-panel.panel.approvals-page
      [:header.approvals-page__header
       [:div
        [:h1 "Tool Approvals"]
        [:p "Review sensitive actions requested by agents. Open a row for full context and decision controls."]]
       (ui-render/trusted-fragment (tool-approvals-status-fragment approvals* limit))]
      [:div.approval-metrics
       (for [[label status] [["Total" nil]
                             ["Pending" "pending"]
                             ["Approved" "approved"]
                             ["Denied" "denied"]]]
         [:div.approval-metric
          [:span.label label]
          [:strong (str (if status (get counts status 0) (count approvals*)))]] )]
      [:section.approval-table
       [:header.approval-table__head
        [:span "Status"]
        [:span "Tool"]
        [:span "Requested by"]
        [:span "Reason"]
        [:span "Created"]
        [:span {:aria-hidden "true"} ""]]
       (if (seq approvals*)
         [:div.approval-table__body
          (for [approval approvals*]
            (approval-record approval))]
         [:div.approval-table__empty
          [:strong "Queue clear"]
          [:span "No approval requests yet."]])]
      (when (= (count approvals*) limit)
        [:button.chat-history-more
         {:type "button"
          "data-on:click" (str "@get('/ui/tool-approvals?limit=" (min 100 (+ limit 20)) "')")}
         "Load 20 older"])]))))
