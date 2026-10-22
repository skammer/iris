(ns agent.ui.sessions
  "Session navigation, chat forms and paginated transcript fragments."
  (:require
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.ui.render :as ui-render]
   [clojure.string :as str]
   [agent.ui.routes :as ui-routes]))

(defn- session-title-node [{:keys [id title]} chat?]
  [(if chat? :h2 :strong)
   {:id (if chat? "chat-session-title" (str "session-title-" id))
    :title (if chat? (str title " · " id) (or title "Untitled session"))}
   (or title "Untitled session")])

(defn session-title-fragments [system session-id]
  (if-let [session (sqlite/get-session (:store system) session-id)]
    (ui-render/render-many (session-title-node session false) (session-title-node session true))
    ""))

(defn session-projects-fragment [system prefix]
  (ui-render/render
   [:datalist#session-project-ids
    (for [project-id (sqlite/session-project-ids (:store system) prefix)]
      [:option {:value project-id}])]))

(defn- session-list-offset [value total]
  (let [requested (try (Long/parseLong (str (or value 0))) (catch Exception _ 0))]
    (* 50 (quot (max 0 (min requested (max 0 (dec total)))) 50))))

(defn sessions-fragment
  ([system] (sessions-fragment system nil))
  ([system active-session-id] (sessions-fragment system active-session-id {}))
  ([system active-session-id opts]
   (let [store (:store system)
         selected-session (some->> active-session-id not-empty
                                   (sqlite/get-session store))
         active-kind (if (= :cron (:kind selected-session)) :cron :chat)
         counts (sqlite/session-kind-counts store)
         offset (session-list-offset (:offset opts) (get counts active-kind))
         sessions-by-kind (into {}
                                (for [kind [:chat :cron]]
                                  [kind (sqlite/list-sessions store {:kind kind
                                                                    :limit (if (= kind active-kind) 50 1)
                                                                    :offset (if (= kind active-kind) offset 0)})]))
         page-sessions (get sessions-by-kind active-kind)
         active-id (or (:id selected-session) (some-> page-sessions first :id))
         sessions (if (and selected-session (not-any? #(= active-id (:id %)) page-sessions))
                    (cons selected-session page-sessions)
                    page-sessions)
         page-url (fn [offset*]
                    (str "/ui/sessions?session_id=" (ui-render/url-encode active-id) "&offset=" offset*))]
     (ui-render/render
      [:aside#sessions-panel.panel.sessions-sidebar
       {"data-on-interval__duration.15s"
        (str "document.hidden || el.contains(document.activeElement) || @get('/ui/sessions"
             (when active-id
               (str "?session_id=" (ui-render/url-encode active-id)))
             (when (pos? offset) (str "&offset=" offset))
             "')")}
       ;; datastar-fetch events are dispatched on document for EVERY fetch on
       ;; the page; evt.detail.el identifies the initiator. Guarding on it is
       ;; the only way a reset doesn't fire for unrelated polls and patches.
       [:form#create-session-form.create-session-form
        {"data-on:submit" "@post('/ui/sessions', {contentType: 'form', selector: '#create-session-form'})"
         "data-on:datastar-fetch" "evt.detail.el === el && evt.detail.type === 'finished' && el.reset()"
         "data-indicator" "createSessionLoading"}
        [:div.compact-form-row
         [:input {:type "text"
                  :name "project_id"
                  :list "session-project-ids"
                  "data-on:input__debounce.200ms" "@get('/ui/sessions/projects?prefix=' + encodeURIComponent(el.value))"
                  "data-on:focus" "@get('/ui/sessions/projects?prefix=' + encodeURIComponent(el.value))"
                  :placeholder "Project (optional)"
                  :aria-label "Project for new session"
                  :maxlength "64"
                  :pattern "[a-z0-9][a-z0-9._-]{0,63}"
                  :autocomplete "off"}]
         [:button {:type "submit"
                   "data-attr:disabled" "$createSessionLoading"}
          "New"]]
        (ui-render/trusted-fragment (session-projects-fragment system ""))]
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
             :aria-selected (if active? "true" "false")
             :disabled (nil? target-id)
             "data-route" (when target-id
                            (ui-routes/route-path {:tab :chat :session-id target-id}))
             "data-on:click" (when target-id
                               (str "@get('/ui/session-detail?session_id=" target-id "')"))}
            [:span (str label " " (get counts kind))]])]]
       (if (seq sessions)
         [:div.session-list
          (for [{:keys [id created-at metadata] :as session} sessions
                :let [state (chat/session-state system id)]]
            [:button.session-link
             {:type "button"
              :class (when (= id active-id) "session-link--active")
              :aria-current (when (= id active-id) "page")
             "data-route" (ui-routes/route-path {:tab :chat :session-id id})
             "data-on:click" (str "@get('/ui/session-detail?session_id=" id "&offset=" offset "')")}
             (session-title-node session false)
             [:div.session-meta {:title created-at}
              (str (when-let [project-id (:project-id metadata)]
                     (str "project " project-id " | "))
                   (ui-render/short-timestamp created-at)
                   (when (:loop-active? state) " | loop")
                   (when (:working? state) " | working")
                   (when (pos? (:queued-count state))
                     (str " | queued " (:queued-count state))))]])]
         [:div.empty "No sessions yet."])
       (when (> (get counts active-kind) 50)
         [:nav.session-pagination {:aria-label "Session pages"}
          [:button {:type "button" :disabled (zero? offset) :aria-label "Previous sessions"
                    "data-on:click" (str "@get('" (page-url (max 0 (- offset 50))) "')")} "←"]
          [:span (str (inc offset) "–" (+ offset (count page-sessions)) " / " (get counts active-kind))]
          [:button {:type "button" :disabled (>= (+ offset 50) (get counts active-kind)) :aria-label "Next sessions"
                    "data-on:click" (str "@get('" (page-url (+ offset 50)) "')")} "→"]])]))))

