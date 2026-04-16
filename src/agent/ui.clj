(ns agent.ui
  "Server-rendered Datastar UI."
  (:require
   [agent.channels.core :as channel-adapters]
   [agent.memory.core :as memory]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]
   [hiccup2.core :as h]))

(def ^:private page-style
  ":root{color-scheme:light;background:#f6f1e8;color:#181512;--paper:#fffdf8;--ink:#181512;--muted:#6f6558;--line:#d9cbb8;--accent:#1b7f6a;--accent-2:#b85c38;--shadow:0 16px 40px rgba(37,27,18,.08);font-family:'IBM Plex Sans','Avenir Next','Segoe UI',sans-serif;}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at top left,#fffaf1,transparent 30%),linear-gradient(180deg,#f6f1e8,#efe3d0);color:var(--ink)}main{max-width:1440px;margin:0 auto;padding:24px;display:grid;gap:18px}.hero,.panel{background:rgba(255,253,248,.94);border:1px solid var(--line);border-radius:20px;box-shadow:var(--shadow)}.hero{padding:24px 28px;display:grid;gap:8px}.hero h1{margin:0;font-size:2rem;letter-spacing:-.04em}.hero p{margin:0;color:var(--muted);max-width:70ch}.grid{display:grid;gap:18px}.grid.top{grid-template-columns:1.2fr .8fr}.grid.bottom{grid-template-columns:1fr 1fr 1fr}.panel{padding:18px}.panel h2,.panel h3{margin:0 0 12px 0;letter-spacing:-.03em}.stats{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px}.stat{padding:12px 14px;border:1px solid var(--line);border-radius:16px;background:#fff}.label{display:block;font-size:.72rem;text-transform:uppercase;letter-spacing:.12em;color:var(--muted);margin-bottom:6px}.value{font-size:1.2rem;font-weight:700}.stack{display:grid;gap:10px}.session-link,.tool-button,button{appearance:none;border:0;border-radius:14px;background:var(--ink);color:#fff;padding:10px 14px;font:inherit;cursor:pointer}.session-link{width:100%;text-align:left;background:#fff;color:var(--ink);border:1px solid var(--line)}.session-link:hover,button:hover{filter:brightness(.98)}.session-meta,.meta,.muted{color:var(--muted);font-size:.88rem}.messages,.event-list{display:grid;gap:10px;max-height:420px;overflow:auto;padding-right:4px}.message,.event-item,.result{border:1px solid var(--line);border-radius:16px;padding:12px 14px;background:#fff}.message-role{font-size:.75rem;text-transform:uppercase;letter-spacing:.12em;color:var(--accent);margin-bottom:6px}.message-role.user{color:var(--accent-2)}.message-role.system{color:#6a4fb3}.message-role.tool{color:#7a5e12}form{display:grid;gap:10px}input,textarea,select{width:100%;padding:10px 12px;border-radius:14px;border:1px solid var(--line);background:#fff;color:var(--ink);font:inherit}textarea{min-height:120px;resize:vertical}.actions{display:flex;gap:10px;flex-wrap:wrap}.dual{display:grid;gap:12px;grid-template-columns:1fr 1fr}.code{font-family:'IBM Plex Mono','SFMono-Regular','Menlo',monospace;font-size:.88rem;white-space:pre-wrap;word-break:break-word}.empty{padding:18px;border:1px dashed var(--line);border-radius:16px;color:var(--muted);background:rgba(255,255,255,.65)}@media (max-width:1100px){.grid.top,.grid.bottom,.stats,.dual{grid-template-columns:1fr}}")

(defn- render [node]
  (str (h/html node)))

(defn- render-many [& nodes]
  (apply str (map render nodes)))

