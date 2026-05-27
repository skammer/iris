(ns agent.memory.core-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

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

(defrecord FailingGraphBackend []
  memory/IGraphMemoryBackend
  (save-fact! [_ _]
    (throw (ex-info "graph save failed" {:type :graph-save-failed})))
  (remove-fact! [_ _]
    (throw (ex-info "graph remove failed" {:type :graph-remove-failed})))
  (remove-all-facts! [_]
    (throw (ex-info "graph reset failed" {:type :graph-reset-failed})))
  (merge-entities! [_ _ _]
    (throw (ex-info "graph merge failed" {:type :graph-merge-failed})))
  (query-facts [_ _ _]
    (throw (ex-info "graph query failed" {:type :graph-query-failed})))
  (graph-facts [_ _]
    (throw (ex-info "graph list failed" {:type :graph-list-failed})))
  (backend-health-check [_]
    {:healthy true
     :details {:enabled true}}))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-memory-" ".db")))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-memory-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest memory-service-exposes-surfaces-and-search-test
  (let [db-path (temp-db-path)
        prompt-dir (temp-dir)
        prompt-file (io/file prompt-dir "MEMORY.md")
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "mem")]
    (spit prompt-file "remember this fact")
    (sqlite/append-message! store (:id session) "user" "hello memory")
    (sqlite/log-event! store {:event-type :session.created
                              :entity-type :session
                              :entity-id (:id session)
                              :payload {:title "memory hello"}})
    (let [service (memory/create-memory-service
                   {:prompt {:paths [(.getAbsolutePath prompt-file)]}
                    :search {:default-limit 10}
                    :graph {:enabled false
                            :backend :datahike
                            :datahike {:path (.getAbsolutePath (io/file prompt-dir "graph"))}}}
                   store)
          prompt (memory/read-prompt-memory service)
          results (memory/search-memory service "hello")
          surfaces (memory/list-surfaces service)]
      (is (= 5 (count surfaces)))
      (is (str/includes? (:combined prompt) "remember this fact"))
      (is (= 1 (count (:messages results))))
      (is (= 1 (count (:events results)))))
    (io/delete-file prompt-file true)
    (.delete prompt-dir)
    (io/delete-file db-path true)))

(deftest memory-search-limit-is-configurable-and-clamped-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "mem-limit")
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 2
                           :max-limit 3}
                  :graph {:enabled false}}
                 store)]
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
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :facts {:extractor {:enabled false}
                          :default-scope :session}
                  :graph {:enabled false}}
                 store)]
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

(deftest graph-failures-are-visible-in-health-and-events-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        service (-> (memory/create-memory-service
                     {:prompt {:paths []}
                      :search {:default-limit 10}
                      :facts {:extractor {:enabled false}}
                      :graph {:enabled false}}
                     store)
                    (assoc :graph-backend (->FailingGraphBackend))
                    (assoc-in [:config :graph :enabled] true))]
    (try
      (memory/save-memory-fact! service
                                {:subject "Alice"
                                 :predicate "likes"
                                 :object "Clojure"})
      (is (empty? (:graph (memory/search-memory service "Alice"))))
      (let [health (memory/health-check service)
            events (sqlite/list-events store {:limit 10})]
        (is (false? (:healthy health)))
        (is (= 2 (get-in health [:graph :failures :count])))
        (is (= [:save :query]
               (mapv :op (get-in health [:graph :failures :recent]))))
        (is (= ["memory.graph.failed" "memory.graph.failed"]
               (mapv :event-type
                     (filter #(= "memory.graph.failed" (:event-type %)) events)))))
      (finally
        (io/delete-file db-path true)))))

(deftest memory-reset-clears-facts-and-graph-without-clearing-messages-or-events-test
  (let [db-path (temp-db-path)
        graph-root (temp-dir)
        graph-path (.getAbsolutePath (io/file graph-root "graph-store"))
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "reset")
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :graph {:enabled true
                          :backend :datahike
                          :datahike {:path graph-path
                                     :keep-history? true}}}
                 store)]
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
                                 :object "Datahike"}
                                {:scope {:type :global}})
      (memory/save-graph-fact! service
                               {:subject "Graph"
                                :predicate "stores"
                                :object "edges"})
      (let [message-count (count (sqlite/list-messages store (:id session)))
            event-count (count (sqlite/list-events store {}))
            fact-reset (memory/remove-all-memory-facts! service)
            graph-reset (memory/remove-all-graph-facts! service)]
        (is (= 2 (:removed-count fact-reset)))
        (is (empty? (memory/search-facts service "" {:all-scopes? true})))
        (is (pos? (:removed-count graph-reset)))
        (is (empty? (memory/query-graph-memory service nil {:mode :facts})))
        (is (seq (memory/query-graph-memory service nil {:mode :facts
                                                         :include-historical? true})))
        (is (= message-count (count (sqlite/list-messages store (:id session)))))
        (is (= event-count (count (sqlite/list-events store {})))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file db-path true)
        (io/delete-file graph-root true)))))

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
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :facts {:extractor {:enabled true}
                          :default-scope :session}
                  :graph {:enabled false}}
                 store)]
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

