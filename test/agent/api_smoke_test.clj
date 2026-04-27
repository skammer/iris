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
   [clojure.test :refer :all]))

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

(deftest memory-vault-roundtrip-smoke
  (let [vault-dir (java.io.File/createTempFile "iris-vault-" "")]
    (.delete vault-dir)
    (.mkdirs vault-dir)
    (try
      (with-server (fn [config]
                     (assoc-in config [:memory :vault]
                               {:paths [(.getAbsolutePath vault-dir)]
                                :writable? true}))
        (fn [{:keys [base-url]}]
          (let [vault-path (str (.getAbsolutePath vault-dir) "/note.txt")
                write-resp (helpers/http-post (str base-url "/v1/memory/vault/write")
                                              {:path vault-path :content "hello vault"})
                write-body (json/parse-string (:body write-resp) true)
                read-resp (helpers/http-post (str base-url "/v1/memory/vault/read")
                                             {:path vault-path})
                read-body (json/parse-string (:body read-resp) true)]
            (is (= 201 (:status write-resp)))
            (is (true? (get-in write-body [:data :written])))
            (is (= 200 (:status read-resp)))
            (is (= "hello vault" (get-in read-body [:data :content]))))))
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
      (let [{:keys [status body]} (helpers/http-get (str base-url "/ui/sessions"))]
        (is (= 200 status))
        (is (str/includes? body "Sessions"))))))

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

(deftest ui-run-detail-body-fragment-smoke
  (with-server nil
    (fn [{:keys [base-url]}]
      (let [run-resp (helpers/http-post (str base-url "/v1/runs")
                                        {:agent_id "smoke-agent"
                                         :name "smoke"
                                         :substrate "local-unsandboxed"
                                         :runner_options {:command ["sh" "-lc" "true"]
                                                          :working-dir "."}})
            run-id (get-in (json/parse-string (:body run-resp) true) [:data :id])
            {:keys [status body]} (helpers/http-get
                                   (str base-url "/ui/run-detail-body?run_id=" run-id))]
        (is (= 201 (:status run-resp)))
        (is (= 200 status))
        (is (str/includes? body run-id))))))