(defn index-page []
  (render
   [:html {:lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "clj-agent control plane"]
     [:script {:type "module"
               :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.8/bundles/datastar.js"}]
     [:style page-style]]
    [:body
     [:main
      [:section.hero
       [:h1 "clj-agent control plane"]
       [:p "Datastar shell over rewritten runtime: sessions, live transcript, memory surfaces, persisted tool approvals."]]
      [:section#dashboard-summary.panel
       {"data-on-interval__duration.5s.leading" "@get('/ui/dashboard')"}
       "Loading dashboard..."]
      [:section.grid.top
       [:section#sessions-panel.panel
        {"data-on-interval__duration.5s.leading" "@get('/ui/sessions')"}
        "Loading sessions..."]
       [:section#session-detail-panel.panel
        {"data-on-interval__duration.3s.leading" "@get('/ui/session-detail')"}
        "Loading transcript..."]]
      [:section.grid.bottom
       [:section#events-panel.panel "Waiting for live events..."]
       [:section.panel.stack
        [:div#memory-prompt-panel
         {"data-on-interval__duration.15s.leading" "@get('/ui/memory/prompt')"}
         "Loading prompt memory..."]
        [:form#memory-search-form
         [:h3 "Memory Search"]
         [:input {:type "text" :name "query" :placeholder "search messages and events"}]
         [:div.actions
          [:button {:type "button"
                    "data-on:click" "@post('/ui/memory/search', {contentType: 'form', selector: '#memory-search-form'})"}
           "Search"]]]
        [:div#memory-search-results-panel.empty "Run memory search."]]
       [:section.panel.stack
        [:div#tools-panel
         {"data-on-interval__duration.10s.leading" "@get('/ui/tools')"}
         "Loading tools..."]
        [:div#tool-approvals-panel
         {"data-on-interval__duration.5s.leading" "@get('/ui/tool-approvals')"}
         "Loading approvals..."]
        [:div#tool-results-panel.empty "Request approval, approve/deny, then run."]]]
      [:div#events-live-bootstrap {"data-init" "@get('/ui/events/live')"}]]]]))

(defn dashboard-fragment [system]
  (let [storage (sqlite/health-check (:store system))
        tools-health (tools/registry-health (:tool-registry system))
        memory-health (memory/health-check (:memory-service system))
        adapter-health (channel-adapters/registry-health (:channel-adapter-registry system))
        agent-health (orchestrator/health-check (:orchestrator system))]
    (render
     [:section#dashboard-summary.panel
      {"data-on-interval__duration.5s.leading" "@get('/ui/dashboard')"}
      [:h2 "Runtime Snapshot"]
      [:div.stats
       [:div.stat [:span.label "provider"] [:span.value (name (get-in system [:config :llm :provider]))]]
       [:div.stat [:span.label "sessions"] [:span.value (get-in storage [:details :session-count] 0)]]
       [:div.stat [:span.label "events"] [:span.value (get-in storage [:details :event-count] 0)]]
       [:div.stat [:span.label "tools"] [:span.value (:count tools-health)]]
       [:div.stat [:span.label "agents"] [:span.value (:agent-count agent-health)]]]
      [:p.meta
       (str "memory graph enabled: "
            (if (true? (get-in memory-health [:graph :details :enabled])) "yes" "no")
            " | channel adapters: " (:count adapter-health)
            " | sqlite schema: " (get-in storage [:details :schema-version] "?")
            " | approvals: " (get-in storage [:details :tool-approval-count] 0))]])))

(defn sessions-fragment [system]
  (let [sessions (sqlite/list-sessions (:store system))]
    (render
     [:section#sessions-panel.panel
      {"data-on-interval__duration.5s.leading" "@get('/ui/sessions')"}
      [:h2 "Sessions"]
      (if (seq sessions)
        [:div.stack
         (for [{:keys [id title created-at]} sessions]
           [:button.session-link
            {:type "button"
             "data-on:click" (str "@get('/ui/session-detail?session_id=" id "')")}
            [:strong (or title "Untitled session")]
            [:div.session-meta.code id]
            [:div.session-meta created-at]])]
        [:div.empty "No sessions yet."])
      [:form#create-session-form
       [:h3 "Create Session"]
       [:input {:type "text" :name "title" :placeholder "optional title"}]
       [:div.actions
        [:button {:type "button"
                  "data-on:click" "@post('/ui/sessions', {contentType: 'form', selector: '#create-session-form'})"}
         "Create"]]]])))

(defn session-detail-fragment [system session-id]
  (let [sessions (sqlite/list-sessions (:store system))
        session (or (some #(when (= session-id (:id %)) %) sessions)
                    (first sessions))]
    (render
     (if-not session
       [:section#session-detail-panel.panel
        [:h2 "Transcript"]
        [:div.empty "Create session to start chatting."]]
       [:section#session-detail-panel.panel
        [:h2 (or (:title session) "Untitled session")]
        [:p.meta.code (:id session)]
        [:div {:id (str "session-live-bootstrap-" (:id session))
               "data-init" (str "@get('/ui/session/live?session_id=" (:id session) "')")}]
        (if-let [messages (seq (sqlite/list-messages (:store system) (:id session)))]
          [:div.messages
           (for [{:keys [role content created-at]} messages]
             [:article.message
              [:div.message-role {:class role} role]
              [:div.code content]
              [:div.meta created-at]])]
          [:div.empty "No messages yet."])
        [:form#chat-form
         [:h3 "Send Prompt"]
         [:input {:type "hidden" :name "session_id" :value (:id session)}]
         [:textarea {:name "prompt" :placeholder "Ask model something concrete"}]
         [:div.actions
          [:button {:type "button"
                    "data-on:click" "@post('/ui/chat', {contentType: 'form', selector: '#chat-form'})"}
           "Send"]]]]))))

(defn events-fragment [system]
  (render
   [:section#events-panel.panel
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

(defn memory-prompt-fragment [system]
  (let [{:keys [documents combined]} (memory/read-prompt-memory (:memory-service system))]
    (render
     [:div#memory-prompt-panel
      [:h3 "Prompt Memory"]
      [:p.meta (str "documents: " (count documents))]
      (if (seq documents)
        [:div.result.code combined]
        [:div.empty "No prompt memory files found."])])))

(defn memory-search-results-fragment [results]
  (render
   [:div#memory-search-results-panel
    [:h3 "Search Results"]
    [:p.meta (str "query: " (:query results)
                  " | messages: " (count (:messages results))
                  " | events: " (count (:events results)))]
    (if (or (seq (:messages results)) (seq (:events results)))
      [:div.stack
       (concat
        (for [{:keys [session-id role content created-at]} (:messages results)]
          [:article.result
           [:strong "message"]
           [:div.meta.code (str session-id " / " role)]
           [:div.code content]
           [:div.meta created-at]])
        (for [{:keys [event-type entity-type entity-id payload created-at]} (:events results)]
          [:article.result
           [:strong "event"]
           [:div.meta (str event-type " / " (or entity-type "system") " / " (or entity-id "-"))]
           [:div.code (json/generate-string payload)]
           [:div.meta created-at]]))]
      [:div.empty "No memory matches."])]))

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
