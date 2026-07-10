(ns agent.memory.idle-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.memory.core :as memory]
   [agent.memory.idle :as idle]
   [agent.persistence.sqlite :as sqlite]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defrecord IdleProvider [responses requests fail?]
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
    (when @fail?
      (throw (ex-info "extractor unavailable" {:type :test-failure})))
    {:role "assistant"
     :content (or (first (first (swap-vals! responses rest)))
                  (json/generate-string {:notes []}))
     :tool-calls []
     :usage nil
     :raw nil})
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defn- temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-idle-memory-" ".db")))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-idle-memory-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- test-system [store root provider cfg]
  {:config {:llm {}
            :memory {:notes {:idle-extraction (merge {:enabled true
                                                      :idle-timeout-minutes 0
                                                      :poll-interval-seconds 60
                                                      :failure-cooldown-minutes 15
                                                      :max-sessions 20
                                                      :max-messages 80
                                                      :max-events 40
                                                      :min-confidence 0.85
                                                      :include-events? true}
                                                     cfg)}}}
   :store store
   :memory-service (memory/create-memory-service
                    {:notes {:extractor {:enabled true}
                             :default-scope :session}
                     :vault {:paths [(.getAbsolutePath root)]
                             :writable? true}}
                    store)
   :llm-provider provider})

(deftest idle-extraction-empty-findings-advance-watermark-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "idle")
        responses (atom [(json/generate-string {:notes []})])
        requests (atom [])
        provider (->IdleProvider responses requests (atom false))
        system (test-system store root provider {})]
    (try
      (let [message (sqlite/append-message! store (:id session) "user" "hello")]
        (is (= {:processed 1
                :skipped-active 0
                :note-count 0
                :results [{:session-id (:id session)
                           :message-count 1
                           :event-count 0
                           :note-count 0}]}
               (idle/run-once! system)))
        (is (= 1 (count @requests)))
        (is (= (:id message)
               (:last-processed-message-id
                (sqlite/get-memory-extraction-state store (:id session)))))
        (is (= 0 (sqlite/count-vault-notes store)))
        (is (= 0 (:processed (idle/run-once! system))))
        (is (= 1 (count @requests))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest idle-extraction-writes-event-provenance-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "idle-event")
        responses (atom [(json/generate-string
                          {:notes [{:type "Runbook"
                                    :title "Focused lint"
                                    :description "Use focused lint for this repo."
                                    :body "Use focused lint for this repo."
                                    :tags ["runbook"]
                                    :scope "project"
                                    :confidence 0.9}]})])
        requests (atom [])
        provider (->IdleProvider responses requests (atom false))
        system (test-system store root provider {})]
    (try
      (sqlite/append-message! store (:id session) "user" "always run focused lint here")
      (sqlite/log-event! store {:event-type :tool-execution-end
                                :entity-type :session
                                :entity-id (:id session)
                                :payload {:tool-name "shell"
                                          :status :success
                                          :tool-call {:arguments {:api-key "secret"}}}})
      (let [result (idle/run-once! system)
            note (first (sqlite/list-vault-notes store {:limit 10}))
            content (slurp (:path note))
            request (first @requests)
            user-content (get-in request [:messages 1 :content])]
        (is (= 1 (:processed result)))
        (is (= 1 (:note-count result)))
        (is (= 1 (sqlite/count-vault-notes store)))
        (is (str/includes? content "idle-extraction"))
        (is (str/includes? content "event_id:"))
        (is (str/includes? user-content "tool-execution-end"))
        (is (not (str/includes? user-content "secret"))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest idle-extraction-skips-active-session-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "active")
        requests (atom [])
        provider (->IdleProvider (atom [(json/generate-string {:notes []})]) requests (atom false))
        system (assoc (test-system store root provider {})
                      :chat-service {:session-runtimes (atom {(:id session) {:active {:request-id "req"}}})
                                     :streaming-state (atom {})})]
    (try
      (sqlite/append-message! store (:id session) "user" "important later")
      (is (= 0 (:processed (idle/run-once! system))))
      (is (= 1 (:skipped-active (idle/run-once! system))))
      (is (nil? (sqlite/get-memory-extraction-state store (:id session))))
      (is (empty? @requests))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))

(deftest idle-extraction-failure-does-not-advance-watermark-test
  (let [db-path (temp-db-path)
        root (temp-dir)
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "failure")
        responses (atom [(json/generate-string {:notes []})])
        requests (atom [])
        fail? (atom true)
        provider (->IdleProvider responses requests fail?)
        system (test-system store root provider {:failure-cooldown-minutes 0})]
    (try
      (let [message (sqlite/append-message! store (:id session) "user" "remember only if ok")]
        (is (= 1 (:processed (idle/run-once! system))))
        (let [state (sqlite/get-memory-extraction-state store (:id session))]
          (is (= 0 (:last-processed-message-id state)))
          (is (= "extractor unavailable" (:last-error state))))
        (reset! fail? false)
        (is (= 1 (:processed (idle/run-once! system))))
        (is (= (:id message)
               (:last-processed-message-id
                (sqlite/get-memory-extraction-state store (:id session)))))
        (is (= 2 (count @requests))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file db-path true)))))
