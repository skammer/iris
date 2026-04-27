(ns agent.ui-test
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.ui :as ui]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(defn- temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-ui-" ".db")))

(deftest index-page-uses-datastar-and-web-components
  (let [html (ui/index-page)]
    (is (str/includes? html "datastar.js"))
    (is (str/includes? html "/public/web-components.js"))
    (is (not (str/includes? html "/public/app.js")))))

(deftest session-message-content-is-escaped
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [session (sqlite/create-session! store "xss")
            payload "<script>alert(1)</script><img src=\"x\" onerror=\"alert(2)\"> **bold**"]
        (sqlite/append-message! store (:id session) "user" payload)
        (let [html (ui/session-messages-fragment {:store store} (:id session))]
          (is (str/includes? html "&lt;script&gt;alert(1)&lt;/script&gt;"))
          (is (str/includes? html "**bold**"))
          (is (not (str/includes? html "<script")))
          (is (not (str/includes? html "<img")))
          (is (not (str/includes? html "onerror=\"alert(2)\"")))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest memory-search-message-content-is-escaped
  (let [payload "<img src=\"x\" onerror=\"alert(1)\"> [link](javascript:alert(1))"
        html (ui/memory-search-results-fragment
              {:query "<script>alert(1)</script>"
               :messages [{:session-id "session-1"
                           :role "assistant"
                           :content payload
                           :created-at "2026-04-19T00:00:00Z"}]
               :events []})]
    (is (str/includes? html "&lt;img src=&quot;x&quot; onerror=&quot;alert(1)&quot;&gt;"))
    (is (str/includes? html "[link](javascript:alert(1))"))
    (is (not (str/includes? html "<script")))
    (is (not (str/includes? html "<img")))
    (is (not (str/includes? html "onerror=\"alert(1)\"")))))
