(ns agent.telegram.format-test
  (:require
   [agent.telegram.format :as fmt]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest md-markdown-v2-bold
  (is (= "*hi*" (fmt/md->markdown-v2 "**hi**")))
  (is (= "*hi*" (fmt/md->markdown-v2 "__hi__")))
  (is (= "a *b* c" (fmt/md->markdown-v2 "a **b** c"))))

(deftest md-markdown-v2-italic
  (is (= "_x_" (fmt/md->markdown-v2 "*x*")))
  (is (= "_x_" (fmt/md->markdown-v2 "_x_")))
  (is (= "snake\\_case\\_var stays" (fmt/md->markdown-v2 "snake_case_var stays"))
      "underscores inside identifiers should not become italic"))

(deftest md-markdown-v2-bold-and-italic
  (is (= "*bold* and _italic_"
         (fmt/md->markdown-v2 "**bold** and *italic*"))))

(deftest md-markdown-v2-strikethrough
  (is (= "~gone~" (fmt/md->markdown-v2 "~~gone~~"))))

(deftest md-markdown-v2-inline-code
  (is (= "use `x`" (fmt/md->markdown-v2 "use `x`")))
  (is (= "`<tag>`" (fmt/md->markdown-v2 "`<tag>`"))))

(deftest md-markdown-v2-fenced-code
  (is (= "```clojure\n(+ 1 2)\n```"
         (fmt/md->markdown-v2 "```clojure\n(+ 1 2)\n```")))
  (is (= "```\nplain\n```"
         (fmt/md->markdown-v2 "```\nplain\n```")))
  (is (= "```\na & b < c\n```"
         (fmt/md->markdown-v2 "```\na & b < c\n```"))))

(deftest md-markdown-v2-headers-downgraded
  (is (= "*Title*" (fmt/md->markdown-v2 "# Title")))
  (is (= "*Sub*" (fmt/md->markdown-v2 "### Sub"))))

(deftest md-markdown-v2-bullets-downgraded
  (is (= "• one\n• two"
         (fmt/md->markdown-v2 "- one\n- two")))
  (is (= "• star" (fmt/md->markdown-v2 "* star")))
  (is (= "• plus" (fmt/md->markdown-v2 "+ plus"))))

(deftest md-markdown-v2-blockquote
  (is (= ">quoted"
         (fmt/md->markdown-v2 "> quoted")))
  (is (= ">line one\n>line two"
         (fmt/md->markdown-v2 "> line one\n> line two"))))

(deftest md-markdown-v2-link
  (is (= "[click](https://example.com)"
         (fmt/md->markdown-v2 "[click](https://example.com)"))))

(deftest md-markdown-v2-link-with-formatting-inside
  (is (= "[see *here*](https://x)"
         (fmt/md->markdown-v2 "[see **here**](https://x)"))))

(deftest md-markdown-v2-escapes-specials
  (is (= "<script\\>alert\\(1\\)</script\\>"
         (fmt/md->markdown-v2 "<script>alert(1)</script>")))
  (is (= "a & b" (fmt/md->markdown-v2 "a & b")))
  (is (= "hi\\!" (fmt/md->markdown-v2 "hi!"))))

(deftest md-markdown-v2-mixed
  (is (= "*Title*\n\nThe *quick* _brown_ fox\\.\n\n```bash\necho & ok\n```"
         (fmt/md->markdown-v2 "# Title\n\nThe **quick** *brown* fox.\n\n```bash\necho & ok\n```"))))

(deftest md-markdown-v2-handles-nil-and-empty
  (is (nil? (fmt/md->markdown-v2 nil)))
  (is (= "" (fmt/md->markdown-v2 ""))))

(deftest md-markdown-v2-streaming-closes-unclosed-fence
  (testing "unclosed code fence renders as a complete pre block"
    (is (= "```\npartial\n```"
           (fmt/md->markdown-v2 "```\npartial"))))
  (testing "unclosed fence with language"
    (is (= "```clojure\n(+ 1\n```"
           (fmt/md->markdown-v2 "```clojure\n(+ 1")))))

(deftest md-markdown-v2-streaming-closes-unclosed-bold
  (is (= "hello *wor*" (fmt/md->markdown-v2 "hello **wor"))))

(deftest md-markdown-v2-streaming-closes-unclosed-inline-code
  (is (= "see `val`" (fmt/md->markdown-v2 "see `val"))))

(deftest md-markdown-v2-streaming-monotonic-growth
  (testing "successive snapshots of a streaming buffer extend without retracting"
    (let [steps ["```clojure\n"
                 "```clojure\n(defn f"
                 "```clojure\n(defn f [x]\n  (* x 2))"
                 "```clojure\n(defn f [x]\n  (* x 2))\n```"]
          outputs (mapv fmt/md->markdown-v2 steps)]
      (is (every? #(str/starts-with? % "```clojure") outputs))
      (is (re-find #"\(defn f" (nth outputs 1)))
      (is (re-find #"\(defn f \[x\]" (nth outputs 2)))
      (is (re-find #"\(defn f \[x\]" (nth outputs 3))))))

(deftest md-markdown-v2-preserves-link-url
  (let [out (fmt/md->markdown-v2 "[q](https://x.com?a=1&b=2)")]
    (is (= "[q](https://x.com?a=1&b=2)" out))))

(deftest safe-md-markdown-v2-never-throws
  (is (= "*x*" (fmt/safe-md->markdown-v2 "**x**")))
  (is (nil? (fmt/safe-md->markdown-v2 nil))))

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
