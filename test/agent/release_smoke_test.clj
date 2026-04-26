(ns agent.release-smoke-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer :all])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
   (java.util UUID)))

(def image-tag "clj-agent:release-smoke")
(def api-key "release-smoke-secret")

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- run-command! [argv]
  (let [process (-> (ProcessBuilder. ^java.util.List argv)
                    (.redirectErrorStream true)
                    .start)
        output (slurp (.getInputStream process))
        exit-code (.waitFor process)]
    (when-not (zero? exit-code)
      (throw (ex-info (str "Command failed: " (str/join " " argv))
                      {:exit-code exit-code
                       :output output})))
    output))

(defn- docker-available? []
  (try
    (run-command! ["docker" "info"])
    true
    (catch Exception _
      false)))

(defn- request
  ([method url] (request method url nil nil))
  ([method url body headers]
   (let [builder (HttpRequest/newBuilder (URI/create url))
         builder (reduce-kv (fn [b k v] (.header b k v)) builder (or headers {}))
         builder (if body
                   (-> builder
                       (.header "Content-Type" "application/json")
                       (.method method (HttpRequest$BodyPublishers/ofString
                                        (json/generate-string body))))
                   (.method builder method (HttpRequest$BodyPublishers/noBody)))
         response (.send (HttpClient/newHttpClient)
                         (.build builder)
                         (HttpResponse$BodyHandlers/ofString))]
     {:status (.statusCode response)
      :body (.body response)})))

(defn- wait-for-health! [base-url]
  (loop [attempt 0]
    (let [response (try
                     (request "GET" (str base-url "/health"))
                     (catch Exception e
                       {:status 0 :body (.getMessage e)}))]
      (cond
        (= 200 (:status response)) response
        (< attempt 60) (do (Thread/sleep 1000)
                           (recur (inc attempt)))
        :else (throw (ex-info "Container health check failed"
                              {:last-response response}))))))

(deftest release-smoke-builds-image-and-checks-runtime-test
  (if-not (= "1" (System/getenv "AGENT_RELEASE_SMOKE"))
    (is true "Set AGENT_RELEASE_SMOKE=1 to run Docker release smoke test.")
    (do
      (when-not (docker-available?)
        (throw (ex-info "Docker unavailable" {})))
      (let [port (free-port)
            container-name (str "clj-agent-release-smoke-" (UUID/randomUUID))
            base-url (str "http://127.0.0.1:" port)
            auth {"X-Api-Key" api-key}]
        (try
          (run-command! ["docker" "build" "-t" image-tag "."])
          (run-command! ["docker" "run" "-d" "--rm"
                         "--name" container-name
                         "-p" (str "127.0.0.1:" port ":8080")
                         "-e" (str "AGENT_API_KEY=" api-key)
                         "-e" "AGENT_FACT_EXTRACTOR_ENABLED=false"
                         "-e" "AGENT_SQLITE_PATH=/app/data/release-smoke.db"
                         image-tag])
          (let [health (wait-for-health! base-url)
                session (request "POST"
                                 (str base-url "/v1/sessions")
                                 {:title "release smoke"}
                                 auth)
                session-body (json/parse-string (:body session) true)
                fact (request "POST"
                              (str base-url "/v1/memory/facts")
                              {:subject "release"
                               :predicate "writes"
                               :object "memory"
                               :scope {:type "global"}}
                              auth)
                search (request "POST"
                                (str base-url "/v1/memory/facts/search")
                                {:query "release"
                                 :scope {:type "global"}}
                                auth)
                search-body (json/parse-string (:body search) true)]
            (is (= 200 (:status health)))
            (is (= 201 (:status session)))
            (is (string? (get-in session-body [:data :id])))
            (is (= 201 (:status fact)))
            (is (= 200 (:status search)))
            (is (= ["release"] (mapv :subject (:data search-body)))))
          (finally
            (try
              (run-command! ["docker" "rm" "-f" container-name])
              (catch Exception _ nil))))))))
