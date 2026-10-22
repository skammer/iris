(ns agent.ui.render
  "Shared server-rendered UI formatting helpers."
  (:require
   [agent.tools.display :as tool-display]
   [cheshire.core :as json]
   [clojure.string :as str]
   [hiccup2.core :as h]
   [agent.ui.format :as ui-format]
   [agent.ui.markdown :as ui-markdown])
  (:import
   (java.util ArrayDeque IdentityHashMap)))

(def markdown->html ui-markdown/markdown->html)
(def pretty-json ui-format/pretty-json)
(def url-encode ui-format/url-encode)
(def status-dot ui-format/status-dot)
(def short-timestamp ui-format/short-timestamp)
(def short-id ui-format/short-id)
(def format-tokens ui-format/format-tokens)
(def format-tps ui-format/format-tps)

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

(defn- markdown-html [content]
  (markdown->html content))

(defn message-content [content]
  [:iris-markdown.message-content.markdown (h/raw (markdown-html content))])

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

(declare user-message-content)

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

(defn- tool-entry-detail-node [assistant-message tool-call result-message detail-id]
  (let [args (tool-call-arguments tool-call)
        result-data (when result-message (tool-result-data result-message))]
    [:div.tool-entry__detail
     (cond-> {:id (str detail-id "-detail")}
       result-message (assoc "data-ignore-morph" true))
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
        [:div.empty "No result yet."])]]))

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
        preview (result-preview (:result result-data) (min 180 (or (:preview-chars cfg) 180)))
        detail-id (safe-dom-id "tool-entry" (or call-id
                                                (:id result-message)
                                                (str name* "-" (hash tool-call))))]
    [:details.tool-entry
     {:id detail-id
      "data-preserve-attr" "open"}
     [:summary.tool-row.tool-entry__summary
      {"data-on:click" (str "@get('/ui/chat/tool-detail?session_id="
                            (url-encode (:session-id assistant-message))
                            "&message_id=" (url-encode (:id assistant-message))
                            "&tool_call_id=" (url-encode call-id) "')")}
      [:span.tool-row__main
       (status-dot status)
       [:span.tool-row__name tool-name]
       (tool-status-node status)
       (when call-id [:span.tool-row__id.meta {:title call-id} (subs call-id 0 (min 12 (count call-id)))])]
      (when-not (str/blank? args-preview)
        [:span.tool-row__args.code args-preview])
      (when-not (str/blank? preview)
        [:span.tool-result__args.code preview])]
     [:div.tool-entry__detail {:id (str detail-id "-detail")}
      [:div.empty "Open to load call and result."]]]))

(defn- orphan-tool-entry [system message]
  (let [data (tool-result-data message)
        tool-call {:id (:tool-call-id data)
                   :name (or (:tool-name data) "tool")
                   :arguments (or (:input data) {})}]
    [:article.message.message--tool
     (tool-entry system message tool-call message)
     [:div.meta (:created-at message)]]))

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
     [:div.meta (ui-format/message-meta-text msg)]]))

(defn message
  ([msg] (message nil msg))
  ([system {:keys [id role content content-blocks tool-calls metadata] :as msg}]
   (let [meta-text (ui-format/message-meta-text msg)
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

(defn tool-detail-fragment [messages message-id call-id]
  (let [assistant-message (some #(when (= (str message-id) (str (:id %))) %) messages)
        orphan-data (when (tool-result-message? assistant-message)
                      (tool-result-data assistant-message))
        tool-call (or (some #(when (= call-id (tool-call-id %)) %)
                            (:tool-calls assistant-message))
                      (when (= call-id (:tool-call-id orphan-data))
                        {:id call-id
                         :name (or (:tool-name orphan-data) "tool")
                         :arguments (or (:input orphan-data) {})}))
        result-message (or (when orphan-data assistant-message)
                           (some #(when (and (tool-result-message? %)
                                             (= call-id (result-message-call-id %)))
                                    %)
                                 messages))
        detail-id (safe-dom-id "tool-entry" (or call-id message-id))]
    (render
     (if tool-call
       (tool-entry-detail-node assistant-message tool-call result-message detail-id)
       [:div.tool-entry__detail {:id (str detail-id "-detail")}
        [:div.empty "Tool call not found."]]))))

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
   Accepts either the message list or a pre-aggregated stats map. Returns nil
   when there is nothing to show yet."
  [messages-or-stats]
  (let [{:keys [total-tokens prompt-tokens completion-tokens cached-tokens
                context-tokens average-tps tool-calls tool-breakdown]}
        (if (map? messages-or-stats)
          messages-or-stats
          (thread-stats messages-or-stats))]
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
