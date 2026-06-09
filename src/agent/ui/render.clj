(ns agent.ui.render
  "Shared server-rendered UI formatting helpers."
  (:require
   [agent.tools.display :as tool-display]
   [cheshire.core :as json]
   [clojure.string :as str]
   [hiccup2.core :as h])
  (:import
   (java.net URLEncoder)
   (java.time Instant)
   (java.util ArrayDeque IdentityHashMap)
   (org.commonmark.parser Parser)
   (org.commonmark.renderer.html HtmlRenderer)
   (org.jsoup Jsoup)
   (org.jsoup.nodes Document$OutputSettings)
   (org.jsoup.safety Safelist)))

(def ^:private max-trusted-fragments 4096)
(defonce ^:private trusted-fragments (IdentityHashMap.))
(defonce ^:private trusted-fragment-order (ArrayDeque.))

(defn- remember-trusted-fragment! [html]
  (locking trusted-fragments
    (.put trusted-fragments html true)
    (.addLast trusted-fragment-order html)
    (while (> (.size trusted-fragment-order) max-trusted-fragments)
      (.remove trusted-fragments (.removeFirst trusted-fragment-order))))
  html)

(defn- trusted-fragment? [html]
  (locking trusted-fragments
    (.containsKey trusted-fragments html)))

(defn render [node]
  (remember-trusted-fragment! (str (h/html node))))

(defn render-many [& nodes]
  (remember-trusted-fragment! (apply str (map render nodes))))

(defn trusted-fragment [html]
  (when-not (and (string? html) (trusted-fragment? html))
    (throw (ex-info "trusted-fragment requires output from render or render-many"
                    {:type :untrusted-html-fragment})))
  (h/raw html))

(defonce ^:private markdown-parser
  (.build (Parser/builder)))

(defonce ^:private markdown-renderer
  (-> (HtmlRenderer/builder)
      (.escapeHtml true)
      (.build)))

(defonce ^:private markdown-safelist
  (doto (Safelist/none)
    (.addTags (into-array String ["a" "blockquote" "br" "code" "del" "em" "h1" "h2"
                                  "h3" "h4" "h5" "h6" "hr" "li" "ol" "p" "pre"
                                  "strong" "ul"]))
    (.addAttributes "a" (into-array String ["href" "title"]))
    (.addAttributes "code" (into-array String ["class"]))
    (.addProtocols "a" "href" (into-array String ["http" "https" "mailto"]))))

(defonce ^:private markdown-output-settings
  (doto (Document$OutputSettings.)
    (.prettyPrint false)))

