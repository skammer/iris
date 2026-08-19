(ns agent.ui
  "Server-rendered Datastar UI. Builds dashboard, chat, tools, memory, events,
   logs, and approval fragments from current system state for live SSE patches."
  (:require
   [agent.build-info :as build-info]
   [agent.channels.core :as channel-adapters]
   [agent.chat :as chat]
   [agent.config :as config]
   [agent.cron.service :as cron]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as runtime-trace]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.ui.memory :as ui-memory]
   [agent.ui.cron :as ui-cron]
   [agent.ui.render :as ui-render]
   [cheshire.core :as json]
   [clojure.string :as str]))

(declare dashboard-fragment
         operator-board-fragment
         sessions-fragment
         session-route-path
         session-detail-fragment
         session-messages-fragment
         route-fragment
         events-fragment
         magi-fragment
         logs-fragment
	         memory-workspace-fragment
	         memory-search-results-fragment
	         memory-tool-result-fragment
	         tool-approval-detail-fragment
	         tool-approvals-fragment)

(def ^:private tabs
  [{:key :overview :label "Overview"}
   {:key :chat :label "Chat"}
   {:key :cron :label "Cron"}
   {:key :tools :label "Tools"}
   {:key :memory :label "Memory"}
   {:key :magi :label "MAGI"}
   {:key :logs :label "Logs"}])

(def memory-search-results-fragment ui-memory/memory-search-results-fragment)
(def memory-tool-result-fragment ui-memory/memory-tool-result-fragment)
(def memory-workspace-fragment ui-memory/memory-workspace-fragment)
(def cron-fragment ui-cron/fragment)

(defn- normalize-tab [value]
  (let [tab (some-> value name str/lower-case keyword)]
    (if (some #(= tab (:key %)) tabs) tab :chat)))

(defn- route-path [{:keys [tab session-id]}]
  (case (normalize-tab tab)
    :overview "/overview"
    :chat (if session-id (str "/chat/" session-id) "/chat")
    :cron "/cron"
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
      "cron" {:tab :cron}
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
    "data-on:click" (str "@get('/ui/route?tab=" (name (:key tab))
                         "&client_id=' + window.irisUiClientId)")}
   (:label tab)])

(defn- shell-nav-node [active-tab]
  [:nav#shell-nav.shell-nav
   [:span.shell-nav__pill {:aria-hidden "true"}]
   (for [tab tabs]
     (tab-link tab active-tab))])

(defn- workspace-node [system active-tab session-id]
  [:div#workspace-content.workspace-content
   (ui-render/trusted-fragment
    (case active-tab
      :chat (ui-render/render-many
             [:section.workspace-grid.chat-workspace
              (ui-render/trusted-fragment (sessions-fragment system session-id))
              (ui-render/trusted-fragment (session-detail-fragment system session-id))])
      :cron (ui-render/render-many
             [:section.workspace-grid.single
              (ui-render/trusted-fragment (ui-cron/fragment system))])
      :tools (ui-render/render-many
              [:section.workspace-grid.single
               [:div.tools-workspace
                [:div#tool-results-panel.tool-operation-result {:hidden true}]
                (ui-render/trusted-fragment
                 (tool-approvals-fragment
                  (tool-approvals/list-review-records (:store system) {:limit 20})))]] )
      :memory (memory-workspace-fragment system)
      :magi (ui-render/render-many
             [:section.workspace-grid.single
              (ui-render/trusted-fragment (magi-fragment system))])
      :logs (ui-render/render-many
             [:section.workspace-grid.single
              (ui-render/trusted-fragment (logs-fragment system))])
      (ui-render/render-many
       [:section.workspace-grid.overview-workspace
        (ui-render/trusted-fragment (dashboard-fragment system))
        (ui-render/trusted-fragment (operator-board-fragment system))])))])

(defn route-fragment [system active-route]
  (let [route (if (map? active-route) active-route {:tab active-route})
        active-tab (normalize-tab (:tab route))
        session-id (:session-id route)
        path (if (= :chat active-tab)
               (session-route-path system session-id)
               (route-path {:tab active-tab}))]
    (str (router-state-fragment path)
         (ui-render/render (shell-nav-node active-tab))
         (ui-render/render (workspace-node system active-tab session-id)))))

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
       [:a.shell-brand {:href "/" :aria-label "Iris control plane"}
        [:span.shell-brand__copy
         [:strong "IRIS"]
         [:small "CONTROL PLANE"]]]
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
       [:div.shell-header__actions
        [:a.shell-system-link {:href "/ui"} "UI SYSTEM"]
        [:theme-toggle
         [:button#theme-toggle.theme-toggle
          {:type "button"
           :aria-label "Toggle light or dark mode"
           :title "Toggle light/dark mode"}
          "Dark"]]]]
      (shell-nav-node active-tab)
      (workspace-node system active-tab session-id)])))

