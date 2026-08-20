(ns agent.config
  "Load, merge, migrate, and validate Iris config. This namespace owns the
   canonical config shape used by system construction, providers, tools,
   channels, storage, telemetry, and runtime limits."
  (:require
   [agent.config.env :as config-env]
   [agent.defaults :as defaults]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]))

(def default-config
  {:llm {:active-provider :ollama
         :stream-content? true
         :providers {:ollama {:type :ollama
                              :base-url "http://localhost:11434"
                              :model "llama3.2:3b"
                              :temperature defaults/llm-temperature
                              :max-tokens defaults/llm-max-tokens
                              :stream? false
                              :prompt-cache? true
                              :stream-structured-output? true
                              :timeout-ms 60000
                              :app-name "iris"
                              :keep-alive "5m"
                              :embedding-model "nomic-embed-text"}
                     :openrouter {:type :openrouter
                                  :api :chat-completions
                                  :base-url "https://openrouter.ai/api/v1"
                                  :model "openai/gpt-4o-mini"
                                  :models {"openai/gpt-4o-mini" {:context-window 128000
                                                                 :max-output-tokens 16384
                                                                 :supports-streaming true
                                                                 :supports-tools true
                                                                 :supports-vision true}}
                                  :temperature defaults/llm-temperature
                                  :max-tokens defaults/llm-max-tokens
                                  :stream? false
                                  :prompt-cache? true
                                  :stream-structured-output? true
                                  :timeout-ms 60000
                                  :site-url nil
                                  :app-name "iris"
                                  :api-key nil}
                     :openai-compatible {:type :openai-compatible
                                         :api :chat-completions
                                         :base-url "https://api.openai.com/v1"
                                         :model "gpt-4o-mini"
                                         :models {"gpt-4o-mini" {:context-window 128000
                                                                :max-output-tokens 16384
                                                                :supports-streaming true
                                                                :supports-tools true
                                                                :supports-vision true}}
                                         :temperature defaults/llm-temperature
                                         :max-tokens defaults/llm-max-tokens
                                         :stream? false
                                         :prompt-cache? true
                                         :stream-structured-output? true
                                         :timeout-ms 60000
                                         :site-url nil
                                         :app-name "iris"
                                         :api-key nil}}}
   :storage {:sqlite {:path "data/agent.db"
                      :journal-mode "WAL"
                      :maximum-pool-size 8
                      :minimum-idle 2
                      :connection-timeout-ms 30000
                      :destructive-reset-on-drift? false}}
   :chat {:max-steps defaults/chat-max-steps
          :profiles {:default {:small-model? false
                              :respond-tool? false
                              :force-tool-choice? false
                              :tool-routing? false
                              :max-nudges 0
                              :nudge-budgets {:unknown-tool 0
                                              :bare-text 0
                                              :malformed-args 0
                                              :repeated-tool-call 0
                                              :missing-prerequisite 0
                                              :repeated-same-error 0
                                              :premature-final 0
                                              :max-token-truncation 0
                                              :edit-failure 0}
                              :context-card-budgets {:nudges 0}}
                     :small-local {:provider :ollama
                                   :model "llama3.2:3b"
                                   :small-model? true
                                   :respond-tool? true
                                   :force-tool-choice? true
                                   :tool-routing? true
                                   :max-nudges 3
                                   :tool-categories [:read :write :run :search :web :plan :respond :messaging]
                                   :nudge-budgets {:bare-text 2
                                                   :unknown-tool 2
                                                   :malformed-args 2
                                                   :repeated-tool-call 2
                                                   :missing-prerequisite 2
                                                   :repeated-same-error 2
                                                   :premature-final 2
                                                   :max-token-truncation 1
                                                   :edit-failure 2}
                                   :context-card-budgets {:nudges 2
                                                         :tool 2
                                                         :prereq 2}}
                    }
	          :guardrails {:doom-loop {:enabled? true
	                                   :threshold 3
	                                   :window-size 16
	                                   :sequence-threshold 3
	                                   :sequence-window-size 24
	                                   :max-sequence-length 8}}
          :compaction {:max-context-tokens 8192
                       :reserve-output-tokens 1024
                       :keep-recent-tokens 2048
                       :max-summary-input-tokens 8192
                       :warning-threshold 0.8
                       :destructive-threshold 1.0
                       :summarizer-input-cap 8192
                       :summary-max-tokens 512
                       :tool-result-truncate-chars 2000
                       :budgets {:system 1200
                                 :memory 1200
                                 :recent-conversation 4096
                                 :tool-schema 1600
                                 :pending-tool-result 800
                                 :referenced-file 2400
                                 :output-reserve 1024}}}
   :loop {:max-iterations 10
          :plan-file "LOOP_PLAN.md"
          :summary-max-chars 1200
          :validation-max-chars 12000}
   :magi {:enabled? false
          :mode :assistive
          :fallback :human
          :apply-to #{:tool-approvals}
          :tool-categories #{:all}
          :memory-promotion {:mode :manual
                             :scopes #{:all}
                             :poll-interval-seconds 60
                             :failure-cooldown-minutes 15
                             :max-candidates 10}
          :tool {:enabled true}
          :execution :parallel
          :allow-critical? false
          :timeout-ms 30000
          :max-context-chars 12000
          :file-review {:enabled? true
                        :max-tool-calls 8
                        :max-tool-rounds 4
                        :timeout-ms 90000
                        :max-evidence-chars 32000
                        :max-tool-result-chars 12000}
          :filter {:provider nil :model nil}
          :judge {:provider nil :model nil}
          :agents {:melchior {:provider nil :model nil}
                   :balthasar {:provider nil :model nil}
                   :casper {:provider nil :model nil}}}
   :tools {:http {:enabled true
                  :timeout-ms 30000
                  :max-timeout-ms 30000
                  :max-response-bytes 1048576
                  :allow-private? false
                  :max-redirects 3
                  :default-headers {"User-Agent" "iris/0.1"}}
           :yolo? false
           :max-parallelism 6
           :permissions {:api [:filesystem-read :filesystem-write :http-request :system-reload :todo-read :todo-write :magi-evaluate :homeassistant :wasm-execute :cron-read :cron-manage]
                         :ui [:filesystem-read :filesystem-write :http-request :system-reload :todo-read :todo-write :magi-evaluate :homeassistant :wasm-execute]
                         :agent [:http-request :memory-read :memory-write :todo-read :todo-write :magi-evaluate :homeassistant :wasm-execute :cron-read :cron-manage]
                         :chat [:filesystem-read :http-request :memory-read :memory-write :system-reload :todo-read :todo-write :shell-exec :magi-evaluate :homeassistant :wasm-execute :cron-read :cron-manage]}
           :profiles {:cron-observe {:permissions [:filesystem-read :http-request :memory-read :homeassistant]
                                     :allowed-tools [:fs_read :fs_list :fs_search :http :memory_recall
                                                     :vault_search :message_search :message_get :homeassistant]
                                     :allowed-actions {:http [:get :head]
                                                       :homeassistant [:get_state :get_states :list_states :search_states :list_services]}}
                      :cron-memory {:permissions [:memory-read :memory-write]
                                    :allowed-tools [:memory_recall :vault_search :message_search :message_get
                                                    :memory_extract_session :memory_propose_create
                                                    :memory_propose_update :skills_list :skills_read]}
                      :cron-automation {:permissions [:filesystem-read :filesystem-write :http-request :shell-exec]
                                        :allowed-tools [:fs_read :fs_list :fs_search :fs_write :http :shell]}}
           :policy {:allowlist []
                    :blocklist []
                    :tool-scopes {}}
           :approvals {:ttl-seconds 900}
           :fs {:enabled true
                :roots ["."]
                :max-read-bytes 1048576
                :max-write-bytes 1048576
                :max-search-files 5000
                :max-search-file-bytes 1048576
                :max-search-results 200
                :max-search-line-chars 500
                :search-timeout-ms 5000}
           :homeassistant {:enabled false
                           :base-url nil
                           :token nil
                           :timeout-ms 10000
                           :allowed-domains #{:light :switch :scene :script}
                           :global-services #{}}
           :wasm {:enabled false
                  :timeout-ms 30000
                  :max-wasm-bytes 1048576
                  :max-stdout-bytes 1048576
                  :max-stderr-bytes 1048576
                  :max-memory-pages 64
                  :wasi {:args []
                         :env {}
                         :stdin ""
                         :fs {:mounts []
                              :allowed-roots []
                              :max-copy-bytes 10485760}}
                  :network {:enabled? false
                            :allowed-hosts []
                            :allow-private? false
                            :timeout-ms 10000
                            :max-response-bytes 1048576}}
           :wasm-bundles {:enabled? true
                          :install-dir "bundles/installed"
                          :package-dir "bundles/packages"
                          :dev-roots []
                          :enabled []
                          :settings {}
                          :timeout-ms 30000
                          :max-stdout-bytes 1048576
                          :max-stderr-bytes 1048576
                          :max-memory-pages 64
                          :http {:timeout-ms 10000
                                 :max-timeout-ms 30000
                                 :max-response-bytes 1048576}}
           :todo {:enabled true}
           :shell {:enabled true
                   :roots ["."]
                   :working-dir "."
                   :timeout-ms 30000
                   :max-timeout-ms 30000
                   :default-decision :ask
                   :rules [{:argv ["pwd"] :decision :allow}
                           {:argv ["printf" "**"] :decision :allow}
                           {:argv ["echo" "**"] :decision :allow}
                           {:argv ["which" "**"] :decision :allow}
                           {:argv ["type" "**"] :decision :allow}
                           {:argv ["ls" "**"] :decision :allow}
                           {:argv ["cat" "**"] :decision :allow}
                           {:argv ["head" "**"] :decision :allow}
                           {:argv ["tail" "**"] :decision :allow}
                           {:argv ["wc" "**"] :decision :allow}
                           {:argv ["df" "**"] :decision :allow}
                           {:argv ["sort" "**"] :decision :allow}
                           {:argv ["uniq" "**"] :decision :allow}
                           {:argv ["cut" "**"] :decision :allow}
                           {:argv ["diff" "**"] :decision :allow}
                           {:argv ["rg" "**"] :decision :allow}
                           {:argv ["grep" "**"] :decision :allow}
                           {:argv ["find" "**"] :decision :allow}
                           {:argv ["git" "status" "**"] :decision :allow}
                           {:argv ["git" "log" "**"] :decision :allow}
                           {:argv ["git" "diff" "**"] :decision :allow}
                           {:argv ["git" "show" "**"] :decision :allow}
                           {:argv ["git" "branch" "**"] :decision :allow}
                           {:argv ["cargo" "fmt" "**"] :decision :allow}
                           {:argv ["rm" "-rf" "/*"] :decision :deny}
                           {:argv ["sudo" "rm" "-rf" "/*"] :decision :deny}
                           {:argv ["dd" "**"] :decision :deny}
                           {:argv ["mkfs" "**"] :decision :deny}
                           {:argv ["fdisk" "**"] :decision :deny}
                           {:argv ["mkswap" "**"] :decision :deny}]
                   :max-output-bytes 65536}
           :mcp {:enabled false
                 :servers []}
           :display {:web {:show-tool-calls? true
                           :collapsed? true
                           :preview-chars 800
                           :args-preview-chars 800
                           :max-result-height-px 320
                           :per-tool {}}
                     :telegram {:show-tool-calls? true
                                :preview-chars 1600
                                :args-preview-chars 1200
                                :per-tool {}}
                     :api {:show-tool-calls? true
                           :full? true
                           :per-tool {}}}}
   :skills {:dirs ["skills"]}
   :cron {:enabled true
          :poll-interval-seconds 15
          :max-concurrency 2
          :run-timeout-seconds 1800
          :misfire-grace-seconds 3600
          :timezone "UTC"
          :provider nil
          :model nil
          :tool-profile :cron-observe
          :output-max-chars 200000}
   :memory {:search {:default-limit 10
                     :max-limit 10
                     :min-score 0.3}
            :vault {:paths ["memory"]
                    :writable? true}
            :embeddings {:enabled? false
                         :surfaces [:vault-notes :vault-chunks]
                         :batch-size 16
                         :candidate-limit 1000
                         :rebuild-mode :replace}
            :quality {:low-confidence-threshold 0.6
                      :stale-days 180}
            :user-profile {:enabled true
                           :min-confidence 0.9
                           :max-facts 24
                           :max-operations 5
                           :max-transcript-chars 20000
                           :max-user-md-chars 8000}
            :notes {:extractor {:enabled true
                                :provider nil
                                :model nil
                                :format :json-schema}
                    :idle-extraction {:enabled true
                                      :idle-timeout-minutes 45
                                      :poll-interval-seconds 60
                                      :failure-cooldown-minutes 15
                                      :max-sessions 20
                                      :max-messages 80
                                      :max-events 40
                                      :min-confidence 0.85
                                      :include-events? true}
                    :default-scope :session}}
   :channel-adapters {:telegram {:enabled false
                                  :bot-token nil
                                  :rich-messages? true
                                  :poll-timeout-seconds 30
                                  :poll-limit 100
                                  :max-download-bytes 20971520
	                                  :document-roots ["."]
	                                  :max-document-bytes 20971520
	                                  :allowlist {:allow-all? false
	                                              :user-ids []
	                                              :chat-ids []}}}
	   :runners {:default-substrate :auto
             ;; Substrates a remote API caller may request. Excludes
             ;; :local-unsandboxed (no isolation) so the run API cannot be used
             ;; as a host-exec endpoint. Internal/system callers are unrestricted.
             :api-selectable-substrates [:seatbelt :bubblewrap :docker :podman]
             :docker {:image "clojure:temurin-21-alpine"
                      :image-mode :mounted-dev
                      :pull-policy :missing
                      :container-working-dir "/workspace"
                      :container-data-dir "/tmp/iris"
                      :container-home-dir "/tmp/iris/home"
                      :user "65532:65532"
                      :host-working-dir "."
                      :share-network? false}
             :podman {:image "clojure:temurin-21-alpine"
                      :image-mode :mounted-dev
                      :pull-policy :missing
                      :container-working-dir "/workspace"
                      :container-data-dir "/tmp/iris"
                      :container-home-dir "/tmp/iris/home"
                      :user "65532:65532"
                      :host-working-dir "."
                      :share-network? false}}
   :nrepl {:enabled true
           :bind "127.0.0.1"
           :port 0
           :port-file ".nrepl-port"}
   :telemetry {:enabled true
               :max-latency-samples 1000}
   :observer {:enabled true
              :best-effort? true
              :sinks [:telemetry :logging]}
   :trace {:mode :none
           :path "runtime-trace.jsonl"
           :rolling-max-entries 1000}
   :logging {:enabled false
             :file {:path "logs/iris.log"
                    :max-bytes 10485760
                    :max-files 5}
             :otel {:enabled false
                    :url "http://localhost:4318/"
                    :send [:traces :logs]
                    :max-items 5000
                    :publish-delay 5000
                    :http-opts {:conn-timeout 2000
                                :socket-timeout 2000}}}
   :api {:host "127.0.0.1"
         :key nil
         :port 8080}})

(def ^:dynamic *env* #(System/getenv %))
(def ^:dynamic *user-home* #(System/getProperty "user.home"))
(def ^:dynamic *cwd* #(System/getProperty "user.dir"))

(def config-file-name "config.edn")
(def markdown-file-names
  ["SOUL.md" "AGENTS.md" "USER.md" "TOOLS.md" "BOOT.md" "HEARTBEAT.md"])
(def context-file-names (into [config-file-name] markdown-file-names))
(def template-file-names context-file-names)
(def app-config-keys
  [:llm :storage :tools :skills :memory :magi :cron :channel-adapters :runners
   :telemetry :observer :trace :logging :api :chat :loop])

(defn- getenv [name]
  (*env* name))

(defn- nonblank [value]
  (when (some? value)
    (let [value* (str/trim (str value))]
      (when-not (str/blank? value*) value*))))

(defn global-config-dir
  []
  (io/file
   (or (nonblank (getenv "IRIS_CONFIG_DIR"))
       (some-> (nonblank (getenv "XDG_CONFIG_HOME"))
               (io/file "iris")
               str)
       (str (io/file (*user-home*) ".config" "iris")))))

(defn local-config-dir
  []
  (io/file (*cwd*) ".iris"))

(defn- expand-home-path [path]
  (when (some? path)
    (let [path* (str path)]
      (cond
        (= "~" path*) (*user-home*)
        (str/starts-with? path* (str "~" java.io.File/separator))
        (str (io/file (*user-home*) (subs path* 2)))
        (str/starts-with? path* "~/")
        (str (io/file (*user-home*) (subs path* 2)))
        :else path*))))

(defn data-dir
  [global-dir]
  (io/file
   (expand-home-path
    (or (nonblank (getenv "IRIS_DATA_DIR"))
        (str (io/file global-dir "data"))))))

(defn- legacy-default-path? [path filename]
  (contains? #{(str "data/" filename) (str "data\\" filename)}
             (str path)))

(defn- resolve-data-path [path data-dir filename]
  (if (or (nil? path) (legacy-default-path? path filename))
    (str (io/file data-dir filename))
    (expand-home-path path)))

(defn- absolute-path? [path]
  (.isAbsolute (io/file path)))

(defn- distinct-paths [paths]
  (->> paths
       (remove nil?)
       (reduce (fn [acc path]
                 (if (some #(= % path) acc)
                   acc
                   (conj acc path)))
               [])))

(defn- resolve-config-first-paths [paths config-dir]
  (->> paths
       (mapcat (fn [path]
                 (let [path* (expand-home-path path)]
                   (if (or (nil? path*) (absolute-path? path*))
                     [path*]
                     [(str (io/file config-dir path*))
                      (str (io/file (*cwd*) path*))]))))
       distinct-paths
       vec))

(defn- resource-template-content [name]
  (when-let [resource (io/resource (if (= config-file-name name)
                                     "config/user.edn"
                                     name))]
    (slurp resource)))

(defn- default-file-content [name]
  (or (resource-template-content name)
      (throw (ex-info (str "Missing classpath template for config file: " name)
                      {:type :missing-config-template
                       :name name}))))

(defn- warn!
  [message attrs]
  (binding [*out* *err*]
    (println (str "WARNING " message " " (pr-str attrs)))))

(defn- error!
  [message attrs]
  (binding [*out* *err*]
    (println (str "ERROR " message " " (pr-str attrs)))))

(defn init-config!
  []
  (let [dir (global-config-dir)]
    (.mkdirs dir)
    (doseq [name template-file-names
            :let [file (io/file dir name)]]
      (when-not (.exists file)
        (warn! "iris config file missing; writing default"
               {:path (.getPath file)})
        (spit file (default-file-content name))))
    dir))

(defn- deep-merge
  [& maps]
  (apply merge-with
         (fn [left right]
           (if (and (map? left) (map? right))
             (deep-merge left right)
             right))
         maps))

(def ^:private provider-keys
  #{:ollama :openrouter :openai-compatible :deepseek})

(def ^:private provider-default-types
  {:ollama :ollama
   :openrouter :openrouter
   :openai-compatible :openai-compatible
   :deepseek :deepseek})

(def ^:private legacy-llm-provider-option-keys
  [:model :api :temperature :max-tokens :stream? :prompt-cache?
   :stream-structured-output? :structured-output-format :timeout-ms :site-url :app-name])

(defn- normalize-provider-config
  [provider-key provider-cfg]
  (assoc provider-cfg :type (or (:type provider-cfg)
                                (provider-default-types provider-key)
                                provider-key)))

(defn migrate-legacy-llm-config
  "Move pre-provider LLM keys into the canonical provider map."
  [llm-cfg]
  (let [active-provider (or (:provider llm-cfg)
                            (:active-provider llm-cfg)
                            (:default-provider llm-cfg)
                            (ffirst (:providers llm-cfg))
                            :ollama)
        legacy-provider-configs (into {}
                                      (keep (fn [provider-key]
                                              (when-let [provider-cfg (get llm-cfg provider-key)]
                                                [provider-key provider-cfg])))
                                      provider-keys)
        provider-configs (deep-merge legacy-provider-configs
                                     (:providers llm-cfg))
        legacy-provider-options (select-keys llm-cfg legacy-llm-provider-option-keys)
        provider-configs* (if (seq legacy-provider-options)
                            (update provider-configs active-provider deep-merge legacy-provider-options)
                            provider-configs)
        provider-configs** (into {}
                                 (map (fn [[provider-key provider-cfg]]
                                        [provider-key (normalize-provider-config provider-key provider-cfg)]))
                                 provider-configs*)]
    (assoc (apply dissoc llm-cfg :provider :default-provider
                  (concat legacy-llm-provider-option-keys provider-keys))
           :active-provider active-provider
           :providers provider-configs**)))

(defn migrate-legacy-config
  "Return cfg with legacy LLM shape converted to canonical config shape."
  [cfg]
  (cond-> cfg
    (contains? cfg :llm) (update :llm migrate-legacy-llm-config)))

(def ^:private provider-required-keys
  {:ollama [:base-url :model]
   :openrouter [:base-url :model :api-key]
   :openai-compatible [:base-url :model :api-key]
   :deepseek [:model :api-key]})

(def ^:private openai-compatible-provider-types
  #{:openrouter :openai-compatible :deepseek})

(def ^:private openai-compatible-apis
  #{:chat :chat-completion :chat-completions :completions
    :response :responses})

(defn- present-config-value? [value]
  (if (string? value)
    (not (str/blank? value))
    (some? value)))

(defn- missing-provider-keys [provider provider-cfg]
  (let [type (:type provider-cfg)]
    (cond
      (nil? provider-cfg)
      [{:path [:llm :providers provider]
        :message (str "active LLM provider " provider " is not configured")}]

      (nil? type)
      [{:path [:llm :providers provider :type]
        :message (str "active LLM provider " provider " missing required key :type")}]

      (nil? (provider-required-keys type))
      [{:path [:llm :providers provider :type]
        :message (str "unsupported active LLM provider type " type)}]

      :else
      (cond-> (vec
               (for [k (provider-required-keys type)
                     :when (not (present-config-value? (get provider-cfg k)))]
                 {:path [:llm :providers provider k]
                  :message (str "active LLM provider " provider " missing required key " k)}))
        (and (openai-compatible-provider-types type)
             (some? (:api provider-cfg))
             (not (openai-compatible-apis (:api provider-cfg))))
        (conj {:path [:llm :providers provider :api]
               :message (str "active LLM provider " provider
                             " has unsupported :api " (:api provider-cfg)
                             "; expected :chat-completions or :responses")})))))

(defn- positive-number-error [cfg path]
  (let [value (get-in cfg path)]
    (when (and (some? value) (not (pos? (long value))))
      {:path path
       :message (str (str/join "/" (map name path)) " must be positive")})))

(def ^:private legacy-llm-config-keys
  (set (concat [:provider :default-provider]
               legacy-llm-provider-option-keys
               provider-keys)))

(defn- legacy-llm-config-errors [llm-cfg]
  (vec
   (for [k legacy-llm-config-keys
         :when (contains? llm-cfg k)]
     {:path [:llm k]
      :message (str "legacy LLM config key " k
                    " is no longer loaded; run config migrate and use :llm/:providers")})))

(def ^:private cron-action-values
  {:http #{:get :head :post :put :patch :delete}
   :homeassistant #{:get_state :get_states :list_states :search_states :list_services :call_service}})

(defn- cron-config-errors [cfg]
  (let [{:keys [provider model timezone tool-profile]} (:cron cfg)
        provider-cfg (get-in cfg [:llm :providers provider])
        model-ids (set (concat [(:model provider-cfg)]
                               (when (map? (:models provider-cfg)) (keys (:models provider-cfg)))
                               (when (sequential? (:models provider-cfg)) (map :model-id (:models provider-cfg)))))
        profiles (get-in cfg [:tools :profiles])]
    (vec
     (concat
      (when (not= (boolean provider) (boolean model))
        [{:path [:cron :provider] :message "cron provider and model must be configured together"}])
      (when (and provider (nil? provider-cfg))
        [{:path [:cron :provider] :message (str "unknown cron provider " provider)}])
      (when (and model provider-cfg (not (contains? model-ids model)))
        [{:path [:cron :model] :message (str "cron model is not configured for provider " provider)}])
      (when-not (contains? profiles tool-profile)
        [{:path [:cron :tool-profile] :message (str "unknown cron tool profile " tool-profile)}])
      (try
        (java.time.ZoneId/of timezone)
        []
        (catch Exception _
          [{:path [:cron :timezone] :message "cron timezone must be a valid IANA timezone"}]))
      (for [[profile-key profile] profiles
            [tool actions] (:allowed-actions profile)
            :let [known (get cron-action-values tool)]
            :when (or (nil? known) (not-every? known (map keyword actions)))]
        {:path [:tools :profiles profile-key :allowed-actions tool]
         :message (str "unsupported allowed action for " tool)})))))

(defn- config-validation-errors [cfg]
  (let [llm-cfg (:llm cfg)
        provider (:active-provider llm-cfg)
        provider-cfg (get-in llm-cfg [:providers provider])]
    (cond-> []
      (nil? (:llm cfg))
      (conj {:path [:llm]
             :message "missing required key :llm"})

      (nil? provider)
      (conj {:path [:llm :active-provider]
             :message "missing required key :llm/:active-provider"})

      provider
      (into (missing-provider-keys provider provider-cfg))

      llm-cfg
      (into (legacy-llm-config-errors llm-cfg))

      true
      (into (cron-config-errors cfg))

      true
      (into (keep #(positive-number-error cfg %)
                  [[:loop :max-iterations]
                   [:loop :summary-max-chars]
                   [:loop :validation-max-chars]
                   [:magi :file-review :max-tool-calls]
                   [:magi :file-review :max-tool-rounds]
                   [:magi :file-review :timeout-ms]
                   [:magi :file-review :max-evidence-chars]
                   [:magi :file-review :max-tool-result-chars]
                   [:tools :fs :max-search-files]
                   [:tools :fs :max-search-file-bytes]
                   [:tools :fs :max-search-results]
                   [:tools :fs :max-search-line-chars]
                   [:tools :fs :search-timeout-ms]
                   [:cron :poll-interval-seconds]
                   [:cron :max-concurrency]
                   [:cron :run-timeout-seconds]
                   [:cron :misfire-grace-seconds]
                   [:cron :output-max-chars]])))))

(defn- validate-config! [cfg]
  (let [errors (config-validation-errors cfg)]
    (when (seq errors)
      (doseq [error errors]
        (error! "iris config invalid" error))
      (throw (ex-info "Invalid iris config" {:type :config-invalid
                                             :errors errors}))))
  cfg)

(defn- existing-file [path]
  (let [file (io/file path)]
    (when (.exists file)
      file)))

(defn- load-edn-file [path]
  (when-let [file (existing-file path)]
    (with-open [reader (java.io.PushbackReader. (io/reader file))]
      (edn/read reader))))

(defn- normalize-iris-namespaced-config
  [cfg]
  (reduce (fn [acc k]
            (let [iris-k (keyword "iris" (name k))]
              (if (and (contains? acc iris-k)
                       (not (contains? acc k)))
                (assoc acc k (get acc iris-k))
                acc)))
          cfg
          app-config-keys))

(defn- config-loader-key? [k]
  (or (= :config k)
      (and (keyword? k)
           (= "config" (namespace k)))))

(defn- strip-loader-config [cfg]
  (if (map? cfg)
    (into {}
          (remove (fn [[k _]] (config-loader-key? k)))
          cfg)
    cfg))

(defn- throw-invalid-includes! [path value]
  (throw (ex-info (str "Invalid config include directive: " path)
                  {:type :config-include-invalid
                   :path path
                   :value value})))

(defn- include-list [path value]
  (cond
    (not (sequential? value))
    (throw-invalid-includes! path value)

    (not-every? #(and (string? %) (nonblank %)) value)
    (throw-invalid-includes! path value)

    :else
    (vec value)))

(defn- config-includes [path cfg]
  (when (map? cfg)
    (let [loader (:config cfg)
          includes* (cond-> []
                      (contains? cfg :config/includes)
                      (conj (:config/includes cfg))

                      (and (contains? cfg :config)
                           (do
                             (when-not (map? loader)
                               (throw-invalid-includes! path loader))
                             (contains? loader :includes)))
                      (conj (:includes loader)))]
      (vec (mapcat #(include-list path %) includes*)))))

(defn- resolve-include-file [base-dir path]
  (let [path* (expand-home-path path)]
    (if (absolute-path? path*)
      (io/file path*)
      (io/file base-dir path*))))

(defn- canonical-path [file]
  (.getCanonicalPath (io/file file)))

(declare load-config-file-with-includes)

(defn- load-include-file [base-dir from-path stack include-path]
  (let [file (resolve-include-file base-dir include-path)]
    (when-not (.exists file)
      (throw (ex-info (str "Config include not found: " (.getPath file))
                      {:type :config-include-not-found
                       :path (.getPath file)
                       :include include-path
                       :declared-from from-path})))
    (load-config-file-with-includes file stack)))

(defn- load-config-file-with-includes [file stack]
  (let [path (canonical-path file)]
    (when (some #(= path %) stack)
      (throw (ex-info (str "Config include cycle: " path)
                      {:type :config-include-cycle
                       :path path
                       :stack (conj (vec stack) path)})))
    (let [cfg (some-> (load-edn-file path)
                      normalize-iris-namespaced-config)
          base-dir (.getParentFile (io/file path))
          stack* (conj (vec stack) path)
          includes (config-includes path cfg)
          included-configs (map #(load-include-file base-dir path stack* %) includes)]
      (apply deep-merge (concat included-configs [(strip-loader-config cfg)])))))

(defn- load-optional-config
  [file]
  (when (.exists file)
    (load-config-file-with-includes file [])))

(defn- config-source-entry [file cfg]
  {:file (io/file file)
   :path (canonical-path file)
   :config cfg
   :stripped-config (strip-loader-config cfg)})

(defn- config-source-chain* [file stack]
  (let [path (canonical-path file)]
    (when (some #(= path %) stack)
      (throw (ex-info (str "Config include cycle: " path)
                      {:type :config-include-cycle
                       :path path
                       :stack (conj (vec stack) path)})))
    (let [cfg (some-> (load-edn-file path)
                      normalize-iris-namespaced-config)
          base-dir (.getParentFile (io/file path))
          stack* (conj (vec stack) path)
          includes (config-includes path cfg)
          included-sources (mapcat (fn [include-path]
                                     (let [include-file (resolve-include-file base-dir include-path)]
                                       (when-not (.exists include-file)
                                         (throw (ex-info (str "Config include not found: "
                                                              (.getPath include-file))
                                                         {:type :config-include-not-found
                                                          :path (.getPath include-file)
                                                          :include include-path
                                                          :declared-from path})))
                                       (config-source-chain* include-file stack*)))
                                   includes)]
      (conj (vec included-sources)
            (config-source-entry (io/file path) cfg)))))

(defn- config-source-chain [file]
  (when (.exists file)
    (config-source-chain* file [])))

(defn- distinct-sources [sources]
  (:sources
   (reduce (fn [{:keys [seen] :as acc} source]
             (if (contains? seen (:path source))
               acc
               (-> acc
                   (update :seen conj (:path source))
                   (update :sources conj source))))
           {:seen #{} :sources []}
           sources)))

(defn- config-sources [explicit-path]
  (let [global-file (io/file (global-config-dir) config-file-name)
        local-file (io/file (local-config-dir) config-file-name)
        explicit-file (when (nonblank explicit-path) (io/file explicit-path))]
    (distinct-sources
     (concat (config-source-chain global-file)
             (config-source-chain local-file)
             (when explicit-file
               (config-source-chain explicit-file))))))

(defn- source-config [source]
  (or (:stripped-config source) {}))

(defn- effective-edit-config [sources]
  (apply deep-merge default-config (map source-config sources)))

(defn- trim-leading-colon [text]
  (if (str/starts-with? text ":")
    (subs text 1)
    text))

(defn- segment-keyword [segment]
  (let [text (-> (str segment)
                 trim-leading-colon
                 (str/replace "_" "-"))]
    (if-let [[_ ns name] (re-matches #"([^/]+)/(.+)" text)]
      (keyword ns name)
      (keyword text))))

(defn- segment-candidates [segment]
  (let [raw (trim-leading-colon (str segment))
        hyphen (str/replace raw "_" "-")]
    (set (distinct [raw hyphen (str hyphen "?")]))))

(defn- key-text [k]
  (if (keyword? k)
    (if-let [ns (namespace k)]
      (str ns "/" (name k))
      (name k))
    (str k)))

(defn- matching-key [m segment]
  (when (map? m)
    (let [candidates (segment-candidates segment)]
      (first (filter #(contains? candidates (key-text %)) (keys m))))))

(defn- split-config-path [path]
  (let [segments (->> (str/split (str path) #"\.")
                      (map str/trim)
                      (remove str/blank?)
                      vec)]
    (when-not (seq segments)
      (throw (ex-info "config set path must be non-empty"
                      {:type :invalid-config-path
                       :path path})))
    segments))

(defn- resolve-config-path [cfg path]
  (loop [node cfg
         segments (split-config-path path)
         resolved []]
    (if-not (seq segments)
      resolved
      (let [segment (first segments)
            k (or (matching-key node segment)
                  (segment-keyword segment))]
        (recur (when (map? node) (get node k))
               (next segments)
               (conj resolved k))))))

(defn- contains-config-path? [cfg path]
  (loop [node cfg
         ks path]
    (cond
      (empty? ks) true
      (not (map? node)) false
      (contains? node (first ks)) (recur (get node (first ks)) (next ks))
      :else false)))

(defn- source-containing-path [sources path]
  (last (filter #(contains-config-path? (source-config %) path) sources)))

(defn- default-set-target-file [explicit-path]
  (cond
    (nonblank explicit-path)
    (io/file explicit-path)

    (.exists (io/file (local-config-dir) config-file-name))
    (io/file (local-config-dir) config-file-name)

    :else
    (io/file (global-config-dir) config-file-name)))

(defn- read-config-map [file]
  (let [cfg (some-> (load-edn-file (.getPath file))
                    normalize-iris-namespaced-config)]
    (cond
      (nil? cfg) {}
      (map? cfg) cfg
      :else (throw (ex-info (str "Config file must contain a map: " (.getPath file))
                            {:type :config-file-invalid
                             :path (.getPath file)})))))

(defn- assoc-config-in [m [k & ks] value]
  (let [m* (if (map? m) m {})]
    (if (seq ks)
      (assoc m* k (assoc-config-in (get m* k) ks value))
      (assoc m* k value))))

(defn- write-config-map! [file cfg]
  (when-let [parent (.getParentFile file)]
    (.mkdirs parent))
  (spit file
        (binding [*print-namespace-maps* false]
          (with-out-str
            (pprint/pprint cfg)))))

(defn- read-complete-edn [text]
  (let [reader (java.io.PushbackReader.
                (java.io.StringReader. text))
        value (edn/read {:eof ::eof} reader)]
    (when (= ::eof value)
      (throw (ex-info "empty EDN value" {:type :invalid-config-value})))
    (loop [ch (.read reader)]
      (cond
        (= -1 ch) value
        (Character/isWhitespace (char ch)) (recur (.read reader))
        :else (throw (ex-info "trailing characters after EDN value"
                              {:type :invalid-config-value}))))))

(defn parse-config-value [text]
  (try
    (let [value (read-complete-edn (str/trim (str text)))]
      (if (symbol? value)
        (str text)
        value))
    (catch Exception _
      (str text))))

(defn set-config-value!
  ([path value-text]
   (set-config-value! path value-text nil))
  ([path value-text {:keys [explicit-path]}]
   (let [sources (config-sources explicit-path)
         resolved-path (resolve-config-path (effective-edit-config sources) path)
         target-source (source-containing-path sources resolved-path)
         target-file (or (:file target-source)
                         (default-set-target-file explicit-path))
         existed? (.exists target-file)
         value (parse-config-value value-text)
         cfg (if existed? (read-config-map target-file) {})
         cfg* (assoc-config-in cfg resolved-path value)]
     (write-config-map! target-file cfg*)
     {:path resolved-path
      :file (.getPath target-file)
      :created? (not existed?)})))

(defn migrate-config-file
  [path]
  (-> (or (load-edn-file path)
          (throw (ex-info (str "Config file not found: " path)
                          {:type :config-file-not-found
                           :path path})))
      normalize-iris-namespaced-config
      migrate-legacy-config))

(defn- read-context-file
  [file name required?]
  (if (.exists file)
    (slurp file)
    (when required?
      (default-file-content name))))

(defn- load-context-files
  [global-dir local-dir]
  (let [local-exists? (.exists local-dir)]
    (into {}
          (map (fn [name]
                 (let [global-content (read-context-file (io/file global-dir name) name true)
                       local-content (when local-exists?
                                       (read-context-file (io/file local-dir name) name false))]
                   [name (str global-content local-content)])))
          markdown-file-names)))

(defn- iris-runtime-config
  [global-dir local-dir contexts]
  {:iris {:config-dir (.getPath global-dir)
          :local-config-dir (.getPath local-dir)
          :data-dir (.getPath (data-dir global-dir))
          :context-files markdown-file-names
          :contexts contexts
          :context (str/join "\n" (map contexts markdown-file-names))}})

(defn refresh-contexts
  "Reload Markdown context files into an already loaded runtime config."
  [cfg]
  (let [global-dir (io/file (get-in cfg [:iris :config-dir]))
        local-dir (io/file (get-in cfg [:iris :local-config-dir]))
        contexts (load-context-files global-dir local-dir)]
    (-> cfg
        (assoc-in [:iris :contexts] contexts)
        (assoc-in [:iris :context-files] markdown-file-names)
        (assoc-in [:iris :context]
                  (str/join "\n" (map contexts markdown-file-names))))))

(defn- finalize-data-paths
  [cfg global-dir]
  (let [data-dir* (data-dir global-dir)]
    (-> cfg
        (assoc-in [:iris :data-dir] (.getPath data-dir*))
        (update-in [:storage :sqlite :path] resolve-data-path data-dir* "agent.db"))))

(defn- finalize-skill-dirs
  [cfg global-dir]
  (update-in cfg [:skills :dirs] resolve-config-first-paths global-dir))

(defn- resolve-config-first-path [path config-dir]
  (first (resolve-config-first-paths [path] config-dir)))

(defn- finalize-wasm-bundles
  [cfg global-dir]
  (-> cfg
      (update-in [:tools :wasm-bundles :install-dir] resolve-config-first-path global-dir)
      (update-in [:tools :wasm-bundles :package-dir] resolve-config-first-path global-dir)
      (update-in [:tools :wasm-bundles :dev-roots] resolve-config-first-paths global-dir)))

(defn load-config
  ([] (load-config nil))
  ([path]
   (let [global-dir (global-config-dir)
         local-dir (local-config-dir)
         contexts (load-context-files global-dir local-dir)
         global-config (load-optional-config (io/file global-dir config-file-name))
         local-config (load-optional-config (io/file local-dir config-file-name))
         explicit-file (when path (io/file path))
         explicit-config (when (and explicit-file (.exists explicit-file))
                           (load-config-file-with-includes explicit-file []))
         file-config (deep-merge default-config
                                 global-config
                                 local-config
                                 (iris-runtime-config global-dir local-dir contexts)
                                 explicit-config)]
     (-> file-config
         (config-env/apply-env-config getenv)
         (finalize-data-paths global-dir)
         (finalize-skill-dirs global-dir)
         (finalize-wasm-bundles global-dir)
         validate-config!))))

(defn llm-config
  [config]
  (:llm config))

(defn active-provider-key
  [llm-cfg]
  (:active-provider llm-cfg))

(defn active-provider-config
  [llm-cfg]
  (let [provider (active-provider-key llm-cfg)]
    (assoc (get-in llm-cfg [:providers provider]) :provider provider)))

(defn active-model
  [llm-cfg]
  (:model (active-provider-config llm-cfg)))

(defn validate-effective-config
  "Load and validate the complete effective config without exposing secrets."
  ([] (validate-effective-config nil))
  ([path]
   (let [cfg (load-config path)]
     {:status :valid
      :source (or path :automatic)
      :provider (active-provider-key (:llm cfg))
      :model (active-model (:llm cfg))
      :chat-max-steps (get-in cfg [:chat :max-steps])})))

(defn chat-profile
  "Resolve chat profile for active provider/model. Precedence:
   per-model :chat-profile, selected named profile, then :default."
  [cfg]
  (let [llm-cfg (:llm cfg)
        provider-cfg (active-provider-config llm-cfg)
        model (:model provider-cfg)
        model-profile (get-in provider-cfg [:models model :chat-profile])
        profiles (get-in cfg [:chat :profiles])
        selected (or (get-in cfg [:chat :active-profile])
                     (get-in cfg [:chat :profile]))
        default-profile (:default profiles)
        selected-profile (get profiles selected)]
    (merge default-profile selected-profile model-profile)))
