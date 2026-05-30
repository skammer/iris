(ns agent.ui-test
  (:require
   [agent.chat :as chat]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as trace]
   [agent.ui :as ui]
   [agent.ui.render :as ui-render]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn- temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-ui-" ".db")))

(deftest index-page-uses-datastar-and-web-components
  (let [html (ui/index-page)]
    (is (str/includes? html "datastar.js"))
    (is (str/includes? html "/public/web-components.js"))
    (is (str/includes? html "/ui/shell?tab=chat"))
    (is (not (str/includes? html "/public/app.js")))))

(deftest index-page-deep-link-loads-route-fragment
  (let [html (ui/index-page "/runs/run-1")]
    (is (str/includes? html "/ui/shell?tab=runs&amp;run_id=run-1"))))

(deftest create-session-form-posts-explicit-form-and-clears-on-success
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [html (ui/sessions-fragment {:store store})]
        (is (str/includes? html "selector: &apos;#create-session-form&apos;"))
        (is (str/includes? html "evt.detail.type === &apos;finished&apos;"))
        (is (str/includes? html "evt.currentTarget.reset()")))
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
        (is (str/includes? html "selected")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest session-message-content-is-escaped
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "xss")
            payload "<script>alert(1)</script><img src=\"x\" onerror=\"alert(2)\"> **bold**"]
        (sqlite/append-message! store (:id session) "user" payload)
        (let [html (ui/session-messages-fragment {:store store} (:id session))]
          (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
          (is (str/includes? html "**bold**"))
          (is (not (str/includes? html "<script")))
          (is (not (str/includes? html "<img")))
          (is (not (str/includes? html "onerror=\"alert(2)\"")))))
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

(deftest memory-search-message-content-is-escaped
  (let [payload "<img src=\"x\" onerror=\"alert(1)\"> [link](javascript:alert(1))"
        html (ui/memory-search-results-fragment
              {:query "<script>alert(1)</script>"
               :messages [{:session-id "session-1"
                           :role "assistant"
                           :content payload
                           :created-at "2026-04-19T00:00:00Z"}]
               :events []})]
    (is (str/includes? html "&lt;img src=&quot;x&quot; onerror=&quot;alert(1)&quot;&gt;"))
    (is (str/includes? html "[link](javascript:alert(1))"))
    (is (not (str/includes? html "<script")))
    (is (not (str/includes? html "<img")))
    (is (not (str/includes? html "onerror=\"alert(1)\"")))))

(deftest trusted-fragment-requires-rendered-html
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires output"
                        (ui-render/trusted-fragment "<script>alert(1)</script>")))
  (let [safe (ui-render/render [:span "safe"])
        html (ui-render/render [:div (ui-render/trusted-fragment safe)])]
    (is (str/includes? html "<span>safe</span>"))))

(deftest memory-workspace-exposes-tool-and-datalog-lab
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        memory-service (memory/create-memory-service
                        {:prompt {:paths []}
                         :search {:default-limit 10}
                         :graph {:enabled false}}
                        store)]
    (try
      (let [html (ui/memory-workspace-fragment {:store store
                                                :memory-service memory-service})]
        (is (str/includes? html "Memory Tool"))
        (is (str/includes? html "/ui/memory/tool"))
        (is (str/includes? html "Reset facts"))
        (is (str/includes? html "/ui/memory/facts/reset"))
        (is (str/includes? html "Reset graph"))
        (is (str/includes? html "/ui/memory/graph/reset"))
        (is (str/includes? html "Datalog DB"))
        (is (str/includes? html "/ui/memory/datalog"))
        (is (str/includes? html "workspace-grid memory-workspace")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest memory-tool-result-shows-text-and-source-json
  (let [html (ui/memory-tool-result-fragment
              {:ok? true
               :input {:action :search :query "tags"}
               :result "Memory results for: tags\n- graph score=0.500: x"
               :source-json {:ranked [{:surface :graph
                                        :item {:fact/tags ["project"]
                                               :edge/tags ["memory"]}}]}})]
    (is (str/includes? html "Memory results for: tags"))
    (is (str/includes? html "source json"))
    (is (str/includes? html "fact/tags"))
    (is (str/includes? html "edge/tags"))))

(deftest memory-datalog-result-preserves-namespaced-keywords
  (let [html (ui/memory-datalog-result-fragment
              {:ok? true
               :result {:query "[:find ?e]"
                        :args []
                        :limit 10
                        :row-count 1
                        :rows [[{:fact/tags ["tag-a"]
                                  :edge/tags ["tag-b"]}]]}})]
    (is (str/includes? html "fact/tags"))
    (is (str/includes? html "edge/tags"))))

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
        (is (str/includes? html "llm.call")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)
        (io/delete-file dir true)))))

(deftest tool-message-summary-shows-arguments
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "tools")
            payload "{\"status\":\"ok\",\"tool-name\":\"web\",\"input\":{\"query\":\"clojure\"},\"result\":{\"answer\":\"done\"}}"]
        (sqlite/append-message! store (:id session) "tool" payload {:tool-call-id "call_1"})
        (let [html (ui/session-messages-fragment {:store store} (:id session))]
          (is (str/includes? html "web"))
          (is (str/includes? html "ok"))
          (is (str/includes? html "query: clojure"))
          (is (str/includes? html "data-tool-detail"))
          (is (not (str/includes? html "<details")))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest assistant-tool-calls-render-as-status-row-with-sidebar-details
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
          (is (str/includes? html "data-tool-detail-template"))
          (is (not (str/includes? html "<details")))
          (is (str/includes? html "url: http://example.test"))
          (is (str/includes? html "method: GET"))))
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
                                                    :completion-tokens 234 :cached-tokens 0}}})
        (sqlite/append-message! store (:id session) "assistant" "done"
                                {:metadata {:usage {:tokens 5678 :prompt-tokens 5000
                                                    :completion-tokens 678 :cached-tokens 0}}})
        (let [html (ui/session-messages-fragment {:store store} (:id session))]
          ;; per-message badge: token count + tool count in the .meta footer
          (is (str/includes? html "1.2k tok"))
          (is (str/includes? html "1 tool"))
          (is (str/includes? html "5.7k tok"))
          ;; per-thread aggregate bar
          (is (str/includes? html "thread-stats"))
          (is (str/includes? html "6.9k"))
          (is (str/includes? html "read"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))
