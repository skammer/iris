(ns agent.memory.vault-test
  (:require
   [agent.memory.core.test-support :refer [temp-db-path temp-dir test-service]]
   [agent.memory.core :as memory]
   [agent.memory.recall :as recall]
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

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
        (is (= 2 (:chunk-count report)))
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
        (is (= 2 (:indexed-files report)))
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
