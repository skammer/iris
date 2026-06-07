(ns agent.mcp.core-test
  (:require
   [agent.mcp.core :as mcp]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.test :refer [deftest is]]))

(deftest streamable-http-initialize-and-call-tool-test
  (let [calls (atom [])
        client (mcp/create-http-client {:endpoint-url "https://mcp.example/mcp"})]
    (with-redefs [http/post (fn [_ request]
                              (let [body (json/parse-string (:body request) true)]
                                (swap! calls conj {:headers (:headers request)
                                                   :body body})
                                (case (:method body)
                                  "initialize"
                                  {:status 200
                                   :headers {"Content-Type" "application/json"
                                             "Mcp-Session-Id" "session-1"}
                                   :body (json/generate-string
                                          {:jsonrpc "2.0"
                                           :id (:id body)
                                           :result {:protocolVersion mcp/default-protocol-version
                                                    :capabilities {:tools {}}
                                                    :serverInfo {:name "remote"}}})}

                                  "notifications/initialized"
                                  {:status 202 :headers {} :body ""}

                                  "tools/call"
                                  {:status 200
                                   :headers {"Content-Type" "application/json"}
                                   :body (json/generate-string
                                          {:jsonrpc "2.0"
                                           :id (:id body)
                                           :result {:content [{:type "text" :text "ok"}]}})})))]
      (let [client* (mcp/initialize! client)
            result (mcp/call-tool! client* "echo" {:message "hi"})]
        (is (= "session-1" (:session-id client*)))
        (is (= {:content [{:type "text" :text "ok"}]} result))
        (is (= "tools/call" (get-in (last @calls) [:headers "Mcp-Method"])))
        (is (= "echo" (get-in (last @calls) [:headers "Mcp-Name"])))
        (is (= "session-1" (get-in (last @calls) [:headers "Mcp-Session-Id"])))))))

(deftest create-http-client-validates-endpoint-url-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"absolute http\(s\) URL"
                        (mcp/create-http-client {:endpoint-url nil})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"absolute http\(s\) URL"
                        (mcp/create-http-client {:endpoint-url "file:///tmp/mcp"})))
  (is (= "https://mcp.example/mcp"
         (:endpoint-url (mcp/create-http-client {:endpoint-url "https://mcp.example/mcp"})))))

(deftest streamable-http-retains-initialized-notification-failure-test
  (let [client (mcp/create-http-client {:endpoint-url "https://mcp.example/mcp"})]
    (with-redefs [http/post (fn [_ request]
                              (let [body (json/parse-string (:body request) true)]
                                (case (:method body)
                                  "initialize"
                                  {:status 200
                                   :headers {"Content-Type" "application/json"
                                             "Mcp-Session-Id" "session-1"}
                                   :body (json/generate-string
                                          {:jsonrpc "2.0"
                                           :id (:id body)
                                           :result {:protocolVersion mcp/default-protocol-version
                                                    :capabilities {:tools {}}
                                                    :serverInfo {:name "remote"}}})}

                                  "notifications/initialized"
                                  {:status 200
                                   :headers {"Content-Type" "application/json"}
                                   :body (json/generate-string
                                          {:jsonrpc "2.0"
                                           :id (:id body)
                                           :error {:code -32000
                                                   :message "init notification failed"}})})))]
      (let [client* (mcp/initialize! client)]
        (is (= "session-1" (:session-id client*)))
        (is (= "init notification failed"
               (get-in client* [:initialized-notification-error :message])))
        (is (= :mcp-json-rpc-error
               (get-in client* [:initialized-notification-error :type])))))))

(deftest mcp-tool-adapter-round-trips-local-descriptor-test
  (let [description (tools/create-tool-description
                     :echo
                     "Echo"
                     :input-schema [:map [:message :string]])
        mcp-tool (mcp/tool-description->mcp description)]
    (is (= "echo" (:name mcp-tool)))
    (is (= (:input-schema description) (:inputSchema mcp-tool)))))

