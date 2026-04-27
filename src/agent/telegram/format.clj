(ns agent.telegram.format
  "Convert standard markdown to Telegram-flavored HTML.

   Telegram's HTML parse mode supports a small whitelist:
   <b><i><u><s><code><pre><a><blockquote><tg-spoiler>.
   Headers, lists, tables, horizontal rules don't exist in this subset
   and are downgraded to plain-text equivalents."
  (:require [clojure.string :as str]))

(def ^:private fence-rx
  #"(?ms)^[ \t]*(?:```|~~~)([^\n`~]*)\n(.*?)\n?[ \t]*(?:```|~~~)[ \t]*$")

(def ^:private fence-marker-rx
  #"(?m)^[ \t]*(?:```|~~~)[^\n`~]*$")

(def ^:private inline-code-rx
  #"`([^`\n]+)`")

(def ^:private fence-token "F")
(def ^:private inline-token "I")
(def ^:private token-end "")

(defn- html-escape [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- attr-escape [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- extract [s rx token]
  (let [acc (volatile! [])
        out (str/replace s rx
                         (fn [m]
                           (let [n (count @acc)]
                             (vswap! acc conj m)
                             (str token n token-end))))]
    [out @acc]))

(defn- render-fenced [match]
  (let [[_ lang body] match
        lang (str/trim (or lang ""))
        opening (if (seq lang)
                  (str "<pre><code class=\"language-" (attr-escape lang) "\">")
                  "<pre><code>")]
    (str opening (html-escape body) "</code></pre>")))

(defn- render-inline [match]
  (let [[_ body] match]
    (str "<code>" (html-escape body) "</code>")))

(defn- transform-headings [s]
  (str/replace s #"(?m)^[ \t]*#{1,6}[ \t]+(.+?)[ \t]*$" "<b>$1</b>"))

(defn- transform-hr [s]
  (str/replace s #"(?m)^[ \t]*([-*_])(?:[ \t]*\1){2,}[ \t]*$" "———"))

(defn- transform-bullets [s]
  (str/replace s #"(?m)^([ \t]*)[-*+][ \t]+(.+?)[ \t]*$" "$1• $2"))

(defn- transform-blockquotes [s]
  ;; Runs after html-escape, so the markdown `>` prefix is now `&gt;`.
  (let [lines (str/split-lines s)]
    (loop [[line & rest] lines
           buf []
           out []]
      (let [is-q? (and line (re-matches #"[ \t]*&gt;[ \t]?.*" line))
            stripped (when is-q? (str/replace line #"^[ \t]*&gt;[ \t]?" ""))]
        (cond
          (nil? line)
          (let [out (if (seq buf)
                      (conj out (str "<blockquote>" (str/join "\n" buf) "</blockquote>"))
                      out)]
            (str/join "\n" out))

          is-q?
          (recur rest (conj buf stripped) out)

          (seq buf)
          (recur rest [] (conj out
                               (str "<blockquote>" (str/join "\n" buf) "</blockquote>")
                               line))

          :else
          (recur rest [] (conj out line)))))))

(defn- transform-bold [s]
  (-> s
      (str/replace #"\*\*([^*\n]+?)\*\*" "<b>$1</b>")
      (str/replace #"(?<![A-Za-z0-9_])__([^_\n]+?)__(?![A-Za-z0-9_])" "<b>$1</b>")))

(defn- transform-italic [s]
  (-> s
      (str/replace #"(?<![A-Za-z0-9*])\*(?!\s)([^*\n]+?)(?<!\s)\*(?![A-Za-z0-9*])"
                   "<i>$1</i>")
      (str/replace #"(?<![A-Za-z0-9_])_(?!\s)([^_\n]+?)(?<!\s)_(?![A-Za-z0-9_])"
                   "<i>$1</i>")))

(defn- transform-strike [s]
  (str/replace s #"~~([^~\n]+?)~~" "<s>$1</s>"))

(defn- transform-links [s]
  ;; URL was already &-escaped by the body html-escape pass; only " needs
  ;; further attribute escaping here to avoid breaking out of href="...".
  (str/replace s #"\[([^\]\n]+)\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)"
               (fn [[_ text url]]
                 (str "<a href=\"" (str/replace url "\"" "&quot;") "\">" text "</a>"))))

(defn- transform-inline-marks [s]
  (-> s transform-bold transform-italic transform-strike transform-links))

(defn- reinsert-tokens [s fenced inlines]
  (let [s1 (str/replace s
                        (re-pattern (str fence-token "(\\d+)" token-end))
                        (fn [[_ idx]]
                          (render-fenced (get fenced (Integer/parseInt idx)))))]
    (str/replace s1
                 (re-pattern (str inline-token "(\\d+)" token-end))
                 (fn [[_ idx]]
                   (render-inline (get inlines (Integer/parseInt idx)))))))

(defn- close-streaming-markers
  "Appends synthetic closers for trailing unclosed openers (code fence,
   inline backtick, **bold**, ~~strike~~). On the next flush the markers
   are recomputed, so as more content arrives the rendered HTML extends
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

(defn md->html
  "Renders standard markdown as Telegram-flavored HTML.

   Streaming-safe: synthesizes closing markers for trailing unclosed
   openers so partial input renders as well-formed HTML. The closer is
   recomputed on each call, so successive flushes monotonically extend
   the rendered output instead of reflowing once a closer arrives."
  [s]
  (when (some? s)
    (let [s0 (close-streaming-markers s)
          [s1 fenced] (extract s0 fence-rx fence-token)
          [s2 inlines] (extract s1 inline-code-rx inline-token)
          s3 (html-escape s2)
          s4 (-> s3
                 transform-blockquotes
                 transform-headings
                 transform-hr
                 transform-bullets
                 transform-inline-marks)]
      (reinsert-tokens s4 fenced inlines))))

(defn safe-md->html
  "Like md->html but never throws. On failure returns nil so callers can
   fall back to plain text."
  [s]
  (try
    (md->html s)
    (catch Throwable _ nil)))

(defn chunk-markdown
  "Splits raw markdown into chunks ≤ max-len characters, preferring
   paragraph (\\n\\n) then line then word boundaries. The post-render
   HTML may exceed max-len when escaping/tags are added — callers that
   send to Telegram should leave headroom (~10–15%) below 4096."
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
