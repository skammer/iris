(ns agent.api-test
  (:require
   [agent.api :as api]
   [agent.core :as core]
   [agent.llm.core :as llm-core]
   [agent.persistence.sqlite :as sqlite]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defrecord TestProvider []
  llm-core/ILLMProvider
  (complete [_ _ _] "test-response")
  (stream [_ _ _]
    (let [ch (async/chan)]
      (async/thread
        (async/>!! ch "hello")
        (async/>!! ch " world")
        (async/close! ch))
      ch))
  (embed [_ _ _] [0.1 0.2])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-streaming false})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0}))

(extend-type TestProvider
  llm-core/ILLMProviderWithHealth
  (health-check [_] {:healthy true})
  (get-metrics [_] {:provider :test}))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "clj-agent-api-" ".db")))

(defn free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn http-client []
  (HttpClient/newHttpClient))

(defn http-get [url]
  (let [request (.build (HttpRequest/newBuilder (URI/create url)))
        response (.send (http-client) request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn http-post [url payload]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString
                            (json/generate-string payload)))
                    .build)
        response (.send (http-client) request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn http-post-legacy [url payload]
  (let [conn (.openConnection (java.net.URL. url))
        body (json/generate-string payload)]
    (.setRequestMethod conn "POST")
    (.setRequestProperty conn "Content-Type" "application/json")
    (.setDoOutput conn true)
    (with-open [writer (java.io.OutputStreamWriter. (.getOutputStream conn) "UTF-8")]
      (.write writer body))
    (let [status (.getResponseCode conn)
          stream (if (>= status 400)
                   (.getErrorStream conn)
                   (.getInputStream conn))
          response-body (if stream (slurp stream) "")]
      {:status status
       :body response-body})))

(defn sse-data-lines [body]
  (->> (str/split-lines body)
       (filter #(str/starts-with? % "data: "))
       (map #(subs % 6))
       vec))

(deftest api-session-chat-flow-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        base-system (core/create-system)
        system (assoc base-system
                      :llm-provider (->TestProvider)
                      :store (sqlite/create-store {:path path})
                      :config (assoc (:config base-system)
                                     :api {:host "127.0.0.1" :port port}
                                     :storage {:sqlite {:path path}}))
        server (api/start-server! system {:host "127.0.0.1" :port port})]
    (try
      (let [bad-session (http-post (str base-url "/v1/sessions") {:title 42})
            bad-chat (http-post (str base-url "/v1/chat/completions")
                                {:messages [{:role "bogus" :content "hello"}]})
            health (http-get (str base-url "/health"))
            created (http-post (str base-url "/v1/sessions") {:title "api-test"})
            created-body (json/parse-string (:body created) true)
            session-id (:id created-body)
            completion (http-post (str base-url "/v1/chat/completions")
                                  {:session_id session-id
                                   :prompt "hello"})
            streamed (http-post-legacy (str base-url "/v1/chat/completions")
                                       {:session_id session-id
                                        :prompt "hello"
                                        :stream true})
            streamed-lines (sse-data-lines (:body streamed))
            messages (http-get (str base-url "/v1/sessions/" session-id "/messages"))
            messages-body (json/parse-string (:body messages) true)]
        (is (= 400 (:status bad-session)))
        (is (= 400 (:status bad-chat)))
        (is (= 200 (:status health)))
        (is (= 201 (:status created)))
        (is (= 200 (:status completion)))
        (is (= 200 (:status streamed)))
        (is (some #(str/includes? % "\"content\":\"hello\"") streamed-lines))
        (is (some #(str/includes? % "\"content\":\" world\"") streamed-lines))
        (is (= "[DONE]" (last streamed-lines)))
        (is (= 4 (count (:data messages-body))))
        (is (= "test-response" (get-in messages-body [:data 1 :content])))
        (is (= "hello world" (get-in messages-body [:data 3 :content]))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))
