(ns agent.logging
  "Minimal μ/log bootstrap and helpers."
  (:require
   [clojure.java.io :as io]
   [com.brunobonacci.mulog :as mulog]
   [com.brunobonacci.mulog.core :as mulog-core]))

(def ^:private default-path "logs/clj-agent.log")
(defonce ^:private publisher-state (atom nil))

(defn- normalize-config
  [cfg]
  (let [enabled? (not= false (:enabled cfg))
        path (or (get-in cfg [:file :path])
                 (get-in cfg [:publisher :filename])
                 default-path)
        context (merge {:service-name "clj-agent"
                        :environment "local"}
                       (:context cfg))]
    {:enabled enabled?
     :file {:path path}
     :context context}))

(defn- ensure-parent-dir!
  [path]
  (when-let [parent (.getParentFile (io/file path))]
    (.mkdirs parent)))

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
                                        [(cond
                                           (keyword? k) k
                                           (string? k) (keyword k)
                                           :else (keyword (str k)))
                                         v]))))))))

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
