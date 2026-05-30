(ns agent.tools.common.http-test
  (:require
   [agent.tools.common.http :as http-tool]
   [agent.tools.core :as tools]
   [clj-http.client :as http]
   [clojure.test :refer :all])
  (:import
   [java.net InetAddress]))

(defn public-resolver [_]
  [(InetAddress/getByName "93.184.216.34")])

(deftest http-tool-executes-successful-request
  (with-redefs [http/request (fn [request]
                               {:status 200
                                :headers {"content-type" "application/json"}
                                :body "{\"ok\":true}"
                                :request request})]
    (let [registry (-> (tools/create-registry)
                       (tools/register-tool (http-tool/create-http-tool {:default-headers {"X-Test" "1"}
                                                                         :resolve-host-fn public-resolver})))
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
                       (tools/register-tool (http-tool/create-http-tool {:resolve-host-fn public-resolver})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"url must be a non-blank string"
                            (tools/execute-tool registry :http {:url ""} {:permissions #{:http-request}})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"HTTP request failed: 500"
                            (tools/execute-tool registry
                                                :http
                                                {:url "https://example.com"}
                                                {:permissions #{:http-request}}))))))

(deftest http-tool-blocks-private-resolutions
  (let [registry (-> (tools/create-registry)
                     (tools/register-tool
                      (http-tool/create-http-tool {:resolve-host-fn (fn [_]
                                                                      [(InetAddress/getByName "127.0.0.1")])})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"non-public"
                          (tools/execute-tool registry
                                              :http
                                              {:url "https://example.com"}
                                              {:permissions #{:http-request}})))))

(deftest http-tool-blocks-ipv4-mapped-private-resolutions
  (let [mapped-loopback (InetAddress/getByAddress
                         (byte-array [0 0 0 0 0 0 0 0 0 0 -1 -1 127 0 0 1]))
        registry (-> (tools/create-registry)
                     (tools/register-tool
                      (http-tool/create-http-tool {:resolve-host-fn (fn [_] [mapped-loopback])})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"non-public"
                          (tools/execute-tool registry
                                              :http
                                              {:url "https://example.com"}
                                              {:permissions #{:http-request}})))))

(deftest http-tool-pins-validated-dns-addresses
  (let [request-seen (atom nil)
        resolutions (atom [(InetAddress/getByName "93.184.216.34")
                           (InetAddress/getByName "127.0.0.1")])]
    (with-redefs [http/request (fn [request]
                                 (reset! request-seen request)
                                 {:status 200
                                  :headers {}
                                  :body "ok"})]
      (let [registry (-> (tools/create-registry)
                         (tools/register-tool
                          (http-tool/create-http-tool
                           {:resolve-host-fn (fn [_]
                                               [(let [address (first @resolutions)]
                                                  (swap! resolutions subvec 1)
                                                  address)])})))
            result (tools/execute-tool registry
                                       :http
                                       {:url "https://example.com"}
                                       {:permissions #{:http-request}})
            pinned (vec (.resolve (:dns-resolver @request-seen) "example.com"))]
        (is (= "ok" (:body result)))
        (is (= ["93.184.216.34"] (mapv #(.getHostAddress %) pinned)))))))

(deftest http-tool-enforces-max-response-bytes
  (with-redefs [http/request (fn [_]
                               {:status 200
                                :headers {}
                                :body "too large"})]
    (let [registry (-> (tools/create-registry)
                       (tools/register-tool
                        (http-tool/create-http-tool {:resolve-host-fn public-resolver
                                                     :max-response-bytes 3})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"max-response-bytes"
                            (tools/execute-tool registry
                                                :http
                                                {:url "https://example.com"}
                                                {:permissions #{:http-request}}))))))

(deftest http-tool-rejects-invalid-json-response
  (with-redefs [http/request (fn [_]
                               {:status 200
                                :headers {"Content-Type" "application/json"}
                                :body "not-json"})]
    (let [registry (-> (tools/create-registry)
                       (tools/register-tool
                        (http-tool/create-http-tool {:resolve-host-fn public-resolver})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"declared JSON"
                            (tools/execute-tool registry
                                                :http
                                                {:url "https://example.com"}
                                                {:permissions #{:http-request}}))))))
