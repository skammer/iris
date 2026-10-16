(ns user
  "REPL helpers for the embedded IRIS nREPL."
  (:require
   [agent.memory.core :as memory]
   [agent.nrepl :as nrepl]
   [agent.persistence.sqlite :as sqlite]
   [agent.sessions.service :as sessions]
   [agent.system :as system]
   [agent.system.events :as system-events]
   [agent.system.health :as system-health]
   [agent.tools.service :as tool-service]
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
  (system-health/health-check (sys)))

(defn api-base-url
  []
  (let [{:keys [host port]} (get-in (sys) [:config :api])]
    (str "http://" host ":" port)))

(defn sessions
  []
  (sessions/list-sessions (sys)))

(defn messages
  [session-id]
  (sessions/list-messages (sys) session-id))

(defn current-context
  [session-id]
  (sqlite/current-llm-context (store) session-id))

(defn events
  ([] (events {:limit 20}))
  ([opts] (sqlite/list-events (store) opts)))

(defn memory-surfaces
  []
  (memory/list-surfaces (memory-service)))

(defn hybrid-search
  ([query] (hybrid-search query {:limit 10}))
  ([query opts] (memory/search-memory (memory-service) query opts)))

(defn search-messages
  ([query] (search-messages query {:limit 10}))
  ([query opts] (sqlite/search-messages (store) query opts)))

(defn search-events
  ([query] (search-events query {:limit 10}))
  ([query opts] (sqlite/search-events (store) query opts)))

(defn tool-context
  ([] (tool-context {}))
  ([opts]
   (merge {:permissions #{:memory-read :memory-write}
           :request-id "dev/user.clj"}
          opts)))

(defn memory-tool
  ([input] (memory-tool input {}))
  ([input context] (tool-service/execute-tool (sys) :memory_recall input (tool-context context))))

(defn message-tool
  ([query] (message-tool query {}))
  ([query opts]
   (tool-service/execute-tool (sys)
                              :message_search
                              (merge {:query query} opts)
                              (tool-context opts))))

(defn create-demo!
  []
  (let [s (sys)
        st (:store s)
        session (sessions/create-session! s "dev memory demo")
        session-id (:id session)
        user-message (sqlite/append-message! st
                                             session-id
                                             "user"
                                             "Alice likes Clojure and concise answers.")
        assistant-message (sqlite/append-message! st
                                                  session-id
                                                  "assistant"
                                                  "Noted: Alice likes Clojure.")
        event (system-events/log-event! s
                                        {:event-type :dev.memory-demo
                                         :entity-type :session
                                         :entity-id session-id
                                         :request-id "dev/user.clj"
                                         :payload {:session-id session-id
                                                   :message-ids [(:id user-message)
                                                                 (:id assistant-message)]}})]
    {:session session
     :messages [user-message assistant-message]
     :event event
     :hybrid (hybrid-search "Alice Clojure"
                            {:session-id session-id
                             :scope {:type :session
                                     :id session-id}
                             :entity-type :session
                             :entity-id session-id
                             :limit 10})}))

(comment
  ;; Load with -M:dev or (load-file "dev/user.clj") in the embedded nREPL.
  ;; These helpers use the running system; create-demo! writes sample history.
  (pp (health))
  (pp (api-base-url))
  (pp (memory-surfaces))
  (def demo (create-demo!))
  (def sid (get-in demo [:session :id]))
  (pp (sessions))
  (pp (messages sid))
  (pp (current-context sid))
  (pp (search-messages "Alice" {:session-id sid :limit 5}))
  (pp (events {:entity-type :session :entity-id sid :limit 20}))
  (pp (search-events "dev.memory-demo"))
  (pp (hybrid-search "Alice Clojure" {:session-id sid :limit 10}))
  (pp (memory-tool {:query "Alice Clojure" :limit 10} {:session-id sid}))
  (pp (message-tool "Alice" {:session-id sid :limit 5})))
