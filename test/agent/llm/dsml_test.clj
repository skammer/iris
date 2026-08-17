(ns agent.llm.dsml-test
  (:require
   [agent.llm.dsml :as dsml]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]))

(def leaked-fs-call
  (str "<｜DSML｜tool_calls>  "
       "<｜DSML｜invoke name=\"fs\">  "
       "<｜DSML｜parameter name=\"action\" string=\"true\">list</｜DSML｜parameter>  "
       "<｜DSML｜parameter name=\"path\" string=\"true\">/workspace/example</｜DSML｜parameter>  "
       "</｜DSML｜invoke>  "
       "</｜DSML｜tool_calls>"))

(def doubled-leaked-fs-call
  (str "<｜｜DSML｜｜tool_calls>  "
       "<｜｜DSML｜｜invoke name=\"fs\">  "
       "<｜｜DSML｜｜parameter name=\"action\" string=\"true\">list</｜｜DSML｜｜parameter>  "
       "<｜｜DSML｜｜parameter name=\"path\" string=\"true\">/workspace/example</｜｜DSML｜｜parameter>  "
       "</｜｜DSML｜｜invoke>  "
       "</｜｜DSML｜｜tool_calls>"))

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
      (is (= {"action" "list" "path" "/workspace/example"}
             (json/parse-string (-> tc :function :arguments)))))))

(deftest recovers-doubled-delimiter-invoke
  (let [{:keys [content tool-calls]} (dsml/recover-tool-calls
                                      {:content doubled-leaked-fs-call
                                       :tool-calls []})]
    (is (= "" content))
    (is (= 1 (count tool-calls)))
    (is (= "fs" (-> tool-calls first :function :name)))
    (is (= {"action" "list" "path" "/workspace/example"}
           (json/parse-string (-> tool-calls first :function :arguments))))))

(deftest forced-stream-guard-suppresses-doubled-delimiter-without-tools
  (let [chunks (atom [])
        guard (dsml/guard-content-delta #(swap! chunks conj %) [] true)]
    (doseq [chunk ["<｜" "｜DSML｜" "｜tool_calls>" doubled-leaked-fs-call]]
      (guard chunk))
    (is (empty? @chunks))))

(deftest recovers-tool-call-function-tags
  (let [content (str "<tool_call>\n"
                     "<function=memory>\n"
                     "<parameter=query>\n"
                     "пример\n"
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
      (is (= {"query" "пример" "action" "search"}
             (json/parse-string (-> tc :function :arguments)))))))

(deftest recovers-kimi-memory-tool-call-tags
  (let [content (str "<tool_call>\n"
                     "<function=memory>\n"
                     "<parameter=query>\n"
                     "Модель: Kimi\n"
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
    (is (= {"query" "Модель: Kimi" "action" "search"}
           (json/parse-string (-> tool-calls first :function :arguments))))))

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

(deftest strips-markup-when-tool-calls-already-present
  (let [native [{:id "call_1" :type "function"
                 :function {:name "x" :arguments "{}"}}]
        turn {:content leaked-fs-call :tool-calls native}]
    (is (= (assoc turn :content "")
           (dsml/recover-tool-calls turn)))))

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
