(ns agent.restart-handoff-test
  (:require
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.restart-handoff :as restart-handoff]
   [agent.test.chat-harness :as harness]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(defn- temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-handoff-" ".db")))

(defn- wait-for-status [store handoff-id expected]
  (loop [remaining 100]
    (let [handoff (sqlite/get-restart-handoff store handoff-id)]
      (cond
        (= expected (:status handoff)) handoff
        (zero? remaining) handoff
        :else (do
                (Thread/sleep 10)
                (recur (dec remaining)))))))

(deftest dispatch-pending-runs-persisted-message-as-next-turn-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "handoff")
        handoff (restart-handoff/schedule!
                 {:store store}
                 {:session-id (:id session)
                  :message "check service health"
                  :permission-profile :admin})
        turn-opts (promise)]
    (try
      (with-redefs [chat/run! (fn [_ opts]
                                (deliver turn-opts opts)
                                {:content "healthy"})]
        (is (= 1 (restart-handoff/dispatch-pending! {:store store})))
        (let [opts (deref turn-opts 1000 ::timeout)
              completed (wait-for-status store (:id handoff) :succeeded)
              [message] (sqlite/list-messages store (:id session))]
          (is (not= ::timeout opts))
          (is (= [{:role "user" :content "check service health"}]
                 (:messages opts)))
          (is (false? (:persist-user? opts)))
          (is (= :admin (:permission-profile opts)))
          (is (= (:id handoff) (get-in opts [:context :restart-handoff-id])))
          (is (= (:id handoff) (get-in message [:metadata :restart-handoff-id])))
          (is (= :succeeded (:status completed)))
          (is (zero? (restart-handoff/dispatch-pending! {:store store})))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest running-handoff-is-reclaimed-after-another-restart-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "reclaim")
        handoff (restart-handoff/schedule!
                 {:store store}
                 {:session-id (:id session) :message "continue"})]
    (try
      (is (= 1 (:attempts (first (sqlite/claim-restart-handoffs! store)))))
      (with-redefs [chat/run! (fn [_ _] {:content "done"})]
        (is (= 1 (restart-handoff/dispatch-pending! {:store store})))
        (let [completed (wait-for-status store (:id handoff) :succeeded)]
          (is (= :succeeded (:status completed)))
          (is (= 2 (:attempts completed)))
          (is (= 1 (sqlite/count-messages store (:id session))))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest automatic-handoff-runs-through-real-chat-queue-test
  (let [h (harness/start!)]
    (try
      (let [session-id (harness/create-session! h "restart integration")
            handoff (restart-handoff/schedule!
                     (:system h)
                     {:session-id session-id
                      :message "verify after restart"})]
        (is (= 1 (restart-handoff/dispatch-pending! (:system h))))
        (let [completed (wait-for-status (:store h) (:id handoff) :succeeded)
              messages (harness/list-messages h session-id)]
          (is (= :succeeded (:status completed)))
          (is (= ["user" "assistant"] (mapv :role messages)))
          (is (= "verify after restart" (:content (first messages))))))
      (finally
        (harness/stop! h)))))
