(ns agent.llm.dsml-test
  (:require
   [agent.llm.dsml :as dsml]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]))

(def leaked-fs-call
  (str "<｜DSML｜tool_calls>  "
       "<｜DSML｜invoke name=\"fs\">  "
       "<｜DSML｜parameter name=\"action\" string=\"true\">list</｜DSML｜parameter>  "
       "<｜DSML｜parameter name=\"path\" string=\"true\">/Users/skammer</｜DSML｜parameter>  "
       "</｜DSML｜invoke>  "
       "</｜DSML｜tool_calls>"))

(deftest recovers-single-invoke
  (let [{:keys [content tool-calls]} (dsml/recover-tool-calls
                                      {:content leaked-fs-call
                                       :tool-calls []})]
    (is (= "" content))
    (is (= 1 (count tool-calls)))
    (let [tc (first tool-calls)]
      (is (= "function" (:type tc)))
      (is (string? (:id tc)))
      (is (= "fs" (-> tc :function :name)))
      (is (= {"action" "list" "path" "/Users/skammer"}
             (json/parse-string (-> tc :function :arguments)))))))

(deftest recovers-tool-call-function-tags
  (let [content (str "<tool_call>\n"
                     "<function=memory>\n"
                     "<parameter=query>\n"
                     "Макс\n"
                     "</parameter>\n"
                     "<parameter=action>\n"
                     "search\n"
                     "</parameter>\n"
                     "</function>\n"
                     "</tool_call>")
        {:keys [content tool-calls]} (dsml/recover-tool-calls
                                      {:content content
                                       :tool-calls []})]
    (is (= "" content))
    (is (= 1 (count tool-calls)))
    (let [tc (first tool-calls)]
      (is (= "memory" (-> tc :function :name)))
      (is (= {"query" "Макс" "action" "search"}
             (json/parse-string (-> tc :function :arguments)))))))

(deftest recovers-multiple-invokes-in-one-block
  (let [content (str "<｜DSML｜tool_calls>"
                     "<｜DSML｜invoke name=\"a\"><｜DSML｜parameter name=\"x\" string=\"true\">1</｜DSML｜parameter></｜DSML｜invoke>"
                     "<｜DSML｜invoke name=\"b\"><｜DSML｜parameter name=\"y\" string=\"true\">2</｜DSML｜parameter></｜DSML｜invoke>"
                     "</｜DSML｜tool_calls>")
        {:keys [tool-calls]} (dsml/recover-tool-calls
                              {:content content :tool-calls []})]
    (is (= ["a" "b"] (map #(-> % :function :name) tool-calls)))
    (is (= [{"x" "1"} {"y" "2"}]
           (map #(json/parse-string (-> % :function :arguments)) tool-calls)))))

(deftest preserves-prose-around-block
  (let [content (str "Sure, listing now. " leaked-fs-call " Done.")
        {:keys [content tool-calls]} (dsml/recover-tool-calls
                                      {:content content :tool-calls []})]
    (is (= 1 (count tool-calls)))
    (is (re-find #"Sure, listing now\." content))
    (is (re-find #"Done\." content))
    (is (not (re-find #"DSML" content)))))

(deftest noop-when-tool-calls-already-present
  (let [native [{:id "call_1" :type "function"
                 :function {:name "x" :arguments "{}"}}]
        turn {:content leaked-fs-call :tool-calls native}]
    (is (= turn (dsml/recover-tool-calls turn)))))

(deftest noop-when-no-blocks
  (let [turn {:content "just a normal answer" :tool-calls []}]
    (is (= turn (dsml/recover-tool-calls turn)))))

(deftest malformed-block-is-ignored
  (testing "open tag without close tag leaves content untouched"
    (let [content "<｜DSML｜tool_calls> <｜DSML｜invoke name=\"fs\"> oops"
          turn {:content content :tool-calls []}]
      (is (= turn (dsml/recover-tool-calls turn))))))

(deftest handles-non-string-content
  (is (= {:content nil :tool-calls []}
         (dsml/recover-tool-calls {:content nil :tool-calls []}))))
