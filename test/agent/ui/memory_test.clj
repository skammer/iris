(ns agent.ui.memory-test
  (:require
   [agent.api.handlers.ui.memory :as memory-handler]
   [agent.ui.test-support :refer [temp-db-path]]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.ui :as ui]
   [agent.ui.memory :as ui-memory]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (org.jsoup Jsoup)))

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
          (doseq [[html selected] [[overview-html "Overview"]
                                  [approvals-html "Approvals"]
                                  [browser-html "Browser"]]]
            (let [doc (Jsoup/parse html)
                  tabs (.select doc "[role=tablist] [role=tab]")]
              (is (= selected (.text (.select doc "[role=tab][aria-selected=true]"))))
              (is (every? #{"true" "false"} (map #(.attr % "aria-selected") tabs))))))
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
  (let [tool-input @#'memory-handler/memory-tool-input]
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
