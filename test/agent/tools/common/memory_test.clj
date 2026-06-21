(ns agent.tools.common.memory-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.common.memory :as memory-tool]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn- temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-memory-tool-" ".db")))

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            prefix
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- memory-service [store]
  (memory/create-memory-service
   {:search {:default-limit 10}
    :notes {:extractor {:enabled false}}}
   store))

(defn- registry [service]
  (reduce tools/register-tool
          (tools/create-registry)
          (conj (memory-tool/create-memory-tools service)
                (memory-tool/create-message-search-tool service))))

(defrecord NoteProvider [responses requests]
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

(deftest memory-tool-recall-uses-vault-notes-and-ignores-prompt-files-test
  (let [path (temp-db-path)
        root (temp-dir "iris-memory-tool")
        prompt-file (io/file root "MEMORY.md")
        note-file (io/file root "preferences/kimi.md")
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "memory-tool")
        _ (spit prompt-file (str "Kimi prompt marker " (apply str (repeat 1200 "x"))))
        service (memory/create-memory-service
                 {:search {:default-limit 10}
                  :vault {:paths [(.getAbsolutePath root)]
                          :writable? true}
                  :notes {:extractor {:enabled false}}}
                 store)
        registry* (registry service)]
    (try
      (.mkdirs (.getParentFile note-file))
      (spit note-file
            (str "---\n"
                 "id: mem_kimi\n"
                 "type: Preference\n"
                 "title: Kimi memory support\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "---\n\n"
                 "# Kimi memory support\n\n"
                 "Kimi supports memory vault notes.\n"))
      (memory/reindex-vault! service)
      (sqlite/append-message! store (:id session) "assistant"
                              (str "Kimi model marker " (apply str (repeat 2000 "x"))))
      (let [result (tools/execute-tool registry*
                                       :memory_recall
                                       {:query "Kimi"
                                        :limit 3}
                                       {:permissions #{:memory-read}
                                        :session-id (:id session)})]
        (is (string? result))
        (is (str/includes? result "Memory recall for: Kimi"))
        (is (str/includes? result "vault_chunk #"))
        (is (not (str/includes? result "prompt #MEMORY.md")))
        (is (not (str/includes? result "\"messages\"")))
        (is (not (str/includes? result "\"ranked\""))))
      (let [blank-result (tools/execute-tool registry*
                                             :memory_recall
                                             {:query ""}
                                             {:permissions #{:memory-read}
                                              :session-id (:id session)})]
        (is (= "Memory recall skipped: query is blank. Provide a focused query."
               blank-result)))
      (finally
        (io/delete-file path true)
        (io/delete-file root true)))))

(deftest memory-extract-session-tool-writes-candidate-notes-test
  (let [path (temp-db-path)
        root (temp-dir "iris-memory-extract-tool")
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "memory-extract")
        service (memory/create-memory-service
                 {:search {:default-limit 10}
                  :vault {:paths [(.getAbsolutePath root)]
                          :writable? true}
                  :notes {:extractor {:enabled true}
                          :default-scope :session}}
                 store)
        responses (atom [(json/generate-string
                          {:notes [{:type "Decision"
                                    :title "Manual memory extraction"
                                    :description "Memory extraction is explicit."
                                    :body "Memory extraction runs from an explicit session command, not every chat turn."
                                    :tags ["memory"]
                                    :scope "session"
                                    :confidence 0.92}]})])
        requests (atom [])
        provider (->NoteProvider responses requests)
        registry* (reduce tools/register-tool
                          (tools/create-registry)
                          (conj (memory-tool/create-memory-tools service provider)
                                (memory-tool/create-message-search-tool service)))]
    (try
      (let [user (sqlite/append-message! store (:id session) "user" "Do memory only on explicit request")
            assistant (sqlite/append-message! store (:id session) "assistant" "Noted")
            result (tools/execute-tool registry*
                                       :memory_extract_session
                                       {:limit 20}
                                       {:permissions #{:memory-write}
                                        :session-id (:id session)
                                        :request-id "req-memory"})]
        (is (str/includes? result "Candidate notes: 1"))
        (is (= 1 (count @requests)))
        (is (= 1 (sqlite/count-vault-notes store)))
        (let [note (first (sqlite/list-vault-notes store))
              content (slurp (:path note))]
          (is (str/includes? content "Manual memory extraction"))
          (is (str/includes? content (str "message_id: \"" (:id user) "\"")))
          (is (str/includes? content (str "message_id: \"" (:id assistant) "\"")))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file root true)
        (io/delete-file path true)))))

(deftest memory-tool-recall-clamps-requested-limit-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "memory-tool-limit")
        service (memory/create-memory-service
                 {:search {:default-limit 2
                           :max-limit 2}
                  :notes {:extractor {:enabled false}}}
                 store)
        registry* (registry service)]
    (try
      (doseq [idx (range 6)]
        (sqlite/append-message! store (:id session) "assistant" (str "Kimi clamp-marker value-" idx)))
      (let [result (tools/execute-tool registry*
                                       :memory_recall
                                       {:query "Kimi"
                                        :limit 99}
                                       {:permissions #{:memory-read}
                                        :session-id (:id session)})
            result-lines (->> (str/split-lines result)
                              (filter #(str/starts-with? % "- "))
                              vec)]
        (is (= 2 (count result-lines))))
      (finally
        (io/delete-file path true)))))

