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
   [agent.ui.render :as ui-render]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

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

(deftest dashboard-fragment-shows-active-model
  (with-redefs [sqlite/health-check (constantly {:details {:session-count 0
                                                           :event-count 0
                                                           :schema-version 1
                                                           :tool-approval-count 0}})
                tools/registry-health (constantly {:count 0})
                memory/health-check (constantly {:vault {:note-count 0}})
                channel-adapters/registry-health (constantly {:count 0})
                tool-approvals/list-requests (constantly [])
                build-info/read-build-info (constantly {:version "abc123-dirty"
                                                        :commit-short "abc123"
                                                        :built-at "2026-06-14T10:20:30Z"})]
    (let [html (ui/dashboard-fragment
                {:config {:llm {:active-provider :openai-compatible
                                :providers {:openai-compatible {:type :openai-compatible
                                                                 :model "gpt-4o-mini"}}}}
                 :reload-state (atom {:status :idle})})]
      (is (str/includes? html ">model</span>"))
      (is (str/includes? html ">gpt-4o-mini</span>"))
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
  (with-redefs [tool-approvals/list-requests (constantly [])
                sqlite/list-events (constantly [])]
    (let [html (ui/operator-board-fragment {:store nil})]
      (is (str/includes? html "board-section"))
      (is (str/includes? html "count-badge"))
      (is (str/includes? html "board-section--empty"))
      (is (str/includes? html "empty-line")))))

(deftest create-session-form-posts-explicit-form-and-clears-on-success
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [html (ui/sessions-fragment {:store store})]
        (is (str/includes? html "selector: &apos;#create-session-form&apos;"))
        (is (str/includes? html "evt.detail.el === el")
            "datastar-fetch fires on document for every fetch; detail.el is the only correct initiator guard")
        (is (str/includes? html "el.reset()")))
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
      (memory/reindex-vault! memory-service)
      (let [html (ui/memory-workspace-fragment {:store store
                                                :memory-service memory-service})]
        (is (str/includes? html "Memory Tool"))
        (is (str/includes? html "/ui/memory/tool"))
	        (is (not (str/includes? html "Reset facts")))
	        (is (not (str/includes? html "/ui/memory/facts/reset")))
	        (is (str/includes? html "Memory Recall"))
	        (is (str/includes? html "Audit &amp; Reindex"))
	        (is (str/includes? html "/ui/memory/vault/reindex"))
	        (is (str/includes? html "/ui/memory/vault/move"))
	        (is (str/includes? html "vault-search"))
        (is (not (str/includes? html "write-vault")))
        (is (not (str/includes? html "read-vault")))
        (is (str/includes? html "Scratchpad"))
        (is (str/includes? html "scratchpad-read"))
        (is (str/includes? html "scratchpad-replace"))
        (is (str/includes? html "expected_revision"))
        (is (str/includes? html "old_text"))
        (is (str/includes? html "new_text"))
	        (is (str/includes? html "workspace-grid memory-workspace"))
        (is (str/includes? html "memory-left-stack"))
        (is (str/includes? html "memory-right-stack"))
        (is (not (str/includes? html "memory-facts"))))
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
        (is (str/includes? html "llm.call")))
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
      (let [html (ui/magi-fragment {:store store})]
        (is (str/includes? html "MAGI OVERSIGHT"))
        (is (str/includes? html "Invocation Log"))
        (is (str/includes? html "Decision Console"))
        (is (str/includes? html "latest 1000 records"))
        (is (str/includes? html "approval"))
        (is (str/includes? html "tool"))
        (is (str/includes? html "BALTHASAR"))
        (is (str/includes? html "MELCHIOR"))
        (is (str/includes? html "input"))
        (is (str/includes? html "filter"))
        (is (str/includes? html "judge"))
        (is (str/includes? html "event"))
        (is (str/includes? html "CONDITIONAL"))
        (is (str/includes? html "printf"))
        (is (str/includes? html "double check")))
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
                                                    :completion-tokens 234 :cached-tokens 321}}})
        (sqlite/append-message! store (:id session) "assistant" "done"
                                {:metadata {:usage {:tokens 5678 :prompt-tokens 5000
                                                    :completion-tokens 678 :cached-tokens 0}}})
        (let [html (ui/session-messages-fragment {:store store} (:id session))]
          ;; per-message badge: token count + tool count in the .meta footer
          (is (str/includes? html "1.2k tok"))
          (is (str/includes? html "321 cache"))
          (is (str/includes? html "1 tool"))
          (is (str/includes? html "5.7k tok"))
          ;; per-thread aggregate bar
          (is (str/includes? html "thread-stats"))
          (is (str/includes? html "6.9k"))
          (is (str/includes? html "read"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))
