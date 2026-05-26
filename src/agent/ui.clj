(ns agent.ui
  "Server-rendered Datastar UI."
  (:require
   [agent.channels.core :as channel-adapters]
   [agent.chat :as chat]
   [agent.config :as config]
   [agent.memory.core :as memory]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as runtime-trace]
   [agent.runners.core :as runners]
   [agent.runners.docker-podman :as docker-podman]
   [agent.runtime.core :as runtime]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.tools.display :as tool-display]
   [cheshire.core :as json]
   [clojure.string :as str]
   [hiccup2.core :as h])
  (:import
   (java.net URLEncoder)
   (java.time Instant)))

(defn- render [node]
  (str (h/html node)))

(defn- render-many [& nodes]
  (apply str (map render nodes)))

(defn- trusted-fragment [html]
  ;; Invariant: only pass fragments produced by this namespace's render helpers.
  (h/raw html))

(defn- render-message-content [content]
  ;; Markdown intentionally disabled; Hiccup escapes LLM/user text here.
  [:div.code (str content)])

(def ^:private slash-chip-re #"(^|[\t ])\/([A-Za-z0-9][A-Za-z0-9_-]*)")

(defn- render-user-message-content [content]
  (let [text (str content)
        matches (re-seq slash-chip-re text)]
    (if (empty? matches)
      (render-message-content text)
      (loop [idx 0
             remaining matches
             nodes []]
        (if-let [[match lead name] (first remaining)]
          (let [start (.indexOf text match idx)
                slash-start (+ start (count lead))
                end (+ start (count match))]
            (recur end
                   (rest remaining)
                   (cond-> nodes
                     (< idx slash-start) (conj (subs text idx slash-start))
                     true (conj [:span.skill-chip (str "/" name)]))))
          (into [:div.code.message-content--user]
                (cond-> nodes
                  (< idx (count text)) (conj (subs text idx)))))))))

(defn- keyword-label [value]
  (if-let [ns (namespace value)]
    (str ns "/" (name value))
    (name value)))

(defn- json-ready [value]
  (cond
    (keyword? value) (keyword-label value)
    (map? value) (into {}
                       (map (fn [[k v]]
                              [(if (keyword? k) (keyword-label k) k)
                               (json-ready v)]))
                       value)
    (set? value) (mapv json-ready value)
    (sequential? value) (mapv json-ready value)
    :else value))

(defn- pretty-json [value]
  (try
    (json/generate-string (json-ready (if (string? value)
                                        (json/parse-string value true)
                                        value))
                          {:pretty true})
    (catch Exception _
      (if (string? value)
        value
        (binding [*print-namespace-maps* false]
          (pr-str value))))))

(defn- url-encode [value]
  (URLEncoder/encode (str value) "UTF-8"))

(defn- safe-dom-id [prefix value]
  (str prefix "-"
       (str/replace (str value)
                    #"[^A-Za-z0-9_-]"
                    "-")))

(defn- parse-json-or-value [value]
  (try
    (if (string? value)
      (json/parse-string value true)
      value)
    (catch Exception _ value)))

(defn- tool-status-node [status]
  (when status
    [:span {:class (str "tool-status status--" status)} status]))

(defn- tool-detail-template [id title status & body]
  [:template {:id id}
   [:div.tool-detail-content
    [:div.tool-detail-content__meta
     [:span.meta title]
     (tool-status-node status)]
    body]])

(defn- render-tool-call [{:keys [id function]}]
  (let [{:keys [name arguments]} function
        params (parse-json-or-value arguments)
        args (tool-display/params-preview params 800)
        detail-id (safe-dom-id "tool-call-detail" (or id (str name "-" (hash arguments))))]
    [:div.tool-call
     [:button.tool-row
      {:type "button"
       "data-tool-detail" true
       "data-tool-detail-template" detail-id
       "data-tool-detail-title" (str "function: " name)
       "data-tool-detail-status" "requested"}
      [:span.tool-row__main
       [:span.tool-row__name (str "→ " name)]
       (tool-status-node "requested")
       (when id [:span.tool-row__id.meta id])]
      (when-not (str/blank? args)
        [:span.tool-row__args.code args])]
     (tool-detail-template
      detail-id
      (str "function: " name)
      "requested"
      [:h3 "Arguments"]
      [:pre.tool-detail__pre.code (pretty-json params)])]))

(defn- parse-tool-content [content]
  (try
    (when (string? content)
      (json/parse-string content true))
    (catch Exception _ nil)))

(defn- tool-result-summary
  "Builds one-line tool result summary: name · status · args preview."
  [system parsed tool-call-id]
  (let [tool-name (or (:tool-name parsed) "tool")
        status (some-> (:status parsed) name)
        cfg (tool-display/channel-config system :web tool-name)
        args (tool-display/params-preview (:input parsed)
                                          (or (:args-preview-chars cfg)
                                              (:preview-chars cfg)
                                              800))]
    [:span.tool-result__summary
     [:span.tool-result__summary-head
      [:span.tool-result__name tool-name]
      (tool-status-node status)]
     (when-not (str/blank? args)
       [:span.tool-result__args.code args])
     (when tool-call-id [:span.tool-result__id.meta tool-call-id])]))

(defn- render-tool-message [system {:keys [id content created-at tool-call-id]}]
  (let [parsed (parse-tool-content content)
        status (some-> (:status parsed) name)
        tool-name (or (:tool-name parsed) "tool")
        detail-id (safe-dom-id "tool-result-detail" (or id tool-call-id (hash content)))
        body (if parsed
               (pretty-json (dissoc parsed :tool-name :status))
               (pretty-json content))]
    [:article.message.message--tool
     [:div.tool-result
      [:button.tool-row.tool-result__head
       {:type "button"
        "data-tool-detail" true
        "data-tool-detail-template" detail-id
        "data-tool-detail-title" (str "tool: " tool-name)
        "data-tool-detail-status" (or status "")}
       (tool-result-summary system parsed tool-call-id)]
      (tool-detail-template
       detail-id
       (str "tool: " tool-name)
       status
       [:h3 "Result"]
       [:pre.tool-detail__pre.code body])]
     [:div.meta created-at]]))

(defn- render-message
  ([msg] (render-message nil msg))
  ([system {:keys [role content created-at tool-calls metadata excluded-from-context?] :as msg}]
   (let [meta-text (str created-at
                        (when (:queued metadata) " | queued")
                        (when excluded-from-context? " | out-of-context"))]
   (cond
     (= role "tool")
     (render-tool-message system msg)

     (seq tool-calls)
     [:article.message.message--tool-calls
      [:div.message-role {:class role} role]
      (when (seq (str content)) (render-message-content content))
      [:div.tool-calls (for [tc tool-calls] (render-tool-call tc))]
      [:div.meta meta-text]]

     :else
     [:article.message
      [:div.message-role {:class role} role]
      (if (= "user" role)
        (render-user-message-content content)
        (render-message-content content))
      [:div.meta meta-text]]))))

(defn- now-ms []
  (.toEpochMilli (Instant/now)))

(defn- instant-ms [value]
  (when (seq (str value))
    (try
      (.toEpochMilli (Instant/parse (str value)))
      (catch Exception _ nil))))

(defn- run-last-seen-ms [run]
  (or (some-> run :heartbeat :observed-at instant-ms)
      (some-> run :started-at instant-ms)
      (some-> run :created-at instant-ms)))

(def stale-run-threshold-ms 30000)

(defn- stale-run? [run]
  (and (contains? #{"requested" "running"} (:status run))
       (when-let [seen-ms (run-last-seen-ms run)]
         (> (- (now-ms) seen-ms) stale-run-threshold-ms))))

(declare dashboard-fragment
         operator-board-fragment
         sessions-fragment
         session-detail-fragment
         session-messages-fragment
         events-fragment
         logs-fragment
         memory-workspace-fragment
         memory-prompt-fragment
         memory-search-results-fragment
         memory-tool-result-fragment
         memory-graph-result-fragment
         memory-datalog-result-fragment
         tools-fragment
         tool-approvals-fragment
         runs-fragment
         run-detail-body
         run-detail-fragment)

(def ^:private tabs
  [{:key :overview :label "Overview"}
   {:key :chat :label "Chat"}
   {:key :runs :label "Runs"}
   {:key :tools :label "Tools"}
   {:key :memory :label "Memory"}
   {:key :logs :label "Logs"}])

(defn- normalize-tab [value]
  (let [tab (some-> value name str/lower-case keyword)]
    (if (some #(= tab (:key %)) tabs) tab :chat)))

(defn- route-path [{:keys [tab session-id run-id]}]
  (case (normalize-tab tab)
    :overview "/overview"
    :chat (if session-id (str "/chat/" session-id) "/chat")
    :runs (if run-id (str "/runs/" run-id) "/runs")
    :tools "/tools"
    :memory "/memory"
    :logs "/logs"
    "/chat"))

(defn- route-state-from-path [path]
  (let [[segment id] (->> (str/split (or path "") #"/")
                          (remove str/blank?)
                          (take 2))]
    (case segment
      "overview" {:tab :overview}
      "chat" (cond-> {:tab :chat} id (assoc :session-id id))
      "runs" (cond-> {:tab :runs} id (assoc :run-id id))
      "tools" {:tab :tools}
      "memory" {:tab :memory}
      "logs" {:tab :logs}
      {:tab :chat})))

(defn- shell-url [{:keys [tab session-id run-id]}]
  (str "/ui/shell?tab=" (name (normalize-tab tab))
       (when session-id
         (str "&session_id=" (url-encode session-id)))
       (when run-id
         (str "&run_id=" (url-encode run-id)))))

(defn router-state-fragment [path]
  (render [:div#router-state {:hidden true
                              "data-route-path" path}]))

(defn- tab-link [tab active-tab]
  [:button.tab-link
   {:type "button"
    :class (when (= (:key tab) active-tab) "active")
    "data-route" (route-path {:tab (:key tab)})
    "data-on:click" (str "@get('/ui/shell?tab=" (name (:key tab)) "')")}
   (:label tab)])

(defn index-page
  ([] (index-page "/"))
  ([path]
   (let [route-state (route-state-from-path path)]
  (render
   [:html {:lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "iris control plane"]
     [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
     [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin true}]
     [:link {:rel "stylesheet"
             :href "https://fonts.googleapis.com/css2?family=Doto:wght,ROND@400..900,0..100&family=Space+Grotesk:wght@300;400;500;700&family=Space+Mono:wght@400;700&display=swap"}]
     [:link {:rel "stylesheet" :href "/public/app.css"}]
     [:script {:type "module"
               :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.8/bundles/datastar.js"}]
     [:script {:type "module" :src "/public/web-components.js"}]
     ]
    [:body
     [:main
      [:div#shell-fragment
       {"data-init" (str "@get('" (shell-url route-state) "')")}
       "[LOADING...]"]]]]))))

(defn shell-fragment [system active-route]
  (let [route (if (map? active-route) active-route {:tab active-route})
        active-tab (normalize-tab (:tab route))
        session-id (:session-id route)
        run-id (:run-id route)
        storage (sqlite/health-check (:store system))
        runtime-health (runtime/runtime-health (:runtime-service system))
        provider (name (config/active-provider-key (get-in system [:config :llm])))
        session-count (get-in storage [:details :session-count] 0)
        event-count (get-in storage [:details :event-count] 0)
        port (get-in system [:config :api :port])]
    (render
     [:div#shell-fragment.workspace-stack
      (trusted-fragment (router-state-fragment (route-path {:tab active-tab
                                                            :session-id session-id
                                                            :run-id run-id})))
      [:header.shell-header
       [:div.status-bar
        [:div.status-block.status-block--accent
         [:span.status-label "provider"]
         [:span.status-value provider]]
        [:div.status-block.status-block--warning
         [:span.status-label "port"]
         [:span.status-value (str port)]]
        [:div.status-block
         [:span.status-label "sessions"]
         [:span.status-value (str session-count)]]
        [:div.status-block
         [:span.status-label "runs"]
         [:span.status-value (str (:run-count runtime-health))]]
        [:div.status-block.status-block--success
         [:span.status-label "events"]
         [:span.status-value (str event-count)]]]
       [:theme-toggle
        [:button#theme-toggle.theme-toggle
         {:type "button"
          :aria-label "Toggle light or dark mode"
          :title "Toggle light/dark mode"}
         "Dark"]]]
      [:nav.shell-nav
       (for [tab tabs]
         (tab-link tab active-tab))]
      (trusted-fragment (case active-tab
             :chat (render-many
                    [:section.workspace-grid.chat-workspace
                     (trusted-fragment (sessions-fragment system session-id))
                     (trusted-fragment (session-detail-fragment system session-id))])
             :runs (render-many
                    [:section.workspace-grid.two-up
                     (trusted-fragment (runs-fragment system))
                     (trusted-fragment (run-detail-fragment system run-id))])
             :tools (render-many
                     [:section.workspace-grid.tools
                      [:section.panel.stack
                       (trusted-fragment (tools-fragment system))]
                      [:section.panel.stack
                       (trusted-fragment
                        (tool-approvals-fragment
                         (tool-approvals/list-requests (:store system) {:limit 50})))
                       [:div#tool-results-panel.empty "Request approval, approve, then run."]]])
             :memory (memory-workspace-fragment system)
             :logs (render-many
                    [:section.workspace-grid.single
                     (trusted-fragment (logs-fragment system))])
             (render-many
              [:section.workspace-grid.two-up
               (trusted-fragment (dashboard-fragment system))
               (trusted-fragment (operator-board-fragment system))])))])))

(defn dashboard-fragment [system]
  (let [storage (sqlite/health-check (:store system))
        tools-health (tools/registry-health (:tool-registry system))
        memory-health (memory/health-check (:memory-service system))
        adapter-health (channel-adapters/registry-health (:channel-adapter-registry system))
        agent-health (orchestrator/health-check (:orchestrator system))
        federated-peers (orchestrator/list-federated-peers (:orchestrator system))
        runs (runtime/list-runs (:runtime-service system) {:limit 50})
        recent-runs (take 6 runs)
        pending-approvals (count (tool-approvals/list-requests (:store system) {:status "pending" :limit 100}))
        status-counts (reduce (fn [acc run]
                                (update acc (:status run) (fnil inc 0)))
                              {}
                              runs)
        stale-runs (filter stale-run? runs)
        attention-runs (->> recent-runs
                            (filter #(or (contains? #{"failed" "cancelled"} (:status %))
                                         (stale-run? %)))
                            (take 4))
        reload-status (or (some-> system :reload-state deref)
                          {:status :idle})]
    (render
     [:section#dashboard-summary.panel
      {"data-on-interval__duration.10s.leading" "@get('/ui/dashboard')"}
      [:h2 "Runtime Snapshot"]
      [:div.stats
       [:div.stat.stat--wide [:span.label "provider"] [:span.value.provider-value (name (config/active-provider-key (get-in system [:config :llm])))]]
       [:div.stat [:span.label "sessions"] [:span.value (get-in storage [:details :session-count] 0)]]
       [:div.stat [:span.label "events"] [:span.value (get-in storage [:details :event-count] 0)]]
       [:div.stat [:span.label "tools"] [:span.value (:count tools-health)]]
       [:div.stat [:span.label "agents"] [:span.value (:agent-count agent-health)]]]
      [:p.meta
       (str "memory graph enabled: "
            (if (true? (get-in memory-health [:graph :details :enabled])) "yes" "no")
            " | channel adapters: " (:count adapter-health)
            " | federated peers: " (count federated-peers)
            " | sqlite schema: " (get-in storage [:details :schema-version] "?")
            " | approvals: " (get-in storage [:details :tool-approval-count] 0))]
      [:form#system-reload-form
       {:method "post"
        "data-on:submit" "@post('/ui/system/reload', {contentType: 'form', selector: '#system-reload-form'})"}
       [:input {:type "hidden" :name "mode" :value "soft"}]
       [:div.actions
        [:button {:type "submit"} "Reload config"]
        [:span.meta
         (str "reload: " (name (:status reload-status))
              (when-let [mode (:mode reload-status)]
                (str " | " (name mode)))
              (when-let [message (:message reload-status)]
                (str " | " message)))]]]
      [:div.run-grid
       [:div.result
        [:strong "Pending approvals"]
        [:div.value (str pending-approvals)]]
       [:div.result
        [:strong "Recent runs"]
        (if (seq recent-runs)
          [:div.stack
           (for [{:keys [id substrate status created-at]} recent-runs]
             [:div.meta
              (str id " | " substrate " | " status " | " created-at)])]
          [:div.meta "none"])]
       [:div.result
        [:strong "Run status"]
        (if (seq status-counts)
          [:div.stack
           (for [[status count] (sort-by key status-counts)]
             [:div.meta (str status " | " count)])]
          [:div.meta "none"])]
       [:div.result
        [:strong "Attention"]
        (if (seq attention-runs)
          [:div.stack
           (for [{:keys [id status last-error] :as run} attention-runs]
             [:div.meta
              (str id " | " status
                   (when (stale-run? run)
                     " | stale")
                   (when (seq last-error)
                     (str " | " last-error)))])]
          [:div.meta "none"])]
       [:div.result
        [:strong "Stale runs"]
        [:div.value (str (count stale-runs))]]
       ]])))

(defn operator-board-fragment [system]
  (let [runs (runtime/list-runs (:runtime-service system) {:limit 50})
        agents (orchestrator/list-agents (:orchestrator system))
        active-runs (filter #(contains? #{"requested" "running"} (:status %)) runs)
        stale-runs (filter stale-run? runs)
        failed-runs (filter #(contains? #{"failed" "cancelled"} (:status %)) runs)
        approvals (tool-approvals/list-requests (:store system) {:status "pending" :limit 8})
        recent-events-pool (sqlite/list-events (:store system) {:limit 40})
        events (take 8 recent-events-pool)
        interop-events (filter #(str/starts-with? (str (:event-type %)) "agent.interop")
                               recent-events-pool)
        kernel-events (filter #(= "agent.kernel.step.executed" (:event-type %))
                              recent-events-pool)
        federated-peers (orchestrator/list-federated-peers (:orchestrator system))
        interop-policies (->> agents
                              (map (fn [agent]
                                     (orchestrator/describe-agent-interop (:orchestrator system) (:id agent))))
                              (filter #(or (seq (:trusted-peers %))
                                           (seq (:trust-policies %))))
                              (take 6))]
    (render
     [:section#operator-board.panel
      {"data-on-interval__duration.10s.leading" "@get('/ui/operator-board')"}
      [:h2 "Operator Board"]
      [:div.stack
       [:div.result
        [:strong "Active runs"]
       (if (seq active-runs)
         [:div.stack
          (for [{:keys [id substrate status created-at]} (take 6 active-runs)]
             [:button.session-link
              {:type "button"
               "data-route" (route-path {:tab :runs :run-id id})
               "data-on:click" (str "@get('/ui/shell?tab=runs&run_id=" id "')")}
              [:strong id]
              [:div.session-meta (str substrate " | " status)]
              [:div.session-meta created-at]])]
          [:div.meta "none"])]
       [:div.result.diagnostic-result
        [:strong "Stale runs"]
        (if (seq stale-runs)
         [:div.stack
          (for [{:keys [id status heartbeat created-at]} (take 6 stale-runs)]
             [:button.session-link
              {:type "button"
               "data-route" (route-path {:tab :runs :run-id id})
               "data-on:click" (str "@get('/ui/shell?tab=runs&run_id=" id "')")}
              [:strong id]
              [:div.session-meta (str status " | stale")]
              [:div.session-meta (str "last seen | " (or (:observed-at heartbeat) created-at))]])]
          [:div.meta "none"])]
       [:div.result
        [:strong "Approval queue"]
        (if (seq approvals)
          [:div.stack
           (for [{:keys [id tool-name reason created-at]} approvals]
             [:div.meta
              (str id " | " tool-name
                   (when (seq reason)
                     (str " | " reason))
                   " | " created-at)])]
          [:div.meta "none"])]
       [:div.result.diagnostic-result
        [:strong "Failure queue"]
        (if (seq failed-runs)
         [:div.stack
          (for [{:keys [id status last-error finished-at]} (take 6 failed-runs)]
             [:button.session-link
              {:type "button"
               "data-route" (route-path {:tab :runs :run-id id})
               "data-on:click" (str "@get('/ui/shell?tab=runs&run_id=" id "')")}
              [:strong id]
              [:div.session-meta (str status " | " (or finished-at "-"))]
              (when (seq last-error)
                [:div.session-meta last-error])])]
          [:div.meta "none"])]
       [:div.result
        [:strong "Recent events"]
        (if (seq events)
          [:div.stack
           (for [{:keys [event-type entity-id created-at]} events]
             [:div.meta (str event-type " | " (or entity-id "-") " | " created-at)])]
          [:div.meta "none"])]
       [:div.result
        [:strong "Federated peers"]
        (if (seq federated-peers)
          [:div.stack
           (for [{:keys [id status base-url logical-address-prefix]} federated-peers]
             [:div.meta (str id " | " status " | "
                             (or base-url logical-address-prefix))])]
          [:div.meta "none"])]
       [:div.result
        [:strong "Interop policy"]
        (if (seq interop-policies)
          [:div.stack
           (for [{:keys [logical-address trusted-peers trust-policies]} interop-policies]
             [:div.meta
              (str logical-address
                   " | peers " (count trusted-peers)
                   " | policies " (count trust-policies))])]
          [:div.meta "none"])]
       [:div.result
        [:strong "Interop activity"]
        (if (seq interop-events)
          [:div.stack
           (for [{:keys [event-type entity-id created-at]} (take 8 interop-events)]
             [:div.meta (str event-type " | " (or entity-id "-") " | " created-at)])]
          [:div.meta "none"])]
       [:div.result
        [:strong "Kernel receipts"]
        (if (seq kernel-events)
          [:div.stack
           (for [{:keys [entity-id created-at payload]} (take 8 kernel-events)]
             [:div.meta
              (str (or entity-id "-")
                   " | directives " (get payload :directive-count 0)
                   " | receipts " (get payload :receipt-count 0)
                   " | " created-at)])]
          [:div.meta "none"])]]])))

(defn sessions-fragment
  ([system] (sessions-fragment system nil))
  ([system active-session-id]
   (let [sessions (sqlite/list-sessions (:store system))
         active-id (or (not-empty active-session-id)
                       (some-> sessions first :id))]
     (render
      [:aside#sessions-panel.panel.sessions-sidebar
       {"data-on-interval__duration.15s.leading"
        (str "@get('/ui/sessions"
             (when active-id
               (str "?session_id=" (url-encode active-id)))
             "')")}
       [:form#create-session-form.create-session-form
        {"data-on:submit" "@post('/ui/sessions', {contentType: 'form', selector: '#create-session-form'})"
         "data-on:datastar-fetch" "evt.detail.type === 'finished' && evt.currentTarget.reset()"
         "data-indicator" "createSessionLoading"}
        [:div.compact-form-row
         [:input {:type "text" :name "title" :placeholder "new session title"}]
         [:button {:type "submit"
                   "data-attr:disabled" "$createSessionLoading"}
          "New"]]]
       [:h2 "Sessions"]
       (if (seq sessions)
         [:div.session-list
          (for [{:keys [id title created-at]} sessions
                :let [state (chat/session-state system id)]]
            [:button.session-link
             {:type "button"
              :class (when (= id active-id) "session-link--active")
             "data-route" (route-path {:tab :chat :session-id id})
             "data-on:click" (str "@get('/ui/session-detail?session_id=" id "')")}
             [:strong (or title "Untitled session")]
             [:div.session-meta
              (str created-at
                   (when (:loop-active? state) " | loop")
                   (when (:working? state) " | working")
                   (when (pos? (:queued-count state))
                     (str " | queued " (:queued-count state))))]])]
         [:div.empty "No sessions yet."])]))))

(defn- session-target [system session-id]
  (let [sessions (sqlite/list-sessions (:store system))]
    (or (some #(when (= session-id (:id %)) %) sessions)
        (first sessions))))

(defn session-route-path [system session-id]
  (if-let [session (session-target system session-id)]
    (route-path {:tab :chat :session-id (:id session)})
    "/chat"))

(defn session-detail-fragment [system session-id]
  (let [session (session-target system session-id)]
    (render
     (if-not session
       [:section#session-detail-panel.panel
        [:h2 "Transcript"]
        [:div.empty "Create session to start chatting."]]
       (let [state (chat/session-state system (:id session))
             status-visible? (or (:working? state) (:loop-active? state))
             status-label (cond
                            (:loop-active? state) (str "Loop active, " (:loop-label state))
                            (pos? (:queued-count state)) (str "Working, queued " (:queued-count state))
                            :else "Working")
             status-text (cond
                           (:loop-active? state) (:loop-label state)
                           (pos? (:queued-count state)) (str "queued " (:queued-count state))
                           :else "")]
         [:agent-chat-panel#session-detail-panel.panel
          {:data-session-id (:id session)
           "data-init" (str "@get('/ui/session/live?session_id=" (:id session) "', {openWhenHidden: true})")}
          [:div.chat-titlebar
           [:h2 (or (:title session) "Untitled session")]
           [:span.meta.code (:id session)]
           [:span.meta (str (or (:active-provider state) "-")
                            "/"
                            (or (:active-model state) "-")
                            (when (:loop-active? state)
                              (str " | " (:loop-label state)))
                            (when (:working? state) " | working")
                            (when (pos? (:queued-count state))
                              (str " | queued " (:queued-count state))))]]
          (trusted-fragment (session-messages-fragment system (:id session)))
          [:aside#tool-detail-sidebar.tool-detail-sidebar
           {:hidden true
            :aria-label "Tool detail"}
           [:div.tool-detail-sidebar__head
            [:div
             [:h2#tool-detail-sidebar-title "Tool detail"]
             [:div#tool-detail-sidebar-status.meta ""]]
            [:button.tool-detail-sidebar__close
             {:type "button"
              "data-tool-detail-close" true
              :aria-label "Close tool detail"}
             "Close"]]
           [:div#tool-detail-sidebar-body.tool-detail-sidebar__body
            [:div.empty "Select tool row."]]]
          [:form#chat-form
           {"data-on:submit" "@post('/ui/chat', {contentType: 'form'})"
            "data-indicator" "chatLoading"
            "data-class:is-loading" "$chatLoading"
            "data-skill-autocomplete" "true"}
           [:input {:id (str "chat-session-id-" (:id session))
                    :type "hidden"
                    :name "session_id"
                    :value (:id session)}]
           [:auto-grow-textarea {:submit-on-enter true}
            [:textarea.chat-input {:name "prompt"
                                   :data-skill-input "true"
                                   :rows 1
                                   :placeholder "Ask model something concrete"}]]
           [:button {:type "button"
                     "data-on:click" "@post('/ui/chat/stop', {contentType: 'form', selector: '#chat-form'})"}
            "Stop"]
           [:button {:type "submit"
                     "data-attr:disabled" "$chatLoading"}
            "Send"]
           [:div#chat-status.meta.chat-status
            {:style (when-not status-visible? "display:none")
             "data-show" (if status-visible? "true" "$chatLoading")
             :role "status"
             :aria-label status-label}
            [:span.chat-spinner {:aria-hidden true}]
            [:span.chat-status__text status-text]]]])))))

(defn- streaming-message [content]
  [:article.message.message--streaming
   [:div.message-role {:class "assistant"} "assistant"]
   (render-message-content content)
   [:div.meta "streaming…"]])

(defn session-messages-fragment
  ([system session-id]
   (session-messages-fragment system session-id {}))
  ([system session-id opts]
   (let [messages (sqlite/list-messages (:store system) session-id)
         state (chat/session-state system session-id)
         streaming (if (contains? opts :streaming)
                     (:streaming opts)
                     (chat/streaming-content session-id))
         streaming* (when-not (str/blank? (str streaming))
                      (str streaming))]
     (render
      [:chat-stream#session-messages-panel
       (if (or (seq messages) streaming* (:working? state))
         (list*
          [:div.chat-stream__filler]
          (concat
           (for [message messages]
             (render-message system message))
           (cond
             streaming* [(streaming-message streaming*)]
             :else nil)))
         [:div.empty "No messages yet."])]))))

(defn events-fragment [system]
  (render
   [:section#events-panel.panel
    {"data-on-interval__duration.5s.leading" "@get('/ui/events')"}
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

(defn logs-fragment [system]
  (let [events (sqlite/list-events (:store system) {:limit 40})
        trace (:trace system)
        trace-health (runtime-trace/health-check trace)
        trace-events (runtime-trace/load-events trace {:limit 40})]
    (render
     [:section#logs-panel.panel
      {"data-on-interval__duration.5s.leading" "@get('/ui/logs')"}
      [:h2 "Logs"]
      [:div.run-grid
       [:div.result
        [:strong "Event Log"]
        [:div.meta (str "sqlite events | latest " (count events))]
        (if (seq events)
          [:div.event-list
           (for [{:keys [event-type entity-type entity-id created-at payload]} events]
             [:article.event-item
              [:strong event-type]
              [:div.meta (str (or entity-type "system") " / " (or entity-id "-") " / " created-at)]
              [:div.code (json/generate-string payload)]])]
          [:div.empty "No events yet."])]
       [:div.result
        [:strong "Runtime Trace"]
        [:div.meta (str "mode: " (or (some-> (:mode trace-health) name) "none")
                        " | path: " (:path trace-health))]
        (cond
          (not (:enabled trace-health))
          [:div.empty "Trace disabled. Set :trace {:mode :rolling} for local/dev logs."]

          (seq trace-events)
          [:div.event-list
           (for [{:keys [event-type timestamp turn-id channel model success error-message payload]} trace-events]
             [:article.event-item
              [:strong event-type]
              [:div.meta (str (or timestamp "-")
                              " / " (or turn-id "-")
                              " / " (or channel "-")
                              " / " (or model "-")
                              " / success " (if (nil? success) "-" success))]
              (when error-message
                [:div.meta (str "error: " error-message)])
              [:div.code (json/generate-string payload)]])]

          :else
          [:div.empty "Trace enabled, no entries yet."])]]])))

(defn memory-prompt-fragment [system]
  (let [{:keys [documents combined]} (memory/read-prompt-memory (:memory-service system))]
    (render
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
    [:pre.code (pretty-json (cond-> {}
                               input (assoc :input input)
                               query (assoc :query query)
                               opts (assoc :opts opts)
                               args (assoc :args args)
                               details (assoc :details details)))]]])

(defn memory-tool-result-fragment [{:keys [ok? input result source-json] :as payload}]
  (render
   [:div#memory-tool-output
    (if ok?
      [:div
       [:h3 "Memory Tool Result"]
       [:p.meta "ok"]
       [:div.memory-result-grid
        [:div.result
         [:strong "input"]
         [:pre.code (pretty-json input)]]
        [:div.result
         [:strong "output"]
         [:pre.code (str result)]]
        (when source-json
          [:div.result
           [:strong "source json"]
           [:pre.code (pretty-json source-json)]])]]
      (error-result "Memory Tool Result" payload))]))

(defn memory-graph-result-fragment [{:keys [ok? query opts result] :as payload}]
  (render
   [:div#memory-graph-results-panel
    (if ok?
      [:div
       [:h3 "Graph Query Result"]
       [:p.meta (str "query: " (or query "")
                     " | count: " (count result)
                     " | opts: " (pretty-json opts))]
       (if (seq result)
         [:div.memory-result-list
          (for [item result]
            [:article.result
             [:strong (or (:type item) "fact")]
             [:pre.code (pretty-json item)]])]
         [:div.empty "No graph rows."])]
      (error-result "Graph Query Result" payload))]))

(defn memory-datalog-result-fragment [{:keys [ok? result] :as payload}]
  (render
   [:div#memory-datalog-results-panel
    (if ok?
      [:div
       [:h3 "Datalog Result"]
       [:p.meta (str "rows: " (:row-count result) " | shown: " (count (:rows result)))]
       [:div.result
        [:strong "query"]
        [:pre.code (pretty-json (select-keys result [:query :args :limit]))]]
       (if (seq (:rows result))
         [:div.memory-result-list
          (for [row (:rows result)]
            [:article.result
             [:pre.code (pretty-json row)]])]
         [:div.empty "No datalog rows."])]
      (error-result "Datalog Result" payload))]))

(defn- memory-reset-result [{:keys [ok? surface result error details]}]
  (if (nil? ok?)
    [:div#memory-reset-output.empty "No reset output."]
    [:div#memory-reset-output.result
     [:strong (str surface " reset")]
     (if ok?
       [:pre.code (pretty-json result)]
       [:pre.code (pretty-json {:error error
                                :details details})])]))

(defn memory-search-results-fragment [results]
  (render
   [:div#memory-search-results-panel
    [:h3 "Search Results"]
    [:p.meta (str "query: " (:query results)
                  " | ranked: " (count (:ranked results))
                  " | messages: " (count (:messages results))
                  " | events: " (count (:events results))
                  " | facts: " (count (:facts results))
                  " | graph: " (count (:graph results)))]
    (if (or (seq (:ranked results))
            (seq (:messages results))
            (seq (:events results))
            (seq (:facts results))
            (seq (:graph results)))
      [:div.memory-result-list
       (concat
        (for [{:keys [surface score item]} (:ranked results)]
          [:article.result
           [:strong (str "ranked " (name surface))]
           [:div.meta (format "score %.3f" (double score))]
           [:pre.code (pretty-json item)]])
        (for [{:keys [subject predicate object scope updated-at]} (:facts results)]
          [:article.result
           [:strong "fact"]
           [:div.meta.code (str (get scope :type) "/" (or (get scope :id) "-"))]
           [:div.code (str subject " " predicate " " object)]
           [:div.meta updated-at]])
        (for [item (:graph results)]
          [:article.result
           [:strong "graph"]
           [:pre.code (pretty-json item)]])
        (for [{:keys [session-id role content created-at]} (:messages results)]
          [:article.result
           [:strong "message"]
           [:div.meta.code (str session-id " / " role)]
           (render-message-content content)
           [:div.meta created-at]])
        (for [{:keys [event-type entity-type entity-id payload created-at]} (:events results)]
          [:article.result
           [:strong "event"]
           [:div.meta (str event-type " / " (or entity-type "system") " / " (or entity-id "-"))]
           [:div.code (json/generate-string payload)]
           [:div.meta created-at]]))]
      [:div.empty "No memory matches."])]))

(defn memory-workspace-fragment
  ([system] (memory-workspace-fragment system nil))
  ([system reset-result]
   (let [memory-service (:memory-service system)
         health (memory/health-check memory-service)
         surfaces (memory/list-surfaces memory-service)
         graph-enabled? (some #(and (= :graph (:name %)) (:enabled %)) surfaces)
         prompt (memory/read-prompt-memory memory-service)]
     (render
      [:section#memory-workspace.workspace-grid.memory-workspace
       [:section.panel.memory-overview
        [:h2 "Memory"]
        [:div.memory-stats
         (memory-health-stat "prompt" (get-in health [:prompt :document-count] 0))
         (memory-health-stat "facts" (get-in health [:facts :count] 0))
         (memory-health-stat "graph" (if graph-enabled? "on" "off"))
         (memory-health-stat "limit" (str (get-in health [:search :default-limit])
                                          "/"
                                          (get-in health [:search :max-limit])))]
        [:div.actions
         [:button {:type "button"
                   "data-on:click" "@post('/ui/memory/facts/reset')"}
          "Reset facts"]
         [:button {:type "button"
                   :disabled (not graph-enabled?)
                   "data-on:click" "@post('/ui/memory/graph/reset')"}
          "Reset graph"]]
        (memory-reset-result reset-result)
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
         [:h3 "Hybrid Search"]
         [:div.compact-form-row
          [:input {:type "text" :name "query" :placeholder "search messages, events, facts, graph"}]
          [:button {:type "button"
                    "data-on:click" "@post('/ui/memory/search', {contentType: 'form', selector: '#memory-search-form'})"}
           "Search"]]]
        [:div#memory-search-results-panel.empty "No search output."]]

       [:section.panel.memory-graph
        [:h2 "Datalog DB"]
        [:form#memory-graph-form.memory-tool-form
         [:h3 "Graph Explorer"]
         [:div.memory-form-grid
          [:select {:name "mode"}
           [:option {:value "facts"} "facts"]
           [:option {:value "neighbors"} "neighbors"]
           [:option {:value "paths"} "paths"]]
          [:input {:type "text" :name "query" :placeholder "text query"}]
          [:input {:type "text" :name "limit" :value "20" :placeholder "limit"}]
          [:input {:type "text" :name "entity" :placeholder "entity"}]
          [:input {:type "text" :name "depth" :value "1" :placeholder "depth"}]
          [:input {:type "text" :name "from" :placeholder "from"}]
          [:input {:type "text" :name "to" :placeholder "to"}]
          [:input {:type "text" :name "max_depth" :value "4" :placeholder "max depth"}]
          [:input {:type "text" :name "as_of" :placeholder "as-of instant"}]]
         [:label.meta.memory-check
          [:input {:type "checkbox" :name "include_historical"}]
          " include historical"]
         [:div.actions
          [:button {:type "button"
                    "data-on:click" "@post('/ui/memory/graph', {contentType: 'form', selector: '#memory-graph-form'})"}
           "Query graph"]]]
        [:div#memory-graph-results-panel.empty "No graph output."]
        [:form#memory-datalog-form.memory-tool-form
         [:h3 "Raw Datalog"]
         [:textarea {:name "query" :rows 5}
          "[:find ?ident\n :where\n [?e :db/ident ?ident]]"]
         [:input {:type "text" :name "args" :value "[]" :placeholder "args EDN vector"}]
         [:input {:type "text" :name "limit" :value "100" :placeholder "limit"}]
         [:div.actions
          [:button {:type "button"
                    "data-on:click" "@post('/ui/memory/datalog', {contentType: 'form', selector: '#memory-datalog-form'})"}
           "Run Datalog"]]]
        [:div#memory-datalog-results-panel.empty "No datalog output."]]]))))

(defn tools-fragment [system]
  (let [tool-list (tools/list-tools (:tool-registry system))]
    (render
     [:div#tools-panel
      [:h3 "Local Tools"]
      [:p.meta "Sensitive actions create approval requests first. Approval list lives below."]
      [:div.meta (str "available: " (str/join ", " (map (comp name :name) tool-list)))]
      [:form#fs-tool-form
       [:h3 "Filesystem"]
       [:input {:type "hidden" :name "tool" :value "fs"}]
       [:div.dual
        [:select {:name "action"}
         [:option {:value "list"} "list"]
         [:option {:value "read"} "read"]
         [:option {:value "write"} "write"]
         [:option {:value "mkdir"} "mkdir"]
         [:option {:value "delete"} "delete"]]
        [:input {:type "text" :name "path" :value "." :placeholder "path"}]]
       [:textarea {:name "content" :placeholder "content for write action"}]
       [:input {:type "text" :name "reason" :placeholder "why this action is needed"}]
       [:div.actions
        [:button {:type "button"
                  "data-on:click" "@post('/ui/tool-approvals/request', {contentType: 'form', selector: '#fs-tool-form'})"}
         "Request filesystem action"]]]
      [:form#shell-tool-form
       [:h3 "Shell"]
       [:input {:type "hidden" :name "tool" :value "shell"}]
       [:input {:type "text" :name "command" :placeholder "printf hello"}]
       [:input {:type "text" :name "working_dir" :value "." :placeholder "working dir"}]
       [:input {:type "text" :name "reason" :placeholder "why shell is needed"}]
       [:div.actions
        [:button {:type "button"
                  "data-on:click" "@post('/ui/tool-approvals/request', {contentType: 'form', selector: '#shell-tool-form'})"}
         "Request shell action"]]]])))

(defn tool-results-fragment [tool-name status payload]
  (render
   [:div#tool-results-panel
    [:h3 "Tool Result"]
    [:p.meta (str (name tool-name) " / status " status)]
    [:div.result.code (json/generate-string payload {:pretty true})]]))

(defn- approval-actions [approval]
  (concat
   (when (= "pending" (:status approval))
     [[:form {:id (str "approve-" (:id approval))}
       [:input {:type "hidden" :name "actor" :value "operator"}]
       [:input {:type "hidden" :name "reason" :value "approved in ui"}]
       [:button {:type "button"
                 "data-on:click" (str "@post('/ui/tool-approvals/" (:id approval) "/approve', {contentType: 'form', selector: '#approve-" (:id approval) "'})")}
        "Approve"]]
      [:form {:id (str "deny-" (:id approval))}
       [:input {:type "hidden" :name "actor" :value "operator"}]
       [:input {:type "hidden" :name "reason" :value "denied in ui"}]
       [:button {:type "button"
                 "data-on:click" (str "@post('/ui/tool-approvals/" (:id approval) "/deny', {contentType: 'form', selector: '#deny-" (:id approval) "'})")}
        "Deny"]]])
   (when (= "approved" (:status approval))
     [[:form {:id (str "run-" (:id approval))}
       [:button {:type "button"
                 "data-on:click" (str "@post('/ui/tool-approvals/" (:id approval) "/run', {contentType: 'form', selector: '#run-" (:id approval) "'})")}
        "Run"]]])))

(defn tool-approvals-fragment [approvals]
  (render
   [:div#tool-approvals-panel
    [:h3 "Tool Approvals"]
    (if (seq approvals)
      [:div.stack
       (for [approval approvals]
         [:article.result
          [:strong (:tool-name approval)]
          [:div.meta (str (:status approval) " / " (:created-at approval))]
          (when-let [reason (:reason approval)]
            [:div.meta (str "reason: " reason)])
          [:div.code (json/generate-string (:input approval) {:pretty true})]
         [:div.actions (approval-actions approval)]])]
      [:div.empty "No tool approvals yet."])]))

(defn runs-fragment [system]
  (let [runs (runtime/list-runs (:runtime-service system) {:limit 50})]
    (render
     [:section#runs-panel.panel
      {"data-on-interval__duration.10s.leading" "@get('/ui/runs')"}
      [:h2 "Runs"]
      (if (seq runs)
        [:div.stack
         (for [{:keys [id name agent-id substrate status created-at]} runs]
           [:button.session-link
            {:type "button"
             "data-route" (route-path {:tab :runs :run-id id})
             "data-on:click" (str "@get('/ui/run-detail?run_id=" id "')")}
            [:strong (or name id)]
            [:div.session-meta.code (str agent-id " / " substrate)]
            [:div.session-meta (str status " / " created-at)]])]
        [:div.empty "No runs yet."])
      [:form#create-run-form
       [:h3 "Create Run"]
       [:input {:type "text" :name "name" :placeholder "optional name"}]
       [:input {:type "text" :name "agent_id" :placeholder "agent id"}]
       [:select {:name "substrate"}
        [:option {:value "local-unsandboxed"} "local-unsandboxed"]
       [:option {:value "bubblewrap"} "bubblewrap"]
       [:option {:value "docker"} "docker"]
       [:option {:value "podman"} "podman"]
       [:option {:value "seatbelt"} "seatbelt"]]
       [:input {:type "text" :name "image" :placeholder "container image for docker/podman"}]
       [:input {:type "text" :name "command" :placeholder "printf hello or leave blank for child shim"}]
       [:input {:type "text" :name "working_dir" :value "." :placeholder "working dir"}]
       [:label.meta
        [:input {:type "checkbox" :name "share_network"}]
        " share network"]
       [:div.actions
        [:button {:type "button"
                  "data-on:click" "@post('/ui/runs', {contentType: 'form', selector: '#create-run-form'})"}
         "Create + launch"]]]])))

(defn- run-detail-target [system run-id]
  (let [runs (runtime/list-runs (:runtime-service system) {:limit 50})]
    (or (when run-id (runtime/get-run (:runtime-service system) run-id))
        (when-let [candidate (first runs)]
          (runtime/get-run (:runtime-service system) (:id candidate))))))

(defn run-route-path [system run-id]
  (if-let [run (run-detail-target system run-id)]
    (route-path {:tab :runs :run-id (:id run)})
    "/runs"))

(defn- json-result [title value]
  [:div.result
   [:strong title]
   [:div.code (json/generate-string value {:pretty true})]])

(defn run-detail-body [system run-id]
  (let [run (run-detail-target system run-id)
        runner-status (when run
                        (when-let [runner (get (:runner-registry system) (keyword (:substrate run)))]
                          (runners/status runner (:id run))))
        recovery (when run
                   (runtime/recovery-plan (:runtime-service system) (:id run)))
        container-contract (when (and run (#{"docker" "podman"} (:substrate run)))
                             (docker-podman/image-contract (:runner-options run)))
        output-events (when run
                        (->> (sqlite/list-events (:store system)
                                                {:entity-type :agent_run
                                                 :entity-id (:id run)
                                                 :limit 50})
                             (filter #(= "agent.run.output" (:event-type %)))))
        output-lines (map (fn [event]
                            (let [{:keys [stream line]} (:payload event)]
                              (str "[" stream "] " line)))
                          (reverse output-events))
        recent-events (when run
                        (remove #(= "agent.run.output" (:event-type %))
                                (sqlite/list-events (:store system)
                                                    {:entity-type :agent_run
                                                     :entity-id (:id run)
                                                     :limit 12})))
        failure-events (when run
                         (filter (fn [{:keys [event-type payload]}]
                                   (or (#{"agent.run.failed" "agent.run.cancelled"} event-type)
                                       (= "failed" (:status payload))))
                                 recent-events))]
    (render
     (if-not run
       [:div
        [:h2 "Run Detail"]
        [:div.empty "No runs yet."]]
       [:div.stack
        [:div.run-header
         [:div
          [:h2 (or (:name run) (:id run))]
          [:div.meta.code (:id run)]]
         [:div.meta
          "stream "
          [:span.run-live-state.poll {"data-run-live-state" true} "poll"]]]
        [:div.meta
         (str "agent: " (:agent-id run)
              " | substrate: " (:substrate run)
              " | status: " (:status run)
              " | requested: " (:created-at run))]
        (when (seq (:last-error run))
          [:div.result.diagnostic-result
           [:strong "Failure diagnostics"]
           [:div.code (:last-error run)]])
        (when recovery
          [:div.result
           [:strong "Recovery"]
           [:div.code (json/generate-string recovery {:pretty true})]])
        (when container-contract
          [:div.result
           [:strong "Container contract"]
           [:div.code (json/generate-string container-contract {:pretty true})]])
        [:div.run-grid
         (json-result "Runner" runner-status)
         (when-let [heartbeat (:heartbeat run)]
           (json-result "Latest heartbeat" heartbeat))
         (when-let [checkpoint (:checkpoint run)]
           (json-result "Latest checkpoint" checkpoint))
         (when-let [commands (seq (:pending-commands run))]
           (json-result "Pending commands" commands))]
        [:div.result
         [:strong "Recent output"]
         [:div#run-output-panel.code
          {:data-run-output-tail true}
          (if (seq output-lines)
            (str/join "\n" output-lines)
            "[waiting for output]")]]
        (when-let [events (seq failure-events)]
          [:div.result.diagnostic-result
           [:strong "Recent failures"]
           [:div.stack
            (for [{:keys [event-type created-at payload]} events]
              [:article.event-item
               [:strong event-type]
               [:div.code (json/generate-string payload)]
               [:div.meta created-at]])]])
        (when-let [events (seq recent-events)]
          [:div#run-events-panel.result
           {:data-run-events-list true}
           [:strong "Recent events"]
           [:div.stack
            (for [{:keys [event-type created-at payload]} events]
              [:article.event-item
               [:strong event-type]
               [:div.code (json/generate-string payload)]
               [:div.meta created-at]])]])
        [:div.actions
         [:form {:id (str "run-launch-" (:id run))}
          [:button {:type "button"
                    "data-on:click" (str "@post('/ui/runs/" (:id run) "/launch', {contentType: 'form', selector: '#run-launch-" (:id run) "'})")}
           "Launch"]]
         [:form {:id (str "run-recover-" (:id run))}
          [:button {:type "button"
                    "data-on:click" (str "@post('/v1/runs/" (:id run) "/recover')")}
           "Recover"]]
         [:form {:id (str "run-cancel-" (:id run))}
          [:button {:type "button"
                    "data-on:click" (str "@post('/ui/runs/" (:id run) "/signal', {contentType: 'form', selector: '#run-cancel-" (:id run) "'})")}
           "Cancel"]]]
        (json-result "Catch-up"
                     {:heartbeats (runtime/list-heartbeats (:runtime-service system) (:id run) {:limit 5})
                      :checkpoints (runtime/list-checkpoints (:runtime-service system) (:id run) {:limit 5})
                      :commands (runtime/list-commands (:runtime-service system) (:id run) {:limit 5})
                      :events (sqlite/list-events (:store system)
                                                  {:entity-type :agent_run
                                                   :entity-id (:id run)
                                                   :limit 10})})]))))

(defn run-detail-fragment [system run-id]
  (let [run (run-detail-target system run-id)]
    (render
     (if-not run
       [:section#run-detail-panel.panel
        [:h2 "Run Detail"]
        [:div.empty "No runs yet."]]
       [:agent-run-panel#run-detail-panel.panel
        {:data-run-id (:id run)
         :data-live-state "live"
         "data-init" (str "@get('/ui/run-detail/live?run_id=" (:id run) "', {openWhenHidden: true})")}
        [:div#run-detail-body
         (trusted-fragment (run-detail-body system (:id run)))]]))))
