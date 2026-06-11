(ns agent.ui.render-test
  (:require
   [agent.ui.render :as render]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest renders-gfm-table
  (let [html (render/markdown->html "| H1 | H2 |\n|:---|---:|\n| a | b |")]
    (is (str/includes? html "<table>"))
    (is (str/includes? html "<th align=\"left\">H1</th>"))
    (is (str/includes? html "<th align=\"right\">H2</th>"))
    (is (str/includes? html "<td align=\"left\">a</td>"))))

(deftest renders-task-list
  (let [html (render/markdown->html "- [x] done\n- [ ] todo")]
    (is (str/includes? html "type=\"checkbox\""))
    (is (str/includes? html "checked"))
    (is (str/includes? html "disabled"))))

(deftest renders-strikethrough-and-ins
  (is (str/includes? (render/markdown->html "~~gone~~") "<del>gone</del>"))
  (is (str/includes? (render/markdown->html "++new++") "<ins>new</ins>")))

(deftest renders-footnotes
  (let [html (render/markdown->html "Claim[^1].\n\n[^1]: Evidence.")]
    (is (str/includes? html "footnote-ref"))
    (is (str/includes? html "class=\"footnotes\""))
    (is (str/includes? html "Evidence."))
    (is (re-find #"href=\"#fn[\w-]*1\"" html)
        "fragment links survive sanitization")))

(deftest renders-mark-and-spoiler
  (is (str/includes? (render/markdown->html "==important==")
                     "<mark>important</mark>"))
  (is (str/includes? (render/markdown->html "||secret||")
                     "<span class=\"spoiler\">secret</span>"))
  (testing "unbalanced runs stay literal"
    (is (str/includes? (render/markdown->html "a ==b c") "==b"))
    (is (str/includes? (render/markdown->html "a ||b c") "||b"))))

(deftest renders-inline-html-passthrough
  (is (str/includes? (render/markdown->html "x<sub>2</sub> y<sup>3</sup>")
                     "<sub>2</sub>"))
  (is (str/includes? (render/markdown->html "<u>under</u>") "<u>under</u>"))
  (let [html (render/markdown->html "<details open><summary>More</summary>\n\nhidden\n\n</details>")]
    (is (str/includes? html "<details open"))
    (is (str/includes? html "<summary>More</summary>"))))

(deftest renders-https-images-only
  (let [html (render/markdown->html "![cat](https://example.com/cat.jpg)")]
    (is (str/includes? html "src=\"https://example.com/cat.jpg\""))
    (is (str/includes? html "loading=\"lazy\""))
    (is (str/includes? html "alt=\"cat\"")))
  (is (not (str/includes? (render/markdown->html "![cat](http://example.com/cat.jpg)")
                          "<img"))
      "plain-http images are dropped entirely"))

(deftest sanitizes-dangerous-html
  (let [html (render/markdown->html "<script>alert(1)</script> ok")]
    (is (not (str/includes? html "<script")))
    (is (not (str/includes? html "alert(1)")))
    (is (str/includes? html "ok")))
  (let [html (render/markdown->html "<img src=\"https://x.test/a.png\" onerror=\"alert(2)\">")]
    (is (not (str/includes? html "onerror"))))
  (is (not (str/includes? (render/markdown->html "[bad](javascript:alert(3))")
                          "javascript:")))
  (is (not (str/includes? (render/markdown->html "<iframe src=\"https://x.test\"></iframe>")
                          "<iframe"))))

(deftest strips-ui-class-injection
  (let [html (render/markdown->html "<span class=\"tool-row\">x</span>")]
    (is (not (str/includes? html "tool-row"))))
  (let [html (render/markdown->html "```python\ncode\n```")]
    (is (str/includes? html "language-python")
        "code language classes survive")))

(deftest math-delimiters-survive-verbatim
  (let [html (render/markdown->html "Euler: $e^{i\\pi} + 1 = 0$ and\n\n$$E = mc^2$$")]
    (is (str/includes? html "$e^"))
    (is (str/includes? html "$$E = mc^2$$"))))

(deftest code-spans-render-html-as-text
  (is (str/includes? (render/markdown->html "use `<div>` here")
                     "<code>&lt;div&gt;</code>")
      "regression: code spans must not double-escape"))

(deftest markers-inside-code-stay-literal
  (let [html (render/markdown->html "```\na ==b== and ||c||\n```")]
    (is (str/includes? html "==b=="))
    (is (str/includes? html "||c||"))))
