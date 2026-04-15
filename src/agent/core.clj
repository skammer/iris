(ns agent.core
  "Canonical composition root for the rewrite."
  (:gen-class)
  (:require
   [agent.api :as api]
   [agent.config :as config]
   [agent.llm.core :as llm-core]
   [agent.llm.providers.ollama :as ollama]
   [agent.llm.providers.openai-compatible :as openai-compatible]
   [agent.persistence.sqlite :as sqlite]
   [clojure.string :as str]))

(defn create-llm-provider
  [cfg]
  (let [{:keys [provider model site-url app-name openrouter ollama openai-compatible]} cfg]
    (case provider
      :ollama
      (ollama/create-ollama-provider
       {:base-url (get ollama :base-url)
        :default-model model
        :embedding-model (get ollama :embedding-model)
        :keep-alive (get ollama :keep-alive)})

      :openrouter
      (openai-compatible/create-openrouter-provider
       {:api-key (get openrouter :api-key)
        :base-url (get openrouter :base-url)
        :model model
        :site-url site-url
        :app-name app-name})

      :openai-compatible
      (openai-compatible/create-openai-compatible-provider
       {:api-key (get openai-compatible :api-key)
        :base-url (get openai-compatible :base-url)
        :default-model model
        :site-url site-url
        :app-name app-name})

      (throw (ex-info (str "Unsupported provider: " provider)
                      {:provider provider})))))

(defn create-store
  [cfg]
  (sqlite/create-store (get cfg :sqlite)))

(defn create-system
  ([] (create-system nil))
  ([config-path]
   (let [cfg (config/load-config config-path)
         llm-cfg (config/llm-config cfg)]
     {:config cfg
      :llm-provider (create-llm-provider llm-cfg)
      :store (create-store (:storage cfg))})))

(defn complete
  ([system prompt]
   (complete system [{:role "user" :content prompt}] {}))
  ([system messages opts]
   (llm-core/complete (:llm-provider system) messages opts)))

(defn stream
  ([system prompt]
   (stream system [{:role "user" :content prompt}] {}))
  ([system messages opts]
   (llm-core/stream (:llm-provider system) messages opts)))

(defn embed
  [system text opts]
  (llm-core/embed (:llm-provider system) text opts))

(defn health-check
  [system]
  {:llm (llm-core/health-check (:llm-provider system))
   :storage (sqlite/health-check (:store system))
   :provider (get-in system [:config :llm :provider])})

(defn create-session!
  ([system] (create-session! system nil))
  ([system title]
   (sqlite/create-session! (:store system) title)))

(defn list-sessions
  [system]
  (sqlite/list-sessions (:store system)))

(defn session-exists?
  [system session-id]
  (sqlite/session-exists? (:store system) session-id))

(defn list-messages
  [system session-id]
  (sqlite/list-messages (:store system) session-id))

(defn complete!
  [system messages {:keys [session-id] :as opts}]
  (let [content (complete system messages opts)
        user-message (last (filter #(= "user" (:role %)) messages))]
    (when session-id
      (when-let [prompt (:content user-message)]
        (sqlite/append-message! (:store system) session-id "user" prompt))
      (sqlite/append-message! (:store system) session-id "assistant" content))
    (sqlite/log-completion! (:store system)
                            {:session-id session-id
                             :provider (get-in system [:config :llm :provider])
                             :model (get-in system [:config :llm :model])
                             :prompt (:content user-message)
                             :response content})
    {:content content}))

(defn start-api!
  [system]
  (let [server (api/start-server! system (:api (:config system)))]
    (assoc system :api-server server)))

(defn stop-api!
  [system]
  (when-let [server (:api-server system)]
    (api/stop-server! server))
  (dissoc system :api-server))

(defn- usage []
  (str/join
   \newline
   ["Usage:"
    "  clojure -M -m agent.core \"prompt text\""
    "  clojure -M -m agent.core serve"
    "  clojure -M -m agent.core --config path/to/config.edn \"prompt text\""]))

(defn -main
  [& args]
  (let [[config-path rest-args] (if (= "--config" (first args))
                                  [(second args) (drop 2 args)]
                                  [nil args])
        command (first rest-args)
        prompt (str/join " " rest-args)]
    (cond
      (= "serve" command)
      (let [system (start-api! (create-system config-path))
            {:keys [host port]} (:api (:config system))]
        (println (str "API listening on http://" host ":" port))
        @(promise))

      (str/blank? prompt)
      (do
        (binding [*out* *err*]
          (println (usage)))
        (System/exit 1))

      :else
      (let [system (create-system config-path)
            response (complete system prompt)]
        (println response)))))
