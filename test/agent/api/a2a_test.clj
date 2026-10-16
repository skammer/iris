(ns agent.api.a2a-test
  (:require
   [agent.api.test-support :refer [->BlockingProvider eventually free-port http-get http-post http-post-headers started-test-system temp-db-path]]
   [agent.api :as api]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(deftest a2a-message-send-is-async-and-pollable-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        started (promise)
        response (promise)
        calls (atom 0)
        provider (->BlockingProvider started response calls (atom nil))
        {:keys [server]} (started-test-system
                           path
                           port
                           #(assoc-in % [:memory :notes :extractor :enabled] false)
                           provider)]
    (try
      (let [submitted (http-post (str base-url "/message:send")
                                 {:message {:messageId "msg-a2a-1"
                                            :role "ROLE_USER"
                                            :parts [{:text "do async"}]}})
            submitted-body (json/parse-string (:body submitted) true)
            task-id (get-in submitted-body [:task :id])
            context-id (get-in submitted-body [:task :contextId])]
        (is (= 200 (:status submitted)))
        (is (string? task-id))
        (is (string? context-id))
        (is (= "TASK_STATE_SUBMITTED" (get-in submitted-body [:task :status :state])))
        (is (true? (deref started 1000 false)))
        (is (eventually
             #(let [body (-> (http-get (str base-url "/tasks/" task-id))
                             :body
                             (json/parse-string true))]
                (= "TASK_STATE_WORKING" (get-in body [:task :status :state])))))
        (deliver response "async-final")
        (let [completed (eventually
                         #(let [body (-> (http-get (str base-url "/tasks/" task-id "?historyLength=2"))
                                         :body
                                         (json/parse-string true))]
                            (when (= "TASK_STATE_COMPLETED" (get-in body [:task :status :state]))
                              body)))]
          (is completed)
          (is (= "async-final"
                 (get-in completed [:task :artifacts 0 :parts 0 :text])))
          (is (= ["do async" "async-final"]
                 (mapv #(get-in % [:parts 0 :text])
                       (get-in completed [:task :history]))))
          (is (= 1 @calls))))
      (finally
        (deliver response "late")
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest a2a-message-send-is-idempotent-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        started (promise)
        response (promise)
        calls (atom 0)
        provider (->BlockingProvider started response calls (atom nil))
        payload {:message {:messageId "msg-a2a-idem"
                           :role "ROLE_USER"
                           :parts [{:text "same task"}]}}
        {:keys [server]} (started-test-system
                           path
                           port
                           #(assoc-in % [:memory :notes :extractor :enabled] false)
                           provider)]
    (try
      (let [first-body (-> (http-post-headers (str base-url "/message:send")
                                              payload
                                              {"Idempotency-Key" "idem-1"})
                           :body
                           (json/parse-string true))
            second-body (-> (http-post-headers (str base-url "/message:send")
                                               payload
                                               {"Idempotency-Key" "idem-1"})
                            :body
                            (json/parse-string true))]
        (is (= (get-in first-body [:task :id])
               (get-in second-body [:task :id])))
        (is (true? (deref started 1000 false)))
        (deliver response "done once")
        (is (eventually #(= 1 @calls))))
      (finally
        (deliver response "late")
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest a2a-task-cancel-marks-task-canceled-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        started (promise)
        response (promise)
        provider (->BlockingProvider started response (atom 0) (atom nil))
        {:keys [server]} (started-test-system
                           path
                           port
                           #(assoc-in % [:memory :notes :extractor :enabled] false)
                           provider)]
    (try
      (let [submitted-body (-> (http-post (str base-url "/message:send")
                                          {:message {:messageId "msg-a2a-cancel"
                                                     :role "ROLE_USER"
                                                     :parts [{:text "wait"}]}})
                               :body
                               (json/parse-string true))
            task-id (get-in submitted-body [:task :id])
            _ (is (true? (deref started 1000 false)))
            cancel-body (-> (http-post (str base-url "/tasks/" task-id ":cancel") {})
                            :body
                            (json/parse-string true))]
        (is (= "TASK_STATE_CANCELED" (get-in cancel-body [:task :status :state])))
        (is (= "Task canceled"
               (get-in cancel-body [:task :status :message :parts 0 :text]))))
      (finally
        (deliver response "late")
        (api/stop-server! server)
        (io/delete-file path true)))))

(deftest a2a-rejects-non-text-parts-test
  (let [path (temp-db-path)
        port (free-port)
        base-url (str "http://127.0.0.1:" port)
        {:keys [server]} (started-test-system path port identity)]
    (try
      (let [response (http-post (str base-url "/message:send")
                                {:message {:messageId "msg-a2a-file"
                                           :role "ROLE_USER"
                                           :parts [{:raw "AAAA"
                                                    :mediaType "application/octet-stream"}]}})
            body (json/parse-string (:body response) true)]
        (is (= 400 (:status response)))
        (is (= "CONTENT_TYPE_NOT_SUPPORTED"
               (get-in body [:error :details 0 :reason]))))
      (finally
        (api/stop-server! server)
        (io/delete-file path true)))))
