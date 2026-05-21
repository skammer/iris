(ns user
  "REPL helpers for the embedded IRIS nREPL."
  (:require
   [agent.memory.core :as memory]
   [agent.nrepl :as nrepl]
   [agent.persistence.sqlite :as sqlite]
   [agent.system :as system]
   [clojure.pprint :refer [pprint]]))

(defn sys
  []
  (or (some-> @nrepl/current-system system/current-system)
      (throw (ex-info "No running system in agent.nrepl/current-system"
                      {:hint "Start IRIS with `clojure -M -m agent.core serve`."}))))

(defn store
  []
  (:store (sys)))

(defn memory-service
  []
  (:memory-service (sys)))

(defn pp
  [x]
  (pprint x)
  x)

(defn health
  []
  (system/health-check (sys)))

(defn api-base-url
  []
  (let [{:keys [host port]} (get-in (sys) [:config :api])]
    (str "http://" host ":" port)))

(defn sessions
  []
  (system/list-sessions (sys)))

(defn messages
  [session-id]
  (system/list-messages (sys) session-id))

(defn current-context
  [session-id]
  (sqlite/current-llm-context (store) session-id))

(defn events
  ([] (events {:limit 20}))
  ([opts] (system/list-events (sys) opts)))

(defn memory-surfaces
  []
  (system/memory-surfaces (sys)))

(defn prompt-memory
  []
  (system/read-prompt-memory (sys)))

(defn hybrid-search
  ([query] (hybrid-search query {:limit 10}))
  ([query opts] (system/search-memory (sys) query opts)))

(defn search-messages
  ([query] (search-messages query {:limit 10}))
  ([query opts] (sqlite/search-messages (store) query opts)))

(defn search-events
  ([query] (search-events query {:limit 10}))
  ([query opts] (sqlite/search-events (store) query opts)))

(defn search-facts
  ([query] (search-facts query {:limit 10}))
  ([query opts] (system/search-memory-facts (sys) query opts)))

(defn save-fact!
  ([fact] (save-fact! fact {}))
  ([fact opts] (system/save-memory-fact! (sys) fact opts)))

(defn save-session-fact!
  [session-id fact]
  (save-fact! fact {:scope {:type :session
                            :id session-id}
                    :source-session-id session-id
                    :source-request-id "dev/user.clj"}))

(defn remove-fact!
  ([fact] (remove-fact! fact {}))
  ([fact opts]
   (memory/remove-memory-fact! (memory-service) fact opts)))

(defn graph-search
  ([query] (graph-search query {:limit 10}))
  ([query opts] (system/query-graph-memory (sys) query opts)))

(defn save-graph-fact!
  [fact]
  (memory/save-graph-fact! (memory-service) fact))

(defn remove-graph-fact!
  [fact]
  (memory/remove-graph-fact! (memory-service) fact))

(defn datalog
  ([query] (datalog query {}))
  ([query opts] (memory/query-datalog-memory (memory-service) query opts)))

(defn tool-context
  ([] (tool-context {}))
  ([opts]
   (merge {:permissions #{:memory-read :memory-write}
           :request-id "dev/user.clj"}
          opts)))

(defn memory-tool
  ([input] (memory-tool input {}))
  ([input context] (system/execute-tool (sys) :memory input (tool-context context))))

(defn message-tool
  ([query] (message-tool query {}))
  ([query opts]
   (system/execute-tool (sys)
                        :message_search
                        (merge {:query query} opts)
                        (tool-context opts))))

