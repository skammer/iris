(ns agent.ui
  "Server-rendered Datastar UI. Builds dashboard, chat, tools, memory, events,
   logs, and approval fragments from current system state for live SSE patches."
  (:require
   [agent.config :as config]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.approvals :as tool-approvals]
   [agent.ui.memory :as ui-memory]
   [agent.ui.cron :as ui-cron]
   [agent.ui.render :as ui-render]
   [agent.ui.approvals :as ui-approvals]
   [agent.ui.logs :as ui-logs]
   [agent.ui.magi :as ui-magi]
   [agent.ui.overview :as ui-overview]
   [agent.ui.routes :as ui-routes]
   [agent.ui.sessions :as ui-sessions]))

(def dashboard-fragment ui-overview/dashboard-fragment)
(def operator-board-fragment ui-overview/operator-board-fragment)
(def session-title-fragments ui-sessions/session-title-fragments)
(def session-projects-fragment ui-sessions/session-projects-fragment)
(def sessions-fragment ui-sessions/sessions-fragment)
(def session-route-path ui-sessions/session-route-path)
(def session-status-fragment ui-sessions/session-status-fragment)
(def session-detail-fragment ui-sessions/session-detail-fragment)
(def session-streaming-fragment ui-sessions/session-streaming-fragment)
(def session-messages-fragment ui-sessions/session-messages-fragment)
(def events-fragment ui-logs/events-fragment)
(def log-detail-fragment ui-logs/log-detail-fragment)
(def logs-fragment ui-logs/logs-fragment)
(def magi-detail-fragment ui-magi/magi-detail-fragment)
(def magi-fragment ui-magi/magi-fragment)
(def tool-results-fragment ui-approvals/tool-results-fragment)
(def tool-approval-detail-fragment ui-approvals/tool-approval-detail-fragment)
(def tool-approvals-status-fragment ui-approvals/tool-approvals-status-fragment)
(def tool-approvals-fragment ui-approvals/tool-approvals-fragment)

(def memory-search-results-fragment ui-memory/memory-search-results-fragment)

(def memory-tool-result-fragment ui-memory/memory-tool-result-fragment)

(def memory-workspace-fragment ui-memory/memory-workspace-fragment)

(def cron-fragment ui-cron/fragment)

(defn- tab-tone [tab-key]
  (or (some #(when (= tab-key (:key %)) (:tone %)) ui-routes/tabs)
      "black"))

(defn- shell-url [{:keys [tab session-id]}]
  (str "/ui/shell?tab=" (name (ui-routes/normalize-tab tab))
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
  (let [selected? (= (:key tab) active-tab)]
    [:button.ui-card-tab
     {:id (str "shell-tab-" (name (:key tab)))
      :type "button"
      :role "tab"
      :class (str "ui-card-tab--" (:tone tab))
      :aria-controls "workspace-content"
      :aria-selected (str selected?)
      :tabindex (if selected? 0 -1)
      "data-route" (ui-routes/route-path {:tab (:key tab)})
      "data-on:click" (str "@get('/ui/route?tab=" (name (:key tab))
                           "&client_id=' + window.irisUiClientId)")}
     [:span.ui-card-tab__label (:label tab)]]))

(defn- shell-card-tab-clip-defs []
  [:svg.ui-card-tab-clip-defs {:aria-hidden "true" :width "0" :height "0"}
   [:defs
    [:clipPath {:id "ui-card-tab-horizontal" :clipPathUnits "objectBoundingBox"}
     [:path {:d "M 0 1 L 0.11 0.18 L 0.115 0.145 Q 0.13 0 0.25 0 H 0.75 Q 0.87 0 0.885 0.145 L 0.89 0.18 L 1 1 Z"}]]]])

(defn- shell-nav-node [active-tab]
  [:nav#shell-nav.shell-nav.ui-card-tabs.ui-card-tabs--horizontal
   {:aria-label "Primary navigation"}
   (shell-card-tab-clip-defs)
   (into [:div.ui-card-tab-list {:role "tablist"}]
         (map #(tab-link % active-tab) ui-routes/tabs))])

(defn- workspace-node [system active-tab session-id]
  [:div#workspace-content.workspace-content
   {:class (str "workspace-content--" (tab-tone active-tab))
    :role "tabpanel"
    :aria-labelledby (str "shell-tab-" (name active-tab))}
   (ui-render/trusted-fragment
    (case active-tab
      :chat (ui-render/render-many
             [:section.workspace-grid.chat-workspace
              [:button.chat-sessions-toggle
               {:type "button" :aria-expanded "false" :aria-controls "sessions-panel"
                "data-on:click" "el.setAttribute('aria-expanded', String(el.parentElement.classList.toggle('chat-workspace--sessions-open')))"}
               "Sessions"]
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
        active-tab (ui-routes/normalize-tab (:tab route))
        session-id (:session-id route)
        path (if (= :chat active-tab)
               (session-route-path system session-id)
               (ui-routes/route-path {:tab active-tab}))]
    (str (router-state-fragment path)
         (ui-render/render (shell-nav-node active-tab))
         (ui-render/render (workspace-node system active-tab session-id)))))

(defn index-page
  ([] (index-page "/"))
  ([path]
   (let [route-state (ui-routes/route-state-from-path path)]
  (ui-render/render
   [:html {:lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "iris control plane"]
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
        active-tab (ui-routes/normalize-tab (:tab route))
        session-id (:session-id route)
        storage (sqlite/health-check (:store system))
        provider (name (config/active-provider-key (get-in system [:config :llm])))
        session-count (get-in storage [:details :session-count] 0)
        event-count (get-in storage [:details :event-count] 0)
        port (get-in system [:config :api :port])]
    (ui-render/render
     [:div#shell-fragment.workspace-stack
      (ui-render/trusted-fragment (router-state-fragment (ui-routes/route-path {:tab active-tab
                                                            :session-id session-id})))
      [:header.shell-header
       [:a.shell-brand {:href "/" :aria-label "Iris control plane"}
        [:span.shell-brand__copy
         [:strong "IRIS"]]]
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
        [:a.shell-system-link {:href "/ui"} "UI SYSTEM"]]]
      (shell-nav-node active-tab)
      (workspace-node system active-tab session-id)])))
