(ns agent.memory.core-test
  (:require
   [agent.memory.core.test-support :refer [->EmbeddingProvider temp-db-path temp-dir test-service]]
   [agent.memory.core :as memory]
   [agent.memory.recall :as recall]
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

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

(deftest project-vault-recall-crosses-project-chats-without-leaking-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        alpha (io/file root "projects/alpha.md")
        beta (io/file root "projects/beta.md")
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]}})]
    (try
      (.mkdirs (.getParentFile alpha))
      (doseq [[file id project marker]
              [[alpha "mem_alpha" "alpha" "shared-project-marker alpha detail"]
               [beta "mem_beta" "beta" "shared-project-marker beta detail"]]]
        (spit file
              (str "---\n"
                   "id: " id "\n"
                   "type: ProjectNote\n"
                   "title: " project " project\n"
                   "iris:\n"
                   "  scope: project\n"
                   "  status: approved\n"
                   "  origins:\n"
                   "  - type: message\n"
                   "    session_id: creator-session\n"
                   "    project_id: " project "\n"
                   "---\n\n"
                   marker "\n")))
      (memory/reindex-vault! service)
      (is (= ["mem_alpha"]
             (mapv :note-id
                   (memory/search-vault service "shared-project-marker"
                                        {:session-id "other-alpha-chat"
                                         :project-id "alpha"
                                         :limit 10}))))
      (is (= ["mem_beta"]
             (mapv :note-id
                   (memory/search-vault service "shared-project-marker"
                                        {:session-id "creator-session"
                                         :project-id "beta"
                                         :limit 10})))
          "creator session id must not bypass current project scope")
      (is (empty? (memory/search-vault service "shared-project-marker"
                                       {:session-id "creator-session" :limit 10})))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest similar-vault-notes-shortlists-within-scope-owner-only-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]}})]
    (try
      (doseq [[id project suffix]
              [["mem_a1" "alpha" "Use deploy checklist before release and verify health endpoint."]
               ["mem_a2" "alpha" "Use deploy checklist before release, then verify health endpoint."]
               ["mem_b1" "beta" "Use deploy checklist before release and verify health endpoint."]]
              :let [file (io/file root "projects" (str id ".md"))]]
        (.mkdirs (.getParentFile file))
        (spit file
              (str "---\n"
                   "id: " id "\n"
                   "type: Runbook\n"
                   "title: Deployment checklist\n"
                   "description: Safe project deployment procedure.\n"
                   "iris:\n"
                   "  scope: project\n"
                   "  status: approved\n"
                   "  origins:\n"
                   "  - type: message\n"
                   "    project_id: " project "\n"
                   "---\n\n# Deployment checklist\n\n" suffix "\n")))
      (memory/reindex-vault! service)
      (let [pairs (memory/similar-vault-notes service {:threshold 0.4 :limit 10})]
        (is (= 1 (count pairs)))
        (is (= #{"mem_a1" "mem_a2"}
               #{(get-in pairs [0 :left :id]) (get-in pairs [0 :right :id])}))
        (is (= "alpha" (:scope-owner (first pairs)))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest vault-embeddings-add-semantic-search-with-scope-filters-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        global (io/file root "approved/bicycle.md")
        session-a (io/file root "sessions/a.md")
        session-b (io/file root "sessions/b.md")
        store (sqlite/create-store {:path db-path})
        requests (atom [])
        provider (->EmbeddingProvider requests)
        cfg {:vault {:paths [(.getAbsolutePath root)]}
             :embeddings {:enabled? true
                          :surfaces [:vault-notes :vault-chunks]
                          :batch-size 2
                          :candidate-limit 10}}
        service (test-service store
                              cfg
                              {:embedding-provider provider
                               :embedding-model "test-embed"})]
    (try
      (doseq [file [global session-a session-b]]
        (.mkdirs (.getParentFile file)))
      (spit global
            (str "---\n"
                 "id: mem_bicycle\n"
                 "type: Reference\n"
                 "title: Bicycle repair\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "---\n\n"
                 "Tire levers and chain lubricant help with bicycle repair.\n"))
      (spit session-a
            (str "---\n"
                 "id: mem_session_bicycle_a\n"
                 "type: ProjectNote\n"
                 "title: Session A transport\n"
                 "iris:\n"
                 "  scope: session\n"
                 "  status: approved\n"
                 "  origins:\n"
                 "  - type: message\n"
                 "    session_id: s1\n"
                 "---\n\n"
                 "Cycling transport details for session one.\n"))
      (spit session-b
            (str "---\n"
                 "id: mem_session_bicycle_b\n"
                 "type: ProjectNote\n"
                 "title: Session B transport\n"
                 "iris:\n"
                 "  scope: session\n"
                 "  status: approved\n"
                 "  origins:\n"
                 "  - type: message\n"
                 "    session_id: s2\n"
                 "---\n\n"
                 "Cycling transport details for session two.\n"))
      (let [report (memory/reindex-vault! service)
            semantic (memory/search-vault service "fix my bike" {:limit 10 :session-id "s1"})
            recalled (recall/recall service "fix my bike" {:limit 10 :session-id "s1"})
            semantic-ids (set (map :note-id semantic))
            offline-service (test-service store cfg)
            offline-fts (memory/search-vault offline-service "Bicycle repair" {:limit 10})]
        (is (= 3 (:vault-chunk-embedding-count report)))
        (is (= 3 (:memory-embedding-count report)))
        (is (empty? (:missing-embeddings report)))
        (is (empty? (:missing-note-embeddings report)))
        (is (empty? (:embedding-errors report)))
        (is (= 3 (count (sqlite/list-vault-chunk-embeddings store))))
        (is (= 3 (count (sqlite/list-memory-embeddings store {:surface "vault_note"}))))
        (is (= #{"mem_bicycle" "mem_session_bicycle_a"} semantic-ids))
        (is (not (contains? semantic-ids "mem_session_bicycle_b")))
        (is (some #(= :semantic-match (:reason %)) semantic))
        (is (every? #(contains? % :why) (:results recalled)))
        (is (integer? (:latency-ms recalled)))
        (is (= ["mem_bicycle"] (mapv :note-id offline-fts)))
        (is (every? #(= "test-embed" (get-in % [:opts :model])) @requests)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))
