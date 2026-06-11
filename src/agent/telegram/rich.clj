(ns agent.telegram.rich
  "Rich messages (Bot API 10.1).

   Outbound: LLM markdown is already GFM-compatible Rich Markdown, so the
   conversion is sanitization rather than translation — unsupported HTML
   tags and entities are escaped, streaming closers are synthesized, and
   payloads are chunked under the rich message limits.

   Inbound: converts a received RichBlock tree back to markdown text."
  (:require
   [agent.telegram.format :as fmt]
   [clojure.string :as str]))

;; Rich messages allow up to 32768 UTF-8 characters. Sanitization expands
;; escaped tags and the docs' phrasing leaves char-vs-byte ambiguous, so we
;; chunk with headroom and re-split any chunk that is still too many bytes.
(def ^:private max-rich-source-chars 31000)
(def ^:private max-rich-bytes 32000)

(def ^:private fence-rx
  #"(?ms)^[ \t]*(?:```|~~~)([^\n`~]*)\n(.*?)\n?[ \t]*(?:```|~~~)[ \t]*$")

(def ^:private fence-marker-rx
  #"(?m)^[ \t]*(?:```|~~~)[^\n`~]*$")

(def ^:private inline-code-rx
  #"`([^`\n]+)`")

(def ^:private token "R")
(def ^:private token-end "")

(def supported-tags
  "HTML tags accepted inside Rich Markdown / Rich HTML content."
  #{"a" "b" "strong" "i" "em" "u" "ins" "s" "strike" "del" "code" "pre"
    "mark" "sub" "sup" "cite" "br" "hr" "p" "h1" "h2" "h3" "h4" "h5" "h6"
    "ul" "ol" "li" "blockquote" "aside" "footer" "details" "summary"
    "table" "caption" "tr" "th" "td" "figure" "figcaption" "img" "video"
    "audio" "tg-spoiler" "tg-emoji" "tg-time" "tg-math" "tg-math-block"
    "tg-reference" "tg-map" "tg-collage" "tg-slideshow" "tg-thinking"})

;; Tags that never take a closing counterpart.
(def ^:private void-tags
  #{"br" "hr" "img"})

(def ^:private allowed-entities
  #{"lt" "gt" "amp" "quot" "apos" "nbsp" "hellip" "mdash" "ndash"
    "lsquo" "rsquo" "ldquo" "rdquo"})

(def ^:private tag-rx
  #"</?([a-zA-Z][a-zA-Z0-9-]*)(?:\s[^<>]*?)?/?>")

(def ^:private entity-rx
  #"&([a-zA-Z][a-zA-Z0-9]*);")

(def ^:private thinking-tag-rx
  #"(?is)</?tg-thinking(?:\s[^<>]*?)?>")

(defn enabled?
  "True when the adapter config opts into rich messages. Deliberately
   `true?` (not `not false?`) so partial configs stay on the legacy path."
  [config]
  (true? (:rich-messages? config)))

(defn strip-thinking-tags
  "Removes <tg-thinking> wrappers, keeping inner text. The tag is draft-only;
   a final message containing it is rejected by the API."
  [s]
  (str/replace (str s) thinking-tag-rx ""))

(defn- stash! [acc rendered]
  (let [n (count @acc)]
    (vswap! acc conj rendered)
    (str token n token-end)))

(defn- extract-code-regions
  "Replaces fenced blocks and inline code spans with tokens so passes over
   the remainder cannot corrupt literal code. Returns [text acc]."
  [s]
  (let [acc (volatile! [])
        s1 (str/replace s fence-rx (fn [[m]] (stash! acc m)))
        s2 (str/replace s1 inline-code-rx (fn [[m]] (stash! acc m)))]
    [s2 acc]))

(defn- reinsert-code-regions [s acc]
  (str/replace s
               (re-pattern (str token "(\\d+)" token-end))
               (fn [[_ idx]]
                 (get @acc (Integer/parseInt idx)))))

(defn- escape-unsupported-tags [s]
  (str/replace s tag-rx
               (fn [[m tag]]
                 (if (contains? supported-tags (str/lower-case tag))
                   m
                   (-> m
                       (str/replace "<" "&lt;")
                       (str/replace ">" "&gt;"))))))

(defn- escape-dangling-tag
  "Escapes a trailing `<tag...` opened but never closed (mid-stream or
   clamped payloads), which would otherwise break the rich parser."
  [s]
  (str/replace s #"<(?=/?[a-zA-Z!][^<>]*$)" "&lt;"))

(defn- escape-stray-tag-starts
  "Escapes `<` that begins tag-like text which never forms a tag on its
   line (e.g. `use <placeholder everywhere`). Comparisons like `a < b`
   are left alone."
  [s]
  (str/replace s #"<(?=/?[a-zA-Z!][^<>\n]*(\n|$))" "&lt;"))

(defn- escape-unsupported-entities [s]
  (str/replace s entity-rx
               (fn [[m name]]
                 (if (contains? allowed-entities name)
                   m
                   (str "&amp;" name ";")))))

(defn sanitize-markdown
  "Prepares LLM markdown for the InputRichMessage `markdown` field: escapes
   HTML tags and named entities the rich parser does not support, leaving
   code regions untouched. Numeric entities pass through (all supported)."
  [s]
  (let [[text acc] (extract-code-regions (str s))
        cleaned (-> text
                    escape-unsupported-tags
                    escape-stray-tag-starts
                    escape-dangling-tag
                    escape-unsupported-entities)]
    (reinsert-code-regions cleaned acc)))

(defn- close-odd-marker [s marker-rx closer strip-code]
  (if (odd? (count (re-seq marker-rx (strip-code s))))
    (str s closer)
    s))

(defn- open-html-tags
  "Stack scan for supported, non-void HTML tags left unclosed, outside
   code regions. Returns tag names innermost-last."
  [s]
  (let [[text _acc] (extract-code-regions s)]
    (reduce (fn [stack [m tag]]
              (let [tag (str/lower-case tag)]
                (cond
                  (or (not (contains? supported-tags tag))
                      (contains? void-tags tag)
                      (str/ends-with? m "/>"))
                  stack

                  (str/starts-with? m "</")
                  (let [idx (.lastIndexOf ^java.util.List stack tag)]
                    (if (neg? idx) stack (subvec stack 0 idx)))

                  :else
                  (conj stack tag))))
            []
            (re-seq tag-rx text))))

(defn close-streaming-markers
  "Appends synthetic closers for trailing unclosed openers so partial input
   renders as well-formed Rich Markdown. Recomputed on each flush, so the
   rendered draft extends monotonically instead of reflowing. Covers code
   fences, inline code, **bold**, ~~strike~~, ||spoiler||, ==mark==,
   $$math$$ and unclosed supported HTML tags."
  [s]
  (let [strip-fences #(str/replace % fence-rx "")
        strip-code #(str/replace (strip-fences %) inline-code-rx "")
        s (if (odd? (count (re-seq fence-marker-rx s)))
            (str s "\n```")
            s)
        s (if (odd? (count (re-seq #"`" (strip-fences s))))
            (str s "`")
            s)
        s (close-odd-marker s #"\*\*" "**" strip-code)
        s (close-odd-marker s #"~~" "~~" strip-code)
        s (close-odd-marker s #"\|\|" "||" strip-code)
        s (close-odd-marker s #"==" "==" strip-code)
        s (close-odd-marker s #"\$\$" "$$" strip-code)]
    (apply str s (map #(str "</" % ">") (reverse (open-html-tags s))))))

(defn- utf8-len ^long [s]
  (alength (.getBytes ^String s "UTF-8")))

(defn- byte-shrink [chunk]
  (if (<= (utf8-len chunk) max-rich-bytes)
    [chunk]
    (mapcat byte-shrink (fmt/chunk-markdown chunk (max 1 (quot (count chunk) 2))))))

(defn chunk-rich-markdown
  "Splits sanitized rich markdown into sendable chunks, each under the rich
   message limits with balanced streaming markers."
  [sanitized]
  (->> (fmt/chunk-markdown sanitized max-rich-source-chars)
       (mapcat byte-shrink)
       (mapv close-streaming-markers)))

(defn- clamp-head [s]
  (if (> (count s) max-rich-source-chars)
    (-> (subs s 0 max-rich-source-chars)
        escape-dangling-tag
        close-streaming-markers)
    s))

(def ^:private thinking-snippet-chars 200)

(defn- thinking-snippet
  "RichBlockThinking.text is inline RichText — a short status line, not a
   reasoning dump. Flattens thinking to a single line and keeps the tail as
   a live ticker; the full thinking still lands in the final <details>."
  [thinking]
  (let [flat (-> (strip-thinking-tags thinking)
                 (str/replace #"\s+" " ")
                 str/trim)
        tail (if (> (count flat) thinking-snippet-chars)
               (str "…" (subs flat (- (count flat) thinking-snippet-chars)))
               flat)]
    (sanitize-markdown tail)))

(defn compose-draft
  "Builds the partial markdown for sendRichMessageDraft: a single-line
   thinking ticker in the draft-only <tg-thinking> block, then the
   accumulated answer text. Drafts are ephemeral, so an over-limit payload
   keeps the head like the legacy draft path."
  [thinking text]
  (let [t (some-> thinking str/trim not-empty)
        b (some-> text str/trim not-empty)
        t* (when t
             (str "<tg-thinking>" (thinking-snippet t) "</tg-thinking>"))
        b* (some-> b sanitize-markdown close-streaming-markers)]
    (clamp-head (str/join "\n\n" (remove nil? [t* b*])))))

(defn compose-final
  "Builds the final rich markdown: thinking as a collapsed <details> block
   (preserving the visible-but-collapsed UX), then the answer."
  [thinking text]
  (let [t (some-> thinking str/trim not-empty)
        b (some-> text str/trim not-empty)
        t* (when t
             (str "<details><summary>thinking</summary>\n\n"
                  (sanitize-markdown (strip-thinking-tags t))
                  "\n\n</details>"))
        b* (some-> b strip-thinking-tags sanitize-markdown)]
    (str/join "\n\n" (remove nil? [t* b*]))))

(defn final-chunks
  "Sendable chunks for the finalized message. The <details> thinking block
   lands in the first chunk only."
  [thinking text]
  (let [s (compose-final thinking text)]
    (if (str/blank? s)
      []
      (chunk-rich-markdown s))))

;; --- Inbound: RichBlock tree -> markdown ------------------------------------

(declare blocks->markdown)

(defn rich-text->markdown
  "Recursively renders a RichText node (string, array, or typed map) as
   markdown. Unknown typed nodes degrade to their inner text."
  [node]
  (cond
    (string? node) node
    (sequential? node) (apply str (map rich-text->markdown node))
    (map? node)
    (let [inner (rich-text->markdown (:text node))]
      (case (some-> (:type node) keyword)
        :bold (str "**" inner "**")
        :italic (str "*" inner "*")
        :underline (str "<u>" inner "</u>")
        :strikethrough (str "~~" inner "~~")
        :spoiler (str "||" inner "||")
        :marked (str "==" inner "==")
        :code (str "`" inner "`")
        :subscript (str "<sub>" inner "</sub>")
        :superscript (str "<sup>" inner "</sup>")
        :mathematical_expression (str "$" (:expression node) "$")
        :custom_emoji (or (:alternative_text node) "")
        :url (str "[" inner "](" (or (:url node) inner) ")")
        :email_address (str "[" inner "](mailto:" (:email_address node) ")")
        :phone_number (str "[" inner "](tel:" (:phone_number node) ")")
        :text_mention inner
        :anchor ""
        :anchor_link inner
        :reference inner
        :reference_link inner
        inner))
    :else ""))

(defn- credit-text [credit]
  (some-> credit rich-text->markdown str/trim not-empty))

(defn- quote-lines [body credit]
  (let [quoted (->> (str/split-lines (str body))
                    (map #(str "> " %))
                    (str/join "\n"))]
    (str quoted (some->> (credit-text credit) (str "\n> — ")))))

(defn- caption-suffix [caption]
  (when caption
    (let [text (some-> (:text caption) rich-text->markdown str/trim not-empty)
          credit (credit-text (:credit caption))]
      (when (or text credit)
        (str/join " — " (remove nil? [text credit]))))))

(defn- list-item->markdown [{:keys [label blocks has_checkbox is_checked]}]
  (let [body (blocks->markdown blocks)
        marker (cond
                 has_checkbox (str "- [" (if is_checked "x" " ") "] ")
                 (some-> label (str/ends-with? ".")) (str label " ")
                 :else "- ")]
    (str marker (str/replace body "\n" (str "\n" (apply str (repeat (count marker) " ")))))))

(defn- gfm-table [cells caption]
  (let [rows (mapv (fn [row]
                     (mapv #(-> (rich-text->markdown (:text %))
                                (str/replace "|" "\\|")
                                (str/replace "\n" " "))
                           row))
                   cells)
        cols (apply max 1 (map count rows))
        row-line #(str "| " (str/join " | " (take cols (concat % (repeat "")))) " |")
        separator (row-line (repeat cols "---"))
        [header & body] rows]
    (str/join "\n"
              (concat [(row-line header) separator]
                      (map row-line body)
                      (some-> (caption-suffix caption) vector)))))

(defn- media-placeholder [kind block]
  (str "[" kind
       (some->> (caption-suffix (:caption block)) (str ": "))
       "]"))

(defn block->markdown
  "Renders one RichBlock as markdown. Media blocks become bracketed
   placeholders with captions; unrecognized blocks degrade to their text."
  [{:keys [type] :as block}]
  (case (some-> type keyword)
    :paragraph (rich-text->markdown (:text block))
    :heading (str (apply str (repeat (-> (or (:size block) 1) (max 1) (min 6)) "#"))
                  " " (rich-text->markdown (:text block)))
    :pre (str "```" (or (:language block) "") "\n"
              (rich-text->markdown (:text block))
              "\n```")
    :footer (rich-text->markdown (:text block))
    :divider "---"
    :mathematical_expression (str "$$" (:expression block) "$$")
    :anchor nil
    :list (->> (:items block)
               (map list-item->markdown)
               (str/join "\n"))
    :blockquote (quote-lines (blocks->markdown (:blocks block)) (:credit block))
    :pullquote (quote-lines (rich-text->markdown (:text block)) (:credit block))
    (:collage :slideshow) (str/join "\n\n"
                                    (remove nil?
                                            (conj (mapv block->markdown (:blocks block))
                                                  (caption-suffix (:caption block)))))
    :table (gfm-table (:cells block) (:caption block))
    :details (str "**" (rich-text->markdown (:summary block)) "**\n\n"
                  (blocks->markdown (:blocks block)))
    :map (str "[map: " (get-in block [:location :latitude]) ","
              (get-in block [:location :longitude]) "]")
    :animation (media-placeholder "animation" block)
    :audio (media-placeholder "audio" block)
    :photo (media-placeholder "photo" block)
    :video (media-placeholder "video" block)
    :voice_note (media-placeholder "voice note" block)
    (some-> (:text block) rich-text->markdown)))

(defn blocks->markdown [blocks]
  (->> blocks
       (keep block->markdown)
       (remove str/blank?)
       (str/join "\n\n")))

(defn message->markdown
  "Markdown text for an inbound message carrying `rich_message`, else nil."
  [message]
  (some-> message :rich_message :blocks seq blocks->markdown not-empty))
