(ns agent.tools.core-test
  (:require
   [agent.tools.core :as tools]
   [clojure.test :refer :all]))

(deftest registry-register-list-and-execute-test
  (let [tool (tools/create-tool
              {:description (tools/create-tool-description
                             :echo
                             "Echo tool"
                             :required-permissions #{:echo})
               :validate-fn (fn [input]
                              (when-not (string? (:message input))
                                (throw (tools/validation-error "message must be a string" {:input input})))
                              input)
               :execute-fn (fn [input _context]
                             {:echoed (:message input)})})
        registry (-> (tools/create-registry)
                     (tools/register-tool tool))]
    (is (= [:echo] (mapv :name (tools/list-tools registry))))
    (is (= {:echoed "hi"}
           (tools/execute-tool registry :echo {:message "hi"} {:permissions #{:echo}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Insufficient permissions"
                          (tools/execute-tool registry :echo {:message "hi"} {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown tool"
                          (tools/execute-tool registry :missing {} {})))))