(deftest memory-facts-similarity-fallback-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :facts {:dedup {:similarity-threshold 0.6}}
                  :graph {:enabled false}}
                 store)]
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
        (is (= [1 2] (:source-message-ids (first facts)))))
      (finally
        (io/delete-file db-path true)))))

(deftest datahike-graph-memory-prototype-test
  (let [db-path (temp-db-path)
        graph-root (temp-dir)
        graph-path (.getAbsolutePath (io/file graph-root "graph-store"))
        store (sqlite/create-store {:path db-path})
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :graph {:enabled true
                          :backend :datahike
                          :datahike {:path graph-path
                                     :keep-history? true}}}
                 store)
        saved (memory/save-graph-fact! service
                                       {:subject "alice"
                                        :predicate "likes"
                                        :object "clojure"
                                        :tags ["lang"]
                                        :confidence 0.9
                                        :valid-from "2026-01-01T00:00:00Z"
                                        :observed-at "2026-01-02T00:00:00Z"
                                        :episode-id "episode-1"
                                        :episode-content "alice likes clojure"})
        _ (memory/save-graph-fact! service
                                   {:subject "clojure"
                                    :predicate "runs-on"
                                    :object "jvm"
                                    :valid-from "2026-01-03T00:00:00Z"
                                    :observed-at "2026-01-03T00:00:00Z"})
        _ (memory/save-graph-fact! service
                                   {:subject "alice"
                                    :predicate "used"
                                    :object "java"
                                    :valid-from "2025-01-01T00:00:00Z"
                                    :valid-to "2025-12-31T00:00:00Z"
                                    :observed-at "2025-01-01T00:00:00Z"})
        _ (memory/save-graph-fact! service
                                   {:subject "alice"
                                    :predicate "likes"
                                    :object "rust"
                                    :valid-from "2026-02-01T00:00:00Z"
                                    :observed-at "2026-02-01T00:00:00Z"})
        queried (memory/query-graph-memory service "likes")
        datalog (memory/query-datalog-memory
                 service
                 "[:find ?label :where [?e :entity/label ?label]]"
                 {:limit 10})
        neighborhood (memory/query-graph-memory service nil {:entity "alice" :depth 2 :include-historical? true})
        current-neighborhood (memory/query-graph-memory service nil {:entity "alice" :depth 2})
        old-like (memory/query-graph-memory service "likes" {:as-of "2026-01-15T00:00:00Z"})
        historical (memory/query-graph-memory service "java" {:as-of "2025-06-01T00:00:00Z"})
        current (memory/query-graph-memory service "java" {:as-of "2026-06-01T00:00:00Z"})
        health (memory/health-check service)]
    (is (= "alice" (:subject saved)))
    (is (contains? (set (map first (:rows datalog))) "alice"))
    (is (= 1 (count queried)))
    (is (= "likes" (:predicate (first queried))))
    (is (= "rust" (:object (first queried))))
    (is (= "entity:alice" (:source-entity-id (first queried))))
    (is (= "episode-1" (get-in old-like [0 :episodes 0 :episode/id])))
    (is (= #{"likes" "runs-on" "used"} (set (map :predicate neighborhood))))
    (is (= ["likes"] (mapv :predicate current-neighborhood)))
    (is (= "clojure" (:object (first old-like))))
    (is (= 1 (count historical)))
    (is (empty? current))
    (is (= 4 (get-in health [:graph :details :edge-count])))
    (is (= 5 (get-in health [:graph :details :entity-count])))
    (io/delete-file db-path true)))

