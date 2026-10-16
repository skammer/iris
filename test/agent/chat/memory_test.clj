(ns agent.chat.memory-test
  (:require
   [agent.chat.test-support :refer [->PlannerProvider message-text temp-db-path temp-dir test-system]]
   [agent.chat :as chat]
   [agent.chat.memory :as chat-memory]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.sessions.service :as sessions]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest chat-loop-injects-iris-context-before-memory-test
  (let [path (temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider
                            #(assoc-in % [:iris :context] "SOUL\nAGENTS"))
        session (sessions/create-session! system "context")]
    (try
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "hello"}]})
      (let [planner-messages (get-in (first @requests) [:request :messages])]
        (is (str/includes? (message-text (first planner-messages))
                           "tool-calling loop"))
        (is (= "SOUL\nAGENTS" (message-text (second planner-messages))))
        (is (str/starts-with? (message-text (nth planner-messages 2))
                              "Relevant memory JSON: "))
        (is (= "user" (:role (nth planner-messages 3)))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-injects-session-prompt-mode-before-memory-test
  (let [path (temp-db-path)
        responses (atom ["done"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:iris :context] nil))
        session (sessions/create-session! system "mode")]
    (try
      (sqlite/set-session-active-mode! (:store system) (:id session) "code")
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "hello"}]})
      (let [planner-messages (get-in (first @requests) [:request :messages])]
        (is (str/includes? (message-text (first planner-messages))
                           "tool-calling loop"))
        (is (str/includes? (message-text (second planner-messages))
                           "## Coding Mode"))
        (is (str/starts-with? (message-text (nth planner-messages 2))
                              "Relevant memory JSON: "))
        (is (= "user" (:role (nth planner-messages 3)))))
      (finally
        (io/delete-file path true)))))

(deftest chat-loop-does-not-auto-extract-candidate-vault-notes-test
  (let [path (temp-db-path)
        root (temp-dir)
        responses (atom ["noted"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:memory :vault :paths] [(.getAbsolutePath root)]))
        session (sessions/create-session! system "notes")]
    (try
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "I prefer concise answers"}]})
      (let [notes (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md"))
                          (file-seq (io/file root)))]
        (is (= 0 (sqlite/count-vault-notes (:store system))))
        (is (empty? notes)))
      (finally
        (io/delete-file root true)
        (io/delete-file path true)))))

(deftest chat-loop-continues-when-memory-recall-fails-test
  (let [path (temp-db-path)
        responses (atom ["ok"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:memory :notes :extractor :enabled] false))
        session (sessions/create-session! system "memory-fails")]
    (try
      (with-redefs [memory/search-memory (fn [& _]
                                           (throw (ex-info "memory boom" {:type :memory-test})))]
        (let [result (chat/run! system {:session-id (:id session)
                                        :messages [{:role "user" :content "hi"}]})
              events (sqlite/list-events (:store system) {:entity-type :session
                                                          :entity-id (:id session)
                                                          :limit 50})]
          (is (= "ok" (:content result)))
          (is (some #(and (= "message-update" (:event-type %))
                          (= "memory-recall-failed" (get-in % [:payload :kind])))
                    events))))
      (finally
        (io/delete-file path true)))))

(deftest chat-recall-does-not-inject-legacy-memory-md-test
  (let [path (temp-db-path)
        root (temp-dir)
        memory-file (io/file root "MEMORY.md")
        responses (atom ["ok"])
        requests (atom [])
        provider (->PlannerProvider responses requests)
        system (test-system path provider #(assoc-in % [:memory :prompt :paths]
                                                     [(.getAbsolutePath memory-file)]))
        session (sessions/create-session! system "no-memory-md")]
    (try
      (spit memory-file "forbidden legacy prompt marker")
      (chat/run! system {:session-id (:id session)
                         :messages [{:role "user" :content "hello"}]})
      (let [memory-message (->> @requests
                                first
                                :request
                                :messages
                                (some #(when (str/starts-with? (message-text %)
                                                               "Relevant memory JSON: ")
                                         (message-text %))))]
        (is (some? memory-message))
        (is (not (str/includes? memory-message "forbidden legacy prompt marker")))
        (is (not (str/includes? memory-message "\"prompt\""))))
      (finally
        (io/delete-file root true)
        (io/delete-file path true)))))

(deftest memory-message-truncation-remains-valid-json-test
  (let [content (:content (chat-memory/memory-message
                           {:query "x"
                            :results (vec (repeat 1000 {:surface :message
                                                        :type "assistant"
                                                        :id "m"
                                                        :scope {:type :session :id "s"}
                                                        :status :current
                                                        :text (apply str (repeat 40 "x"))
                                                        :score 0.5
                                                        :source {:message-id "m"}
                                                        :reason :lexical-overlap
                                                        :tags []}))
                            :surface-counts {:messages 1000
                                             :events 0
                                             :vault-chunks 0}}))
        json-text (subs content (count "Relevant memory JSON: "))
        parsed (json/parse-string json-text true)]
    (is (= "x" (:query parsed)))
    (is (vector? (:results parsed)))
    (is (< (count (:results parsed)) 1000))
    (is (<= (count json-text) chat-memory/memory-max-chars))
    (is (nil? (:preview parsed)))))
