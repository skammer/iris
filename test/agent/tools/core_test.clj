(ns agent.tools.core-test
  (:require
   [agent.tools.core :as tools]
   [clojure.test :refer :all]))

(deftest registry-register-list-and-execute-test
  (let [events (atom [])
        hooks (atom [])
        tool (tools/create-tool
              {:description (tools/create-tool-description
                             :echo
                             "Echo tool"
                             :required-permissions #{:echo}
                             :source :builtin)
               :validate-fn (fn [input]
                              (when-not (string? (:message input))
                                (throw (tools/validation-error "message must be a string" {:input input})))
                              input)
               :execute-fn (fn [input _context]
                             {:echoed (:message input)})})
        registry (-> (tools/create-registry
                      {:event-sink #(swap! events conj %)
                       :before-execute #(swap! hooks conj [:before (get-in % [:tool :name])])
                       :after-execute #(do (swap! hooks conj [:after (get-in % [:tool :name])])
                                           {:result (:result %)})})
                     (tools/register-tool tool))]
    (is (= [:echo] (mapv :name (tools/list-tools registry))))
    (is (= {:echoed "hi"}
           (tools/execute-tool registry :echo {:message "hi"} {:permissions #{:echo}})))
    (is (= [[:before :echo] [:after :echo]] @hooks))
    (is (= ["tool.execution.requested" "tool.execution.succeeded"]
           (mapv (comp name :event-type) @events)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Insufficient permissions"
                          (tools/execute-tool registry :echo {:message "hi"} {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown tool"
                          (tools/execute-tool registry :missing {} {})))))

(deftest registry-blocks-tool-via-hook-test
  (let [tool (tools/create-tool
              {:description (tools/create-tool-description
                             :echo
                             "Echo tool"
                             :required-permissions #{:echo})
               :execute-fn (fn [input _context] input)})
        registry (-> (tools/create-registry
                      {:before-execute (fn [_] {:block true
                                                :reason "disabled"})})
                     (tools/register-tool tool))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"disabled"
                          (tools/execute-tool registry :echo {:message "hi"} {:permissions #{:echo}})))))
