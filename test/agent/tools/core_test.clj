(ns agent.tools.core-test
  (:require
   [agent.tools.display :as display]
   [agent.tools.core :as tools]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn- thrown-message
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (.getMessage e))))

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
    (is (= ["tool-execution-start" "tool-execution-end"]
           (mapv (comp name :event-type) @events)))
    (let [payload (:payload (last @events))]
      (is (= "succeeded" (:status payload)))
      (is (= "builtin" (:source payload)))
      (is (= {:message "hi"} (:input payload)))
      (is (= {:echoed "hi"} (:result payload)))
      (is (number? (:duration-ms payload))))
    (is (re-find #"Insufficient permissions"
                 (thrown-message #(tools/execute-tool registry :echo {:message "hi"} {}))))
    (is (re-find #"Unknown tool"
                 (thrown-message #(tools/execute-tool registry :missing {} {}))))))

(deftest registry-blocks-tool-via-hook-test
  (let [events (atom [])
        tool (tools/create-tool
              {:description (tools/create-tool-description
                             :echo
                             "Echo tool"
                             :input-schema [:map [:message :string]]
                             :required-permissions #{:echo})
               :execute-fn (fn [input _context] input)})
        registry (-> (tools/create-registry
                      {:event-sink #(swap! events conj %)
                       :before-execute (fn [_] {:block true
                                                :reason "disabled"})})
                     (tools/register-tool tool))]
    (is (re-find #"disabled"
                 (thrown-message #(tools/execute-tool registry :echo {:message "hi"} {:permissions #{:echo}}))))
    (let [payload (:payload (last @events))]
      (is (= "blocked" (:status payload)))
      (is (= "disabled" (:reason payload)))
      (is (number? (:duration-ms payload))))))

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
    (is (re-find #"Tool not allowed"
                 (thrown-message #(tools/execute-tool registry :echo {:message "hi"}
                                                      {:permissions #{:echo}
                                                       :allowed-tools #{:http}}))))))

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
        approved-registry (tools/with-approval registry (fn [_] {:allow true}))]
    (is (re-find #"requires approval policy"
                 (thrown-message #(tools/execute-tool registry :sensitive-echo {:message "hi"}
                                                      {:permissions #{:echo}}))))
    (is (= {:message "hi"}
           (tools/execute-tool approved-registry :sensitive-echo {:message "hi"}
                               {:permissions #{:echo}})))))

(deftest registry-emits-duration-on-failed-execution-test
  (let [events (atom [])
        tool (tools/create-tool
              {:description (tools/create-tool-description
                             :fail
                             "Fail tool"
                             :input-schema [:map [:message :string]]
                             :required-permissions #{:fail})
               :execute-fn (fn [_input _context]
                             (throw (ex-info "boom" {:type :boom})))})
        registry (-> (tools/create-registry {:event-sink #(swap! events conj %)})
                     (tools/register-tool tool))]
    (is (re-find #"boom"
                 (thrown-message #(tools/execute-tool registry :fail {:message "hi"} {:permissions #{:fail}}))))
    (let [payload (:payload (last @events))]
      (is (= "failed" (:status payload)))
      (is (= "boom" (:error payload)))
      (is (= {:message "hi"} (:input payload)))
      (is (number? (:duration-ms payload))))))

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
