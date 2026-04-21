(ns agent.logging
  "Minimal μ/log bootstrap and helpers."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.brunobonacci.mulog :as mulog]
   [com.brunobonacci.mulog.core :as mulog-core]))

(def ^:private default-path "logs/clj-agent.log")
(def ^:private default-max-bytes (* 10 1024 1024))
(def ^:private default-max-files 5)
(def ^:private sensitive-key-fragments
  #{"api-key" "api_key" "authorization" "password" "secret" "token" "credential"})
(defonce ^:private publisher-state (atom nil))

(defn- normalize-config
  [cfg]
  (let [enabled? (true? (:enabled cfg))
        path (or (get-in cfg [:file :path])
                 (get-in cfg [:publisher :filename])
                 default-path)
        max-bytes (long (or (get-in cfg [:file :max-bytes]) default-max-bytes))
        max-files (long (or (get-in cfg [:file :max-files]) default-max-files))
        context (merge {:service-name "clj-agent"
                        :environment "local"}
                       (:context cfg))]
    {:enabled enabled?
     :file {:path path
            :max-bytes max-bytes
            :max-files max-files}
     :context context}))

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
    (boolean (some #(str/includes? text %) sensitive-key-fragments))))

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
   :path (get-in @publisher-state [:config :file :path])})

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
                 :error/data (some-> (ex-data error) pr-str)}))))

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
                (ensure-parent-dir! (get-in cfg* [:file :path]))
                (rotate-logs! (:file cfg*))
                (mulog/set-global-context! (:context cfg*))
                (let [stop-fn* (mulog/start-publisher!
                                {:type :simple-file
                                 :filename (get-in cfg* [:file :path])})]
                  (reset! publisher-state {:config cfg*
                                           :stop-fn stop-fn*})
                  (log! :agent.logging/started
                        {:path (get-in cfg* [:file :path])})
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
