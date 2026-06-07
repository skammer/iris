(ns agent.memory.core-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defrecord FactProvider [responses]
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

(defrecord CapturingFactProvider [responses requests]
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
   (merge {:prompt {:paths []}
           :search {:default-limit 10}
           :facts {:extractor {:enabled false}
                   :default-scope :session}
           :vault {:paths []}}
          cfg)
   store))

(deftest memory-service-exposes-surfaces-and-search-test
  (let [db-path (temp-db-path)
        prompt-dir (temp-dir)
        prompt-file (io/file prompt-dir "MEMORY.md")
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "mem")]
    (try
      (spit prompt-file "remember this fact")
      (sqlite/append-message! store (:id session) "user" "hello memory")
      (sqlite/log-event! store {:event-type :session.created
                                :entity-type :session
                                :entity-id (:id session)
                                :payload {:title "memory hello"}})
      (let [service (test-service store {:prompt {:paths [(.getAbsolutePath prompt-file)]}})
            prompt (memory/read-prompt-memory service)
            results (memory/search-memory service "hello")
            surfaces (memory/list-surfaces service)]
        (is (= [:prompt :search :facts :vault] (mapv :name surfaces)))
        (is (str/includes? (:combined prompt) "remember this fact"))
        (is (= 1 (count (:messages results))))
        (is (= 1 (count (:events results)))))
      (finally
        (io/delete-file prompt-file true)
        (io/delete-file prompt-dir true)
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

(deftest memory-facts-dedup-and-scope-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        service (test-service store {})]
    (try
      (let [first-save (memory/save-memory-fact! service
                                                 {:subject "Alice"
                                                  :predicate "Likes"
                                                  :object "Clojure"}
                                                 {:scope {:type :session :id "s1"}
                                                  :source-message-ids [1]})
            second-save (memory/save-memory-fact! service
                                                  {:subject " alice "
                                                   :predicate "likes"
                                                   :object "clojure"}
                                                  {:scope {:type :session :id "s1"}
                                                   :source-message-ids [2]})
            same-scope (memory/search-facts service "alice" {:scope {:type :session :id "s1"}})
            other-scope (memory/search-facts service "alice" {:scope {:type :session :id "s2"}})]
        (is (:created? first-save))
        (is (false? (:created? second-save)))
        (is (= 1 (count same-scope)))
        (is (= [1 2] (:source-message-ids (first same-scope))))
        (is (empty? other-scope)))
      (finally
        (io/delete-file db-path true)))))

(deftest memory-facts-validation-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        service (test-service store {})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"non-blank"
                            (memory/save-memory-fact! service
                                                      {:subject ""
                                                       :predicate "likes"
                                                       :object "Clojure"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"confidence"
                            (memory/save-memory-fact! service
                                                      {:subject "Alice"
                                                       :predicate "likes"
                                                       :object "Clojure"
                                                       :confidence 2.0})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"provide fact id"
                            (memory/remove-memory-fact! service {:subject "Alice"})))
      (finally
        (io/delete-file db-path true)))))

(deftest memory-reset-hard-deletes-facts-without-clearing-messages-or-events-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "reset")
        service (test-service store {})]
    (try
      (sqlite/append-message! store (:id session) "user" "keep message")
      (sqlite/log-event! store {:event-type :keep.event
                                :entity-type :session
                                :entity-id (:id session)
                                :payload {:keep true}})
      (memory/save-memory-fact! service
                                {:subject "Alice"
                                 :predicate "likes"
                                 :object "Clojure"}
                                {:scope {:type :global}})
      (memory/save-memory-fact! service
                                {:subject "Bob"
                                 :predicate "uses"
                                 :object "SQLite"}
                                {:scope {:type :global}})
      (let [message-count (count (sqlite/list-messages store (:id session)))
            before-event-count (count (sqlite/list-events store {}))
            reset (memory/reset-facts! service)
            after-events (sqlite/list-events store {})]
        (is (= 2 (:removed-count reset)))
        (is (= :hard-delete (:mode reset)))
        (is (zero? (sqlite/count-memory-facts store)))
        (is (empty? (memory/search-facts service "" {:all-scopes? true})))
        (is (= message-count (count (sqlite/list-messages store (:id session)))))
        (is (= (inc before-event-count) (count after-events)))
        (is (= "memory.facts.reset" (:event-type (first after-events)))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file db-path true)))))

(deftest memory-extraction-dedups-and-respects-scopes-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        responses (atom [(json/generate-string
                          {:facts [{:subject "user"
                                    :predicate "prefers"
                                    :object "concise answers"
                                    :scope "session"
                                    :confidence 0.9}]})
                         (json/generate-string
                          {:facts [{:subject " user "
                                    :predicate "prefers"
                                    :object "concise answers"
                                    :scope "session"
                                    :confidence 0.8}]})
                         (json/generate-string
                          {:facts [{:subject "team"
                                    :predicate "uses"
                                    :object "release smoke tests"
                                    :scope "global"
                                    :confidence 0.7}]})])
        provider (->FactProvider responses)
        service (test-service store {:facts {:extractor {:enabled true}
                                             :default-scope :session}})]
    (try
      (memory/extract-and-save-facts! service
                                      provider
                                      {:user-message "I prefer concise answers"
                                       :assistant-message "noted"}
                                      {:session-id "s1"
                                       :source-session-id "s1"
                                       :source-message-ids [1 2]})
      (memory/extract-and-save-facts! service
                                      provider
                                      {:user-message "remember concise"
                                       :assistant-message "still noted"}
                                      {:session-id "s1"
                                       :source-session-id "s1"
                                       :source-message-ids [3 4]})
      (memory/extract-and-save-facts! service
                                      provider
                                      {:user-message "team uses smoke tests"
                                       :assistant-message "noted"}
                                      {:session-id "s2"
                                       :source-session-id "s2"
                                       :source-message-ids [5 6]})
      (let [session-facts (memory/search-facts service
                                               "concise"
                                               {:scope {:type :session :id "s1"}
                                                :include-global? false})
            other-session (memory/search-facts service
                                               "concise"
                                               {:scope {:type :session :id "s2"}
                                                :include-global? false})
            global-facts (memory/search-facts service
                                              "release"
                                              {:scope {:type :global}})]
        (is (= 1 (count session-facts)))
        (is (= [1 2 3 4] (:source-message-ids (first session-facts))))
        (is (empty? other-session))
        (is (= 1 (count global-facts)))
        (is (= {:type "global" :id nil} (:scope (first global-facts)))))
      (finally
        (io/delete-file db-path true)))))

