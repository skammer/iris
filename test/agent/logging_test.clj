(ns agent.logging-test
  (:require
   [agent.logging :as logging]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.brunobonacci.mulog :as mulog]
   [clojure.test :refer [deftest is]]))

(deftest start-and-write-log-file
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "iris-logging-" (System/nanoTime)))
        file (io/file dir "agent.log")]
    (.mkdirs dir)
    (try
      (logging/stop!)
      (logging/start! {:enabled true
                       :file {:path (.getAbsolutePath file)}
                       :context {:service-name "iris-test"}})
      (logging/log! :agent.logging/test-event {:value 42})
      (Thread/sleep 250)
      (logging/stop!)
      (Thread/sleep 150)
      (is (.exists file))
      (is (str/includes? (slurp file) "agent.logging/test-event"))
      (finally
        (logging/stop!)
        (io/delete-file file true)
        (io/delete-file dir true)))))

(deftest masks-sensitive-log-fields
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "iris-logging-mask-" (System/nanoTime)))
        file (io/file dir "agent.log")]
    (.mkdirs dir)
    (try
      (logging/stop!)
      (logging/start! {:enabled true
                       :file {:path (.getAbsolutePath file)}})
      (logging/log! :agent.logging/secret-event
                    {:api-key "sk-test"
                     :nested {:authorization "Bearer token-value"
                              :safe "visible"}})
      (logging/log-error! :agent.logging/secret-error
                          (ex-info "boom"
                                   {:openai-key "sk-error"
                                    :headers {"Authorization" "Bearer error-token"}
                                    :message "Bearer inline-token"
                                    :safe "visible-error"}))
      (Thread/sleep 250)
      (logging/stop!)
      (Thread/sleep 150)
      (let [body (slurp file)]
        (is (str/includes? body "***REDACTED***"))
        (is (str/includes? body "visible"))
        (is (str/includes? body "visible-error"))
        (is (not (str/includes? body "sk-test")))
        (is (not (str/includes? body "sk-error")))
        (is (not (str/includes? body "token-value")))
        (is (not (str/includes? body "error-token")))
        (is (not (str/includes? body "inline-token"))))
      (finally
        (logging/stop!)
        (io/delete-file file true)
        (io/delete-file (io/file (str (.getAbsolutePath file) ".1")) true)
        (io/delete-file dir true)))))

(deftest rotates-existing-log-file-on-start
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "iris-logging-rotate-" (System/nanoTime)))
        file (io/file dir "agent.log")
        rotated (io/file dir "agent.log.1")]
    (.mkdirs dir)
    (spit file "0123456789")
    (try
      (logging/stop!)
      (logging/start! {:enabled true
                       :file {:path (.getAbsolutePath file)
                              :max-bytes 5
                              :max-files 2}})
      (logging/log! :agent.logging/after-rotate {:value 1})
      (Thread/sleep 250)
      (logging/stop!)
      (Thread/sleep 150)
      (is (.exists rotated))
      (is (= "0123456789" (slurp rotated)))
      (is (str/includes? (slurp file) "agent.logging/after-rotate"))
      (finally
        (logging/stop!)
        (io/delete-file file true)
        (io/delete-file rotated true)
        (io/delete-file (io/file dir "agent.log.2") true)
        (io/delete-file dir true)))))

(deftest otel-starts-trace-and-log-publishers
  (let [started (atom nil)]
    (with-redefs [mulog/start-publisher! (fn [cfg]
                                           (reset! started cfg)
                                           (fn [] :stopped))
                  mulog/stop-all-publishers! (fn [] :stopped)]
      (try
        (logging/stop!)
        (logging/start! {:otel {:enabled true
                                :url "http://collector:4318/"
                                :send [:traces :logs]}})
        (is (logging/otel-traces-enabled?))
        (is (= {:type :multi
                :publishers [{:type :open-telemetry
                              :send :traces
                              :url "http://collector:4318/"
                              :max-items 5000
                              :publish-delay 5000
                              :http-opts {:conn-timeout 2000
                                          :socket-timeout 2000}}
                             {:type :open-telemetry
                              :send :logs
                              :url "http://collector:4318/"
                              :max-items 5000
                              :publish-delay 5000
                              :http-opts {:conn-timeout 2000
                                          :socket-timeout 2000}}]}
               @started))
        (finally
          (logging/stop!))))))

(deftest span-writes-otel-trace-record
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "iris-logging-span-" (System/nanoTime)))
        file (io/file dir "agent.log")]
    (.mkdirs dir)
    (try
      (logging/stop!)
      (logging/start! {:enabled true
                       :file {:path (.getAbsolutePath file)}})
      (logging/span! :agent.logging/span-test
                     {:turn/id "11111111-2222-3333-4444-555555555555"
                      :duration-ms 12
                      :safe "visible"}
                     {:duration-ms 12
                      :success? true})
      (Thread/sleep 250)
      (logging/stop!)
      (Thread/sleep 150)
      (let [body (slurp file)]
        (is (str/includes? body ":agent.logging/span-test"))
        (is (str/includes? body ":mulog/duration"))
        (is (str/includes? body ":mulog/root-trace"))
        (is (str/includes? body "visible")))
      (finally
        (logging/stop!)
        (io/delete-file file true)
        (io/delete-file dir true)))))
