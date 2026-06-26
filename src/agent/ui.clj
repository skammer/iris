(ns agent.ui
  "Server-rendered Datastar UI. Builds dashboard, chat, tools, memory, events,
   logs, and approval fragments from current system state for live SSE patches."
  (:require
   [agent.build-info :as build-info]
   [agent.channels.core :as channel-adapters]
   [agent.chat :as chat]
   [agent.config :as config]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as runtime-trace]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.ui.memory :as ui-memory]
   [agent.ui.render :as ui-render]
   [cheshire.core :as json]
   [clojure.string :as str]))

(declare dashboard-fragment
         operator-board-fragment
         sessions-fragment
         session-detail-fragment
         session-messages-fragment
         events-fragment
         magi-fragment
         logs-fragment
	         memory-workspace-fragment
	         memory-search-results-fragment
	         memory-tool-result-fragment
		         tools-fragment
	         tool-approvals-fragment)

(def ^:private tabs
  [{:key :overview :label "Overview"}
   {:key :chat :label "Chat"}
   {:key :tools :label "Tools"}
   {:key :memory :label "Memory"}
   {:key :magi :label "MAGI"}
   {:key :logs :label "Logs"}])

(def memory-search-results-fragment ui-memory/memory-search-results-fragment)
(def memory-tool-result-fragment ui-memory/memory-tool-result-fragment)
(def memory-workspace-fragment ui-memory/memory-workspace-fragment)