(defn create-demo!
  []
  (let [s (sys)
        st (:store s)
        session (system/create-session! s "dev memory demo")
        session-id (:id session)
        user-message (sqlite/append-message! st
                                             session-id
                                             "user"
                                             "Alice likes Clojure and concise answers.")
        assistant-message (sqlite/append-message! st
                                                  session-id
                                                  "assistant"
                                                  "Noted: Alice likes Clojure.")
        event (system/log-event! s
                                 {:event-type :dev.memory-demo
                                  :entity-type :session
                                  :entity-id session-id
                                  :request-id "dev/user.clj"
                                  :payload {:session-id session-id
                                            :message-ids [(:id user-message)
                                                          (:id assistant-message)]}})
        session-fact (save-fact! {:subject "Alice"
                                  :predicate "likes"
                                  :object "Clojure"}
                                 {:scope {:type :session
                                          :id session-id}
                                  :source-session-id session-id
                                  :source-message-ids [(:id user-message)]
                                  :source-request-id "dev/user.clj"})
        global-fact (save-fact! {:subject "IRIS memory"
                                 :predicate "stores"
                                 :object "messages events facts and optional graph facts"}
                                {:scope {:type :global}
                                 :source-session-id session-id
                                 :source-message-ids [(:id assistant-message)]
                                 :source-request-id "dev/user.clj"})
        graph-fact (save-graph-fact! {:id "dev-user-alice-clojure"
                                      :subject "Alice"
                                      :predicate "likes"
                                      :object "Clojure"
                                      :source-session-id session-id
                                      :source-request-id "dev/user.clj"})]
    {:session session
     :messages [user-message assistant-message]
     :event event
     :facts [session-fact global-fact]
     :graph-fact graph-fact
     :hybrid (hybrid-search "Alice Clojure"
                            {:session-id session-id
                             :scope {:type :session
                                     :id session-id}
                             :entity-type :session
                             :entity-id session-id
                             :limit 10})}))

