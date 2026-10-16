(ns agent.config
  "Load, merge, migrate, and validate Iris config. This namespace owns the
   canonical config shape used by system construction, providers, tools,
   channels, storage, telemetry, and runtime limits."
  (:require
   [agent.config.env :as config-env]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [agent.config.defaults :as config-defaults]
   [agent.config.migration :as migration]
   [agent.config.validation :as validation]))

(def default-config config-defaults/default-config)
(def migrate-legacy-llm-config migration/migrate-legacy-llm-config)
(def migrate-legacy-config migration/migrate-legacy-config)

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

(defn- validate-config! [cfg]
  (let [errors (validation/config-validation-errors cfg)]
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
      (apply migration/deep-merge (concat included-configs [(strip-loader-config cfg)])))))

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
  (apply migration/deep-merge default-config (map source-config sources)))

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
         file-config (migration/deep-merge default-config
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
