(ns agent.tools.common.http-test
  (:require
   [agent.tools.common.http :as http-tool]
   [agent.tools.core :as tools]
   [clj-http.client :as http]
   [clojure.test :refer :all]))

(deftest http-tool-executes-successful-request
  (with-redefs [http/request (fn [request]
                               {:status 200
                                :headers {"content-type" "application/json"}
                                :body "{\"ok\":true}"
                                :request request})]
    (let [registry (-> (tools/create-registry)
                       (tools/register-tool (http-tool/create-http-tool {:default-headers {"X-Test" "1"}})))
          result (tools/execute-tool registry
                                     :http
                                     {:url "https://example.com"
                                      :method "GET"}
                                     {:permissions #{:http-request}})]
      (is (= 200 (:status result)))
      (is (= true (get-in result [:body :ok]))))))

(deftest http-tool-validates-and-raises-on-http-error
  (with-redefs [http/request (fn [_]
                               {:status 500
                                :headers {}
                                :body "{\"error\":\"boom\"}"})]
    (let [registry (-> (tools/create-registry)
                       (tools/register-tool (http-tool/create-http-tool {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"url must be a non-blank string"
                            (tools/execute-tool registry :http {:url ""} {:permissions #{:http-request}})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"HTTP request failed: 500"
                            (tools/execute-tool registry
                                                :http
                                                {:url "https://example.com"}
                                                {:permissions #{:http-request}}))))))
