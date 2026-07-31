(ns agent.ui.render
  "Shared server-rendered UI formatting helpers."
  (:require
   [agent.tools.display :as tool-display]
   [cheshire.core :as json]
   [clojure.string :as str]
   [hiccup2.core :as h])
  (:import
   (java.net URLEncoder)
   (java.util ArrayDeque IdentityHashMap)
   (org.commonmark.ext.footnotes FootnotesExtension)
   (org.commonmark.ext.gfm.strikethrough StrikethroughExtension)
   (org.commonmark.ext.gfm.tables TablesExtension)
   (org.commonmark.ext.task.list.items TaskListItemsExtension)
   (org.commonmark.node HtmlInline)
   (org.commonmark.parser Parser)
   (org.commonmark.parser.delimiter DelimiterProcessor)
   (org.commonmark.renderer.html AttributeProvider AttributeProviderFactory
                                 HtmlRenderer)
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

(defn- inline-wrap-processor
  "DelimiterProcessor that brackets ch-delimited runs (==x==, ||x||, ++x++)
   with raw HTML; the jsoup pass whitelists the emitted tags and unmatched
   runs stay literal."
  [ch open-html close-html]
  (reify DelimiterProcessor
    (getOpeningCharacter [_] ch)
    (getClosingCharacter [_] ch)
    (getMinLength [_] 2)
    (process [_ opening closing]
      (if (and (>= (.length opening) 2) (>= (.length closing) 2))
        (do
          (.insertAfter (.getOpener opening)
                        (doto (HtmlInline.) (.setLiteral open-html)))
          (.insertBefore (.getCloser closing)
                         (doto (HtmlInline.) (.setLiteral close-html)))
          2)
        0))))

(defonce ^:private markdown-extensions
  [(TablesExtension/create)
   (StrikethroughExtension/create)
   (TaskListItemsExtension/create)
   (FootnotesExtension/create)])

(defonce ^:private markdown-parser
  (-> (Parser/builder)
      (.extensions markdown-extensions)
      (.customDelimiterProcessor (inline-wrap-processor \= "<mark>" "</mark>"))
      (.customDelimiterProcessor (inline-wrap-processor \| "<span class=\"spoiler\">" "</span>"))
      (.customDelimiterProcessor (inline-wrap-processor \+ "<ins>" "</ins>"))
      (.build)))

(defonce ^:private markdown-renderer
  (-> (HtmlRenderer/builder)
      (.extensions markdown-extensions)
      ;; Raw HTML flows through to the jsoup clean below — the single
      ;; sanitization boundary — so whitelisted inline HTML (details, sub,
      ;; sup, u) renders instead of displaying as escaped text.
      (.escapeHtml false)
      (.attributeProviderFactory
       (reify AttributeProviderFactory
         (create [_ _context]
           (reify AttributeProvider
             (setAttributes [_ _node tag-name attributes]
               (when (= "img" tag-name)
                 (.put attributes "loading" "lazy")))))))
      (.build)))

(defonce ^:private markdown-safelist
  (doto (Safelist/none)
    (.addTags (into-array String ["a" "blockquote" "br" "code" "del" "details"
                                  "em" "h1" "h2" "h3" "h4" "h5" "h6" "hr" "img"
                                  "input" "ins" "kbd" "li" "mark" "ol" "p" "pre"
                                  "s" "section" "span" "strong" "sub" "summary"
                                  "sup" "table" "tbody" "td" "th" "thead" "tr"
                                  "u" "ul"]))
    (.addAttributes "a" (into-array String ["href" "title" "id" "class"
                                            "data-footnote-ref"
                                            "data-footnote-backref"
                                            "data-footnote-backref-idx"
                                            "aria-label"]))
    (.addAttributes "code" (into-array String ["class"]))
    (.addAttributes "details" (into-array String ["open"]))
    (.addAttributes "img" (into-array String ["src" "alt" "title" "loading"]))
    (.addAttributes "input" (into-array String ["type" "checked" "disabled"]))
    (.addAttributes "li" (into-array String ["id"]))
    (.addAttributes "ol" (into-array String ["start"]))
    (.addAttributes "section" (into-array String ["class" "data-footnotes"]))
    (.addAttributes "span" (into-array String ["class"]))
    (.addAttributes "sup" (into-array String ["id" "class"]))
    (.addAttributes "td" (into-array String ["align"]))
    (.addAttributes "th" (into-array String ["align"]))
    ;; "#" is jsoup's anchor pseudo-protocol: keeps footnote fragment links
    ;; (#fn-1) while every other relative URL still fails validation.
    (.addProtocols "a" "href" (into-array String ["http" "https" "mailto" "#"]))
    (.addProtocols "img" "src" (into-array String ["https"]))))

(defonce ^:private markdown-output-settings
  (doto (Document$OutputSettings.)
    (.prettyPrint false)))

;; jsoup safelists cannot constrain attribute values, and a blanket
;; span[class]/a[class] would let assistant-authored raw HTML adopt the UI's
;; own classes. Allow only the classes the pipeline itself emits.
(def ^:private allowed-classes
  {"span" #{"spoiler"}
   "sup" #{"footnote-ref"}
   "a" #{"footnote-ref" "footnote-backref"}
   "section" #{"footnotes"}})

(def ^:private code-class-rx #"^language-[\w.+#-]+$")

(defn- class-allowed? [tag class]
  (or (contains? (get allowed-classes tag #{}) class)
      (and (= "code" tag) (re-matches code-class-rx class))))

(defn- restrict-classes [html]
  (let [doc (Jsoup/parseBodyFragment html)]
    (.outputSettings doc markdown-output-settings)
    (doseq [el (.select doc "[class]")]
      (let [tag (.normalName el)
            classes (->> (str/split (.attr el "class") #"\s+")
                         (filterv #(class-allowed? tag %)))]
        (if (seq classes)
          (.attr el "class" (str/join " " classes))
          (.removeAttr el "class"))))
    ;; jsoup strips a non-https src but leaves the bare element behind.
    (doseq [el (.select doc "img:not([src])")]
      (.remove el))
    (.html (.body doc))))

(defn markdown->html
  "Renders markdown (GFM tables, task lists, strikethrough, footnotes,
   ==mark==, ||spoiler||, ++ins++, whitelisted inline HTML) to sanitized
   HTML. jsoup clean is the sanitization boundary for all rendered content."
  [content]
  (-> (.render markdown-renderer (.parse markdown-parser (str content)))
      (Jsoup/clean "" markdown-safelist markdown-output-settings)
      restrict-classes))

(defn- markdown-html [content]
  (markdown->html content))

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

(declare status-dot
         user-message-content)

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
  (when-not (str/blank? (str status))
    (let [status* (str/lower-case (str status))]
      [:span {:class (str "tool-status status--" status*)} status*])))

(defn- tool-call-id [tool-call]
  (or (:id tool-call)
      (:tool-call-id tool-call)
      (:tool_call_id tool-call)))

(defn- tool-call-name [tool-call]
  (str (or (:name tool-call)
           (:tool-name tool-call)
           (:tool_name tool-call)
           (get-in tool-call [:function :name])
           "tool")))

(defn- tool-call-arguments [tool-call]
  (parse-json-or-value
   (or (:arguments tool-call)
       (:input tool-call)
       (:args tool-call)
       (get-in tool-call [:function :arguments])
       {})))

(defn- tool-result-block [{:keys [content-blocks]}]
  (first (filter #(= :tool-result (some-> (:type %) keyword))
                 content-blocks)))

(defn- parse-tool-content [content]
  (try
    (when (string? content)
      (json/parse-string content true))
    (catch Exception _ nil)))

(defn- tool-result-data [{:keys [content tool-call-id] :as message}]
  (let [block (tool-result-block message)
        block-content (:content block)
        parsed (parse-tool-content (or block-content content))]
    {:message message
     :block block
     :parsed parsed
     :tool-call-id (or tool-call-id (:tool-call-id block))
     :tool-name (or (:tool-name parsed) (:name block))
     :status (or (:status parsed) (:status block))
     :input (:input parsed)
     :result (if (contains? parsed :result)
               (:result parsed)
               (or block-content content))
     :raw-content (or block-content content)}))

(defn- done-status? [status]
  (contains? #{"ok" "completed" "succeeded" "success" "done"}
             (some-> status name str/lower-case)))

(defn- display-tool-status [result-data]
  (let [status (:status result-data)]
    (cond
      (done-status? status) "done"
      (not (str/blank? (str status))) (str/lower-case (str status))
      result-data "done"
      :else "requested")))

(defn- concise-value [value max-chars]
  (tool-display/args-preview value max-chars))

(defn- result-preview [value max-chars]
  (cond
    (map? value)
    (or (some (fn [k]
                (when (sequential? (get value k))
                  (str (count (get value k)) " " (name k))))
              [:results :items :entries :rows :matches])
        (not-empty (concise-value value max-chars)))

    (sequential? value)
    (str (count value) " items")

    :else
    (not-empty (concise-value value max-chars))))

(defn- tool-entry [system assistant-message tool-call result-message]
  (let [call-id (tool-call-id tool-call)
        name* (tool-call-name tool-call)
        args (tool-call-arguments tool-call)
        result-data (when result-message (tool-result-data result-message))
        tool-name (or (:tool-name result-data) name*)
        cfg (tool-display/channel-config system :web tool-name)
        args-preview (tool-display/args-preview args
                                                (or (:args-preview-chars cfg)
                                                    (:preview-chars cfg)
                                                    800))
        status (display-tool-status result-data)
        preview (result-preview (:result result-data) (or (:preview-chars cfg) 800))
        detail-id (safe-dom-id "tool-entry" (or call-id
                                                (:id result-message)
                                                (str name* "-" (hash tool-call))))]
    [:details.tool-entry
     {:id detail-id
      "data-preserve-attr" "open"}
     [:summary.tool-row.tool-entry__summary
      [:span.tool-row__main
       (status-dot status)
       [:span.tool-row__name tool-name]
       (tool-status-node status)
       (when call-id [:span.tool-row__id.meta call-id])]
      (when-not (str/blank? args-preview)
        [:span.tool-row__args.code args-preview])
      (when-not (str/blank? preview)
        [:span.tool-result__args.code preview])]
     [:div.tool-entry__detail
      [:section.tool-entry__section
       [:h3 "Call"]
       [:pre.tool-detail__pre.code
        (pretty-json {:message-id (:id assistant-message)
                      :created-at (:created-at assistant-message)
                      :tool-call tool-call
                      :arguments args})]]
      [:section.tool-entry__section
       [:h3 "Result"]
       (if result-data
         [:pre.tool-detail__pre.code
          (pretty-json {:message-id (get-in result-data [:message :id])
                        :created-at (get-in result-data [:message :created-at])
                        :tool-call-id (:tool-call-id result-data)
                        :status (:status result-data)
                        :content-block (:block result-data)
                        :parsed (:parsed result-data)
                        :result (:result result-data)
                        :raw-content (:raw-content result-data)})]
         [:div.empty "No result yet."])]]]))

(defn- orphan-tool-entry [system message]
  (let [data (tool-result-data message)
        tool-call {:id (:tool-call-id data)
                   :name (or (:tool-name data) "tool")
                   :arguments (or (:input data) {})}]
    [:article.message.message--tool
     (tool-entry system message tool-call message)
     [:div.meta (:created-at message)]]))

(def ^:private status-dot-classes
  {"running" "dot--live"
   "requested" "dot--live"
   "completed" "dot--ok"
   "succeeded" "dot--ok"
   "success" "dot--ok"
   "ok" "dot--ok"
   "done" "dot--ok"
   "failed" "dot--err"
   "error" "dot--err"
   "cancelled" "dot--err"
   "denied" "dot--err"
   "pending" "dot--warn"
   "approved" "dot--ok"})

(defn status-dot
  "Square status indicator, colored by status keyword/string. Stale items
   always show as warning regardless of their nominal status."
  ([status] (status-dot status nil))
  ([status {:keys [stale?]}]
   [:span.dot {:class (if stale?
                        "dot--warn"
                        (get status-dot-classes (some-> status name str/lower-case) "dot--idle"))
               :aria-hidden "true"}]))

(defn short-timestamp
  "Compact 'MM-DD HH:MM' rendering of an ISO-8601 timestamp for list rows."
  [value]
  (let [s (str value)]
    (if-let [[_ mm-dd hh-mm] (re-find #"^\d{4}-(\d{2}-\d{2})T(\d{2}:\d{2})" s)]
      (str mm-dd " " hh-mm)
      (not-empty s))))

(def ^:private uuid-rx
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn short-id
  "First segment of a UUID for display — pair with a :title attribute
   carrying the full value. Non-UUID identifiers pass through unchanged."
  [value]
  (let [s (str value)]
    (if (re-matches uuid-rx s)
      (subs s 0 8)
      s)))

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

(defn- usage-tps
  [metadata]
  (let [{:keys [completion-tokens duration-ms]} (:usage metadata)]
    (when (and (number? completion-tokens)
               (pos? completion-tokens)
               (number? duration-ms)
               (pos? duration-ms))
      (/ (* 1000.0 (double completion-tokens))
         (double duration-ms)))))

(defn format-tps [value]
  (format "%.1f" (double value)))

(defn- message-meta-suffix
  "Per-message stats badge text: token count for the turn + tool-call count."
  [metadata tool-calls]
  (let [tok (usage-tokens metadata)
        cached (usage-cached-tokens metadata)
        tps (usage-tps metadata)
        n-tools (count tool-calls)]
    (str (when (and tok (pos? tok)) (str " | " (format-tokens tok) " tok"))
         (when (and cached (pos? cached)) (str " | " (format-tokens cached) " cache"))
         (when tps (str " | " (format-tps tps) " t/s"))
         (when (pos? n-tools) (str " | " n-tools " tool" (when (> n-tools 1) "s"))))))

(defn- message-meta-text
  [{:keys [created-at metadata excluded-from-context? tool-calls]}]
  (str created-at
       (when (:queued metadata) " | queued")
       (when excluded-from-context? " | out-of-context")
       (message-meta-suffix metadata tool-calls)))

(defn- tool-turn-message
  [system {:keys [id role content content-blocks tool-calls metadata] :as msg} results-by-id]
  (let [thinking (or (:thinking metadata)
                     (content-block-thinking content-blocks)
                     (tagged-thinking content))
        content* (if thinking (strip-think-tags content) content)
        content-visible? (not (str/blank? (str content*)))]
    [:article.message.message--assistant.message--tool-turn
     (when (or content-visible? thinking)
       (list
        [:div.message-role {:class role} role]
        (thinking-content thinking id)
        (when content-visible? (message-content content*))))
     [:div.tool-calls
      (for [tc tool-calls]
        (tool-entry system msg tc (get results-by-id (tool-call-id tc))))]
     [:div.meta (message-meta-text msg)]]))

(defn message
  ([msg] (message nil msg))
  ([system {:keys [id role content content-blocks tool-calls metadata] :as msg}]
   (let [meta-text (message-meta-text msg)
         thinking (or (:thinking metadata)
                      (content-block-thinking content-blocks)
                      (tagged-thinking content))
         content* (if (and (= "assistant" role) thinking)
                    (strip-think-tags content)
                    content)]
     (cond
       (= role "tool")
       (orphan-tool-entry system msg)

       (seq tool-calls)
       (tool-turn-message system msg {})

       :else
       [:article.message {:class (str "message--" role)}
        [:div.message-role {:class role} role]
        (when (= "assistant" role)
          (thinking-content thinking id))
        (rich-message-content role content* content-blocks)
        [:div.meta meta-text]]))))

(defn- tool-result-message? [message]
  (= "tool" (:role message)))

(defn- result-message-call-id [message]
  (:tool-call-id (tool-result-data message)))

(defn- consume-tool-results [messages call-ids]
  (loop [remaining (seq messages)
         results {}]
    (if-let [message* (first remaining)]
      (let [result-id (when (tool-result-message? message*)
                        (result-message-call-id message*))]
        (if (and result-id (contains? call-ids result-id))
          (recur (next remaining) (assoc results result-id message*))
          {:results results
           :remaining remaining}))
      {:results results
       :remaining nil})))

(defn message-list
  [system messages]
  (loop [remaining (seq messages)
         nodes []]
    (if-let [msg (first remaining)]
      (if-let [tool-calls (seq (:tool-calls msg))]
        (let [call-ids (set (keep tool-call-id tool-calls))
              {:keys [results remaining]} (consume-tool-results (next remaining) call-ids)]
          (recur remaining (conj nodes (tool-turn-message system msg results))))
        (recur (next remaining) (conj nodes (message system msg))))
      nodes)))

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
        timed-usages (filter #(and (pos? (long (or (:completion-tokens %) 0)))
                                   (pos? (long (or (:duration-ms %) 0))))
                             usages)
        timed-completion-tokens (reduce + (map :completion-tokens timed-usages))
        timed-duration-ms (reduce + (map :duration-ms timed-usages))
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
     :average-tps (when (pos? timed-duration-ms)
                    (/ (* 1000.0 (double timed-completion-tokens))
                       (double timed-duration-ms)))
     :tool-calls (count tool-names)
     :tool-breakdown breakdown}))

(defn thread-stats-bar
  "Compact per-thread stats strip rendered live inside the message panel.
   Returns nil when there is nothing to show yet."
  [messages]
  (let [{:keys [total-tokens prompt-tokens completion-tokens cached-tokens
                context-tokens average-tps tool-calls tool-breakdown]} (thread-stats messages)]
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
       (when average-tps
         [:span.thread-stats__group
          {:title "weighted output speed: completion tokens / total model call time"}
          [:span.thread-stats__label "avg"]
          [:span.thread-stats__value (format-tps average-tps)]
          [:span.thread-stats__unit "t/s"]])
       [:span.thread-stats__group
        {:title (when (seq tool-breakdown)
                  (str/join " · " (map (fn [[nm n]] (str nm " ×" n)) tool-breakdown)))}
        [:span.thread-stats__label "tools"]
        [:span.thread-stats__value (str tool-calls)]
        (when (seq tool-breakdown)
          (into [:span.thread-stats__breakdown]
                (for [[nm n] tool-breakdown]
                  [:span.thread-stats__tool nm [:span.thread-stats__tool-n (str "×" n)]])))]])))
