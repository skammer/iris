(ns agent.ui.memory
  "Memory workspace fragments for server-rendered UI."
  (:require
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.ui.render :as ui-render]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn memory-prompt-fragment [system]
  (let [{:keys [documents combined]} (memory/read-prompt-memory (:memory-service system))]
    (ui-render/render
     [:div#memory-prompt-panel
      [:h3 "Prompt Memory"]
      [:p.meta (str "documents: " (count documents))]
      (if (seq documents)
        [:div.result.code combined]
        [:div.empty "No prompt memory files found."])])))

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
    [:h3 "Search Results"]
    [:p.meta (str "query: " (:query results)
	                  " | ranked: " (count (:ranked results))
	                  " | messages: " (count (:messages results))
	                  " | events: " (count (:events results))
	                  " | facts: " (count (:facts results)))]
    (if (or (seq (:ranked results))
            (seq (:messages results))
            (seq (:events results))
            (seq (:facts results)))
      [:div.memory-result-list
       (concat
        (for [{:keys [surface score item]} (:ranked results)]
          [:article.result
           [:strong (str "ranked " (name surface))]
           [:div.meta (format "score %.3f" (double score))]
           [:pre.code (ui-render/pretty-json item)]])
        (for [{:keys [subject predicate object scope updated-at]} (:facts results)]
          [:article.result
	           [:strong "fact"]
	           [:div.meta.code (str (get scope :type) "/" (or (get scope :id) "-"))]
	           [:div.code (str subject " " predicate " " object)]
	           [:div.meta updated-at]])
        (for [{:keys [session-id role content created-at]} (:messages results)]
          [:article.result
           [:strong "message"]
           [:div.meta.code (str session-id " / " role)]
           (ui-render/message-content content)
           [:div.meta created-at]])
        (for [{:keys [event-type entity-type entity-id payload created-at]} (:events results)]
          [:article.result
           [:strong "event"]
           [:div.meta (str event-type " / " (or entity-type "system") " / " (or entity-id "-"))]
           [:div.code (json/generate-string payload)]
           [:div.meta created-at]]))]
      [:div.empty "No memory matches."])]))

(defn- fact-row [{:keys [subject predicate object scope updated-at]}]
  [:div.row
   [:span.row__id {:title (str subject
                               (when scope
                                 (str " [" (:type scope) "/" (or (:id scope) "-") "]")))}
    (str subject)]
   [:span.row__meta {:title (str predicate " " object)}
    (str predicate " · " object)]
   [:span.row__time (ui-render/short-timestamp updated-at)]])

(defn memory-workspace-fragment
  ([system] (memory-workspace-fragment system nil))
	   ([system reset-result]
	    (let [memory-service (:memory-service system)
	         health (memory/health-check memory-service)
	         surfaces (memory/list-surfaces memory-service)
	         prompt (memory/read-prompt-memory memory-service)
	         fact-count (get-in health [:facts :count] 0)
	         ;; Store-level call: the memory service clamps :limit to the search
	         ;; max, which would cap this listing at a couple dozen facts.
	         facts (sqlite/search-memory-facts (:store memory-service) ""
	                                           {:all-scopes? true :limit 100})]
     (ui-render/render
      [:section#memory-workspace.workspace-grid.memory-workspace
       [:div.memory-left-stack
        [:section.panel.memory-overview
         [:h2 "Memory"]
         [:div.memory-stats
          (memory-health-stat "prompt" (get-in health [:prompt :document-count] 0))
          (memory-health-stat "facts" fact-count)
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
             (memory-surface-row surface))]]
         [:div#memory-prompt-panel.memory-docs
          [:h3 "Prompt Memory"]
          [:p.meta (str "documents: " (count (:documents prompt)))]
          (if (seq (:documents prompt))
            [:div.memory-result-list
             (for [{:keys [path content]} (:documents prompt)]
               [:details.result
                [:summary [:strong path]]
                [:pre.code content]])]
            [:div.empty "No prompt memory files found."])]]

        [:section.panel.memory-lab
         [:h2 "Retrieval Lab"]
         [:form#memory-tool-form.memory-tool-form
          [:h3 "Memory Tool"]
          [:div.memory-form-grid
           [:select {:name "action"}
            [:option {:value "search"} "search"]
            [:option {:value "save-fact"} "save-fact"]
            [:option {:value "read-vault"} "read-vault"]
            [:option {:value "write-vault"} "write-vault"]]
           [:input {:type "text" :name "query" :placeholder "query"}]
           [:input {:type "text" :name "limit" :value "10" :placeholder "limit"}]
           [:select {:name "scope_type"}
            [:option {:value ""} "scope auto"]
            [:option {:value "global"} "global"]
            [:option {:value "session"} "session"]
            [:option {:value "agent"} "agent"]]
           [:input {:type "text" :name "scope_id" :placeholder "scope id"}]
           [:input {:type "text" :name "subject" :placeholder "subject"}]
           [:input {:type "text" :name "predicate" :placeholder "predicate"}]
           [:input {:type "text" :name "object" :placeholder "object"}]
           [:input {:type "text" :name "path" :placeholder "vault path"}]]
          [:textarea {:name "content" :rows 4 :placeholder "vault content"}]
          [:div.actions
           [:button {:type "button"
                     "data-on:click" "@post('/ui/memory/tool', {contentType: 'form', selector: '#memory-tool-form'})"}
            "Run"]]]
         [:div#memory-tool-output.empty "No memory tool output."]
         [:form#memory-search-form.memory-tool-form
          [:h3 "Memory Search"]
          [:div.compact-form-row
           [:input {:type "text" :name "query" :placeholder "search messages, events, facts"}]
           [:button {:type "button"
                     "data-on:click" "@post('/ui/memory/search', {contentType: 'form', selector: '#memory-search-form'})"}
            "Search"]]]
         [:div#memory-search-results-panel.empty "No search output."]]]

       [:section.panel.memory-facts
        [:div.panel-head
         [:h2 "Facts"]
         [:div.panel-head__form
          [:span.count-badge (str fact-count)]
          [:button {:type "button"
                    "data-on:click" "@post('/ui/memory/facts/reset')"}
           "Reset facts"]]]
        (memory-reset-result reset-result)
        (if (seq facts)
          [:div.rows
           (map fact-row facts)]
          [:div.empty-line "none"])
        (when (> fact-count (count facts))
          [:p.meta (str "showing " (count facts) " of " fact-count)])]]))))
