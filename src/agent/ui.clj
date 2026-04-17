(ns agent.ui
  "Server-rendered Datastar UI."
  (:require
   [agent.channels.core :as channel-adapters]
   [agent.memory.core :as memory]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]
   [agent.runners.core :as runners]
   [agent.runtime.core :as runtime]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]
   [hiccup2.core :as h]))

(defn- render [node]
  (str (h/html node)))

(defn- render-many [& nodes]
  (apply str (map render nodes)))

(declare dashboard-fragment
         sessions-fragment
         session-detail-fragment
         session-messages-fragment
         events-fragment
         memory-prompt-fragment
         memory-search-results-fragment
         tools-fragment
         tool-approvals-fragment
         runs-fragment
         run-detail-fragment)

(def ^:private tabs
  [{:key :overview :label "Overview"}
   {:key :chat :label "Chat"}
   {:key :runs :label "Runs"}
   {:key :tools :label "Tools"}
   {:key :memory :label "Memory"}])

(defn- normalize-tab [value]
  (let [tab (some-> value name str/lower-case keyword)]
    (if (some #(= tab (:key %)) tabs) tab :overview)))

(defn- tab-link [tab active-tab]
  [:button.tab-link
   {:type "button"
    :class (when (= (:key tab) active-tab) "active")
    "data-on:click" (str "@get('/ui/shell?tab=" (name (:key tab)) "')")}
   (:label tab)])

(defn index-page []
  (render
   [:html {:lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "clj-agent control plane"]
     [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
     [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin true}]
     [:link {:rel "stylesheet"
             :href "https://fonts.googleapis.com/css2?family=Doto:wght,ROND@400..900,0..100&family=Space+Grotesk:wght@300;400;500;700&family=Space+Mono:wght@400;700&display=swap"}]
     [:link {:rel "stylesheet" :href "/public/app.css"}]
     [:script {:type "module"
               :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.8/bundles/datastar.js"}]
     [:script {:type "module" :src "/public/app.js"}]
     ]
    [:body
     [:main
      [:div#shell-fragment
       {"data-init" "@get('/ui/shell?tab=overview')"}
       "[LOADING...]"]]]]))

(defn shell-fragment [system active-tab]
  (let [active-tab (normalize-tab active-tab)
        storage (sqlite/health-check (:store system))
        runtime-health (runtime/runtime-health (:runtime-service system))
        provider (name (get-in system [:config :llm :provider]))
        session-count (get-in storage [:details :session-count] 0)
        event-count (get-in storage [:details :event-count] 0)
        port (get-in system [:config :api :port])]
    (render
     [:div#shell-fragment.workspace-stack
      [:header.status-bar
       [:div.status-block
        [:span.status-label "mode"]
        [:span.status-value "dark"]]
       [:div.status-block
        [:span.status-label "provider"]
        [:span.status-value provider]]
       [:div.status-block
        [:span.status-label "port"]
        [:span.status-value (str port)]]
       [:div.status-block
        [:span.status-label "sessions"]
        [:span.status-value (str session-count)]]
       [:div.status-block
        [:span.status-label "runs"]
        [:span.status-value (str (:run-count runtime-health))]]
       [:div.status-block
        [:span.status-label "events"]
        [:span.status-value (str event-count)]]]
      [:nav.shell-nav
       (for [tab tabs]
         (tab-link tab active-tab))]
      (h/raw (case active-tab
             :chat (render-many
                    [:section.workspace-grid.two-up
                     (h/raw (sessions-fragment system))
                     (h/raw (session-detail-fragment system nil))])
             :runs (render-many
                    [:section.workspace-grid.two-up
                     (h/raw (runs-fragment system))
                     (h/raw (run-detail-fragment system nil))])
             :tools (render-many
                     [:section.workspace-grid.tools
                      [:section.panel.stack
                       (h/raw (tools-fragment system))]
                      [:section.panel.stack
                       (h/raw (tool-approvals-fragment
                               (tool-approvals/list-requests (:store system) {:limit 50})))
                       [:div#tool-results-panel.empty "Request approval, approve, then run."]]])
             :memory (render-many
                      [:section.workspace-grid.two-up
                       [:section.panel.stack
                        (h/raw (memory-prompt-fragment system))]
                       [:section.panel.stack
                        [:form#memory-search-form
                         [:h3 "Memory Search"]
                         [:input {:type "text" :name "query" :placeholder "search messages and events"}]
                         [:div.actions
                          [:button {:type "button"
                                    "data-on:click" "@post('/ui/memory/search', {contentType: 'form', selector: '#memory-search-form'})"}
                           "Search"]]]
                        [:div#memory-search-results-panel.empty "Run memory search."]]])
             (render-many
              [:section.workspace-grid.two-up
               (h/raw (dashboard-fragment system))
               (h/raw (events-fragment system))])))])))

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
       [:agent-chat-panel#session-detail-panel.panel
        {:data-session-id (:id session)}
        [:h2 (or (:title session) "Untitled session")]
        [:p.meta.code (:id session)]
        (h/raw (session-messages-fragment system (:id session)))
        [:form#chat-form
         {"data-on:submit" "@chatSubmit()"}
         [:input {:type "hidden" :name "session_id" :value (:id session)}]
         [:textarea.chat-input {:name "prompt"
                                :rows 1
                                "data-on:input" "@chatInput()"
                                "data-on:keydown" "@chatKeydown()"
                                :placeholder "Ask model something concrete"}]
         [:div#chat-status.meta.chat-status {:hidden true} "thinking..."]
         [:div.actions
          [:button {:type "submit"}
           "Send"]]]]))))

(defn session-messages-fragment [system session-id]
  (render
   [:div#session-messages-panel
    {"data-on-interval__duration.3s" (str "@get('/ui/session-messages?session_id=" session-id "')")}
    (if-let [messages (seq (sqlite/list-messages (:store system) session-id))]
      [:div.messages
       (for [{:keys [role content created-at]} messages]
         [:article.message
          [:div.message-role {:class role} role]
          [:div.code content]
          [:div.meta created-at]])]
      [:div.empty "No messages yet."])]))

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

(defn runs-fragment [system]
  (let [runs (runtime/list-runs (:runtime-service system) {:limit 50})]
    (render
     [:section#runs-panel.panel
      {"data-on-interval__duration.5s.leading" "@get('/ui/runs')"}
      [:h2 "Runs"]
      (if (seq runs)
        [:div.stack
         (for [{:keys [id name agent-id substrate status created-at]} runs]
           [:button.session-link
            {:type "button"
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
        [:option {:value "local-process"} "local-process"]
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

(defn run-detail-fragment [system run-id]
  (let [runs (runtime/list-runs (:runtime-service system) {:limit 50})
        run (or (when run-id (runtime/get-run (:runtime-service system) run-id))
                (when-let [candidate (first runs)]
                  (runtime/get-run (:runtime-service system) (:id candidate))))
        runner-status (when run
                        (when-let [runner (get (:runner-registry system) (keyword (:substrate run)))]
                          (runners/status runner (:id run))))]
    (render
     (if-not run
       [:section#run-detail-panel.panel
        [:h2 "Run Detail"]
        [:div.empty "No runs yet."]]
       [:section#run-detail-panel.panel
        {"data-on-interval__duration.5s.leading" (str "@get('/ui/run-detail?run_id=" (:id run) "')")}
        [:h2 (or (:name run) (:id run))]
        [:div.meta.code (:id run)]
        [:div.meta (str "agent: " (:agent-id run) " | substrate: " (:substrate run) " | status: " (:status run))]
        (when runner-status
          [:div.result
           [:strong "Runner"]
           [:div.code (json/generate-string runner-status {:pretty true})]])
        (when-let [heartbeat (:heartbeat run)]
          [:div.result
           [:strong "Latest heartbeat"]
           [:div.code (json/generate-string heartbeat {:pretty true})]])
        (when-let [checkpoint (:checkpoint run)]
          [:div.result
           [:strong "Latest checkpoint"]
           [:div.code (json/generate-string checkpoint {:pretty true})]])
        (when-let [commands (seq (:pending-commands run))]
          [:div.result
           [:strong "Pending commands"]
           [:div.code (json/generate-string commands {:pretty true})]])
        (when-let [output-events (seq (->> (sqlite/list-events (:store system)
                                                               {:entity-type :agent_run
                                                                :entity-id (:id run)
                                                                :limit 20})
                                           (filter #(= "agent.run.output" (:event-type %)))))]
          [:div.result
           [:strong "Recent output"]
           [:div.code
            (->> output-events
                 reverse
                 (map (fn [event]
                        (let [{:keys [stream line]} (:payload event)]
                          (str "[" stream "] " line))))
                 (str/join "\n"))]])
        [:div.actions
         [:form {:id (str "run-launch-" (:id run))}
          [:button {:type "button"
                    "data-on:click" (str "@post('/ui/runs/" (:id run) "/launch', {contentType: 'form', selector: '#run-launch-" (:id run) "'})")}
           "Launch"]]
         [:form {:id (str "run-cancel-" (:id run))}
          [:button {:type "button"
                    "data-on:click" (str "@post('/ui/runs/" (:id run) "/signal', {contentType: 'form', selector: '#run-cancel-" (:id run) "'})")}
           "Cancel"]]]
        [:div.result
         [:strong "Catch-up"]
         [:div.code (json/generate-string
                     {:heartbeats (runtime/list-heartbeats (:runtime-service system) (:id run) {:limit 5})
                      :checkpoints (runtime/list-checkpoints (:runtime-service system) (:id run) {:limit 5})
                      :commands (runtime/list-commands (:runtime-service system) (:id run) {:limit 5})
                      :events (sqlite/list-events (:store system) {:entity-type :agent_run
                                                                   :entity-id (:id run)
                                                                   :limit 10})}
                     {:pretty true})]]]))))
