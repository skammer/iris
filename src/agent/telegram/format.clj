(ns agent.telegram.format
  "Convert standard markdown to Telegram MarkdownV2.

   Headers, lists, tables, horizontal rules don't exist in MarkdownV2
   and are downgraded to plain-text equivalents."
  (:require
   [clojure.string :as str]))

(def ^:private fence-rx
  #"(?ms)^[ \t]*(?:```|~~~)([^\n`~]*)\n(.*?)\n?[ \t]*(?:```|~~~)[ \t]*$")

(def ^:private fence-marker-rx
  #"(?m)^[ \t]*(?:```|~~~)[^\n`~]*$")

(def ^:private inline-code-rx
  #"`([^`\n]+)`")

(def ^:private global-token "G")
(def ^:private local-token "L")
(def ^:private token-end "")

(def ^:private mdv2-specials
  (set "\\_*[]()~`>#+-=|{}.!"))

(defn- escape-mdv2 [s]
  (apply str
         (map (fn [ch]
                (if (contains? mdv2-specials ch)
                  (str "\\" ch)
                  (str ch)))
              (str s))))

(defn- escape-code [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "`" "\\`")))

(defn- escape-link-url [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace ")" "\\)")))

(defn- stash! [acc token rendered]
  (let [n (count @acc)]
    (vswap! acc conj rendered)
    (str token n token-end)))

(defn- extract-rendered [s rx token acc render]
  (str/replace s rx
               (fn [m]
                 (stash! acc token (render m)))))

(defn- reinsert-tokens [s token acc]
  (str/replace s
               (re-pattern (str token "(\\d+)" token-end))
               (fn [[_ idx]]
                 (get acc (Integer/parseInt idx)))))

(declare render-inline)

(defn- render-link [match]
  (let [[_ text url] match]
    (str "[" (render-inline text) "](" (escape-link-url url) ")")))

(defn- render-entity [token body]
  (str token (render-inline body) token))

(defn- render-fenced [match]
  (let [[_ lang body] match
        lang (-> (or lang "") str/trim (str/replace #"\s+" ""))]
    (str "```" (escape-code lang) "\n"
         (escape-code body)
         "\n```")))

(defn- render-inline-code [match]
  (let [[_ body] match]
    (str "`" (escape-code body) "`")))

(defn- render-inline [s]
  (let [acc (volatile! [])
        s1 (str/replace (str s)
                        #"\[([^\]\n]+)\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)"
                        (fn [m] (stash! acc local-token (render-link m))))
        s2 (str/replace s1 #"\*\*([^*\n]+?)\*\*"
                        (fn [[_ body]]
                          (stash! acc local-token (render-entity "*" body))))
        s3 (str/replace s2 #"(?<![A-Za-z0-9_])__([^_\n]+?)__(?![A-Za-z0-9_])"
                        (fn [[_ body]]
                          (stash! acc local-token (render-entity "*" body))))
        s4 (str/replace s3 #"~~([^~\n]+?)~~"
                        (fn [[_ body]]
                          (stash! acc local-token (render-entity "~" body))))
        s5 (str/replace s4 #"(?<![A-Za-z0-9*])\*(?!\s)([^*\n]+?)(?<!\s)\*(?![A-Za-z0-9*])"
                        (fn [[_ body]]
                          (stash! acc local-token (render-entity "_" body))))
        s6 (str/replace s5 #"(?<![A-Za-z0-9_])_(?!\s)([^_\n]+?)(?<!\s)_(?![A-Za-z0-9_])"
                        (fn [[_ body]]
                          (stash! acc local-token (render-entity "_" body))))]
    (reinsert-tokens (escape-mdv2 s6) local-token @acc)))

(defn- render-line [line]
  (cond
    (re-matches #"[ \t]*#{1,6}[ \t]+.+?[ \t]*" line)
    (let [[_ title] (re-matches #"[ \t]*#{1,6}[ \t]+(.+?)[ \t]*" line)]
      (str "*" (render-inline title) "*"))

    (re-matches #"[ \t]*([-*_])(?:[ \t]*\1){2,}[ \t]*" line)
    (escape-mdv2 "---")

    (re-matches #"([ \t]*)[-*+][ \t]+.+?[ \t]*" line)
    (let [[_ indent body] (re-matches #"([ \t]*)[-*+][ \t]+(.+?)[ \t]*" line)]
      (str indent "• " (render-inline body)))

    (re-matches #"[ \t]*>[ \t]?.*" line)
    (let [body (str/replace line #"^[ \t]*>[ \t]?" "")]
      (str ">" (render-inline body)))

    :else
    (render-inline line)))

(defn- blockquote-parts [line]
  (when-let [[_ body] (re-matches #"[ \t]*>[ \t]?(.*)" line)]
    body))

(defn- fence-marker [line]
  (when-let [[_ lang] (re-matches #"[ \t]*(?:```|~~~)([^\n`~]*)[ \t]*" line)]
    (-> (or lang "") str/trim (str/replace #"\s+" ""))))

(defn- render-blockquote-fence-open [lang]
  (str ">```" (escape-code lang)))

(defn- render-blockquote-code-line [line]
  (if-let [body (blockquote-parts line)]
    (str ">" (escape-code body))
    (escape-code line)))

(defn- render-lines [s]
  (let [lines (str/split (str s) #"\n" -1)]
    (loop [remaining lines
           in-blockquote-code? false
           acc []]
      (if-let [line (first remaining)]
        (if in-blockquote-code?
          (let [body (blockquote-parts line)]
            (if-let [_lang (and body (fence-marker body))]
              (recur (rest remaining) false (conj acc ">```"))
              (recur (rest remaining) true (conj acc (render-blockquote-code-line line)))))
          (let [body (blockquote-parts line)]
            (if-let [lang (and body (fence-marker body))]
              (recur (rest remaining) true (conj acc (render-blockquote-fence-open lang)))
              (recur (rest remaining) false (conj acc (render-line line))))))
        (str/join "\n" acc)))))

(defn- close-streaming-markers
  "Appends synthetic closers for trailing unclosed openers (code fence,
   inline backtick, **bold**, ~~strike~~). On the next flush the markers
   are recomputed, so as more content arrives the rendered output extends
   instead of reflowing — content never appears, retracts, then reappears
   when a closing marker finally streams in."
  [s]
  (let [s (if (odd? (count (re-seq fence-marker-rx s)))
            (str s "\n```")
            s)
        non-fenced (str/replace s fence-rx "")
        s (if (odd? (count (re-seq #"`" non-fenced)))
            (str s "`")
            s)
        plain (str/replace (str/replace s fence-rx "") inline-code-rx "")
        s (if (odd? (count (re-seq #"\*\*" plain)))
            (str s "**")
            s)
        plain (str/replace (str/replace s fence-rx "") inline-code-rx "")
        s (if (odd? (count (re-seq #"~~" plain)))
            (str s "~~")
            s)]
    s))

(defn md->markdown-v2
  "Renders standard markdown as Telegram MarkdownV2.

   Streaming-safe: synthesizes closing markers for trailing unclosed
   openers so partial input renders as well-formed MarkdownV2. The closer is
   recomputed on each call, so successive flushes monotonically extend
   the rendered output instead of reflowing once a closer arrives."
  [s]
  (when (some? s)
    (let [s0 (close-streaming-markers s)
          acc (volatile! [])
          s1 (extract-rendered s0 fence-rx global-token acc render-fenced)
          s2 (extract-rendered s1 inline-code-rx global-token acc render-inline-code)
          s3 (render-lines s2)]
      (reinsert-tokens s3 global-token @acc))))

(defn safe-md->markdown-v2
  "Like md->markdown-v2 but never throws. On failure returns nil so callers can
   fall back to plain text."
  [s]
  (try
    (md->markdown-v2 s)
    (catch Throwable _ nil)))

(defn chunk-markdown
  "Splits raw markdown into chunks ≤ max-len characters, preferring
   paragraph (\\n\\n) then line then word boundaries. The post-render
   MarkdownV2 may exceed max-len when escapes are added — callers that
   send to Telegram should leave headroom below 4096."
  [s max-len]
  (let [s (str s)]
    (if (<= (count s) max-len)
      [s]
      (loop [remaining s
             acc []]
        (if (<= (count remaining) max-len)
          (conj acc remaining)
          (let [head (subs remaining 0 max-len)
                split (or (str/last-index-of head "\n\n")
                          (str/last-index-of head "\n")
                          (str/last-index-of head " ")
                          max-len)
                taken (subs remaining 0 split)
                rest (str/triml (subs remaining split))]
            (recur rest (conj acc taken))))))))
