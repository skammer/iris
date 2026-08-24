(ns agent.telegram.sessions
  "Telegram chat to Iris session mapping."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [clojure.string :as str])
  (:import
   (java.time Instant LocalTime ZoneId)))

(def ^:private reset-modes #{:none :idle :daily :both})

(defn- chat-title [chat]
  (or (:title chat)
      (:username chat)
      (not-empty (str/trim (str (or (:first_name chat) "")
                            " "
                            (or (:last_name chat) ""))))
      (str (:id chat))))

(defn- session-title [chat]
  (str "Telegram: " (chat-title chat)))

(defn- normalize-mode [mode]
  (let [mode* (cond
                (keyword? mode) mode
                (string? mode) (keyword (str/lower-case mode))
                :else :none)]
    (if (contains? reset-modes mode*) mode* :none)))

(defn- reset-reason
  [policy mapping ^Instant now]
  (when (true? (get-in mapping [:metadata :session-reset-tracked?]))
    (try
      (let [mode (normalize-mode (:mode policy))
            updated-at (Instant/parse (:updated-at mapping))
            idle-minutes (long (or (:idle-minutes policy) 1440))
            at-hour (long (or (:at-hour policy) 4))
            idle? (and (#{:idle :both} mode)
                       (.isAfter now (.plusSeconds updated-at (* 60 idle-minutes))))
            zone (ZoneId/systemDefault)
            now-local (.atZone now zone)
            today-boundary (.atZone (.atTime (.toLocalDate now-local)
                                              (LocalTime/of (int at-hour) 0))
                                    zone)
            daily-boundary (if (.isAfter today-boundary now-local)
                             (.minusDays today-boundary 1)
                             today-boundary)
            daily? (and (#{:daily :both} mode)
                        (.isBefore updated-at (.toInstant daily-boundary)))]
        (cond
          idle? :idle
          daily? :daily
          :else nil))
      (catch Exception _
        nil))))

(defn ensure-session!
  ([store chat]
   (ensure-session! store chat nil (Instant/now)))
  ([store chat policy]
   (ensure-session! store chat policy (Instant/now)))
  ([store chat policy ^Instant now]
   (let [metadata {:chat chat
                   :session-reset-tracked? true}]
     (sqlite/ensure-channel-session!
      store
      {:source :telegram
       :external-chat-id (:id chat)
       :title (session-title chat)
       :metadata metadata
       :now (str now)
       :touch? true
       :rotation-reason-fn #(reset-reason policy % now)}))))

(defn reset-session!
  [store chat]
  (sqlite/reset-channel-session!
   store
    {:source :telegram
     :external-chat-id (:id chat)
     :title (session-title chat)
     :metadata {:chat chat
                :session-reset-tracked? true}}))
