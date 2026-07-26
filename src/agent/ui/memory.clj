(ns agent.ui.memory
  "Memory workspace fragments for server-rendered UI."
  (:require
   [agent.memory.core :as memory]
   [agent.memory.magi-review :as magi-review]
   [agent.persistence.sqlite :as sqlite]
   [agent.ui.render :as ui-render]
   [clojure.string :as str]))

(defn- compact-bool [value]
  (if value "yes" "no"))

(defn- memory-health-stat [label value]
  [:div.memory-stat
   [:span.label label]
   [:span.value (str value)]])

(defn- memory-surface-row [{surface-name :name surface-type :type
                            :keys [writable enabled paths default-limit max-limit]}]
  [:tr
   [:td.code (name surface-name)]
   [:td.code (name surface-type)]
   [:td (compact-bool writable)]
   [:td (cond
          (nil? enabled) "-"
          (seq? enabled) (compact-bool (seq enabled))
          :else (compact-bool enabled))]
   [:td.code (str (or default-limit "-")
                  (when max-limit (str "/" max-limit)))]
   [:td.code (when (seq paths) (str/join "\n" paths))]])

(defn- error-result [title {:keys [input query opts args error details]}]
  [:div
   [:h3 title]
   [:p.meta "error"]
   [:div.result.diagnostic-result
    [:strong error]
    [:pre.code (ui-render/pretty-json (cond-> {}
                                      input (assoc :input input)
                                      query (assoc :query query)
                                      opts (assoc :opts opts)
                                      args (assoc :args args)
                                      details (assoc :details details)))]]])

(defn memory-tool-result-fragment [{:keys [ok? input result source-json] :as payload}]
  (ui-render/render
   [:div#memory-tool-output
    (if ok?
      [:div
       [:h3 "Memory Tool Result"]
       [:p.meta "ok"]
       [:div.memory-result-grid
        [:div.result
         [:strong "input"]
         [:pre.code (ui-render/pretty-json input)]]
        [:div.result
         [:strong "output"]
         [:pre.code (str result)]]
        (when source-json
          [:div.result
           [:strong "source json"]
           [:pre.code (ui-render/pretty-json source-json)]])]]
      (error-result "Memory Tool Result" payload))]))