(comment
  ;; Shell entry points.
  ;; Current embedded nREPL may run from target/iris-0.1.0.jar only, so load-file works even when dev/ is not on classpath.
  ;; clj-nrepl-eval --discover-ports
  ;; clj-nrepl-eval -p 53849 "(load-file \"dev/user.clj\") (user/pp (user/health))"
  ;; clj-nrepl-eval -p 53849 "(load-file \"dev/user.clj\") (user/pp (user/create-demo!))"
  ;; clj-nrepl-eval -p 53849 "(load-file \"dev/user.clj\") (user/pp (user/hybrid-search \"Alice Clojure\"))"
  ;; clj-nrepl-eval -p 53849 "(load-file \"dev/user.clj\") (user/pp (user/search-messages \"Alice\"))"
  ;; clj-nrepl-eval -p 53849 "(load-file \"dev/user.clj\") (user/pp (user/search-events \"memory\"))"
  ;; clj-nrepl-eval -p 53849 "(load-file \"dev/user.clj\") (user/pp (user/search-facts \"Alice\" {:all-scopes? true}))"

  ;; If process was launched with -M:dev, this also works.
  (require 'user :reload)

  ;; Running system.
  (pp (health))
  (pp (api-base-url))

  ;; Memory surfaces.
  ;; prompt: files from :memory :prompt :paths, read separately.
  ;; search: core/API hybrid search over messages/events/facts/graph.
  ;; memory tool search: facts + graph + prompt files only.
  ;; message_search tool: messages only, text chunks only.
  ;; facts: SQLite memory_facts, scope-aware.
  ;; graph: Datahike backend only when :memory :graph :enabled true.
  ;; vault: explicit file read/write; not searched by hybrid-search.
  (pp (memory-surfaces))
  (pp (prompt-memory))

  ;; Store demo data.
  ;; messages -> SQLite messages table; append-message! also writes session_entries.
  ;; events -> SQLite agent_events via system/log-event!.
  ;; facts -> SQLite memory_facts via save-fact!, logs memory.fact.saved event.
  ;; graph -> optional Datahike write from save-fact! when graph enabled.
  (def demo (create-demo!))
  (def sid (get-in demo [:session :id]))
  (pp demo)

  ;; Query sessions/messages.
  (pp (sessions))
  (pp (messages sid))
  (pp (current-context sid))
  (pp (search-messages "clojure"))
  (pp (search-messages "Alice" {:session-id sid
                                 :limit 5}))

  ;; Query events.
  (pp (events))
  (pp (events {:entity-type :session
               :entity-id sid
               :limit 20}))
  (pp (search-events "memory"))
  (pp (search-events "dev.memory-demo" {:entity-type :session
                                         :entity-id sid
                                         :limit 10}))

  ;; Query facts.
  (pp (search-facts "Alice" {:scope {:type :session
                                     :id sid}
                            :limit 10}))
  (pp (search-facts "IRIS memory" {:scope {:type :global}
                                   :limit 10}))
  (pp (search-facts "clojure" {:all-scopes? true
                             :limit 10}))

  ;; Store/remove SQLite facts.
  (def fact (save-fact! {:subject "Bob"
                         :predicate "likes"
                         :object "Datalog"}
                        {:scope {:type :global}
                         :source-request-id "dev/user.clj"}))
  (pp fact)
  (pp (remove-fact! {:id (:id fact)}))

  ;; Hybrid search returns {:messages [] :events [] :facts [] :graph [] :ranked []}.
  ;; It does not full-text search prompt/vault files.
  (pp (hybrid-search "Alice Clojure"
                     {:session-id sid
                      :scope {:type :session
                              :id sid}
                      :entity-type :session
                      :entity-id sid
                      :limit 10}))

  ;; Optional graph query. Default config has graph disabled, so this returns [].
  (pp (graph-search nil {:limit 10}))

  ;; Tools.
  ;; :memory search excludes messages/events.
  (println (memory-tool {:action :search
                         :query "Alice Clojure"
                         :limit 10}
                        {:session-id sid}))

  ;; :message_search returns only text chunks.
  (println (message-tool "Alice" {:session-id sid
                                  :limit 5}))

  ;; :memory fact actions.
  (println (memory-tool {:action :save-fact
                         :subject "Carol"
                         :predicate "uses"
                         :object "IRIS"
                         :scope {:type :global}}))
  (println (memory-tool {:action :remove-fact
                         :subject "Carol"
                         :predicate "uses"
                         :object "IRIS"
                         :scope {:type :global}}))

  ;; :memory graph actions and Datalog.
  (println (memory-tool {:action :save-graph-fact
                         :id "dev-tool-graph-fact"
                         :subject "IRIS"
                         :predicate "stores"
                         :object "graph facts"}))
  (println (memory-tool {:action :datalog
                         :query "[:find ?label :where [?e :entity/label ?label]]"
                         :limit 20}))
  (println (memory-tool {:action :remove-graph-fact
                         :id "dev-tool-graph-fact"}))

  ;; API equivalents.
  ;; curl -s http://127.0.0.1:8080/v1/memory/surfaces
  ;; curl -s -X POST http://127.0.0.1:8080/v1/memory/search -H 'content-type: application/json' -d '{"query":"Alice Clojure","limit":10}'
  ;; curl -s -X POST http://127.0.0.1:8080/v1/memory/facts/search -H 'content-type: application/json' -d '{"query":"Alice","all_scopes":true,"limit":10}'





;; all current graph facts
  (user/pp (user/graph-search nil {:mode :facts :limit 20}))

  ;; text filter over subject/predicate/object/tags
  (user/pp (user/graph-search "clojure" {:mode :facts :limit 10}))

  ;; facts connected to entity
  (user/pp (user/graph-search nil {:mode :neighbors :entity "user" :depth 1 :limit 10}))
  (user/pp (user/graph-search nil {:mode :neighbors :entity "alice" :depth 1 :limit 10}))

  ;; path between entities
  (user/pp (user/graph-search nil {:mode :paths :from "alice" :to "clojure" :max-depth 3}))

  ;; historical/as-of
  (user/pp (user/graph-search "prefers" {:mode :facts :include-historical? true :limit 20}))
  (user/pp (user/graph-search "prefers" {:mode :facts :as-of "2026-05-20T12:00:00Z"}))

  ;; raw Datalog
  (user/pp (user/datalog "[:find ?label :where [?e :entity/label ?label]]"
                         {:limit 20}))

  ;; Your current graph has only useful unique triples:

  ["user" "prefers" "concise answers"] ; 27 copies
  ["alice" "likes" "clojure"]          ; 18 copies

  ;; So best current queries:

  (user/graph-search "concise answers" {:mode :facts})
  (user/graph-search "clojure" {:mode :facts})
  (user/graph-search nil {:mode :neighbors :entity "user"})
  (user/graph-search nil {:mode :neighbors :entity "alice"})



  )
