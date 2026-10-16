(ns agent.ui.routes
  "Navigation metadata and route normalization shared by shell and session views."
  (:require
   [clojure.string :as str]))

(def ^:no-doc tabs
  [{:key :overview :label "Overview" :tone "sky"}
   {:key :chat :label "Chat" :tone "black"}
   {:key :cron :label "Cron" :tone "violet"}
   {:key :tools :label "Tools" :tone "graphite"}
   {:key :memory :label "Memory" :tone "mint"}
   {:key :magi :label "MAGI" :tone "yellow"}
   {:key :logs :label "Logs" :tone "red"}])

(defn ^:no-doc normalize-tab [value]
  (let [tab (some-> value name str/lower-case keyword)]
    (if (some #(= tab (:key %)) tabs) tab :chat)))

(defn ^:no-doc route-path [{:keys [tab session-id]}]
  (case (normalize-tab tab)
    :overview "/overview"
    :chat (if session-id (str "/chat/" session-id) "/chat")
    :cron "/cron"
    :tools "/tools"
    :memory "/memory"
    :magi "/magi"
    :logs "/logs"
    "/chat"))

(defn ^:no-doc route-state-from-path [path]
  (let [[segment id] (->> (str/split (or path "") #"/")
                          (remove str/blank?)
                          (take 2))]
    (case segment
      "overview" {:tab :overview}
      "chat" (cond-> {:tab :chat} id (assoc :session-id id))
      "cron" {:tab :cron}
      "tools" {:tab :tools}
      "memory" {:tab :memory}
      "magi" {:tab :magi}
      "logs" {:tab :logs}
      {:tab :chat})))