(deftest hybrid-memory-ranking-and-eval-test
  (let [db-path (temp-db-path)
        graph-root (temp-dir)
        graph-path (.getAbsolutePath (io/file graph-root "graph-store"))
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "hybrid")
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :graph {:enabled true
                          :backend :datahike
                          :datahike {:path graph-path
                                     :keep-history? true}}}
                 store)]
    (try
      (sqlite/append-message! store (:id session) "user" "Alice mentioned weekend hiking")
      (memory/save-memory-fact! service
                                {:subject "alice"
                                 :predicate "prefers"
                                 :object "rust"
                                 :confidence 0.95}
                                {:scope {:type :global}
                                 :source-session-id (:id session)})
      (memory/save-memory-fact! service
                                {:subject "bob"
                                 :predicate "prefers"
                                 :object "python"
                                 :confidence 0.9}
                                {:scope {:type :global}
                                 :source-session-id (:id session)})
      (let [results (memory/search-memory service "alice rust" {:limit 5})
            ranked (:ranked results)
            eval (memory/evaluate-retrieval
                  service
                  [{:query "alice rust"
                    :expected [{:surface :graph
                                :subject "alice"
                                :predicate "prefers"
                                :object "rust"}]}]
                  {:limit 5})]
        (is (= :graph (:surface (first ranked))))
        (is (= "rust" (get-in ranked [0 :item :object])))
        (is (pos? (get-in ranked [0 :score])))
        (is (= 1.0 (:recall eval)))
        (is (= 1.0 (:recall-at-k eval)))
        (is (= 1.0 (:mrr eval)))
        (is (= [1] (get-in eval [:cases 0 :ranks])))
        (is (= 1 (:passed-count eval))))
      (finally
        (io/delete-file db-path true)))))

(deftest memory-search-thresholds-and-dedupes-ranked-values-test
  (let [db-path (temp-db-path)
        graph-root (temp-dir)
        graph-path (.getAbsolutePath (io/file graph-root "graph-store"))
        store (sqlite/create-store {:path db-path})
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10
                           :min-score 0.3}
                  :graph {:enabled true
                          :backend :datahike
                          :datahike {:path graph-path
                                     :keep-history? true}}}
                 store)]
    (try
      (memory/save-memory-fact! service
                                {:subject "Alice"
                                 :predicate "likes"
                                 :object "Clojure"}
                                {:scope {:type :global}})
      (memory/save-graph-fact! service
                               {:subject "Bob"
                                :predicate "likes"
                                :object "Java"})
      (let [results (memory/search-memory service "Alice Clojure" {:limit 10})
            ranked (:ranked results)]
        (is (= 1 (count ranked)))
        (is (= :graph (:surface (first ranked))))
        (is (= "Alice likes Clojure"
               (str (get-in ranked [0 :item :subject])
                    " "
                    (get-in ranked [0 :item :predicate])
                    " "
                    (get-in ranked [0 :item :object]))))
        (is (not-any? #(= "Java" (:object %)) (:graph results))))
    (finally
      (io/delete-file db-path true)
      (io/delete-file graph-root true)))))

(deftest graph-reconciliation-detects-and-repairs-drift-test
  (let [db-path (temp-db-path)
        graph-root (temp-dir)
        graph-path (.getAbsolutePath (io/file graph-root "graph-store"))
        store (sqlite/create-store {:path db-path})
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :graph {:enabled true
                          :backend :datahike
                          :datahike {:path graph-path
                                     :keep-history? true}}}
                 store)]
    (try
      (sqlite/save-memory-fact! store {:id "fact-missing"
                                       :subject "Alice"
                                       :predicate "likes"
                                       :object "Clojure"})
      (sqlite/save-memory-fact! store {:id "fact-diverged"
                                       :subject "Bob"
                                       :predicate "likes"
                                       :object "Rust"})
      (sqlite/save-memory-fact! store {:id "fact-stale"
                                       :subject "Carol"
                                       :predicate "used"
                                       :object "Datahike"})
      (memory/save-graph-fact! service {:id "fact-diverged"
                                        :subject "Bob"
                                        :predicate "likes"
                                        :object "Python"})
      (memory/save-graph-fact! service {:id "fact-stale"
                                        :subject "Carol"
                                        :predicate "used"
                                        :object "Datahike"})
      (memory/save-graph-fact! service {:id "graph-only"
                                        :subject "Graph"
                                        :predicate "stores"
                                        :object "extra"})
      (sqlite/remove-memory-fact! store {:id "fact-stale"})
      (let [dry-run (memory/reconcile-graph-memory service)
            before-repair (memory/query-graph-memory service "Bob" {:mode :facts})
            repaired (memory/reconcile-graph-memory service {:repair? true})
            after (memory/reconcile-graph-memory service)
            bob (memory/query-graph-memory service "Bob" {:mode :facts})
            alice (memory/query-graph-memory service "Alice" {:mode :facts})
            stale (memory/query-graph-memory service "Carol" {:mode :facts})]
        (is (= {:sqlite-active 2
                :graph-active 3
                :missing 1
                :diverged 1
                :stale 1
                :graph-only 1
                :repair-errors 0}
               (:counts dry-run)))
        (is (= ["Python"] (mapv :object before-repair)))
        (is (false? (:repair? dry-run)))
        (is (= 3 (count (:repaired repaired))))
        (is (= {:sqlite-active 2
                :graph-active 3
                :missing 0
                :diverged 0
                :stale 0
                :graph-only 1
                :repair-errors 0}
               (:counts after)))
        (is (= ["Rust"] (mapv :object bob)))
        (is (= ["Clojure"] (mapv :object alice)))
        (is (empty? stale)))
      (finally
        (io/delete-file db-path true)
        (io/delete-file graph-root true)))))

