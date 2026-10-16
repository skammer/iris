(ns agent.memory.quality-test
  (:require
   [agent.memory.core.test-support :refer [temp-db-path temp-dir test-service]]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(deftest memory-quality-report-flags-review-queue-risks-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        candidate (io/file root "inbox/candidate.md")
        conflict-a (io/file root "preferences/conflict-a.md")
        conflict-b (io/file root "preferences/conflict-b.md")
        stale (io/file root "runbooks/stale.md")
        deleted (io/file root "references/deleted.md")
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]}
                                     :quality {:low-confidence-threshold 0.7
                                               :stale-days 30}})]
    (try
      (doseq [file [candidate conflict-a conflict-b stale deleted]]
        (.mkdirs (.getParentFile file)))
      (spit candidate
            (str "---\n"
                 "id: mem_candidate\n"
                 "type: Preference\n"
                 "title: Candidate marker\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: candidate\n"
                 "  confidence: 0.4\n"
                 "  origins:\n"
                 "  - type: vault_chunk\n"
                 "    vault_path: " (.getAbsolutePath (io/file root "missing-origin.md")) "\n"
                 "---\n\n"
                 "candidate body\n"))
      (spit conflict-a
            (str "---\n"
                 "id: mem_conflict_a\n"
                 "type: Preference\n"
                 "title: Conflict Marker\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "  confidence: 0.9\n"
                 "---\n\n"
                 "first conflict body\n"))
      (spit conflict-b
            (str "---\n"
                 "id: mem_conflict_b\n"
                 "type: Preference\n"
                 "title: Conflict Marker\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "  confidence: 0.95\n"
                 "---\n\n"
                 "second conflict body\n"))
      (spit stale
            (str "---\n"
                 "id: mem_stale\n"
                 "type: Runbook\n"
                 "title: Stale marker\n"
                 "timestamp: 2020-01-01T00:00:00Z\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "  confidence: 0.8\n"
                 "---\n\n"
                 "stale body\n"))
      (spit deleted
            (str "---\n"
                 "id: mem_deleted\n"
                 "type: Reference\n"
                 "title: Deleted marker\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "---\n\n"
                 "deleted body\n"))
      (memory/reindex-vault! service)
      (io/delete-file deleted true)
      (let [quality (:quality (memory/health-check service))]
        (is (= 1 (:candidate-backlog quality)))
        (is (= ["mem_candidate"] (mapv :id (:low-confidence-notes quality))))
        (is (= ["mem_candidate"] (mapv :id (:broken-origin-notes quality))))
        (is (= #{"mem_conflict_a" "mem_conflict_b"}
               (set (map :id (:notes (first (:conflicts quality)))))))
        (is (= ["mem_stale"] (mapv :id (:stale-notes quality))))
        (is (= ["mem_deleted"] (mapv :id (:orphan-notes quality))))
        (is (seq (:orphan-chunks quality)))
        (is (= 0 (get-in quality [:embedding-coverage :vault-chunks :embedded])))
        (is (pos? (:review-queue-count quality))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest memory-quality-report-detects-approved-inbox-drift-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        note (io/file root "inbox/approved.md")
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]}})]
    (try
      (.mkdirs (.getParentFile note))
      (spit note
            (str "---\n"
                 "id: mem_approved_inbox\n"
                 "type: Reference\n"
                 "title: Approved inbox drift\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "---\n\nDrift.\n"))
      (memory/reindex-vault! service)
      (let [quality (memory/quality-report service)]
        (is (= 1 (count (:approved-inbox-notes quality))))
        (is (= "mem_approved_inbox" (get-in quality [:approved-inbox-notes 0 :id])))
        (is (pos? (:review-queue-count quality))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))