(deftest vault-search-tool-uses-indexed-approved-vault-notes-test
  (let [path (temp-db-path)
        root (temp-dir "iris-memory-vault-tool")
        note-file (io/file root "runbooks/ops.md")
        store (sqlite/create-store {:path path})
        service (memory/create-memory-service
                 {:search {:default-limit 10}
                  :vault {:paths [(.getAbsolutePath root)]
                          :writable? true}
                  :notes {:extractor {:enabled false}}}
                 store)
        registry* (registry service)]
    (try
      (.mkdirs (.getParentFile note-file))
      (spit note-file
            (str "---\n"
                 "id: mem_ops\n"
                 "type: Runbook\n"
                 "title: Ops deploy\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "---\n\n"
                 "# Deploy\n\n"
                 "Use agent.example.invalid health check.\n"))
      (memory/reindex-vault! service)
      (let [result (tools/execute-tool registry*
                                       :vault_search
                                       {:query "tailscale"
                                        :limit 5}
                                       {:permissions #{:memory-read}})]
        (is (str/includes? result "Vault results for: tailscale"))
        (is (str/includes? result "mem_ops"))
        (is (str/includes? result "agent.example.invalid")))
      (finally
        (io/delete-file path true)
        (io/delete-file root true)))))

(deftest scratchpad-tools-read-search-replace-test
  (let [path (temp-db-path)
        root (temp-dir "iris-memory-scratchpad-tool")
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "scratchpad-tool")
        service (memory/create-memory-service
                 {:search {:default-limit 10}
                  :vault {:paths [(.getAbsolutePath root)]
                          :writable? true}
                  :notes {:extractor {:enabled false}}}
                 store)
        registry* (registry service)
        read-ctx {:permissions #{:memory-read}
                  :session-id (:id session)}
        write-ctx {:permissions #{:memory-write}
                   :session-id (:id session)}]
    (try
      (let [empty-pad (memory/read-scratchpad service {:session-id (:id session)})
            replace-result (tools/execute-tool registry*
                                               :scratchpad_replace
	                                               {:old-text ""
	                                                :new-text "alpha scratchpad marker\nbeta scratchpad marker\n"
	                                                :expected-revision (:revision empty-pad)}
	                                               write-ctx)
	            read-result (tools/execute-tool registry* :scratchpad_read {} read-ctx)
	            search-result (tools/execute-tool registry*
	                                              :scratchpad_search
	                                              {:query "beta"}
	                                              read-ctx)
             _ (memory/reindex-vault! service)
             vault-result (tools/execute-tool registry*
                                              :vault_search
                                              {:query "alpha"}
                                              read-ctx)
	            pad-after (memory/read-scratchpad service {:session-id (:id session)})
             inbox (io/file root "inbox")]
	        (is (str/includes? replace-result "Updated scratchpad session/"))
	        (is (str/includes? read-result "alpha scratchpad marker"))
	        (is (str/includes? search-result "line 2"))
        (is (str/includes? vault-result "Scratchpad"))
	        (is (str/includes? (:content pad-after) "beta scratchpad marker"))
	        (is (= 1 (sqlite/count-vault-notes store)))
        (is (not (.exists inbox)))
	        (is (thrown-with-msg? clojure.lang.ExceptionInfo
	                              #"scratchpad revision is stale"
                              (tools/execute-tool registry*
	                                                  :scratchpad_replace
	                                                  {:old-text "beta scratchpad marker\n"
	                                                   :new-text ""
	                                                   :expected-revision (:revision empty-pad)}
	                                                  write-ctx))))
      (finally
        (io/delete-file path true)
        (io/delete-file root true)))))

(deftest message-search-tool-returns-only-text-chunks-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "message-search")
        service (memory-service store)
        registry* (registry service)]
    (try
      (sqlite/append-message! store (:id session) "assistant"
                              (str "alpha "
                                   (apply str (repeat 1200 "x"))
                                   " Kimi chunk marker omega"))
      (let [result (tools/execute-tool registry*
                                       :message_search
                                       {:query "Kimi"
                                        :limit 3}
                                       {:permissions #{:memory-read}
                                        :session-id (:id session)})]
        (is (str/includes? result "Message chunks for: Kimi"))
        (is (str/includes? result "Kimi chunk marker"))
        (is (not (str/includes? result "message #")))
        (is (not (str/includes? result "session-id"))))
      (finally
        (io/delete-file path true)))))

(deftest message-search-tool-accepts-provider-string-limit-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "message-search-string-limit")
        service (memory-service store)
        registry* (registry service)]
    (try
      (doseq [idx (range 3)]
        (sqlite/append-message! store (:id session) "assistant" (str "Kimi chunk " idx)))
      (let [result (tools/execute-tool registry*
                                       :message_search
                                       {:query "Kimi"
                                        :limit "1"
                                        :session-id (:id session)}
                                       {:permissions #{:memory-read}
                                        :session-id (:id session)})
            result-lines (->> (str/split-lines result)
                              (filter #(str/starts-with? % "- "))
                              vec)]
        (is (= 1 (count result-lines))))
      (finally
        (io/delete-file path true)))))
