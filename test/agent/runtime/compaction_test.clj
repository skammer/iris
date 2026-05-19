(ns agent.runtime.compaction-test
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.compaction :as compaction]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-compact-" ".db")))

(defn with-store [f]
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (f store)
      (finally
        (io/delete-file path true)))))

(deftest normal-compaction-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "compact")]
        (dotimes [idx 8]
          (sqlite/append-entry! store (:id session) :message {:role "user"
                                                              :content (apply str (repeat 80 (char (+ 65 idx))))}))
        (let [result (compaction/compact-session! store (:id session)
                                                  {:max-context-tokens 80
                                                   :reserve-output-tokens 10
                                                   :keep-recent-tokens 20
                                                   :max-summary-input-tokens 200})]
          (is (true? (:compacted? result)))
          (is (= :compaction (:type result)))
          (is (string? (get-in result [:payload :summary])))
          (is (pos? (get-in result [:payload :tokens-before]))))))))

(deftest repeated-compaction-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "repeat")]
        (dotimes [idx 10]
          (sqlite/append-entry! store (:id session) :message {:role "user"
                                                              :content (str idx " " (apply str (repeat 100 "x")))}))
        (is (true? (:compacted? (compaction/compact-session! store (:id session)
                                                             {:max-context-tokens 100
                                                              :reserve-output-tokens 10
                                                              :keep-recent-tokens 20}))))
        (is (true? (:compacted? (compaction/compact-session! store (:id session)
                                                             {:max-context-tokens 100
                                                              :reserve-output-tokens 10
                                                              :keep-recent-tokens 20}))))
        (is (<= 2 (count (filter #(= :compaction (:type %))
                                 (sqlite/list-entries store (:id session))))))))))

(deftest split-turn-compaction-avoids-tool-result-start-test
  (let [entries [{:id "a" :type :message :payload {:role "assistant" :content (apply str (repeat 120 "a"))}}
                 {:id "t" :type :message :payload {:role "tool" :content "tool-result"}}
                 {:id "u" :type :message :payload {:role "user" :content "next"}}]
        plan (compaction/prepare-compaction entries
                                            {:max-context-tokens 40
                                             :reserve-output-tokens 1
                                             :keep-recent-tokens 5})]
    (is (= "u" (:first-kept-entry-id plan)))))

(deftest branch-summary-from-common-ancestor-test
  (let [old-path [{:id "a" :type :message :payload {:content "root"}}
                  {:id "b" :type :message :payload {:content "old"}}]
        new-path [{:id "a" :type :message :payload {:content "root"}}
                  {:id "c" :type :message :payload {:content "new"}}]
        summary (compaction/branch-summary old-path new-path)]
    (is (= "a" (:from-id summary)))
    (is (= "Branch switch from 1 old entries to 1 new entries." (:summary summary)))))

(deftest file-tracking-accumulation-test
  (let [history (compaction/file-history
                 [{:payload {:details {:files-read ["a.clj"]
                                       :files-touched ["b.clj"]}}}
                  {:payload {:metadata {:read-files ["c.clj"]
                                        :touched-files ["d.clj"]}}}])]
    (is (= ["a.clj" "c.clj"] (:files-read history)))
    (is (= ["b.clj" "d.clj"] (:files-touched history)))))
