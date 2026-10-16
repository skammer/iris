(ns agent.api.ui-streaming-test
  (:require
   [agent.api.test-support :refer [free-port http-post http-post-form http-post-multipart started-test-system temp-db-path]]
   [agent.api :as api]
   [agent.persistence.sqlite :as sqlite]
   [agent.ui :as ui]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]])
  (:import
   (java.net URL)))

(deftest ui-chat-streams-partial-patch-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system path port identity)]
    (try
      (let [created (http-post (str base-url "/v1/sessions") {:title "stream"})
            session-id (:id (json/parse-string (:body created) true))
            response (http-post-form (str base-url "/ui/chat")
                                     (str "session_id=" session-id "&prompt=hello"))
            body (:body response)
            stream-idx (str/index-of body "message--streaming")
            final-idx (str/last-index-of body "hello world")]
        (is (= 200 (:status response)))
        (is (some? stream-idx))
        (is (some? final-idx))
        (is (< stream-idx final-idx)))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest ui-chat-accepts-image-upload-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [system server]} (started-test-system
                                  path
                                  port
                                  #(assoc-in % [:memory :notes :extractor :enabled] false))]
    (try
      (let [created (http-post (str base-url "/v1/sessions") {:title "upload"})
            session-id (:id (json/parse-string (:body created) true))
            image-bytes (.getBytes "image-bytes" "UTF-8")
            response (http-post-multipart
                      (str base-url "/ui/chat")
                      [{:name "session_id" :value session-id}
                       {:name "prompt" :value "inspect"}
                       {:name "image"
                        :filename "photo.png"
                        :content-type "image/png"
                        :bytes image-bytes}])
            user-message (last (filter #(= "user" (:role %))
                                       @(:messages* (:llm-provider system))))]
        (is (= 200 (:status response)))
        (is (= [{:type :text :text "inspect"}
                {:type :image
                 :source {:type :base64
                          :media-type "image/png"
                          :value (.encodeToString (java.util.Base64/getEncoder)
                                                  image-bytes)}
                 :filename "photo.png"
                 :alt "photo.png"}]
               (:content user-message))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest ui-chat-keeps-history-out-of-token-patches-test
  (let [path (temp-db-path)
        port (free-port)
        {:keys [server system]} (started-test-system path port identity)
        sid (:id (sqlite/create-session! (:store system) "bounded stream"))
        renders (atom 0)
        original ui/session-messages-fragment]
    (try
      (dotimes [_ 60] (sqlite/append-message! (:store system) sid "user" "unchanged-history-marker"))
      (let [response (with-redefs [ui/session-messages-fragment
                                  (fn [system sid & [opts]]
                                    (swap! renders inc)
                                    (original system sid (or opts {})))]
                       (http-post-form (str "http://127.0.0.1:" port "/ui/chat")
                                       (str "session_id=" sid "&prompt=hello&client_id=bounded-test")))
            body (:body response)]
        (is (= 200 (:status response)))
        (is (str/includes? body "message--streaming"))
        (is (str/includes? body "hello world"))
        (is (<= @renders 3))
        (is (<= (count (re-seq #"unchanged-history-marker" body)) 180)))
      (finally (api/stop-server! server) (sqlite/close-store! (:store system)) (io/delete-file path true)))))

(deftest ui-live-disconnect-releases-subscription-test
  (let [path (temp-db-path)
        port (free-port)
        {:keys [server system]} (started-test-system path port identity)
        sid (:id (sqlite/create-session! (:store system) "disconnect"))
        streams (deref (ns-resolve 'agent.api.handlers.ui 'ui-session-streams))
        client-id (str "disconnect-" sid)
        await-state (fn [pred]
                      (loop [attempt 0]
                        (cond (pred) true
                              (< attempt 100) (do (Thread/sleep 20) (recur (inc attempt)))
                              :else false)))]
    (try
      ;; Repeated connections catch stale channel attachments as well as first disconnect.
      (dotimes [_ 3]
        (let [connection (.openConnection (URL. (str "http://127.0.0.1:" port
                                                    "/ui/session/live?session_id=" sid
                                                    "&client_id=" client-id)))]
          (.setReadTimeout connection 3000)
          (with-open [input (.getInputStream connection)]
            (is (= 58 (.read input)))
            (is (await-state #(contains? @streams client-id)))
            (.disconnect connection))
          (is (await-state #(not (contains? @streams client-id))))))
      (finally (api/stop-server! server) (sqlite/close-store! (:store system)) (io/delete-file path true)))))
