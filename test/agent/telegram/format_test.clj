(ns agent.telegram.format-test
  (:require
   [agent.telegram.format :as fmt]
   [clojure.test :refer [deftest is testing]]))

(deftest md-html-bold
  (is (= "<b>hi</b>" (fmt/md->html "**hi**")))
  (is (= "<b>hi</b>" (fmt/md->html "__hi__")))
  (is (= "a <b>b</b> c" (fmt/md->html "a **b** c"))))

(deftest md-html-italic
  (is (= "<i>x</i>" (fmt/md->html "*x*")))
  (is (= "<i>x</i>" (fmt/md->html "_x_")))
  (is (= "snake_case_var stays" (fmt/md->html "snake_case_var stays"))
      "underscores inside identifiers should not become italic"))

(deftest md-html-bold-and-italic
  (is (= "<b>bold</b> and <i>italic</i>"
         (fmt/md->html "**bold** and *italic*"))))

(deftest md-html-strikethrough
  (is (= "<s>gone</s>" (fmt/md->html "~~gone~~"))))

(deftest md-html-inline-code
  (is (= "use <code>x</code>" (fmt/md->html "use `x`")))
  (is (= "<code>&lt;tag&gt;</code>" (fmt/md->html "`<tag>`"))
      "html chars inside code must be escaped"))

(deftest md-html-fenced-code
  (is (= "<pre><code class=\"language-clojure\">(+ 1 2)</code></pre>"
         (fmt/md->html "```clojure\n(+ 1 2)\n```")))
  (is (= "<pre><code>plain</code></pre>"
         (fmt/md->html "```\nplain\n```")))
  (is (= "<pre><code>a &amp; b &lt; c</code></pre>"
         (fmt/md->html "```\na & b < c\n```"))
      "html chars in fenced code must be escaped"))

(deftest md-html-headers-downgraded
  (is (= "<b>Title</b>" (fmt/md->html "# Title")))
  (is (= "<b>Sub</b>" (fmt/md->html "### Sub"))))

(deftest md-html-bullets-downgraded
  (is (= "• one\n• two"
         (fmt/md->html "- one\n- two")))
  (is (= "• star" (fmt/md->html "* star")))
  (is (= "• plus" (fmt/md->html "+ plus"))))

(deftest md-html-blockquote
  (is (= "<blockquote>quoted</blockquote>"
         (fmt/md->html "> quoted")))
  (is (= "<blockquote>line one\nline two</blockquote>"
         (fmt/md->html "> line one\n> line two"))))

(deftest md-html-link
  (is (= "<a href=\"https://example.com\">click</a>"
         (fmt/md->html "[click](https://example.com)"))))

(deftest md-html-link-with-formatting-inside
  (is (= "<a href=\"https://x\">see <b>here</b></a>"
         (fmt/md->html "[see **here**](https://x)"))))

(deftest md-html-escapes-raw-html
  (is (= "&lt;script&gt;alert(1)&lt;/script&gt;"
         (fmt/md->html "<script>alert(1)</script>")))
  (is (= "a &amp; b" (fmt/md->html "a & b"))))

(deftest md-html-mixed
  (is (= "<b>Title</b>\n\nThe <b>quick</b> <i>brown</i> fox.\n\n<pre><code class=\"language-bash\">echo &amp; ok</code></pre>"
         (fmt/md->html "# Title\n\nThe **quick** *brown* fox.\n\n```bash\necho & ok\n```"))))

(deftest md-html-handles-nil-and-empty
  (is (nil? (fmt/md->html nil)))
  (is (= "" (fmt/md->html ""))))

(deftest md-html-streaming-closes-unclosed-fence
  (testing "unclosed code fence renders as a complete <pre><code> block"
    (is (= "<pre><code>partial</code></pre>"
           (fmt/md->html "```\npartial"))))
  (testing "unclosed fence with language"
    (is (= "<pre><code class=\"language-clojure\">(+ 1</code></pre>"
           (fmt/md->html "```clojure\n(+ 1")))))

(deftest md-html-streaming-closes-unclosed-bold
  (is (= "hello <b>wor</b>" (fmt/md->html "hello **wor"))))

(deftest md-html-streaming-closes-unclosed-inline-code
  (is (= "see <code>val</code>" (fmt/md->html "see `val"))))

(deftest md-html-streaming-monotonic-growth
  (testing "successive snapshots of a streaming buffer extend without retracting"
    (let [steps ["```clojure\n"
                 "```clojure\n(defn f"
                 "```clojure\n(defn f [x]\n  (* x 2))"
                 "```clojure\n(defn f [x]\n  (* x 2))\n```"]
          outputs (mapv fmt/md->html steps)]
      (is (every? #(re-find #"<pre><code" %) outputs))
      (is (re-find #"\(defn f" (nth outputs 1)))
      (is (re-find #"\(defn f \[x\]" (nth outputs 2)))
      (is (re-find #"\(defn f \[x\]" (nth outputs 3))))))

(deftest md-html-no-double-escape-in-link-href
  (let [out (fmt/md->html "[q](https://x.com?a=1&b=2)")]
    (is (= "<a href=\"https://x.com?a=1&amp;b=2\">q</a>" out)
        "ampersand in URL escaped exactly once")))

(deftest safe-md-html-never-throws
  (is (= "<b>x</b>" (fmt/safe-md->html "**x**")))
  (is (nil? (fmt/safe-md->html nil))))

(deftest chunk-markdown-keeps-short-input
  (is (= ["short text"] (fmt/chunk-markdown "short text" 100))))

(deftest chunk-markdown-splits-on-paragraph
  (let [s (str (apply str (repeat 50 "a")) "\n\n" (apply str (repeat 50 "b")))]
    (is (= [(apply str (repeat 50 "a"))
            (apply str (repeat 50 "b"))]
           (fmt/chunk-markdown s 60)))))

(deftest chunk-markdown-falls-back-to-line-then-word
  (let [s "aaaa bbbb cccc dddd"
        chunks (fmt/chunk-markdown s 8)]
    (is (every? #(<= (count %) 8) chunks))
    (is (= "aaaa bbbb cccc dddd" (clojure.string/join " " chunks)))))