(defn- session-target [system session-id]
  (let [store (:store system)]
    (or (some->> session-id not-empty (sqlite/get-session store))
        (first (sqlite/list-sessions store {:kind :chat :limit 1})))))

(defn session-route-path [system session-id]
  (if-let [session (session-target system session-id)]
    (ui-routes/route-path {:tab :chat :session-id (:id session)})
    "/chat"))

(defn- chat-status-nodes [state]
  (let [visible? (or (:working? state) (:loop-active? state))
        label (cond
                (:loop-active? state) (str "Loop active, " (:loop-label state))
                (pos? (:queued-count state)) (str "Working, queued " (:queued-count state))
                :else "Working")
        text (cond
               (:loop-active? state) (:loop-label state)
               (pos? (:queued-count state)) (str "queued " (:queued-count state))
               :else "")
        visibility {:style (when-not visible? "display:none")
                    "data-show" (if visible? "true" "$chatLoading")}]
    [[:button#chat-stop.chat-stop
      (assoc visibility :type "button"
             "data-on:click" "@post('/ui/chat/stop', {contentType: 'form', selector: '#chat-form'})")
      "Stop"]
     [:div#chat-status.meta.chat-status (assoc visibility :role "status" :aria-label label)
      [:span.chat-spinner {:aria-hidden "true"}]
      [:span.chat-status__text text]]]))

(defn session-status-fragment [system session-id]
  (apply ui-render/render-many (chat-status-nodes (chat/session-state system session-id))))

(declare session-messages-fragment)