(defn- memory-reset-result [{:keys [ok? surface result error details]}]
  (if (nil? ok?)
    ;; Keep the patch target in the DOM, but show nothing until a reset runs.
    [:div#memory-reset-output {:hidden true}]
    [:div#memory-reset-output.result
     [:strong (str surface " reset")]
     (if ok?
       [:pre.code (ui-render/pretty-json result)]
       [:pre.code (ui-render/pretty-json {:error error
                                         :details details})])]))

(defn memory-search-results-fragment [results]
  (ui-render/render
   [:div#memory-search-results-panel
    [:h3 "Recall Results"]
    [:p.meta (str "query: " (:query results)
                  " | results: " (count (:results results))
                  " | messages: " (get-in results [:surface-counts :messages] 0)
                  " | events: " (get-in results [:surface-counts :events] 0)
                  " | vault chunks: " (get-in results [:surface-counts :vault-chunks] 0))]
    (if (seq (:results results))
      [:div.memory-result-list
       (for [{:keys [surface type id scope status text score source reason why]} (:results results)]
         [:article.result
          [:strong (str (name surface) " " (name type))]
          [:div.meta.code
           (str "id " (or id "-")
                " | score " (format "%.3f" (double score))
                " | " (name status)
                " | " (name reason)
                (when scope
                  (str " | " (name (:type scope)) "/" (or (:id scope) "-"))))]
          (ui-render/message-content text)
          [:details
           [:summary "source / why"]
           [:pre.code (ui-render/pretty-json {:source source
                                              :why why})]]])]
      [:div.empty "No memory matches."])]))

(defn- vault-note-action [idx path label status scope class-name]
  (let [form-id (str "vault-note-action-" idx "-" status "-" scope)]
    [:form.inline-form {:id form-id}
     [:input {:type "hidden" :name "path" :value path}]
     [:input {:type "hidden" :name "status" :value status}]
     [:input {:type "hidden" :name "scope" :value scope}]
     [:button {:type "button"
               :class class-name
               "data-on:click" (str "@post('/ui/memory/vault/status', "
                                    "{contentType: 'form', selector: '#" form-id "'})")}
      label]]))

(defn- vault-note-magi-action [idx path action label class-name]
  (let [form-id (str "vault-note-magi-" idx "-" action)]
    [:form.inline-form {:id form-id}
     [:input {:type "hidden" :name "path" :value path}]
     [:input {:type "hidden" :name "action" :value action}]
     [:button {:type "button"
               :class class-name
               "data-on:click" (str "@post('/ui/memory/vault/magi', "
                                    "{contentType: 'form', selector: '#" form-id "'})")}
      label]]))

(def ^:private vault-note-folders
  ["inbox" "preferences" "decisions" "projects" "runbooks" "sessions" "references" "archive"])

(defn- vault-note-move-form [idx path]
  (let [form-id (str "vault-note-move-" idx)]
    [:form.inline-form {:id form-id}
     [:input {:type "hidden" :name "path" :value path}]
     [:select {:name "folder" :aria-label "Destination folder"}
      (for [folder vault-note-folders]
        [:option {:value folder} folder])]
     [:button {:type "button"
               "data-on:click" (str "@post('/ui/memory/vault/move', "
                                    "{contentType: 'form', selector: '#" form-id "'})")}
      "Move"]]))

(declare source-value)

(defn- source-key [value]
  (if (keyword? value) (name value) (str value)))

(defn- source-value [value]
  (cond
    (map? value)
    [:dl.memory-source-map
     (for [[k v] (sort-by (comp str key) value)]
       [:div
        [:dt (source-key k)]
        [:dd (source-value v)]])]

    (sequential? value)
    (if (seq value)
      [:ol.memory-source-list
       (for [item value]
         [:li (source-value item)])]
      [:span.memory-source-empty "none"])

    (nil? value) [:span.memory-source-empty "not set"]
    (boolean? value) [:span (compact-bool value)]
    :else [:code (str value)]))

(defn- source-field [label value]
  [:div.memory-source-field
   [:dt label]
   [:dd (source-value value)]])

(defn- vault-note-actions [system idx path status scope]
  (let [scope* (or scope "global")
        candidate? (= status "candidate")
        magi-enabled? (and candidate?
                           (magi-review/enabled? system)
                           (magi-review/scope-allowed? system scope*))
        review-actions
        (case status
          "approved"
          [(vault-note-action idx path "Mark candidate" "candidate" scope* nil)
           (vault-note-action idx path "Reject" "rejected" scope* "memory-action--danger")]

          "rejected"
          [(vault-note-action idx path "Restore candidate" "candidate" scope* nil)
           (vault-note-action idx path "Approve" "approved" scope* "memory-action--primary")]

          [(vault-note-action idx path "Approve" "approved" scope* "memory-action--primary")
           (when-not (= scope* "global")
             (vault-note-action idx path "Approve global" "approved" "global" nil))
           (vault-note-action idx path "Reject" "rejected" scope* "memory-action--danger")])]
    [:footer.memory-note-actions
     [:span.memory-note-actions__label "Review actions"]
     [:div.memory-note-actions__controls
      (when magi-enabled?
        [:div.memory-note-action-group
         [:span "MAGI"]
         (remove nil?
                 [(when (magi-review/review-applies? system)
                    (vault-note-magi-action idx path "review" "Review"
                                            "memory-action--magi"))
                  (vault-note-magi-action idx path "advice" "Advice" nil)])])
      [:div.memory-note-action-group
       [:span "Manual"]
       (remove nil? review-actions)]
      [:div.memory-note-action-group
       [:span "File"]
       (vault-note-move-form idx path)]]]))

(defn- magi-verdict [review]
  (when-let [payload (:payload review)]
    [:div.memory-note-magi-verdict
     [:span.badge {:class (str "memory-note-magi-verdict--"
                               (or (some-> (:decision payload) name) "unknown"))}
      (str "MAGI " (or (some-> (:decision payload) name str/upper-case) "-"))]
     [:span (or (:reason payload) "No reason")]
     (when (:applied payload)
       [:span.memory-note-magi-applied "applied"])]))

(defn- vault-note-row [system reviews idx {:keys [path id title type iris-status iris-scope updated-at
                                                  iris-confidence origins frontmatter description]}]
  [:article.memory-note-card {:data-status (or iris-status "unknown")}
   [:header.memory-note-card__header
    [:div
     [:h4 {:title path} (or title path)]
     [:span.memory-note-type (or type "Reference")]]
    [:time {:datetime updated-at} (ui-render/short-timestamp updated-at)]]
   [:div.memory-note-badges
    [:span.badge.memory-note-status (or iris-status "unknown")]
    [:span.badge (str "scope " (or iris-scope "-"))]
    [:span.badge (str "confidence " (or iris-confidence "-"))]]
   (when-not (str/blank? description)
     [:p.memory-note-description description])
   (magi-verdict (get reviews (or id path)))
   [:details.memory-note-source
    [:summary "Source details"]
    [:dl.memory-source-details
     (source-field "Path" path)
     (source-field "Description" description)
     (source-field "Origins" origins)
     (source-field "Metadata" frontmatter)]]
   (vault-note-actions system idx path iris-status iris-scope)])

(defn- vault-note-group [system reviews title status notes start-idx]
  (when (seq notes)
    [:section.memory-note-group {:data-status status}
     [:header.memory-note-group__header
      [:h3 title]
      [:span.count-badge (count notes)]]
     [:div.memory-note-list
      (map-indexed (fn [idx note]
                     (vault-note-row system reviews (+ start-idx idx) note))
                   notes)]]))

(defn- vault-note-groups [system reviews notes]
  (let [by-status (group-by #(or (:iris-status %) "unknown") notes)
        candidates (get by-status "candidate")
        approved (get by-status "approved")
        other-statuses (sort (remove #{"candidate" "approved"} (keys by-status)))]
    [:div.memory-note-groups
     (vault-note-group system reviews "Candidates" "candidate" candidates 0)
     (vault-note-group system reviews "Approved" "approved" approved (count candidates))
     (for [[offset status] (map-indexed vector other-statuses)]
       (vault-note-group system reviews (str/capitalize status)
                         status
                         (get by-status status)
                         (+ (count candidates) (count approved) (* 100 offset))))]))

(defn- memory-update-action [idx update-id action label class-name]
  (let [form-id (str "memory-update-magi-" idx "-" action)]
    [:form.inline-form {:id form-id}
     [:input {:type "hidden" :name "update_id" :value update-id}]
     [:input {:type "hidden" :name "action" :value action}]
     [:button {:type "button"
               :class class-name
               "data-on:click" (str "@post('/ui/memory/vault/magi-update', "
                                    "{contentType: 'form', selector: '#" form-id "'})")}
      label]]))

(defn- memory-update-list [updates]
  (when (seq updates)
    [:section.panel.memory-overview
     [:h2 "Pending Memory Updates"]
     [:div.memory-note-list
      (map-indexed
       (fn [idx update]
         [:article.memory-note-card
          [:header.memory-note-card__header
           [:h4 (str (:target-id update) " update")]
           [:span.badge.memory-note-status (:status update)]]
          [:pre.code (:diff update)]
          [:footer.memory-note-actions
           [:div.memory-note-actions__controls
            (memory-update-action idx (:id update) "review" "Review"
                                  "memory-action--magi")
            (memory-update-action idx (:id update) "advice" "Advice" nil)]]])
       updates)]]))

(defn- review-row [kind note]
  [:div.row.memory-review-row
   [:span.row__id {:title (:path note)}
    (str kind ": " (or (:title note) (:id note) (:path note)))]
   [:span.row__meta
    (str (or (:type note) "-")
         " | " (or (:iris-status note) "-")
         " | " (or (:iris-scope note) "-")
         " | c=" (or (:iris-confidence note) "-"))]
   [:span.row__time (ui-render/short-timestamp (:updated-at note))]])

(defn- quality-panel [quality]
  (let [conflict-notes (mapcat :notes (:conflicts quality))
        queue (concat
               (map #(vector "candidate" %) (:candidate-notes quality))
               (map #(vector "approved in inbox" %) (:approved-inbox-notes quality))
               (map #(vector "low confidence" %) (:low-confidence-notes quality))
               (map #(vector (str "stale " (name (:stale-reason %))) %) (:stale-notes quality))
               (map #(vector "broken origin" %) (:broken-origin-notes quality))
               (map #(vector "orphan note" %) (:orphan-notes quality))
               (map #(vector "empty chunks" %) (:notes-without-chunks quality))
               (map #(vector "conflict" %) conflict-notes))]
    [:section.panel.memory-overview
     [:h2 "Memory Quality"]
     [:div.memory-stats
      (memory-health-stat "review" (:review-queue-count quality))
      (memory-health-stat "candidates" (:candidate-backlog quality))
      (memory-health-stat "inbox drift" (count (:approved-inbox-notes quality)))
      (memory-health-stat "conflicts" (count (:conflicts quality)))
      (memory-health-stat "stale" (count (:stale-notes quality)))
      (memory-health-stat "orphans" (+ (count (:orphan-notes quality))
                                       (count (:orphan-chunks quality))))]
     [:details
      [:summary "metrics"]
      [:pre.code (ui-render/pretty-json
                  (select-keys quality
                               [:note-count-by-type
                                :note-count-by-status
                                :origin-count-by-type
                                :embedding-coverage
                                :recall-latency]))]]
     [:h3 "Review Queue"]
     (if (seq queue)
       [:div.rows
        (for [[kind note] (take 20 queue)]
          (review-row kind note))]
       [:div.empty-line "none"])]))

(defn- scratchpad-panel [scratchpad]
  [:section.panel.memory-overview
   [:h2 "Scratchpad"]
   (if-let [error (:error scratchpad)]
     [:div.result.diagnostic-result
      [:strong "error"]
      [:pre.code error]]
     [:div
      [:div.memory-stats
       (memory-health-stat "scope" (get-in scratchpad [:scope :type] "global"))
       (memory-health-stat "chars" (count (:content scratchpad)))
       (memory-health-stat "revision" (subs (:revision scratchpad) 0 12))]
      (if (str/blank? (:content scratchpad))
        [:div.empty-line "empty"]
        [:pre.code.scratchpad-preview (:content scratchpad)])])])

(defn- retrieval-lab-panel []
  [:section.panel.memory-lab
   [:h2 "Retrieval Lab"]
   [:form#memory-tool-form.memory-tool-form
    [:h3 "Memory Tool"]
    [:div.memory-form-grid
     [:select {:name "action"}
      [:option {:value "recall"} "recall"]
      [:option {:value "vault-search"} "vault-search"]
      [:option {:value "scratchpad-read"} "scratchpad-read"]
      [:option {:value "scratchpad-search"} "scratchpad-search"]
      [:option {:value "scratchpad-replace"} "scratchpad-replace"]]
     [:input {:type "text" :name "query" :placeholder "query"}]
     [:input {:type "text" :name "limit" :value "10" :placeholder "limit"}]
     [:select {:name "scope_type"}
      [:option {:value ""} "scope auto"]
      [:option {:value "global"} "global"]
      [:option {:value "session"} "session"]
      [:option {:value "agent"} "agent"]]
     [:input {:type "text" :name "scope_id" :placeholder "scope id"}]
     [:input {:type "text" :name "expected_revision" :placeholder "scratchpad revision"}]]
    [:textarea {:name "old_text" :rows 4 :placeholder "scratchpad old_text"}]
    [:textarea {:name "new_text" :rows 4 :placeholder "scratchpad new_text"}]
    [:div.actions
     [:button {:type "button"
               "data-on:click" "@post('/ui/memory/tool', {contentType: 'form', selector: '#memory-tool-form'})"}
      "Run"]]]
   [:div#memory-tool-output.empty "No memory tool output."]
   [:form#memory-search-form.memory-tool-form
    [:h3 "Memory Recall"]
    [:div.compact-form-row
     [:input {:type "text" :name "query" :placeholder "recall messages, events, vault notes"}]
     [:button {:type "button"
               "data-on:click" "@post('/ui/memory/search', {contentType: 'form', selector: '#memory-search-form'})"}
      "Recall"]]]
   [:div#memory-search-results-panel.empty "No recall output."]])

(defn memory-workspace-fragment
  ([system] (memory-workspace-fragment system nil))
	   ([system reset-result]
	    (let [memory-service (:memory-service system)
	         health (memory/health-check memory-service)
          quality (:quality health)
	         surfaces (memory/list-surfaces memory-service)
		         notes (sqlite/list-vault-notes (:store memory-service) {:limit 50})
          updates (sqlite/list-memory-note-updates (:store memory-service)
                                                   {:status "pending" :limit 50})
          reviews (->> (sqlite/list-events (:store memory-service)
                                           {:event-type magi-review/review-event-type
                                            :entity-type :vault_note
                                            :limit 1000})
                       (reduce (fn [by-note event]
                                 (if (contains? by-note (:entity-id event))
                                   by-note
                                   (assoc by-note (:entity-id event) event)))
                               {}))
          global-scratchpad (try
                              (memory/read-scratchpad memory-service {:scope {:type :global}})
                              (catch Exception e
                                {:error (.getMessage e)}))]
     (ui-render/render
      [:section#memory-workspace.workspace-grid.memory-workspace
       [:div.memory-left-stack
        [:section.panel.memory-overview
         [:h2 "Memory"]
         [:div.memory-stats
          (memory-health-stat "notes" (get-in health [:vault :note-count] 0))
          (memory-health-stat "limit" (str (get-in health [:search :default-limit])
                                           "/"
                                           (get-in health [:search :max-limit])))]
         [:table.memory-table
          [:thead
           [:tr
            [:th "surface"]
            [:th "type"]
            [:th "write"]
            [:th "on"]
            [:th "limit"]
            [:th "paths"]]]
          [:tbody
           (for [surface surfaces]
             (memory-surface-row surface))]]]

        [:section.panel.memory-overview
         [:h2 "Vault"]
         [:div.memory-stats
          (memory-health-stat "notes" (get-in health [:vault :note-count] 0))
          (memory-health-stat "chunks" (get-in health [:vault :chunk-count] 0))]
         [:button {:type "button"
                   "data-on:click" "@post('/ui/memory/vault/reindex')"}
          "Audit & Reindex"]]

        (retrieval-lab-panel)

        (quality-panel quality)

        (scratchpad-panel global-scratchpad)]

       [:div.memory-right-stack
        [:section.panel.memory-overview
         [:h2 "Vault Notes"]
         (if (seq notes)
           (vault-note-groups system reviews notes)
	           [:div.empty-line "none"])]
        (memory-update-list updates)
        (memory-reset-result reset-result)]]))))
