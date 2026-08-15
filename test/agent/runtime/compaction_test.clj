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
        (let [thresholds {:max-context-tokens 100
                          :reserve-output-tokens 10
                          :keep-recent-tokens 20}
              first-result (compaction/compact-session! store (:id session) thresholds)
              first-kept (get-in first-result [:plan :first-kept-entry-id])]
          (is (true? (:compacted? first-result)))
          (is (= {:compacted? false :reason :no-progress}
                 (compaction/compact-session! store (:id session) thresholds)))
          (dotimes [idx 8]
            (sqlite/append-entry! store (:id session) :message
                                  {:role "assistant"
                                   :content (str idx " " (apply str (repeat 100 "y")))}))
          (let [next-result (compaction/compact-session! store (:id session) thresholds)]
            (is (true? (:compacted? next-result)))
            (is (not= first-kept
                      (get-in next-result [:plan :first-kept-entry-id]))))
          (is (= 2 (count (filter #(= :compaction (:type %))
                                  (sqlite/list-entries store (:id session)))))))))))

(deftest split-turn-compaction-avoids-tool-result-start-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "split")]
        (sqlite/append-entry! store (:id session) {:id "a" :type :message :payload {:role "assistant" :content (apply str (repeat 120 "a"))}})
        (sqlite/append-entry! store (:id session) {:id "t" :type :message :payload {:role "tool" :content "tool-result"}})
        (sqlite/append-entry! store (:id session) {:id "u" :type :message :payload {:role "user" :content "next"}})
        (let [result (compaction/compact-session! store (:id session)
                                                  {:max-context-tokens 40
                                                   :reserve-output-tokens 1
                                                   :keep-recent-tokens 5})]
          (is (= "u" (get-in result [:plan :first-kept-entry-id]))))))))

(deftest branch-summary-from-common-ancestor-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "branch")]
        (sqlite/append-entry! store (:id session) {:id "a" :type :message :payload {:role "user" :content "root"}})
        (sqlite/append-entry! store (:id session) {:id "b" :type :message :payload {:role "assistant" :content "old"}})
        (sqlite/append-entry! store (:id session) {:id "c" :parent-id "a" :type :message :payload {:role "assistant" :content "new"}})
        (compaction/store-branch-summary! store (:id session) "b" "c")
        (let [entry (last (filter #(= :branch_summary (:type %))
                                  (sqlite/list-entries store (:id session))))]
          (is (= "a" (get-in entry [:payload :from-id])))
          (is (= "Branch switch from 1 old entries to 1 new entries."
                 (get-in entry [:payload :summary]))))))))

(deftest file-tracking-accumulation-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "files")]
        (sqlite/append-entry! store (:id session)
                              {:type :custom
                               :payload {:content (apply str (repeat 120 "x"))
                                         :details {:files-read ["a.clj"]
                                                   :files-touched ["b.clj"]}}})
        (sqlite/append-entry! store (:id session)
                              {:type :custom
                               :payload {:content (apply str (repeat 120 "y"))
                                         :metadata {:read-files ["c.clj"]
                                                    :touched-files ["d.clj"]}}})
        (sqlite/append-entry! store (:id session)
                              {:type :message
                               :payload {:role "user" :content "keep"}})
        (let [result (compaction/compact-session! store (:id session)
                                                  {:max-context-tokens 40
                                                   :reserve-output-tokens 1
                                                   :keep-recent-tokens 1})
              history (get-in result [:payload :details :file-history])]
          (is (= ["a.clj" "c.clj"] (:files-read history)))
          (is (= ["b.clj" "d.clj"] (:files-touched history))))))))
