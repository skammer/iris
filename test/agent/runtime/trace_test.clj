(ns agent.runtime.trace-test
  (:require
   [agent.logging :as logging]
   [agent.runtime.trace :as trace]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-trace-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest none-mode-does-not-write
  (let [dir (temp-dir)
        path (str (io/file dir "trace.jsonl"))
        runtime-trace (trace/create-trace {:mode :none :path path} (.getPath dir))]
    (try
      (trace/record-event! runtime-trace {:event-type :llm.call
                                          :payload {:model "m"}})
      (is (false? (.exists (io/file path))))
      (finally
        (io/delete-file dir true)))))

(deftest otel-export-does-not-require-local-trace-file
  (let [dir (temp-dir)
        path (str (io/file dir "trace.jsonl"))
        runtime-trace (trace/create-trace {:mode :none :path path} (.getPath dir))
        spans (atom [])]
    (try
      (with-redefs [logging/otel-traces-enabled? (constantly true)
                    logging/span! (fn [event-name attrs opts]
                                    (swap! spans conj {:event-name event-name
                                                       :attrs attrs
                                                       :opts opts}))]
        (trace/record-event! runtime-trace {:event-type :llm.call
                                            :turn-id "11111111-2222-3333-4444-555555555555"
                                            :provider :openrouter
                                            :model "model"
                                            :success true
                                            :payload {:duration-ms 42
                                                      :messages ["secret"]}}))
      (is (false? (.exists (io/file path))))
      (is (= :agent.trace/llm.call (-> @spans first :event-name)))
      (is (= 42 (-> @spans first :opts :duration-ms)))
      (is (= "[redacted]" (-> @spans first :attrs :payload :messages)))
      (finally
        (io/delete-file dir true)))))

(deftest rolling-mode-trims-to-max-entries
  (let [dir (temp-dir)
        runtime-trace (trace/create-trace {:mode :rolling
                                           :path "trace.jsonl"
                                           :rolling-max-entries 2}
                                          (.getPath dir))]
    (try
      (doseq [i (range 5)]
        (trace/record-event! runtime-trace {:event-type :tool.call
                                            :payload {:i i}}))
      (let [events (trace/load-events runtime-trace {:limit 10})]
        (is (= 2 (count events)))
        (is (= [4 3] (mapv #(get-in % [:payload :i]) events))))
      (finally
        (io/delete-file dir true)))))

(deftest load-events-skips-malformed-lines
  (let [dir (temp-dir)
        runtime-trace (trace/create-trace {:mode :rolling :path "trace.jsonl"} (.getPath dir))]
    (try
      (spit (:path runtime-trace)
            "not-json\n{\"event-type\":\"llm.call\",\"payload\":{\"ok\":true}}\n")
      (is (= [true] (mapv #(get-in % [:payload :ok])
                          (trace/load-events runtime-trace {:limit 10}))))
      (finally
        (io/delete-file dir true)))))

(deftest relative-path-resolves-under-base-dir
  (let [dir (temp-dir)
        runtime-trace (trace/create-trace {:mode :rolling :path "state/trace.jsonl"} (.getPath dir))]
    (try
      (is (= (.getAbsolutePath (io/file dir "state" "trace.jsonl"))
             (:path runtime-trace)))
      (finally
        (io/delete-file dir true)))))

(deftest concurrent-writes-remain-jsonl-and-rolling-capped
  (let [dir (temp-dir)
        runtime-trace (trace/create-trace {:mode :rolling
                                           :path "trace.jsonl"
                                           :rolling-max-entries 25}
                                          (.getPath dir))]
    (try
      (doseq [f (doall
                 (for [i (range 80)]
                   (future
                     (trace/record-event! runtime-trace {:event-type :tool.call
                                                         :payload {:i i}}))))]
        @f)
      (let [events (trace/load-events runtime-trace {:limit 100})]
        (is (= 25 (count events)))
        (is (= 25 (count (distinct (map :id events))))))
      (finally
        (io/delete-file dir true)))))
