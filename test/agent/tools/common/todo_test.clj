(ns agent.tools.common.todo-test
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.common.todo :as todo-tool]
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn- temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-todo-tool-" ".db")))

(defn- registry [store]
  (reduce tools/register-tool
          (tools/create-registry)
          (todo-tool/create-todo-tools store)))

(defn- item
  ([content description]
   (item content description "pending" "medium"))
  ([content description status priority]
   {:content content
    :description description
    :status status
    :priority priority}))

(deftest todo-tool-write-read-and-search-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        registry* (registry store)]
    (try
      (let [written (tools/execute-tool registry*
                                        :todo_write
                                        {:description "Current thread work"
                                         :todos [(item "Wire todo tool" "Searchable implementation note" :in_progress :high)]
                                         :metadata {:kind "plan"}}
                                        {:permissions #{:todo-write}
                                         :session-id "thread-1"})
            read-back (tools/execute-tool registry*
                                          :todo_get
                                          {}
                                          {:permissions #{:todo-read}
                                           :session-id "thread-1"})
            search-result (tools/execute-tool registry*
                                              :todo_search
                                              {:query "implementation note"}
                                              {:permissions #{:todo-read}
                                               :session-id "thread-1"})]
        (is (:created? written))
        (is (= "thread-1" (:thread-id written)))
        (is (= "default" (:slug written)))
        (is (= "Searchable implementation note" (get-in read-back [:todos 0 :description])))
        (is (= 1 (:count search-result)))
        (is (= (:id written) (get-in search-result [:lists 0 :id]))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest todo-tool-updates-current-thread-and-all-threads-opt-in-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        registry* (registry store)]
    (try
      (let [first-write (tools/execute-tool registry*
                                            :todo_write
                                            {:todos [(item "First" "")]}
                                            {:permissions #{:todo-write}
                                             :session-id "thread-1"})
            _ (Thread/sleep 2)
            second-write (tools/execute-tool registry*
                                             :todo_write
                                             {:todos [(item "First updated" "same row")]}
                                             {:permissions #{:todo-write}
                                              :session-id "thread-1"})
            _ (tools/execute-tool registry*
                                  :todo_write
                                  {:todos [(item "Second" "other thread")]}
                                  {:permissions #{:todo-write}
                                   :session-id "thread-2"})
            current-lists (tools/execute-tool registry*
                                              :todo_list
                                              {}
                                              {:permissions #{:todo-read}
                                               :session-id "thread-1"})
            all-lists (tools/execute-tool registry*
                                          :todo_list
                                          {:all-threads? true}
                                          {:permissions #{:todo-read}})]
        (is (= (:id first-write) (:id second-write)))
        (is (not= (:updated-at first-write) (:updated-at second-write)))
        (is (= ["thread-1"] (mapv :thread-id (:lists current-lists))))
        (is (= #{"thread-1" "thread-2"} (set (map :thread-id (:lists all-lists))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest todo-tool-permissions-schema-and-metadata-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        write-tool (todo-tool/create-todo-write-tool store)
        search-tool (todo-tool/create-todo-search-tool store)
        registry* (reduce tools/register-tool
                          (tools/create-registry)
                          (todo-tool/create-todo-tools store))
        write-description (tools/describe write-tool)
        search-description (tools/describe search-tool)]
    (try
      (is (tools/read-only-call? search-description {:query "x"}))
      (is (not (tools/read-only-call? write-description {:todos []})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Insufficient permissions"
                            (tools/execute-tool registry*
                                                :todo_write
                                                {:todos [(item "Denied" "")]}
                                                {:permissions #{:todo-read}
                                                 :session-id "thread-1"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"input failed schema validation"
                            (tools/execute-tool registry*
                                                :todo_write
                                                {:todos [{:content "Missing description"}]}
                                                {:permissions #{:todo-write}
                                                 :session-id "thread-1"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"thread-id is required"
                            (tools/execute-tool registry*
                                                :todo_list
                                                {}
                                                {:permissions #{:todo-read}})))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))
