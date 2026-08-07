(ns agent.api-smoke-test
  "Per-domain HTTP tripwires for endpoints not exercised by api-test/api-session-chat-flow-test.
   These are intentionally narrow: status code + minimal payload shape, no behavioural depth.
   Their job is to fail loudly if the api.clj refactor breaks a route's wiring."
  (:require
   [agent.api :as api]
   [agent.api-test :as helpers]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn- with-server [config-fn f]
  (let [path (helpers/temp-db-path)
        port (helpers/free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [system server]} (helpers/started-test-system path port (or config-fn identity))]
    (try
      (f {:system system :server server :base-url base-url})
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(defn- http-url-response [url]
  (let [conn (.openConnection (java.net.URL. url))
        status (.getResponseCode conn)
        stream (if (>= status 400)
                 (.getErrorStream conn)
                 (.getInputStream conn))]
    {:status status
     :content-type (.getHeaderField conn "Content-Type")
     :body (if stream (slurp stream) "")}))

(deftest telemetry-smoke
  (with-server nil
    (fn [{:keys [base-url]}]
      (let [{:keys [status body]} (helpers/http-get (str base-url "/v1/telemetry"))
            payload (json/parse-string body true)]
        (is (= 200 status))
        (is (contains? payload :data))))))

(deftest public-file-not-found-smoke
  (with-server nil
    (fn [{:keys [base-url]}]
      (let [{:keys [status body]} (helpers/http-get (str base-url "/public/does-not-exist.css"))
            payload (json/parse-string body true)]
        (is (= 404 status))
        (is (= "not_found" (:error payload)))))))

(deftest public-static-assets-smoke
  (with-server nil
    (fn [{:keys [base-url]}]
      (let [css (http-url-response (str base-url "/public/app.css"))
            js (http-url-response (str base-url "/public/web-components.js"))]
        (is (= 200 (:status css)))
        (is (= "text/css; charset=utf-8" (:content-type css)))
        (is (str/includes? (:body css) "font-family"))
        (is (= 200 (:status js)))
        (is (= "application/javascript; charset=utf-8" (:content-type js)))
        (is (str/includes? (:body js) "customElements"))))))

(deftest memory-vault-update-proposal-smoke
  (let [vault-dir (java.io.File/createTempFile "iris-vault-" "")]
    (.delete vault-dir)
    (.mkdirs vault-dir)
    (try
      (spit (io/file vault-dir "note.md")
            (str "---\n"
                 "id: mem_api_update\n"
                 "type: Reference\n"
                 "title: API update\n"
                 "description: Before\n"
                 "iris:\n"
                 "  scope: global\n"
                 "  status: approved\n"
                 "---\n\n# API update\n\nBefore body.\n"))
      (with-server (fn [config]
                     (assoc-in config [:memory :vault]
                               {:paths [(.getAbsolutePath vault-dir)]
                                :writable? true}))
        (fn [{:keys [base-url]}]
          (let [vault-path (str (.getAbsolutePath vault-dir) "/note.md")
                _ (helpers/http-post (str base-url "/v1/memory/vault/reindex") {})
                read-resp (helpers/http-post (str base-url "/v1/memory/vault/read")
                                             {:path vault-path})
                read-body (json/parse-string (:body read-resp) true)
                proposal-resp (helpers/http-post
                               (str base-url "/v1/memory/vault/propose-update")
                               {:note_id "mem_api_update"
                                :expected_revision (get-in read-body [:data :revision])
                                :changes {:description "After"
                                          :body "After body."}
                                :evidence {:user "Update it."}})
                proposal-body (json/parse-string (:body proposal-resp) true)
                read-after (json/parse-string
                            (:body (helpers/http-post
                                    (str base-url "/v1/memory/vault/read")
                                    {:path vault-path}))
                            true)]
            (is (= 200 (:status read-resp)))
            (is (= 201 (:status proposal-resp)))
            (is (= "pending" (get-in proposal-body [:data :status])))
            (is (str/includes? (get-in proposal-body [:data :diff]) "description"))
            (is (str/includes? (get-in read-after [:data :content]) "Before body.")))))
      (finally
        (doseq [f (reverse (file-seq vault-dir))]
          (io/delete-file f true))))))

(deftest events-stream-smoke
  (with-server nil
    (fn [{:keys [base-url]}]
      (let [lines (helpers/read-sse-data-lines
                   (str base-url "/v1/events/stream")
                   1
                   #(helpers/http-post (str base-url "/v1/sessions") {:title "stream-trigger"}))]
        (is (seq lines))
        (let [first-line (json/parse-string (first lines) true)]
          (is (= "event.chunk" (:object first-line)))
          (is (some? (:event first-line))))))))

(deftest ui-sessions-list-smoke
  (with-server nil
    (fn [{:keys [base-url]}]
      (let [created (helpers/http-post (str base-url "/v1/sessions") {:title "selected"})
            session-id (:id (json/parse-string (:body created) true))
            {:keys [status body]} (helpers/http-get
                                   (str base-url "/ui/sessions?session_id=" session-id))]
        (is (= 201 (:status created)))
        (is (= 200 status))
        (is (str/includes? body "Sessions"))
        (is (str/includes? body "session-link--active"))
        (is (str/includes? body (str "/ui/sessions?session_id=" session-id)))))))

(deftest ui-catalog-page-smoke
  (with-server nil
    (fn [{:keys [base-url]}]
      (let [{:keys [status body]} (helpers/http-get (str base-url "/ui"))]
        (is (= 200 status))
        (is (str/includes? body "IRIS UI SYSTEM"))
        (is (str/includes? body "id=\"workflow\""))
        (is (str/includes? body "Source of truth: DESIGN.md"))))))

(deftest ui-deep-link-pages-smoke
  (with-server nil
    (fn [{:keys [base-url]}]
      (doseq [path ["/overview" "/chat" "/tools" "/memory" "/magi" "/cron" "/logs"]]
        (let [{:keys [status body]} (helpers/http-get (str base-url path))]
          (is (= 200 status) path)
          (is (str/includes? body "datastar.js") path))))))

(deftest ui-events-page-smoke
  (with-server nil
    (fn [{:keys [base-url]}]
      (let [{:keys [status body]} (helpers/http-get (str base-url "/ui/events"))]
        (is (= 200 status))
        (is (str/includes? body "Events"))))))

(deftest ui-tool-approvals-page-smoke
  (with-server nil
    (fn [{:keys [base-url]}]
      (let [{:keys [status body]} (helpers/http-get (str base-url "/ui/tool-approvals"))]
        (is (= 200 status))
        (is (str/includes? body "Tool Approvals"))))))
