(ns agent.wasm.bundles
  "Install, discover, and execute WASI tool/skill bundles."
  (:require
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [endive-clj.core :as wasm])
  (:import
   [java.io File]
   [java.nio.charset StandardCharsets]
   [java.nio.file Files Path StandardCopyOption]
   [java.util.zip ZipFile]))

(def default-config
  {:enabled? true
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
          :max-response-bytes 1048576}})

(def ^:private id-re #"[A-Za-z0-9._-]+")
(def ^:private tool-name-re #"[A-Za-z][A-Za-z0-9_]*")
(def ^:private bundle-extensions #{".tool" ".skill"})

(defn- clean-string [value]
  (some-> value str str/trim not-empty))

(defn- lower [value]
  (some-> value str str/lower-case))

(defn- path-string [^File file]
  (.getAbsolutePath file))

(defn- relative-safe-path? [value]
  (let [text (clean-string value)]
    (boolean
     (and text
          (not (str/starts-with? text "/"))
          (not (str/starts-with? text "\\"))
          (not (str/includes? text ".."))
          (not (.isAbsolute (io/file text)))))))

(defn- read-json-file [file]
  (try
    (json/parse-string (slurp file) true)
    (catch Exception e
      (throw (ex-info (str "Bundle manifest must be valid JSON: " (.getPath (io/file file)))
                      {:type :invalid-bundle-manifest
                       :path (.getPath (io/file file))
                       :error (.getMessage e)})))))

(defn- require-field [manifest k]
  (or (clean-string (get manifest k))
      (throw (ex-info (str "Bundle manifest missing " (name k))
                      {:type :invalid-bundle-manifest
                       :field k}))))

(defn- validate-name! [manifest k pattern message]
  (let [value (require-field manifest k)]
    (when-not (re-matches pattern value)
      (throw (ex-info message
                      {:type :invalid-bundle-manifest
                       :field k
                       :value value})))
    value))

(defn- validate-relative-file! [root manifest k required?]
  (let [value (clean-string (get manifest k))]
    (when (or value required?)
      (when-not (relative-safe-path? value)
        (throw (ex-info (str "Bundle manifest field " (name k) " must be a safe relative path")
                        {:type :invalid-bundle-manifest
                         :field k
                         :value value})))
      (let [file (io/file root value)]
        (when-not (.isFile file)
          (throw (ex-info (str "Bundle file missing: " value)
                          {:type :invalid-bundle
                           :field k
                           :path (.getPath file)})))
        (.getPath file)))))

(defn- declared-host-functions [manifest]
  (set (map (fn [{:keys [module name]}]
              [(str module) (str name)])
            (get-in manifest [:runtime :requiresHostFunctions]))))

(defn- validate-host-functions! [manifest]
  (let [declared (declared-host-functions manifest)
        supported #{["http" "request"]}
        unknown (seq (remove supported declared))]
    (when unknown
      (throw (ex-info "Bundle requires unsupported host functions"
                      {:type :unsupported-host-functions
                       :host-functions (mapv (fn [[module name]]
                                               {:module module :name name})
                                             unknown)})))))

(defn- json-type [schema]
  (let [value (:type schema)]
    (cond
      (string? value) value
      (keyword? value) (name value)
      :else nil)))

(declare json-schema->malli)

(defn- number-props [schema]
  (cond-> {}
    (:minimum schema) (assoc :min (:minimum schema))
    (:maximum schema) (assoc :max (:maximum schema))))

(defn- object-schema->malli [schema]
  (let [required (set (map keyword (:required schema)))
        closed? (false? (:additionalProperties schema))
        entries (mapv (fn [[k child]]
                        (let [k* (keyword (name k))
                              entry-props (when-not (contains? required k*)
                                            {:optional true})]
                          (if entry-props
                            [k* entry-props (json-schema->malli child)]
                            [k* (json-schema->malli child)])))
                      (:properties schema))]
    (if closed?
      (into [:map {:closed true}] entries)
      (if (seq entries)
        (into [:map] entries)
        [:map-of :any :any]))))

(defn json-schema->malli [schema]
  (cond
    (not (map? schema)) :any
    (seq (:enum schema)) (into [:enum] (:enum schema))
    :else
    (case (json-type schema)
      "object" (object-schema->malli schema)
      "string" :string
      "integer" (let [props (number-props schema)]
                  (if (seq props)
                    [:int props]
                    :int))
      "number" number?
      "boolean" :boolean
      "array" [:vector (json-schema->malli (:items schema))]
      :any)))

(defn load-bundle-root
  [root]
  (let [root-file (io/file root)
        manifest-file (io/file root-file "tool.json")
        manifest (when (.isFile manifest-file)
                   (read-json-file manifest-file))]
    (when manifest
      (let [id (validate-name! manifest :id id-re "Bundle id must contain only letters, digits, dot, underscore, and dash")
            name (validate-name! manifest :name tool-name-re "Bundle tool name must be snake_case ASCII")
            version (require-field manifest :version)
            module-path (validate-relative-file! root-file manifest :module true)
            skill-path (validate-relative-file! root-file manifest :skill false)
            schema (or (:schema manifest) {:type "object"})]
        (validate-host-functions! manifest)
        {:id id
         :name name
         :version version
         :root (path-string root-file)
         :manifest-path (path-string manifest-file)
         :module-path module-path
         :skill-path skill-path
         :manifest manifest
         :input-schema (json-schema->malli schema)}))))

(defn- installed-bundle-roots [install-dir enabled]
  (let [install-root (io/file install-dir)
        enabled-set (set (map str enabled))
        enabled-version-set (set (keep (fn [entry]
                                         (when-let [[_ id version] (re-matches #"([^@]+)@(.+)" (str entry))]
                                           [id version]))
                                       enabled))
        all? (or (= :all enabled) (contains? enabled-set "*"))]
    (if-not (.isDirectory install-root)
      []
      (for [id-dir (seq (.listFiles install-root))
            :when (.isDirectory id-dir)
            version-dir (seq (.listFiles id-dir))
            :when (.isDirectory version-dir)
            :let [id (.getName id-dir)
                  version (.getName version-dir)]
            :when (or all?
                      (contains? enabled-set id)
                      (contains? enabled-version-set [id version]))]
        version-dir))))

(defn discover-bundles
  [cfg]
  (let [cfg* (merge default-config (or cfg {}))]
    (if-not (:enabled? cfg*)
      []
      (->> (concat (map io/file (:dev-roots cfg*))
                   (installed-bundle-roots (:install-dir cfg*) (:enabled cfg*)))
           (keep load-bundle-root)
           (sort-by (juxt :id :version :root))
           vec))))

(defn bundle-skill-dirs
  [cfg]
  (->> (discover-bundles cfg)
       (filter :skill-path)
       (mapv :root)))

(defn- setting-candidates [bundle]
  [(:id bundle)
   (:name bundle)
   (keyword (:id bundle))
   (keyword (:name bundle))])

(defn bundle-settings
  [cfg bundle]
  (let [settings (:settings cfg)]
    (or (some #(get settings %) (setting-candidates bundle))
        {})))

(defn- normalize-actions [values]
  (set (keep (fn [value]
               (cond
                 (keyword? value) value
                 (string? value) (keyword (lower value))
                 :else nil))
             values)))

(defn- manifest-actions [bundle k]
  (normalize-actions (get-in bundle [:manifest k])))

(defn- sensitive-predicate [bundle]
  (let [sensitive-actions (manifest-actions bundle :sensitiveActions)
        read-only-actions (manifest-actions bundle :readOnlyActions)
        action-key (some-> (get-in bundle [:manifest :actionKey]) keyword)]
    (cond
      (seq sensitive-actions)
      (fn [input] (contains? sensitive-actions (some-> (get input action-key) str lower keyword)))

      (seq read-only-actions)
      (fn [input] (not (contains? read-only-actions (some-> (get input action-key) str lower keyword))))

      :else true)))

(defn- http-request-host-function [http-cfg]
  (let [config (merge (:http default-config) (or http-cfg {}))]
    {:module "http"
     :name "request"
     :params [:i32 :i32 :i32 :i32 :i32]
     :results [:i32]
     :fn (fn [ctx [request-ptr request-len out-ptr out-cap status-ptr]]
           (let [read-string (:memory/read-string ctx)
                 write-string! (:memory/write-string! ctx)
                 write-i32! (:memory/write-i32! ctx)]
             (try
               (let [request (json/parse-string (read-string request-ptr request-len) true)
                     method (keyword (lower (or (:method request) "GET")))
                     timeout-ms (min (long (or (:timeout_ms request)
                                               (:timeout-ms request)
                                               (:timeout-ms config)))
                                     (long (:max-timeout-ms config)))
                     body (:body request)
                     headers (into {}
                                   (map (fn [[k v]] [(name k) (str v)]))
                                   (:headers request))
                     response (http/request
                               (cond-> {:method method
                                        :url (:url request)
                                        :headers headers
                                        :socket-timeout timeout-ms
                                        :conn-timeout timeout-ms
                                        :follow-redirects false
                                        :throw-exceptions false}
                                 (some? body) (assoc :body (if (string? body)
                                                             body
                                                             (json/generate-string body)))))
                     status (int (:status response))
                     response-body (str (:body response))
                     response-bytes (.getBytes response-body StandardCharsets/UTF_8)
                     len (alength response-bytes)]
                 (write-i32! status-ptr status)
                 (cond
                   (> len (long (:max-response-bytes config))) -2
                   (> len (long out-cap)) -3
                   :else (write-string! out-ptr response-body)))
               (catch Exception _
                 (write-i32! status-ptr 0)
                 -1))))}))

(defn- host-functions [cfg bundle]
  (let [declared (declared-host-functions (:manifest bundle))]
    (cond-> []
      (contains? declared ["http" "request"])
      (conj (http-request-host-function (:http cfg))))))

(defn- run-options [cfg bundle settings input]
  {:engine :interpreter
   :limits {:timeout-ms (long (or (:timeout-ms input) (:timeout-ms cfg)))
            :max-stdout-bytes (:max-stdout-bytes cfg)
            :max-stderr-bytes (:max-stderr-bytes cfg)
            :max-memory-pages (:max-memory-pages cfg)}
   :wasi {:args [(:name bundle)]
          :stdin (json/generate-string
                  {:tool (:name bundle)
                   :arguments (dissoc input :timeout-ms)
                   :settings settings
                   :workspace "/workspace"})
          :fs {:mounts []
               :allowed-roots []
               :max-copy-bytes 0}}
   :host-functions (host-functions cfg bundle)})

(defn- parse-module-output [bundle result]
  (let [stdout (str/trim (or (:stdout result) ""))]
    (if (str/blank? stdout)
      {:result result
       :result-text (str (:name bundle) " ok: empty stdout")}
      (try
        (let [parsed (json/parse-string stdout true)]
          (if (false? (:ok parsed))
            (throw (tools/tool-error
                    (keyword (or (:error_type parsed) "wasm-bundle-error"))
                    (or (:error parsed) "WASM bundle returned error")
                    (assoc parsed :bundle-id (:id bundle))))
            {:result parsed
             :body (:body parsed)
             :result-text (or (:result_text parsed)
                              (:result-text parsed)
                              stdout)}))
        (catch clojure.lang.ExceptionInfo e
          (throw e))
        (catch Exception _
          {:result result
           :result-text stdout})))))

(defn create-bundle-tool
  [cfg bundle]
  (let [settings (bundle-settings cfg bundle)
        action-key (some-> (get-in bundle [:manifest :actionKey]) keyword)
        read-only-actions (manifest-actions bundle :readOnlyActions)
        parallel-safe-actions (manifest-actions bundle :parallelSafeActions)
        permissions (set (map keyword (or (get-in bundle [:manifest :requiredPermissions])
                                          ["wasm-execute"])))]
    (tools/create-tool
     {:description
      (tools/create-tool-description
       (keyword (:name bundle))
       (:description (:manifest bundle))
       :version (:version bundle)
       :category :api
       :timeout-ms (:timeout-ms cfg)
       :required-permissions permissions
       :input-schema (:input-schema bundle)
       :source :wasm-bundle
       :source-details {:bundle-id (:id bundle)
                        :bundle-version (:version bundle)
                        :root (:root bundle)}
       :operation :act
       :routing-categories #{:api :tools}
       :approval-sensitive? false
       :action-key action-key
       :read-only-actions read-only-actions
       :parallel-safe-actions parallel-safe-actions
       :sensitive (sensitive-predicate bundle))
      :health-fn (fn []
                   {:healthy true
                    :details {:bundle-id (:id bundle)
                              :version (:version bundle)
                              :root (:root bundle)}})
      :execute-fn
      (fn [input _context]
        (let [result (wasm/run-wasi {:path (:module-path bundle)}
                                    (run-options cfg bundle settings input))]
          (if (zero? (:exit-code result))
            (parse-module-output bundle result)
            (throw (tools/tool-error :wasm-bundle-exit
                                     "WASM bundle exited non-zero"
                                     {:bundle-id (:id bundle)
                                      :exit-code (:exit-code result)
                                      :stderr (:stderr result)
                                      :stdout (:stdout result)})))))})))

(defn create-bundle-tools
  [cfg]
  (let [cfg* (merge default-config (or cfg {}))]
    (mapv #(create-bundle-tool cfg* %) (discover-bundles cfg*))))

(defn bundle-extension [path]
  (some (fn [ext]
          (when (str/ends-with? (lower path) ext)
            ext))
        bundle-extensions))

(defn- safe-zip-entry? [name]
  (and (relative-safe-path? name)
       (not (str/blank? name))))

(defn- copy-stream! [in ^Path target]
  (Files/createDirectories (.getParent target)
                           (make-array java.nio.file.attribute.FileAttribute 0))
  (Files/copy in target (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING])))

(defn install-bundle!
  [cfg package-path]
  (let [cfg* (merge default-config (or cfg {}))
        package-file (io/file package-path)]
    (when-not (.isFile package-file)
      (throw (ex-info "Bundle package not found"
                      {:type :bundle-package-not-found
                       :path package-path})))
    (when-not (bundle-extension (.getName package-file))
      (throw (ex-info "Bundle package must end with .tool or .skill"
                      {:type :invalid-bundle-package
                       :path package-path})))
    (with-open [zip (ZipFile. package-file)]
      (let [manifest-entry (.getEntry zip "tool.json")]
        (when-not manifest-entry
          (throw (ex-info "Bundle package missing tool.json"
                          {:type :invalid-bundle-package
                           :path package-path})))
        (let [manifest (json/parse-string
                        (String. (.readAllBytes (.getInputStream zip manifest-entry))
                                 StandardCharsets/UTF_8)
                        true)
              id (validate-name! manifest :id id-re "Bundle id must contain only letters, digits, dot, underscore, and dash")
              version (require-field manifest :version)
              install-dir (io/file (:install-dir cfg*) id version)
              package-dir (io/file (:package-dir cfg*))
              package-target (io/file package-dir (.getName package-file))]
          (when (.exists install-dir)
            (throw (ex-info "Bundle version already installed"
                            {:type :bundle-already-installed
                             :id id
                             :version version
                             :path (.getPath install-dir)})))
          (.mkdirs install-dir)
          (.mkdirs package-dir)
          (doseq [entry (enumeration-seq (.entries zip))
                  :when (not (.isDirectory entry))]
            (let [name (.getName entry)]
              (when-not (safe-zip-entry? name)
                (throw (ex-info "Unsafe bundle path"
                                {:type :unsafe-bundle-path
                                 :path name})))
              (with-open [in (.getInputStream zip entry)]
                (copy-stream! in (.toPath (io/file install-dir name))))))
          (Files/copy (.toPath package-file)
                      (.toPath package-target)
                      (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
          (let [bundle (load-bundle-root install-dir)]
            {:id (:id bundle)
             :name (:name bundle)
             :version (:version bundle)
             :root (:root bundle)
             :package (.getPath package-target)}))))))

(defn installed-bundles
  [cfg]
  (->> (installed-bundle-roots (:install-dir (merge default-config (or cfg {}))) :all)
       (keep load-bundle-root)
       (mapv #(select-keys % [:id :name :version :root]))))
