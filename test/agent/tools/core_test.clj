(ns agent.tools.core-test
  (:require
   [agent.tools.display :as display]
   [agent.tools.core :as tools]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(deftest display-telegram-summary-includes-args-and-code-output-test
  (let [text (display/telegram-summary
              {}
              {:tool-name "web"
               :status :ok
               :input {:q "clojure"}
               :result {:answer "done"}})]
    (is (str/includes? text "🔧 web"))
    (is (str/includes? text "status: ok"))
    (is (str/includes? text "q: clojure"))
    (is (not (str/includes? text "\"q\"")))
    (is (not (str/includes? text "\"answer\"")))))

(deftest display-telegram-summary-normalizes-keyword-tool-name-test
  (let [text (display/telegram-summary
              {}
              {:tool-name :fs
               :status :denied})]
    (is (= "🔧 fs status: denied" text))))

(deftest display-args-preview-is-human-readable-not-json-test
  (is (= "url: http://example.test method: GET"
         (display/args-preview {:url "http://example.test" :method "GET"} 200))))

(deftest display-telegram-summary-escapes-html-test
  (let [text (display/telegram-summary
              {}
              {:tool-name "web"
               :input {:q "<tag>&x"}})]
    (is (str/includes? text "&lt;tag&gt;&amp;x"))))

(deftest registry-register-list-and-execute-test
  (let [events (atom [])
        hooks (atom [])
        tool (tools/create-tool
              {:description (tools/create-tool-description
                             :echo
                             "Echo tool"
                             :input-schema [:map [:message :string]]
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
                             :input-schema [:map [:message :string]]
                             :required-permissions #{:echo})
               :execute-fn (fn [input _context] input)})
        registry (-> (tools/create-registry
                      {:before-execute (fn [_] {:block true
                                                :reason "disabled"})})
                     (tools/register-tool tool))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"disabled"
                          (tools/execute-tool registry :echo {:message "hi"} {:permissions #{:echo}})))))

(deftest registry-enforces-allowed-tools-test
  (let [tool (tools/create-tool
              {:description (tools/create-tool-description
                             :echo
                             "Echo tool"
                             :input-schema [:map [:message :string]]
                             :required-permissions #{:echo})
               :execute-fn (fn [input _context] input)})
        registry (-> (tools/create-registry)
                     (tools/register-tool tool))]
    (is (= {:message "hi"}
           (tools/execute-tool registry :echo {:message "hi"}
                               {:permissions #{:echo}
                                :allowed-tools #{:echo}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Tool not allowed"
                          (tools/execute-tool registry :echo {:message "hi"}
                                              {:permissions #{:echo}
                                               :allowed-tools #{:http}})))))

(deftest registry-requires-approval-policy-for-sensitive-tools-test
  (let [tool (tools/create-tool
              {:description (tools/create-tool-description
                             :sensitive-echo
                             "Sensitive echo"
                             :input-schema [:map [:message :string]]
                             :required-permissions #{:echo}
                             :sensitive true)
               :execute-fn (fn [input _context] input)})
        registry (-> (tools/create-registry)
                     (tools/register-tool tool))
        approved-registry (tools/with-approval registry (fn [_] nil))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires approval policy"
                          (tools/execute-tool registry :sensitive-echo {:message "hi"}
                                              {:permissions #{:echo}})))
    (is (= {:message "hi"}
           (tools/execute-tool approved-registry :sensitive-echo {:message "hi"}
                               {:permissions #{:echo}})))))

(deftest registry-dedupes-tools-with-activity-context-test
  (let [calls (atom 0)
        activity-calls (atom [])
        tool (tools/create-tool
              {:description (tools/create-tool-description
                             :echo
                             "Echo tool"
                             :input-schema [:map [:message :string]]
                             :required-permissions #{:echo})
               :execute-fn (fn [input _context]
                             (swap! calls inc)
                             {:echoed (:message input)})})
        registry (-> (tools/create-registry
                      {:activity-executor (fn [activity f]
                                            (swap! activity-calls conj activity)
                                            (if (= 1 (count @activity-calls))
                                              (f)
                                              {:echoed "cached"}))})
                     (tools/register-tool tool))
        context {:permissions #{:echo}
                 :activity {:run-id "run-1"
                            :command-id "cmd-1"}}]
    (is (= {:echoed "hi"}
           (tools/execute-tool registry :echo {:message "hi"} context)))
    (is (= {:echoed "cached"}
           (tools/execute-tool registry :echo {:message "hi"} context)))
    (is (= 1 @calls))
    (is (= "run-1" (:run-id (first @activity-calls))))
    (is (= "cmd-1" (:command-id (first @activity-calls))))
    (is (re-matches #"tool\.echo\.[0-9a-f]{16}"
                    (:activity-name (first @activity-calls))))))
