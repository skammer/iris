(ns agent.memory.notes-test
  (:require
   [agent.memory.core.test-support :refer [temp-db-path temp-dir test-service]]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest vault-cleanup-backs-up-compacts-and-indexes-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        approved (io/file root "inbox" "approved.md")
        rejected (io/file root "inbox" "rejected.md")
        store (sqlite/create-store {:path db-path})
        backup-root (io/file (.getParentFile root) "memory-backups")
        service (test-service store {:vault {:paths [(.getAbsolutePath root)] :writable? true}})]
    (try
      (.mkdirs (.getParentFile approved))
      (spit approved "---\nid: mem_clean\ntype: Preference\ntitle: Clean me\niris:\n  scope: global\n  status: approved\n---\n\n# Clean me\n\nDurable fact.\n\n## Evidence\n\nlegacy transcript dump\n")
      (spit rejected "---\nid: mem_rejected\ntype: Reference\ntitle: Reject me\niris:\n  scope: global\n  status: rejected\n---\n\nRejected.\n")
      (memory/reindex-vault! service)
      (let [result (memory/cleanup-vault! service {:apply? true})
            moved (io/file root "preferences" "approved.md")
            first-content (slurp moved)
            second-result (memory/cleanup-vault! service {:apply? true})
            second-content (slurp moved)]
        (is (true? (:applied result)))
        (is (true? (:applied second-result)))
        (is (= 1 (count (:backup-paths result))))
        (is (.isDirectory (io/file (first (:backup-paths result)))))
        (is (.isFile moved))
        (is (not (.exists rejected)))
        (is (not (str/includes? second-content "legacy transcript dump")))
        (is (= 1 (count (re-seq #"(?m)^# Clean me$" second-content))))
        (is (= first-content second-content))
        (is (.isFile (io/file root "index.md")))
        (is (true? (get-in second-result [:index :ok?])))
        (is (= 1 (sqlite/count-vault-notes store))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (when (.exists backup-root) (io/delete-file backup-root true))
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

(deftest vault-note-promotion-moves-to-durable-folder-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        candidate (io/file root "inbox/preference.md")
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}})]
    (try
      (.mkdirs (.getParentFile candidate))
      (spit candidate
            (str "---\n"
                 "id: mem_promote_move\n"
                 "type: Preference\n"
                 "title: Promotion move marker\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: candidate\n"
                 "---\n\n"
                 "promotion-move-marker detail\n"))
      (memory/reindex-vault! service)
      (let [result (memory/promote-vault-note! service
                                               (.getAbsolutePath candidate)
                                               {:scope "global"})
            target (io/file root "preferences/preference.md")
            note (first (sqlite/list-vault-notes store {:limit 10}))
            event (first (sqlite/list-events store
                                             {:event-type :memory.vault.note_promoted
                                              :limit 1}))]
        (is (false? (.exists candidate)))
        (is (.isFile target))
        (is (= "approved" (:iris-status note)))
        (is (= (.getCanonicalPath target) (:path note) (:path result)))
        (is (= "preferences" (:folder result)))
        (is (= (.getCanonicalPath target) (:entity-id event))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest session-note-promotion-overrides-type-folder-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        candidate (io/file root "inbox/session-decision.md")
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}})]
    (try
      (.mkdirs (.getParentFile candidate))
      (spit candidate
            (str "---\n"
                 "id: mem_session_promote\n"
                 "type: Decision\n"
                 "title: Session decision\n"
                 "iris:\n"
                 "  scope: session\n"
                 "  status: candidate\n"
                 "---\n\nSession-only decision.\n"))
      (memory/reindex-vault! service)
      (let [result (memory/promote-vault-note! service
                                               (.getAbsolutePath candidate)
                                               {})
            note (first (sqlite/list-vault-notes store {:limit 10}))]
        (is (= "sessions" (:folder result)))
        (is (.isFile (io/file root "sessions/session-decision.md")))
        (is (= "session" (:iris-scope note))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest vault-note-promotion-rolls-back-on-move-conflict-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        candidate (io/file root "inbox/conflict.md")
        target (io/file root "preferences/conflict.md")
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}})]
    (try
      (doseq [file [candidate target]] (.mkdirs (.getParentFile file)))
      (spit candidate
            (str "---\n"
                 "id: mem_promotion_conflict\n"
                 "type: Preference\n"
                 "title: Promotion conflict\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: candidate\n"
                 "---\n\nCandidate content.\n"))
      (spit target "Existing durable note.\n")
      (memory/reindex-vault! service)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"target already exists"
                            (memory/promote-vault-note! service
                                                        (.getAbsolutePath candidate)
                                                        {})))
      (is (str/includes? (slurp candidate) "status: candidate"))
      (is (= "Existing durable note.\n" (slurp target)))
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