(defn- normalize-tab [value]
  (let [tab (some-> value name str/lower-case keyword)]
    (if (some #(= tab (:key %)) tabs) tab :chat)))

(defn- route-path [{:keys [tab session-id]}]
  (case (normalize-tab tab)
    :overview "/overview"
    :chat (if session-id (str "/chat/" session-id) "/chat")
    :tools "/tools"
    :memory "/memory"
    :magi "/magi"
    :logs "/logs"
    "/chat"))

(defn- route-state-from-path [path]
  (let [[segment id] (->> (str/split (or path "") #"/")
                          (remove str/blank?)
                          (take 2))]
    (case segment
      "overview" {:tab :overview}
      "chat" (cond-> {:tab :chat} id (assoc :session-id id))
      "tools" {:tab :tools}
      "memory" {:tab :memory}
      "magi" {:tab :magi}
      "logs" {:tab :logs}
      {:tab :chat})))

(defn- shell-url [{:keys [tab session-id]}]
  (str "/ui/shell?tab=" (name (normalize-tab tab))
       (when session-id
         (str "&session_id=" (ui-render/url-encode session-id)))))

(defn router-state-fragment [path]
  (ui-render/render [:div#router-state {:hidden true
                              "data-route-path" path}]))

;; Static assets are served with a 1h cache; the version param makes each
;; server boot serve fresh CSS/JS instead of waiting out stale caches.
(defonce ^:private asset-version
  (str (System/currentTimeMillis)))

(defn- asset-href [path]
  (str path "?v=" asset-version))

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
  (ui-render/render
   [:html {:lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "iris control plane"]
     [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
     [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin true}]
     [:link {:rel "stylesheet"
             :href "https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300;400;500;700&family=Space+Mono:wght@400;700&display=swap"}]
     [:link {:rel "stylesheet" :href (asset-href "/public/app.css")}]
     [:link {:rel "stylesheet" :href (asset-href "/public/katex/katex.min.css")}]
     [:script {:defer true :src (asset-href "/public/katex/katex.min.js")}]
     [:script {:defer true :src (asset-href "/public/katex/auto-render.min.js")}]
     [:script {:type "module"
               :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.8/bundles/datastar.js"}]
     [:script {:type "module" :src (asset-href "/public/web-components.js")}]
     ]
    [:body
     [:main
      [:div#shell-fragment
       {"data-init" (str "@get('" (shell-url route-state) "')")}
       ;; data-shimmer-text, not data-text: Datastar owns data-text as an
       ;; expression attribute and would evaluate the content as JS.
       [:span.t-shimmer.boot-shimmer {"data-shimmer-text" "[LOADING...]"} "[LOADING...]"]]]]]))))

(defn shell-fragment [system active-route]
  (let [route (if (map? active-route) active-route {:tab active-route})
        active-tab (normalize-tab (:tab route))
        session-id (:session-id route)
        storage (sqlite/health-check (:store system))
        provider (name (config/active-provider-key (get-in system [:config :llm])))
        session-count (get-in storage [:details :session-count] 0)
        event-count (get-in storage [:details :event-count] 0)
        port (get-in system [:config :api :port])]
    (ui-render/render
     [:div#shell-fragment.workspace-stack
      (ui-render/trusted-fragment (router-state-fragment (route-path {:tab active-tab
                                                            :session-id session-id})))
      [:header.shell-header
       [:div.status-bar
        [:div.status-block.status-block--accent
         (ui-render/status-dot "running")
         [:span.status-label "provider"]
         [:span.status-value provider]]
        [:div.status-block.status-block--warning
         [:span.status-label "port"]
         [:span.status-value (str port)]]
        [:div.status-block
         [:span.status-label "sessions"]
         [:span.status-value (str session-count)]]
        [:div.status-block.status-block--success
         [:span.status-label "events"]
         [:span.status-value (str event-count)]]]
       [:theme-toggle
        [:button#theme-toggle.theme-toggle
         {:type "button"
          :aria-label "Toggle light or dark mode"
          :title "Toggle light/dark mode"}
         "Dark"]]]
      [:nav#shell-nav.shell-nav
       [:span.shell-nav__pill {:aria-hidden "true"}]
       (for [tab tabs]
         (tab-link tab active-tab))]
      (ui-render/trusted-fragment (case active-tab
             :chat (ui-render/render-many
                    [:section.workspace-grid.chat-workspace
                     (ui-render/trusted-fragment (sessions-fragment system session-id))
                     (ui-render/trusted-fragment (session-detail-fragment system session-id))])
	             :tools (ui-render/render-many
                     [:section.workspace-grid.tools
                      [:section.panel.stack
                       (ui-render/trusted-fragment (tools-fragment system))]
                      [:section.panel.stack
                       (ui-render/trusted-fragment
                        (tool-approvals-fragment
                         (tool-approvals/list-requests (:store system) {:limit 50})))
                       [:div#tool-results-panel.empty "Request approval, approve, then run."]]])
             :memory (memory-workspace-fragment system)
             :magi (ui-render/render-many
                    [:section.workspace-grid.single
                     (ui-render/trusted-fragment (magi-fragment system))])
             :logs (ui-render/render-many
                    [:section.workspace-grid.single
                     (ui-render/trusted-fragment (logs-fragment system))])
             (ui-render/render-many
              [:section.workspace-grid.two-up
               (ui-render/trusted-fragment (dashboard-fragment system))
               (ui-render/trusted-fragment (operator-board-fragment system))])))])))

(defn dashboard-fragment [system]
  (let [storage (sqlite/health-check (:store system))
        build (build-info/read-build-info)
        llm-config (get-in system [:config :llm])
        tools-health (tools/registry-health (:tool-registry system))
        memory-health (memory/health-check (:memory-service system))
        adapter-health (channel-adapters/registry-health (:channel-adapter-registry system))
        pending-approvals (count (tool-approvals/list-requests (:store system) {:status "pending" :limit 100}))
        reload-status (or (some-> system :reload-state deref)
                          {:status :idle})
        reload-label (str/join " · " (keep #(some-> % name)
                                           [(:status reload-status) (:mode reload-status)]))]
    (ui-render/render
     [:section#dashboard-summary.panel
      {"data-on-interval__duration.10s.leading" "@get('/ui/dashboard')"}
      [:div.panel-head
       [:h2 "Runtime Snapshot"]
       [:form#system-reload-form.panel-head__form
        {:method "post"
         "data-on:submit" "@post('/ui/system/reload', {contentType: 'form', selector: '#system-reload-form'})"}
        [:input {:type "hidden" :name "mode" :value "soft"}]
        [:span.reload-status
         (cond-> {:class (str "reload-status--" (name (:status reload-status)))}
           (:message reload-status) (assoc :title (str (:message reload-status))))
         reload-label]
        [:button {:type "submit"} "Reload config"]]]
      [:div.stats
       [:div.stat.stat--wide [:span.label "provider"] [:span.value.provider-value (name (config/active-provider-key llm-config))]]
       [:div.stat.stat--wide [:span.label "model"] [:span.value (or (config/active-model llm-config) "-")]]
       [:div.stat [:span.label "sessions"] [:span.value (get-in storage [:details :session-count] 0)]]
       [:div.stat [:span.label "events"] [:span.value (get-in storage [:details :event-count] 0)]]
       [:div.stat [:span.label "tools"] [:span.value (:count tools-health)]]
       [:div.stat [:span.label "adapters"] [:span.value (:count adapter-health)]]]
      [:div.fact-strip
       (for [[label value] [["vault notes" (get-in memory-health [:vault :note-count] 0)]
                            ["version" (:version build)]
                            ["commit" (:commit-short build)]
                            ["built" (or (ui-render/short-timestamp (:built-at build)) "-")]
                            ["schema" (get-in storage [:details :schema-version] "?")]
                            ["approvals" (get-in storage [:details :tool-approval-count] 0)]]]
         [:span.fact
          [:span.fact__label label]
          [:span.fact__value (str value)]])]
      [:div.run-grid
       [:div.result.result--metric
        [:strong "Pending approvals"]
        [:div.value {:class (when (pos? pending-approvals) "value--warn")}
         (str pending-approvals)]]]])))

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
  (let [approvals (tool-approvals/list-requests (:store system) {:status "pending" :limit 8})
        recent-events-pool (sqlite/list-events (:store system) {:limit 40})
        events (take 8 recent-events-pool)
        kernel-events (filter #(= "agent.kernel.step.executed" (:event-type %))
                              recent-events-pool)]
    (ui-render/render
     [:section#operator-board.panel
      {"data-on-interval__duration.10s.leading" "@get('/ui/operator-board')"}
      [:div.panel-head
       [:h2 "Operator Board"]]
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

(defn sessions-fragment
  ([system] (sessions-fragment system nil))
  ([system active-session-id]
   (let [sessions (sqlite/list-sessions (:store system))
         active-id (or (not-empty active-session-id)
                       (some-> sessions first :id))]
     (ui-render/render
      [:aside#sessions-panel.panel.sessions-sidebar
       {"data-on-interval__duration.15s.leading"
        (str "@get('/ui/sessions"
             (when active-id
               (str "?session_id=" (ui-render/url-encode active-id)))
             "')")}
       ;; datastar-fetch events are dispatched on document for EVERY fetch on
       ;; the page; evt.detail.el identifies the initiator. Guarding on it is
       ;; the only way a reset doesn't fire for unrelated polls and patches.
       [:form#create-session-form.create-session-form
        {"data-on:submit" "@post('/ui/sessions', {contentType: 'form', selector: '#create-session-form'})"
         "data-on:datastar-fetch" "evt.detail.el === el && evt.detail.type === 'finished' && el.reset()"
         "data-indicator" "createSessionLoading"}
        [:div.compact-form-row
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
    (ui-render/render
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
           ;; Generous retry budget: the default 10 retries can be exhausted
           ;; by server restarts, after which the transcript silently freezes.
           "data-init" (str "@get('/ui/session/live?session_id=" (:id session)
                            "', {openWhenHidden: true, retryMaxCount: 1000, retryMaxWaitMs: 10000})")}
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
          (ui-render/trusted-fragment (session-messages-fragment system (:id session)))
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
          ;; @post serializes the FormData synchronously, so el.reset() right
          ;; after it deterministically clears the prompt (and any attached
          ;; image) without depending on submit-listener ordering.
          [:form#chat-form
           {"data-on:submit" "@post('/ui/chat', {contentType: 'form'}); el.reset()"
            "data-indicator" "chatLoading"
            "data-class:is-loading" "$chatLoading"
            "data-skill-autocomplete" "true"
            :enctype "multipart/form-data"}
           [:input {:id (str "chat-session-id-" (:id session))
                    :type "hidden"
                    :name "session_id"
                    :value (:id session)}]
           [:textarea.chat-input {:name "prompt"
                                  :data-skill-input "true"
                                  :data-submit-on-enter "true"
                                  :rows 1
                                  :placeholder "Ask model something concrete"}]
          [:label.chat-file-input
           [:span "Image"]
           [:input {:type "file"
                    :name "image"
                    :accept "image/*"
                    :multiple true}]]
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

(defn- streaming-message [{:keys [content thinking]}]
  [:article.message.message--streaming
   [:div.message-role {:class "assistant"} "assistant"]
   (ui-render/thinking-content thinking "streaming")
   (when-not (str/blank? (str content))
     (ui-render/message-content content))
   [:div.meta "streaming…"]])

(defn- streaming-state [system session-id opts]
  (let [provided? (contains? opts :streaming)
        value (if provided?
                (:streaming opts)
                (chat/streaming-state system session-id))]
    (if (map? value)
      value
      {:content value})))

(defn session-messages-fragment
  ([system session-id]
   (session-messages-fragment system session-id {}))
  ([system session-id opts]
   (let [messages (sqlite/list-messages (:store system) session-id)
         state (chat/session-state system session-id)
         streaming (streaming-state system session-id opts)
         streaming* (cond-> {}
                      (not (str/blank? (str (:content streaming))))
                      (assoc :content (str (:content streaming)))
                      (not (str/blank? (str (:thinking streaming))))
                      (assoc :thinking (str (:thinking streaming))))
         streaming? (seq streaming*)]
     (ui-render/render
      [:chat-stream#session-messages-panel
       (if (or (seq messages) streaming? (:working? state))
         (list*
          (ui-render/thread-stats-bar messages)
          [:div.chat-stream__filler]
          (concat
           (ui-render/message-list system messages)
           (cond
             streaming? [(streaming-message streaming*)]
             :else nil)))
         [:div.empty "No messages yet."])
       [:div#chat-bottom-anchor.chat-stream__bottom-anchor {:aria-hidden true}]]))))

(defn events-fragment [system]
  (ui-render/render
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
    (ui-render/render
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

(defn- magi-decision-events [system]
  (sqlite/list-events (:store system)
                      {:event-type :tool.approval.magi_evaluated
                       :limit 1000}))

(defn- magi-tool-event? [{:keys [event-type payload]}]
  (and (= "tool-execution-end" event-type)
       (= "magi" (:tool-name payload))))

(defn- magi-invocation-events [system]
  (let [approval-events (magi-decision-events system)
        tool-events (->> (sqlite/list-events (:store system)
                                             {:event-type :tool-execution-end
                                              :limit 1000})
                         (filter magi-tool-event?))]
    (->> (concat approval-events tool-events)
         (sort-by #(long (or (:id %) 0)) >)
         (take 1000)
         vec)))

(defn- decision-class [decision]
  (str "magi-decision magi-decision--" (or (some-> decision name) "unknown")))

(defn- magi-agent-panel [label agent]
  (let [response (:response agent)]
    [:div.magi-node {:class (str "magi-node--" (or (some-> response name) "unknown"))}
     [:span.magi-node__label label]
     [:strong.magi-node__response (or (some-> response name str/upper-case) "-")]
     (when-let [comment (some-> (:comment agent) str str/trim not-empty)]
       [:span.magi-node__comment comment])]))

(defn- compact-json [value]
  (json/generate-string value {:pretty true}))

(defn- decision-label [decision]
  (or (some-> decision name str/upper-case) "-"))

(defn- magi-event-result [{:keys [payload] :as event}]
  (if (= "tool.approval.magi_evaluated" (:event-type event))
    payload
    (or (:result payload)
        (get-in payload [:receipt :result]))))

(defn- magi-invocation-row [{:keys [id event-type created-at payload] :as event}]
  (let [approval? (= "tool.approval.magi_evaluated" event-type)
        result (magi-event-result event)
        judge* (or (:judge result)
                   (select-keys result [:decision :reason]))
        filter* (:filter result)
        agents (:agents result)
        input* (or (:input payload)
                   (get-in payload [:receipt :input])
                   (get-in payload [:receipt :call :input])
                   (:input result)
                   (get-in filter* [:context :input]))
        source (if approval? "approval" "tool")
        tool-name (or (:tool-name payload)
                      (:tool-name result)
                      (get-in filter* [:context :tool-name])
                      "magi")
        duration-ms (or (:duration-ms payload) (:duration-ms result))
        reason (or (:reason judge*) (:reason result) (:reason payload))]
    [:details.magi-invocation
     [:summary.magi-invocation__summary
      [:span.magi-log-id (str "#" (or id "-"))]
      [:span source]
      [:span (or tool-name "-")]
      [:strong {:class (decision-class (:decision judge*))}
       (decision-label (:decision judge*))]
      [:span.magi-log-reason (or reason "-")]
      [:span (str (or duration-ms 0) "ms")]
      [:span (or created-at "-")]]
     [:div.magi-invocation__detail
      [:div.magi-readout
       [:span "input"]
       [:pre (compact-json input*)]]
      [:div.magi-readout
       [:span "filter"]
       [:pre (compact-json filter*)]]
      [:div.magi-readout
       [:span "agents"]
       [:pre (compact-json agents)]]
      [:div.magi-readout
       [:span "judge"]
       [:pre (compact-json judge*)]]
      [:div.magi-readout.magi-readout--wide
       [:span "event"]
       [:pre (compact-json event)]]]]))

(defn- magi-decision-card [{:keys [id entity-id created-at payload]}]
  (let [{:keys [tool-name input filter agents judge decision reason providers duration-ms]} payload
        judge* (or judge {:decision decision :reason reason})
        filter-context (:context filter)
        input* (or input (:input filter-context))]
    [:article.magi-decision-card
     [:div.magi-frame]
     [:div.magi-head
      [:div.magi-titleblock
       [:div.magi-kicker "QUESTION"]
       [:h2 (str "CODE: " (or id "-"))]
       [:div.magi-file (str "FILE: " (or tool-name "MAGI") "_CHK")]]
      [:div.magi-status {:class (decision-class (:decision judge*))}
       [:span "DECISION"]
       [:strong (or (some-> (:decision judge*) name str/upper-case) "-")]]]
     [:div.magi-diagram
      [:div.magi-center
       [:span "MAGI"]]
      (magi-agent-panel "BALTHASAR • 2" (:balthasar agents))
      (magi-agent-panel "CASPER • 3" (:casper agents))
      (magi-agent-panel "MELCHIOR • 1" (:melchior agents))]
     [:div.magi-details
      [:div.magi-readout
       [:span "input"]
       [:pre (compact-json input*)]]
      [:div.magi-readout
       [:span "filter"]
       [:pre (compact-json filter)]]
      [:div.magi-readout
       [:span "judge"]
       [:pre (compact-json judge*)]]
      [:div.magi-readout
       [:span "providers"]
       [:pre (compact-json providers)]]]
     [:div.magi-footer
      [:span (str "access code: " (or entity-id "-"))]
      [:span (str "duration: " (or duration-ms 0) "ms")]
      [:span (or created-at "-")]]]))

(defn magi-fragment [system]
  (let [invocations (magi-invocation-events system)
        decision-events (filter #(= "tool.approval.magi_evaluated" (:event-type %)) invocations)
        latest (first invocations)]
    (ui-render/render
     [:section#magi-panel.magi-panel
      [:div.magi-console
       [:div.magi-console__header
        [:div
         [:div.magi-kicker "MAGI OVERSIGHT"]
         [:h1 "Invocation Log"]]
        [:div.magi-console__count
         [:span "records"]
         [:strong (str (count invocations))]
         [:button.magi-refresh
          {:type "button"
           "data-on:click" "@get('/ui/magi')"}
          "Refresh"]]]
       (if latest
         [:div.magi-log
          [:section.magi-invocation-log
           [:div.magi-section-head
            [:h2 "Invocation Log"]
            [:span "latest 1000 records"]]
           [:div.magi-invocation__head
            [:span "id"]
            [:span "source"]
            [:span "tool"]
            [:span "decision"]
            [:span "reason"]
            [:span "time"]
            [:span "created"]]
           (for [event invocations]
             (magi-invocation-row event))]
          (when (seq decision-events)
            [:section.magi-decision-preview
             [:div.magi-section-head
              [:h2 "Decision Console"]
              [:span "approval decisions"]]
             (for [event (take 20 decision-events)]
               (magi-decision-card event))])]
         [:div.magi-empty
          [:div.magi-diagram.magi-diagram--empty
           [:div.magi-center [:span "MAGI"]]
           (magi-agent-panel "BALTHASAR • 2" nil)
           (magi-agent-panel "CASPER • 3" nil)
           (magi-agent-panel "MELCHIOR • 1" nil)]
          [:div.empty "No MAGI decisions yet."]])]])))

(defn tools-fragment [system]
  (let [tool-list (tools/list-tools (:tool-registry system))]
    (ui-render/render
     [:div#tools-panel
      [:h3 "Local Tools"]
      [:p.meta "Sensitive actions create approval requests first. Approval list lives below."]
      [:div.meta (str "available: " (str/join ", " (map (comp name :name) tool-list)))]
      [:form#fs-tool-form
       [:h3 "Filesystem"]
       [:div.dual
        [:select {:name "tool"}
         [:option {:value "fs_list"} "list"]
         [:option {:value "fs_read"} "read"]
         [:option {:value "fs_write"} "write"]
         [:option {:value "fs_mkdir"} "mkdir"]
         [:option {:value "fs_delete"} "delete"]]
        [:input {:type "text" :name "path" :value "." :placeholder "path"}]]
       [:textarea {:name "content" :placeholder "content for write"}]
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
  (ui-render/render
   [:div#tool-results-panel
    [:h3 "Tool Result"]
    [:p.meta (str (name tool-name) " / status " status)]
    [:div.result.code (json/generate-string payload {:pretty true})]]))

(defn- approval-actions [approval]
  (concat
   (when (= "pending" (:status approval))
     [[:form {:id (str "approve-" (:id approval))}
       [:input {:type "hidden" :name "actor" :value "operator"}]
       [:input {:type "text" :name "reason" :placeholder "approval reason"}]
       [:button {:type "button"
                 "data-on:click" (str "@post('/ui/tool-approvals/" (:id approval) "/approve', {contentType: 'form', selector: '#approve-" (:id approval) "'})")}
        "Approve"]]
      [:form {:id (str "deny-" (:id approval))}
       [:input {:type "hidden" :name "actor" :value "operator"}]
       [:input {:type "text" :name "reason" :placeholder "denial reason"}]
       [:button {:type "button"
                 "data-on:click" (str "@post('/ui/tool-approvals/" (:id approval) "/deny', {contentType: 'form', selector: '#deny-" (:id approval) "'})")}
        "Deny"]]])
   (when (= "approved" (:status approval))
     [[:form {:id (str "run-" (:id approval))}
       [:button {:type "button"
                 "data-on:click" (str "@post('/ui/tool-approvals/" (:id approval) "/run', {contentType: 'form', selector: '#run-" (:id approval) "'})")}
        "Run"]]])))

(defn tool-approvals-fragment [approvals]
  (ui-render/render
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
          (when-let [decision-reason (:decision-reason approval)]
            [:div.meta (str "decision: " decision-reason)])
          [:div.code (json/generate-string (:input approval) {:pretty true})]
         [:div.actions (approval-actions approval)]])]
      [:div.empty "No tool approvals yet."])]))
