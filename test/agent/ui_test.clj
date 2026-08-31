(ns agent.ui-test
  (:require
   [agent.build-info :as build-info]
   [agent.chat :as chat]
   [agent.channels.core :as channel-adapters]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as trace]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.ui :as ui]
   [agent.ui.catalog :as ui-catalog]
   [agent.ui.memory :as ui-memory]
   [agent.ui.render :as ui-render]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (org.jsoup Jsoup)))

(defn- temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-ui-" ".db")))

(deftest index-page-uses-datastar-and-web-components
  (let [html (ui/index-page)]
    (is (str/includes? html "datastar.js"))
    (is (str/includes? html "/public/web-components.js"))
    (is (str/includes? html "/ui/shell?tab=chat"))
    (is (not (str/includes? html "/public/app.js")))))

(deftest index-page-deep-link-loads-route-fragment
  (let [html (ui/index-page "/chat/session-1")]
    (is (str/includes? html "/ui/shell?tab=chat&amp;session_id=session-1"))))

(deftest ui-is-dark-only
  (let [css (slurp (io/file "public/app.css"))
        js (slurp (io/file "public/web-components.js"))
        ui-source (slurp (io/file "src/agent/ui.clj"))
        catalog-html (ui-catalog/page)]
    (is (str/includes? css "color-scheme: dark"))
    (is (not (str/includes? css ":root[data-theme=\"light\"]")))
    (is (not (str/includes? css "theme-switching")))
    (is (not (str/includes? js "ThemeToggle")))
    (is (not (str/includes? js "iris-theme")))
    (is (not (str/includes? ui-source "theme-toggle")))
    (is (not (str/includes? catalog-html "theme-toggle")))
    (is (not (str/includes? catalog-html "data-theme=")))))

(deftest ui-catalog-covers-reference-components-and-layouts
  (let [html (ui-catalog/page)]
    (is (str/includes? html "IRIS UI SYSTEM"))
    (is (str/includes? html "Status &amp; progress"))
    (is (str/includes? html "Cards, metrics &amp; tables"))
    (is (str/includes? html "Card tabs"))
    (is (str/includes? html "Horizontal tabs"))
    (is (str/includes? html "Vertical tabs"))
    (is (str/includes? html "Bottom tabs"))
    (is (str/includes? html "Left tabs"))
    (is (re-find #"/public/app\.css\?v=\d+" html))
    (is (re-find #"/public/web-components\.js\?v=\d+" html))
    (is (str/includes? html "Workflow canvas"))
    (is (str/includes? html "Layout recipes"))
    (is (str/includes? html "Dialog / onboarding"))))

(deftest ui-catalog-has-valid-structure-and-accessible-controls
  (let [doc (Jsoup/parse (ui-catalog/page))
        sections (.select doc ".ui-catalog-section")
        ids (mapv #(.id %) (.select doc "[id]"))]
    (is (= 1 (.size (.select doc ".ui-catalog-shell > aside.ui-catalog-nav"))))
    (is (= 1 (.size (.select doc ".ui-catalog-shell > main.ui-catalog-main"))))
    (is (= ["foundations" "actions" "forms" "status" "data" "navigation" "card-tabs"
            "layouts" "workflow" "feedback"]
           (mapv #(.id %) sections)))
    (is (= 22 (.size (.select doc ".ui-card-tab[role=tab]"))))
    (is (= 22 (.size (.select doc ".ui-card-tab > .ui-card-tab__label"))))
    (is (= 22 (.size (.select doc ".ui-catalog-card[role=tabpanel]"))))
    (is (= 1 (.size (.select doc "clipPath#ui-card-tab-horizontal path"))))
    (is (= 1 (.size (.select doc "clipPath#ui-card-tab-vertical path"))))
    (is (= 1 (.size (.select doc "clipPath#ui-card-tab-bottom path"))))
    (is (= 1 (.size (.select doc "clipPath#ui-card-tab-left path"))))
    (is (= 4 (.size (.select doc ".ui-card-tab[aria-selected=true]"))))
    (is (= 4 (.size (.select doc ".ui-card-tab--black"))))
    (is (= 4 (.size (.select doc ".ui-card-tab--yellow"))))
    (is (= 4 (.size (.select doc ".ui-card-tab--red"))))
    (is (= 4 (.size (.select doc ".ui-card-tabs--vertical .ui-card-tab"))))
    (is (= 4 (.size (.select doc ".ui-card-tabs--left .ui-card-tab"))))
    (is (= 2 (.size (.select doc ".ui-card-tab-list[aria-orientation=vertical]"))))
    (is (= (count ids) (count (distinct ids))) "catalog ids must be unique")
    (is (every? #(not (str/blank? (.attr % "aria-label")))
                (.select doc ".ui-icon-button"))
        "icon-only buttons need accessible names")
    (is (every? #(or (not (str/blank? (.attr % "aria-label")))
                     (some (fn [parent] (= "label" (.normalName parent)))
                           (.parents %)))
                (.select doc "input, textarea, select"))
        "form controls need a label or accessible name")))

(deftest design-document-covers-every-redesign-reference
  (let [design (slurp (io/file "DESIGN.md"))
        references (->> (file-seq (io/file "redesign-inspiration"))
                        (filter #(.isFile %))
                        (map #(.getName %))
                        (filter #(str/ends-with? % ".webp"))
                        sort)]
    (is (= 16 (count references)))
    (doseq [filename references]
      (is (str/includes? design filename)
          (str "DESIGN.md must map reference " filename)))))

(deftest design-document-covers-every-css-token
  (let [design (slurp (io/file "DESIGN.md"))
        css (slurp (io/file "public/app.css"))
        tokens (->> (re-seq #"(?m)^\s*(--[a-z0-9-]+)\s*:" css)
                    (map second)
                    set
                    sort)]
    (is (seq tokens))
    (doseq [token tokens]
      (is (str/includes? design (str "`" token "`"))
          (str "DESIGN.md must document token " token)))))

(deftest ui-avoids-thick-side-accent-borders
  (let [css (slurp (io/file "public/app.css"))]
    (is (not (re-find #"border-(?:left|right)\s*:\s*[2-9][0-9]*px" css)))
    (is (not (re-find #"border-(?:left|right)-width\s*:\s*[2-9][0-9]*px" css)))))

(deftest card-tab-hover-preserves-ink-color
  (let [css (slurp (io/file "public/app.css"))
        [_ hover-rule] (re-find #"(?s)\.ui-card-tab:not\(\[aria-selected=\"true\"\]\):hover\s*\{([^}]*)\}" css)]
    (is hover-rule)
    (is (str/includes? css ".ui-card-tab:hover { color: var(--tab-ink); }"))
    (is (not (str/includes? hover-rule "brightness(")))
    (is (not (str/includes? hover-rule "saturate(")))))

(deftest shell-brand-is-text-only
  (let [source (slurp (io/file "src/agent/ui.clj"))]
    (is (str/includes? source "[:strong \"IRIS\"]"))
    (is (not (str/includes? source "shell-brand__mark")))))

(deftest dashboard-fragment-shows-active-model
  (with-redefs [sqlite/health-check (constantly {:details {:session-count 0
                                                           :event-count 0
                                                           :schema-version 1
                                                           :tool-approval-count 0}})
                tools/registry-health (constantly {:count 0})
                memory/health-check (constantly {:vault {:note-count 0}})
                channel-adapters/registry-health (constantly {:count 0})
                tool-approvals/list-review-requests (constantly [])
                build-info/read-build-info (constantly {:version "abc123-dirty"
                                                        :commit-short "abc123"
                                                        :built-at "2026-06-14T10:20:30Z"})]
    (let [html (ui/dashboard-fragment
                {:config {:llm {:active-provider :openai-compatible
                                :providers {:openai-compatible {:type :openai-compatible
                                                                 :model "gpt-4o-mini"}}}}
                 :reload-state (atom {:status :idle})})]
      (is (str/includes? html ">Model</dt>"))
      (is (str/includes? html ">gpt-4o-mini</dd>"))
      (is (str/includes? html "Agent Control Plane"))
      (is (str/includes? html "overview-action-grid"))
      (is (str/includes? html "Current deployment"))
      (is (str/includes? html "href=\"/chat\""))
      (is (str/includes? html "href=\"/tools\""))
      (is (str/includes? html "href=\"/memory\""))
      (is (str/includes? html "href=\"/logs\""))
      (is (str/includes? html ">version</span>"))
      (is (str/includes? html ">abc123-dirty</span>"))
      (is (str/includes? html ">commit</span>"))
      (is (str/includes? html ">abc123</span>"))
      (is (str/includes? html ">built</span>"))
      (is (str/includes? html ">06-14 10:20</span>")))))

(deftest short-id-shortens-uuids-only
  (is (= "303ea8ca" (ui-render/short-id "303ea8ca-9665-4edd-bd97-b5c3cec87438")))
  (is (= "run-1" (ui-render/short-id "run-1"))
      "hyphenated non-UUID ids stay intact")
  (is (= "telegram" (ui-render/short-id "telegram"))))

(deftest operator-board-renders-sections-with-counts
  (with-redefs [tool-approvals/list-review-requests (constantly [])
                sqlite/list-events (constantly [])]
    (let [html (ui/operator-board-fragment {:store nil})]
      (is (str/includes? html "board-section"))
      (is (str/includes? html "count-badge"))
      (is (str/includes? html "board-section--empty"))
      (is (str/includes? html "empty-line"))
      (is (str/includes? html "View all activity")))))

(deftest tool-approvals-are-an-operator-queue
  (let [base {:requested-by "session-1"
              :reason "Agent requested filesystem access"
              :created-at "2026-07-12T10:00:00Z"
              :expires-at "2999-07-12T11:00:00Z"
              :requested-permissions #{:filesystem-write}
              :input {:path "/workspace/report.md" :content "draft"}}
        pending-id "303ea8ca-9665-4edd-bd97-b5c3cec87438"
        approvals [(assoc base :id pending-id :tool-name "fs_write" :status "pending")
                   (assoc base :id "approval-approved" :tool-name "shell" :status "approved"
                          :expires-at "2000-01-01T00:00:00Z"
                          :actor "operator" :decision-reason "Reviewed" :decided-at "2026-07-12T10:02:00Z")
                   (assoc base :id "approval-denied" :tool-name "fs_delete" :status "denied"
                          :expires-at "2000-01-01T00:00:00Z"
                          :actor "operator" :decision-reason "Unsafe path" :decided-at "2026-07-12T10:03:00Z")]
        html (ui/tool-approvals-fragment approvals)
        detail-html (ui/tool-approval-detail-fragment (first approvals))
        doc (Jsoup/parse html)]
    (is (= 3 (.size (.select doc ".approval-record"))))
    (is (= 4 (.size (.select doc ".approval-metric"))))
    (is (= 1 (.size (.select doc ".approval-status--pending"))))
    (is (= 1 (.size (.select doc ".approval-status--approved"))))
    (is (= 1 (.size (.select doc ".approval-status--denied"))))
    (is (= "pending" (.text (.first (.select doc ".approval-status")))))
    (is (not (str/includes? html "Tool input"))
        "queue rows load heavy details only when opened")
    (is (str/includes? detail-html "Tool input"))
    (is (str/includes? detail-html "What must change"))
    (let [detail-doc (Jsoup/parse detail-html)
          id-code (.first (.select detail-doc ".approval-metadata code"))]
      (is (= "303ea8ca" (.text id-code)))
      (is (= pending-id (.attr id-code "title"))))
    (is (str/includes? html (str "/ui/tool-approvals/" pending-id "/detail")))
    (is (str/includes? html "data-preserve-attr=\"open\""))
    (is (str/includes? html "/ui/tool-approvals/status?limit=20"))
    (is (not (.hasAttr (.first (.select doc "#tool-approvals-panel"))
                       "data-on-interval__duration.10s")))
    (is (not (str/includes? html "Local Tools")))
    (is (not (str/includes? html "tool-approvals/request")))
    (is (not (str/includes? html "fs-tool-form")))
    (is (not (str/includes? html "shell-tool-form")))))

(deftest route-fragment-patches-nav-and-workspace-only
  (with-redefs [tool-approvals/list-review-records (constantly [])]
    (let [html (ui/route-fragment {:store nil} {:tab :tools})]
      (is (str/includes? html "id=\"shell-nav\""))
      (is (str/includes? html "id=\"workspace-content\""))
      (is (str/includes? html "id=\"router-state\""))
      (is (not (str/includes? html "shell-header")))
      (is (str/includes? html "/ui/route?tab=chat"))
      (is (not (str/includes? html ".leading"))))))

(deftest create-session-form-posts-explicit-form-and-clears-on-success
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [html (ui/sessions-fragment {:store store})]
        (is (str/includes? html "selector: &apos;#create-session-form&apos;"))
        (is (str/includes? html "evt.detail.el === el")
            "datastar-fetch fires on document for every fetch; detail.el is the only correct initiator guard")
        (is (str/includes? html "el.reset()"))
        (is (str/includes? html "name=\"project_id\""))
        (is (str/includes? html "Project (optional)"))
        (is (not (str/includes? html "new session title"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest session-project-is-visible-and-editable-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "project chat"
                                            {:metadata {:project-id "alpha"}})
            sidebar (ui/sessions-fragment {:store store} (:id session))
            detail (ui/session-detail-fragment {:store store} (:id session))]
        (is (str/includes? sidebar "project alpha |"))
        (is (str/includes? sidebar "value=\"alpha\""))
        (is (str/includes? detail "/ui/session/project"))
        (is (str/includes? detail "name=\"project_id\""))
        (is (str/includes? detail "value=\"alpha\"")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest sessions-refresh-preserves-selected-session
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [_first-session (sqlite/create-session! store "first")
            selected-session (sqlite/create-session! store "selected")
            html (ui/sessions-fragment {:store store} (:id selected-session))]
        (is (str/includes? html
                           (str "@get(&apos;/ui/sessions?session_id="
                                (:id selected-session)
                                "&apos;)")))
        (is (str/includes? html "session-link--active"))
        (is (str/includes? html "aria-current=\"page\""))
        (is (str/includes? html "selected")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest sessions-sidebar-switches-between-chat-and-ephemeral-sessions
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [chat-session (sqlite/create-session! store "Persistent chat")
            cron-session (sqlite/create-session! store "Ephemeral cron" {:kind :cron})
            chat-html (ui/sessions-fragment {:store store} (:id chat-session))
            cron-html (ui/sessions-fragment {:store store} (:id cron-session))]
        (testing "persistent view"
          (is (str/includes? chat-html "Persistent chat"))
          (is (not (str/includes? chat-html "Ephemeral cron")))
          (is (str/includes? chat-html ">Chats 1</span>"))
          (is (str/includes? chat-html ">Ephemeral 1</span>"))
          (is (str/includes? chat-html "aria-selected=\"true\"")))
        (testing "ephemeral view"
          (is (str/includes? cron-html "Ephemeral cron"))
          (is (not (str/includes? cron-html "Persistent chat")))
          (is (str/includes? cron-html "session-kind-tab--active"))
          (is (str/includes? cron-html
                             (str "/ui/session-detail?session_id=" (:id chat-session))))
          (is (str/includes? cron-html
                             (str "/ui/sessions?session_id=" (:id cron-session))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest ephemeral-session-deep-link-does-not-fall-back-to-chat
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (sqlite/create-session! store "Wrong persistent chat")
      (let [cron-session (sqlite/create-session! store "Cron transcript" {:kind :cron})]
        (sqlite/append-message! store (:id cron-session) "assistant" "Cron output")
        (let [html (ui/session-detail-fragment {:store store} (:id cron-session))
              route-html (ui/route-fragment {:store store}
                                             {:tab :chat :session-id (:id cron-session)})]
          (is (str/includes? html "Cron transcript"))
          (is (str/includes? html "Cron output"))
          (is (not (str/includes? html "Wrong persistent chat")))
          (is (str/includes? route-html "Ephemeral"))
          (is (str/includes? route-html "Cron transcript"))
          (is (str/includes? route-html "Cron output"))
          (is (= (str "/chat/" (:id cron-session))
                 (ui/session-route-path {:store store} (:id cron-session))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest session-message-content-renders-markdown-and-sanitizes-html
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "xss")
            payload "<script>alert(1)</script><img src=\"x\" onerror=\"alert(2)\">\n\n**bold** [ok](https://example.com) [bad](javascript:alert(3))"]
        (sqlite/append-message! store (:id session) "user" payload)
        (let [html (ui/session-messages-fragment {:store store} (:id session))]
          (is (not (str/includes? html "alert(1)"))
              "script tags and their bodies are removed, not displayed")
          (is (str/includes? html "<strong>bold</strong>"))
          (is (str/includes? html "href=\"https://example.com\""))
          (is (not (str/includes? html "<script")))
          (is (not (str/includes? html "onerror")))
          (is (not (re-find #"<img[^>]*src=\"x\"" html))
              "non-https image sources are dropped")
          (is (not (str/includes? html "javascript:alert")))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest session-transcript-limits-initial-render-and-loads-older
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "history")]
        (doseq [n (range 65)]
          (sqlite/append-message! store (:id session) "user" (str "message-" n)))
        (let [html (with-redefs [sqlite/list-messages
                                 (fn [& _]
                                   (throw (ex-info "full history must not be loaded" {})))]
                     (ui/session-messages-fragment {:store store} (:id session)))
              doc (Jsoup/parse html)]
          (is (= 60 (.size (.select doc "article.message"))))
          (is (str/includes? html "Load 5 older"))
          (is (str/includes? html "&amp;limit=120"))
          (is (not (str/includes? html "message-0")))
          (is (str/includes? html "message-64"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest chat-live-request-uses-explicit-cancellation
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "stream")
            html (ui/session-detail-fragment {:store store} (:id session))]
        (is (str/includes? html "requestCancellation: window.irisChatStreamController")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest session-message-renders-image-content-block
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "image")
            data "aW1hZ2UtYnl0ZXM="]
        (sqlite/append-message! store
                                (:id session)
                                "user"
                                "photo.png"
                                {:content-blocks [{:type :image
                                                   :source {:type :base64
                                                            :media-type "image/png"
                                                            :value data}
                                                   :filename "photo.png"
                                                   :alt "photo.png"}]})
        (let [html (ui/session-messages-fragment {:store store} (:id session))]
          (is (str/includes? html "<img"))
          (is (str/includes? html "message-media__image"))
          (is (str/includes? html (str "data:image/png;base64," data)))
          (is (str/includes? html "photo.png"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest chat-form-accepts-image-upload
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "form")
            html (ui/session-detail-fragment {:store store} (:id session))]
        (is (str/includes? html "enctype=\"multipart/form-data\""))
        (is (str/includes? html "type=\"file\""))
        (is (str/includes? html "accept=\"image/*\""))
        (is (str/includes? html "name=\"image\"")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest chat-controls-reflect-generation-state
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "controls")]
        (with-redefs [chat/session-state (fn [_ _]
                                           {:working? false
                                            :loop-active? false
                                            :queued-count 0})]
          (let [html (ui/session-detail-fragment {:store store} (:id session))]
            (is (str/includes? html "data-chat-scroll-bottom"))
            (is (str/includes? html "chat-stop"))
            (is (str/includes? html "style=\"display:none\""))
            (is (str/includes? html "data-show=\"$chatLoading\""))))
        (with-redefs [chat/session-state (fn [_ _]
                                           {:working? true
                                            :loop-active? false
                                            :queued-count 0})]
          (let [html (ui/session-detail-fragment {:store store} (:id session))]
            (is (str/includes? html "data-show=\"true\""))
            (is (not (str/includes? html "chat-stop style=\"display:none\""))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest working-session-uses-status-spinner-not-thinking-message
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "working")]
        (with-redefs [chat/session-state (fn [_ _]
                                           {:working? true
                                            :queued-count 0})]
          (let [messages-html (ui/session-messages-fragment {:store store} (:id session))
                detail-html (ui/session-detail-fragment {:store store} (:id session))]
            (is (not (str/includes? messages-html "thinking")))
            (is (not (str/includes? messages-html "message--streaming")))
            (is (str/includes? detail-html "chat-spinner"))
            (is (not (str/includes? detail-html ">thinking"))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest session-messages-render-thinking-and-bottom-anchor
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "thinking")
            message (sqlite/append-message! store
                                            (:id session)
                                            "assistant"
                                            "answer"
                                            {:metadata {:thinking "hidden reasoning"}})
            html (ui/session-messages-fragment {:store store} (:id session))]
        (is (str/includes? html "message-thinking"))
        (is (str/includes? html "data-preserve-attr=\"open\""))
        (is (str/includes? html (str "id=\"message-thinking-" (:id message) "\"")))
        (is (str/includes? html "hidden reasoning"))
        (is (str/includes? html "chat-stream__bottom-anchor"))
        (is (str/includes? html "id=\"chat-bottom-anchor\"")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest session-messages-render-streaming-thinking-with-preserved-open-state
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "streaming-thinking")
            html (ui/session-messages-fragment {:store store}
                                               (:id session)
                                               {:streaming {:thinking "live reasoning"}})]
        (is (str/includes? html "message-thinking"))
        (is (str/includes? html "data-preserve-attr=\"open\""))
        (is (str/includes? html "id=\"message-thinking-streaming\""))
        (is (str/includes? html "live reasoning")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest session-messages-render-thinking-from-content-blocks
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "thinking-block")]
        (sqlite/append-message! store
                                (:id session)
                                "assistant"
                                "answer"
                                {:content-blocks [{:type :thinking :text "block reasoning"}
                                                  {:type :text :text "answer"}]})
        (let [html (ui/session-messages-fragment {:store store} (:id session))]
          (is (str/includes? html "message-thinking"))
          (is (str/includes? html "block reasoning"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest session-messages-render-thinking-from-think-tags
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "think-tags")]
        (sqlite/append-message! store
                                (:id session)
                                "assistant"
                                "<think>old reasoning</think>\nanswer")
        (let [html (ui/session-messages-fragment {:store store} (:id session))]
          (is (str/includes? html "message-thinking"))
          (is (str/includes? html "old reasoning"))
          (is (str/includes? html "answer"))
          (is (not (str/includes? html "&lt;think&gt;")))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest memory-recall-message-content-is-escaped
  (let [payload "<img src=\"x\" onerror=\"alert(1)\"> [link](javascript:alert(1))"
        html (ui/memory-search-results-fragment
              {:query "<script>alert(1)</script>"
               :results [{:surface :message
                          :type "assistant"
                          :id "msg-1"
                          :scope {:type :session :id "session-1"}
                          :status :current
                          :text payload
                          :score 1.0
                          :source {:message-id "msg-1"}
                          :reason :exact-match
                          :tags []}]
               :surface-counts {:messages 1
                                :events 0
                                :vault-chunks 0}})]
    (is (str/includes? html "<a>link</a>"))
    (is (not (str/includes? html "onerror")))
    (is (not (str/includes? html "<script")))
    (is (not (str/includes? html "<img")))
    (is (not (str/includes? html "href=\"javascript")))))

(deftest trusted-fragment-requires-rendered-html
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires output"
                        (ui-render/trusted-fragment "<script>alert(1)</script>")))
  (let [safe (ui-render/render [:span "safe"])
        html (ui-render/render [:div (ui-render/trusted-fragment safe)])]
    (is (str/includes? html "<span>safe</span>"))))

(deftest memory-workspace-exposes-tool-and-search-lab
  (let [path (temp-db-path)
        root (.toFile (java.nio.file.Files/createTempDirectory
                       "iris-ui-memory-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        note (io/file root "inbox/note.md")
        approved-note (io/file root "preferences/approved.md")
        store (sqlite/create-store {:path path})
        memory-service (memory/create-memory-service
                        {:search {:default-limit 10}
                         :vault {:paths [(.getAbsolutePath root)]
                                 :writable? true}}
                        store)]
    (try
      (.mkdirs (.getParentFile note))
      (spit note
            (str "---\n"
                 "id: mem_ui\n"
                 "type: Reference\n"
                 "title: UI note\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: candidate\n"
                 "---\n\n"
                 "UI note body\n"))
      (.mkdirs (.getParentFile approved-note))
      (spit approved-note
            (str "---\n"
                 "id: mem_ui_approved\n"
                 "type: Preference\n"
                 "title: Approved UI note\n"
                 "description: Stable interface preference\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "  origins:\n"
                 "  - type: session\n"
                 "    session_id: session-1\n"
                 "---\n\n"
                 "Approved note body\n"))
      (memory/reindex-vault! memory-service)
      (let [revision (:revision (sqlite/get-vault-note-by-id store "mem_ui_approved"))]
        (memory/propose-vault-note-delete! memory-service "mem_ui_approved" revision
                                           {:source :test}))
      (let [system {:store store
                    :memory-service memory-service
                    :magi-service {:config {:enabled? true
                                            :memory-promotion {:mode :manual
                                                               :scopes #{:all}}}}}
            overview-html (ui/memory-workspace-fragment system)
            approvals-html (ui/memory-workspace-fragment system nil
                                                          {:limit 20 :tab :approvals})
            browser-html (ui/memory-workspace-fragment system nil
                                                        {:limit 20
                                                         :tab :browser
                                                         :note-id "mem_ui_approved"})]
        (testing "second-level navigation"
          (doseq [label ["Overview" "Approvals" "Browser"]]
            (is (str/includes? overview-html (str ">" label "</button>"))))
          (is (str/includes? overview-html "role=\"tablist\""))
          (is (str/includes? overview-html "memory-tab--active")))
        (testing "overview"
          (is (str/includes? overview-html "Memory Tool"))
          (is (str/includes? overview-html "/ui/memory/tool"))
          (is (not (str/includes? overview-html "Reset facts")))
          (is (not (str/includes? overview-html "/ui/memory/facts/reset")))
          (is (str/includes? overview-html "Memory Recall"))
          (is (str/includes? overview-html "Audit &amp; Reindex"))
          (is (str/includes? overview-html "/ui/memory/vault/reindex"))
          (is (str/includes? overview-html "vault-search"))
          (is (not (str/includes? overview-html "write-vault")))
          (is (not (str/includes? overview-html "read-vault")))
          (is (str/includes? overview-html "Scratchpad"))
          (is (str/includes? overview-html "scratchpad-read"))
          (is (str/includes? overview-html "scratchpad-replace"))
          (is (str/includes? overview-html "expected_revision"))
          (is (str/includes? overview-html "old_text"))
          (is (str/includes? overview-html "new_text"))
          (is (str/includes? overview-html "memory-tab-grid"))
          (is (not (str/includes? overview-html "memory-note-card"))))
        (testing "approvals"
          (is (str/includes? approvals-html "Memory Approvals"))
          (is (not (str/includes? approvals-html "/ui/memory/vault/move")))
          (is (str/includes? approvals-html "/ui/memory/vault/magi"))
          (is (str/includes? approvals-html ">Review</button>"))
          (is (str/includes? approvals-html ">Advice</button>"))
          (is (str/includes? approvals-html ">Candidates</h3>"))
          (is (not (str/includes? approvals-html ">Approved</h3>")))
          (is (str/includes? approvals-html "memory-note-card"))
          (is (str/includes? approvals-html "memory-note-actions"))
          (is (str/includes? approvals-html "Source details"))
          (is (str/includes? approvals-html "/ui/memory/vault/mem_ui/detail"))
          (is (str/includes? approvals-html "Operation Proposals"))
          (is (str/includes? approvals-html "note delete"))
          (is (str/includes? approvals-html "/ui/memory/proposals/decision"))
          (is (str/includes? approvals-html ">Apply</button>"))
          (is (str/includes? approvals-html ">Reject</button>"))
          (is (not (str/includes? approvals-html "memory-source-details"))))
        (testing "file browser"
          (is (str/includes? browser-html "Memory Files"))
          (is (str/includes? browser-html "inbox"))
          (is (str/includes? browser-html "preferences"))
          (is (str/includes? browser-html "note.md"))
          (is (str/includes? browser-html "approved.md"))
          (is (str/includes? browser-html "memory-tree-file--active"))
          (is (str/includes? browser-html "Approved UI note"))
          (is (str/includes? browser-html "Approved note body"))
          (is (str/includes? browser-html "Raw source")))
        (let [detail-html (ui-memory/vault-note-detail-fragment
                           (sqlite/get-vault-note-by-id store "mem_ui_approved")
                           nil)]
          (is (str/includes? detail-html "memory-source-details"))
          (is (str/includes? detail-html "Stable interface preference")))
        (is (not (str/includes? overview-html "source json")))
        (is (not (str/includes? overview-html "memory-facts"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file path true)))))

(deftest memory-tool-input-omits-blank-content
  (require 'agent.api.handlers.ui)
  (let [tool-input @(resolve 'agent.api.handlers.ui/memory-tool-input)]
    (testing "blank content from the always-submitted textarea is dropped"
      (is (= {:action "search" :query "hey" :limit 10}
             (tool-input {:action "search" :query "hey" :limit "10" :content ""}))))
    (testing "vault write fields are no longer memory-tool inputs"
      (is (= {:action "vault-search" :query "tags"}
             (tool-input {:action "vault-search"
                          :query "tags"
                          :path "notes.md"
                          :content "hello"}))))
    (testing "scratchpad replace keeps blank exact-replace text"
      (is (= {:action "scratchpad-replace"
              :old-text ""
              :new-text ""
              :expected-revision "abc"}
             (tool-input {:action "scratchpad-replace"
                          :old_text ""
                          :new_text ""
                          :expected_revision "abc"}))))))

(deftest memory-tool-result-shows-text-and-source-json
  (let [html (ui/memory-tool-result-fragment
	              {:ok? true
	               :input {:action :recall :query "tags"}
	               :result "Memory recall for: tags\n- vault_chunk #1 score=0.500 approved exact-match: x"
	               :source-json {:results [{:surface :vault_chunk
	                                         :text "vault/tags project"}]}})]
	    (is (str/includes? html "Memory recall for: tags"))
	    (is (str/includes? html "source json"))
	    (is (str/includes? html "vault/tags"))))

(deftest logs-fragment-shows-events-and-trace-state
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        dir (.toFile (java.nio.file.Files/createTempDirectory
                      "iris-ui-trace-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        runtime-trace (trace/create-trace {:mode :rolling :path "trace.jsonl"} (.getPath dir))]
    (try
      (sqlite/log-event! store {:event-type :test.event
                                :entity-type :test
                                :entity-id "1"
                                :payload {:ok true}})
      (trace/record-event! runtime-trace {:event-type :llm.call
                                          :success true
                                          :payload {:model "m"}})
      (let [html (ui/logs-fragment {:store store :trace runtime-trace})]
        (is (str/includes? html "Event Log"))
        (is (str/includes? html "Runtime Trace"))
        (is (str/includes? html "test.event"))
        (is (str/includes? html "llm.call"))
        (is (str/includes? html "log-metrics"))
        (is (= 2 (.size (.select (Jsoup/parse html) ".log-table"))))
        (is (= 2 (.size (.select (Jsoup/parse html) ".log-record"))))
        (is (str/includes? html "data-preserve-attr=\"open\""))
        (is (not (str/includes? html "data-on-interval")))
        (is (not (str/includes? html "&quot;ok&quot;")))
        (is (not (str/includes? html "event-item"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)
        (io/delete-file dir true)))))

(deftest magi-fragment-shows-decision-log
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (sqlite/log-event! store
                         {:event-type :tool.approval.magi_evaluated
                          :entity-type :tool_approval
                          :entity-id "approval-1"
                          :payload {:tool-name "shell"
                                    :input {:argv ["printf" "ok"]}
                                    :filter {:kind :yes-no
                                             :domain :tool-approval
                                             :risk :low
                                             :question "Allow?"
                                             :expected-response :permit
                                             :context {}}
                                    :agents {:melchior {:response :yes :comment "ok"}
                                             :balthasar {:response :conditional :comment "narrow scope"}
                                             :casper {:response :yes :comment "ok"}}
                                    :judge {:decision :conditional
                                            :reason "narrow scope"}
                                    :decision :conditional
                                    :reason "narrow scope"
                                    :duration-ms 12}})
      (sqlite/log-event! store
                         {:event-type :tool-execution-end
                          :entity-type :tool
                          :entity-id "magi"
                          :payload {:tool-name "magi"
                                    :status "succeeded"
                                    :receipt {:input {:question "double check"}
                                              :result {:filter {:kind :yes-no
                                                                :domain :policy
                                                                :risk :low
                                                                :question "double check"
                                                                :expected-response :permit
                                                                :context {}}
                                                       :agents {:melchior {:response :yes}
                                                                :balthasar {:response :yes}
                                                                :casper {:response :yes}}
                                                       :decision :yes
                                                       :reason "all yes"}}
                                    :duration-ms 7}})
      (sqlite/log-event! store
                         {:event-type :memory.vault.magi_evaluated
                          :entity-type :vault_note
                          :entity-id "mem-1"
                          :payload {:source "manual"
                                    :note {:id "mem-1" :title "Stable preference"}
                                    :input {:question "Promote note?"}
                                    :filter {:kind :yes-no
                                             :domain :memory-promotion
                                             :risk :medium
                                             :question "Promote note?"
                                             :expected-response :permit}
                                    :agents {:melchior {:response :yes}
                                             :balthasar {:response :yes}
                                             :casper {:response :yes}}
                                    :judge {:decision :yes :reason "supported"}
                                    :decision :yes
                                    :reason "supported"
                                    :duration-ms 9
                                    :applied true}})
      (let [html (ui/magi-fragment {:store store})
            event (first (sqlite/list-events store {:event-type :tool.approval.magi_evaluated
                                                     :limit 1}))
            detail-html (ui/magi-detail-fragment event)]
        (is (str/includes? html "decision log"))
        (is (str/includes? html "Invocation Log"))
        (is (not (str/includes? html "Decision Console")))
        (is (str/includes? html "latest 3 records"))
        (is (str/includes? html "approval"))
        (is (str/includes? html "tool"))
        (is (str/includes? html "/ui/magi/1/detail"))
        (is (not (str/includes? html "BALTHASAR")))
        (is (str/includes? html "CONDITIONAL"))
        (is (not (str/includes? html "printf")))
        (is (str/includes? html "vault-note"))
        (is (str/includes? detail-html "BALTHASAR"))
        (is (str/includes? detail-html "MELCHIOR"))
        (is (str/includes? detail-html "input"))
        (is (str/includes? detail-html "filter"))
        (is (str/includes? detail-html "judge"))
        (is (str/includes? detail-html "event"))
        (is (str/includes? detail-html "printf")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest tool-message-summary-shows-arguments
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "tools")
            payload "{\"status\":\"ok\",\"tool-name\":\"web\",\"input\":{\"query\":\"clojure\"},\"result\":{\"answer\":\"done\"}}"]
        (sqlite/append-message! store (:id session) "tool" payload {:tool-call-id "call_1"})
        (let [messages (sqlite/list-messages store (:id session))
              html (ui/session-messages-fragment {:store store} (:id session))
              detail-html (ui-render/tool-detail-fragment messages "1" "call_1")]
          (is (str/includes? html "web"))
          (is (str/includes? html "done"))
          (is (str/includes? html "query: clojure"))
          (is (str/includes? html "<details"))
          (is (str/includes? html "tool-entry"))
          (is (str/includes? html "data-preserve-attr=\"open\""))
          (is (str/includes? html "answer"))
          (is (str/includes? detail-html "Call"))
          (is (str/includes? detail-html "Result"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest assistant-tool-calls-render-as-expandable-row
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "tool-calls")
            tool-calls [{:id "call_1"
                         :type "function"
                         :function {:name "http"
                                    :arguments "{\"url\":\"http://example.test\",\"method\":\"GET\"}"}}]]
        (sqlite/append-message! store (:id session) "assistant" "" {:tool-calls tool-calls})
        (let [html (ui/session-messages-fragment {:store store} (:id session))]
          (is (str/includes? html "class=\"tool-row"))
          (is (str/includes? html "requested"))
          (is (str/includes? html "<details"))
          (is (str/includes? html "tool-entry"))
          (is (str/includes? html "url: http://example.test"))
          (is (str/includes? html "method: GET"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest assistant-tool-call-and-result-render-as-one-expandable-row
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "tool-merge")
            tool-calls [{:id "call_1"
                         :type "function"
                         :function {:name "web_search"
                                    :arguments "{\"q\":\"clojure\"}"}}]
            payload "{\"status\":\"ok\",\"tool-name\":\"web_search\",\"input\":{\"q\":\"clojure\"},\"result\":{\"results\":[{\"title\":\"one\"},{\"title\":\"two\"}]}}"]
        (sqlite/append-message! store (:id session) "assistant" "" {:tool-calls tool-calls})
        (sqlite/append-message! store (:id session) "tool" payload {:tool-call-id "call_1"})
        (let [messages (sqlite/list-messages store (:id session))
              html (ui/session-messages-fragment {:store store} (:id session))
              detail-html (ui-render/tool-detail-fragment messages "1" "call_1")]
          (is (= 1 (count (re-seq #"<details class=\"tool-entry\"" html))))
          (is (str/includes? html "web_search"))
          (is (str/includes? html "done"))
          (is (str/includes? html "2 results"))
          (is (str/includes? html "/ui/chat/tool-detail"))
          (is (not (str/includes? html ">Call<")))
          (is (str/includes? detail-html "Call"))
          (is (str/includes? detail-html "Result"))
          (is (str/includes? detail-html "data-ignore-morph"))
          (is (not (str/includes? html "message--tool\"")))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest format-tokens-compacts-large-counts
  (is (= "950" (ui-render/format-tokens 950)))
  (is (= "12.3k" (ui-render/format-tokens 12345)))
  (is (= "2.3M" (ui-render/format-tokens 2345678)))
  (is (= "0" (ui-render/format-tokens nil))))

(deftest thread-stats-aggregates-tokens-and-tools-across-full-history
  (let [messages [{:role "user" :content "hi"}
                  {:role "assistant" :content ""
                   :tool-calls [{:function {:name "read"}} {:function {:name "bash"}}]
                   :metadata {:usage {:tokens 100 :prompt-tokens 80 :completion-tokens 20 :cached-tokens 10}}}
                  {:role "tool" :content "result" :tool-call-id "c1"}
                  {:role "assistant" :content ""
                   :tool-calls [{:function {:name "read"}}]
                   :metadata {:usage {:tokens 150 :prompt-tokens 120 :completion-tokens 30 :cached-tokens 40}}}
                  {:role "assistant" :content "done"
                   :metadata {:usage {:tokens 200 :prompt-tokens 160 :completion-tokens 40 :cached-tokens 50}}}]
        stats (ui-render/thread-stats messages)]
    ;; cumulative SUM over every turn = total billed across the thread
    (is (= 450 (:total-tokens stats)))
    (is (= 360 (:prompt-tokens stats)))
    (is (= 90 (:completion-tokens stats)))
    (is (= 100 (:cached-tokens stats)))
    ;; current context window = most recent turn's prompt + completion
    (is (= 200 (:context-tokens stats)))
    ;; total tool calls + per-tool breakdown (desc)
    (is (= 3 (:tool-calls stats)))
    (is (= [["read" 2] ["bash" 1]] (:tool-breakdown stats)))))

(deftest thread-stats-bar-empty-when-no-usage-or-tools
  (is (nil? (ui-render/thread-stats-bar [{:role "user" :content "hi"}
                                         {:role "assistant" :content "hello"}]))))

(deftest session-messages-fragment-surfaces-per-message-and-thread-stats
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "stats")]
        (sqlite/append-message! store (:id session) "user" "hi" nil)
        (sqlite/append-message! store (:id session) "assistant" ""
                                {:tool-calls [{:id "c1" :function {:name "read" :arguments "{}"}}]
                                 :metadata {:usage {:tokens 1234 :prompt-tokens 1000
                                                    :completion-tokens 234 :cached-tokens 321}}})
        (sqlite/append-message! store (:id session) "assistant" "done"
                                {:metadata {:usage {:tokens 5678 :prompt-tokens 5000
                                                    :completion-tokens 678 :cached-tokens 0
                                                    :duration-ms 2000}}})
        (let [html (ui/session-messages-fragment {:store store} (:id session))
              doc (Jsoup/parse html)]
          ;; per-message badge: token count + tool count in the .meta footer
          (is (str/includes? html "1.2k tok"))
          (is (str/includes? html "321 cache"))
          (is (str/includes? html "1 tool"))
          (is (str/includes? html "5.7k tok"))
          (is (str/includes? html "339.0 t/s"))
          ;; per-thread aggregate bar
          (is (str/includes? html "thread-stats"))
          (is (str/includes? html "6.9k"))
          (is (str/includes? html "avg"))
          (is (str/includes? html "read"))
          (is (= 1 (.size (.select doc "#session-messages-panel > .thread-stats"))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))
