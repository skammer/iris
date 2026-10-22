(ns agent.ui-test
  (:require
   [agent.build-info :as build-info]
   [agent.channels.core :as channel-adapters]
   [agent.config :as config]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.ui :as ui]
   [agent.ui.catalog :as ui-catalog]
   [agent.ui.render :as ui-render]
   [clojure.string :as str]
   [clojure.test :refer [deftest is ]])
  (:import
   (org.jsoup Jsoup)))

(deftest index-page-uses-datastar-and-web-components
  (let [html (ui/index-page)]
    (is (str/starts-with? html "<!doctype html>"))
    (is (not (str/includes? html "cdn.jsdelivr.net")))
    (is (not (str/includes? html "katex.min.js")))
    (is (str/includes? html "/public/web-components.js"))
    (is (str/includes? html "/ui/shell?tab=chat"))
    (is (not (str/includes? html "/public/app.js")))))

(deftest index-page-deep-link-loads-route-fragment
  (let [html (ui/index-page "/chat/session-1")]
    (is (str/includes? html "/ui/shell?tab=chat&amp;session_id=session-1"))))

(deftest ui-catalog-has-valid-structure-and-accessible-controls
  (let [doc (Jsoup/parse (ui-catalog/page))
        ids (mapv #(.id %) (.select doc "[id]"))]
    (is (= 1 (.size (.select doc ".ui-catalog-shell > aside.ui-catalog-nav"))))
    (is (= 1 (.size (.select doc ".ui-catalog-shell > main.ui-catalog-main"))))
    (let [tabs (.select doc "[role=tab]")
          panels (.select doc "[role=tabpanel]")]
      (is (seq tabs))
      (is (= (count tabs) (count panels)))
      (is (every? #(not (str/blank? (.text %))) tabs)
          "tabs need an accessible name")
      (is (every? #(some? (.getElementById doc (.attr % "aria-controls"))) tabs)
          "every tab must reference an existing panel")
      (is (every? #(some? (.getElementById doc (.attr % "aria-labelledby"))) panels)
          "every panel must reference its tab"))
    (is (= (count ids) (count (distinct ids))) "catalog ids must be unique")
    (is (every? #(not (str/blank? (.attr % "aria-label")))
                (.select doc ".ui-icon-button"))
        "icon-only buttons need accessible names")
    (is (every? #(or (not (str/blank? (.attr % "aria-label")))
                     (some (fn [parent] (= "label" (.normalName parent)))
                           (.parents %)))
                (.select doc "input, textarea, select"))
        "form controls need a label or accessible name")))

(deftest shell-brand-is-accessible-text-link
  (with-redefs [sqlite/health-check (constantly {:details {}})
                ui/sessions-fragment (constantly (ui-render/render [:div]))
                ui/session-detail-fragment (constantly (ui-render/render [:div]))]
    (let [doc (Jsoup/parse (ui/shell-fragment {:config config/default-config} :chat))
          brand (.selectFirst doc "a.shell-brand")]
      (is (= "IRIS" (.text brand)))
      (is (= "/" (.attr brand "href")))
      (is (= "Iris control plane" (.attr brand "aria-label")))
      (is (empty? (.select brand "img, svg"))))))

(deftest dashboard-fragment-shows-active-model
  (with-redefs [sqlite/health-check (constantly {:details {:session-count 0
                                                           :event-count 0
                                                           :schema-version 1
                                                           :tool-approval-count 0}})
                sqlite/dashboard-activity (constantly [{:day "2026-09-06" :chat 2 :cron 0 :tools 0 :memory 0 :logs 3}])
                tools/registry-health (constantly {:count 0})
                memory/health-check (constantly {:vault {:note-count 0}})
                channel-adapters/registry-health (constantly {:count 0})
                tool-approvals/list-review-requests (constantly [])
                build-info/read-build-info (constantly {:version "abc123-dirty"
                                                        :commit-short "abc123"
                                                        :built-at "2026-06-14T10:20:30Z"})]
    (let [html (ui/dashboard-fragment
                {:store :dashboard-test
                 :config {:llm {:active-provider :openai-compatible
                                :providers {:openai-compatible {:type :openai-compatible
                                                                 :model "gpt-4o-mini"}}}}
                 :reload-state (atom {:status :idle})})]
      (is (str/includes? html ">Model</dt>"))
      (is (str/includes? html ">gpt-4o-mini</dd>"))
      (is (str/includes? html "<h1>Overview</h1>"))
      (is (str/includes? html "overview-action-grid"))
      (is (= 5 (count (re-seq #"class=\"overview-activity__chart\"" html))))
      (is (str/includes? html "Turns: 2 over 7 UTC days, including today"))
      (is (str/includes? html "2026-09-06 UTC: 2 Turns"))
      (is (not (str/includes? html "NaN")))
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
    (let [html (ui/route-fragment {:store nil} {:tab :tools})
          doc (Jsoup/parse html)
          nav (.first (.select doc "#shell-nav"))
          selected (.first (.select nav ".ui-card-tab[aria-selected=true]"))]
      (is (str/includes? html "id=\"shell-nav\""))
      (is (str/includes? html "id=\"workspace-content\""))
      (is (str/includes? html "id=\"router-state\""))
      (is (not (str/includes? html "shell-header")))
      (is (str/includes? html "/ui/route?tab=chat"))
      (is (.hasClass nav "ui-card-tabs--horizontal"))
      (is (= 7 (.size (.select nav ".ui-card-tab[role=tab]"))))
      (is (.hasClass (.first (.select nav "#shell-tab-chat"))
                     "ui-card-tab--black"))
      (is (= "shell-tab-tools" (.id selected)))
      (is (.hasClass selected "ui-card-tab--graphite"))
      (is (= "workspace-content"
             (.attr (.first (.select nav "#shell-tab-tools")) "aria-controls")))
      (is (= "shell-tab-tools"
             (.attr (.first (.select doc "#workspace-content")) "aria-labelledby")))
      (is (.hasClass (.first (.select doc "#workspace-content"))
                     "workspace-content--graphite"))
      (is (not (str/includes? html ".leading"))))))

(deftest trusted-fragment-requires-rendered-html
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires output"
                        (ui-render/trusted-fragment "<script>alert(1)</script>")))
  (let [safe (ui-render/render [:span "safe"])
        html (ui-render/render [:div (ui-render/trusted-fragment safe)])]
    (is (str/includes? html "<span>safe</span>"))))
