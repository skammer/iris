(ns agent.persistence.session-entries-test
  (:require
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-entries-" ".db")))

(defn with-store [f]
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (f store)
      (finally
        (io/delete-file path true)))))

(deftest append-entry-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "entries")
            entry (sqlite/append-entry! store (:id session) :custom {:x 1})]
        (is (= :custom (:type entry)))
        (is (= {:x 1} (:payload entry)))
        (is (= [(:id entry)] (mapv :id (sqlite/branch-path store (:id session)))))))))

(deftest branch-from-prior-entry-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "branch")
            a (sqlite/append-entry! store (:id session) :custom {:n 1})
            b (sqlite/append-entry! store (:id session) :custom {:n 2})
            c (sqlite/append-entry! store (:id session)
                                    {:type :custom
                                     :parent-id (:id a)
                                     :payload {:n 3}})]
        (is (= [(:id a) (:id c)]
               (mapv :id (sqlite/branch-path store (:id session)))))
        (is (= (:id b) (:id (sqlite/select-leaf! store (:id session) (:id b)))))
        (is (= [(:id a) (:id b)]
               (mapv :id (sqlite/branch-path store (:id session)))))))))

(deftest read-current-path-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "path")
            a (sqlite/append-entry! store (:id session) :message {:role "user" :content "hi"})
            b (sqlite/append-entry! store (:id session) :message {:role "assistant" :content "ok"})]
        (is (= [(:id a) (:id b)]
               (mapv :id (sqlite/branch-path store (:id session)))))
        (is (= [{:role "user" :content "hi"}
                {:role "assistant" :content "ok"}]
               (sqlite/current-llm-context store (:id session))))))))

(deftest labels-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "labels")
            a (sqlite/append-entry! store (:id session) :custom {:n 1})
            _ (sqlite/append-entry! store (:id session)
                                    {:type :label
                                     :payload {:target-id (:id a)
                                               :label "keep"}})
            tree (sqlite/session-tree store (:id session))]
        (is (= "keep" (:label (first tree))))))))

(deftest custom-entries-ignored-custom-messages-included-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "ctx")]
        (sqlite/append-entry! store (:id session) :message {:role "user" :content "visible"})
        (sqlite/append-entry! store (:id session) :custom {:content "hidden"})
        (sqlite/append-entry! store (:id session) :custom_message {:content "injected"})
        (is (= [{:role "user" :content "visible"}
                {:role "user" :content "injected"}]
               (sqlite/current-llm-context store (:id session))))))))

(deftest linear-messages-migrate-to-entry-chain-test
  (with-store
    (fn [store]
      (let [session (sqlite/create-session! store "legacy")
            m1 (sqlite/append-message! store (:id session) "user" "one")
            m2 (sqlite/append-message! store (:id session) "assistant" "two")
            entries (sqlite/list-entries store (:id session))]
        (is (= 2 (count entries)))
        (is (= ["one" "two"] (mapv #(get-in % [:payload :content]) entries)))
        (is (= [(:id m1) (:id m2)] (mapv #(get-in % [:payload :message-id]) entries)))))))
