(ns agent.runtime.trace
  "Privacy-safe runtime JSONL trace."
  (:require
   [agent.logging :as logging]
   [agent.util :as util]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute PosixFilePermissions)
   (java.util UUID)))

(def modes #{:none :rolling :full})
(def default-path "runtime-trace.jsonl")
(def default-rolling-max-entries 1000)

(def sensitive-key-fragments
  #{"api-key" "api_key" "authorization" "password" "secret" "token" "credential"
    "prompt" "content" "messages" "input" "args" "arguments" "result" "output"})

(defn normalize-mode [mode]
  (let [mode* (cond
                (keyword? mode) mode
                (string? mode) (keyword (str/lower-case (str/trim mode)))
                :else :none)]
    (if (contains? modes mode*) mode* :none)))

(defn resolve-trace-path [path base-dir]
  (let [path* (or (some-> path str str/trim not-empty) default-path)
        file (io/file path*)]
    (.getAbsolutePath
     (if (.isAbsolute file)
       file
       (io/file (or base-dir ".") path*)))))

(defn create-trace
  [cfg base-dir]
  (let [mode (normalize-mode (:mode cfg))
        max-entries (long (or (:rolling-max-entries cfg)
                              (:max-entries cfg)
                              default-rolling-max-entries))]
    {:mode mode
     :path (resolve-trace-path (:path cfg) base-dir)
     :rolling-max-entries (max 1 max-entries)
     :lock (Object.)}))

(defn enabled? [trace]
  (and (map? trace)
       (not= :none (:mode trace))))

(defn- sensitive-key? [k]
  (let [text (str/lower-case (cond
                               (keyword? k) (name k)
                               (string? k) k
                               :else (str k)))]
    (boolean (some #(str/includes? text %) sensitive-key-fragments))))

(declare scrub)

(defn- scrub-map [m]
  (into (empty m)
        (map (fn [[k v]]
               [k (if (sensitive-key? k)
                    "[redacted]"
                    (scrub v))]))
        m))

(defn- scrub-string [s]
  (if (> (count s) 512)
    (str "[string redacted, length " (count s) "]")
    s))

(defn scrub [value]
  (cond
    (map? value) (scrub-map value)
    (vector? value) (mapv scrub value)
    (set? value) (set (map scrub value))
    (sequential? value) (doall (map scrub value))
    (string? value) (scrub-string value)
    :else value))

(defn- ensure-parent! [path]
  (when-let [parent (.getParentFile (io/file path))]
    (.mkdirs parent)))

(defn- restrict-file-permissions! [path]
  (try
    (Files/setPosixFilePermissions (.toPath (io/file path))
                                   (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _ nil)
    (catch Exception _ nil)))

(defn- append-line! [path line]
  (ensure-parent! path)
  (spit path (str line "\n") :append true)
  (restrict-file-permissions! path))

(defn- runtime-event
  [{:keys [event-type turn-id provider model channel success error-message payload]}]
  (let [event {:id (str (UUID/randomUUID))
               :timestamp (util/now-str)
               :event-type (name event-type)
               :turn-id turn-id
               :provider provider
               :model model
               :channel channel
               :success success
               :error-message error-message
               :payload (scrub (or payload {}))}]
    (into {}
          (remove (comp nil? val))
          event)))

(defn- span-event-name [event-type]
  (keyword "agent.trace" (str event-type)))

(defn- event-duration-ms [event]
  (or (get-in event [:payload :duration-ms])
      (get-in event [:payload :latency-ms])
      0))

(defn- export-otel-span! [event]
  (when (logging/otel-traces-enabled?)
    (logging/span! (span-event-name (:event-type event))
                   (cond-> {:trace/event-id (:id event)
                            :trace/event-type (:event-type event)
                            :turn/id (:turn-id event)
                            :provider (:provider event)
                            :model (:model event)
                            :channel (:channel event)
                            :success (:success event)
                            :payload (:payload event)
                            :duration-ms (event-duration-ms event)}
                     (:error-message event) (assoc :error/message (:error-message event)))
                   {:duration-ms (event-duration-ms event)
                    :success? (:success event)})))

(defn- trim-rolling! [trace]
  (when (= :rolling (:mode trace))
    (let [path (:path trace)
          file (io/file path)]
      (when (.exists file)
        (let [lines (->> (str/split-lines (slurp file))
                         (remove str/blank?)
                         vec)
              max-entries (:rolling-max-entries trace)]
          (when (> (count lines) max-entries)
            (let [kept (subvec lines (- (count lines) max-entries))
                  tmp (str path ".tmp." (System/currentTimeMillis))]
              (spit tmp (str (str/join "\n" kept) "\n"))
              (restrict-file-permissions! tmp)
              (Files/move (.toPath (io/file tmp))
                          (.toPath file)
                          (into-array java.nio.file.CopyOption
                                      [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))))))))

(defn record-event!
  [trace {:keys [event-type turn-id provider model channel success error-message payload]}]
  (when (or (enabled? trace)
            (logging/otel-traces-enabled?))
    (let [event* (runtime-event {:event-type event-type
                                 :turn-id turn-id
                                 :provider provider
                                 :model model
                                 :channel channel
                                 :success success
                                 :error-message error-message
                                 :payload payload})]
      (export-otel-span! event*)
      (when (enabled? trace)
        (locking (:lock trace)
          (append-line! (:path trace) (json/generate-string event*))
          (trim-rolling! trace)))
      event*)))

(defn load-events
  ([trace] (load-events trace {}))
  ([trace {:keys [limit event-type contains]
           :or {limit 100}}]
   (let [path (:path trace)
         file (when path (io/file path))]
     (if-not (and file (.exists file))
       []
       (let [needle (some-> contains str str/lower-case)
             event-type* (some-> event-type name str/lower-case)]
         (->> (str/split-lines (slurp file))
              (keep (fn [line]
                      (try
                        (json/parse-string line true)
                        (catch Exception _ nil))))
              (filter (fn [event]
                        (or (nil? event-type*)
                            (= event-type* (some-> (:event-type event) str/lower-case)))))
              (filter (fn [event]
                        (or (nil? needle)
                            (str/includes? (str/lower-case (pr-str event)) needle))))
              (take-last limit)
              reverse
              vec))))))

(defn health-check [trace]
  {:healthy true
   :enabled (enabled? trace)
   :mode (:mode trace)
   :path (:path trace)
   :rolling-max-entries (:rolling-max-entries trace)
   :exists (boolean (when-let [path (:path trace)]
                      (.exists (io/file path))))})
