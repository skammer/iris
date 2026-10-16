(ns agent.ui.markdown
  "Markdown parsing and HTML sanitization before trusted rendering."
  (:require
   [clojure.string :as str])
  (:import
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
