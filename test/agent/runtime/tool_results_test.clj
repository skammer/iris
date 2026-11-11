(ns agent.runtime.tool-results-test
  (:require [agent.chat.history :as history]
            [agent.persistence.sqlite :as sqlite]
            [agent.runtime.messages :as messages]
            [agent.runtime.context-pack :as pack]
            [agent.runtime.schema :as schema]
            [agent.tools.common.tool-results :as results]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- with-dir [f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                     "iris-results-test-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (f (.getPath dir))
         (finally (doseq [file (reverse (file-seq dir))] (io/delete-file file true))))))

(defn- receipt [text]
  {:status :ok :tool-name :inventory_list
   :result {:content [{:type "text" :text text}]}})

(deftest ordinary-json-is-complete-and-recoverable-after-compaction
  (with-dir
    (fn [dir]
      (let [text (json/generate-string {:locations (vec (repeat 66 {:id (str (java.util.UUID/randomUUID))
                                                                  :name "Полка" :description (apply str (repeat 120 "x"))}))})
            saved (results/preserve! dir "session-a" (receipt text))
            message (second (messages/tool-protocol-messages "request" ""
                             [{:id "call" :name "inventory_list" :arguments {}}] [saved]))]
        (is (< 12000 (count text) 32000))
        (is (= text (messages/tool-output-content saved)))
        (is (= 66 (count (:locations (json/parse-string (messages/tool-output-content saved) true)))))
        (is (= (:output-ref saved) (:output-ref (first (schema/normalize-content (:content message))))))
        (doseq [compact? [true false]]
          (let [output (#'pack/compact-tool-result-message message 100 compact?)]
            (is (str/includes? (get-in output [:content 0 :content]) (:output-ref saved)))
            (is (str/includes? (get-in output [:content 0 :content]) "tool_result_read"))))))))

(deftest oversized-results-round-trip-through-pages-with-session-isolation
  (with-dir
    (fn [dir]
      (let [text (apply str (repeat 12000 "Ж\"\\😀"))
            saved (results/preserve! dir "session-a" (receipt text))
            id (:output-ref saved)
            read-page #(results/read-result dir "session-a" {:result_id id :offset % :limit 1777})
            restored (loop [offset 0 chunks []]
                       (let [page (read-page offset) chunks (conj chunks (:content page))]
                         (if (:eof page) (apply str chunks) (recur (:next_offset page) chunks))))]
        (is (= text restored))
        (is (str/includes? (messages/tool-output-content saved) id))
        (is (< (count (messages/tool-output-content saved)) 3000))
        (is (thrown? Exception (results/read-result dir "session-b" {:result_id id})))
        (is (thrown? Exception (results/read-result dir "session-a" {:result_id "../secret"})))
        (is (thrown? Exception (results/read-result dir "session-a" {:result_id id :offset -1})))
        (is (thrown? Exception (results/read-result dir "session-a" {:result_id id :limit 24001})))
        (is (:eof (read-page (count text))))))))

(deftest storage-failure-does-not-discard-output
  (with-dir
    (fn [dir]
      (let [file (io/file dir "not-a-directory")
            text (apply str (repeat 40000 "x"))]
        (spit file "blocked")
        (let [saved (results/preserve! (.getPath file) "session" (receipt text))]
          (is (nil? (:output-ref saved)))
          (is (= text (messages/tool-output-content saved))))))))

(deftest unicode-page-boundaries-remain-encodable
  (with-dir
    (fn [dir]
      (let [text (apply str (repeat 1001 "😀"))
            saved (results/preserve! dir "session" (receipt text))
            page (results/read-result dir "session" {:result_id (:output-ref saved) :limit 1})]
        (is (= "😀" (:content page)))
        (is (= 2 (:next_offset page)))
        (is (= (:content page) (String. (.getBytes ^String (:content page) "UTF-8") "UTF-8")))))))

(deftest reloaded-legacy-history-is-not-cut-at-8000
  (let [text (apply str (repeat 12377 "x"))]
    (with-redefs [sqlite/current-llm-context
                  (fn [& _] [{:role "tool" :content text :tool-call-id "call"}])]
      (is (= text (get-in (history/session-messages {} "session") [0 :content 0 :content]))))))
