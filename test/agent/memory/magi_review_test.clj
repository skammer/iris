(ns agent.memory.magi-review-test
  (:require
   [agent.config :as config]
   [agent.llm.core :as llm]
   [agent.magi.core :as magi]
   [agent.memory.core :as memory]
   [agent.memory.magi-review :as review]
   [agent.persistence.sqlite :as sqlite]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defrecord StaticProvider [response requests]
  llm/ILLMProviderInvoke
  (invoke [_ request]
    (swap! requests conj request)
    {:role "assistant"
     :content (json/generate-string response)
     :tool-calls []
     :usage nil
     :raw nil})
  (generate [this messages opts]
    (llm/invoke this (assoc opts :messages messages))))

(defn- temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-magi-memory-" ".db")))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-magi-memory-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-candidate! [root]
  (let [file (io/file root "inbox/candidate.md")]
    (.mkdirs (.getParentFile file))
    (spit file
          (str "---\n"
               "id: mem_magi_review\n"
               "type: Preference\n"
               "title: Concise answers\n"
               "description: User prefers concise answers.\n"
               "iris:\n"
               "  scope: global\n"
               "  status: candidate\n"
               "  confidence: 0.95\n"
               "  origins:\n"
               "  - type: message\n"
               "    message_id: 1\n"
               "---\n\n"
               "# Concise answers\n\n"
               "User prefers concise answers.\n\n"
               "## Evidence\n\n> Be concise.\n"))
    file))

(defn- write-approved! [root]
  (let [file (io/file root "preferences/approved.md")]
    (.mkdirs (.getParentFile file))
    (spit file
          (str "---\n"
               "id: mem_magi_update\n"
               "type: Preference\n"
               "title: Answer detail\n"
               "description: Detailed answers.\n"
               "iris:\n"
               "  scope: global\n"
               "  status: approved\n"
               "---\n\n"
               "# Answer detail\n\nDetailed answers.\n"))
    file))

(defn- magi-service [mode decision requests]
  (let [agent-response (if (= decision :no) "no" "yes")]
    (magi/create-service
     (assoc config/default-config
            :magi {:enabled? true
                   :memory-promotion {:mode mode
                                      :scopes #{:all}
                                      :poll-interval-seconds 60
                                      :failure-cooldown-minutes 15
                                      :max-candidates 10}})
     {:providers {:filter (->StaticProvider {:kind "yes-no"
                                             :domain "memory-promotion"
                                             :risk "medium"
                                             :question "Promote note?"
                                             :expected_response "permit"}
                                           requests)
                  :melchior (->StaticProvider {:response agent-response} requests)
                  :balthasar (->StaticProvider {:response "yes"} requests)
                  :casper (->StaticProvider {:response "yes"} requests)
                  :judge (->StaticProvider {:decision (name decision)
                                            :reason (if (= decision :yes)
                                                      "supported durable memory"
                                                      "insufficient evidence")}
                                           requests)}})))

(defn- test-system [store root mode decision requests]
  (let [memory-service (memory/create-memory-service
                        {:vault {:paths [(.getAbsolutePath root)]
                                 :writable? true}}
                        store)]
    (memory/reindex-vault! memory-service)
    {:store store
     :memory-service memory-service
     :magi-service (magi-service mode decision requests)
     :event-sink #(sqlite/log-event! store %)}))

(deftest manual-review-unanimous-yes-promotes-and-audits-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        file (write-candidate! root)
        requests (atom [])
        system (test-system store root :manual :yes requests)]
    (try
      (let [result (review/review-note! system (.getCanonicalPath file)
                                        {:apply? true :source :manual})
            note (first (sqlite/list-vault-notes store {:limit 10}))
            event (review/latest-review system note)]
        (is (:applied result))
        (is (= "approved" (:iris-status note)))
        (is (= "yes" (get-in event [:payload :decision])))
        (is (true? (get-in event [:payload :applied])))
        (is (some #(str/includes? (get-in % [:messages 1 :content])
                                  "User prefers concise answers")
                  @requests)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest advice-never-promotes-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        file (write-candidate! root)
        requests (atom [])
        system (test-system store root :manual :yes requests)]
    (try
      (let [result (review/review-note! system (.getCanonicalPath file)
                                        {:apply? false :source :advice})]
        (is (false? (:applied result)))
        (is (= "candidate"
               (:iris-status (first (sqlite/list-vault-notes store {:limit 10}))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest magi-approved-update-applies-diff-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        file (write-approved! root)
        store (sqlite/create-store {:path db-path})
        requests (atom [])
        system (test-system store root :manual :yes requests)]
    (try
      (let [note (sqlite/get-vault-note-by-id store "mem_magi_update")
            proposal (memory/propose-vault-note-update!
                      (:memory-service system)
                      "mem_magi_update"
                      (:revision note)
                      {:description "Concise answers."
                       :body "Concise answers."}
                      {:source :test
                       :evidence {:user "Be concise."}})
            result (review/review-update! system (:id proposal)
                                          {:apply? true :source :manual})]
        (is (:applied result))
        (is (= "applied"
               (:status (memory/get-memory-note-update
                         (:memory-service system) (:id proposal)))))
        (is (str/includes? (slurp file) "Concise answers."))
        (is (some #(str/includes? (get-in % [:messages 1 :content]) "## body")
                  @requests)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest magi-rejected-update-leaves-approved-note-unchanged-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        file (write-approved! root)
        store (sqlite/create-store {:path db-path})
        requests (atom [])
        system (test-system store root :manual :no requests)]
    (try
      (let [note (sqlite/get-vault-note-by-id store "mem_magi_update")
            proposal (memory/propose-vault-note-update!
                      (:memory-service system)
                      "mem_magi_update"
                      (:revision note)
                      {:body "Unsupported replacement."}
                      {:source :test})
            result (review/review-update! system (:id proposal)
                                          {:apply? true :source :manual})]
        (is (false? (:applied result)))
        (is (= "rejected"
               (:status (memory/get-memory-note-update
                         (:memory-service system) (:id proposal)))))
        (is (str/includes? (slurp file) "Detailed answers.")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest auto-negative-verdict-keeps-candidate-and-dedupes-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        file (write-candidate! root)
        requests (atom [])
        system (test-system store root :auto :no requests)]
    (try
      (review/review-note! system (.getCanonicalPath file)
                           {:apply? false :source :advice})
      (is (= 1 (:processed (review/run-once! system))))
      (is (= "candidate"
             (:iris-status (first (sqlite/list-vault-notes store {:limit 10})))))
      (is (= 0 (:processed (review/run-once! system))))
      (spit file (str/replace (slurp file)
                              "description: User prefers concise answers."
                              "description: User strongly prefers concise answers."))
      (memory/reindex-vault! (:memory-service system))
      (is (= 1 (:processed (review/run-once! system))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest auto-worker-applies-pending-update-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        file (write-approved! root)
        store (sqlite/create-store {:path db-path})
        requests (atom [])
        system (test-system store root :auto :yes requests)]
    (try
      (let [note (sqlite/get-vault-note-by-id store "mem_magi_update")
            proposal (memory/propose-vault-note-update!
                      (:memory-service system)
                      "mem_magi_update"
                      (:revision note)
                      {:body "Concise answers."}
                      {:source :test})
            result (review/run-once! system)]
        (is (= 1 (:processed result)))
        (is (= 1 (:approved result)))
        (is (= "applied"
               (:status (memory/get-memory-note-update
                         (:memory-service system) (:id proposal)))))
        (is (str/includes? (slurp file) "Concise answers.")))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest approved-inbox-drift-event-is-emitted-on-change-only-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        file (write-candidate! root)
        requests (atom [])
        system (test-system store root :auto :yes requests)
        service (review/create-service (atom system))]
    (try
      (memory/update-vault-note-iris! (:memory-service system)
                                      (.getCanonicalPath file)
                                      {:status "approved"})
      (review/report-approved-inbox-drift! service system)
      (review/report-approved-inbox-drift! service system)
      (let [events (sqlite/list-events store
                                       {:event-type :memory.vault.approved_inbox_detected
                                        :limit 10})]
        (is (= 1 (count events)))
        (is (= 1 (get-in events [0 :payload :count]))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))
