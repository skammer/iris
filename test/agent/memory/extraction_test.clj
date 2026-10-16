(ns agent.memory.extraction-test
  (:require
   [agent.memory.core.test-support :refer [->CapturingNoteProvider ->NoteProvider temp-db-path temp-dir test-service write-approved-note!]]
   [agent.memory.core :as memory]
   [agent.memory.recall :as recall]
   [agent.persistence.sqlite :as sqlite]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest memory-extraction-writes-candidate-vault-notes-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        responses (atom [(json/generate-string
                          {:notes [{:operation "create"
                                    :type "Preference"
                                    :title "Concise answers"
                                    :description "User prefers concise answers."
                                    :body "User prefers concise answers."
                                    :tags ["preference"]
                                    :scope "global"
                                    :confidence 0.9
                                    :evidence (apply str (repeat 10000 "e"))}]})])
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
                                                   :source-message-ids (mapv #(str "m" %) (range 1 81))})
            note-path (:path (first saved))
            content (slurp note-path)
            recall-results (:results (recall/recall service "concise answers" {:limit 10}))]
        (is (= 1 (count saved)))
        (is (str/includes? note-path "/inbox/"))
        (is (str/includes? content "status: \"candidate\""))
        (is (str/includes? content "scope: \"global\""))
        (is (str/includes? content "message_id_start: \"m1\""))
        (is (str/includes? content "message_id_end: \"m80\""))
        (is (str/includes? content "message_count: 80"))
        (is (= 1 (count (re-seq #"(?m)^  - type:" content))))
        (is (str/includes? content "[evidence truncated 9200 chars]"))
        (is (< (count content) 2200))
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

(deftest extraction-proposes-update-instead-of-duplicate-note-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        _ (write-approved-note! root)
        store (sqlite/create-store {:path db-path})
        service (test-service store {:notes {:extractor {:enabled true}}
                                     :vault {:paths [(.getAbsolutePath root)]
                                             :writable? true}})
        requests (atom [])]
    (try
      (memory/reindex-vault! service)
      (let [revision (:revision (sqlite/get-vault-note-by-id store "mem_answer_style"))
            responses (atom [(json/generate-string
                              {:notes [{:operation "update"
                                        :target_id "mem_answer_style"
                                        :expected_revision revision
                                        :type "Preference"
                                        :title "Answer style"
                                        :description "User prefers concise answers."
                                        :body "User prefers concise answers."
                                        :tags ["preference"]
                                        :scope "global"
                                        :confidence 0.99}]})])
            provider (->CapturingNoteProvider responses requests)
            saved (memory/extract-and-save-notes!
                   service provider
                   {:user-message "Correction: be concise."
                    :assistant-message "Understood."}
                   {:session-id "s-update"
                    :source-request-id "req-update"})]
        (is (= 1 (count saved)))
        (is (= "pending" (:status (first saved))))
        (is (= 1 (sqlite/count-vault-notes store)))
        (is (= 1 (count (sqlite/list-memory-note-updates store {:status "pending"}))))
        (is (str/includes? (get-in (first @requests) [:messages 1 :content])
                           "mem_answer_style")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

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
