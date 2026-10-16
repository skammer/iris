(ns agent.ui.render-test
  (:require
   [agent.ui.render :as render]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import (org.jsoup Jsoup)))

(defn- markdown [text]
  (Jsoup/parse (render/markdown->html text)))

(deftest renders-gfm-table
  (let [doc (markdown "| H1 | H2 |\n|:---|---:|\n| a | b |")]
    (is (= ["H1" "H2"] (mapv #(.text %) (.select doc "table th"))))
    (is (= ["a" "b"] (mapv #(.text %) (.select doc "table td"))))
    (is (= ["left" "right"] (mapv #(.attr % "align") (.select doc "table th"))))))

(deftest renders-task-list
  (let [items (.select (markdown "- [x] done\n- [ ] todo") "li")
        boxes (mapv #(.selectFirst % "input[type=checkbox]") items)]
    (is (= ["done" "todo"] (mapv #(.text %) items)))
    (is (= [true false] (mapv #(.hasAttr % "checked") boxes)))
    (is (every? #(.hasAttr % "disabled") boxes))))

(deftest renders-strikethrough-and-ins
  (let [doc (markdown "~~gone~~ ++new++")]
    (is (= "gone" (.text (.select doc "del"))))
    (is (= "new" (.text (.select doc "ins"))))))

(deftest renders-footnotes
  (let [doc (markdown "Claim[^1].\n\n[^1]: Evidence.")
        reference (first (filter #(str/starts-with? (.attr % "href") "#fn")
                                 (.select doc "a[href]")))
        target (when reference (.getElementById doc (subs (.attr reference "href") 1)))]
    (is (some? reference) "footnote reference survives sanitization")
    (is (some? target) "reference resolves to an actual footnote")
    (is (and target (str/includes? (.text target) "Evidence.")))))

(deftest renders-mark-and-spoiler
  (let [doc (markdown "==important== ||secret||")]
    (is (= "important" (.text (.select doc "mark"))))
    (is (= "secret" (.text (.select doc ".spoiler")))))
  (testing "unbalanced runs stay literal"
    (is (= "a ==b c" (.text (markdown "a ==b c"))))
    (is (= "a ||b c" (.text (markdown "a ||b c"))))))

(deftest renders-inline-html-passthrough
  (let [doc (markdown "x<sub>2</sub> y<sup>3</sup> <u>under</u>")]
    (is (= ["2" "3" "under"] (mapv #(.text %) (.select doc "sub, sup, u")))))
  (let [details (.select (markdown "<details open><summary>More</summary>\n\nhidden\n\n</details>") "details[open]")]
    (is (= 1 (count details)))
    (is (= "More" (.text (.select details "summary"))))))

(deftest renders-https-images-only
  (let [images (.select (markdown "![cat](https://example.com/cat.jpg)") "img")]
    (is (= 1 (count images)))
    (is (= ["https://example.com/cat.jpg" "cat" "lazy"]
           (mapv #(.attr (.first images) %) ["src" "alt" "loading"]))))
  (is (empty? (.select (markdown "![cat](http://example.com/cat.jpg)") "img"))
      "plain-http images are dropped entirely"))

(deftest sanitizes-dangerous-html
  (let [doc (markdown "<script>alert(1)</script> ok")]
    (is (empty? (.select doc "script")))
    (is (= "ok" (.text doc))))
  (let [doc (markdown "<img src=\"https://x.test/a.png\" onerror=\"alert(2)\">")]
    (is (= 1 (count (.select doc "img"))))
    (is (empty? (.select doc "[onerror]"))))
  (is (empty? (.select (markdown "[bad](javascript:alert(3))") "a[href^=javascript:]")))
  (is (empty? (.select (markdown "<iframe src=\"https://x.test\"></iframe>") "iframe"))))

(deftest strips-ui-class-injection
  (let [doc (markdown "<span class=\"tool-row\">x</span>")]
    (is (empty? (.select doc ".tool-row")))
    (is (= "x" (.text doc))))
  (is (= "code" (.text (.select (markdown "```python\ncode\n```") "pre > code.language-python")))))

(deftest math-delimiters-survive-verbatim
  (let [text (.text (markdown "Euler: $e^{i\\pi} + 1 = 0$ and\n\n$$E = mc^2$$"))]
    (is (str/includes? text "$e^{i\\pi} + 1 = 0$"))
    (is (str/includes? text "$$E = mc^2$$"))))

(deftest code-spans-render-html-as-text
  (let [doc (markdown "use `<div>` here")]
    (is (= "<div>" (.text (.select doc "code"))))
    (is (empty? (.select doc "div")) "code must be escaped exactly once")))

(deftest markers-inside-code-stay-literal
  (let [doc (markdown "```\na ==b== and ||c||\n```")]
    (is (= "a ==b== and ||c||" (.text (.select doc "pre > code"))))
    (is (empty? (.select doc "mark, .spoiler")))))
