(ns agent.logging
  "Minimal μ/log bootstrap and helpers."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.brunobonacci.mulog :as mulog]
   [com.brunobonacci.mulog.core :as mulog-core]))

(def ^:private default-path "logs/iris.log")
(def ^:private default-max-bytes (* 10 1024 1024))
(def ^:private default-max-files 5)
(def ^:private sensitive-key-fragments
  #{"api-key" "api_key" "authorization" "bearer" "password" "secret" "token" "credential"})
(def ^:private sensitive-value-patterns
  [#"(?i)bearer\s+[A-Za-z0-9._~+/=-]+"])
(defonce ^:private publisher-state (atom nil))

(defn- normalize-send [send]
  (let [values (cond
                 (nil? send) [:traces :logs]
                 (sequential? send) send
                 :else [send])]
    (mapv (fn [value]
            (if (keyword? value) value (keyword (str value))))
          values)))

(defn- normalize-config
  [cfg]
  (let [file-enabled? (true? (:enabled cfg))
        otel-enabled? (true? (get-in cfg [:otel :enabled]))
        path (or (get-in cfg [:file :path])
                 (get-in cfg [:publisher :filename])
                 default-path)
        max-bytes (long (or (get-in cfg [:file :max-bytes]) default-max-bytes))
        max-files (long (or (get-in cfg [:file :max-files]) default-max-files))
        otel-send (normalize-send (get-in cfg [:otel :send]))
        context (merge {:service-name "iris"
                        :app-name "iris"
                        :environment "local"}
                       (:context cfg))]
    {:enabled (or file-enabled? otel-enabled?)
     :file-enabled file-enabled?
     :file {:path path
            :max-bytes max-bytes
            :max-files max-files}
     :otel {:enabled otel-enabled?
            :url (or (get-in cfg [:otel :url]) "http://localhost:4318/")
            :send otel-send
            :max-items (long (or (get-in cfg [:otel :max-items]) 5000))
            :publish-delay (long (or (get-in cfg [:otel :publish-delay]) 5000))
            :http-opts (merge {:conn-timeout 2000
                               :socket-timeout 2000}
                              (get-in cfg [:otel :http-opts]))}
     :context context}))

(defn- file-publisher-config [cfg]
  {:type :simple-file
   :filename (get-in cfg [:file :path])})

(defn- otel-publisher-config [otel send]
  {:type :open-telemetry
   :send send
   :url (:url otel)
   :max-items (:max-items otel)
   :publish-delay (:publish-delay otel)
   :http-opts (:http-opts otel)})

(defn- publisher-config [cfg]
  (let [publishers (cond-> []
                     (:file-enabled cfg) (conj (file-publisher-config cfg))
                     (get-in cfg [:otel :enabled])
                     (into (mapv #(otel-publisher-config (:otel cfg) %)
                                 (get-in cfg [:otel :send]))))]
    (case (count publishers)
      0 nil
      1 (first publishers)
      {:type :multi
       :publishers publishers})))

(defn- ensure-parent-dir!
  [path]
  (when-let [parent (.getParentFile (io/file path))]
    (.mkdirs parent)))

(defn- rotated-path [path n]
  (str path "." n))

(defn- rotate-logs!
  [{:keys [path max-bytes max-files]}]
  (let [file (io/file path)]
    (when (and (pos? max-bytes)
               (pos? max-files)
               (.exists file)
               (>= (.length file) max-bytes))
      (doseq [n (range max-files 0 -1)]
        (let [src (io/file (if (= n 1) path (rotated-path path (dec n))))
              dst (io/file (rotated-path path n))]
          (when (.exists src)
            (io/delete-file dst true)
            (.renameTo src dst)))))))

(defn- sensitive-key? [k]
  (let [text (str/lower-case (cond
                               (keyword? k) (name k)
                               (string? k) k
                               :else (str k)))]
    (boolean (or (some #(str/includes? text %) sensitive-key-fragments)
                 (re-find #"(^|[-_/.:])key($|[-_/.:])" text)))))

(declare mask-sensitive)

(defn- mask-map [m]
  (into (empty m)
        (map (fn [[k v]]
               [k (if (sensitive-key? k)
                    "***REDACTED***"
                    (mask-sensitive v))]))
        m))

(defn- mask-sensitive [value]
  (cond
    (map? value) (mask-map value)
    (vector? value) (mapv mask-sensitive value)
    (set? value) (set (map mask-sensitive value))
    (sequential? value) (doall (map mask-sensitive value))
    (string? value) (reduce (fn [text pattern]
                              (str/replace text pattern "Bearer ***REDACTED***"))
                            value
                            sensitive-value-patterns)
    :else value))

(defn enabled?
  []
  (boolean (:stop-fn @publisher-state)))

(defn current-config
  []
  (:config @publisher-state))

(defn health-check
  []
  {:healthy true
   :enabled (enabled?)
   :path (get-in @publisher-state [:config :file :path])
   :otel (select-keys (get-in @publisher-state [:config :otel])
                      [:enabled :url :send :publish-delay :max-items])})

(defn log!
  ([event-name] (log! event-name {}))
  ([event-name attrs]
   (when (enabled?)
     (mulog/log* mulog-core/*default-logger*
                 event-name
                 (apply list
                        :mulog/namespace "agent.logging"
                        (mapcat identity
                                (into (sorted-map)
                                      (for [[k v] attrs]
                                        (let [k* (cond
                                                   (keyword? k) k
                                                   (string? k) (keyword k)
                                                   :else (keyword (str k)))]
                                          [k*
                                           (if (sensitive-key? k*)
                                             "***REDACTED***"
                                             (mask-sensitive v))])))))))))

(defn log-error!
  ([event-name error] (log-error! event-name error {}))
  ([event-name error attrs]
   (log! event-name
         (merge attrs
                {:error/message (.getMessage error)
                 :error/class (.getName (class error))
                 :error/data (some-> (ex-data error) mask-sensitive pr-str)}))))

(defn log-system-event!
  [event]
  (log! :agent/event
        {:event/type (some-> (:event-type event) name)
         :entity/type (some-> (:entity-type event) name)
         :entity/id (:entity-id event)
         :request/id (:request-id event)
         :payload (:payload event)}))

(defn start!
  [cfg]
  (let [cfg* (normalize-config cfg)]
    (if-not (:enabled cfg*)
      (do
      (when-let [stop-fn (:stop-fn @publisher-state)]
        (Thread/sleep 50)
        (stop-fn)
        (mulog/stop-all-publishers!)
        (reset! publisher-state nil))
      {:enabled false})
      (locking publisher-state
        (let [{existing :config
               stop-fn :stop-fn} @publisher-state]
          (when (and stop-fn (not= existing cfg*))
            (Thread/sleep 50)
            (stop-fn)
            (mulog/stop-all-publishers!)
            (reset! publisher-state nil))
          (or @publisher-state
              (do
                (when (:file-enabled cfg*)
                  (ensure-parent-dir! (get-in cfg* [:file :path]))
                  (rotate-logs! (:file cfg*)))
                (mulog/set-global-context! (:context cfg*))
                (let [stop-fn* (mulog/start-publisher! (publisher-config cfg*))]
                  (reset! publisher-state {:config cfg*
                                           :stop-fn stop-fn*})
                  (log! :agent.logging/started
                        {:path (get-in cfg* [:file :path])
                         :otel/enabled (get-in cfg* [:otel :enabled])
                         :otel/url (get-in cfg* [:otel :url])})
                  @publisher-state))))))))

(defn stop!
  []
  (when-let [stop-fn (:stop-fn @publisher-state)]
    (log! :agent.logging/stopping
          {:path (get-in @publisher-state [:config :file :path])})
    (Thread/sleep 50)
    (stop-fn)
    (mulog/stop-all-publishers!)
    (reset! publisher-state nil))
  nil)
