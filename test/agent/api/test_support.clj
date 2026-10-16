(ns agent.api.test-support
  (:require
   [agent.api :as api]
   [agent.config :as cfg]
   [agent.system :as system]
   [agent.llm.core :as llm-core]
   [agent.llm.messages :as llm-messages]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   (java.io BufferedReader ByteArrayInputStream ByteArrayOutputStream InputStreamReader)
   (java.net URI)
   (java.net URL)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
   (java.util.zip GZIPInputStream)))

(defn gunzip-string [bytes]
  (with-open [input (GZIPInputStream. (ByteArrayInputStream. bytes))]
    (slurp input)))

(defrecord TestProvider [messages*]
  llm-core/ILLMProvider
  (complete [_ messages _]
    (reset! messages* messages)
    "test-response")
  (stream [_ messages _]
    (reset! messages* messages)
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
  llm-core/ILLMProviderInvoke
  (invoke [provider {:keys [messages on-content-delta] :as request}]
    (reset! (:messages* provider) messages)
    (llm-core/normalize-llm-response
     (if on-content-delta
       (do
         (on-content-delta "hello")
         (on-content-delta " world")
         "hello world")
       "test-response")
     request))
  (generate [provider messages opts]
    (llm-core/invoke provider (assoc opts :messages messages))))

(defn message-text [message]
  (llm-messages/content-text message))

(extend-type TestProvider
  llm-core/ILLMProviderWithHealth
  (health-check [_] {:healthy true})
  (get-metrics [_] {:provider :test}))

(defrecord BlockingProvider [started response calls messages*]
  llm-core/ILLMProvider
  (complete [_ messages _]
    (reset! messages* messages)
    "fallback-response")
  (stream [_ messages _]
    (reset! messages* messages)
    (async/to-chan! []))
  (embed [_ _ _] [0.1 0.2])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-tools true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ {:keys [messages] :as request}]
    (swap! calls inc)
    (reset! messages* messages)
    (deliver started true)
    (llm-core/normalize-llm-response @response request))
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-api-" ".db")))

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

(defn add-headers [builder headers]
  (reduce-kv (fn [b k v] (.header b k v))
             builder
             headers))

(defn http-get-headers [url headers]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (add-headers headers)
                    .build)
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

(defn http-post-headers [url payload headers]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Content-Type" "application/json")
                    (add-headers headers)
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

(defn http-post-form [url form-body]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Content-Type" "application/x-www-form-urlencoded")
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString form-body))
                    .build)
        response (.send (http-client) request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)}))

(defn http-post-multipart [url parts]
  (let [boundary (str "iris-boundary-" (System/nanoTime))
        out (ByteArrayOutputStream.)]
    (doseq [{:keys [name value filename content-type bytes]} parts]
      (.write out (.getBytes (str "--" boundary "\r\n") "UTF-8"))
      (.write out (.getBytes (str "Content-Disposition: form-data; name=\"" name "\""
                                  (when filename (str "; filename=\"" filename "\""))
                                  "\r\n")
                              "UTF-8"))
      (when content-type
        (.write out (.getBytes (str "Content-Type: " content-type "\r\n") "UTF-8")))
      (.write out (.getBytes "\r\n" "UTF-8"))
      (.write out (if bytes bytes (.getBytes (str value) "UTF-8")))
      (.write out (.getBytes "\r\n" "UTF-8")))
    (.write out (.getBytes (str "--" boundary "--\r\n") "UTF-8"))
    (let [request (-> (HttpRequest/newBuilder (URI/create url))
                      (.header "Content-Type" (str "multipart/form-data; boundary=" boundary))
                      (.POST (java.net.http.HttpRequest$BodyPublishers/ofByteArray
                              (.toByteArray out)))
                      .build)
          response (.send (http-client) request (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response)
       :body (.body response)})))

(defn sse-data-lines [body]
  (->> (str/split-lines body)
       (filter #(str/starts-with? % "data: "))
       (map #(subs % 6))
       vec))

(defn read-sse-data-lines
  [url line-count trigger-fn]
  (let [result (promise)
        worker (future
                 (with-open [stream (.getInputStream (.openConnection (URL. url)))
                             reader (BufferedReader. (InputStreamReader. stream))]
                   (let [lines (loop [acc []]
                                 (if (>= (count acc) line-count)
                                   acc
                                   (if-let [line (.readLine reader)]
                                     (recur (if (str/starts-with? line "data: ")
                                              (conj acc (subs line 6))
                                              acc))
                                     acc)))]
                     (deliver result lines))))]
    (Thread/sleep 250)
    (trigger-fn)
    (let [lines (deref result 10000 nil)]
      (future-cancel worker)
      lines)))

(defn eventually [f]
  (let [deadline (+ (System/currentTimeMillis) 3000)]
    (loop []
      (let [value (f)]
        (cond
          value value
          (< (System/currentTimeMillis) deadline) (do (Thread/sleep 25) (recur))
          :else false)))))

(defn started-test-system
  ([path port config-fn]
   (started-test-system path port config-fn (->TestProvider (atom nil))))
  ([path port config-fn provider]
   (let [config (-> (cfg/load-config)
                    (assoc :llm (:llm cfg/default-config)
                           :chat (:chat cfg/default-config)
                           :api {:host "127.0.0.1" :port port}
                           :storage {:sqlite {:path path}})
                    config-fn)
         system (assoc (with-redefs [cfg/load-config (constantly config)]
                         (system/create-system))
                       :llm-provider provider)]
     (try
       (let [server (api/start-server! system {:host "127.0.0.1" :port port})]
         {:system system
          :server (fn [& args]
                    (try
                      (apply server args)
                      (finally (system/close-system! system))))})
       (catch Throwable error
         (system/close-system! system)
         (throw error))))))