(deftest graph-entity-alias-resolution-and-merge-test
  (let [db-path (temp-db-path)
        graph-root (temp-dir)
        graph-path (.getAbsolutePath (io/file graph-root "graph-store"))
        store (sqlite/create-store {:path db-path})
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :graph {:enabled true
                          :backend :datahike
                          :datahike {:path graph-path
                                     :keep-history? true}}}
                 store)]
    (try
      (memory/save-graph-fact! service
                               {:subject "Alice Smith"
                                :predicate "works-on"
                                :object "Graph Memory"
                                :observed-at "2026-01-01T00:00:00Z"})
      (memory/merge-graph-entities! service "Alice Smith" ["alice" "A. Smith"])
      (memory/save-graph-fact! service
                               {:subject "alice"
                                :predicate "prefers"
                                :object "Clojure"
                                :observed-at "2026-01-02T00:00:00Z"})
      (let [alias-neighborhood (memory/query-graph-memory service nil {:entity "A. Smith" :depth 1})
            canonical-neighborhood (memory/query-graph-memory service nil {:entity "Alice Smith" :depth 1})
            alias-fact (first (memory/query-graph-memory service "prefers"))]
        (is (= #{"works-on" "prefers"} (set (map :predicate alias-neighborhood))))
        (is (= (set (map :id canonical-neighborhood))
               (set (map :id alias-neighborhood))))
        (is (= "Alice Smith" (:subject alias-fact)))
        (is (= "entity:alice smith" (:source-entity-id alias-fact))))
      (finally
        (io/delete-file db-path true)))))

(deftest graph-path-query-test
  (let [db-path (temp-db-path)
        graph-root (temp-dir)
        graph-path (.getAbsolutePath (io/file graph-root "graph-store"))
        store (sqlite/create-store {:path db-path})
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :graph {:enabled true
                          :backend :datahike
                          :datahike {:path graph-path
                                     :keep-history? true}}}
                 store)]
    (try
      (memory/save-graph-fact! service
                               {:subject "Alice Smith"
                                :predicate "likes"
                                :object "Clojure"
                                :observed-at "2026-01-01T00:00:00Z"})
      (memory/save-graph-fact! service
                               {:subject "Clojure"
                                :predicate "runs-on"
                                :object "JVM"
                                :observed-at "2026-01-02T00:00:00Z"})
      (memory/save-graph-fact! service
                               {:subject "JVM"
                                :predicate "uses"
                                :object "bytecode"
                                :valid-to "2026-01-15T00:00:00Z"
                                :observed-at "2026-01-03T00:00:00Z"})
      (memory/merge-graph-entities! service "Alice Smith" ["alice"])
      (let [paths (memory/query-graph-memory service nil {:mode :paths
                                                          :from "alice"
                                                          :to "JVM"
                                                          :max-depth 3})
            inactive (memory/query-graph-memory service nil {:mode :paths
                                                             :from "alice"
                                                             :to "bytecode"
                                                             :max-depth 4})
            historical (memory/query-graph-memory service nil {:mode :paths
                                                               :from "alice"
                                                               :to "bytecode"
                                                               :max-depth 4
                                                               :as-of "2026-01-10T00:00:00Z"})]
        (is (= 1 (count paths)))
        (is (= ["Alice Smith" "Clojure" "JVM"]
               (mapv :label (:nodes (first paths)))))
        (is (= ["likes" "runs-on"]
               (mapv :predicate (:edges (first paths)))))
        (is (empty? inactive))
        (is (= ["likes" "runs-on" "uses"]
               (mapv :predicate (:edges (first historical))))))
      (finally
        (io/delete-file db-path true)))))

