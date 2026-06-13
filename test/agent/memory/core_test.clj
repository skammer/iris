(ns agent.memory.core-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.memory.core :as memory]
   [agent.memory.recall :as recall]
   [agent.persistence.sqlite :as sqlite]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defrecord NoteProvider [responses]
  llm-core/ILLMProvider
  (complete [_ _ _] "")
  (stream [_ _ _] nil)
  (embed [_ _ _] [])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-tools true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ _]
    {:role "assistant"
     :content (first (first (swap-vals! responses rest)))
     :tool-calls []
     :usage nil
     :raw nil})
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defrecord CapturingNoteProvider [responses requests]
  llm-core/ILLMProvider
  (complete [_ _ _] "")
  (stream [_ _ _] nil)
  (embed [_ _ _] [])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-tools true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ request]
    (swap! requests conj request)
    {:role "assistant"
     :content (first (first (swap-vals! responses rest)))
     :tool-calls []
     :usage nil
     :raw nil})
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-memory-" ".db")))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-memory-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn test-service [store cfg]
  (memory/create-memory-service
   (merge {:search {:default-limit 10}
           :notes {:extractor {:enabled false}
                   :default-scope :session}
           :vault {:paths []}}
          cfg)
   store))

(deftest memory-service-exposes-surfaces-and-search-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "mem")]
    (try
      (sqlite/append-message! store (:id session) "user" "hello memory")
      (sqlite/log-event! store {:event-type :session.created
                                :entity-type :session
                                :entity-id (:id session)
                                :payload {:title "memory hello"}})
      (let [service (test-service store {})
            results (memory/search-memory service "hello")
            surfaces (memory/list-surfaces service)]
        (is (= [:search :vault] (mapv :name surfaces)))
        (is (= 1 (count (:messages results))))
        (is (= 1 (count (:events results)))))
      (finally
        (io/delete-file db-path true)))))

(deftest memory-search-limit-is-configurable-and-clamped-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "mem-limit")
        service (test-service store {:search {:default-limit 2
                                              :max-limit 3}})]
    (try
      (doseq [idx (range 6)]
        (sqlite/append-message! store (:id session) "assistant" (str "needle result " idx)))
      (is (= 2 (count (:ranked (memory/search-memory service "needle")))))
      (is (= 3 (count (:ranked (memory/search-memory service "needle" {:limit 99})))))
      (finally
        (io/delete-file db-path true)))))