(defn- markdown-input [content]
  (-> (str content)
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- markdown-html [content]
  (let [html (.render markdown-renderer (.parse markdown-parser (markdown-input content)))]
    (Jsoup/clean html "" markdown-safelist markdown-output-settings)))

(defn message-content [content]
  [:div.message-content.markdown (h/raw (markdown-html content))])

(defn- source-url [{:keys [type value media-type]}]
  (case (keyword type)
    :url value
    :base64 (str "data:" (or media-type "application/octet-stream") ";base64," value)
    nil))

(defn- safe-media-url [url media-prefix]
  (let [url* (str url)
        lower (str/lower-case url*)]
    (when (or (str/starts-with? lower "https://")
              (str/starts-with? lower "http://")
              (str/starts-with? lower (str "data:" media-prefix "/")))
      url*)))

(defn- media-caption [block fallback]
  (or (:alt block) (:filename block) fallback))

(declare user-message-content)

(defn- media-block [block]
  (let [type (keyword (:type block))
        url (source-url (:source block))]
    (case type
      :image
      (if-let [src (safe-media-url url "image")]
        [:figure.message-media
         [:img.message-media__image {:src src
                                     :alt (media-caption block "image")
                                     :loading "lazy"}]
         (when-let [caption (media-caption block nil)]
           [:figcaption caption])]
        (message-content (media-caption block "[image]")))

      :audio
      (if-let [src (safe-media-url url "audio")]
        [:figure.message-media
         [:audio.message-media__audio {:src src :controls true :preload "metadata"}]
         (when-let [caption (media-caption block nil)]
           [:figcaption caption])]
        (message-content (media-caption block "[audio]")))

      :video
      (if-let [src (safe-media-url url "video")]
        [:figure.message-media
         [:video.message-media__video {:src src :controls true :preload "metadata"}]
         (when-let [caption (media-caption block nil)]
           [:figcaption caption])]
        (message-content (media-caption block "[video]")))

      :file
      (message-content (media-caption block "[file]"))

      nil)))

(defn- rich-message-content [role content content-blocks]
  (let [blocks (seq content-blocks)]
    (if-not blocks
      (if (= "user" role)
        (user-message-content content)
        (message-content content))
      (into [:div.message-rich-content]
            (keep (fn [block]
                    (case (keyword (:type block))
                      :text (if (= "user" role)
                              (user-message-content (:text block))
                              (message-content (:text block)))
                      :thinking nil
                      (:image :audio :video :file) (media-block block)
                      nil))
                  blocks)))))

(defn- thinking-id [value]
  (when-not (str/blank? (str value))
    (str "message-thinking-"
         (str/replace (str value) #"[^A-Za-z0-9_-]" "-"))))

(defn thinking-content
  ([content] (thinking-content content nil))
  ([content id-value]
   (when-not (str/blank? (str content))
     [:details.message-thinking
      (cond-> {"data-preserve-attr" "open"}
        id-value (assoc :id (thinking-id id-value)))
      [:summary "thinking"]
      [:div.code (str content)]])))

(defn- content-block-thinking [content-blocks]
  (not-empty
   (apply str
          (keep (fn [block]
                  (when (= "thinking" (some-> (:type block) name))
                    (:text block)))
                content-blocks))))

(def ^:private think-tag-re #"(?is)<think>\s*(.*?)\s*</think>")

(defn- tagged-thinking [content]
  (not-empty
   (str/join "\n\n" (map second (re-seq think-tag-re (str content))))))

(defn- strip-think-tags [content]
  (str/trim (str/replace (str content) think-tag-re "")))

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

(defn format-tokens
  "Compact human-readable token count: 950 -> \"950\", 12345 -> \"12.3k\", 2300000 -> \"2.3M\"."
  [n]
  (let [n (long (or n 0))]
    (cond
      (>= n 1000000) (format "%.1fM" (/ (double n) 1e6))
      (>= n 1000) (format "%.1fk" (/ (double n) 1e3))
      :else (str n))))

(defn- usage-tokens
  "Total tokens for one message's usage map, if present."
  [metadata]
  (some-> (:usage metadata) :tokens long))

(defn- usage-cached-tokens
  [metadata]
  (some-> (:usage metadata) :cached-tokens long))

(defn- message-meta-suffix
  "Per-message stats badge text: token count for the turn + tool-call count."
  [metadata tool-calls]
  (let [tok (usage-tokens metadata)
        cached (usage-cached-tokens metadata)
        n-tools (count tool-calls)]
    (str (when (and tok (pos? tok)) (str " | " (format-tokens tok) " tok"))
         (when (and cached (pos? cached)) (str " | " (format-tokens cached) " cache"))
         (when (pos? n-tools) (str " | " n-tools " tool" (when (> n-tools 1) "s"))))))

(defn message
  ([msg] (message nil msg))
  ([system {:keys [id role content created-at content-blocks tool-calls metadata excluded-from-context?] :as msg}]
   (let [meta-text (str created-at
                        (when (:queued metadata) " | queued")
                        (when excluded-from-context? " | out-of-context")
                        (message-meta-suffix metadata tool-calls))
         thinking (or (:thinking metadata)
                      (content-block-thinking content-blocks)
                      (tagged-thinking content))
         content* (if (and (= "assistant" role) thinking)
                    (strip-think-tags content)
                    content)]
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
        (when (= "assistant" role)
          (thinking-content thinking id))
        (rich-message-content role content* content-blocks)
        [:div.meta meta-text]]))))

(defn thread-stats
  "Aggregate per-thread usage/tool stats from the FULL message list.
   Compaction-safe: `list-messages` returns every message (compaction never
   deletes rows), so this sums the whole thread.
   - :total/:prompt/:completion/:cached-tokens — cumulative SUM over every turn
     (= total tokens billed across the thread's life).
   - :context-tokens — size of the live context window, taken from the most
     recent turn's prompt+completion (already reflects any compaction cut).
   - :tool-calls — total tool calls; :tool-breakdown — [name count] desc."
  [messages]
  (let [usages (keep #(get-in % [:metadata :usage]) messages)
        sum-key (fn [k] (reduce (fn [acc u] (+ acc (long (or (get u k) 0)))) 0 usages))
        latest (last usages)
        tool-names (for [m messages
                         tc (:tool-calls m)
                         :let [nm (get-in tc [:function :name])]
                         :when nm]
                     nm)
        breakdown (->> tool-names
                       frequencies
                       (sort-by (juxt (comp - val) key))
                       (mapv (fn [[nm n]] [nm n])))]
    {:total-tokens (sum-key :tokens)
     :prompt-tokens (sum-key :prompt-tokens)
     :completion-tokens (sum-key :completion-tokens)
     :cached-tokens (sum-key :cached-tokens)
     :context-tokens (when latest
                       (+ (long (or (:prompt-tokens latest) 0))
                          (long (or (:completion-tokens latest) 0))))
     :tool-calls (count tool-names)
     :tool-breakdown breakdown}))

(defn thread-stats-bar
  "Compact per-thread stats strip rendered live inside the message panel.
   Returns nil when there is nothing to show yet."
  [messages]
  (let [{:keys [total-tokens prompt-tokens completion-tokens cached-tokens
                context-tokens tool-calls tool-breakdown]} (thread-stats messages)]
    (when (or (pos? total-tokens) (pos? tool-calls))
      [:div.thread-stats {:aria-label "Thread usage"}
       [:span.thread-stats__group
        {:title (str "cumulative across thread — prompt " prompt-tokens
                     " · completion " completion-tokens
                     " · cached " cached-tokens)}
        [:span.thread-stats__label "Σ"]
        [:span.thread-stats__value (format-tokens total-tokens)]
        [:span.thread-stats__unit "tok"]]
       (when (and context-tokens (pos? context-tokens))
         [:span.thread-stats__group
          {:title "approximate tokens in the current context window (post-compaction)"}
          [:span.thread-stats__label "ctx"]
          [:span.thread-stats__value (str "~" (format-tokens context-tokens))]])
       [:span.thread-stats__group
        {:title (when (seq tool-breakdown)
                  (str/join " · " (map (fn [[nm n]] (str nm " ×" n)) tool-breakdown)))}
        [:span.thread-stats__label "tools"]
        [:span.thread-stats__value (str tool-calls)]
        (when (seq tool-breakdown)
          (into [:span.thread-stats__breakdown]
                (for [[nm n] tool-breakdown]
                  [:span.thread-stats__tool nm [:span.thread-stats__tool-n (str "×" n)]])))]])))

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
