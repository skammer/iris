(ns agent.runtime.mcp-output-test
  (:require [agent.runtime.messages :as messages]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]))
(deftest mcp-text-is-unwrapped
  (is (= "{\"items\":[]}"
         (messages/tool-output-content {:status :ok :tool-name :inventory_find
           :input {:limit 20} :result {:content [{:type "text" :text "{\"items\":[]}"}]}})))
  (let [result (messages/tool-output-content {:status :ok :tool-name :inventory_find
                 :result {:content [{:type "text" :text (apply str (repeat 100 "x"))}]}} 20)]
    (is (= (apply str (repeat 100 "x")) result))
    (is (not (.contains result "preview")))))
(deftest mcp-errors-and-blocks-survive
  (is (= "MCP tool error: not found"
         (messages/tool-output-content {:status :ok :result {:isError true :content [{:type "text" :text "not found"}]}})))
  (is (= "a\nb" (messages/tool-output-content {:status :completed :result {:content [{:type "text" :text "a"} {:type "text" :text "b"}]}})))
  (is (= "text" (messages/tool-output-content {:status :ok :result-text "text" :result {:content [{:type "text" :text "other"}]}})))
  (let [result (json/parse-string (messages/tool-output-content {:status :denied :reason "no access" :result {:content [{:type "text" :text "hidden"}]}}) true)]
    (is (= "denied" (:status result))) (is (= "no access" (:reason result))))
  (let [image {:content [{:type "image" :mimeType "image/png" :data "bytes"}]}
        result (json/parse-string (messages/tool-output-content {:status :ok :result image}) true)]
    (is (= image (:result result)))))

(deftest non-mcp-results-preserve-their-value
  (doseq [value ["skill instructions" nil 42 true ["item"]]]
    (let [output (messages/tool-output-content
                  {:status :ok :tool-name :skills_read :result value})]
      (is (= value (:result (json/parse-string output true)))))))

(deftest structured-mcp-results-preserve-both-representations
  (let [value {:content [{:type "text" :text "summary"}]
               :structuredContent {:items [1 2]}}
        output (messages/tool-output-content {:status :ok :result value})]
    (is (= value (:result (json/parse-string output true))))))
