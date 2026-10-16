(ns agent.memory.proposals-test
  (:require
   [agent.memory.core.test-support :refer [temp-db-path temp-dir test-service write-approved-note!]]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest approved-note-update-stays-in-recall-until-applied-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        file (write-approved-note! root)
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}})]
    (try
      (memory/reindex-vault! service)
      (let [revision (:revision (sqlite/get-vault-note-by-id store "mem_answer_style"))
            proposal (memory/propose-vault-note-update!
                      service
                      "mem_answer_style"
                      revision
                      {:description "User prefers concise answers."
                       :body "User prefers concise answers."}
                      {:source :test
                       :evidence {:user "Be concise."}})
            before-recall (memory/search-vault service "detailed answers" {:limit 10})]
        (is (= "pending" (:status proposal)))
        (is (str/includes? (:diff proposal) "## body"))
        (is (str/includes? (slurp file) "detailed answers"))
        (is (= #{"mem_answer_style"} (set (map :note-id before-recall))))
        (let [applied (memory/apply-memory-note-update! service (:id proposal) "supported")
              note (sqlite/get-vault-note-by-id store "mem_answer_style")]
          (is (= "applied" (:status applied)))
          (is (= "approved" (:iris-status note)))
          (is (not= revision (:revision note)))
          (is (str/includes? (slurp file) "concise answers"))
          (is (= #{"mem_answer_style"}
                 (set (map :note-id
                           (memory/search-vault service "concise answers" {:limit 10})))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest stale-approved-note-update-is-superseded-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        file (write-approved-note! root)
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}})]
    (try
      (memory/reindex-vault! service)
      (let [revision (:revision (sqlite/get-vault-note-by-id store "mem_answer_style"))
            proposal (memory/propose-vault-note-update!
                      service "mem_answer_style" revision
                      {:body "User prefers concise answers."}
                      {:source :test})]
        (spit file (str (slurp file) "\nOperator edit.\n"))
        (let [result (memory/apply-memory-note-update! service (:id proposal) "supported")]
          (is (= "superseded" (:status result)))
          (is (str/includes? (slurp file) "Operator edit."))
          (is (not (str/includes? (slurp file) "concise answers")))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest unchanged-approved-note-update-is-noop-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        _ (write-approved-note! root)
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}})]
    (try
      (memory/reindex-vault! service)
      (let [revision (:revision (sqlite/get-vault-note-by-id store "mem_answer_style"))
            result (memory/propose-vault-note-update!
                    service "mem_answer_style" revision
                    {:description "User prefers detailed answers."
                     :body "User prefers detailed answers."}
                    {:source :test})]
        (is (:noop result))
        (is (empty? (sqlite/list-memory-note-updates store))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest reviewed-note-operation-proposals-do-not-mutate-before-apply-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        service (test-service store {:vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}})
        write-note! (fn [id filename body]
                      (let [file (io/file root "references" filename)]
                        (.mkdirs (.getParentFile file))
                        (spit file
                              (str "---\nid: " id "\ntype: Reference\ntitle: " id
                                   "\niris:\n  scope: global\n  status: approved\n---\n\n# " id
                                   "\n\n" body "\n"))
                        file))
        move-file (write-note! "mem_move_proposal" "move.md" "move proposal marker")
        delete-file (write-note! "mem_delete_proposal" "delete.md" "delete proposal marker")
        merge-target (write-note! "mem_merge_target" "target.md" "old target")
        merge-source (write-note! "mem_merge_source" "source.md" "source detail")]
    (try
      (memory/reindex-vault! service)
      (let [revision (:revision (sqlite/get-vault-note-by-id store "mem_move_proposal"))
            proposal (memory/propose-vault-note-move! service "mem_move_proposal" revision "archive"
                                                      {:source :test})
            moved-file (io/file root "archive/move.md")]
        (is (= "note-move" (:operation proposal)))
        (is (.exists move-file))
        (is (false? (.exists moved-file)))
        (is (= "applied" (:status (memory/apply-memory-note-proposal!
                                    service (:id proposal) "approved"))))
        (is (false? (.exists move-file)))
        (is (.exists moved-file)))
      (let [revision (:revision (sqlite/get-vault-note-by-id store "mem_delete_proposal"))
            proposal (memory/propose-vault-note-delete! service "mem_delete_proposal" revision
                                                        {:source :test})]
        (is (.exists delete-file))
        (memory/apply-memory-note-proposal! service (:id proposal) "approved")
        (is (false? (.exists delete-file)))
        (is (nil? (sqlite/get-vault-note-by-id store "mem_delete_proposal"))))
      (let [target-revision (:revision (sqlite/get-vault-note-by-id store "mem_merge_target"))
            source-revision (:revision (sqlite/get-vault-note-by-id store "mem_merge_source"))
            proposal (memory/propose-vault-note-merge!
                      service "mem_merge_target" "mem_merge_source"
                      target-revision source-revision
                      {:body "merged target with source detail"}
                      {:source :test})]
        (is (str/includes? (slurp merge-target) "old target"))
        (is (.exists merge-source))
        (memory/apply-memory-note-proposal! service (:id proposal) "approved")
        (is (str/includes? (slurp merge-target) "merged target with source detail"))
        (is (false? (.exists merge-source)))
        (is (nil? (sqlite/get-vault-note-by-id store "mem_merge_source"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))