(deftest memory-extraction-writes-candidate-vault-notes-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        responses (atom [(json/generate-string
                          {:notes [{:type "Preference"
                                    :title "Concise answers"
                                    :description "User prefers concise answers."
                                    :body "User prefers concise answers."
                                    :tags ["preference"]
                                    :scope "global"
                                    :confidence 0.9}]})])
        provider (->NoteProvider responses)
        service (test-service store {:notes {:extractor {:enabled true}
                                             :default-scope :session}
                                     :vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}})]
    (try
      (let [saved (memory/extract-and-save-notes! service
                                                  provider
                                                  {:user-message "I prefer concise answers"
                                                   :assistant-message "noted"}
                                                  {:session-id "s1"
                                                   :source-request-id "req-1"
                                                   :source-message-ids ["m1" "m2"]})
            note-path (:path (first saved))
            content (slurp note-path)
            recall-results (:results (recall/recall service "concise answers" {:limit 10}))]
        (is (= 1 (count saved)))
        (is (str/includes? note-path "/inbox/"))
        (is (str/includes? content "status: \"candidate\""))
        (is (str/includes? content "scope: \"global\""))
        (is (str/includes? content "message_id: \"m1\""))
        (is (str/includes? content "> I prefer concise answers"))
        (is (= 1 (sqlite/count-vault-notes store)))
        (is (empty? (filter #(= :vault_chunk (:surface %)) recall-results))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest memory-extraction-defaults-to-json-schema-output-test
  (let [responses (atom [(json/generate-string {:notes []})])
        requests (atom [])
        provider (->CapturingNoteProvider responses requests)]
    (memory/extract-notes provider
                          {:user-message "I prefer concise answers"
                           :assistant-message "noted"
                           :model "model"
                           :session-id "s1"
                           :extractor {:enabled true}})
    (let [request (first @requests)]
      (is (= "memory_notes" (get-in request [:structured-output :name])))
      (is (nil? (:response-format request))))))

(deftest memory-extraction-supports-json-object-output-test
  (let [responses (atom [(json/generate-string {:notes []})])
        requests (atom [])
        provider (->CapturingNoteProvider responses requests)]
    (memory/extract-notes provider
                          {:user-message "I prefer concise answers"
                           :assistant-message "noted"
                           :model "model"
                           :session-id "s1"
                           :extractor {:enabled true
                                       :format :json-object}})
    (let [request (first @requests)]
      (is (= {:type "json_object"} (:response-format request)))
      (is (nil? (:structured-output request))))))

(deftest memory-search-thresholds-and-dedupes-ranked-values-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "rank")
        service (test-service store {:search {:default-limit 10
                                              :min-score 0.3}})]
    (try
      (sqlite/append-message! store (:id session) "user" "Alice likes Clojure")
      (sqlite/append-message! store (:id session) "user" "Bob likes Java")
      (let [results (memory/search-memory service "Alice Clojure" {:limit 10})
            ranked (:ranked results)]
        (is (= 1 (count ranked)))
        (is (= :message (:surface (first ranked))))
        (is (= "Alice likes Clojure" (get-in ranked [0 :item :content])))
        (is (not-any? #(str/includes? (:content %) "Java") (:messages results))))
      (finally
        (io/delete-file db-path true)))))

(deftest cross-session-memory-recall-rank-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        approved (io/file root "preferences/concise.md")
        store (sqlite/create-store {:path db-path})
        session-a (sqlite/create-session! store "source")
        session-b (sqlite/create-session! store "recall")
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]}})]
    (try
      (.mkdirs (.getParentFile approved))
      (sqlite/append-message! store (:id session-a) "user" "I prefer concise answers.")
      (spit approved
            (str "---\n"
                 "id: mem_concise\n"
                 "type: Preference\n"
                 "title: Concise answers\n"
                 "description: User prefers concise answers.\n"
                 "tags: [memory, preference]\n"
                 "timestamp: 2026-06-13T12:00:00Z\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "  confidence: 0.95\n"
                 "  origins:\n"
                 "  - type: message\n"
                 "    session_id: " (:id session-a) "\n"
                 "---\n\n"
                 "# Concise answers\n\n"
                 "User prefers concise answers.\n"))
      (memory/reindex-vault! service)
      (sqlite/close-store! store)
      (let [restarted-store (sqlite/create-store {:path db-path})
            restarted-service (test-service restarted-store {:vault {:paths [(.getAbsolutePath root)]}})
            results (recall/recall restarted-service
                                   "concise answers preference"
                                   {:limit 5
                                    :session-id (:id session-b)
                                    :scope {:type :session :id (:id session-b)}})]
        (is (= 1 (count (:results results))))
        (is (= :vault_chunk (get-in results [:results 0 :surface])))
        (is (= "Preference" (get-in results [:results 0 :type])))
        (is (= :approved (get-in results [:results 0 :status])))
        (is (= {:type "global"} (get-in results [:results 0 :scope])))
        (is (str/includes? (get-in results [:results 0 :text]) "concise answers"))
        (sqlite/close-store! restarted-store))
      (finally
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest vault-recall-includes-current-session-notes-only-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        global (io/file root "approved/global.md")
        session-a (io/file root "sessions/a.md")
        session-b (io/file root "sessions/b.md")
        auto-session (io/file root "sessions/auto.md")
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]}})]
    (try
      (doseq [file [global session-a session-b auto-session]]
        (.mkdirs (.getParentFile file)))
      (spit global
            (str "---\n"
                 "id: mem_global\n"
                 "type: Reference\n"
                 "title: Global marker\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "---\n\n"
                 "shared session-marker detail\n"))
      (spit session-a
            (str "---\n"
                 "id: mem_session_a\n"
                 "type: ProjectNote\n"
                 "title: Session A marker\n"
                 "iris:\n"
                 "  scope: session\n"
                 "  status: approved\n"
                 "  origins:\n"
                 "  - type: message\n"
                 "    session_id: s1\n"
                 "---\n\n"
                 "s1 session-marker detail\n"))
      (spit session-b
            (str "---\n"
                 "id: mem_session_b\n"
                 "type: ProjectNote\n"
                 "title: Session B marker\n"
                 "iris:\n"
                 "  scope: session\n"
                 "  status: approved\n"
                 "  origins:\n"
                 "  - type: message\n"
                 "    session_id: s2\n"
                 "---\n\n"
                 "s2 session-marker detail\n"))
      (spit auto-session
            (str "---\n"
                 "id: mem_auto_session\n"
                 "type: ProjectNote\n"
                 "title: Auto session marker\n"
                 "iris:\n"
                 "  scope: session\n"
                 "  status: auto_session\n"
                 "  origins:\n"
                 "  - type: message\n"
                 "    session_id: s1\n"
                 "---\n\n"
                 "auto session-marker detail\n"))
      (memory/reindex-vault! service)
      (let [without-session (memory/search-vault service "session-marker" {:limit 10})
            with-session (memory/search-vault service "session-marker" {:limit 10 :session-id "s1"})
            ids-with-session (set (map :note-id with-session))]
        (is (= ["mem_global"] (mapv :note-id without-session)))
        (is (= #{"mem_global" "mem_session_a" "mem_auto_session"} ids-with-session))
        (is (not (contains? ids-with-session "mem_session_b"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest vault-note-status-update-promotes-candidate-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        candidate (io/file root "inbox/promote.md")
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}})]
    (try
      (.mkdirs (.getParentFile candidate))
      (spit candidate
            (str "---\n"
                 "id: mem_promote\n"
                 "type: Preference\n"
                 "title: Promotion marker\n"
                 "iris:\n"
                 "  scope: session\n"
                 "  status: candidate\n"
                 "  origins:\n"
                 "  - type: manual\n"
                 "---\n\n"
                 "promotion-marker detail\n"))
      (memory/reindex-vault! service)
      (is (empty? (memory/search-vault service "promotion-marker" {:limit 10})))
      (let [result (memory/update-vault-note-iris! service
                                                   (.getAbsolutePath candidate)
                                                   {:status "approved"
                                                    :scope "global"})
            content (slurp candidate)
            recalled (memory/search-vault service "promotion-marker" {:limit 10})]
        (is (:updated result))
        (is (str/includes? content "scope: \"global\""))
        (is (str/includes? content "status: \"approved\""))
        (is (= ["mem_promote"] (mapv :note-id recalled))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest vault-note-move-keeps-index-in-sync-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        note (io/file root "inbox/move.md")
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}})]
    (try
      (.mkdirs (.getParentFile note))
      (spit note
            (str "---\n"
                 "id: mem_move\n"
                 "type: Reference\n"
                 "title: Move marker\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "---\n\n"
                 "move-marker detail\n"))
      (memory/reindex-vault! service)
      (let [result (memory/move-vault-note! service (.getAbsolutePath note) "archive")
            target (io/file root "archive/move.md")
            recalled (memory/search-vault service "move-marker" {:limit 10})]
        (is (:moved result))
        (is (false? (.exists note)))
        (is (.exists target))
        (is (= (.getCanonicalPath target) (:path result)))
        (is (= [(.getCanonicalPath target)] (mapv :path recalled))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unsupported vault note target folder"
                            (memory/move-vault-note! service (.getAbsolutePath (io/file root "archive/move.md")) "../bad")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest scratchpad-replace-and-vault-index-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}})]
    (try
      (let [empty-pad (memory/read-scratchpad service {:scope {:type :session :id "s1"}})
            _ (memory/replace-scratchpad! service
                                          {:scope {:type :session :id "s1"}
	                                           :old-text ""
	                                           :new-text "scratch-marker one\nscratch-marker two\n"
	                                           :expected-revision (:revision empty-pad)})
	            pad (memory/read-scratchpad service {:scope {:type :session :id "s1"}})
	            recall-before-reindex (recall/recall service "unrelated" {:limit 10 :session-id "s1"})
             _ (memory/reindex-vault! service)
             vault-results (memory/search-vault service "scratch-marker one" {:limit 10 :session-id "s1"})
             recall-after-reindex (recall/recall service "scratch-marker one" {:limit 10 :session-id "s1"})
             _ (memory/replace-scratchpad! service
                                           {:scope {:type :session :id "s1"}
                                            :old-text "scratch-marker one\n"
                                            :new-text ""
                                            :expected-revision (:revision pad)})
	            pad-after (memory/read-scratchpad service {:scope {:type :session :id "s1"}})
             inbox (io/file root "inbox")]
	        (is (= "session" (get-in pad [:scope :type])))
        (is (empty? (:results recall-before-reindex)))
        (is (= ["Scratchpad"] (mapv :type vault-results)))
        (is (= ["session"] (mapv #(get % :iris-scope) vault-results)))
        (is (= ["approved"] (mapv #(get % :iris-status) vault-results)))
        (is (= [:vault_chunk] (mapv :surface (:results recall-after-reindex))))
	        (is (str/includes? (:content pad-after) "scratch-marker two"))
	        (is (not (str/includes? (:content pad-after) "scratch-marker one")))
        (is (not (.exists inbox))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest vault-reindex-recalls-approved-notes-only-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        approved (io/file root "preferences/concise.md")
        candidate (io/file root "inbox/draft.md")
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]}})]
    (try
      (.mkdirs (.getParentFile approved))
      (.mkdirs (.getParentFile candidate))
      (spit approved
            (str "---\n"
                 "id: mem_concise\n"
                 "type: Preference\n"
                 "title: Concise answers\n"
                 "description: User prefers concise answers.\n"
                 "tags: [memory, preference]\n"
                 "timestamp: 2026-06-13T12:00:00Z\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "  confidence: 0.9\n"
                 "  origins:\n"
                 "    - type: manual\n"
                 "---\n\n"
                 "# Concise answers\n\n"
                 "User prefers terse Russian answers.\n\n"
                 "## Evidence\n\n"
                 "> отвечай кратко\n"))
      (spit candidate
            (str "---\n"
                 "id: mem_draft\n"
                 "type: Preference\n"
                 "title: Draft secret\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: candidate\n"
                 "---\n\n"
                 "Candidate-only redwood detail.\n"))
      (let [report (memory/reindex-vault! service)
            recalled (recall/recall service "terse Russian redwood" {:limit 10})
            vault-results (filter #(= :vault_chunk (:surface %)) (:results recalled))]
        (is (= 2 (:indexed-files report)))
        (is (= 2 (:note-count report)))
        (is (= 3 (:chunk-count report)))
        (is (= 1 (count vault-results)))
        (is (= "mem_concise" (get-in (first vault-results) [:source :note-id])))
        (is (str/includes? (:text (first vault-results)) "terse Russian"))
        (is (not (str/includes? (pr-str vault-results) "redwood"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest vault-reindex-audit-indexes-body-with-frontmatter-errors-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        note (io/file root "preferences/broken.md")
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]}})]
    (try
      (.mkdirs (.getParentFile note))
      (spit note
            (str "---\n"
                 "id: mem_broken\n"
                 "type Preference\n"
                 "title: Broken frontmatter marker\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "---\n\n"
                 "# Broken frontmatter marker\n\n"
                 "body-only-discovery-marker survives bad metadata.\n"))
      (let [report (memory/reindex-vault! service)
            results (memory/search-vault service "body-only-discovery-marker" {:limit 10})]
        (is (= 1 (:indexed-files report)))
        (is (= 1 (:note-count report)))
        (is (seq (:parse-errors report)))
        (is (= [:missing-type] (mapv :type (:okf-issues report))))
        (is (= ["mem_broken"] (mapv :note-id results)))
        (is (str/includes? (:text (first results)) "body-only-discovery-marker")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest vault-reindex-audit-reports-duplicates-links-orphans-and-embeddings-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        old-note (io/file root "preferences/old.md")
        dup-a (io/file root "preferences/dup-a.md")
        dup-b (io/file root "runbooks/dup-b.md")
        index-note (io/file root "index.md")
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}
                                     :embeddings {:enabled? true}})]
    (try
      (.mkdirs (.getParentFile old-note))
      (spit old-note
            (str "---\n"
                 "id: mem_old\n"
                 "type: Preference\n"
                 "title: Old marker\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "---\n\n"
                 "vanishedlegacytoken\n"))
      (memory/reindex-vault! service)
      (io/delete-file old-note true)
      (.mkdirs (.getParentFile dup-a))
      (.mkdirs (.getParentFile dup-b))
      (spit dup-a
            (str "---\n"
                 "id: mem_dup\n"
                 "type: Preference\n"
                 "title: Duplicate A\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "  origins:\n"
                 "  - type: vault_chunk\n"
                 "    vault_path: " (.getAbsolutePath (io/file root "missing-origin.md")) "\n"
                 "---\n\n"
                 "duplicate-marker A links to [[missing-note]].\n"))
      (spit dup-b
            (str "---\n"
                 "id: mem_dup\n"
                 "type: Runbook\n"
                 "title: Duplicate B\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "---\n\n"
                 "duplicate-marker B links to [missing](missing.md).\n"))
      (spit index-note "# Vault Index\n\nReserved file may omit OKF type.\n")
      (let [report (memory/reindex-vault! service)
            duplicate (first (:duplicate-ids report))]
        (is (= 3 (:indexed-files report)))
        (is (= "mem_dup" (:id duplicate)))
        (is (= 2 (count (:paths duplicate))))
        (is (= #{:broken-link} (set (map :type (:broken-links report)))))
        (is (= [:broken-origin] (mapv :type (:broken-origins report))))
        (is (= [(.getCanonicalPath old-note)] (mapv :path (:orphan-notes report))))
        (is (= [(.getCanonicalPath old-note)] (mapv :path (:orphan-chunks report))))
        (is (true? (get-in report [:embedding-audit :enabled])))
        (is (seq (:missing-embeddings report)))
        (is (empty? (:okf-issues report)))
        (is (empty? (memory/search-vault service "vanishedlegacytoken" {:limit 10}))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))
