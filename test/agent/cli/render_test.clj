(ns agent.cli.render-test
  (:require
   [agent.cli.render :as render]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- rendered
  "Feeds deltas through a tty renderer and returns captured stdout."
  [deltas]
  (with-out-str
    (let [{:keys [on-delta finish]} (render/make-stream-renderer {:tty? true :width 40})]
      (doseq [d deltas]
        (on-delta d))
      (finish))))

(defn- stripped [s]
  (str/replace s #"\u001b\[[0-9;]*m" ""))

(deftest passthrough-when-not-tty
  (let [out (with-out-str
              (let [{:keys [on-delta finish]} (render/make-stream-renderer {:tty? false})]
                (on-delta "# Title\n|a|b|\n")
                (on-delta "**bo")
                (on-delta "ld**")
                (finish)))]
    (is (= "# Title\n|a|b|\n**bold**" out)
        "non-tty output is byte-identical to the raw deltas")))

(deftest styles-inline-markdown
  (let [out (rendered ["**bold** and *it* and `code`\n"])]
    (is (str/includes? out "\u001b[1mbold\u001b[0m"))
    (is (str/includes? out "\u001b[3mit\u001b[0m"))
    (is (str/includes? out "\u001b[7mcode\u001b[0m"))))

(deftest styles-headings-and-quotes
  (let [out (rendered ["# Title\n> quoted\n"])]
    (is (str/includes? out "\u001b[1mTitle\u001b[0m"))
    (is (str/includes? (stripped out) "┃ quoted")))
  (let [out (rendered ["- item\n- [x] done\n- [ ] todo\n"])]
    (is (str/includes? (stripped out) "• item"))
    (is (str/includes? out "☑ done"))
    (is (str/includes? out "☐ todo"))))

(deftest deltas-split-mid-line-and-mid-marker
  (let [whole (rendered ["**bold** line\n"])
        split (rendered ["**bo" "ld**" " li" "ne\n"])]
    (is (= whole split)
        "line buffering makes delta boundaries irrelevant")))

(deftest fenced-code-renders-verbatim-with-rules
  (let [out (rendered ["```python\nprint('**not bold**')\n```\n"])
        first-line (first (str/split-lines (stripped out)))]
    (is (str/includes? first-line "python") "language label in the top rule")
    (is (str/includes? first-line "─"))
    (is (str/includes? out "print('**not bold**')")
        "no inline styling inside fences"))
  (testing "unterminated fence is closed at finish"
    (let [out (rendered ["```\ncode line"])]
      (is (str/includes? out "code line")))))

(deftest table-buffers-until-complete-then-draws
  (let [out (rendered ["| H1 | H2 |\n|:---|---:|\n| a | b |\nafter\n"])
        plain (stripped out)]
    (is (str/includes? plain "┌"))
    (is (str/includes? plain "│"))
    (is (str/includes? plain "└"))
    (is (str/includes? plain "H1"))
    (is (str/includes? plain "after"))
    (is (str/includes? out "\u001b[1mH1\u001b[0m") "header cells bold")))

(deftest lone-pipe-line-is-not-a-table
  (let [out (rendered ["| just a pipe line\nnot a separator\n"])
        plain (stripped out)]
    (is (str/includes? plain "| just a pipe line"))
    (is (str/includes? plain "not a separator"))
    (is (not (str/includes? plain "┌")))))

(deftest finish-flushes-partial-state
  (testing "partial line"
    (is (str/includes? (stripped (rendered ["no newline at end"]))
                       "no newline at end")))
  (testing "table pending at finish"
    (let [out (stripped (rendered ["| H |\n|---|\n| a |"]))]
      (is (str/includes? out "┌"))
      (is (str/includes? out "a")))))

(deftest render-string-full-document
  (let [out (with-out-str (render/render-string! "# T\n\n- a\n" {:tty? true :width 40}))]
    (is (str/includes? out "\u001b[1mT\u001b[0m"))
    (is (str/includes? (stripped out) "• a")))
  (let [out (with-out-str (render/render-string! "# T\n\n- a\n" {:tty? false}))]
    (is (= "# T\n\n- a\n" out))))
