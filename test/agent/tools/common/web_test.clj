(ns agent.tools.common.web-test
  (:require
   [agent.tools.common.web :as web-tool]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]])
  (:import
   [java.net InetAddress]))

(defn public-resolver [_]
  [(InetAddress/getByName "93.184.216.34")])

(defn registry [opts]
  (reduce tools/register-tool
          (tools/create-registry)
          (web-tool/create-web-tools (merge {:resolve-host-fn public-resolver} opts))))

(def context
  {:permissions #{:http-request}
   :request-id "request-1"
   :session-id "session-1"})

(deftest web-search-uses-searchharvester-first
  (let [requests (atom [])]
    (with-redefs [client/post (fn [url opts]
                               (swap! requests conj [url (json/parse-string (:body opts) true)])
                               {:status 200
                                :body (json/generate-string
                                       {:query "ladybug"
                                        :results [{:title "LadybugDB"
                                                   :url "https://ladybugdb.com"
                                                   :content "Graph database"}]})})]
      (let [result (tools/execute-tool (registry {})
                                       :web_search
                                       {:query "ladybug" :max-results 3}
                                       context)]
        (is (= "searchharvester" (:provider result)))
        (is (= "https://ladybugdb.com" (get-in result [:results 0 :url])))
        (is (= [["http://127.0.0.1:8000/search"
                 {:query "ladybug" :max_results 3}]]
               @requests))))))

(deftest web-search-falls-back-to-tavily
  (let [requests (atom [])]
    (with-redefs [client/post (fn [url opts]
                               (swap! requests conj [url opts])
                               (if (str/includes? url "127.0.0.1")
                                 {:status 503 :body "unavailable"}
                                 {:status 200
                                  :body (json/generate-string
                                         {:query "ladybug"
                                          :results [{:title "LadybugDB"
                                                     :url "https://ladybugdb.com"
                                                     :content "Graph database"
                                                     :score 0.9}]})}))]
      (let [result (tools/execute-tool (registry {:tavily {:api-key "test-key"}})
                                       :web_search
                                       {:query "ladybug" :depth "advanced"}
                                       context)
            [tavily-url tavily-opts] (second @requests)
            tavily-body (json/parse-string (:body tavily-opts) true)]
        (is (= "tavily" (:provider result)))
        (is (= 2 (count @requests)))
        (is (= "https://api.tavily.com/search" tavily-url))
        (is (= "Bearer test-key" (get-in tavily-opts [:headers "Authorization"])))
        (is (= "advanced" (:search_depth tavily-body)))))))

(deftest web-extract-normalizes-url-and-caches-per-request
  (let [calls (atom 0)]
    (with-redefs [client/post (fn [_url _opts]
                               (swap! calls inc)
                               {:status 200
                                :body (json/generate-string
                                       {:url "https://example.com/docs"
                                        :title "Docs"
                                        :content "# Documentation"})})]
      (let [registry* (registry {})
            first-result (tools/execute-tool registry*
                                             :web_extract
                                             {:url "https://EXAMPLE.com:443/docs/"}
                                             context)
            cached-result (tools/execute-tool registry*
                                              :web_extract
                                              {:url "https://example.com/docs"}
                                              context)
            next-run-result (tools/execute-tool registry*
                                                :web_extract
                                                {:url "https://example.com/docs"}
                                                (assoc context :request-id "request-2"))]
        (is (= 2 @calls))
        (is (= "https://example.com/docs" (:url first-result)))
        (is (false? (:cached first-result)))
        (is (true? (:cached cached-result)))
        (is (false? (:cached next-run-result)))))))

(deftest web-extract-falls-back-to-tavily-advanced
  (let [requests (atom [])]
    (with-redefs [client/post (fn [url opts]
                               (swap! requests conj [url opts])
                               (if (str/includes? url "127.0.0.1")
                                 {:status 422 :body "cannot extract"}
                                 {:status 200
                                  :body (json/generate-string
                                         {:results [{:url "https://example.com/article"
                                                     :title "Article"
                                                     :raw_content "# Clean Markdown"}]
                                          :request_id "tavily-request"})}))]
      (let [result (tools/execute-tool (registry {:tavily {:api-key "test-key"}})
                                       :web_extract
                                       {:url "https://example.com/article"
                                        :query "examples"
                                        :chunks-per-source 2}
                                       context)
            [_ tavily-opts] (second @requests)
            tavily-body (json/parse-string (:body tavily-opts) true)]
        (is (= "tavily" (:provider result)))
        (is (= "# Clean Markdown" (:content result)))
        (is (= "advanced" (:extract_depth tavily-body)))
        (is (= "markdown" (:format tavily-body)))
        (is (= 2 (:chunks_per_source tavily-body)))))))

(deftest web-extract-bounds-content
  (with-redefs [client/post (fn [_url _opts]
                             {:status 200
                              :body (json/generate-string
                                     {:url "https://example.com"
                                      :content "abcdefghij"})})]
    (let [result (tools/execute-tool (registry {})
                                     :web_extract
                                     {:url "https://example.com" :max-chars 4}
                                     context)]
      (is (= "abcd" (:content result)))
      (is (= 10 (:chars result)))
      (is (= 4 (:returned-chars result)))
      (is (true? (:truncated result))))))

(deftest web-extract-rejects-private-url
  (let [registry* (registry {:resolve-host-fn (fn [_]
                                                [(InetAddress/getByName "127.0.0.1")])})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"non-public"
                          (tools/execute-tool registry*
                                              :web_extract
                                              {:url "http://localhost/private"}
                                              context)))))

(deftest web-tools-have-provider-independent-contracts
  (let [descriptions (mapv tools/describe (web-tool/create-web-tools {}))]
    (is (= [:web_search :web_extract] (mapv :name descriptions)))
    (is (every? #(= #{:http-request} (:required-permissions %)) descriptions))
    (is (every? :parallel-safe? descriptions))))