(deftest memory-extraction-defaults-to-json-schema-output-test
  (let [responses (atom [(json/generate-string {:facts []})])
        requests (atom [])
        provider (->CapturingFactProvider responses requests)]
    (memory/extract-facts provider
                          {:user-message "I prefer concise answers"
                           :assistant-message "noted"
                           :model "model"
                           :session-id "s1"
                           :extractor {:enabled true}})
    (let [request (first @requests)]
      (is (= "memory_facts" (get-in request [:structured-output :name])))
      (is (nil? (:response-format request))))))

(deftest memory-extraction-supports-json-object-output-test
  (let [responses (atom [(json/generate-string {:facts []})])
        requests (atom [])
        provider (->CapturingFactProvider responses requests)]
    (memory/extract-facts provider
                          {:user-message "I prefer concise answers"
                           :assistant-message "noted"
                           :model "model"
                           :session-id "s1"
                           :extractor {:enabled true
                                       :format :json-object}})
    (let [request (first @requests)]
      (is (= {:type "json_object"} (:response-format request)))
      (is (nil? (:structured-output request))))))

(deftest memory-facts-similarity-fallback-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        service (test-service store {:facts {:extractor {:enabled false}
                                             :dedup {:similarity-threshold 0.6}}})]
    (try
      (memory/save-memory-fact! service
                                {:subject "Alice"
                                 :predicate "likes"
                                 :object "Clojure language"}
                                {:scope {:type :global}
                                 :source-message-ids [1]})
      (let [saved (memory/save-memory-fact! service
                                            {:subject "Alice"
                                             :predicate "likes"
                                             :object "Clojure"}
                                            {:scope {:type :global}
                                             :source-message-ids [2]})
            facts (memory/search-facts service "Alice" {:scope {:type :global}})]
        (is (false? (:created? saved)))
        (is (:similar-duplicate? saved))
        (is (= 1 (count facts)))
        (is (= "Clojure language" (:object (first facts))))
        (is (= [1 2] (:source-message-ids (first facts)))))
      (finally
        (io/delete-file db-path true)))))

(deftest memory-search-thresholds-and-dedupes-ranked-values-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        service (test-service store {:search {:default-limit 10
                                              :min-score 0.3}})]
    (try
      (memory/save-memory-fact! service
                                {:subject "Alice"
                                 :predicate "likes"
                                 :object "Clojure"}
                                {:scope {:type :global}})
      (memory/save-memory-fact! service
                                {:subject "Bob"
                                 :predicate "likes"
                                 :object "Java"}
                                {:scope {:type :global}})
      (let [results (memory/search-memory service "Alice Clojure" {:limit 10})
            ranked (:ranked results)]
        (is (= 1 (count ranked)))
        (is (= :fact (:surface (first ranked))))
        (is (= "Alice likes Clojure"
               (str (get-in ranked [0 :item :subject])
                    " "
                    (get-in ranked [0 :item :predicate])
                    " "
                    (get-in ranked [0 :item :object]))))
        (is (not-any? #(= "Java" (:object %)) (:facts results))))
      (finally
        (io/delete-file db-path true)))))

(deftest cross-session-memory-recall-rank-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        session-a (sqlite/create-session! store "source")
        session-b (sqlite/create-session! store "recall")
        service (test-service store {})]
    (try
      (sqlite/append-message! store (:id session-a) "user" "I prefer concise answers.")
      (memory/save-memory-fact! service
                                {:subject "user"
                                 :predicate "prefers"
                                 :object "concise answers"
                                 :confidence 0.95}
                                {:scope {:type :global}
                                 :source-session-id (:id session-a)})
      (memory/save-memory-fact! service
                                {:subject "session-a"
                                 :predicate "private-project"
                                 :object "redwood"
                                 :confidence 0.9}
                                {:scope {:type :session :id (:id session-a)}
                                 :source-session-id (:id session-a)})
      (sqlite/close-store! store)
      (let [restarted-store (sqlite/create-store {:path db-path})
            restarted-service (test-service restarted-store {})
            results (memory/search-memory restarted-service
                                          "concise answers preference"
                                          {:limit 5
                                           :session-id (:id session-b)
                                           :scope {:type :session :id (:id session-b)}})
            leaked-session-facts (memory/search-facts restarted-service
                                                      "redwood"
                                                      {:scope {:type :session :id (:id session-b)}
                                                       :include-global? false})]
        (is (= 1 (count (:ranked results))))
        (is (= :fact (get-in results [:ranked 0 :surface])))
        (is (= "concise answers" (get-in results [:ranked 0 :item :object])))
        (is (empty? leaked-session-facts))
        (sqlite/close-store! restarted-store))
      (finally
        (io/delete-file db-path true)))))