(defn session-detail-fragment [system session-id]
  (let [session (session-target system session-id)]
    (ui-render/render
     (if-not session
       [:section#session-detail-panel.panel
        [:h2 "Transcript"]
        [:div.empty "Create session to start chatting."]]
       (let [state (chat/session-state system (:id session))
             [stop-node status-node] (chat-status-nodes state)]
         [:agent-chat-panel#session-detail-panel.panel
          {:data-session-id (:id session)
           "data-init" (str "@get('/ui/session/live?session_id=" (:id session)
                            "&resync=true&client_id=' + window.irisUiClientId, {requestCancellation: window.irisChatStreamController, openWhenHidden: true, retry: 'always', retryMaxCount: 10, retryMaxWaitMs: 10000})")
           "data-on:iris-reconnect" (str "@get('/ui/session/live?session_id=" (:id session)
                                         "&resync=true&client_id=' + window.irisUiClientId, {requestCancellation: window.irisChatStreamController, openWhenHidden: true, retry: 'always', retryMaxCount: 10, retryMaxWaitMs: 10000})")}
          [:div.chat-titlebar
           (session-title-node session true)
           [:form.session-project-form
            {"data-on:submit" "@post('/ui/session/project', {contentType: 'form', selector: 'form.session-project-form'})"}
            [:input {:type "hidden" :name "session_id" :value (:id session)}]
            [:input {:type "text"
                     :name "project_id"
                     :value (or (get-in session [:metadata :project-id]) "")
                     :list "session-project-ids"
                  "data-on:input__debounce.200ms" "@get('/ui/sessions/projects?prefix=' + encodeURIComponent(el.value))"
                  "data-on:focus" "@get('/ui/sessions/projects?prefix=' + encodeURIComponent(el.value))"
                     :placeholder "No project"
                     :maxlength "64"
                     :pattern "[a-z0-9][a-z0-9._-]{0,63}"
                     :autocomplete "off"
                     :aria-label "Project ID"}]
            [:button {:type "submit"} "Set"]]
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
            [:span {:aria-hidden "true"} "↓"]
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
          [:iris-chat-composer {:data-session-id (:id session)}
          [:div.connection-status {:hidden true :role "status" :data-connection-status true}
           [:span "Connection lost."]
           [:button {:type "button" :data-chat-reconnect true} "Reconnect"]]
          [:form#chat-form
           {"data-on:submit" "@post('/ui/chat', {contentType: 'form'}); el.reset()"
            "data-indicator" "chatLoading"
            "data-class:is-loading" "$chatLoading"
            "data-skill-autocomplete" "true"
            :enctype "multipart/form-data"}
           [:input {:type "hidden" :name "client_id"
                    "data-attr:value" "window.irisUiClientId"}]
           [:input {:id (str "chat-session-id-" (:id session))
                    :type "hidden"
                    :name "session_id"
                    :value (:id session)}]
           [:textarea.chat-input {:name "prompt"
                                  :data-skill-input "true"
                                  :data-submit-on-enter "true"
                                  :rows 1
                                  :aria-label "Message"
                                  :placeholder "Message…"}]
          [:label.chat-file-input
           [:span "Image"]
           [:input {:type "file"
                    :name "image"
                    :accept "image/*"
                    :multiple true}]]
          stop-node
           [:button {:type "submit"
                     "data-attr:disabled" "$chatLoading"}
            "Send"]
           status-node]]])))))

(defn- streaming-message [{:keys [content thinking]}]
  [:article#streaming-message.message.message--assistant.message--streaming
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

(defn session-streaming-fragment
  "Patch only the in-flight message; never read persisted history for a token."
  ([system session-id] (session-streaming-fragment system session-id {}))
  ([system session-id opts]
   (let [streaming (streaming-state system session-id opts)]
     (ui-render/render
      (if (some #(not (str/blank? (str %))) (vals streaming))
        (streaming-message streaming)
        [:div#streaming-message {:hidden true}])))))

(def ^:private default-visible-messages 60)

(defn- history-button [session-id direction cursor label]
  [:button.chat-history-more
   {:type "button" :data-history-latest (when-not cursor true)
    "data-on:click" (str "@get('/ui/session-messages?session_id=" (ui-render/url-encode session-id)
                         (when cursor (str "&" (name direction) "=" cursor))
                         "&client_id=' + window.irisUiClientId)")}
   label])

(defn session-messages-fragment
  ([system session-id]
   (session-messages-fragment system session-id {}))
  ([system session-id opts]
   (let [history? (or (:before opts) (:after opts))
         page (when history? (sqlite/message-page (:store system) session-id opts))
         message-count (sqlite/count-messages (:store system) session-id)
         visible-messages (if history? (:messages page) (sqlite/list-recent-messages (:store system) session-id default-visible-messages))
         hidden-count (- message-count (count visible-messages))
         thread-stats (sqlite/session-thread-stats (:store system) session-id)
         state (chat/session-state system session-id)
         streaming (when-not history? (streaming-state system session-id opts))
         streaming* (cond-> {}
                      (not (str/blank? (str (:content streaming))))
                      (assoc :content (str (:content streaming)))
                      (not (str/blank? (str (:thinking streaming))))
                      (assoc :thinking (str (:thinking streaming))))
         streaming? (seq streaming*)]
     (ui-render/render
      [:chat-stream#session-messages-panel
       {:data-history (str (boolean history?))}
       (if (or (seq visible-messages) streaming? (:working? state))
         (list*
          (ui-render/thread-stats-bar thread-stats)
          [:nav.chat-history-nav {:aria-label "Message history"}
           (when (if history? (or (:after opts) (:more? page)) (pos? hidden-count))
             (history-button session-id :before (:id (first visible-messages)) "Older"))
           (when history?
             (history-button session-id nil nil "Latest"))
           (when (and history? (or (:before opts) (:more? page)))
             (history-button session-id :after (:id (last visible-messages)) "Newer"))]
          [:div.chat-stream__filler]
          (concat
           (ui-render/message-list system visible-messages)
           (cond
             streaming? [(streaming-message streaming*)]
             :else [[:div#streaming-message {:hidden true}]])))
         (list (when history? (history-button session-id nil nil "Latest"))
               [:div.empty (if history? "No messages on this page." "No messages yet.")]
               [:div#streaming-message {:hidden true}]))
       [:div#chat-bottom-anchor.chat-stream__bottom-anchor {:aria-hidden "true"}]]))))
