(ns agent.ui.sessions-test
  (:require
   [agent.ui.test-support :refer [temp-db-path]]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.ui :as ui]
   [agent.ui.render :as ui-render]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (org.jsoup Jsoup)))

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
          (is (str/includes? html ">Older</button>"))
          (is (str/includes? html "&amp;before="))
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