(deftest cross-session-memory-eval-recall-and-rank-test
  (let [db-path (temp-db-path)
        graph-root (temp-dir)
        graph-path (.getAbsolutePath (io/file graph-root "graph-store"))
        config {:prompt {:paths []}
                :search {:default-limit 10}
                :graph {:enabled true
                        :backend :datahike
                        :datahike {:path graph-path
                                   :keep-history? true}}}
        store (sqlite/create-store {:path db-path})
        session-a (sqlite/create-session! store "source")
        session-b (sqlite/create-session! store "recall")
        service (memory/create-memory-service config store)]
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
      (memory/save-graph-fact! service
                               {:subject "Sam"
                                :predicate "prefers-language"
                                :object "Python"
                                :observed-at "2026-01-01T00:00:00Z"})
      (memory/save-graph-fact! service
                               {:subject "Sam"
                                :predicate "prefers-language"
                                :object "Rust"
                                :observed-at "2026-02-01T00:00:00Z"})
      (memory/save-graph-fact! service
                               {:subject "Sam"
                                :predicate "works-on"
                                :object "Agent Runtime"
                                :observed-at "2026-02-02T00:00:00Z"})
      (memory/save-graph-fact! service
                               {:subject "Agent Runtime"
                                :predicate "uses"
                                :object "Datahike"
                                :observed-at "2026-02-03T00:00:00Z"})
      (sqlite/close-store! store)
      (let [restarted-store (sqlite/create-store {:path db-path})
            restarted-service (memory/create-memory-service config restarted-store)
            eval (memory/evaluate-retrieval
                  restarted-service
                  [{:query "concise answers preference"
                    :search-opts {:session-id (:id session-b)
                                  :scope {:type :session :id (:id session-b)}}
                    :expected [{:surface :fact
                                :subject "user"
                                :predicate "prefers"
                                :object "concise answers"}]}
                   {:query "prefers-language"
                    :graph-opts {:mode :facts}
                    :expected [{:surface :graph
                                :subject "Sam"
                                :predicate "prefers-language"
                                :object "Rust"}]}
                   {:query ""
                    :graph-opts {:mode :paths
                                 :from "sam"
                                 :to "Datahike"
                                 :max-depth 3}
                    :expected [{:surface :graph
                                :type "path"
                                :path-labels ["Sam" "Agent Runtime" "Datahike"]
                                :path-predicates ["works-on" "uses"]}]}]
                  {:limit 5})
            current-language (memory/query-graph-memory restarted-service
                                                        "prefers-language"
                                                        {:mode :facts})
            historical-language (memory/query-graph-memory restarted-service
                                                           "prefers-language"
                                                           {:mode :facts
                                                            :as-of "2026-01-15T00:00:00Z"})
            leaked-session-facts (memory/search-facts restarted-service
                                                      "redwood"
                                                      {:scope {:type :session :id (:id session-b)}
                                                       :include-global? false})]
        (is (= 3 (:passed-count eval)))
        (is (= 1.0 (:recall-at-k eval)))
        (is (= (/ 2.5 3.0) (:mrr eval)))
        (is (= [[2] [1] [1]] (mapv :ranks (:cases eval))))
        (is (= ["Rust"] (mapv :object current-language)))
        (is (= ["Python"] (mapv :object historical-language)))
        (is (empty? leaked-session-facts))
        (sqlite/close-store! restarted-store))
      (finally
        (io/delete-file db-path true)))))