(defn dashboard-fragment [system]
  (let [storage (sqlite/health-check (:store system))
        build (build-info/read-build-info)
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
        [:span.overview-kicker "Iris / Runtime"]
        [:h1 "Agent Control Plane"]
        [:p "Local-first agent runtime, memory, tools, and operator review."]
        [:dl.overview-runtime-identity
         [:div [:dt "Provider"] [:dd (name (config/active-provider-key llm-config))]]
         [:div [:dt "Model"] [:dd (or (config/active-model llm-config) "-")]]]]
       [:nav.overview-action-grid {:aria-label "Primary workspaces"}
        (for [[mark label detail href] [["01" "Open chat" "Sessions and messages" "/chat"]
                                        ["02" "Cron jobs" (str (:active-jobs cron-health) " active · " (:running-runs cron-health) " running") "/cron"]
                                        ["03" "Review tools" (str pending-approvals " pending approvals") "/tools"]
                                        ["04" "Browse memory" (str (get-in memory-health [:vault :note-count] 0) " vault notes") "/memory"]
                                        ["05" "Inspect logs" (str (get-in storage [:details :event-count] 0) " events") "/logs"]]]
          [:a.overview-action-tile {:href href}
           [:span.overview-action-tile__mark {:aria-hidden "true"} mark]
           [:span.overview-action-tile__copy
            [:strong label]
            [:small detail]]
           [:span.overview-action-tile__arrow {:aria-hidden "true"} "↗"]])]]
      [:section.runtime-card
       [:header.runtime-card__header
        [:div
         [:span.overview-kicker "Live runtime"]
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
        [:span.overview-kicker "Operations"]
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

(defn sessions-fragment
  ([system] (sessions-fragment system nil))
  ([system active-session-id]
   (let [store (:store system)
         selected-session (some->> active-session-id not-empty
                                   (sqlite/get-session store))
         active-kind (if (= :cron (:kind selected-session)) :cron :chat)
         sessions-by-kind {:chat (sqlite/list-sessions store {:kind :chat})
                           :cron (sqlite/list-sessions store {:kind :cron})}
         sessions (get sessions-by-kind active-kind)
         active-id (or (:id selected-session)
                       (some-> sessions first :id))]
     (ui-render/render
      [:aside#sessions-panel.panel.sessions-sidebar
       {"data-on-interval__duration.15s"
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
       [:div.session-sidebar-heading
        [:h2 "Sessions"]
        [:div.session-kind-tabs {:role "tablist" :aria-label "Session type"}
         (for [[kind label] [[:chat "Chats"] [:cron "Ephemeral"]]
               :let [target-id (some-> (get sessions-by-kind kind) first :id)
                     active? (= kind active-kind)]]
           [:button.session-kind-tab
            {:type "button"
             :role "tab"
             :class (when active? "session-kind-tab--active")
             :aria-selected active?
             :disabled (nil? target-id)
             "data-route" (when target-id
                            (route-path {:tab :chat :session-id target-id}))
             "data-on:click" (when target-id
                               (str "@get('/ui/session-detail?session_id=" target-id "')"))}
            [:span label]
            [:span.session-kind-tab__count (count (get sessions-by-kind kind))]])]]
       (if (seq sessions)
         [:div.session-list
          (for [{:keys [id title created-at]} sessions
                :let [state (chat/session-state system id)]]
            [:button.session-link
             {:type "button"
              :class (when (= id active-id) "session-link--active")
              :aria-current (when (= id active-id) "page")
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
  (let [store (:store system)]
    (or (some->> session-id not-empty (sqlite/get-session store))
        (first (sqlite/list-sessions store {:kind :chat})))))

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
                            "&client_id=' + window.irisUiClientId, {requestCancellation: window.irisChatStreamController, openWhenHidden: true, retryMaxCount: 1000, retryMaxWaitMs: 10000})")}
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
          [:div.chat-transcript
           (ui-render/trusted-fragment (session-messages-fragment system (:id session)))
           [:button.chat-scroll-bottom
            {:type "button"
             :hidden true
             "data-chat-scroll-bottom" true
             :aria-label "Scroll to latest message"
             :title "Scroll to latest message"}
            [:span {:aria-hidden true} "↓"]
            "Latest"]]
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
          [:button.chat-stop {:type "button"
                     :style (when-not status-visible? "display:none")
                     "data-show" (if status-visible? "true" "$chatLoading")
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
  [:article.message.message--assistant.message--streaming
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

(def ^:private default-visible-messages 60)

(defn- visible-message-limit [value]
  (try
    (-> (or value default-visible-messages) long (max default-visible-messages) (min 400))
    (catch Exception _
      default-visible-messages)))

(defn session-messages-fragment
  ([system session-id]
   (session-messages-fragment system session-id {}))
  ([system session-id opts]
   (let [limit (visible-message-limit (:limit opts))
         message-count (sqlite/count-messages (:store system) session-id)
         visible-messages (sqlite/list-recent-messages (:store system) session-id limit)
         hidden-count (- message-count (count visible-messages))
         thread-stats (sqlite/session-thread-stats (:store system) session-id)
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
       (if (or (seq visible-messages) streaming? (:working? state))
         (list*
          (ui-render/thread-stats-bar thread-stats)
          (when (pos? hidden-count)
            [:button.chat-history-more
             {:type "button"
              "data-on:click" (str "@get('/ui/session-messages?session_id="
                                   (ui-render/url-encode session-id)
                                   "&limit=" (min 400 (+ limit default-visible-messages)) "')")}
             (str "Load " (min hidden-count default-visible-messages) " older")])
          [:div.chat-stream__filler]
          (concat
           (ui-render/message-list system visible-messages)
           (cond
             streaming? [(streaming-message streaming*)]
             :else nil)))
         [:div.empty "No messages yet."])
       [:div#chat-bottom-anchor.chat-stream__bottom-anchor {:aria-hidden true}]]))))

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
        [:span.overview-kicker "Runtime observability"]
        [:h1 "Logs"]
        [:p "SQLite events are durable application history. Runtime Trace is a separate diagnostic stream."]]
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

(defn- magi-decision-events [system limit]
  (concat
   (sqlite/list-events (:store system)
                       {:event-type :tool.approval.magi_evaluated
                        :limit limit})
   (sqlite/list-events (:store system)
                       {:event-type :memory.vault.magi_evaluated
                        :limit limit})))

(defn- magi-tool-event? [{:keys [event-type payload]}]
  (and (= "tool-execution-end" event-type)
       (= "magi" (:tool-name payload))))

(defn- magi-invocation-events [system limit]
  (let [approval-events (magi-decision-events system limit)
        tool-events (->> (sqlite/list-events (:store system)
                                             {:event-type :tool-execution-end
                                              :limit (min 1000 (* 5 limit))})
                         (filter magi-tool-event?))]
    (->> (concat approval-events tool-events)
         (sort-by #(long (or (:id %) 0)) >)
         (take limit)
         vec)))

(defn- decision-class [decision]
  (str "magi-decision magi-decision--" (or (some-> decision name) "unknown")))

(defn- magi-agent-panel [label agent]
  (let [response (:response agent)]
    [:div.magi-node {:class (str "magi-node--" (or (some-> response name) "unknown"))}
     [:div.magi-node__head
      [:span.magi-node__label label]
      [:strong.magi-node__response (or (some-> response name str/upper-case) "-")]]
     (when-let [comment (some-> (:comment agent) str str/trim not-empty)]
       [:span.magi-node__comment comment])]))

(defn- compact-json [value]
  (json/generate-string value {:pretty true}))

(defn- decision-label [decision]
  (or (some-> decision name str/upper-case) "-"))

(defn- magi-event-result [{:keys [payload] :as event}]
  (if (contains? #{"tool.approval.magi_evaluated" "memory.vault.magi_evaluated"}
                 (:event-type event))
    payload
    (or (:result payload)
        (get-in payload [:receipt :result]))))

(defn- magi-event-view [{:keys [id event-type entity-id created-at payload] :as event}]
  (let [approval? (= "tool.approval.magi_evaluated" event-type)
        memory? (= "memory.vault.magi_evaluated" event-type)
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
        source (cond approval? "approval"
                     memory? "vault-note"
                     :else "tool")
        tool-name (or (when memory? "vault-note")
                      (:tool-name payload)
                      (:tool-name result)
                      (get-in filter* [:context :tool-name])
                      "magi")
        duration-ms (or (:duration-ms payload) (:duration-ms result))
        reason (or (:reason judge*) (:reason result) (:reason payload))]
    {:id id
     :entity-id entity-id
     :created-at created-at
     :source source
     :tool-name tool-name
     :duration-ms duration-ms
     :reason reason
     :decision (:decision judge*)
     :input input*
     :filter filter*
     :agents agents
     :judge judge*
     :providers (or (:providers result) (:providers payload))}))

(defn- magi-decision-card [event]
  (let [{:keys [id entity-id created-at tool-name input filter agents judge
                providers duration-ms]} (magi-event-view event)]
    [:article.magi-decision-card
     [:div.magi-head
      [:div.magi-titleblock
       [:div.magi-kicker (str "#" (or id "-"))]
       [:h2 (or tool-name "MAGI")]
       [:div.magi-file (or entity-id "-")]]
      [:div.magi-status {:class (decision-class (:decision judge))}
       [:span "DECISION"]
       [:strong (decision-label (:decision judge))]]]
     [:div.magi-agent-grid
      (magi-agent-panel "MELCHIOR" (:melchior agents))
      (magi-agent-panel "BALTHASAR" (:balthasar agents))
      (magi-agent-panel "CASPER" (:casper agents))]
     [:div.magi-details
      [:div.magi-readout
       [:span "input"]
       [:pre (compact-json input)]]
      [:div.magi-readout
       [:span "filter"]
       [:pre (compact-json filter)]]
      [:div.magi-readout
       [:span "judge"]
       [:pre (compact-json judge)]]
      [:div.magi-readout
       [:span "providers"]
       [:pre (compact-json providers)]]]
     [:div.magi-footer
      [:span (str "approval: " (or entity-id "-"))]
      [:span (str "duration: " (or duration-ms 0) "ms")]
      [:span (or created-at "-")]]]))

(defn- magi-invocation-row [{:keys [id created-at] :as event}]
  (let [{:keys [source tool-name decision duration-ms reason]} (magi-event-view event)]
    [:details.magi-invocation
     {:id (str "magi-invocation-" id)
      "data-preserve-attr" "open"}
     [:summary.magi-invocation__summary
      {"data-on:click" (str "@get('/ui/magi/" id "/detail')")}
      [:span.magi-log-id (str "#" (or id "-"))]
      [:span source]
      [:span (or tool-name "-")]
      [:strong {:class (decision-class decision)}
       (decision-label decision)]
      [:span.magi-log-reason (or reason "-")]
      [:span (str (or duration-ms 0) "ms")]
      [:span (or created-at "-")]]
     [:div.magi-invocation__detail
      {:id (str "magi-detail-" id)}
      [:div.empty-line "Loading details…"]]]))

(defn magi-detail-fragment [event]
  (ui-render/render
   (if event
     [:div.magi-invocation__detail {:id (str "magi-detail-" (:id event))}
      (magi-decision-card event)
      [:div.magi-readout.magi-readout--wide
       [:span "event"]
       [:pre (compact-json event)]]]
     [:div#magi-detail-missing.magi-invocation__detail
      [:div.empty-line "MAGI event not found."]])))

(defn magi-fragment
  ([system] (magi-fragment system {:limit 25}))
  ([system {:keys [limit] :or {limit 25}}]
   (let [invocations (magi-invocation-events system limit)
        decision-events (filter #(str/ends-with? (:event-type %) ".magi_evaluated") invocations)
        latest (first invocations)]
     (ui-render/render
      [:section#magi-panel.panel.magi-panel
      [:div.magi-console
       [:div.panel-head.magi-console__header
        [:h2 "MAGI"]
        [:div.panel-head__form
         [:span.reload-status "decision log"]
         [:button.magi-refresh
          {:type "button"
           "data-on:click" (str "@get('/ui/magi?limit=" limit "')")}
          "Refresh"]]]
       [:div.fact-strip.magi-facts
        [:span.fact
         [:span.fact__label "records"]
         [:span.fact__value (str (count invocations))]]
        [:span.fact
         [:span.fact__label "reviews"]
         [:span.fact__value (str (count decision-events))]]
        [:span.fact
         [:span.fact__label "latest"]
         [:span.fact__value (or (:created-at latest) "-")]]]
       (if latest
         [:div.magi-log
          [:section.magi-invocation-log
           [:div.magi-section-head
            [:h2 "Invocation Log"]
            [:span (str "latest " (count invocations) " records")]]
           [:div.magi-invocation__head
            [:span "id"]
            [:span "source"]
            [:span "tool"]
            [:span "decision"]
            [:span "reason"]
            [:span "time"]
            [:span "created"]]
           (for [event invocations]
             (magi-invocation-row event))
           (when (= (count invocations) limit)
             [:button.chat-history-more
              {:type "button"
               "data-on:click" (str "@get('/ui/magi?limit=" (min 200 (+ limit 25)) "')")}
              "Load 25 older"])] ]
         [:div.magi-empty
          [:div.empty "No MAGI decisions yet."]])]]))))

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
        [:span.overview-kicker "Operator review"]
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