(deftest streamable-http-parses-sse-response-test
  (let [client (mcp/create-http-client {:endpoint-url "https://mcp.example/mcp"})]
    (with-redefs [http/post (fn [_ request]
                              (let [body (json/parse-string (:body request) true)]
                                {:status 200
                                 :headers {"Content-Type" "text/event-stream"}
                                 :body (str "event: message\n"
                                            "data:" (json/generate-string
                                                     {:jsonrpc "2.0"
                                                      :id (:id body)
                                                      :result {:ok true}})
                                            "\n\n")}))]
      (is (= {:ok true}
             (mcp/rpc! client (mcp/json-rpc-request "ping" {})))))))

(deftest remote-mcp-tool-registers-and-executes-test
  (let [calls (atom [])
        client (mcp/create-http-client {:endpoint-url "https://mcp.example/mcp"})]
    (with-redefs [http/post (fn [_ request]
                              (let [body (json/parse-string (:body request) true)]
                                (swap! calls conj body)
                                (case (:method body)
                                  "tools/list"
                                  {:status 200
                                   :headers {"Content-Type" "application/json"}
                                   :body (json/generate-string
                                          {:jsonrpc "2.0"
                                           :id (:id body)
                                           :result {:tools [{:name "remote_echo"
                                                            :description "Remote echo"
                                                            :inputSchema {:type "object"
                                                                          :properties {:message {:type "string"}}
                                                                          :required ["message"]}}]}})}

                                  "tools/call"
                                  {:status 200
                                   :headers {"Content-Type" "application/json"}
                                   :body (json/generate-string
                                          {:jsonrpc "2.0"
                                           :id (:id body)
                                           :result {:ok true}})})))]
      (let [registry* (mcp/register-remote-tools! (tools/create-registry) client)
            result (tools/execute-tool registry* :remote_echo {:message "hi"} {:permissions #{:mcp-call}})]
        (is (= {:ok true} result))
        (is (= ["tools/list" "tools/call"] (mapv :method @calls)))))))

(deftest remote-mcp-tool-validates-json-schema-before-call-test
  (let [calls (atom [])
        client (mcp/create-http-client {:endpoint-url "https://mcp.example/mcp"})]
    (with-redefs [http/post (fn [_ request]
                              (let [body (json/parse-string (:body request) true)]
                                (swap! calls conj body)
                                {:status 200
                                 :headers {"Content-Type" "application/json"}
                                 :body (json/generate-string
                                        {:jsonrpc "2.0"
                                         :id (:id body)
                                         :result {:tools [{:name "remote_echo"
                                                          :description "Remote echo"
                                                          :inputSchema {:type "object"
                                                                        :properties {:message {:type "string"}}
                                                                        :required ["message"]}}]}})}))]
      (let [registry* (mcp/register-remote-tools! (tools/create-registry) client)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"input failed schema validation"
                              (tools/execute-tool registry*
                                                  :remote_echo
                                                  {:message 1}
                                                  {:permissions #{:mcp-call}})))
        (is (= ["tools/list"] (mapv :method @calls)))))))

(deftest register-remote-tools-rejects-name-collisions-test
  (let [client (mcp/create-http-client {:endpoint-url "https://mcp.example/mcp"})
        local-tool (tools/create-tool
                    {:description (tools/create-tool-description
                                   :remote_echo
                                   "Local echo"
                                   :input-schema [:map])
                     :execute-fn (fn [_ _] {:local true})})
        registry (tools/register-tool (tools/create-registry) local-tool)]
    (with-redefs [http/post (fn [_ request]
                              (let [body (json/parse-string (:body request) true)]
                                {:status 200
                                 :headers {"Content-Type" "application/json"}
                                 :body (json/generate-string
                                        {:jsonrpc "2.0"
                                         :id (:id body)
                                         :result {:tools [{:name "remote_echo"
                                                          :description "Remote echo"
                                                          :inputSchema {:type "object"}}]}})}))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"would overwrite"
                            (mcp/register-remote-tools! registry client))))))
