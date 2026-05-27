(ns agent.ui.render
  "Shared server-rendered UI formatting helpers."
  (:require
   [agent.tools.display :as tool-display]
   [cheshire.core :as json]
   [clojure.string :as str]
   [hiccup2.core :as h])
  (:import
   (java.net URLEncoder)
   (java.time Instant)))

(defn render [node]
  (str (h/html node)))

(defn render-many [& nodes]
  (apply str (map render nodes)))

(defn trusted-fragment [html]
  ;; Invariant: only pass fragments produced by this namespace's render helpers.
  (h/raw html))

(defn message-content [content]
  ;; Markdown intentionally disabled; Hiccup escapes LLM/user text here.
  [:div.code (str content)])

(def ^:private slash-chip-re #"(^|[\t ])\/([A-Za-z0-9][A-Za-z0-9_-]*)")

(defn- user-message-content [content]
  (let [text (str content)
        matches (re-seq slash-chip-re text)]
    (if (empty? matches)
      (message-content text)
      (loop [idx 0
             remaining matches
             nodes []]
        (if-let [[match lead name] (first remaining)]
          (let [start (.indexOf text match idx)
                slash-start (+ start (count lead))
                end (+ start (count match))]
            (recur end
                   (rest remaining)
                   (cond-> nodes
                     (< idx slash-start) (conj (subs text idx slash-start))
                     true (conj [:span.skill-chip (str "/" name)]))))
          (into [:div.code.message-content--user]
                (cond-> nodes
                  (< idx (count text)) (conj (subs text idx)))))))))

(defn- keyword-label [value]
  (if-let [ns (namespace value)]
    (str ns "/" (name value))
    (name value)))

(defn- json-ready [value]
  (cond
    (keyword? value) (keyword-label value)
    (map? value) (into {}
                       (map (fn [[k v]]
                              [(if (keyword? k) (keyword-label k) k)
                               (json-ready v)]))
                       value)
    (set? value) (mapv json-ready value)
    (sequential? value) (mapv json-ready value)
    :else value))

(defn pretty-json [value]
  (try
    (json/generate-string (json-ready (if (string? value)
                                        (json/parse-string value true)
                                        value))
                          {:pretty true})
    (catch Exception _
      (if (string? value)
        value
        (binding [*print-namespace-maps* false]
          (pr-str value))))))

(defn url-encode [value]
  (URLEncoder/encode (str value) "UTF-8"))

(defn- safe-dom-id [prefix value]
  (str prefix "-"
       (str/replace (str value)
                    #"[^A-Za-z0-9_-]"
                    "-")))

(defn- parse-json-or-value [value]
  (try
    (if (string? value)
      (json/parse-string value true)
      value)
    (catch Exception _ value)))

(defn- tool-status-node [status]
  (when status
    [:span {:class (str "tool-status status--" status)} status]))

(defn- tool-detail-template [id title status & body]
  [:template {:id id}
   [:div.tool-detail-content
    [:div.tool-detail-content__meta
     [:span.meta title]
     (tool-status-node status)]
    body]])

(defn- tool-call [{:keys [id function]}]
  (let [{:keys [name arguments]} function
        params (parse-json-or-value arguments)
        args (tool-display/params-preview params 800)
        detail-id (safe-dom-id "tool-call-detail" (or id (str name "-" (hash arguments))))]
    [:div.tool-call
     [:button.tool-row
      {:type "button"
       "data-tool-detail" true
       "data-tool-detail-template" detail-id
       "data-tool-detail-title" (str "function: " name)
       "data-tool-detail-status" "requested"}
      [:span.tool-row__main
       [:span.tool-row__name (str "→ " name)]
       (tool-status-node "requested")
       (when id [:span.tool-row__id.meta id])]
      (when-not (str/blank? args)
        [:span.tool-row__args.code args])]
     (tool-detail-template
      detail-id
      (str "function: " name)
      "requested"
      [:h3 "Arguments"]
      [:pre.tool-detail__pre.code (pretty-json params)])]))

(defn- parse-tool-content [content]
  (try
    (when (string? content)
      (json/parse-string content true))
    (catch Exception _ nil)))

(defn- tool-result-summary
  "Builds one-line tool result summary: name, status, args preview."
  [system parsed tool-call-id]
  (let [tool-name (or (:tool-name parsed) "tool")
        status (some-> (:status parsed) name)
        cfg (tool-display/channel-config system :web tool-name)
        args (tool-display/params-preview (:input parsed)
                                          (or (:args-preview-chars cfg)
                                              (:preview-chars cfg)
                                              800))]
    [:span.tool-result__summary
     [:span.tool-result__summary-head
      [:span.tool-result__name tool-name]
      (tool-status-node status)]
     (when-not (str/blank? args)
       [:span.tool-result__args.code args])
     (when tool-call-id [:span.tool-result__id.meta tool-call-id])]))

(defn- tool-message [system {:keys [id content created-at tool-call-id]}]
  (let [parsed (parse-tool-content content)
        status (some-> (:status parsed) name)
        tool-name (or (:tool-name parsed) "tool")
        detail-id (safe-dom-id "tool-result-detail" (or id tool-call-id (hash content)))
        body (if parsed
               (pretty-json (dissoc parsed :tool-name :status))
               (pretty-json content))]
    [:article.message.message--tool
     [:div.tool-result
      [:button.tool-row.tool-result__head
       {:type "button"
        "data-tool-detail" true
        "data-tool-detail-template" detail-id
        "data-tool-detail-title" (str "tool: " tool-name)
        "data-tool-detail-status" (or status "")}
       (tool-result-summary system parsed tool-call-id)]
      (tool-detail-template
       detail-id
       (str "tool: " tool-name)
       status
       [:h3 "Result"]
       [:pre.tool-detail__pre.code body])]
     [:div.meta created-at]]))

(defn message
  ([msg] (message nil msg))
  ([system {:keys [role content created-at tool-calls metadata excluded-from-context?] :as msg}]
   (let [meta-text (str created-at
                        (when (:queued metadata) " | queued")
                        (when excluded-from-context? " | out-of-context"))]
     (cond
       (= role "tool")
       (tool-message system msg)

       (seq tool-calls)
       [:article.message.message--tool-calls
        [:div.message-role {:class role} role]
        (when (seq (str content)) (message-content content))
        [:div.tool-calls (for [tc tool-calls] (tool-call tc))]
        [:div.meta meta-text]]

       :else
       [:article.message
        [:div.message-role {:class role} role]
        (if (= "user" role)
          (user-message-content content)
          (message-content content))
        [:div.meta meta-text]]))))

(defn- now-ms []
  (.toEpochMilli (Instant/now)))

(defn- instant-ms [value]
  (when (seq (str value))
    (try
      (.toEpochMilli (Instant/parse (str value)))
      (catch Exception _ nil))))

(defn- run-last-seen-ms [run]
  (or (some-> run :heartbeat :observed-at instant-ms)
      (some-> run :started-at instant-ms)
      (some-> run :created-at instant-ms)))

(def stale-run-threshold-ms 30000)

(defn stale-run? [run]
  (and (contains? #{"requested" "running"} (:status run))
       (when-let [seen-ms (run-last-seen-ms run)]
         (> (- (now-ms) seen-ms) stale-run-threshold-ms))))
