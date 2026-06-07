(ns agent.config
  "Configuration loading for the rewritten runtime."
  (:require
   [agent.config.env :as config-env]
   [agent.defaults :as defaults]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
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
                                   :action :stop}
                        :enabled? true
                        :max-retries 3
                        :respond-tool? true
                        :force-tool-choice? true
                        :tool-routing? false}
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
   :tools {:http {:enabled true
                  :timeout-ms 30000
                  :max-timeout-ms 30000
                  :max-response-bytes 1048576
                  :allow-private? false
                  :max-redirects 3
                  :default-headers {"User-Agent" "iris/0.1"}}
           :yolo? false
           :max-parallelism 6
           :permissions {:api [:filesystem-read :filesystem-write :http-request :system-reload :todo-read :todo-write]
                         :ui [:filesystem-read :filesystem-write :http-request :system-reload :todo-read :todo-write]
                         :agent [:http-request :memory-read :memory-write :todo-read :todo-write]
                         :chat [:filesystem-read :http-request :memory-read :memory-write :system-reload :todo-read :todo-write :shell-exec]}
           :policy {:allowlist []
                    :blocklist []
                    :tool-scopes {}}
           :approvals {:ttl-seconds 900}
           :fs {:enabled true
                :roots ["."]
                :max-read-bytes 1048576
                :max-write-bytes 1048576}
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
   :memory {:prompt {:paths ["MEMORY.md"]}
            :search {:default-limit 10
                     :max-limit 10
                     :min-score 0.3}
            :vault {:paths ["memory"]
                    :writable? true}
            :facts {:extractor {:enabled true
                                :provider nil
                                :model nil
                                :format :json-schema}
                    :default-scope :session
                    :dedup {:similarity-threshold nil}}}
   :channel-adapters {:telegram {:enabled false
                                  :bot-token nil
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
   :orchestrator {:enabled false
                  :federation {:timeout-ms 10000
                               :inbox-path "/v1/federation/inbox"
                               :max-clock-skew-ms 300000
                               :outbox-poll-ms 1000
                               :retry-policy {:max-attempts 3
                                              :base-delay-ms 100
                                              :max-delay-ms 2000}
                               :peer-policy {:max-concurrency 8
                                             :rate-limit-per-minute 120
                                             :failure-threshold 5
                                             :circuit-open-ms 30000}
                               :key-id nil
                               :private-key nil}}
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
(def memory-file-name "MEMORY.md")
(def context-file-names (into [config-file-name] markdown-file-names))
(def template-file-names (conj context-file-names memory-file-name))
(def app-config-keys
  [:llm :storage :tools :skills :memory :channel-adapters :runners
   :orchestrator :telemetry :observer :trace :logging :api :chat :loop])

(def default-config-edn
  {:iris/config-version 1
   :iris/context-files markdown-file-names})

(def default-markdown-content
  {"SOUL.md" "# SOUL\n\n"
   "AGENTS.md" "# AGENTS\n\n"
   "USER.md" "# USER\n\n"
   "TOOLS.md" "# TOOLS\n\n"
   "BOOT.md" "# BOOT\n\n"
   "HEARTBEAT.md" "# HEARTBEAT\n\n"})

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
                                     "config/default.edn"
                                     name))]
    (slurp resource)))

(defn- default-file-content [name]
  (or (resource-template-content name)
      (if (= config-file-name name)
        (str (binding [*print-namespace-maps* false]
               (pr-str default-config-edn))
             "\n")
        (get default-markdown-content name ""))))

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
  #{:ollama :openrouter :openai-compatible})

(def ^:private provider-default-types
  {:ollama :ollama
   :openrouter :openrouter
   :openai-compatible :openai-compatible})

(def ^:private legacy-llm-provider-option-keys
  [:model :api :temperature :max-tokens :stream? :prompt-cache?
   :stream-structured-output? :timeout-ms :site-url :app-name])

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
   :openai-compatible [:base-url :model :api-key]})

(def ^:private openai-compatible-provider-types
  #{:openrouter :openai-compatible})

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
      (into (keep #(positive-number-error cfg %)
                  [[:loop :max-iterations]
                   [:loop :summary-max-chars]
                   [:loop :validation-max-chars]])))))

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

(defn- finalize-data-paths
  [cfg global-dir]
  (let [data-dir* (data-dir global-dir)]
    (-> cfg
        (assoc-in [:iris :data-dir] (.getPath data-dir*))
        (update-in [:storage :sqlite :path] resolve-data-path data-dir* "agent.db"))))

(defn- finalize-skill-dirs
  [cfg global-dir]
  (update-in cfg [:skills :dirs] resolve-config-first-paths global-dir))

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
