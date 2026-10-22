(ns benchmark-branch-path
  (:require [agent.persistence.sqlite :as sqlite]
         [agent.persistence.sqlite.common :as common]
         [agent.persistence.sqlite.sessions.entries :as entries]
         [agent.test-runner :as runner]
         [clojure.java.io :as io]))

(defn legacy-branch [store sid leaf]
  (let [by-id (into {} (map (juxt :id identity)) (entries/list-entries store sid))]
    (loop [entry (get by-id leaf) path ()]
      (if entry (recur (get by-id (:parent-id entry)) (conj path entry)) (vec path)))))

(defn sample [f]
  (let [decoded (atom 0) original entries/row->entry started (System/nanoTime)
        result (with-redefs [entries/row->entry (fn [row] (swap! decoded inc) (original row))] (f))]
    {:ms (/ (- (System/nanoTime) started) 1e6) :decoded @decoded :result result}))

(runner/with-isolated-home
 (fn []
   (let [file (java.io.File/createTempFile "iris-branch-bench-" ".db")
         store (sqlite/create-store {:path (.getPath file)})]
     (try
       (doseq [branches [1 10]]
         (let [sid (:id (sqlite/create-session! store "branch benchmark"))
               depth 500
               key-of (fn [branch i] (str sid "-" branch "-" i))]
           (common/with-transaction
            store (fn [conn]
                    (doseq [branch (range branches) i (range depth)]
                      (entries/insert-entry-row!
                       conn {:id (key-of branch i) :session-id sid
                             :parent-id (if (pos? i) (key-of branch (dec i))
                                          (when (pos? branch) (key-of 0 0)))
                             :type :message :payload {:role "user" :content (apply str (repeat 600 "x"))}}))))
           (let [leaf (key-of 0 (dec depth))
                 old #(legacy-branch store sid leaf)
                 current #(entries/branch-path store sid leaf)]
             (assert (= (old) (current)))
             (dotimes [_ 2] (old) (current))
             (doseq [[label f] [[:legacy old] [:current current]]]
               (let [samples (repeatedly 5 #(sample f))
                     ordered (sort-by :ms samples)]
                 (prn {:branches branches :depth depth :algorithm label
                       :median-ms (:ms (nth ordered 2)) :decoded (:decoded (first ordered))}))))))
       (finally (sqlite/close-store! store) (io/delete-file file true))))))
(shutdown-agents)
