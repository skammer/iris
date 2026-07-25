(ns agent.telegram.rich-test
  (:require
   [agent.telegram.rich :as rich]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;; --- sanitize-markdown -------------------------------------------------------

(deftest sanitize-escapes-unsupported-tags
  (is (= "&lt;div&gt;x&lt;/div&gt;" (rich/sanitize-markdown "<div>x</div>")))
  (is (= "&lt;span class=\"x\"&gt;y&lt;/span&gt;"
         (rich/sanitize-markdown "<span class=\"x\">y</span>")))
  (is (= "&lt;think&gt;hm&lt;/think&gt;" (rich/sanitize-markdown "<think>hm</think>"))))

(deftest sanitize-keeps-supported-tags
  (is (= "<u>x</u>" (rich/sanitize-markdown "<u>x</u>")))
  (is (= "<details open><summary>t</summary>body</details>"
         (rich/sanitize-markdown "<details open><summary>t</summary>body</details>")))
  (is (= "<tg-spoiler>s</tg-spoiler>" (rich/sanitize-markdown "<tg-spoiler>s</tg-spoiler>")))
  (is (= "<tg-map lat=\"41.9\" long=\"12.5\" zoom=\"14\"/>"
         (rich/sanitize-markdown "<tg-map lat=\"41.9\" long=\"12.5\" zoom=\"14\"/>"))))

(deftest sanitize-skips-code-regions
  (is (= "```html\n<div>x</div>\n```"
         (rich/sanitize-markdown "```html\n<div>x</div>\n```")))
  (is (= "use `<div>` here" (rich/sanitize-markdown "use `<div>` here")))
  (is (= "`&nbsp;&custom;`" (rich/sanitize-markdown "`&nbsp;&custom;`"))))

(deftest sanitize-entities
  (is (= "a &amp;custom; b" (rich/sanitize-markdown "a &custom; b")))
  (is (= "&nbsp;" (rich/sanitize-markdown "&nbsp;")))
  (is (= "&lt; &gt; &amp;" (rich/sanitize-markdown "&lt; &gt; &amp;")))
  (is (= "&#65; &#x1F600;" (rich/sanitize-markdown "&#65; &#x1F600;"))
      "numeric entities pass through"))

(deftest sanitize-stray-and-dangling-tags
  (is (= "trailing &lt;di" (rich/sanitize-markdown "trailing <di")))
  (is (= "use &lt;placeholder everywhere\nnext line"
         (rich/sanitize-markdown "use <placeholder everywhere\nnext line")))
  (is (= "a < b and x < 3" (rich/sanitize-markdown "a < b and x < 3"))
      "comparisons are not tag-like"))

(deftest strip-thinking-tags
  (is (= "inner" (rich/strip-thinking-tags "<tg-thinking>inner</tg-thinking>")))
  (is (= "plain" (rich/strip-thinking-tags "plain"))))

;; --- close-streaming-markers -------------------------------------------------

(deftest closes-unclosed-fence
  (is (str/ends-with? (rich/close-streaming-markers "```python\nprint(1)")
                      "\n```")))

(deftest closes-inline-markers
  (is (= "a `b`" (rich/close-streaming-markers "a `b")))
  (is (= "a **b**" (rich/close-streaming-markers "a **b")))
  (is (= "a ~~b~~" (rich/close-streaming-markers "a ~~b")))
  (is (= "a ||b||" (rich/close-streaming-markers "a ||b")))
  (is (= "a ==b==" (rich/close-streaming-markers "a ==b")))
  (is (= "a $$x$$" (rich/close-streaming-markers "a $$x"))))

(deftest closes-unclosed-html-tags
  (is (= "<details><summary>t</summary></details>"
         (rich/close-streaming-markers "<details><summary>t")))
  (is (= "<u>under</u>" (rich/close-streaming-markers "<u>under")))
  (is (= "<u>done</u>" (rich/close-streaming-markers "<u>done</u>"))
      "balanced tags untouched")
  (is (= "line<br>more" (rich/close-streaming-markers "line<br>more"))
      "void tags need no closer")
  (is (= "<tg-map lat=\"1\" long=\"2\" zoom=\"14\"/>"
         (rich/close-streaming-markers "<tg-map lat=\"1\" long=\"2\" zoom=\"14\"/>"))
      "self-closing tags need no closer"))

(deftest markers-inside-code-ignored
  (is (= "`a || b`" (rich/close-streaming-markers "`a || b`")))
  (is (= "```\n**unclosed || stuff ==\n```"
         (rich/close-streaming-markers "```\n**unclosed || stuff ==\n```"))))

(deftest streaming-monotonic-growth
  (testing "each successive flush extends the previous rendered output"
    (let [full "Intro **bold** then\n\n```clj\n(+ 1 2)\n```\n\nand ||spoiler|| end"
          cuts (map #(subs full 0 %) (range 1 (inc (count full))))]
      (doseq [cut cuts]
        (let [closed (rich/close-streaming-markers cut)]
          (is (str/starts-with? closed cut)))))))

;; --- compose / chunk ----------------------------------------------------------

(deftest compose-draft-thinking-only
  (is (= "<tg-thinking>hmm</tg-thinking>" (rich/compose-draft "hmm" nil))))

(deftest compose-draft-thinking-and-text
  (let [out (rich/compose-draft "pondering" "Answer so far")]
    (is (= "<tg-thinking>pondering</tg-thinking>\n\nAnswer so far" out))))

(deftest compose-draft-sanitizes-and-closes
  (let [out (rich/compose-draft "<tg-thinking>echo</tg-thinking>" "open **bold")]
    (is (= "<tg-thinking>echo</tg-thinking>\n\nopen **bold**" out))))

(deftest compose-draft-flattens-thinking-to-inline-ticker
  (testing "RichBlockThinking.text is inline RichText — no paragraphs or fences"
    (is (= "<tg-thinking>first second</tg-thinking>"
           (rich/compose-draft "first\n\nsecond" nil)))
    (is (= "<tg-thinking>look at ``` code ``` block</tg-thinking>"
           (rich/compose-draft "look at ```\ncode\n``` block" nil))))
  (testing "long thinking keeps the tail as a ticker"
    (let [out (rich/compose-draft (apply str (repeat 100 "vwxyz ")) nil)]
      (is (str/starts-with? out "<tg-thinking>…"))
      (is (<= (count out) 230)))))

(deftest compose-final-wraps-thinking-in-details
  (let [out (rich/compose-final "deep thought" "The answer.")]
    (is (= (str "<details><summary>thinking</summary>\n\n"
                "deep thought\n\n</details>\n\nThe answer.")
           out))))

(deftest compose-final-never-contains-tg-thinking
  (let [out (rich/compose-final "<tg-thinking>t</tg-thinking>" "a <tg-thinking>b</tg-thinking>")]
    (is (not (str/includes? out "<tg-thinking")))))

(deftest compose-final-without-thinking
  (is (= "Just text." (rich/compose-final nil "Just text.")))
  (is (= "Just text." (rich/compose-final "  " "Just text."))))

(deftest final-chunks-empty-when-blank
  (is (= [] (rich/final-chunks nil nil)))
  (is (= [] (rich/final-chunks "" "  "))))

(deftest chunking-respects-limits-and-balance
  (let [para (str (apply str (repeat 400 "word ")) "\n\n")
        big (str "```clj\n(code)\n```\n\n" (apply str (repeat 20 para)))
        chunks (rich/final-chunks nil big)]
    (is (> (count chunks) 1))
    (doseq [chunk chunks]
      (is (<= (alength (.getBytes ^String chunk "UTF-8")) 32768))
      (is (even? (count (re-seq #"(?m)^[ \t]*(?:```|~~~)" chunk)))
          "fences balanced per chunk"))))

(deftest chunking-uses-character-not-byte-limit
  (let [big (->> (repeat 200 (str (apply str (repeat 80 "漢字テスト")) "\n\n"))
                 (apply str))
        chunks (rich/chunk-rich-markdown big)]
    (is (> (count chunks) 1))
    (doseq [chunk chunks]
      (is (<= (count chunk) 32768)))
    (is (some #(> (alength (.getBytes ^String % "UTF-8")) 32768) chunks)
        "Bot API limit is UTF-8 characters, not encoded bytes")))

(deftest chunking-respects-rich-block-limit
  (let [big (str/join "\n\n" (map #(str "paragraph-" %) (range 600)))
        chunks (rich/chunk-rich-markdown big)]
    (is (= 2 (count chunks)))
    (is (= 600 (reduce + (map #(count (re-seq #"paragraph-" %)) chunks))))
    (is (every? #(<= (count (re-seq #"paragraph-" %)) 480) chunks))))

(deftest ha-report-gfm-tables-stay-rich
  (let [report (str "## 🌿 Растения\n\n"
                    "| Растение | 💧 Влажность | 🌡️ Температура | 🔋 Батарея |\n"
                    "|---|---|---|---|\n"
                    "| Драцена | 82% | 27.4°C | 57% |\n"
                    "| Фикус | 88% | 26.1°C | 100% |\n\n"
                    "## 🏢 Климат\n\n"
                    "| Параметр | Значение |\n"
                    "|---|---|\n"
                    "| 🌡️ Температура | 27.9°C |\n"
                    "| 💧 Влажность | 44.6% |")]
    (is (= [report] (rich/final-chunks nil report)))))

;; --- inbound conversion -------------------------------------------------------

(deftest rich-text-nodes
  (is (= "plain" (rich/rich-text->markdown "plain")))
  (is (= "ab" (rich/rich-text->markdown ["a" "b"])))
  (is (= "**b**" (rich/rich-text->markdown {:type "bold" :text "b"})))
  (is (= "**[x](https://t.me)**"
         (rich/rich-text->markdown {:type "bold"
                                    :text {:type "url" :text "x" :url "https://t.me"}}))
      "nesting recurses")
  (is (= "<sub>2</sub>" (rich/rich-text->markdown {:type "subscript" :text "2"})))
  (is (= "||s||" (rich/rich-text->markdown {:type "spoiler" :text "s"})))
  (is (= "x" (rich/rich-text->markdown {:type "wat-future" :text "x"}))
      "unknown node degrades to inner text"))

(deftest block-conversion
  (is (= "hello" (rich/block->markdown {:type "paragraph" :text "hello"})))
  (is (= "## Title" (rich/block->markdown {:type "heading" :text "Title" :size 2})))
  (is (= "```python\nprint(1)\n```"
         (rich/block->markdown {:type "pre" :text "print(1)" :language "python"})))
  (is (= "---" (rich/block->markdown {:type "divider"})))
  (is (= "$$E = mc^2$$"
         (rich/block->markdown {:type "mathematical_expression" :expression "E = mc^2"})))
  (is (= "[photo: a cat]"
         (rich/block->markdown {:type "photo" :photo [] :caption {:text "a cat"}}))))

(deftest block-list-conversion
  (is (= "- one\n- two"
         (rich/block->markdown
          {:type "list"
           :items [{:blocks [{:type "paragraph" :text "one"}]}
                   {:blocks [{:type "paragraph" :text "two"}]}]})))
  (is (= "- [x] done\n- [ ] todo"
         (rich/block->markdown
          {:type "list"
           :items [{:has_checkbox true :is_checked true
                    :blocks [{:type "paragraph" :text "done"}]}
                   {:has_checkbox true
                    :blocks [{:type "paragraph" :text "todo"}]}]})))
  (is (= "1. first"
         (rich/block->markdown
          {:type "list"
           :items [{:label "1." :blocks [{:type "paragraph" :text "first"}]}]}))))

(deftest block-quote-conversion
  (is (= "> wisdom\n> — Author"
         (rich/block->markdown {:type "blockquote"
                                :blocks [{:type "paragraph" :text "wisdom"}]
                                :credit "Author"})))
  (is (= "> pulled"
         (rich/block->markdown {:type "pullquote" :text "pulled"}))))

(deftest block-table-conversion
  (is (= "| H1 | H2 |\n| --- | --- |\n| a | b |"
         (rich/block->markdown
          {:type "table"
           :cells [[{:text "H1" :is_header true} {:text "H2" :is_header true}]
                   [{:text "a"} {:text "b"}]]})))
  (is (= "| pipe\\|cell |\n| --- |"
         (rich/block->markdown {:type "table" :cells [[{:text "pipe|cell"}]]}))))

(deftest block-details-conversion
  (is (= "**More**\n\nhidden"
         (rich/block->markdown {:type "details"
                                :summary "More"
                                :blocks [{:type "paragraph" :text "hidden"}]}))))

(deftest message->markdown-conversion
  (is (nil? (rich/message->markdown {:text "plain"})))
  (is (= "# Hi\n\nbody"
         (rich/message->markdown
          {:rich_message {:blocks [{:type "heading" :text "Hi" :size 1}
                                   {:type "paragraph" :text "body"}]}}))))

;; --- enabled? -----------------------------------------------------------------

(deftest enabled-requires-explicit-true
  (is (rich/enabled? {:rich-messages? true}))
  (is (not (rich/enabled? {:rich-messages? false})))
  (is (not (rich/enabled? {}))))
