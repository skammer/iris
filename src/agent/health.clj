(ns agent.health
  "Process-local component health registry."
  (:require
   [agent.util :as util]
   [clojure.string :as str])
  (:import
   (java.lang.management ManagementFactory)))

(def default-components
  [:api
   :llm-provider
   :sqlite
   :broker
   :telemetry
   :runtime
   :tools
   :memory
   :channel-adapters])

(def ^:private now util/now-str)

(defn- component-id [component]
  (if (keyword? component)
    (name component)
    (str component)))

(defn- initial-component [updated-at]
  {:status "starting"
   :updated-at updated-at
   :last-ok nil
   :last-error nil
   :restart-count 0})

(defn create-registry
  ([] (create-registry default-components))
  ([components]
   (let [started-at (System/nanoTime)
         updated-at (now)]
     {:started-at started-at
      :components (atom (into (sorted-map)
                              (map (fn [component]
                                     [(component-id component)
                                      (initial-component updated-at)]))
                              components))})))

(defn- update-component!
  [registry component f]
  (when registry
    (let [updated-at (now)
          id (component-id component)]
      (get (swap! (:components registry)
                  (fn [components]
                    (let [entry (get components id (initial-component updated-at))]
                      (assoc components id (assoc (f entry updated-at)
                                                  :updated-at updated-at)))))
           id))))

(defn- error-text [error]
  (cond
    (nil? error) nil
    (instance? Throwable error) (.getMessage ^Throwable error)
    :else (str error)))

(defn- pid []
  (try
    (Long/parseLong (first (str/split (.getName (ManagementFactory/getRuntimeMXBean)) #"@")))
    (catch Exception _
      0)))

(defn mark-ok!
  [registry component]
  (update-component!
   registry
   component
   (fn [entry updated-at]
     (assoc entry
            :status "ok"
            :last-ok updated-at
            :last-error nil))))

(defn mark-error!
  [registry component error]
  (update-component!
   registry
   component
   (fn [entry _updated-at]
     (assoc entry
            :status "error"
            :last-error (error-text error)))))

(defn bump-restart!
  [registry component]
  (update-component!
   registry
   component
   (fn [entry _updated-at]
     (update entry :restart-count (fnil inc 0)))))

(defn snapshot
  [registry]
  {:pid (pid)
   :updated-at (now)
   :uptime-seconds (if registry
                     (quot (- (System/nanoTime) (:started-at registry)) 1000000000)
                     0)
   :components (if registry
                 @(:components registry)
                 (sorted-map))})
